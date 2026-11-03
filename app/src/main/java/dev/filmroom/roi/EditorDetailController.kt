package dev.filmroom.roi

import dev.filmroom.engine.*
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*

/** Immutable source/parameter generation is independent of viewport revisions. */
interface EditorDetailSession : AutoCloseable {
    val key: RenderKey
    val regionOutput:Boolean get()=false
    val scientificBackend:DetailActualBackend? get()=null
    fun onRetired(listener: () -> Unit) {}
    suspend fun awaitRetired() {}
    suspend fun tile(x: Int, y: Int): TileResult
    suspend fun region(rect:PixelRect,cancelled:()->Boolean):TileResult = throw UnsupportedOperationException("Region provider required")
}
fun interface EditorDetailProvider {
    suspend fun open(source: PhotoSource, parameters: EditorState): EditorDetailSession
    suspend fun openLegacy(source:PhotoSource,parameters:EditorState):EditorDetailSession = open(source,parameters)
}
class RealEditorDetailProvider(private val provider: OwnedDetailProvider) : EditorDetailProvider {
    override suspend fun open(source: PhotoSource, parameters: EditorState): EditorDetailSession {
        val preferRegion=withContext(Dispatchers.IO){provider.supportsRegions()}
        return openWithPolicy(source,parameters,preferRegion)
    }
    override suspend fun openLegacy(source:PhotoSource,parameters:EditorState):EditorDetailSession = openWithPolicy(source,parameters,false)
    private suspend fun openWithPolicy(source:PhotoSource,parameters:EditorState,preferRegion:Boolean):EditorDetailSession {
        val session = provider.open(source, parameters, 1536,preferRegion=preferRegion)
        try { return object : EditorDetailSession {
            override val regionOutput=session.regionOutput
            override val scientificBackend=if(session.key.scienceExperimental)session.actualBackend else null
            override val key = session.key.toRenderKey(regionOutput)
            override suspend fun tile(x: Int, y: Int): TileResult {
                val tile = session.tile(x,y)
                try { return TileResult(tile.key.toRenderKey(regionOutput), tile.core.toPixels(), tile.sample.toPixels(), tile.bitmap, if(tile.key.scienceExperimental)TileQuality.ExperimentalSameWorkingGrid else TileQuality.Exact) }
                catch(t:Throwable) { if(!tile.bitmap.isRecycled)tile.bitmap.recycle();throw t }
            }
            override suspend fun region(rect:PixelRect,cancelled:()->Boolean):TileResult {
                val tile=session.region(DetailRect(rect.left,rect.top,rect.width,rect.height),cancelled)
                try{return TileResult(tile.key.toRenderKey(regionOutput),tile.core.toPixels(),tile.sample.toPixels(),tile.bitmap,if(tile.key.scienceExperimental)TileQuality.ExperimentalSameWorkingGrid else TileQuality.Exact)}
                catch(t:Throwable){if(!tile.bitmap.isRecycled)tile.bitmap.recycle();throw t}
            }
            override fun onRetired(listener: () -> Unit) = session.setRetirementListener(listener)
            override suspend fun awaitRetired() = session.awaitRetired()
            override fun close() = session.close()
        } } catch(t:Throwable) {
            withContext(NonCancellable+Dispatchers.IO){session.close();if(!session.key.scienceExperimental)session.awaitRetired()}
            throw t
        }
    }
}
private fun DetailKey.toRenderKey(region:Boolean) = RenderKey(source,parameters,
    if(scienceExperimental){if(region)SCIENCE_REGION_ALGORITHM else SCIENCE_GRID_ALGORITHM}
    else if(region)"owned-roi-api-v2-visible-region-cap1536" else "owned-roi-api-v1-grid256-cap1536",data,mapping,width,height)
private fun DetailRect.toPixels() = PixelRect(x,y,x+width,y+height)

/** Main-confined editor owner. Native context creation and retirement never run on main.
 * No source replacement, export input, or global engine cancellation here.
 */
class EditorDetailController(
    private val provider: EditorDetailProvider,
    private val viewport: Viewport,
    private val changed: () -> Unit,
    private val settleMillis: Long = 180,
    private val visibleRegion: (RenderKey)->PixelRect? = { viewport.visibleRect(it) },
    private val effectiveZoom: ()->Double = { viewport.zoom },
    private val retainContextWhileEditing: ()->Boolean = { false },
    private val retryIdle: ()->Boolean = { NativeWorkReservations.detailRetryIdle() },
    private val retryDelays: List<Long> = listOf(5_000L,20_000L),
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
    private var scheduler: DetailOutputScheduler? = null
    private var failedGeneration = -1L
    private var fallbackReason:DetailFailure?=null
    private var retryJob:Job?=null
    private var retryAttempts=0
    private var forceLegacy=false
    private val retirementJobs=mutableListOf<Job>()
    val status:DetailStatus get() {
        if(!foreground || disposed)return DetailStatus(DetailPhase.Paused)
        if(comparing)return DetailStatus(DetailPhase.Comparing)
        if(!allowed || source==null)return DetailStatus(DetailPhase.WaitingForPreview)
        if(effectiveZoom()<1.15)return DetailStatus(DetailPhase.ZoomRequired)
        if(failedGeneration==generation)return DetailStatus(DetailPhase.Fallback,failure=fallbackReason ?: DetailFailure.RenderFailed)
        val active=session
        if(active!=null){
            val ready=scheduler?.drawableTiles()?.size ?: 0
            val phase=if(scheduler?.hasDemand==false || (scheduler?.ready==true && scheduler?.runningCount==0))DetailPhase.Ready else DetailPhase.Refining
            return DetailStatus(phase,active.key.virtualFullWidth,active.key.virtualFullHeight,ready,scientificBackend=active.scientificBackend)
        }
        return DetailStatus(if(openingStarted)DetailPhase.Preparing else DetailPhase.Settling)
    }
    val key get() = if(comparing)null else session?.key
    val cacheBytes get() = scheduler?.cacheBytes ?: 0L
    val viewportRevision get() = scheduler?.viewportRevision ?: 0L
    fun bind(photo: PhotoSource?, params: EditorState, eligible: Boolean, compareOriginal:Boolean=false) {
        if (disposed) return
        if (source !== photo || parameters != params || allowed != eligible) {
            if(source !== photo || parameters != params){retryAttempts=0;forceLegacy=false}
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
            active.key?.let { active.update(it,visibleRegion(it)) }
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
                if(forceLegacy)while(!retirementReady()){retirementJobs.toList().forEach{it.join()}}
                ensureActive()
                openingStarted=true;changed()
                opened=if(forceLegacy)provider.openLegacy(photo,params) else provider.open(photo,params)
                ensureActive()
                if (disposed || request != generation || !foreground || !allowed) return@launch
                val adopted=opened
                require(maxOf(adopted.key.virtualFullWidth,adopted.key.virtualFullHeight)<=1536)
                session=adopted; opened=null
                val created=DetailOutputScheduler(TileProvider { k, rect, cancelled ->
                    if (cancelled()) throw CancellationException("Retired detail")
                    check(k == adopted.key)
                    runBlocking { if(adopted.regionOutput)adopted.region(rect,cancelled) else adopted.tile(rect.left/256,rect.top/256) }
                },regionOutput=adopted.regionOutput,onChanged={
                    // A provider failure (including native pressure retirement) releases idle ownership.
                    if (scheduler?.lastError != null) {
                        val failure=scheduler?.lastFailure
                        if(session?.regionOutput==true && failure is NativeWorkUnavailable)forceLegacy=true
                        fallbackReason=detailFailure(failure);failedGeneration=generation;retire(resetFailure=false)
                    }
                    changed()
                })
                scheduler=created
                created.setPaused(latestMoving || comparing || (effectiveZoom()<1.15 && retainContextWhileEditing()))
                created.update(adopted.key,visibleRegion(adopted.key))
                adopted.onRetired {
                    scope.launch {
                        if(session === adopted) { fallbackReason=DetailFailure.MemoryPressure;retire(resetFailure=false) }
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
    fun releaseForPressure() { fallbackReason=DetailFailure.MemoryPressure;retire(resetFailure=false) }
    private fun closeOffMain(value: EditorDetailSession) {
        retirementJobs.removeAll{it.isCompleted}
        retirementJobs+=retirement.launch { runCatching { try{value.close()}finally{value.awaitRetired()} } }
    }
    private fun retirementReady():Boolean {retirementJobs.removeAll{it.isCompleted};return retirementJobs.isEmpty()}
    private fun retire(resetFailure: Boolean = true) {
        retryJob?.cancel();retryJob=null
        generation++
        val retiringOpen=opening
        retiringOpen?.cancel()
        if(retiringOpen!=null && !retiringOpen.isCompleted)retirementJobs+=retiringOpen
        opening=null;openingStarted=false
        scheduler?.close(); scheduler=null
        session?.let(::closeOffMain); session=null
        if(resetFailure) {failedGeneration=-1;fallbackReason=null} else failedGeneration=generation
        changed()
        if(!resetFailure)scheduleRecovery()
    }
    private fun scheduleRecovery() {
        if(disposed || retryAttempts>=retryDelays.size || fallbackReason !in setOf(DetailFailure.MemoryAdmission,DetailFailure.MemoryPressure))return
        val request=generation
        retryJob=scope.launch {
            delay(retryDelays[retryAttempts])
            // At most two attempts per science binding; movement does not replenish
            // this budget. Wait quietly up to two minutes for competing work.
            var waited=0L
            while(request==generation && !disposed && waited<120_000L){
                if(foreground && allowed && !comparing && !latestMoving && effectiveZoom()>=1.15 && retirementReady() && retryIdle()){
                    retryAttempts++;failedGeneration=-1;fallbackReason=null;retryJob=null
                    demand(false);changed();return@launch
                }
                delay(1_000L);waited+=1_000L
            }
            retryJob=null
        }
    }
    override fun close() { if(disposed)return; disposed=true; retire(); scope.cancel() }
}
