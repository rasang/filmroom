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

/** No fallback cosmetic filter: absent native engine is an explicit unavailable state. */
object NativeFilmBridge {
    val loadError: String? = try { System.loadLibrary("film_engine"); null } catch (e: LinkageError) { e.message ?: dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6c9f62a53f72) }
    external fun nativeCreate(dataDirectory: String): Long
    external fun nativeRender(handle: Long, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int, paramsJson: String): String
    external fun nativeRenderWithGpuDelegate(handle: Long, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int, paramsJson: String, delegate: Any): String
    external fun nativeCleanupPreviewAfterGpuDrain(handle: Long): String
    external fun nativeCleanupExportAfterGpuDrain(handle: Long): String
    external fun nativeExportFile(handle: Long, inputPath: String, outputPath: String, width: Int, height: Int, paramsJson: String): String
    external fun nativeExportFileWithGpuDelegate(handle: Long, inputPath: String, outputPath: String, width: Int, height: Int, paramsJson: String, delegate: Any): String
    external fun nativePlanExport(handle: Long, width: Int, height: Int, paramsJson: String): String
    external fun nativeGetProgress(handle: Long): String
    external fun nativeCancel(handle: Long)
    external fun nativeDestroy(handle: Long)
}

enum class EngineFailure { UNAVAILABLE, CANCELLED, IMAGE_SIZE, BUDGET, PROFILE, PARAMETERS, STORAGE, INCOMPLETE, PATH, INTERNAL }
class EngineException(val failure:EngineFailure, val nativeEngineCode:NativeEngineCode?=null, val nativeEngineStage:NativeEngineStage?=null):java.io.IOException(engineFailureMessage(failure).render()) {
    val uiMessage get()=engineFailureMessage(failure)
}
private fun engineFailureMessage(failure:EngineFailure):dev.filmroom.i18n.UiMessage=when(failure){
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

class FilmEngine(private val context: Context) {
    val unavailable: String? get() = NativeFilmBridge.loadError?.let { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0a9765e80ef5) }
    private val renderMutex=Mutex()
    private val optimizationPreferences=OptimizationPreferences(context)
    // Only read/write while renderMutex is held; plan and execution share this snapshot.
    private var operationOptimization=OptimizationSnapshot()
    private var operationStartedNanos=0L
    private var operationProbeCapture:ProbeCaptureRequestStore.Request?=null
    private var operationGpuScience=false
    private var operationGpuScienceDispatchEnabled=false
    private var operationGpuSciencePreflightFallback=false
    private var operationGpuExposure=false
    private var operationGpuExposureDispatchEnabled=false
    private var operationGpuExposurePreflightFallback=false
    private var operationGpuDir=false
    private var operationGpuDirDispatchEnabled=false
    private var operationGpuDirPreflightFallback=false
    private var operationGpuOptical=false
    private var operationGpuOpticalDispatchEnabled=false
    private var operationGpuOpticalPreflightFallback=false
    private var operationGpuScanner=false
    private var operationGpuScannerDispatchEnabled=false
    private var operationGpuScannerPreflightFallback=false
    private var operationGpuDiffusion=false
    private var operationGpuDispatchEnabled=false
    private var operationGpuPreflightFallback=false
    private fun beginOptimizationOperation(useGpu:Boolean){
        operationProbeCapture=null;operationGpuScience=false;operationGpuScienceDispatchEnabled=false;operationGpuSciencePreflightFallback=false;operationGpuExposure=false;operationGpuExposureDispatchEnabled=false;operationGpuExposurePreflightFallback=false;operationGpuDir=false;operationGpuDirDispatchEnabled=false;operationGpuDirPreflightFallback=false;operationGpuOptical=false;operationGpuOpticalDispatchEnabled=false;operationGpuOpticalPreflightFallback=false;operationGpuScanner=false;operationGpuScannerDispatchEnabled=false;operationGpuScannerPreflightFallback=false;operationGpuDiffusion=false;operationGpuDispatchEnabled=false;operationGpuPreflightFallback=false
        val requested=optimizationPreferences.snapshot()
        operationOptimization=requested.copy(gpuResourceReuse=!dev.filmroom.BuildConfig.PERF_BUILD && requested.gpuResourceReuse && !requested.cpuComparison && useGpu && GpuReuseAdmission.admit(context))
        operationStartedNanos=System.nanoTime()
    }
    private fun selectScientificGpuOperations(mode:String,capturingCpuReference:Boolean=false){
        operationGpuDiffusion=operationOptimization.experimentalDiffusionEnabled(dev.filmroom.BuildConfig.PERF_BUILD,mode,capturingCpuReference)
        operationGpuDispatchEnabled=operationGpuDiffusion
        operationGpuScanner=operationOptimization.experimentalScannerEnabled(dev.filmroom.BuildConfig.PERF_BUILD,mode,capturingCpuReference)
        operationGpuScannerDispatchEnabled=operationGpuScanner
        operationGpuOptical=operationOptimization.experimentalOpticalEnabled(dev.filmroom.BuildConfig.PERF_BUILD,mode,capturingCpuReference)
        operationGpuOpticalDispatchEnabled=operationGpuOptical
        operationGpuExposure=operationOptimization.experimentalExposureEnabled(dev.filmroom.BuildConfig.PERF_BUILD,mode,capturingCpuReference)
        operationGpuExposureDispatchEnabled=operationGpuExposure
        operationGpuDir=operationOptimization.experimentalDirEnabled(dev.filmroom.BuildConfig.PERF_BUILD,mode,capturingCpuReference)
        operationGpuDirDispatchEnabled=operationGpuDir
        operationGpuScience=operationOptimization.experimentalScienceEnabled(dev.filmroom.BuildConfig.PERF_BUILD,mode,capturingCpuReference)
        operationGpuScienceDispatchEnabled=operationGpuScience
        if(operationGpuDiffusion || operationGpuScanner || operationGpuOptical || operationGpuExposure || operationGpuDir || operationGpuScience) { operationOptimization=operationOptimization.copy(gpuResourceReuse=false) }
    }
    private fun createGpuRouter(preview:Boolean=false):GpuExportRouter? {
        if(!(operationGpuDispatchEnabled || operationGpuScannerDispatchEnabled || operationGpuOpticalDispatchEnabled || operationGpuExposureDispatchEnabled || operationGpuDirDispatchEnabled || operationGpuScienceDispatchEnabled))return null
        return if(preview)GpuExportRouter.forPreview(context.applicationContext,cameraEnabled=operationGpuDispatchEnabled,scannerEnabled=operationGpuScannerDispatchEnabled,opticalEnabled=operationGpuOpticalDispatchEnabled,exposureEnabled=operationGpuExposureDispatchEnabled,dirEnabled=operationGpuDirDispatchEnabled,scienceEnabled=operationGpuScienceDispatchEnabled)
        else GpuExportRouter(context.applicationContext,cameraEnabled=operationGpuDispatchEnabled,scannerEnabled=operationGpuScannerDispatchEnabled,opticalEnabled=operationGpuOpticalDispatchEnabled,exposureEnabled=operationGpuExposureDispatchEnabled,dirEnabled=operationGpuDirDispatchEnabled,scienceEnabled=operationGpuScienceDispatchEnabled)
    }
    private fun optimizationSelection()=PerformanceInfo.selection(operationOptimization.exportMask,operationOptimization.cpuComparison,operationOptimization.previewBrightnessReuse,operationOptimization.gpuResourceReuse)
    private fun performanceInfo(result:JSONObject)=PerformanceInfo.fromNative(result,optimizationSelection())
    private fun recordOptimization(operation:String,result:JSONObject){optimizationPreferences.record(operation,operationOptimization,result,(System.nanoTime()-operationStartedNanos)/1_000_000.0)}
    @Volatile private var handle = 0L
    private val handleLock = Any()
    fun cancel() { synchronized(handleLock) { if(handle != 0L) NativeFilmBridge.nativeCancel(handle) } }
    private class PreviewOwner(val id:Long,lease:AutoCloseable,val source:Any) {
        val lifetime=PreviewCacheLease{try{NativeFilmBridge.nativeDestroy(id)}finally{lease.close()}}
    }
    private var previewOwner:PreviewOwner?=null
    private val optimizationObserver=optimizationPreferences.observeChoices{clearPreviewReuse()}
    fun close(){cancel();clearPreviewReuse();optimizationObserver.close()}
    /** Lifecycle/pressure eviction never frees a native buffer while JNI still borrows it. */
    fun clearPreviewReuse() {synchronized(handleLock){previewOwner?.let { owner ->
        owner.lifetime.retire()
        if(owner.lifetime.isReleased()){previewOwner=null;if(handle==owner.id)handle=0L}
    }}}
    private fun previewHandle(data:File,source:Any,enabled:Boolean):Long = synchronized(handleLock) {
        if(!enabled || previewOwner?.source?.let{it!==source}==true)clearPreviewReuse()
        previewOwner?.takeIf{!it.lifetime.isRetired()}?.let{check(handle==0L);handle=it.id;return@synchronized it.id}
        if(enabled){
            val pressure=java.util.concurrent.atomic.AtomicBoolean(false)
            val lease=try{NativeWorkReservations.retain(32L*1024*1024,{NativeWorkReservations.snapshot(context)}){pressure.set(true);clearPreviewReuse()}}catch(_:NativeWorkUnavailable){null}
            if(lease!=null){
                try {
                    if(!pressure.get()){
                        val id=NativeFilmBridge.nativeCreate(data.absolutePath)
                        if(id!=0L){previewOwner=PreviewOwner(id,lease,source);if(!pressure.get()){check(handle==0L);handle=id;return@synchronized id};clearPreviewReuse()}
                    }
                }catch(t:Throwable){lease.close();throw t}
                lease.close()
            }
        }
        NativeFilmBridge.nativeCreate(data.absolutePath).also{check(handle==0L);handle=it}
    }
    private fun finishPreview(id:Long,success:Boolean) {synchronized(handleLock){
        val owner=previewOwner?.takeIf{it.id==id}
        handle=0L
        if(owner==null)NativeFilmBridge.nativeDestroy(id)
        else {owner.lifetime.finish(success);if(owner.lifetime.isReleased())previewOwner=null}
    }}

    private suspend fun copyData(): File {
        val copyJob = currentCoroutineContext()
        val root = File(context.filesDir, "engine-data")
        fun copy(asset: String, dest: File) {
            val children = context.assets.list(asset) ?: emptyArray()
            if(children.isNotEmpty()) { dest.mkdirs(); children.forEach { copy("$asset/$it", File(dest,it)) } }
            else {
                if(asset == PackedSpectralLut.ASSET) {
                    context.assets.open(asset).use { PackedSpectralLut.install(it, File(dest.parentFile, PackedSpectralLut.ORIGINAL_NAME), context.filesDir, { copyJob.ensureActive() }) }
                    return
                }
                dest.parentFile?.mkdirs()
                val bytes=context.assets.open(asset).use { it.readBytes() }
                if(!dest.exists() || !dest.readBytes().contentEquals(bytes)) {
                    val temporary=File.createTempFile("asset-", ".tmp", dest.parentFile)
                    try { temporary.outputStream().use { it.write(bytes); it.flush() }; check(temporary.renameTo(dest)) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4c0c1ba5f426) } }
                    finally { temporary.delete() }
                }
            }
        }
        copy("engine-data",root)
        return root
    }
    internal fun previewEdge(settled:Boolean)=NativeMemoryPolicy.previewEdge(memoryBudgetMiB(),settled)
    suspend fun render(source: Bitmap, parameters: EditorState, mode: String, photo:PhotoSource?=null,settled:Boolean=false): Bitmap = ownedBitmap { capture -> OperationTrace.run(Operation.PREVIEW_RENDER,parameters.safeInfo(source.width,source.height)) { trace -> renderMutex.withLock { withContext(Dispatchers.Default) {
        beginOptimizationOperation(parameters.useGpu)
        selectScientificGpuOperations(mode)
        check(unavailable == null) { unavailable!! }
        val contextJob = currentCoroutineContext()
        val budget=memoryBudgetMiB()
        val edge=NativeMemoryPolicy.previewEdge(budget,settled)
        // RAW uses its unclipped linear spool. The display Bitmap is only a comparison
        // image; decoding it here would irreversibly clip highlights before metering.
        val raw=photo?.linearPreview(edge)
        val effective=if(raw==null && maxOf(source.width,source.height)>edge){val scale=edge.toDouble()/maxOf(source.width,source.height);Bitmap.createScaledBitmap(source,(source.width*scale).toInt().coerceAtLeast(1),(source.height*scale).toInt().coerceAtLeast(1),true)}else source
        try {
        val width=raw?.width ?: effective.width; val height=raw?.height ?: effective.height
        val count=Math.multiplyExact(width,height)
        require(count <= NativeMemoryPolicy.DETAIL_PREVIEW_EDGE*NativeMemoryPolicy.DETAIL_PREVIEW_EDGE) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c7b93a82c0c6) }
        val input=raw?.buffer() ?: ByteBuffer.allocateDirect(count*12).order(ByteOrder.nativeOrder())
        val output=ByteBuffer.allocateDirect(count*12).order(ByteOrder.nativeOrder())
        val ints=IntArray(width)
        val linear=FloatArray(256) { i -> val v=i/255.0; (if(v<=0.04045) v/12.92 else ((v+0.055)/1.055).pow(2.4)).toFloat() }
        try {if(raw==null)for(y in 0 until height) {
            contextJob.ensureActive(); effective.getPixels(ints,0,width,0,y,width,1)
            for(c in ints) { input.putFloat(linear[(c ushr 16) and 255]); input.putFloat(linear[(c ushr 8) and 255]); input.putFloat(linear[c and 255]) }
        }}finally{if(effective!==source)effective.recycle()}
        input.rewind(); output.rewind()
        val data=copyData(); contextJob.ensureActive()
        trace.mark(Stage.PREVIEW_PREPARE,SafeInfo(width=width,height=height,memoryBudgetMiB=budget,performance=optimizationSelection()))
        val gpuPreview=createGpuRouter(preview=true)
        val reuse=gpuPreview==null && operationOptimization.previewBrightnessReuse && (operationOptimization.cpuComparison || !parameters.useGpu)
        var created=previewHandle(data,photo ?: source,reuse)
        var result:String
        var attempt=0
        while(true){
            // Admission refusal falls back to ordinary computation, never an unreserved cache.
            val admittedReuse=synchronized(handleLock){previewOwner?.id==created}
            check(created != 0L) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ba4205340595) }
            var nativeSuccess=false
            var retryUncached=false
            val quarantine=GpuResourceQuarantine()
            try {
                contextJob.ensureActive()
                trace.mark(Stage.PREVIEW_ADMISSION_WAIT)
                result=withNativeReservation(NativeWorkReservations.previewEstimate(width,height),quarantine.retention){
                    trace.mark(Stage.PREVIEW_ADMITTED)
                    // A passive owner may be evicted while waiting for active admission.
                    // Only actual JNI borrowing defers destruction, never the wait itself.
                    val useCache=synchronized(handleLock){
                        val owner=previewOwner?.takeIf{it.id==created && !it.lifetime.isRetired()}
                        if(owner!=null && owner.lifetime.borrow()){true}else{
                            if(admittedReuse || handle!=created){created=NativeFilmBridge.nativeCreate(data.absolutePath);check(created!=0L);handle=created}
                            false
                        }
                    }
                    val params=JSONObject(nativeParameters(parameters,mode,budget)).put("previewBrightnessReuse",useCache).toString()
                    try {
                        previewNativeBoundary(
                            mark={ stage -> trace.mark(stage,if(stage==Stage.NATIVE_ENTER)SafeInfo(width=width,height=height,memoryBudgetMiB=budget,performance=optimizationSelection())else SafeInfo()) },
                            readProgress={ progress(created) },
                            recordProgress={ value -> trace.mark(Stage.NATIVE_PROGRESS,SafeInfo(nativePhase=value.phase,nativePass=value.passId,nativeUpdateSerial=value.updateSerial,completedUnits=value.done,totalUnits=value.total)) },
                            now={ android.os.SystemClock.elapsedRealtime() },
                        ) {
                            if(gpuPreview==null)NativeFilmBridge.nativeRender(created,input,output,width,height,params)
                            else NativeFilmBridge.nativeRenderWithGpuDelegate(created,input,output,width,height,params,gpuPreview)
                        }
                    } finally {
                        // Polling has joined before helper drain or handle destruction. The
                        // parent admission and buffers remain owned until every helper is dead.
                        if(gpuPreview!=null)withContext(NonCancellable){
                            runCatching{trace.mark(Stage.GPU_DRAIN_START)}
                            if(gpuPreview.awaitSafeTermination()){
                                checkPreviewGpuCleanup(NativeFilmBridge.nativeCleanupPreviewAfterGpuDrain(created))
                                runCatching{trace.mark(Stage.GPU_DRAIN_END)}
                            } else {
                                val retainedHandle=created
                                quarantine.retain(gpuPreview,listOf(input,output,photo ?: source)) {
                                    checkPreviewGpuCleanup(NativeFilmBridge.nativeCleanupPreviewAfterGpuDrain(retainedHandle))
                                    NativeFilmBridge.nativeDestroy(retainedHandle)
                                }
                                synchronized(handleLock){if(handle==retainedHandle)handle=0L}
                                throw EngineException(EngineFailure.INCOMPLETE)
                            }
                        }
                    }
                }
                contextJob.ensureActive();nativeSuccess=JSONObject(result).optBoolean("ok")
            } catch(e:EngineException){
                if(e.failure==EngineFailure.BUDGET && admittedReuse && attempt==0){retryUncached=true;result=""}else throw e
            } finally {if(!quarantine.retained)finishPreview(created,nativeSuccess)}
            if(!retryUncached)break
            // Pressure may revoke a passive cache while the active render is waiting.
            // Drop both native cache and its lease before one ordinary admission retry.
            contextJob.ensureActive();attempt++;created=previewHandle(data,photo ?: source,false)
        }
        contextJob.ensureActive()
        val metadata=JSONObject(result)
        recordOptimization("preview",metadata)
        trace.mark(Stage.NATIVE_EXIT,GpuRuntimeInfo.fromNativeResult(metadata).copy(performance=performanceInfo(metadata)))
        checkNativeResult(metadata)
        val rendered=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            output.rewind()
            fun encode(v: Float): Int { check(v.isFinite()) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1c50d5872b77) }; val x=v.coerceIn(0f,1f).toDouble(); return ((if(x<=0.0031308) x*12.92 else 1.055*x.pow(1.0/2.4)-0.055)*255.0+0.5).toInt().coerceIn(0,255) }
            for(y in 0 until height) {
                contextJob.ensureActive()
                for(x in 0 until width) { val r=encode(output.float); val g=encode(output.float); val b=encode(output.float); ints[x]=(0xff shl 24) or (r shl 16) or (g shl 8) or b }
                rendered.setPixels(ints,0,width,0,y,width,1)
            }
            rendered.also(capture)
        } catch(t: Throwable) { rendered.recycle(); throw t }
        } finally {if(effective!==source&&!effective.isRecycled)effective.recycle()}
    } } } }
    suspend fun planExport(width:Int,height:Int,parameters:EditorState):NativeExportPlan = OperationTrace.run(Operation.EXPORT_PLAN,parameters.safeInfo(width,height)) { trace ->
        renderMutex.withLock { withContext(Dispatchers.IO) {
        beginOptimizationOperation(parameters.useGpu);clearPreviewReuse()
            if(unavailable!=null)throw EngineException(EngineFailure.UNAVAILABLE)
            val job=currentCoroutineContext();val budget=memoryBudgetMiB();val data=copyData();job.ensureActive()
            val created=NativeFilmBridge.nativeCreate(data.absolutePath);check(created!=0L){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ba4205340595)}
            synchronized(handleLock){check(handle==0L);handle=created}
            try {
                job.ensureActive();val plan=planForHandle(created,width,height,parameters,budget)
                trace.mark(Stage.NATIVE_EXIT,SafeInfo(width=width,height=height,memoryBudgetMiB=budget,estimatedWorkingBytes=plan.workingBytes))
                plan
            } finally {synchronized(handleLock){NativeFilmBridge.nativeDestroy(created);handle=0L}}
        } }
    }
    private fun planForHandle(created:Long,width:Int,height:Int,parameters:EditorState,budget:Int):NativeExportPlan {
        val raw=JSONObject(try{NativeFilmBridge.nativePlanExport(created,width,height,nativeParameters(parameters,"export",budget))}catch(_:LinkageError){throw EngineException(EngineFailure.UNAVAILABLE)})
        checkNativeResult(raw);val plan=raw.getJSONObject("plan")
        check(plan.getInt("width")==width && plan.getInt("height")==height)
        return NativeExportPlan(width,height,plan.getLong("inputBytes"),plan.getLong("outputBytes"),plan.getLong("estimatedScratchBytes"),plan.getLong("estimatedTotalLocalBytes"),plan.getLong("estimatedNativeWorkingBytes"),plan.getLong("checkpointReserveBytes"))
    }
    private fun requireExportSpace(plan:NativeExportPlan,inputExists:Boolean,workspace:File?=null) {
        val needed=Math.addExact(if(inputExists)ExportStoragePolicy.afterInput(plan)else ExportStoragePolicy.beforeInput(plan),if(workspace!=null)plan.checkpointReserveBytes else 0L)
        if(!ExportStoragePolicy.hasSpace(android.os.StatFs(context.cacheDir.absolutePath).availableBytes,needed))throw EngineException(EngineFailure.STORAGE)
    }
    private fun progress(created:Long):EngineProgress? = runCatching {
        val raw=synchronized(handleLock){if(handle!=created)return null;NativeFilmBridge.nativeGetProgress(created)}
        val data=JSONObject(raw)
        if(!data.optBoolean("ok")||!data.optBoolean("active")||data.optString("counterScope")!="current-pass")return null
        val phase=NativePhase.fromId(data.optString("stage"))?:return null
        EngineProgress(data.getLong("jobId"),data.getLong("passId"),phase,data.getLong("done"),data.getLong("total"),
            if(data.optString("stageCounterScope")=="stage-work" && data.optInt("displayStage") in 1..6)ExportDisplayProgress(data.getInt("displayStage"),data.getLong("stageDone"),data.getLong("stageTotal"))
            else ExportDisplayProgress.forNativePhase(phase.id), updateSerial=data.optLong("updateSerial",0).coerceAtLeast(0))
    }.getOrNull()
    private suspend fun exportWithProgress(created:Long,input:File,output:File,width:Int,height:Int,parameters:EditorState,budget:Int,trace:OperationTrace,onProgress:((EngineProgress)->Unit)?,workspace:File?=null,progressOnMain:Boolean=true,onNativeResult:((String)->Unit)?=null,quarantine:GpuResourceQuarantine):String = coroutineScope {
        // Each call owns a new handle (initial jobId0), so positive IDs identify this export.
        // Filename derives exclusively from this Kotlin-owned output, never a native supplied path.
        val scanPreview=scanPreviewForOutput(output,workspace)
        var latest:EngineProgress?=null
        val polling=launch(Dispatchers.IO) {
            val gate=NativeProgressLogGate()
            while(isActive) {
                delay(400)
                val value=(progress(created)?:continue).let{it.copy(scanPreview=scanPreview?.takeIf{file->file.isFile&&file.length()>0})}
                latest=value
                if(gate.shouldLog(value,android.os.SystemClock.elapsedRealtime())){
                    trace.mark(Stage.NATIVE_PROGRESS,SafeInfo(nativePhase=value.phase,nativePass=value.passId,nativeUpdateSerial=value.updateSerial,completedUnits=value.done,totalUnits=value.total))
                }
                if(onProgress!=null){if(progressOnMain)withContext(Dispatchers.Main.immediate){onProgress(value)}else onProgress(value)}
            }
        }
        val gpuDelegate=createGpuRouter()
        val result=try {
            currentCoroutineContext().ensureActive()
            val params=JSONObject(nativeParameters(parameters,"export",budget)).apply{workspace?.let{put("checkpointDirectory",File(it,"checkpoint").absolutePath)}}.toString()
            (if(gpuDelegate!=null)NativeFilmBridge.nativeExportFileWithGpuDelegate(created,input.absolutePath,output.absolutePath,width,height,params,gpuDelegate)
             else NativeFilmBridge.nativeExportFile(created,input.absolutePath,output.absolutePath,width,height,params)).also{value->onNativeResult?.invoke(value)}
        } finally {withContext(NonCancellable){
            try {
                if(gpuDelegate!=null && !gpuDelegate.awaitSafeTermination()){
                    quarantine.retain(gpuDelegate,listOf(input,output)) {
                        checkPreviewGpuCleanup(NativeFilmBridge.nativeCleanupExportAfterGpuDrain(created))
                        NativeFilmBridge.nativeDestroy(created)
                        output.delete()
                    }
                    synchronized(handleLock){if(handle==created)handle=0L}
                    throw EngineException(EngineFailure.INCOMPLETE)
                } else if(gpuDelegate!=null)checkPreviewGpuCleanup(NativeFilmBridge.nativeCleanupExportAfterGpuDrain(created))
            } finally {polling.cancelAndJoin()}
        }}
        // A short stage or checkpoint restore can finish between400ms polls. No new render.
        if(onProgress!=null && JSONObject(result).optBoolean("ok") && scanPreview!=null && scanPreview.isFile && scanPreview.length()>0){
            val ready=(latest ?: EngineProgress(1,1,dev.filmroom.diagnostics.NativePhase.PRINTING_SCANNING,1,1)).copy(display=ExportDisplayProgress(6,1,1),scanPreview=scanPreview)
            if(progressOnMain)withContext(Dispatchers.Main.immediate){onProgress(ready)}else onProgress(ready)
        }
        result
    }
    /** Consumes an export-only decoded bitmap; never pass the displayed preview. */
    suspend fun renderFile(source: Bitmap, parameters: EditorState, onProgress:((EngineProgress)->Unit)?=null): Bitmap = ownedBitmap { capture -> OperationTrace.run(Operation.EXPORT_RENDER,parameters.safeInfo(source.width,source.height)) { trace -> renderMutex.withLock { withContext(Dispatchers.IO) {
        beginOptimizationOperation(parameters.useGpu);clearPreviewReuse()
        selectScientificGpuOperations("export")
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
        val quarantine=GpuResourceQuarantine()
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
            trace.mark(Stage.NATIVE_ENTER,SafeInfo(width=width,height=height,memoryBudgetMiB=budget,performance=optimizationSelection()))
            val created=NativeFilmBridge.nativeCreate(data.absolutePath);check(created!=0L){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ba4205340595)}
            synchronized(handleLock){check(handle==0L);handle=created}
            val result:String
            try {job.ensureActive();val plan=planForHandle(created,width,height,parameters,budget);requireExportSpace(plan,true);result=withExportReservation(plan.workingBytes,quarantine.retention){exportWithProgress(created,input,output,width,height,parameters,budget,trace,onProgress,quarantine=quarantine)}}
            finally { if(!quarantine.retained)synchronized(handleLock){NativeFilmBridge.nativeDestroy(created);handle=0L} }
            job.ensureActive()
            val metadata=JSONObject(result)
            recordOptimization("export",metadata)
            trace.mark(Stage.NATIVE_EXIT,ExportExecutionInfo.fromNativeResult(metadata).copy(performance=performanceInfo(metadata)))
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
        } finally { input.delete();if(!quarantine.retained)output.delete();if(!source.isRecycled)source.recycle();if(!delivered)resultBitmap?.recycle() }
    } } } }

    /** Handles prompt cancellation after a worker succeeds but before its result is delivered. */
    private suspend fun ownedBitmap(work:suspend ((Bitmap)->Unit)->Bitmap):Bitmap {
        var produced:Bitmap?=null;var handedOff=false
        try {val result=work {produced=it};handedOff=true;return result}
        finally {if(!handedOff)produced?.let {if(!it.isRecycled)it.recycle()}}
    }

    /** Returns an owned, private linear-RGB output spool. Caller deletes it after encoding. */
    suspend fun exportSpool(input: File, width:Int, height:Int, parameters:EditorState,workspace:File?=null,progressOnMain:Boolean=true,onProgress:((EngineProgress)->Unit)?=null):File = ownedFile { capture -> OperationTrace.run(Operation.EXPORT_RENDER,parameters.safeInfo(width,height)) { trace ->
        var produced:File?=null;var handedOff=false
        var probeRequest:ProbeCaptureRequestStore.Request?=null;var probeResult:JSONObject?=null;var probeSuccess=false
        val quarantine=GpuResourceQuarantine()
        try {
            val file=renderMutex.withLock { withContext(Dispatchers.IO) {
                beginOptimizationOperation(parameters.useGpu);clearPreviewReuse()
                probeRequest=ProbeCaptureRequestStore.consume(context);operationProbeCapture=probeRequest
                selectScientificGpuOperations("export",operationProbeCapture!=null)
                val job=currentCoroutineContext();job.ensureActive()
                val count=width.toLong()*height
                require(width>0 && height>0 && count<=150_000_000L){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3ac8122c5f15)}
                check(input.length()==count*12){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ed2785856a9e)}
                val output=File(workspace?:context.cacheDir,"film-result-${java.util.UUID.randomUUID()}.rgb")
                produced=output
                val data=copyData();job.ensureActive();val budget=memoryBudgetMiB()
                trace.mark(Stage.NATIVE_ENTER,SafeInfo(width=width,height=height,memoryBudgetMiB=budget,performance=optimizationSelection()))
                val created=NativeFilmBridge.nativeCreate(data.absolutePath);check(created!=0L){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ba4205340595)}
                synchronized(handleLock){check(handle==0L);handle=created}
                val raw:String
                try {
                    job.ensureActive();val plan=planForHandle(created,width,height,parameters,budget);requireExportSpace(plan,true,workspace)
                    val required=Math.addExact(ExportStoragePolicy.afterInput(plan),if(workspace!=null)plan.checkpointReserveBytes else 0L)
                    if(operationProbeCapture!=null && !ExportStoragePolicy.hasSpace(android.os.StatFs(context.cacheDir.absolutePath).availableBytes,Math.addExact(required,ProbeCaptureRequestStore.DISK_BYTES)))operationProbeCapture=null
                    var nativeStarted=false
                    suspend fun render():String { nativeStarted=true;return exportWithProgress(created,input,output,width,height,parameters,budget,trace,onProgress,workspace,progressOnMain,onNativeResult={value->probeResult=runCatching{JSONObject(value)}.getOrNull()},quarantine=quarantine) }
                    val gpuPrepBytes=if(operationGpuDispatchEnabled || operationGpuScannerDispatchEnabled || operationGpuOpticalDispatchEnabled || operationGpuExposureDispatchEnabled || operationGpuDirDispatchEnabled || operationGpuScienceDispatchEnabled)2L*1024*1024 else 0L
                    raw=try {withExportReservation(Math.addExact(plan.workingBytes,Math.addExact(if(operationProbeCapture!=null)ProbeCaptureRequestStore.MEMORY_BYTES else 0L,gpuPrepBytes)),quarantine.retention){render()}}
                    catch(e:EngineException){
                        if(!nativeStarted && (operationProbeCapture!=null || operationGpuDiffusion || operationGpuScanner || operationGpuOptical || operationGpuExposure || operationGpuDir || operationGpuScience) && e.failure==EngineFailure.BUDGET){operationGpuPreflightFallback=operationGpuDiffusion;operationGpuScannerPreflightFallback=operationGpuScanner;operationGpuOpticalPreflightFallback=operationGpuOptical;operationGpuExposurePreflightFallback=operationGpuExposure;operationGpuDirPreflightFallback=operationGpuDir;operationGpuSciencePreflightFallback=operationGpuScience;operationProbeCapture=null;operationGpuDispatchEnabled=false;operationGpuScannerDispatchEnabled=false;operationGpuOpticalDispatchEnabled=false;operationGpuExposureDispatchEnabled=false;operationGpuDirDispatchEnabled=false;operationGpuScienceDispatchEnabled=false;withExportReservation(plan.workingBytes,quarantine.retention){render()}}else throw e
                    }
                }
                finally {operationProbeCapture=null;if(!quarantine.retained)synchronized(handleLock){NativeFilmBridge.nativeDestroy(created);handle=0L}}
                job.ensureActive()
                val result=JSONObject(raw)
                if(operationGpuPreflightFallback)annotateGpuPreflightFallback(result)
                if(operationGpuScannerPreflightFallback)annotateGpuScannerPreflightFallback(result)
                if(operationGpuOpticalPreflightFallback)annotateGpuOpticalPreflightFallback(result)
                if(operationGpuExposurePreflightFallback)annotateGpuExposurePreflightFallback(result)
                if(operationGpuDirPreflightFallback)annotateGpuDirPreflightFallback(result)
                if(operationGpuSciencePreflightFallback)annotateGpuSciencePreflightFallback(result)
                probeResult=result
                recordOptimization("export",result)
                trace.mark(Stage.NATIVE_EXIT,ExportExecutionInfo.fromNativeResult(result).copy(performance=performanceInfo(result)))
                checkNativeResult(result)
                check(output.length()==count*12){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a7c774a3bd73)}
                probeSuccess=true
                output
            } }
            handedOff=true;capture(file);file
        } finally {ProbeCaptureRequestStore.finish(probeRequest,probeResult,probeSuccess && handedOff);if(!handedOff && !quarantine.retained)produced?.delete()}
    } }
    private suspend fun ownedFile(work:suspend ((File)->Unit)->File):File {
        var produced:File?=null;var handedOff=false
        try {val result=work{produced=it};handedOff=true;return result}
        finally {if(!handedOff)produced?.delete()}
    }
    internal fun checkNativeResult(result:JSONObject) {
        if(result.optBoolean("ok"))return
        throw EngineException(when(result.optString("code")){
            "CANCELLED"->EngineFailure.CANCELLED
            "IMAGE_TOO_LARGE","INVALID_DIMENSIONS","INVALID_INPUT"->EngineFailure.IMAGE_SIZE
            "MEMORY_BUDGET_TOO_SMALL","MEMORY_BUDGET","BUDGET_EXCEEDED"->EngineFailure.BUDGET
            "UNSUPPORTED_PROFILE","UNKNOWN_STOCK"->EngineFailure.PROFILE
            "INVALID_PARAMS","UNSUPPORTED_PARAMETER"->EngineFailure.PARAMETERS
            "INVALID_PATH","EXPORT_PATH_POLICY","PREVIEW_PATH_POLICY"->EngineFailure.PATH
            "CHECKPOINT_INVALID","PREVIEW_DRAIN_REQUIRED","PREVIEW_STILL_RUNNING",
            "PREVIEW_CLEANUP_IDENTITY","PREVIEW_CLEANUP_FAILED",
            "GPU_CLEANUP_IDENTITY","GPU_RETAINED_LIMIT"->EngineFailure.INCOMPLETE
            "IO_ERROR","INSUFFICIENT_DISK_SPACE"->EngineFailure.STORAGE
            "UNSUPPORTED_PRECISION"->EngineFailure.UNAVAILABLE
            // Missing admission/registry evidence is not proof of insufficient disk space.
            "PREVIEW_DISK_UNAVAILABLE","PREVIEW_REGISTRY_UNAVAILABLE"->EngineFailure.INTERNAL
            else->EngineFailure.INTERNAL
        },NativeEngineCode.fromId(result.optString("code")),NativeEngineStage.fromId(result.optString("stage")))
    }
    private suspend fun <T> withExportReservation(bytes:Long,retention:NativeWorkRetention?=null,work:suspend ()->T):T =
        ExportWorkAdmissions.run { withNativeReservation(bytes,retention,work) }
    private suspend fun <T> withNativeReservation(bytes:Long,retention:NativeWorkRetention?=null,work:suspend ()->T):T =
        try {if(retention==null)NativeWorkReservations.run(bytes,{NativeWorkReservations.snapshot(context)},work)
            else NativeWorkReservations.runQuarantinable(bytes,{NativeWorkReservations.snapshot(context)},retention,work)}
        catch(_:NativeWorkUnavailable){throw EngineException(EngineFailure.BUDGET)}
    private fun memoryBudgetMiB():Int {
        val manager=context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info=android.app.ActivityManager.MemoryInfo();manager.getMemoryInfo(info)
        return NativeMemoryPolicy.chooseBudgetMiB(info.availMem,info.threshold,info.lowMemory) ?: throw EngineException(EngineFailure.BUDGET)
    }
    private fun nativeParameters(parameters:EditorState,mode:String,budget:Int)=operationOptimization.applyTo(JSONObject(parameters.json(mode, includePresentation=false)).put("memoryBudgetMiB",budget)).apply {
        applyFullGpuDiffusionPolicy(this,mode in setOf("export","preview") && operationGpuDiffusion)
        applyGpuScannerPolicy(this,mode in setOf("export","preview") && operationGpuScanner)
        applyGpuOpticalPolicy(this,mode in setOf("export","preview") && operationGpuOptical)
        applyGpuExposurePolicy(this,mode in setOf("export","preview") && operationGpuExposure)
        applyGpuDirPolicy(this,mode in setOf("export","preview") && operationGpuDir)
        applyGpuSciencePolicy(this,mode in setOf("export","preview") && operationGpuScience)
        if(mode=="export")operationProbeCapture?.let { put("gpuProbeCaptureToken",it.token);put("useGpu",false);put("gpuResourceReuse",false) }
    }.toString()

}

/** Legacy Bitmap export has sRGB storage; wide-gamut exports must use the linear spool path. */
internal fun requireBitmapOutputColor(color:String) { require(color=="sRGB") { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_84fe057f0854) } }

/** Do not relabel a resumed GPU checkpoint as CPU when this run did not need the stage. */
internal fun annotateGpuPreflightFallback(result:JSONObject) {
    val metrics=result.optJSONObject("metrics") ?: return
    val resumed=metrics.optDouble("checkpointResumeStage",Double.NaN)
    if(!resumed.isFinite() || resumed<0.0 || resumed>=3.0)return
    val gpu=metrics.optJSONObject("gpuDiffusion") ?: JSONObject().also{metrics.put("gpuDiffusion",it)}
    if(gpu.has("backend") && gpu.optString("backend")!="cpu_f64")return
    gpu.put("requested",true).put("backend","cpu_f64").put("attempted",false).put("fallbackReason","preflight_memory").put("resumed",false)
}

/** Scanner policy is independent and cannot silently enable the old density GPU. */
internal fun applyGpuScannerPolicy(parameters:JSONObject,enabled:Boolean) {
    if(enabled)parameters.put("gpuScannerExperimental",true).put("useGpu",false).put("gpuResourceReuse",false)
    else parameters.remove("gpuScannerExperimental")
}
internal fun annotateGpuScannerPreflightFallback(result:JSONObject) {
    val metrics=result.optJSONObject("metrics") ?: return
    val resumed=metrics.optDouble("checkpointResumeStage",Double.NaN)
    if(!resumed.isFinite() || resumed<0.0 || resumed>=6.0)return
    val gpu=metrics.optJSONObject("gpuScanner") ?: JSONObject().also{metrics.put("gpuScanner",it)}
    if(gpu.has("backend") && gpu.optString("backend")!="cpu_f64")return
    gpu.put("requested",true).put("backend","cpu_f64").put("attempted",false).put("fallbackReason","preflight_memory").put("resumed",false)
}

internal fun applyGpuOpticalPolicy(parameters:JSONObject,enabled:Boolean) {
    if(enabled)parameters.put("gpuOpticalExperimental",true).put("useGpu",false).put("gpuResourceReuse",false)
    else parameters.remove("gpuOpticalExperimental")
}
internal fun annotateGpuOpticalPreflightFallback(result:JSONObject) {
    val metrics=result.optJSONObject("metrics") ?: return
    val resumed=metrics.optDouble("checkpointResumeStage",Double.NaN)
    if(!resumed.isFinite() || resumed<0.0 || resumed>=3.0)return
    val gpu=metrics.optJSONObject("gpuOptical") ?: JSONObject().also{metrics.put("gpuOptical",it)}
    if(gpu.has("backend") && gpu.optString("backend")!="cpu_f64")return
    gpu.put("requested",true).put("backend","cpu_f64").put("attempted",false).put("fallbackReason","preflight_memory").put("resumed",false)
}

internal fun applyGpuExposurePolicy(parameters:JSONObject,enabled:Boolean) {
    if(enabled)parameters.put("gpuExposureExperimental",true).put("useGpu",false).put("gpuResourceReuse",false)
    else parameters.remove("gpuExposureExperimental")
}
internal fun annotateGpuExposurePreflightFallback(result:JSONObject) {
    val metrics=result.optJSONObject("metrics") ?: return
    val resumed=metrics.optDouble("checkpointResumeStage",Double.NaN)
    if(!resumed.isFinite() || resumed<0.0 || resumed>=2.0)return
    val gpu=metrics.optJSONObject("gpuExposure") ?: JSONObject().also{metrics.put("gpuExposure",it)}
    if(gpu.has("backend") && gpu.optString("backend")!="cpu_f64")return
    gpu.put("requested",true).put("backend","cpu_f64").put("attempted",false).put("fallbackReason","preflight_memory").put("resumed",false)
}

internal fun applyGpuDirPolicy(parameters:JSONObject,enabled:Boolean) {
    if(enabled)parameters.put("gpuDirExperimental",true).put("useGpu",false).put("gpuResourceReuse",false)
    else parameters.remove("gpuDirExperimental")
}
internal fun annotateGpuDirPreflightFallback(result:JSONObject) {
    val metrics=result.optJSONObject("metrics") ?: return
    val resumed=metrics.optDouble("checkpointResumeStage",Double.NaN)
    if(!resumed.isFinite() || resumed<0.0 || resumed>=4.0)return
    val gpu=metrics.optJSONObject("gpuDir") ?: JSONObject().also{metrics.put("gpuDir",it)}
    if(gpu.has("backend") && gpu.optString("backend")!="cpu_f64")return
    gpu.put("requested",true).put("backend","cpu_f64").put("attempted",false).put("fallbackReason","preflight_memory").put("resumed",false)
}


internal fun applyGpuSciencePolicy(parameters:JSONObject,enabled:Boolean) {
    if(enabled)parameters.put("gpuScienceExperimental",true).put("useGpu",false).put("gpuResourceReuse",false)
    else parameters.remove("gpuScienceExperimental")
}
/** Never overwrite a restored GPU checkpoint or a stage with unknown provenance. */
internal fun annotateGpuSciencePreflightFallback(result:JSONObject) {
    val metrics=result.optJSONObject("metrics") ?: return
    val resumed=metrics.optDouble("checkpointResumeStage",Double.NaN)
    if(!resumed.isFinite() || resumed<0.0)return
    val science=metrics.optJSONObject("gpuScience") ?: JSONObject().also{metrics.put("gpuScience",it)}
    for((key,boundary) in mapOf("autoExposure" to 2,"highlightBoost" to 3,"exposureLog" to 4,"grain" to 5,"printExposure" to 6,"printDiffusion" to 6,"scanTail" to 6)) {
        if(resumed>=boundary)continue
        val stage=science.optJSONObject(key) ?: JSONObject().also{science.put(key,it)}
        if(stage.optBoolean("resumed") || (stage.has("backend") && stage.optString("backend")!="cpu_f64"))continue
        stage.put("requested",true).put("backend","cpu_f64").put("attempted",false).put("fallbackReason","preflight_memory").put("resumed",false)
    }
}

/** Cleanup acknowledgments contain no paths and cannot turn an unknown drain into success. */
internal fun checkPreviewGpuCleanup(raw:String) {
    val j=try{require(raw.length<=1024);JSONObject(raw)}catch(_:Exception){throw EngineException(EngineFailure.INCOMPLETE)}
    if(j.opt("ok")!=true || j.opt("cleaned") !is Boolean || j.keys().asSequence().toSet()!=setOf("ok","cleaned"))throw EngineException(EngineFailure.INCOMPLETE)
}
