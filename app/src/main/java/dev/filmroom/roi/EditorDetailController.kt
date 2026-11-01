package dev.filmroom.roi

import dev.filmroom.engine.*
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*

/** Immutable source/parameter generation is independent of viewport revisions. */
interface EditorDetailSession : AutoCloseable {
    val key: RenderKey
    fun onRetired(listener: () -> Unit) {}
    suspend fun tile(x: Int, y: Int): TileResult
}
fun interface EditorDetailProvider {
    suspend fun open(source: PhotoSource, parameters: EditorState): EditorDetailSession
}
class RealEditorDetailProvider(private val provider: OwnedDetailProvider) : EditorDetailProvider {
    override suspend fun open(source: PhotoSource, parameters: EditorState): EditorDetailSession {
        val session = provider.open(source, parameters, 1536)
        try { return object : EditorDetailSession {
            override val key = session.key.toRenderKey()
            override suspend fun tile(x: Int, y: Int): TileResult {
                val tile = session.tile(x,y)
                try { return TileResult(tile.key.toRenderKey(), tile.core.toPixels(), tile.sample.toPixels(), tile.bitmap, TileQuality.Exact) }
                catch(t:Throwable) { if(!tile.bitmap.isRecycled)tile.bitmap.recycle();throw t }
            }
            override fun onRetired(listener: () -> Unit) = session.setRetirementListener(listener)
            override fun close() = session.close()
        } } catch(t:Throwable) {
            withContext(NonCancellable+Dispatchers.IO){session.close()}
            throw t
        }
    }
}
private fun DetailKey.toRenderKey() = RenderKey(source,parameters,"owned-roi-api-v1-grid256-cap1536",data,mapping,width,height)
private fun DetailRect.toPixels() = PixelRect(x,y,x+width,y+height)

/** Main-confined editor owner. Native context creation and retirement never run on main.
 * No rendered full frame, source replacement, export input, or global engine cancellation here.
 */
class EditorDetailController(
    private val provider: EditorDetailProvider,
    private val viewport: Viewport,
    private val changed: () -> Unit,
    private val settleMillis: Long = 180,
    private val visibleTiles: (RenderKey)->List<TileId> = { viewport.visibleTiles(it) },
    private val effectiveZoom: ()->Double = { viewport.zoom },
    private val retainContextWhileEditing: ()->Boolean = { false },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val retirement = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var source: PhotoSource? = null
    private var parameters: EditorState? = null
    private var allowed = false
    private var comparing = false
    private var foreground = true
    private var disposed = false
    private var generation = 0L
    private var opening: Job? = null
    private var openingStarted = false
    private var latestMoving = false
    private var session: EditorDetailSession? = null
    private var scheduler: TileScheduler? = null
    private var failedGeneration = -1L
    private var fallbackReason:DetailFailure?=null
    val status:DetailStatus get() {
        if(!foreground || disposed)return DetailStatus(DetailPhase.Paused)
        if(comparing)return DetailStatus(DetailPhase.Comparing)
        if(!allowed || source==null)return DetailStatus(DetailPhase.WaitingForPreview)
        if(effectiveZoom()<1.15)return DetailStatus(DetailPhase.ZoomRequired)
        if(failedGeneration==generation)return DetailStatus(DetailPhase.Fallback,failure=fallbackReason ?: DetailFailure.RenderFailed)
        val active=session
        if(active!=null){
            val ready=scheduler?.drawableTiles()?.size ?: 0
            val pending=scheduler?.admittedTiles()?.size ?: 0
            val phase=if(ready>=pending && ready>0 && scheduler?.runningCount==0)DetailPhase.Ready else DetailPhase.Refining
            return DetailStatus(phase,active.key.virtualFullWidth,active.key.virtualFullHeight,ready)
        }
        return DetailStatus(if(openingStarted)DetailPhase.Preparing else DetailPhase.Settling)
    }
    val key get() = if(comparing)null else session?.key
    val cacheBytes get() = scheduler?.cacheBytes ?: 0L
    val viewportRevision get() = scheduler?.viewportRevision ?: 0L
    fun bind(photo: PhotoSource?, params: EditorState, eligible: Boolean, compareOriginal:Boolean=false) {
        if (disposed) return
        if (source !== photo || parameters != params || allowed != eligible) {
            retire(); source=photo; parameters=params; allowed=eligible
        }
        comparing=compareOriginal
        demand(latestMoving);changed()
    }
    fun foreground(value: Boolean) {
        if (foreground == value || disposed) return
        foreground=value
        if(!value)latestMoving=false
        if (!value) retire() else demand(false)
    }
    fun demand(moving: Boolean) {
        latestMoving=moving
        if (disposed) return
        if (!allowed || !foreground || source == null) {
            if (session != null || opening != null) retire()
            return
        }
        if(effectiveZoom() < 1.15) {
            if(retainContextWhileEditing()) {
                // The full-source crop editor temporarily zooms out. Keep same-science ownership,
                // but admit no new tiles/context builds while that editor does not need detail.
                scheduler?.setPaused(true)
                if(!openingStarted){opening?.cancel();opening=null}
            } else if(session!=null || opening!=null)retire()
            return
        }
        if(comparing) {
            scheduler?.setPaused(true)
            // Keep an already-running same-key context build/result, but do not start work.
            if(!openingStarted){opening?.cancel();opening=null}
            changed();return
        }
        scheduler?.let { active ->
            active.setPaused(true)
            active.key?.let { active.update(it,visibleTiles(it)) }
            active.setPaused(moving)
            return
        }
        // Once native context construction starts, same-key pans only update demand.
        // Cancel/restart the settle timer, never a valid in-progress build for viewport motion.
        if(opening != null && openingStarted) return
        opening?.cancel(); opening=null
        if (moving || failedGeneration == generation) return
        val photo=source ?: return; val params=parameters ?: return; val request=generation
        opening=scope.launch {
            var opened: EditorDetailSession? = null
            try {
                delay(settleMillis)
                openingStarted=true;changed()
                opened=provider.open(photo,params)
                ensureActive()
                if (disposed || request != generation || !foreground || !allowed) return@launch
                val adopted=opened
                require(maxOf(adopted.key.virtualFullWidth,adopted.key.virtualFullHeight)<=1536)
                session=adopted; opened=null
                scheduler=TileScheduler(TileProvider { k, rect, cancelled ->
                    if (cancelled()) throw CancellationException("Retired detail")
                    check(k == adopted.key)
                    runBlocking { adopted.tile(rect.left/256,rect.top/256) }
                },onChanged={
                    // A provider failure (including native pressure retirement) releases idle ownership.
                    if (scheduler?.lastError != null) { fallbackReason=detailFailure(scheduler?.lastFailure);failedGeneration=generation; retire(resetFailure=false) }
                    changed()
                }).also { it.setPaused(latestMoving || comparing || (effectiveZoom()<1.15 && retainContextWhileEditing()));it.update(adopted.key,visibleTiles(adopted.key)) }
                adopted.onRetired {
                    scope.launch {
                        if(session === adopted) { fallbackReason=DetailFailure.MemoryBudget;retire(resetFailure=false) }
                    }
                }
                changed()
            } catch(c: CancellationException) { throw c }
            catch(e: Exception) { if(request==generation) {fallbackReason=detailFailure(e);retire(resetFailure=false)} }
            catch(e: LinkageError) { if(request==generation) {fallbackReason=detailFailure(e);retire(resetFailure=false)} }
            catch(e: OutOfMemoryError) { if(request==generation) {fallbackReason=detailFailure(e);retire(resetFailure=false)} }
            finally { opened?.let(::closeOffMain); if(opening === currentCoroutineContext()[Job]) {opening=null;openingStarted=false} }
        }
    }
    fun forEachDrawable(draw: (TileResult)->Unit) { if(!comparing)scheduler?.forEachDrawable(draw) }
    fun releaseForPressure() { fallbackReason=DetailFailure.MemoryBudget;retire(resetFailure=false) }
    private fun closeOffMain(value: EditorDetailSession) { retirement.launch { runCatching { value.close() } } }
    private fun retire(resetFailure: Boolean = true) {
        generation++; opening?.cancel(); opening=null; openingStarted=false
        scheduler?.close(); scheduler=null
        session?.let(::closeOffMain); session=null
        if(resetFailure) {failedGeneration=-1;fallbackReason=null} else failedGeneration=generation
        changed()
    }
    override fun close() { if(disposed)return; disposed=true; retire(); scope.cancel() }
}
