package dev.filmroom.diagnostics

import org.json.JSONObject

/** Canonical diagnostics values only: never serialize native driver names or error text. */
enum class GpuBackend(val id:String) { CPU("cpu"), VULKAN_ADRENO_PARTIAL("vulkan-adreno-partial"), WGPU_CHAIN("wgpu") }
enum class GpuStage(val id:String) { DENSITY_CURVE_INTERP("density_curve_interp"), FULL_RESIDENT_CHAIN("full-resident-chain"), BANDED_CHAIN("banded-chain"), TILED_CHAIN("tiled-chain") }
enum class GpuReason(val id:String) {
    USER_DISABLED("user_disabled"), UNAVAILABLE("unavailable"), ACTIVE_PARTIAL("active_partial"),
    ELIGIBLE_UNUSED("eligible_unused"), PRIOR_FAILURE("prior_failure"), DEVICE_FAILURE("device_failure"),
    NO_ADAPTER("no_adapter"), UNSUPPORTED_ADAPTER("unsupported_adapter"), LIMITS_INSUFFICIENT("limits_insufficient"),
    VALIDATION_FAILED("validation_failed"), SELF_TEST_FAILED("self_test_failed"),
    NO_MEASURED_BENEFIT("no_measured_benefit"), CALIBRATION_BUDGET("calibration_budget"),
    NUMERIC_FAILURE("numeric_failure"), READBACK_FAILED("readback_failed"),
    CALLBACK_DISCONNECTED("callback_disconnected"), TIMEOUT_DEVICE("timeout_device"),
    BATCH_VALIDATION("batch_validation"), APPROXIMATE_RESIDENT_CHAIN("approximate_resident_chain"),
    APPROXIMATE_BANDED_CHAIN("approximate_banded_chain"), APPROXIMATE_TILED_CHAIN("approximate_tiled_chain"), GPU_MEMORY_BUDGET_FALLBACK("gpu_memory_budget_fallback"),
    GPU_BINDING_LIMIT_FALLBACK("gpu_binding_limit_fallback"), GPU_UNAVAILABLE_FALLBACK("gpu_unavailable_fallback"),
    GPU_EXECUTION_FALLBACK("gpu_execution_fallback"), CPU_CHECKPOINT_RESUME("cpu_checkpoint_resume"),
    GPU_CHECKPOINT_RESTORED("gpu_checkpoint_restored"), UNKNOWN("unknown");
}

enum class GpuWorkPhase(val id:String,private val nativePhase:NativePhase) {
    FRONT("front",NativePhase.FILM_EXPOSURE), CAMERA_OPTICS("camera_optics",NativePhase.CAMERA_DIFFUSION),
    HALATION("halation",NativePhase.FILM_HALATION), FILM_DENSITY("film_density",NativePhase.FILM_DENSITY),
    DIR("dir",NativePhase.DIR_DEVELOP), GRAIN("grain",NativePhase.GRAIN_SAMPLING),
    PRINT("print",NativePhase.PRINTING_SCANNING), SCAN("scan",NativePhase.PRINTING_SCANNING),
    GLARE("glare",NativePhase.SCAN_GLARE), OUTPUT("output",NativePhase.GPU_FULL_CHAIN);
    val label:String get()=nativePhase.chinese
    companion object {fun fromId(id:String?):GpuWorkPhase?=entries.firstOrNull{it.id==id}}
}

object GpuRuntimeInfo {
    private const val MAX_COUNT=1_800_000_000L
    private val adapterPattern=Regex("(?:Qualcomm\\s+)?Adreno(?:\\s*\\(TM\\))?\\s+([0-9]{3,4})",RegexOption.IGNORE_CASE)
    private val costPattern=Regex("No measured acceleration benefit; CPU fallback \\(CPU [0-9]{1,9}\\.[0-9]{3} ms, GPU [0-9]{1,9}\\.[0-9]{3} ms including transfers\\)")
    private fun strictBoolean(source:JSONObject,key:String)=source.opt(key) as? Boolean
    private fun strictCount(source:JSONObject,key:String):Long? {
        val value=when(val raw=source.opt(key)){is Int->raw.toLong();is Long->raw;else->return null}
        return value.takeIf{it in 0..MAX_COUNT}
    }
    private fun duration(source:JSONObject):Long? {
        val number=source.opt("elapsedMs") as? Number ?: return null
        val value=number.toDouble()
        return if(value.isFinite()&&value in 0.0..604_800_000.0)value.toLong()else null
    }
    private fun reason(raw:Any?):GpuReason? {
        val value=raw as? String ?: return null
        if(value.length>240)return GpuReason.UNKNOWN
        GpuReason.entries.firstOrNull{it.id==value}?.let{return it}
        return when(value){
            "Disabled by user"->GpuReason.USER_DISABLED
            "GPU unavailable on this build/device"->GpuReason.UNAVAILABLE
            "Runtime device, numeric self-check and measured cost gate passed; partial density acceleration"->GpuReason.ACTIVE_PARTIAL
            "Eligible GPU, but no accelerated batches used in this operation"->GpuReason.ELIGIBLE_UNUSED
            "GPU disabled after prior device or numerical failure; CPU fallback"->GpuReason.PRIOR_FAILURE
            "GPU disabled after device or numerical failure; CPU fallback", "GPU device error"->GpuReason.DEVICE_FAILURE
            "No Vulkan adapter"->GpuReason.NO_ADAPTER
            "Not a supported Qualcomm Adreno Vulkan adapter"->GpuReason.UNSUPPORTED_ADAPTER
            "Adreno compute limits insufficient"->GpuReason.LIMITS_INSUFFICIENT
            "Density compute validation failed"->GpuReason.VALIDATION_FAILED
            "Adreno density startup self-check failed"->GpuReason.SELF_TEST_FAILED
            "No measured acceleration benefit; CPU fallback (calibration time budget exceeded)"->GpuReason.CALIBRATION_BUDGET
            "GPU numerical check failed; CPU fallback"->GpuReason.NUMERIC_FAILURE
            "GPU readback failed"->GpuReason.READBACK_FAILED
            "GPU callback disconnected"->GpuReason.CALLBACK_DISCONNECTED
            "GPU initialization timed out; CPU fallback until process restart",
            "GPU submission timed out or failed; CPU fallback until process restart",
            "GPU batch timeout/device error"->GpuReason.TIMEOUT_DEVICE
            "GPU initializer failed; CPU fallback until process restart"->GpuReason.DEVICE_FAILURE
            "GPU worker unavailable; CPU fallback"->GpuReason.UNAVAILABLE
            "GPU batch limit/curve validation"->GpuReason.BATCH_VALIDATION
            else->if(costPattern.matches(value))GpuReason.NO_MEASURED_BENEFIT else GpuReason.UNKNOWN
        }
    }

    /** Sticky per-job route decision, available even if the job is later cancelled.
     * No invented batch count: an execution fallback may follow completed GPU work. */
    fun fallbackFromProgress(data:JSONObject,newerThanJobId:Long?=null):SafeInfo? {
        if(newerThanJobId!=null){
            val id=when(val raw=data.opt("jobId")){is Int->raw.toLong();is Long->raw;else->return null}
            if(id<=newerThanJobId)return null
        }
        val raw=data.opt("gpuFallbackReason") as? String ?: return null
        val allowed=setOf(GpuReason.GPU_MEMORY_BUDGET_FALLBACK,GpuReason.GPU_BINDING_LIMIT_FALLBACK,
            GpuReason.GPU_UNAVAILABLE_FALLBACK,GpuReason.GPU_EXECUTION_FALLBACK,GpuReason.UNSUPPORTED_ADAPTER,GpuReason.CPU_CHECKPOINT_RESUME)
        val reason=allowed.firstOrNull{it.id==raw} ?: return null
        return SafeInfo(gpuRequested=true,gpuActive=false,gpuBackend=GpuBackend.CPU,gpuReason=reason)
    }

    fun workFromProgress(data:JSONObject,newerThanJobId:Long):SafeInfo? {
        val id=when(val raw=data.opt("jobId")){is Int->raw.toLong();is Long->raw;else->return null}
        if(id<=newerThanJobId)return null
        val work=dev.filmroom.engine.GpuWorkProgress.fromJson(data)?:return null
        return SafeInfo(gpuWorkPhase=work.phase,gpuFailedPhase=work.failedPhase,gpuTileIndex=work.tileIndex,
            gpuTileTotal=work.tileTotal,gpuBatchIndex=work.batchIndex,gpuCompletedSubmits=work.completedSubmits,
            gpuBatchElapsedMs=work.batchElapsedMs,gpuChainWaitMs=work.chainWaitMs,gpuFailedElapsedMs=work.failedElapsedMs)
    }

    /** Preview uses gpu; file export uses metrics.gpu. Missing/invalid telemetry is not GPU proof. */
    fun fromNativeResult(result:JSONObject):SafeInfo {
        // AE disabled/missing is unknown, not a fabricated zero. Never coerce strings.
        val envelope=if(result.has("meteredEv"))result else result.optJSONObject("metrics")
        val meteredEv=(envelope?.opt("meteredEv") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in -64.0..64.0 }
        val gpu=result.optJSONObject("gpu") ?: result.optJSONObject("metrics")?.optJSONObject("gpu")
            ?: return SafeInfo(durationMs=duration(result),meteredEv=meteredEv)
        val requested=strictBoolean(gpu,"requested")
        val nativeActive=strictBoolean(gpu,"active")
        val selfTest=strictBoolean(gpu,"startupSelfTestPassed")
        val numericGuard=strictBoolean(gpu,"numericGuardPassed")
        val batches=strictCount(gpu,"gpuBatches")
        val backend=when(gpu.opt("backend")){
            "cpu"->GpuBackend.CPU
            "wgpu"->GpuBackend.WGPU_CHAIN
            "vulkan-adreno", "vulkan-adreno-partial"->GpuBackend.VULKAN_ADRENO_PARTIAL
            else->null
        }
        val stage=GpuStage.entries.firstOrNull{it.id==gpu.opt("acceleratedStage")}
        val adapter=(gpu.opt("adapter") as? String)?.takeIf{it.length<=64}
            ?.let{adapterPattern.matchEntire(it)}?.groupValues?.get(1)?.toIntOrNull()?.takeIf{it in 100..9999}
        // Requested or a discovered adapter alone never means this operation used the GPU.
        val active=when {
            nativeActive==false->false
            nativeActive==true && requested==true && selfTest==true && batches!=null && batches>0 &&
                backend==GpuBackend.VULKAN_ADRENO_PARTIAL && stage==GpuStage.DENSITY_CURVE_INTERP->true
            nativeActive==true && requested==true && batches!=null && batches>0 && numericGuard==true &&
                backend==GpuBackend.WGPU_CHAIN && stage in setOf(GpuStage.FULL_RESIDENT_CHAIN,GpuStage.BANDED_CHAIN,GpuStage.TILED_CHAIN)->true
            else->null
        }
        val tiles=result.optJSONObject("metrics")?.optJSONObject("gpuTiles")
        fun tileDimension(key:String)=tiles?.let{strictCount(it,key)}?.takeIf{it in 1L..40000L}
        return SafeInfo(durationMs=duration(result),meteredEv=meteredEv,gpuRequested=requested,gpuActive=active,
            gpuBackend=backend,gpuStage=stage,gpuAdapterModel=adapter,gpuBatches=batches,
            gpuTileCoreWidth=tileDimension("coreWidth"),gpuTileCoreHeight=tileDimension("coreHeight"),
            gpuTileExpandedWidth=tileDimension("expandedWidth"),gpuTileExpandedHeight=tileDimension("expandedHeight"),
            gpuFallbacks=strictCount(gpu,"cpuFallbacks"),gpuReason=reason(gpu.opt("reason")),gpuSelfTest=selfTest,gpuNumericGuard=numericGuard)
    }
}
