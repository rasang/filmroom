package dev.filmroom.engine

import android.content.Context
import android.graphics.Bitmap
import dev.filmroom.model.EditorState
import dev.filmroom.io.PhotoSource
import dev.filmroom.OperationTrace
import dev.filmroom.safeInfo
import dev.filmroom.diagnostics.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/** Request-scoped metadata; never shared by concurrent renders or retained by the engine. */
internal class PreviewPresentationContext : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<PreviewPresentationContext>
    var border:dev.filmroom.border.BorderSnapshot?=null
}

/** No fallback cosmetic filter: absent native engine is an explicit unavailable state. */
object NativeFilmBridge {
    val loadError: String? = try { System.loadLibrary("film_engine"); null } catch (e: LinkageError) { e.message ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6c9f62a53f72) }
    external fun nativeMeasurePreview(input:ByteBuffer,width:Int,height:Int,method:String):String
    external fun nativeEncodedReuseProbe(paramsJson:String):String
    external fun nativeSetPreviewCacheBudget(handle:Long,bytes:Long)
    external fun nativeCapabilities(): String
    external fun nativeRenderBitmap(handle: Long, source: Bitmap, dest: Bitmap, width: Int, height: Int, paramsJson: String): String
    external fun nativeRenderLinearBitmap(handle: Long, input: ByteBuffer, dest: Bitmap, width: Int, height: Int, paramsJson: String): String
    external fun nativeRenderRefinedBitmap(handle: Long, source: Bitmap, dest: Bitmap, width: Int, height: Int, paramsJson: String): String
    external fun nativeRenderRefinedLinearBitmap(handle: Long, input: ByteBuffer, dest: Bitmap, width: Int, height: Int, paramsJson: String): String
    external fun nativeRenderRgba8(handle: Long, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int, paramsJson: String): String
    external fun nativeInvalidatePrewarm(handle:Long,token:Long)
    external fun nativePrewarmFront(handle:Long,token:Long,paramsJson:String):String
    external fun nativeCreate(dataDirectory: String): Long
    external fun nativeRender(handle: Long, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int, paramsJson: String): String
    external fun nativeExportFile(handle: Long, inputPath: String, outputPath: String, width: Int, height: Int, paramsJson: String): String
    external fun nativePlanExport(handle: Long, width: Int, height: Int, paramsJson: String): String
    external fun nativeSetPreparedAssetsBudget(handle:Long,bytes:Long):String
    external fun nativeGetProgress(handle: Long): String
    external fun nativeTrimExportReuse(handle:Long,reserveBytes:Long)
    external fun nativeCancel(handle: Long)
    external fun nativeDestroy(handle: Long)
}

enum class EngineFailure { GPU_COMPLETION_UNCERTAIN, UNAVAILABLE, CANCELLED, IMAGE_SIZE, BUDGET, PROFILE, PARAMETERS, STORAGE, INCOMPLETE, PATH, INTERNAL }
class EngineException(val failure:EngineFailure, val nativeEngineCode:NativeEngineCode?=null, val nativeEngineStage:NativeEngineStage?=null):java.io.IOException(engineFailureMessage(failure).render()) {
    val uiMessage get()=engineFailureMessage(failure)
}
private fun engineFailureMessage(failure:EngineFailure):dev.filmroom.i18n.UiMessage=when(failure){
    EngineFailure.GPU_COMPLETION_UNCERTAIN->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.gpu_completion_uncertain)
    EngineFailure.UNAVAILABLE->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_a4ca69371996)
    EngineFailure.CANCELLED->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_6c0813b39bb5)
    EngineFailure.IMAGE_SIZE->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_d5113f613aad)
    EngineFailure.BUDGET->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_8bbca1842388)
    EngineFailure.PROFILE->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_e46692a12a54)
    EngineFailure.PARAMETERS->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_fda570c695c8)
    EngineFailure.STORAGE->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_368306bc6068)
    EngineFailure.INCOMPLETE->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_77f7a824adc1)
    EngineFailure.PATH->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_9695c3a1a61a)
    EngineFailure.INTERNAL->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_4e906ddf82d9)
}

internal data class EncodedReuseIdentity(val identity:String,val receipt:String)

class FilmEngine(private val context: Context) {
    @Volatile private var completedEncodedReuse:EncodedReuseIdentity?=null
    internal fun completedEncodedReuseIdentity()=completedEncodedReuse
    /** Optional read-only probe. An old/fake ABI, unavailable memory or quarantined GPU is a miss. */
    internal suspend fun encodedReuseIdentity(width:Int,height:Int,parameters:EditorState):String? {
        // A warm export can bypass planExport/exportSpool, so its optional probe
        // must also preempt speculative front work before waiting for the handle.
        cancelPrewarm()
        return renderMutex.withLock {
            withContext(Dispatchers.IO) { optionalEncodedReuse {
                if(unavailable!=null || closed)return@optionalEncodedReuse null
                val data=copyData();val budget=memoryBudgetMiB()
                val probe=JSONObject(NativeFilmBridge.nativeEncodedReuseProbe(nativeParameters(parameters,"export",budget))).optJSONObject("identity") ?: return@optionalEncodedReuse null
                encodedIdentity(data,width,height,parameters,budget,probe)
            } }
        }
    }
    private suspend fun <T> optionalEncodedReuse(work:suspend ()->T?):T? = try {work()}catch(c:CancellationException){throw c}catch(_:Exception){null}catch(_:LinkageError){null}catch(_:OutOfMemoryError){null}
    private suspend fun encodedIdentity(data:File,width:Int,height:Int,parameters:EditorState,budget:Int,probe:JSONObject):String? {
        val job=currentCoroutineContext()
        val dataHash=dev.filmroom.export.EncodedPayloadCache.hashFile(File(data,".prepared-v2"),4L*1024*1024){job.ensureActive()} ?: return null
        return dev.filmroom.export.EncodedPayloadCache.key("encoded-science-v1",width.toString(),height.toString(),nativeParameters(parameters,"export",budget),dataHash,probe.toString()).digest
    }

    val unavailable: String? get() = NativeFilmBridge.loadError?.let { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0a9765e80ef5) }
    private val renderMutex=Mutex()
    private val preparedAssets=PreparedAssetsOwner(
        configureNative={ owner,bytes -> checkNativeResult(JSONObject(NativeFilmBridge.nativeSetPreparedAssetsBudget(owner,bytes))) },
        reserve={ bytes,onPressure -> NativeWorkReservations.retain(bytes,{NativeWorkReservations.snapshot(context)},onPressure) },
    )
    private val refinedSources=dev.filmroom.io.RefinedPreviewSourceOwner(
        reserve={bytes,onPressure->NativeWorkReservations.retain(bytes,{NativeWorkReservations.snapshot(context)},onPressure)},
    )
    @Volatile private var handle = 0L
    private val handleLock = Any()
    private val previewStageCache=PreviewStageCacheOwner(
        { id,bytes->NativeFilmBridge.nativeSetPreviewCacheBudget(id,bytes) },
        { bytes,pressure->NativeWorkReservations.retain(bytes,{NativeWorkReservations.snapshot(context)},pressure) },
    )
    private val prewarmGeneration=java.util.concurrent.atomic.AtomicLong()
    private var prewarmOwner=0L
    fun cancelPrewarm() { synchronized(handleLock) {
        val token=prewarmGeneration.incrementAndGet()
        if(handle!=0L && prewarmOwner==handle)NativeFilmBridge.nativeInvalidatePrewarm(handle,token)
    } }
    fun cancel() { cancelPrewarm();synchronized(handleLock) { if(handle != 0L) NativeFilmBridge.nativeCancel(handle) } }
    internal fun trimExportReuse(reserveBytes:Long) { synchronized(handleLock) { if(handle!=0L)runCatching { NativeFilmBridge.nativeTrimExportReuse(handle,reserveBytes) } } }
    @Volatile private var closed=false
    private var preparedHandleDirectory:String?=null
    private fun ensureHandle(data:File):Long=synchronized(handleLock) {
        check(!closed) { "Engine owner closed" }
        if(handle!=0L && preparedHandleDirectory!=data.absolutePath)resetHandle()
        if(handle==0L){
            val created=NativeFilmBridge.nativeCreate(data.absolutePath)
            if(created==0L)throw EngineException(EngineFailure.UNAVAILABLE)
            handle=created;preparedHandleDirectory=data.absolutePath
        }
        handle
    }
    private fun resetHandle()=synchronized(handleLock) {
        previewStageCache.retire()
        // Native zero-budget acknowledgement waits for render_lock and all front
        // leases to drain. Never destroy the registry entry before retirement.
        preparedAssets.retire()
        val previous=handle;handle=0L;preparedHandleDirectory=null
        if(previous!=0L)NativeFilmBridge.nativeDestroy(previous)
    }
    /** Retire only once the serialized native call has returned; never free a running engine. */
    fun close() {
        cancelPrewarm()
        synchronized(handleLock){if(closed)return;closed=true;if(handle!=0L)NativeFilmBridge.nativeCancel(handle)}
        CoroutineScope(Dispatchers.IO).launch { renderMutex.withLock { resetHandle();previewStageCache.close();preparedAssets.close();refinedSources.close() } }
    }
    private suspend fun copyData():File=VersionedEngineAssets.prepare(context)
    /** Only verified local assets and source-independent front data. Holding
     * renderMutex pins this handle, while cancellation uses the separate token. */
    internal suspend fun prewarmFront(parameters:EditorState,allowed:()->Boolean):String {
        if(!allowed() || unavailable!=null || closed || !renderMutex.tryLock())return "busy"
        val token=prewarmGeneration.get()
        // A private, one-attempt executor avoids changing Default/IO pool thread
        // priority. No thread survives the attempt; all I/O stays off the UI.
        var dispatcher:ExecutorCoroutineDispatcher?=null
        try {
            val worker=java.util.concurrent.Executors.newSingleThreadExecutor { work -> Thread(work,"film-front-prewarm") }.asCoroutineDispatcher()
            dispatcher=worker
            return withContext(worker) {
                if(runCatching{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)}.isFailure)return@withContext "priority_unavailable"
                if(!allowed() || token!=prewarmGeneration.get())return@withContext "cancelled"
                val data=VersionedEngineAssets.preparedForPrewarm() ?: return@withContext "assets_not_ready"
                val created=ensureHandle(data)
                val armed=synchronized(handleLock){
                    if(token!=prewarmGeneration.get())false else {
                        prewarmOwner=created;NativeFilmBridge.nativeInvalidatePrewarm(created,token);true
                    }
                }
                if(!armed)return@withContext "cancelled"
                // Admission/retirement is outside native render_lock. Keep this
                // exact existing passive owner, never a second speculative cache.
                if(!preparedAssets.prepare(created))return@withContext "passive_budget"
                NativeWorkReservations.tryRunIdle(64L*NativeMemoryPolicy.MIB,{NativeWorkReservations.snapshot(context)},::cancelPrewarm) {
                    if(!allowed() || token!=prewarmGeneration.get())return@tryRunIdle "cancelled"
                    val result=JSONObject(NativeFilmBridge.nativePrewarmFront(created,token,nativeParameters(parameters,"preview",memoryBudgetMiB())))
                    if(result.optBoolean("ok"))result.optString("outcome","skipped")else "cancelled_or_unavailable"
                } ?: "active_budget"
            }
        } finally {
            // withContext cannot finish this finally until blocking JNI returns.
            // The active scratch reservation therefore never releases early.
            synchronized(handleLock){prewarmOwner=0L}
            dispatcher?.close();renderMutex.unlock()
        }
    }
    internal fun previewEdge(settled:Boolean)=NativeMemoryPolicy.previewEdge(memoryBudgetMiB(),settled)
    /** Independent, explicit zoom tier; never falls back to a cosmetically enlarged preview. */
    suspend fun renderZoom(source:Bitmap,photo:PhotoSource,parameters:EditorState,edge:Int):Bitmap {
        require(edge==2048 || edge==3072)
        require(parameters.preImagingBorder)
        return renderInternal(source,parameters,"zoom-preview",photo,true,edge)
    }
    suspend fun render(source:Bitmap,parameters:EditorState,mode:String,photo:PhotoSource?=null,settled:Boolean=false):Bitmap =
        renderInternal(source,parameters,mode,photo,settled,NativeMemoryPolicy.REFINED_PREVIEW_EDGE)
    private suspend fun renderInternal(source: Bitmap, parameters: EditorState, mode: String, photo:PhotoSource?,settled:Boolean,refinementEdge:Int): Bitmap = ownedBitmap { capture -> OperationTrace.run(Operation.PREVIEW_RENDER,parameters.safeInfo(source.width,source.height)) { trace ->
        trace.previewStage(PreviewFailureStage.CANCEL_PREWARM)
        cancelPrewarm()
        val timing=currentCoroutineContext()[PreviewDiagnosticContext]?.timing
        timing?.operation(trace.id.value);timing?.mark(PreviewTimingPoint.RENDER_QUEUED)
        trace.previewStage(PreviewFailureStage.WAIT_FOR_WORKER)
        renderMutex.withLock { timing?.mark(PreviewTimingPoint.MUTEX_ACQUIRED);withContext(Dispatchers.Default) renderWork@{
        timing?.mark(PreviewTimingPoint.WORKER_STARTED)
        trace.previewStage(PreviewFailureStage.LOAD_ENGINE)
        if(unavailable!=null || closed)throw EngineException(EngineFailure.UNAVAILABLE)
        val job=currentCoroutineContext()
        val preparationStart=System.nanoTime()
        trace.previewStage(PreviewFailureStage.SOURCE_PREPARATION)
        val budget=memoryBudgetMiB()
        val preImaging=parameters.filmBorder && parameters.filmBorderStage==1
        val zoomRequested=mode=="zoom-preview"
        require(refinementEdge==NativeMemoryPolicy.REFINED_PREVIEW_EDGE || (zoomRequested && refinementEdge in setOf(2048,3072)))
        if((mode=="preview" || zoomRequested) && settled && parameters.useGpu && photo!=null &&
            maxOf(photo.originalWidth,photo.originalHeight)>NativeMemoryPolicy.DETAIL_PREVIEW_EDGE &&
            budget>=NativeMemoryPolicy.REFINED_MIN_BUDGET_MIB) {
            trace.previewStage(PreviewFailureStage.CAPABILITIES)
            val capabilities=try{JSONObject(NativeFilmBridge.nativeCapabilities())}catch(_:UnsatisfiedLinkError){JSONObject()}
            if((if(zoomRequested)PreviewNativeContract.supportsZoomRefinedBitmap(capabilities,refinementEdge)else PreviewNativeContract.supportsRefinedBitmap(capabilities)) && (!preImaging || capabilities.optInt("preImagingAbi")==1)) {
                return@renderWork renderRefined(photo,parameters,budget,trace,timing,capture,refinementEdge)
            }
        }
        if(zoomRequested)throw EngineException(EngineFailure.BUDGET)
        val frameEdge=NativeMemoryPolicy.previewEdge(budget,settled)
        // Budget the complete frame, not merely the photograph. Use frozen geometry to
        // choose the source grid before decoding RAW or allocating the composite.
        val edge=if(preImaging){
            var candidate=frameEdge
            repeat(4){
                val ratio=minOf(1.0,candidate.toDouble()/maxOf(source.width,source.height))
                val probe=dev.filmroom.border.PreImagingInput.snapshot(parameters,
                    (source.width*ratio).toInt().coerceAtLeast(1),(source.height*ratio).toInt().coerceAtLeast(1),
                    photo?.originalWidth ?: source.width,photo?.originalHeight ?: source.height)
                val fullEdge=maxOf(probe.outputWidth,probe.outputHeight)
                if(fullEdge>frameEdge)candidate=(candidate.toDouble()*frameEdge/fullEdge).toInt().coerceAtLeast(1)
            }
            candidate
        }else frameEdge
        val sourceRevision=photo?.previewSourceRevision()
        val raw=photo?.linearPreview(edge)
        val scale=if(raw==null && maxOf(source.width,source.height)>edge)edge.toDouble()/maxOf(source.width,source.height) else 1.0
        val sourceWidth=raw?.width ?: (source.width*scale).toInt().coerceAtLeast(1)
        val sourceHeight=raw?.height ?: (source.height*scale).toInt().coerceAtLeast(1)
        val border=if(preImaging)dev.filmroom.border.PreImagingInput.snapshot(parameters,sourceWidth,sourceHeight,
            photo?.originalWidth ?: source.width,photo?.originalHeight ?: source.height)else null
        val width=border?.outputWidth ?: sourceWidth
        val height=border?.outputHeight ?: sourceHeight
        val count=Math.multiplyExact(width,height)
        require(count<=NativeMemoryPolicy.DETAIL_PREVIEW_EDGE*NativeMemoryPolicy.DETAIL_PREVIEW_EDGE)
        // Admit the actual complete canvas (including rails), not only the aperture.
        // Refuse insufficient memory before engine/cache/result allocation.
        if(!NativeMemoryPolicy.previewFitsBudget(width,height,budget))throw EngineException(EngineFailure.BUDGET)
        trace.previewStage(PreviewFailureStage.ASSET_PREPARATION)
        val data=copyData();job.ensureActive()
        timing?.handle(synchronized(handleLock){handle==0L})
        trace.previewStage(PreviewFailureStage.METERING)
        val metering=if(parameters.autoExposure) {
            // A missing full source must never silently meter the display image/ROI.
            val fullSource=photo ?: throw EngineException(EngineFailure.PARAMETERS)
            PreviewMetering.resolve(fullSource,context.applicationContext.dataDir,parameters.metering) { NativeWorkReservations.snapshot(context) }
        }else null
        val raster=border?.let{dev.filmroom.border.BorderCatalog.resolve(it){job.ensureActive()}}
        val compositeBytes=if(border!=null)dev.filmroom.border.PreImagingInput.previewWorkingBytes(border,checkNotNull(raster),parameters.imprint.enabled)+sourceWidth.toLong()*sourceHeight*4L else 0L
        val compositeLease=if(border!=null){
            val heap=Runtime.getRuntime().let{it.maxMemory()-it.totalMemory()+it.freeMemory()}
            if(heap<compositeBytes+dev.filmroom.border.BorderMemoryAdmission.HEAP_MARGIN_BYTES)throw EngineException(EngineFailure.BUDGET)
            NativeWorkReservations.retain(compositeBytes,{NativeWorkReservations.snapshot(context)})
        }else null
        try {
        val composite=if(border!=null) {
            val imprint=dev.filmroom.imprint.ImprintPlan.create(parameters.imprint,photo?.photoMetadata ?: dev.filmroom.io.PhotoMetadata.unavailable(),
                border.outputWidth,border.outputHeight,border)
            run {
                val scaled=if(raw==null && (source.width!=sourceWidth || source.height!=sourceHeight))
                    Bitmap.createScaledBitmap(source,sourceWidth,sourceHeight,true)else null
                try {dev.filmroom.border.PreImagingInput.preview(if(raw==null)scaled ?: source else null,raw,border,checkNotNull(raster),imprint){job.ensureActive()}}
                finally {scaled?.recycle()}
            }
        }else null
        trace.previewStage(PreviewFailureStage.CACHE_IDENTITY)
        val sourceIdentity=if(photo!=null && mode=="preview") {
            check(photo.previewSourceRevision()==sourceRevision){"Source changed during preview preparation"}
            val dataRevision=PreviewAssetIdentity.revision(data,job)
            JSONObject().put("sourceRevision",sourceRevision)
                .put("mappingRevision","oriented-original-v1:${photo.mimeType}:${photo.originalWidth}x${photo.originalHeight}:linear=${raw!=null}:input=${source.width}x${source.height}:output=${width}x${height}:${if(raw!=null)"linear-bilinear-center-v1" else "srgb-area-box-then-linearize-v1"}")
                .put("dataRevision",dataRevision).apply {
                    if(border!=null){
                        val identity=java.security.MessageDigest.getInstance("SHA-256")
                            .digest((border.json()+parameters.json()).toByteArray(Charsets.UTF_8))
                            .joinToString(""){"%02x".format(it)}
                        put("mappingRevision",getString("mappingRevision")+":"+dev.filmroom.border.PreImagingInput.ALGORITHM+":"+identity)
                    }
                }
        }else null
        trace.previewStage(PreviewFailureStage.CREATE_HANDLE)
        val created=ensureHandle(data)
        trace.previewStage(PreviewFailureStage.CAPABILITIES)
        val caps=try { JSONObject(NativeFilmBridge.nativeCapabilities()) } catch(_:UnsatisfiedLinkError) { throw EngineException(EngineFailure.UNAVAILABLE) }
        if(!PreviewNativeContract.supportsBitmap(caps))throw EngineException(EngineFailure.UNAVAILABLE)
        if(preImaging && caps.optInt("preImagingAbi")!=1)throw EngineException(EngineFailure.UNAVAILABLE)
        trace.previewStage(PreviewFailureStage.PREPARED_CACHE)
        if(caps.optInt("preparedSpectralCacheAbi")==1 && caps.optLong("preparedSpectralCacheBudgetBytes")==PreparedAssetsOwner.BYTES) {
            // Passive prepared bytes remain charged between renders, separately
            // from active preview scratch. Pressure can disable reuse safely.
            preparedAssets.prepare(created)
        }
        trace.previewStage(PreviewFailureStage.PREVIEW_CACHE)
        val cacheSupported=sourceIdentity!=null && PreviewNativeContract.supportsStageCache(caps) &&
            PreviewStageCacheOwner.candidateFits(width,height,budget)
        val cacheEligible=if(cacheSupported) {
            // A denied passive lease uses the original cold parameters and
            // reservation. Once admitted, retain the active candidate charge for
            // this request even if later pressure retires the passive owner.
            previewStageCache.prepare(created,sourceIdentity!!.toString(),width,height)
        }else {
            previewStageCache.retire()
            false
        }
        trace.previewStage(PreviewFailureStage.PARAMETERS)
        val previewParams=JSONObject(nativeParameters(parameters,mode,budget)).apply {
            metering?.let { put("previewMetering",it.json()) }
            border?.let{put("preImaging",dev.filmroom.border.PreImagingInput.nativeJson(it))}
            if(cacheEligible)sourceIdentity?.let{put("previewSource",it)}
        }.toString()
        val preparationMs=(System.nanoTime()-preparationStart)/1_000_000
        // Never pool a displayed Bitmap: stale View/StateFlow readers may still own it.
        val allocationStart=System.nanoTime()
        trace.previewStage(PreviewFailureStage.BITMAP_ALLOCATION)
        val rendered=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val allocationMs=(System.nanoTime()-allocationStart)/1_000_000
        timing?.mark(PreviewTimingPoint.PREPARED)
        var delivered=false
        try {
            job.ensureActive()
            trace.mark(Stage.NATIVE_ENTER,SafeInfo(width=width,height=height,memoryBudgetMiB=budget,previewPreparationMs=preparationMs,previewAllocationMs=allocationMs))
            var jniWallMs:Long?=null
            timing?.mark(PreviewTimingPoint.RESERVATION_REQUESTED)
            trace.previewStage(PreviewFailureStage.RESERVATION)
            val result=withNativeReservation(NativeWorkReservations.previewEstimate(width,height)+(if(cacheEligible)PreviewStageCacheOwner.candidateBytes(width,height)else 0L)) {
                timing?.mark(PreviewTimingPoint.JNI_ENTER)
                trace.previewStage(PreviewFailureStage.NATIVE_RENDER)
                val jniStart=System.nanoTime()
                try {
                    if(composite!=null)NativeFilmBridge.nativeRenderLinearBitmap(created,composite.buffer(),rendered,width,height,previewParams)
                    else if(raw==null)NativeFilmBridge.nativeRenderBitmap(created,source,rendered,width,height,previewParams)
                    else NativeFilmBridge.nativeRenderLinearBitmap(created,raw.buffer(),rendered,width,height,previewParams)
                } finally { jniWallMs=(System.nanoTime()-jniStart)/1_000_000;timing?.mark(PreviewTimingPoint.JNI_EXIT) }
            }
            job.ensureActive()
            trace.previewStage(PreviewFailureStage.NATIVE_RESULT)
            val metadata=JSONObject(result)
            trace.mark(Stage.NATIVE_EXIT,GpuRuntimeInfo.withPreviewCache(metadata,GpuRuntimeInfo.fromNativeResult(metadata)).copy(previewJniWallMs=jniWallMs))
            checkNativeResult(metadata)
            trace.previewStage(PreviewFailureStage.SOURCE_VALIDATION)
            check(photo==null || photo.previewSourceRevision()==sourceRevision){"Source changed during preview"}
            trace.previewStage(PreviewFailureStage.OUTPUT_VALIDATION)
            check(PreviewNativeContract.outputMatches(metadata,width,height)) { "Invalid native preview result" }
            if(border!=null){
                dev.filmroom.border.PreImagingInput.restoreHoles(rendered,border,checkNotNull(raster)){job.ensureActive()}
                job[PreviewPresentationContext]?.border=border
            }
            capture(rendered);delivered=true;rendered
        } catch(t:Throwable) {
            // Cleanup must not replace the original diagnostic failure.
            try { resetHandle() } catch(cleanup:Throwable) { if(cleanup!==t)t.addSuppressed(cleanup) }
            throw t
        }
        finally { if(!delivered && !rendered.isRecycled)rendered.recycle() }
        } finally {compositeLease?.close()}
    } } } }
    /** Dedicated GPU-only refinement. Its bounded native planner includes source,
     * result and tile working memory inside the SAME budget used by ordinary work.
     * Failure is propagated to the existing refinement handler, which keeps512. */
    private suspend fun renderRefined(photo:PhotoSource,parameters:EditorState,budget:Int,trace:OperationTrace,
        timing:PreviewTiming?,capture:(Bitmap)->Unit,frameEdge:Int=NativeMemoryPolicy.REFINED_PREVIEW_EDGE):Bitmap {
        val job=currentCoroutineContext()
        val sourceRevision=photo.previewSourceRevision()
        trace.previewStage(PreviewFailureStage.ASSET_PREPARATION)
        val data=copyData();job.ensureActive()
        timing?.handle(synchronized(handleLock){handle==0L})
        trace.previewStage(PreviewFailureStage.METERING)
        val metering=if(parameters.autoExposure)PreviewMetering.resolve(photo,context.applicationContext.dataDir,parameters.metering){NativeWorkReservations.snapshot(context)}else null
        trace.previewStage(PreviewFailureStage.CREATE_HANDLE)
        val created=ensureHandle(data)
        // Plan the COMPLETE frame before decoding. The original photo still owns AE;
        // the aperture owns physical pitch, and zoom never changes either.
        val preImaging=parameters.preImagingBorder
        var sourceEdge=frameEdge
        if(preImaging)repeat(8) {
            val size=dev.filmroom.io.PhotoLimits.fit(photo.originalWidth,photo.originalHeight,sourceEdge)
            val probe=dev.filmroom.border.PreImagingInput.snapshot(parameters,size.width,size.height,photo.originalWidth,photo.originalHeight)
            val edge=maxOf(probe.outputWidth,probe.outputHeight)
            if(edge>frameEdge)
                sourceEdge=(sourceEdge.toLong()*frameEdge/edge).toInt().coerceAtLeast(1)
        }
        if(frameEdge>NativeMemoryPolicy.REFINED_PREVIEW_EDGE) {
            val size=dev.filmroom.io.PhotoLimits.fit(photo.originalWidth,photo.originalHeight,sourceEdge)
            val frame=dev.filmroom.border.PreImagingInput.snapshot(parameters,size.width,size.height,photo.originalWidth,photo.originalHeight)
            if(!NativeMemoryPolicy.zoomFrameFloorFits(frame.outputWidth,frame.outputHeight,budget))throw EngineException(EngineFailure.BUDGET)
        }
        var rendered:Bitmap?=null
        var delivered=false
        try {
            timing?.mark(PreviewTimingPoint.RESERVATION_REQUESTED)
            trace.previewStage(PreviewFailureStage.RESERVATION)
            return withNativeReservation(budget.toLong()*NativeMemoryPolicy.MIB) {
                val preparationStart=System.nanoTime()
                trace.previewStage(PreviewFailureStage.SOURCE_PREPARATION)
                refinedSources.acquire(photo,sourceEdge).use { borrowed ->
                    job.ensureActive()
                    check(photo.previewSourceRevision()==sourceRevision){"Source changed during refinement preparation"}
                    val input=borrowed.input
                    val border=if(preImaging)dev.filmroom.border.PreImagingInput.snapshot(parameters,input.width,input.height,photo.originalWidth,photo.originalHeight)else null
                    val raster=border?.let{dev.filmroom.border.BorderCatalog.resolve(it){job.ensureActive()}}
                    val width=border?.outputWidth ?: input.width;val height=border?.outputHeight ?: input.height
                    check(width in 1..frameEdge && height in 1..frameEdge)
                    val compositeBytes=if(border!=null)dev.filmroom.border.PreImagingInput.previewWorkingBytes(border,checkNotNull(raster),parameters.imprint.enabled)else 0L
                    val compositeLease=if(border!=null) {
                        val heap=Runtime.getRuntime().let{it.maxMemory()-it.totalMemory()+it.freeMemory()}
                        if(heap<compositeBytes+dev.filmroom.border.BorderMemoryAdmission.HEAP_MARGIN_BYTES)throw EngineException(EngineFailure.BUDGET)
                        NativeWorkReservations.retain(compositeBytes,{NativeWorkReservations.snapshot(context)})
                    }else null
                    try {
                    val composite=if(border!=null) {
                        val imprint=dev.filmroom.imprint.ImprintPlan.create(parameters.imprint,photo.photoMetadata,width,height,border)
                        if(frameEdge>NativeMemoryPolicy.REFINED_PREVIEW_EDGE)
                            dev.filmroom.border.PreImagingInput.zoomPreview(input.bitmap,input.linear,border,checkNotNull(raster),imprint,frameEdge){job.ensureActive()}
                        else dev.filmroom.border.PreImagingInput.refinedPreview(input.bitmap,input.linear,border,checkNotNull(raster),imprint){job.ensureActive()}
                    }else null
                    val params=JSONObject(nativeParameters(parameters,"preview",budget)).apply {
                        if(frameEdge>NativeMemoryPolicy.REFINED_PREVIEW_EDGE)put("zoomRefinementEdge",frameEdge)
                        metering?.let{put("previewMetering",it.json())}
                        border?.let{put("preImaging",dev.filmroom.border.PreImagingInput.nativeJson(it))}
                    }.toString()
                    val preparationMs=(System.nanoTime()-preparationStart)/1_000_000
                    trace.previewStage(PreviewFailureStage.BITMAP_ALLOCATION)
                    val allocationStart=System.nanoTime()
                    val destination=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{rendered=it}
                    val allocationMs=(System.nanoTime()-allocationStart)/1_000_000
                    timing?.mark(PreviewTimingPoint.PREPARED)
                    trace.mark(Stage.NATIVE_ENTER,SafeInfo(width=width,height=height,memoryBudgetMiB=budget,previewPreparationMs=preparationMs,previewAllocationMs=allocationMs))
                    job.ensureActive()
                    timing?.mark(PreviewTimingPoint.JNI_ENTER)
                    trace.previewStage(PreviewFailureStage.NATIVE_RENDER)
                    val started=System.nanoTime()
                    var jniMs:Long
                    val native=try {
                        if(composite!=null)NativeFilmBridge.nativeRenderRefinedLinearBitmap(created,composite.buffer(),destination,width,height,params)
                        else input.bitmap?.let{NativeFilmBridge.nativeRenderRefinedBitmap(created,it,destination,width,height,params)}
                            ?: NativeFilmBridge.nativeRenderRefinedLinearBitmap(created,checkNotNull(input.linear).buffer(),destination,width,height,params)
                    } catch(_:LinkageError) {throw EngineException(EngineFailure.UNAVAILABLE)}
                    finally {jniMs=(System.nanoTime()-started)/1_000_000;timing?.mark(PreviewTimingPoint.JNI_EXIT)}
                    job.ensureActive()
                    trace.previewStage(PreviewFailureStage.NATIVE_RESULT)
                    val result=JSONObject(native)
                    trace.mark(Stage.NATIVE_EXIT,GpuRuntimeInfo.fromNativeResult(result).copy(previewJniWallMs=jniMs))
                    checkNativeResult(result)
                    trace.previewStage(PreviewFailureStage.SOURCE_VALIDATION)
                    check(photo.previewSourceRevision()==sourceRevision){"Source changed during refinement"}
                    trace.previewStage(PreviewFailureStage.OUTPUT_VALIDATION)
                    val externalBytes=(composite?.buffer()?.capacity()?.toLong() ?: input.bitmap?.let{it.rowBytes.toLong()*it.height} ?: checkNotNull(input.linear).buffer().capacity().toLong())+destination.rowBytes.toLong()*height
                    check(PreviewNativeContract.refinedOutputMatches(result,width,height,budget,externalBytes,frameEdge)){"Invalid native refined-preview result/plan"}
                    if(border!=null) {
                        dev.filmroom.border.PreImagingInput.restoreHoles(destination,border,checkNotNull(raster)){job.ensureActive()}
                        job[PreviewPresentationContext]?.border=border
                    }
                    capture(destination);delivered=true;destination
                    } finally {compositeLease?.close()}
                }
            }
        } catch(t:Throwable) {
            try{resetHandle()}catch(cleanup:Throwable){if(cleanup!==t)t.addSuppressed(cleanup)}
            throw t
        } finally {if(!delivered)rendered?.takeUnless{it.isRecycled}?.recycle()}
    }

    suspend fun planExport(width:Int,height:Int,parameters:EditorState,preImaging:JSONObject?=null):NativeExportPlan = OperationTrace.run(Operation.EXPORT_PLAN,parameters.safeInfo(width,height)) { trace ->
        cancelPrewarm()
        renderMutex.withLock { withContext(Dispatchers.IO) {
            if(unavailable!=null)throw EngineException(EngineFailure.UNAVAILABLE)
            val job=currentCoroutineContext();val budget=memoryBudgetMiB();val data=copyData();job.ensureActive()
            val created=ensureHandle(data)
            try {
                job.ensureActive();val plan=planForHandle(created,width,height,parameters,budget,preImaging)
                trace.mark(Stage.NATIVE_EXIT,SafeInfo(width=width,height=height,memoryBudgetMiB=budget,estimatedWorkingBytes=plan.workingBytes))
                plan
            } catch(t:Throwable){resetHandle();throw t}
        } }
    }
    private fun planForHandle(created:Long,width:Int,height:Int,parameters:EditorState,budget:Int,preImaging:JSONObject?=null):NativeExportPlan {
        if(preImaging!=null && JSONObject(NativeFilmBridge.nativeCapabilities()).optInt("preImagingAbi")!=1)throw EngineException(EngineFailure.UNAVAILABLE)
        val raw=JSONObject(try{NativeFilmBridge.nativePlanExport(created,width,height,exportParameters(parameters,budget,preImaging))}catch(_:LinkageError){throw EngineException(EngineFailure.UNAVAILABLE)})
        checkNativeResult(raw);val plan=raw.getJSONObject("plan")
        check(plan.getInt("width")==width && plan.getInt("height")==height)
        return NativeExportPlan(width,height,plan.getLong("inputBytes"),plan.getLong("outputBytes"),plan.getLong("estimatedScratchBytes"),plan.getLong("estimatedTotalLocalBytes"),plan.getLong("estimatedNativeWorkingBytes"),plan.getLong("checkpointReserveBytes"))
    }
    private fun requireExportSpace(plan:NativeExportPlan,inputExists:Boolean,workspace:File?=null) {
        val needed=Math.addExact(if(inputExists)ExportStoragePolicy.afterInput(plan)else ExportStoragePolicy.beforeInput(plan),if(workspace!=null)plan.checkpointReserveBytes else 0L)
        if(!ExportStoragePolicy.hasSpace(android.os.StatFs(context.cacheDir.absolutePath).availableBytes,needed))throw EngineException(EngineFailure.STORAGE)
    }
    private fun progressPacket(created:Long):JSONObject? = runCatching {
        val raw=synchronized(handleLock){if(handle!=created)return null;NativeFilmBridge.nativeGetProgress(created)}
        JSONObject(raw)
    }.getOrNull()
    private fun progress(data:JSONObject):EngineProgress? = runCatching {
        if(!data.optBoolean("ok")||!data.optBoolean("active")||data.optString("counterScope")!="current-pass")return null
        val phase=NativePhase.fromId(data.optString("stage"))?:return null
        EngineProgress(data.getLong("jobId"),data.getLong("passId"),phase,data.getLong("done"),data.getLong("total"),
            if(data.optString("stageCounterScope")=="stage-work" && data.optInt("displayStage") in 1..6)ExportDisplayProgress(data.getInt("displayStage"),data.getLong("stageDone"),data.getLong("stageTotal"))
            else ExportDisplayProgress.forNativePhase(phase.id)).let { value ->
                if(phase in setOf(NativePhase.GPU_FULL_CHAIN,NativePhase.GPU_BANDED_CHAIN,NativePhase.GPU_TILED_CHAIN,NativePhase.GPU_DIFFUSION_PREPASS))
                    value.copy(display=value.display.copy(gpu=(GpuWorkProgress.fromJson(data)?:GpuWorkProgress()).copy(prepass=phase==NativePhase.GPU_DIFFUSION_PREPASS)))
                else value
            }
    }.getOrNull()
    private suspend fun exportWithProgress(created:Long,input:File,output:File,width:Int,height:Int,parameters:EditorState,budget:Int,trace:OperationTrace,onProgress:((EngineProgress)->Unit)?,workspace:File?=null,progressOnMain:Boolean=true,cacheDiskReserveBytes:Long=0,preImaging:JSONObject?=null):String = coroutineScope {
        // A warmed handle has monotonic native job IDs; only active progress is published.
        // Filename derives exclusively from this Kotlin-owned output, never a native supplied path.
        val scanPreview=scanPreviewForOutput(output,workspace)
        val priorJobId=(progressPacket(created)?.opt("jobId") as? Number)?.toLong()?:-1L
        var latest:EngineProgress?=null
        val presentation=GpuExportPresentation()
        var lastFallback:GpuReason?=null
        var lastGpuWork:SafeInfo?=null
        suspend fun recordGpuMetadata(data:JSONObject) {
            // Never attach a warmed handle's previous job to this export.
            GpuRuntimeInfo.fallbackFromProgress(data,priorJobId)?.let { info ->
                if(info.gpuReason!=lastFallback){trace.mark(Stage.NATIVE_PROGRESS,info);lastFallback=info.gpuReason}
            }
            GpuRuntimeInfo.workFromProgress(data,priorJobId)?.let { info ->
                if(info!=lastGpuWork){trace.mark(Stage.NATIVE_PROGRESS,info);lastGpuWork=info}
            }
        }
        val polling=launch(Dispatchers.IO) {
            var lastPass=-1L;var lastPhase:NativePhase?=null
            while(isActive) {
                delay(400)
                val packet=progressPacket(created)?:continue
                recordGpuMetadata(packet)
                if((packet.opt("jobId") as? Number)?.toLong()?.let{it>priorJobId}!=true)continue
                val value=presentation.project(progress(packet)?:continue).let{it.copy(scanPreview=scanPreview?.takeIf{file->file.isFile&&file.length()>0})}
                latest=value
                if(value.passId!=lastPass||value.phase!=lastPhase){
                    trace.mark(Stage.NATIVE_PROGRESS,SafeInfo(nativePhase=value.phase,nativePass=value.passId,completedUnits=value.done,totalUnits=value.total))
                    lastPass=value.passId;lastPhase=value.phase
                }
                if(onProgress!=null){if(progressOnMain)withContext(Dispatchers.Main.immediate){onProgress(value)}else onProgress(value)}
            }
        }
        val result=try {currentCoroutineContext().ensureActive();NativeFilmBridge.nativeExportFile(created,input.absolutePath,output.absolutePath,width,height,JSONObject(exportParameters(parameters,budget,preImaging)).apply{workspace?.let{put("checkpointDirectory",File(it,"checkpoint").absolutePath);if(cacheDiskReserveBytes>0)put("exportReuseReserveBytes",cacheDiskReserveBytes)}}.toString())}
        finally {withContext(NonCancellable){
            polling.cancelAndJoin()
            // A short/failed/cancelled job can end before the first 400ms poll.
            // The native job retains its route decision until the next begin.
            progressPacket(created)?.let { recordGpuMetadata(it) }
        }}
        // A short stage or checkpoint restore can finish between400ms polls. No new render.
        if(onProgress!=null && JSONObject(result).optBoolean("ok") && scanPreview!=null && scanPreview.isFile && scanPreview.length()>0){
            val ready=(latest ?: EngineProgress(1,1,dev.filmroom.diagnostics.NativePhase.PRINTING_SCANNING,1,1)).copy(display=ExportDisplayProgress(6,1,1),scanPreview=scanPreview)
            if(progressOnMain)withContext(Dispatchers.Main.immediate){onProgress(ready)}else onProgress(ready)
        }
        result
    }
    /** Consumes an export-only decoded bitmap; never pass the displayed preview. */
    suspend fun renderFile(source: Bitmap, parameters: EditorState, onProgress:((EngineProgress)->Unit)?=null): Bitmap = ownedBitmap { capture -> OperationTrace.run(Operation.EXPORT_RENDER,parameters.safeInfo(source.width,source.height)) { trace -> cancelPrewarm();renderMutex.withLock { withContext(Dispatchers.IO) {
        require(!parameters.preImagingBorder){"Pre-imaging exports require the owned composite and source metering contract"}
        requireBitmapOutputColor(parameters.outputColorSpace)
        check(unavailable==null) { unavailable!! }
        val job=currentCoroutineContext()
        val width=source.width;val height=source.height;val count=width.toLong()*height
        require(width in 1..4096 && height in 1..4096) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ed15a09dd74c) }
        // Authoritative native planning is repeated below after a handle is ready.
        // Keep this minimum preflight before even staging the input float file.
        if(android.os.StatFs(context.cacheDir.absolutePath).availableBytes<count*12+64L*1024*1024)throw EngineException(EngineFailure.STORAGE)
        val input=File.createTempFile("film-input-", ".rgb", context.cacheDir)
        val output=File(context.cacheDir,input.name+".output")
        var resultBitmap:Bitmap?=null
        var delivered=false
        try {
            val row=IntArray(width);val bytes=ByteBuffer.allocate(width*12).order(ByteOrder.LITTLE_ENDIAN)
            val linear=FloatArray(256) { i -> val v=i/255.0;(if(v<=0.04045)v/12.92 else ((v+0.055)/1.055).pow(2.4)).toFloat() }
            input.outputStream().buffered().use { stream ->
                for(y in 0 until height){job.ensureActive();source.getPixels(row,0,width,0,y,width,1);bytes.clear()
                    for(c in row){bytes.putFloat(linear[(c ushr 16)and 255]);bytes.putFloat(linear[(c ushr 8)and 255]);bytes.putFloat(linear[c and 255])}
                    stream.write(bytes.array())
                }
            }
            source.recycle()
            val data=copyData();job.ensureActive();val budget=memoryBudgetMiB()
            trace.mark(Stage.NATIVE_ENTER,SafeInfo(width=width,height=height,memoryBudgetMiB=budget))
            val created=ensureHandle(data)
            val result:String
            try {job.ensureActive();val plan=planForHandle(created,width,height,parameters,budget);requireExportSpace(plan,true);result=withExportReservation(plan.workingBytes){exportWithProgress(created,input,output,width,height,parameters,budget,trace,onProgress)}}
            catch(t:Throwable){resetHandle();throw t}
            job.ensureActive()
            val metadata=JSONObject(result)
            trace.mark(Stage.NATIVE_EXIT,ExportExecutionInfo.fromNativeResult(metadata))
            checkNativeResult(metadata)
            check(output.length()==count*12){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_77f7a824adc1)}
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);resultBitmap=bitmap
            output.inputStream().buffered().use { stream ->
                for(y in 0 until height){job.ensureActive();var read=0
                    while(read<bytes.capacity()){val n=stream.read(bytes.array(),read,bytes.capacity()-read);check(n>0){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_84ccf8f71d0d)};read+=n};bytes.rewind()
                    fun encode(v:Float):Int{check(v.isFinite()){ dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0db55ca8317f) };val x=v.coerceIn(0f,1f).toDouble();return ((if(x<=0.0031308)x*12.92 else 1.055*x.pow(1.0/2.4)-0.055)*255+0.5).toInt().coerceIn(0,255)}
                    for(x in 0 until width){val r=encode(bytes.float);val g=encode(bytes.float);val b=encode(bytes.float);row[x]=(0xff shl 24)or(r shl 16)or(g shl 8)or b}
                    bitmap.setPixels(row,0,width,0,y,width,1)
                }
            }
            job.ensureActive();delivered=true;capture(bitmap);bitmap
        } finally { input.delete();output.delete();if(!source.isRecycled)source.recycle();if(!delivered)resultBitmap?.recycle() }
    } } } }

    /** Handles prompt cancellation after a worker succeeds but before its result is delivered. */
    private suspend fun ownedBitmap(work:suspend ((Bitmap)->Unit)->Bitmap):Bitmap {
        var produced:Bitmap?=null;var handedOff=false
        try {val result=work {produced=it};handedOff=true;return result}
        finally {if(!handedOff)produced?.let {if(!it.isRecycled)it.recycle()}}
    }

    /** Returns an owned, private linear-RGB output spool. Caller deletes it after encoding. */
    suspend fun exportSpool(input: File, width:Int, height:Int, parameters:EditorState,workspace:File?=null,progressOnMain:Boolean=true,cacheDiskReserveBytes:Long=0,preImaging:JSONObject?=null,onProgress:((EngineProgress)->Unit)?=null):File = ownedFile { capture -> OperationTrace.run(Operation.EXPORT_RENDER,parameters.safeInfo(width,height)) { trace ->
        var produced:File?=null;var handedOff=false
        try {
            cancelPrewarm()
            val file=renderMutex.withLock { withContext(Dispatchers.IO) {
                completedEncodedReuse=null
                val job=currentCoroutineContext();job.ensureActive()
                val count=width.toLong()*height
                require(width>0 && height>0 && count<=150_000_000L){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3ac8122c5f15)}
                check(input.length()==count*12){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ed2785856a9e)}
                val output=File(workspace?:context.cacheDir,"film-result-${java.util.UUID.randomUUID()}.rgb")
                produced=output
                val data=copyData();job.ensureActive();val budget=memoryBudgetMiB()
                trace.mark(Stage.NATIVE_ENTER,SafeInfo(width=width,height=height,memoryBudgetMiB=budget))
                val created=ensureHandle(data)
                val raw:String
                try {job.ensureActive();val plan=planForHandle(created,width,height,parameters,budget,preImaging);requireExportSpace(plan,true,workspace);raw=withExportReservation(plan.workingBytes){exportWithProgress(created,input,output,width,height,parameters,budget,trace,onProgress,workspace,progressOnMain,cacheDiskReserveBytes,preImaging)}}
                catch(t:Throwable){resetHandle();throw t}
                job.ensureActive()
                val result=JSONObject(raw)
                trace.mark(Stage.NATIVE_EXIT,ExportExecutionInfo.fromNativeResult(result))
                checkNativeResult(result)
                check(output.length()==count*12){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a7c774a3bd73)}
                completedEncodedReuse=if(preImaging!=null)null else optionalEncodedReuse {
                    val receipt=result.optJSONObject("encodedReuse") ?: return@optionalEncodedReuse null
                    val probe=receipt.optJSONObject("probe") ?: return@optionalEncodedReuse null
                    val identity=encodedIdentity(data,width,height,parameters,budget,probe) ?: return@optionalEncodedReuse null
                    EncodedReuseIdentity(identity,receipt.toString())
                }
                output
            } }
            handedOff=true;capture(file);file
        } finally {if(!handedOff)produced?.delete()}
    } }
    private suspend fun ownedFile(work:suspend ((File)->Unit)->File):File {
        var produced:File?=null;var handedOff=false
        try {val result=work{produced=it};handedOff=true;return result}
        finally {if(!handedOff)produced?.delete()}
    }
    internal fun checkNativeResult(result:JSONObject) {
        if(result.optBoolean("ok"))return
        resetHandle()
        throw EngineException(when(result.optString("code")){
            "GPU_COMPLETION_UNCERTAIN"->EngineFailure.GPU_COMPLETION_UNCERTAIN
            "GPU_UNAVAILABLE"->EngineFailure.UNAVAILABLE
            "GPU_BINDING_LIMIT"->EngineFailure.BUDGET
            "CANCELLED"->EngineFailure.CANCELLED
            "IMAGE_TOO_LARGE","INVALID_DIMENSIONS","INVALID_INPUT"->EngineFailure.IMAGE_SIZE
            "MEMORY_BUDGET_TOO_SMALL","MEMORY_BUDGET","BUDGET_EXCEEDED"->EngineFailure.BUDGET
            "UNSUPPORTED_PROFILE","UNKNOWN_STOCK"->EngineFailure.PROFILE
            "INVALID_PARAMS","UNSUPPORTED_PARAMETER"->EngineFailure.PARAMETERS
            "INVALID_PATH","EXPORT_PATH_POLICY"->EngineFailure.PATH
            "CHECKPOINT_INVALID"->EngineFailure.INCOMPLETE
            "IO_ERROR"->EngineFailure.STORAGE
            else->EngineFailure.INTERNAL
        },NativeEngineCode.fromId(result.optString("code")),NativeEngineStage.fromId(result.optString("stage")))
    }
    private suspend fun <T> withExportReservation(bytes:Long,work:suspend ()->T):T =
        ExportWorkAdmissions.run { withNativeReservation(bytes,work) }
    private suspend fun <T> withNativeReservation(bytes:Long,work:suspend ()->T):T =
        try {NativeWorkReservations.run(bytes,{NativeWorkReservations.snapshot(context)},work)}
        catch(_:NativeWorkUnavailable){throw EngineException(EngineFailure.BUDGET)}
    private fun memoryBudgetMiB():Int {
        val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info=android.app.ActivityManager.MemoryInfo();manager.getMemoryInfo(info)
        return NativeMemoryPolicy.chooseBudgetMiB(info.availMem,info.threshold,info.lowMemory) ?: throw EngineException(EngineFailure.BUDGET)
    }
    private fun exportParameters(parameters:EditorState,budget:Int,preImaging:JSONObject?):String {
        require(parameters.preImagingBorder==(preImaging!=null)){"Export border stage and composite contract must agree"}
        return JSONObject(nativeParameters(parameters,"export",budget)).apply{preImaging?.let{put("preImaging",it)}}.toString()
    }
    private fun nativeParameters(parameters:EditorState,mode:String,budget:Int)=JSONObject(parameters.json(mode, includePresentation=false)).put("memoryBudgetMiB",budget).toString()

}

/** Legacy Bitmap export has sRGB storage; wide-gamut exports must use the linear spool path. */
internal fun requireBitmapOutputColor(color:String) { require(color=="sRGB") { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_84fe057f0854) } }
