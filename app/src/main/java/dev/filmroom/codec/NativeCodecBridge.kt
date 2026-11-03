package dev.filmroom.codec

import java.io.File
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeMemoryPolicy
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.engine.NativeWorkUnavailable
import kotlinx.coroutines.*
import org.json.JSONObject
import dev.filmroom.io.PhotoException
import dev.filmroom.diagnostics.CodecErrorCode
import dev.filmroom.diagnostics.CodecStage
import dev.filmroom.diagnostics.RawTechnicalInfo

interface CodecNativeApi {
    val available: Boolean
    fun nativeCreate(root: String): Long
    fun nativeProbe(handle: Long, input: String, budget: String): String
    fun nativeDecode(handle: Long, input: String, output: String, budget: String): String
    fun nativeEncode(handle: Long, input: String, output: String, parameters: String): String
    fun nativeCancel(handle: Long)
    fun nativeDestroy(handle: Long)
}


object NativeCodecBridge:CodecNativeApi {
    override val available: Boolean = runCatching { System.loadLibrary("film_codecs"); true }.getOrDefault(false)
    external override fun nativeCreate(root: String): Long
    external override fun nativeProbe(handle: Long, input: String, budget: String): String
    external override fun nativeDecode(handle: Long, input: String, output: String, budget: String): String
    external override fun nativeEncode(handle: Long, input: String, output: String, parameters: String): String
    external override fun nativeCancel(handle: Long)
    external override fun nativeDestroy(handle: Long)
}

/** One fresh handle per operation: cancellation is sticky and cannot be lost/reset. */
class PhotoCodecs(private val root: File, private val api:CodecNativeApi=NativeCodecBridge, private val memorySnapshot:()->NativeMemorySnapshot={NativeWorkReservations.unavailableMemory}, private val exportJobsRoot:File?=null, private val rawBatchPreference:()->dev.filmroom.model.RawBatchMode={dev.filmroom.model.RawBatchMode.AUTO}, private val memoryBudgetMiB:()->Int={256}) {
    val available get() = api.available
    private suspend fun cameraMetadataBytes(input:File):Long=withContext(Dispatchers.IO) {
        // A resource hint, not a format allowlist. Never read outside the native root.
        if(input.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
            RawMetadataAdmission.estimatedBytes(input) else 0L
    }
    fun snapshotRawBatch():RawBatchSnapshot=RawBatchSnapshot(rawBatchPreference())
    suspend fun decode(input: File, output: File): JSONObject {
        // Freeze before metadata IO or ledger wait. Repository carries the selection from import start.
        val batch=currentCoroutineContext()[RawBatchSnapshot] ?: snapshotRawBatch()
        val request=budget(cameraRaw=cameraMetadataBytes(input)>0).put("rawBatch",batch.mode.id)
        val reservedBytes=request.getLong("memoryBudgetMiB")*NativeMemoryPolicy.MIB
        return execute(heavyBytes=reservedBytes) { handle ->
            api.nativeDecode(handle,input.canonicalPath,output.canonicalPath,request.toString())
        }
    }
    suspend fun probe(input: File): JSONObject {
        val metadataBytes=cameraMetadataBytes(input)
        val request=budget(metadataOnly=true,cameraRaw=metadataBytes>0).put("metadataOnly",true)
        return execute(metadataBytes=metadataBytes) { handle ->
            api.nativeProbe(handle,input.canonicalPath,request.toString())
        }
    }
    suspend fun encode(input: File, output: File, width: Int, height: Int, format: String, quality: Int, outputColorSpace:String="sRGB"): JSONObject {
        val scope=withContext(Dispatchers.IO){CodecExportScope.select(root,exportJobsRoot,input,output)}
        val request=budget().put("width",width).put("height",height).put("format",format).put("quality",quality).put("outputColorSpace",outputColorSpace)
        val reservedBytes=request.getLong("memoryBudgetMiB")*NativeMemoryPolicy.MIB
        return execute(heavyBytes=reservedBytes,operationRoot=scope,failureReason=PhotoException.Reason.EXPORT) { handle ->
            api.nativeEncode(handle,input.canonicalPath,output.canonicalPath,request.toString())
        }
    }
    private fun budget(metadataOnly:Boolean=false,cameraRaw:Boolean=false):JSONObject {
        val cap=if(cameraRaw)256 else 128
        val requested=if(metadataOnly)cap else memoryBudgetMiB()
        if(requested<128)throw PhotoException(PhotoException.Reason.TOO_LARGE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_df30ae362f2b))
        return JSONObject().put("memoryBudgetMiB",minOf(cap,requested)).put("diskBudgetBytes",(root.usableSpace-16L*1024*1024).coerceAtLeast(0))
    }
    private suspend fun execute(heavyBytes:Long=0,metadataBytes:Long=0,operationRoot:File=root,failureReason:PhotoException.Reason=PhotoException.Reason.UNREADABLE,work: (Long)->String): JSONObject = coroutineScope {
        if (!available) throw PhotoException(PhotoException.Reason.UNSUPPORTED,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_2c28d802618b))
        ensureActive()
        val handle = api.nativeCreate(operationRoot.canonicalPath)
        if(handle==0L) throw PhotoException(PhotoException.Reason.UNREADABLE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_41db58446aa4),codecCode=CodecErrorCode.NATIVE_HANDLE_CREATE_FAILED,codecStage=CodecStage.NATIVE_CREATE)
        // UNDISTPATCHED installs the cancellation hook before worker dispatch. The
        // watcher always cancels on scope cancellation, even while JNI is blocking.
        val watcher=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { api.nativeCancel(handle) }
        }
        try {
            val result=withContext(Dispatchers.IO) {
                ensureActive()
                val text=if(heavyBytes>0 || metadataBytes>0) {
                    // The native JSON and ledger share one frozen per-operation budget.
                    // Camera RAW caps at 256 MiB; DNG/export stay at 128 MiB.
                    val bytes=if(heavyBytes>0)heavyBytes else metadataBytes
                    try {NativeWorkReservations.run(bytes,memorySnapshot){work(handle)}}
                    catch(_:NativeWorkUnavailable){throw PhotoException(PhotoException.Reason.TOO_LARGE,dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_93313da164ac))}
                }else work(handle)
                ensureActive();JSONObject(text)
            }
            ensureActive()
            if(!result.optBoolean("ok")) {
                val error=result.optJSONObject("error") ?: result
                val safeCode=CodecErrorCode.fromId(error.optString("code"))
                val safeStage=CodecStage.fromId(error.optString("stage"))
                val code=safeCode.name
                if(code=="CANCELLED")throw CancellationException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c2145ea571e4))
                val message=when(code){
                    "UNSUPPORTED_CAMERA_RAW_VARIANT"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.camera_raw_variant_unsupported)
                    "UNSUPPORTED_CAMERA"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.camera_raw_profile_unsupported)
                    "UNSUPPORTED_COMPRESSION","UNSUPPORTED_DNG_VARIANT","UNSUPPORTED_FORMAT"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_0105eb1c06a7)
                    "UNSUPPORTED_DNG_OPCODE"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_46ac8768982e)
                    "UNSUPPORTED_DNG_CALIBRATION"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_c946e8a02212)
                    "IO_ERROR"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_19b3b0255d08)
                    "INVALID_INPUT"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_199d9706e7a1)
                    "UNSUPPORTED_COLOR_SPACE"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_e92a0c3cbaef)
                    "PIXEL_LIMIT"->if(failureReason==PhotoException.Reason.EXPORT)dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_d6bc69a0a6a8)else dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_5c55db3ba035)
                    "DISK_BUDGET","NO_SPACE"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_6bceec6d29d6)
                    "MEMORY_BUDGET"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_d171c3322a9c)
                    "INVALID_COLOR_METADATA"->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_56858cdae70f)
                    else->dev.filmroom.i18n.UiMessage.of(dev.filmroom.R.string.text_aff865b41066)
                }
                throw PhotoException(failureReason,message,codecCode=safeCode,codecStage=safeStage,rawTechnical=RawTechnicalInfo.fromNative(result.optJSONObject("technical")))
            }
            result
        } finally {
            withContext(NonCancellable){watcher.cancelAndJoin()}
            api.nativeDestroy(handle)
        }
    }
}
