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
    external fun nativeCapabilities(): String
    external fun nativeRenderBitmap(handle: Long, source: Bitmap, dest: Bitmap, width: Int, height: Int, paramsJson: String): String
    external fun nativeRenderLinearBitmap(handle: Long, input: ByteBuffer, dest: Bitmap, width: Int, height: Int, paramsJson: String): String
    external fun nativeRenderRgba8(handle: Long, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int, paramsJson: String): String
    external fun nativeCreate(dataDirectory: String): Long
    external fun nativeRender(handle: Long, input: ByteBuffer, output: ByteBuffer, width: Int, height: Int, paramsJson: String): String
    external fun nativeExportFile(handle: Long, inputPath: String, outputPath: String, width: Int, height: Int, paramsJson: String): String
    external fun nativePlanExport(handle: Long, width: Int, height: Int, paramsJson: String): String
    external fun nativeGetProgress(handle: Long): String
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

class FilmEngine(private val context: Context) {
    val unavailable: String? get() = NativeFilmBridge.loadError?.let { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0a9765e80ef5) }
    private val renderMutex=Mutex()
    @Volatile private var handle = 0L
    private val handleLock = Any()
    fun cancel() { synchronized(handleLock) { if(handle != 0L) NativeFilmBridge.nativeCancel(handle) } }
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
        val previous=handle;handle=0L;preparedHandleDirectory=null
        if(previous!=0L)NativeFilmBridge.nativeDestroy(previous)
    }
    /** Retire only once the serialized native call has returned; never free a running engine. */
    fun close() {
        synchronized(handleLock){if(closed)return;closed=true;if(handle!=0L)NativeFilmBridge.nativeCancel(handle)}
        CoroutineScope(Dispatchers.IO).launch { renderMutex.withLock { resetHandle() } }
    }
    private suspend fun copyData():File=VersionedEngineAssets.prepare(context)
    internal fun previewEdge(settled:Boolean)=NativeMemoryPolicy.previewEdge(memoryBudgetMiB(),settled)
    suspend fun render(source: Bitmap, parameters: EditorState, mode: String, photo:PhotoSource?=null,settled:Boolean=false): Bitmap = ownedBitmap { capture -> OperationTrace.run(Operation.PREVIEW_RENDER,parameters.safeInfo(source.width,source.height)) { trace ->
        val timing=currentCoroutineContext()[PreviewDiagnosticContext]?.timing
        timing?.operation(trace.id.value);timing?.mark(PreviewTimingPoint.RENDER_QUEUED)
        renderMutex.withLock { timing?.mark(PreviewTimingPoint.MUTEX_ACQUIRED);withContext(Dispatchers.Default) {
        timing?.mark(PreviewTimingPoint.WORKER_STARTED)
        if(unavailable!=null || closed)throw EngineException(EngineFailure.UNAVAILABLE)
        val job=currentCoroutineContext()
        val preparationStart=System.nanoTime()
        val budget=memoryBudgetMiB()
        val edge=NativeMemoryPolicy.previewEdge(budget,settled)
        val raw=photo?.linearPreview(edge)
        val scale=if(raw==null && maxOf(source.width,source.height)>edge)edge.toDouble()/maxOf(source.width,source.height) else 1.0
        val width=raw?.width ?: (source.width*scale).toInt().coerceAtLeast(1)
        val height=raw?.height ?: (source.height*scale).toInt().coerceAtLeast(1)
        val count=Math.multiplyExact(width,height)
        require(count<=NativeMemoryPolicy.DETAIL_PREVIEW_EDGE*NativeMemoryPolicy.DETAIL_PREVIEW_EDGE)
        val data=copyData();job.ensureActive()
        timing?.handle(synchronized(handleLock){handle==0L})
        val created=ensureHandle(data)
        val caps=try { JSONObject(NativeFilmBridge.nativeCapabilities()) } catch(_:UnsatisfiedLinkError) { throw EngineException(EngineFailure.UNAVAILABLE) }
        if(!PreviewNativeContract.supportsBitmap(caps))throw EngineException(EngineFailure.UNAVAILABLE)
        val preparationMs=(System.nanoTime()-preparationStart)/1_000_000
        // Never pool a displayed Bitmap: stale View/StateFlow readers may still own it.
        val allocationStart=System.nanoTime()
        val rendered=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val allocationMs=(System.nanoTime()-allocationStart)/1_000_000
        timing?.mark(PreviewTimingPoint.PREPARED)
        var delivered=false
        try {
            job.ensureActive()
            trace.mark(Stage.NATIVE_ENTER,SafeInfo(width=width,height=height,memoryBudgetMiB=budget,previewPreparationMs=preparationMs,previewAllocationMs=allocationMs))
            var jniWallMs:Long?=null
            timing?.mark(PreviewTimingPoint.RESERVATION_REQUESTED)
            val result=withNativeReservation(NativeWorkReservations.previewEstimate(width,height)) {
                timing?.mark(PreviewTimingPoint.JNI_ENTER)
                val jniStart=System.nanoTime()
                try {
                    if(raw==null)NativeFilmBridge.nativeRenderBitmap(created,source,rendered,width,height,nativeParameters(parameters,mode,budget))
                    else NativeFilmBridge.nativeRenderLinearBitmap(created,raw.buffer(),rendered,width,height,nativeParameters(parameters,mode,budget))
                } finally { jniWallMs=(System.nanoTime()-jniStart)/1_000_000;timing?.mark(PreviewTimingPoint.JNI_EXIT) }
            }
            job.ensureActive()
            val metadata=JSONObject(result)
            trace.mark(Stage.NATIVE_EXIT,GpuRuntimeInfo.fromNativeResult(metadata).copy(previewJniWallMs=jniWallMs))
            checkNativeResult(metadata)
            check(PreviewNativeContract.outputMatches(metadata,width,height)) { "Invalid native preview result" }
            capture(rendered);delivered=true;rendered
        } catch(t:Throwable) { resetHandle();throw t }
        finally { if(!delivered && !rendered.isRecycled)rendered.recycle() }
    } } } }
    suspend fun planExport(width:Int,height:Int,parameters:EditorState):NativeExportPlan = OperationTrace.run(Operation.EXPORT_PLAN,parameters.safeInfo(width,height)) { trace ->
        renderMutex.withLock { withContext(Dispatchers.IO) {
            if(unavailable!=null)throw EngineException(EngineFailure.UNAVAILABLE)
            val job=currentCoroutineContext();val budget=memoryBudgetMiB();val data=copyData();job.ensureActive()
            val created=ensureHandle(data)
            try {
                job.ensureActive();val plan=planForHandle(created,width,height,parameters,budget)
                trace.mark(Stage.NATIVE_EXIT,SafeInfo(width=width,height=height,memoryBudgetMiB=budget,estimatedWorkingBytes=plan.workingBytes))
                plan
            } catch(t:Throwable){resetHandle();throw t}
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
    private suspend fun exportWithProgress(created:Long,input:File,output:File,width:Int,height:Int,parameters:EditorState,budget:Int,trace:OperationTrace,onProgress:((EngineProgress)->Unit)?,workspace:File?=null,progressOnMain:Boolean=true):String = coroutineScope {
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
        val result=try {currentCoroutineContext().ensureActive();NativeFilmBridge.nativeExportFile(created,input.absolutePath,output.absolutePath,width,height,JSONObject(nativeParameters(parameters,"export",budget)).apply{workspace?.let{put("checkpointDirectory",File(it,"checkpoint").absolutePath)}}.toString())}
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
    suspend fun renderFile(source: Bitmap, parameters: EditorState, onProgress:((EngineProgress)->Unit)?=null): Bitmap = ownedBitmap { capture -> OperationTrace.run(Operation.EXPORT_RENDER,parameters.safeInfo(source.width,source.height)) { trace -> renderMutex.withLock { withContext(Dispatchers.IO) {
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
    suspend fun exportSpool(input: File, width:Int, height:Int, parameters:EditorState,workspace:File?=null,progressOnMain:Boolean=true,onProgress:((EngineProgress)->Unit)?=null):File = ownedFile { capture -> OperationTrace.run(Operation.EXPORT_RENDER,parameters.safeInfo(width,height)) { trace ->
        var produced:File?=null;var handedOff=false
        try {
            val file=renderMutex.withLock { withContext(Dispatchers.IO) {
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
                try {job.ensureActive();val plan=planForHandle(created,width,height,parameters,budget);requireExportSpace(plan,true,workspace);raw=withExportReservation(plan.workingBytes){exportWithProgress(created,input,output,width,height,parameters,budget,trace,onProgress,workspace,progressOnMain)}}
                catch(t:Throwable){resetHandle();throw t}
                job.ensureActive()
                val result=JSONObject(raw)
                trace.mark(Stage.NATIVE_EXIT,ExportExecutionInfo.fromNativeResult(result))
                checkNativeResult(result)
                check(output.length()==count*12){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a7c774a3bd73)}
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
    private fun nativeParameters(parameters:EditorState,mode:String,budget:Int)=JSONObject(parameters.json(mode, includePresentation=false)).put("memoryBudgetMiB",budget).toString()

}

/** Legacy Bitmap export has sRGB storage; wide-gamut exports must use the linear spool path. */
internal fun requireBitmapOutputColor(color:String) { require(color=="sRGB") { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_84fe057f0854) } }
