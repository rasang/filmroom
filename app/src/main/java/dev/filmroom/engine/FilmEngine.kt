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
    external fun nativeExportFile(handle: Long, inputPath: String, outputPath: String, width: Int, height: Int, paramsJson: String): String
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
    @Volatile private var handle = 0L
    private val handleLock = Any()
    fun cancel() { synchronized(handleLock) { if(handle != 0L) NativeFilmBridge.nativeCancel(handle) } }
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
        trace.mark(Stage.NATIVE_ENTER,SafeInfo(width=width,height=height,memoryBudgetMiB=budget))
        val created=NativeFilmBridge.nativeCreate(data.absolutePath)
        check(created != 0L) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ba4205340595) }
        synchronized(handleLock) { check(handle==0L); handle=created }
        val result: String
        try { contextJob.ensureActive(); result=withNativeReservation(NativeWorkReservations.previewEstimate(width,height)){NativeFilmBridge.nativeRender(created,input,output,width,height,nativeParameters(parameters,mode,budget))} }
        finally { synchronized(handleLock) { NativeFilmBridge.nativeDestroy(created); handle=0L } }
        contextJob.ensureActive()
        val metadata=JSONObject(result)
        trace.mark(Stage.NATIVE_EXIT,GpuRuntimeInfo.fromNativeResult(metadata))
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
            else ExportDisplayProgress.forNativePhase(phase.id))
    }.getOrNull()
    private suspend fun exportWithProgress(created:Long,input:File,output:File,width:Int,height:Int,parameters:EditorState,budget:Int,trace:OperationTrace,onProgress:((EngineProgress)->Unit)?,workspace:File?=null,progressOnMain:Boolean=true):String = coroutineScope {
        // Each call owns a new handle (initial jobId0), so positive IDs identify this export.
        // Filename derives exclusively from this Kotlin-owned output, never a native supplied path.
        val scanPreview=scanPreviewForOutput(output,workspace)
        var latest:EngineProgress?=null
        val polling=launch(Dispatchers.IO) {
            var lastPass=-1L;var lastPhase:NativePhase?=null
            while(isActive) {
                delay(400)
                val value=(progress(created)?:continue).let{it.copy(scanPreview=scanPreview?.takeIf{file->file.isFile&&file.length()>0})}
                latest=value
                if(value.passId!=lastPass||value.phase!=lastPhase){
                    trace.mark(Stage.NATIVE_PROGRESS,SafeInfo(nativePhase=value.phase,nativePass=value.passId,completedUnits=value.done,totalUnits=value.total))
                    lastPass=value.passId;lastPhase=value.phase
                }
                if(onProgress!=null){if(progressOnMain)withContext(Dispatchers.Main.immediate){onProgress(value)}else onProgress(value)}
            }
        }
        val result=try {currentCoroutineContext().ensureActive();NativeFilmBridge.nativeExportFile(created,input.absolutePath,output.absolutePath,width,height,JSONObject(nativeParameters(parameters,"export",budget)).apply{workspace?.let{put("checkpointDirectory",File(it,"checkpoint").absolutePath)}}.toString())}
        finally {withContext(NonCancellable){polling.cancelAndJoin()}}
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
            val created=NativeFilmBridge.nativeCreate(data.absolutePath);check(created!=0L){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ba4205340595)}
            synchronized(handleLock){check(handle==0L);handle=created}
            val result:String
            try {job.ensureActive();val plan=planForHandle(created,width,height,parameters,budget);requireExportSpace(plan,true);result=withExportReservation(plan.workingBytes){exportWithProgress(created,input,output,width,height,parameters,budget,trace,onProgress)}}
            finally { synchronized(handleLock){NativeFilmBridge.nativeDestroy(created);handle=0L} }
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
                val created=NativeFilmBridge.nativeCreate(data.absolutePath);check(created!=0L){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ba4205340595)}
                synchronized(handleLock){check(handle==0L);handle=created}
                val raw:String
                try {job.ensureActive();val plan=planForHandle(created,width,height,parameters,budget);requireExportSpace(plan,true,workspace);raw=withExportReservation(plan.workingBytes){exportWithProgress(created,input,output,width,height,parameters,budget,trace,onProgress,workspace,progressOnMain)}}
                finally {synchronized(handleLock){NativeFilmBridge.nativeDestroy(created);handle=0L}}
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
        throw EngineException(when(result.optString("code")){
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
