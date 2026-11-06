package dev.filmroom.roi

import dev.filmroom.engine.*
import dev.filmroom.diagnostics.*
import kotlin.coroutines.EmptyCoroutineContext
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*

/** Immutable source/parameter generation is independent of viewport revisions. */
interface EditorDetailSession : AutoCloseable {
    val key: RenderKey
    val regionOutput:Boolean get()=false
    val diagnosticContext:Long? get()=null
    fun onRetired(listener: () -> Unit) {}
    suspend fun awaitRetired() {}
    suspend fun tile(x: Int, y: Int): TileResult
    suspend fun region(rect:PixelRect,cancelled:()->Boolean):TileResult = throw UnsupportedOperationException("Region provider required")
}
fun interface EditorDetailProvider {
    suspend fun open(source: PhotoSource, parameters: EditorState): EditorDetailSession
    suspend fun openLegacy(source:PhotoSource,parameters:EditorState):EditorDetailSession = open(source,parameters)
    /** Geometry-only warmup hint; never part of the immutable science key. */
    suspend fun openWithInitialRegion(source:PhotoSource,parameters:EditorState,initialRegion:PixelRect?):EditorDetailSession = open(source,parameters)
}
class RealEditorDetailProvider(private val provider: OwnedDetailProvider) : EditorDetailProvider {
    override suspend fun open(source: PhotoSource, parameters: EditorState): EditorDetailSession {
        val preferRegion=withContext(Dispatchers.IO){provider.supportsRegions()}
        return openWithPolicy(source,parameters,preferRegion)
    }
    override suspend fun openLegacy(source:PhotoSource,parameters:EditorState):EditorDetailSession = openWithPolicy(source,parameters,false)
    override suspend fun openWithInitialRegion(source:PhotoSource,parameters:EditorState,initialRegion:PixelRect?):EditorDetailSession {
        val preferRegion=withContext(Dispatchers.IO){provider.supportsRegions()}
        return openWithPolicy(source,parameters,preferRegion,initialRegion)
    }
    private suspend fun openWithPolicy(source:PhotoSource,parameters:EditorState,preferRegion:Boolean,initialRegion:PixelRect?=null):EditorDetailSession {
        val session = provider.open(source, parameters, 1536,preferRegion=preferRegion,initialRegion=initialRegion?.let{DetailRect(it.left,it.top,it.width,it.height)})
        try { return object : EditorDetailSession {
            override val regionOutput=session.regionOutput
            override val diagnosticContext=session.diagnosticContext
            override val key = session.key.toRenderKey(regionOutput)
            override suspend fun tile(x: Int, y: Int): TileResult {
                val tile = session.tile(x,y)
                try { return TileResult(tile.key.toRenderKey(regionOutput), tile.core.toPixels(), tile.sample.toPixels(), tile.bitmap, TileQuality.Exact,tile.diagnosticTiming) }
                catch(t:Throwable) { if(!tile.bitmap.isRecycled)tile.bitmap.recycle();throw t }
            }
            override suspend fun region(rect:PixelRect,cancelled:()->Boolean):TileResult {
                val tile=session.region(DetailRect(rect.left,rect.top,rect.width,rect.height),cancelled)
                try{return TileResult(tile.key.toRenderKey(regionOutput),tile.core.toPixels(),tile.sample.toPixels(),tile.bitmap,TileQuality.Exact,tile.diagnosticTiming)}
                catch(t:Throwable){if(!tile.bitmap.isRecycled)tile.bitmap.recycle();throw t}
            }
            override fun onRetired(listener: () -> Unit) = session.setRetirementListener(listener)
            override suspend fun awaitRetired() = session.awaitRetired()
            override fun close() = session.close()
        } } catch(t:Throwable) {
            withContext(NonCancellable+Dispatchers.IO){session.close();session.awaitRetired()}
            throw t
        }
    }
}
private fun DetailKey.toRenderKey(region:Boolean) = RenderKey(source,parameters,if(region)"owned-roi-api-v2-visible-region-cap1536" else "owned-roi-api-v1-grid256-cap1536",data,mapping,width,height)
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
    private var science: String? = null
    private var allowed = false
    private var comparing = false
    private var foreground = true
    private var disposed = false
    private var generation = 0L
    private var opening: Job? = null
    private var openingTiming: DetailTiming? = null
    private var settlingRegion: PixelRect? = null
    private var openingStarted = false
    private var latestMoving = false
    private var session: EditorDetailSession? = null
    private var firstPresentation:DetailTiming?=null
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
            return DetailStatus(phase,active.key.virtualFullWidth,active.key.virtualFullHeight,ready)
        }
        return DetailStatus(if(openingStarted)DetailPhase.Preparing else DetailPhase.Settling)
    }
    val key get() = if(comparing)null else session?.key
    val cacheBytes get() = scheduler?.cacheBytes ?: 0L
    val viewportRevision get() = scheduler?.viewportRevision ?: 0L
    fun bind(photo: PhotoSource?, params: EditorState, eligible: Boolean, compareOriginal:Boolean=false) {
        if (disposed) return
        // Compare exactly the parameter representation sent to native. Imprint,
        // framing, crop and output preferences do not invalidate scientific work.
        val nextScience=if(parameters==params)science ?: params.json("preview",includePresentation=false) else params.json("preview",includePresentation=false)
        val scienceChanged=source !== photo || science != nextScience
        if (scienceChanged || allowed != eligible) {
            if(scienceChanged){retryAttempts=0;forceLegacy=false}
            retire(reason=when {source !== photo->DetailRetirementReason.SOURCE_CHANGED;scienceChanged->DetailRetirementReason.SCIENCE_CHANGED;else->DetailRetirementReason.ELIGIBILITY_CHANGED})
        }
        source=photo;parameters=params;science=nextScience;allowed=eligible
        comparing=compareOriginal
        demand(latestMoving);changed()
    }
    fun foreground(value: Boolean) {
        if (foreground == value || disposed) return
        foreground=value
        if(!value)latestMoving=false
        if (!value) retire(reason=DetailRetirementReason.BACKGROUND) else demand(false)
    }
    fun demand(moving: Boolean) {
        latestMoving=moving
        if (disposed) return
        if (!allowed || !foreground || source == null) {
            if (session != null || opening != null) retire(reason=DetailRetirementReason.ELIGIBILITY_CHANGED)
            return
        }
        if(effectiveZoom() < 1.15) {
            if(retainContextWhileEditing()) {
                // The full-source crop editor temporarily zooms out. Keep same-science ownership,
                // but admit no new tiles/context builds while that editor does not need detail.
                scheduler?.setPaused(true)
                if(!openingStarted){openingTiming?.retiring(DetailRetirementReason.ZOOM_OUT);opening?.cancel();opening=null;openingTiming=null}
            } else if(session!=null || opening!=null)retire(reason=DetailRetirementReason.ZOOM_OUT)
            return
        }
        if(comparing) {
            scheduler?.setPaused(true)
            // Keep an already-running same-key context build/result, but do not start work.
            if(!openingStarted){openingTiming?.retiring(DetailRetirementReason.COMPARING);opening?.cancel();opening=null;openingTiming=null}
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
        // Repeated UI binds with the same science and visible demand do not
        // restart the existing settle timer. Motion or a changed rect still does.
        val requestedRegion=source?.let(::initialRegion)
        if(opening!=null && !moving && settlingRegion==requestedRegion)return
        openingTiming?.retiring(DetailRetirementReason.MOTION);opening?.cancel(); opening=null;openingTiming=null
        if (moving || failedGeneration == generation) return
        val photo=source ?: return; val params=parameters ?: return; val request=generation
        val timing=DetailTiming(DetailAttemptKind.OPEN)
        openingTiming=timing;settlingRegion=requestedRegion
        opening=scope.launch(timing) {
            var opened: EditorDetailSession? = null
            try {
                delay(settleMillis)
                timing.mark(DetailTimingPoint.SETTLE_END)
                if(forceLegacy)while(!retirementReady()){retirementJobs.toList().forEach{it.join()}}
                ensureActive()
                openingStarted=true;changed()
                // Resolve the current viewport after settling, on Main. This key
                // carries geometry only and is never published or used in a cache.
                val firstRegion=initialRegion(photo)
                opened=if(forceLegacy)provider.openLegacy(photo,params) else provider.openWithInitialRegion(photo,params,firstRegion)
                ensureActive()
                if (disposed || request != generation || !foreground || !allowed) return@launch
                val adopted=opened
                require(maxOf(adopted.key.virtualFullWidth,adopted.key.virtualFullHeight)<=1536)
                session=adopted; opened=null
                timing.finish(DetailTimingTerminal.OPEN_READY)
                firstPresentation=timing.presentation()
                val created=DetailOutputScheduler(object:TileProvider {
                    override fun startTiming()=adopted.diagnosticContext?.let{DetailTiming(if(adopted.regionOutput)DetailAttemptKind.REGION else DetailAttemptKind.TILE,it)}
                    override fun render(k:RenderKey,rect:PixelRect,cancelled:()->Boolean)=renderTimed(k,rect,cancelled,null)
                    override fun renderTimed(k:RenderKey,rect:PixelRect,cancelled:()->Boolean,timing:DetailTiming?):TileResult {
                        timing?.mark(DetailTimingPoint.WORKER_STARTED)
                        if (cancelled()) throw CancellationException("Retired detail")
                        check(k == adopted.key)
                        return runBlocking(timing ?: EmptyCoroutineContext) { if(adopted.regionOutput)adopted.region(rect,cancelled) else adopted.tile(rect.left/256,rect.top/256) }
                    }
                },regionOutput=adopted.regionOutput,onChanged={
                    // A provider failure (including native pressure retirement) releases idle ownership.
                    if (scheduler?.lastError != null) {
                        val failure=scheduler?.lastFailure
                        if(session?.regionOutput==true && failure is NativeWorkUnavailable)forceLegacy=true
                        fallbackReason=detailFailure(failure);failedGeneration=generation;retire(resetFailure=false,reason=DetailRetirementReason.PROVIDER_ERROR)
                    }
                    changed()
                })
                scheduler=created
                created.setPaused(latestMoving || comparing || (effectiveZoom()<1.15 && retainContextWhileEditing()))
                created.update(adopted.key,visibleRegion(adopted.key))
                adopted.onRetired {
                    scope.launch {
                        if(session === adopted) { fallbackReason=DetailFailure.MemoryPressure;retire(resetFailure=false,reason=DetailRetirementReason.RESOURCE_PRESSURE) }
                    }
                }
                changed()
            } catch(c: CancellationException) { timing.failed(c);throw c }
            catch(e: Exception) { timing.failed(e);if(request==generation) {fallbackReason=detailFailure(e);retire(resetFailure=false,reason=DetailRetirementReason.PROVIDER_ERROR)} }
            catch(e: LinkageError) { timing.failed(e);if(request==generation) {fallbackReason=detailFailure(e);retire(resetFailure=false,reason=DetailRetirementReason.PROVIDER_ERROR)} }
            catch(e: OutOfMemoryError) { timing.failed(e);if(request==generation) {fallbackReason=detailFailure(e);retire(resetFailure=false,reason=DetailRetirementReason.PROVIDER_ERROR)} }
            finally { opened?.let(::closeOffMain); if(opening === currentCoroutineContext()[Job]) {opening=null;openingStarted=false;openingTiming=null} }
        }.also(timing::monitor)
    }
    private fun initialRegion(photo:PhotoSource):PixelRect? {
        val size=photo.outputSize(1536)
        val geometry=RenderKey("open-hint","open-hint","open-hint","open-hint","open-hint",size.width,size.height)
        return visibleRegion(geometry)?.takeIf{it.left>=0 && it.top>=0 && it.right<=size.width && it.bottom<=size.height && it.width>0 && it.height>0}
    }
    fun onTileDrawn(output:DetailTiming?=null){firstPresentation?.let{it.drawnOutput(output);it.drawn()};firstPresentation=null}
    fun forEachDrawable(draw: (TileResult)->Unit) { if(!comparing)scheduler?.forEachDrawable(draw) }
    fun releaseForPressure() { fallbackReason=DetailFailure.MemoryPressure;retire(resetFailure=false,reason=DetailRetirementReason.EXTERNAL_INVALIDATION) }
    private fun closeOffMain(value: EditorDetailSession) {
        retirementJobs.removeAll{it.isCompleted}
        retirementJobs+=retirement.launch { runCatching { try{value.close()}finally{value.awaitRetired()} } }
    }
    private fun retirementReady():Boolean {retirementJobs.removeAll{it.isCompleted};return retirementJobs.isEmpty()}
    private fun retire(resetFailure: Boolean = true,reason:DetailRetirementReason=DetailRetirementReason.PROVIDER_ERROR) {
        openingTiming?.retiring(reason);openingTiming=null;settlingRegion=null
        firstPresentation?.retiring(reason)
        firstPresentation?.finish(DetailTimingTerminal.RETIRED);firstPresentation=null
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
    override fun close() { if(disposed)return; disposed=true; retire(reason=DetailRetirementReason.CLOSED); scope.cancel() }
}
