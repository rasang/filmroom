package dev.filmroom.diagnostics

import org.json.JSONObject

/** Canonical diagnostics values only: never serialize native driver names or error text. */
enum class GpuBackend(val id:String) { CPU("cpu"), VULKAN_ADRENO_PARTIAL("vulkan-adreno-partial") }
enum class GpuStage(val id:String) { DENSITY_CURVE_INTERP("density_curve_interp") }
enum class GpuReason(val id:String) {
    USER_DISABLED("user_disabled"), UNAVAILABLE("unavailable"), ACTIVE_PARTIAL("active_partial"),
    ELIGIBLE_UNUSED("eligible_unused"), PRIOR_FAILURE("prior_failure"), DEVICE_FAILURE("device_failure"),
    NO_ADAPTER("no_adapter"), UNSUPPORTED_ADAPTER("unsupported_adapter"), LIMITS_INSUFFICIENT("limits_insufficient"),
    VALIDATION_FAILED("validation_failed"), SELF_TEST_FAILED("self_test_failed"),
    NO_MEASURED_BENEFIT("no_measured_benefit"), CALIBRATION_BUDGET("calibration_budget"),
    NUMERIC_FAILURE("numeric_failure"), READBACK_FAILED("readback_failed"),
    CALLBACK_DISCONNECTED("callback_disconnected"), TIMEOUT_DEVICE("timeout_device"),
    BATCH_VALIDATION("batch_validation"), UNKNOWN("unknown");
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
        val batches=strictCount(gpu,"gpuBatches")
        val backend=when(gpu.opt("backend")){
            "cpu"->GpuBackend.CPU
            "vulkan-adreno", "vulkan-adreno-partial"->GpuBackend.VULKAN_ADRENO_PARTIAL
            else->null
        }
        val stage=if(gpu.opt("acceleratedStage")=="density_curve_interp")GpuStage.DENSITY_CURVE_INTERP else null
        val adapter=(gpu.opt("adapter") as? String)?.takeIf{it.length<=64}
            ?.let{adapterPattern.matchEntire(it)}?.groupValues?.get(1)?.toIntOrNull()?.takeIf{it in 100..9999}
        // Requested or a discovered adapter alone never means this operation used the GPU.
        val active=when {
            nativeActive==false->false
            nativeActive==true && requested==true && selfTest==true && batches!=null && batches>0 &&
                backend==GpuBackend.VULKAN_ADRENO_PARTIAL && stage==GpuStage.DENSITY_CURVE_INTERP->true
            else->null
        }
        return SafeInfo(durationMs=duration(result),meteredEv=meteredEv,gpuRequested=requested,gpuActive=active,
            gpuBackend=backend,gpuStage=stage,gpuAdapterModel=adapter,gpuBatches=batches,
            gpuFallbacks=strictCount(gpu,"cpuFallbacks"),gpuReason=reason(gpu.opt("reason")),gpuSelfTest=selfTest)
    }
}
