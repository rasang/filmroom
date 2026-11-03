package dev.filmroom.engine

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal enum class GpuRenderStatus(val wire: String) {
    PENDING("pending"), RUNNING("running"), STOPPING("stopping"),
    SUCCESS("success_stopped"), FAILED("failed_stopped"), CANCELLED("cancelled_stopped"), NOT_STARTED("not_started");
    val terminal get() = this in setOf(SUCCESS, FAILED, CANCELLED, NOT_STARTED)
}
internal enum class GpuRenderCode { OK, NO_JOB, INVALID_REQUEST, MEMORY_UNAVAILABLE, START_REVOKED, CANCELLED, TIMED_OUT, PREVIEW_BUDGET_EXHAUSTED, HELPER_DIED, BIND_FAILED, NATIVE_UNAVAILABLE, NATIVE_FAILED, INVALID_RESULT, NUMERIC_REJECTED, GPU_BUSY, UNSUPPORTED }
/** Closed stage registry: never turn a packet operation into a reflective JNI name. */
internal enum class GpuRenderOperation(val wire: String) {
    CAMERA_DIFFUSION("fullCameraDiffusion"), PRINT_SCAN("fullPrintScanLogXyz"), FILM_OPTICS("fullFilmOptics"), FILM_EXPOSURE("fullFilmExposure"), FILM_DIR("fullFilmDirDensity"),
    AUTO_EXPOSURE("fullAutoExposure"), HIGHLIGHT_BOOST("fullHighlightBoost"), EXPOSURE_LOG("fullExposureLog"),
    FILM_GRAIN("fullFilmGrain"), PRINT_EXPOSURE("fullPrintExposure"), PRINT_DIFFUSION("fullPrintDiffusion"), SCAN_TAIL("fullScanTail"), FINISH_TAIL("fullFinishTail");
    companion object { fun fromWire(value: Any?): GpuRenderOperation = entries.singleOrNull { it.wire == value } ?: error("Invalid GPU operation") }
}
internal sealed interface GpuStagePlan {
    val operation: GpuRenderOperation
    val token: Long; val digest: String; val width: Int; val height: Int
    val deviceBytes: Long; val hostBytes: Long; val driverAllowanceBytes: Long; val helperAllowanceBytes: Long
    val combinedPeakBytes: Long; val diskBytes: Long
}
internal sealed interface GpuStageSummary { fun json(): JSONObject }
internal data class GpuRenderRequest(val token: Long, val parentHandle: Long, val jobDir: String, val digest: String, val budgetBytes: Long, val width: Int, val height: Int, val totalPixels: Long, val operation: GpuRenderOperation = GpuRenderOperation.CAMERA_DIFFUSION)
internal data class GpuRenderPlan(
    override val token: Long, override val digest: String, override val width: Int, override val height: Int, val radius: Int, val fftSide: Int,
    val core: Int, val tileCount: Long, override val deviceBytes: Long, override val hostBytes: Long,
    override val driverAllowanceBytes: Long, override val helperAllowanceBytes: Long, override val combinedPeakBytes: Long, override val diskBytes: Long,
    override val operation: GpuRenderOperation = GpuRenderOperation.CAMERA_DIFFUSION,
) : GpuStagePlan
internal data class GpuRenderSummary(
    val width: Int, val height: Int, val tileCount: Long, val elapsedMs: Double, val vendor: Long,
    val f64Advertised: Boolean, val uploadBytes: Long, val readbackBytes: Long, val dispatches: Long,
    val deviceBytes: Long, val sampleCount: Long, val sampleMaxAbs: Double, val sampleRms: Double, val sampleToleranceRelative: Double, val sampleToleranceAbsolute: Double,
    val operation: GpuRenderOperation = GpuRenderOperation.CAMERA_DIFFUSION,
) : GpuStageSummary {
    override fun json(): JSONObject = JSONObject().put("schemaVersion", 1).put("gpuUsed", true).put("fullImage", true)
        .put("width", width).put("height", height).put("channels", 3).put("tileCount", tileCount)
        .put("elapsedMs", elapsedMs).put("backend", "vulkan_f32_fft_and_scatter").put("vendor", vendor)
        .put("shaderF64Advertised", f64Advertised).put("uploadBytes", uploadBytes).put("readbackBytes", readbackBytes)
        .put("dispatches", dispatches).put("deviceBytes", deviceBytes).put("sampleCount", sampleCount)
        .put("sampleMaxAbs", sampleMaxAbs).put("sampleRms", sampleRms).put("sampleToleranceRelative", sampleToleranceRelative).put("sampleToleranceAbsolute", sampleToleranceAbsolute)
        .apply { if (operation != GpuRenderOperation.CAMERA_DIFFUSION) put("operation", operation.wire) }
}
internal data class GpuRenderPoll(val token: Long, val status: GpuRenderStatus, val code: GpuRenderCode, val totalPixels: Long, val summary: GpuStageSummary? = null) {
    fun json(): String = JSONObject().put("schemaVersion", 1).put("token", token).put("status", status.wire)
        .put("completedPixels", if (status == GpuRenderStatus.SUCCESS) totalPixels else 0L).put("totalPixels", totalPixels)
        .put("code", code.name).apply { summary?.let { put("summary", it.json()) } }.toString()
}

internal object GpuRenderProtocol {
    const val MIB = 1024L * 1024
    const val HARD_CAP = 768L * MIB
    const val MAX_JSON = 16 * 1024
    const val MAX_IPC = 4096
    const val MAX_TILES = 64L
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val requestKeys = setOf("schemaVersion", "token", "parentHandle", "jobDir", "manifestSha256", "budgetBytes", "width", "height", "totalPixels")
    private val planKeys = setOf("schemaVersion", "operation", "token", "manifestSha256", "width", "height", "radius", "fftSide", "core", "tileCount", "deviceBytes", "hostBytes", "driverAllowanceBytes", "helperAllowanceBytes", "combinedPeakBytes", "diskBytes")
    fun objectOf(raw: String, cap: Int = MAX_JSON): JSONObject {
        require(raw.length <= cap && raw.toByteArray(Charsets.UTF_8).size <= cap)
        return JSONObject(raw)
    }
    private fun JSONObject.exact(keys: Set<String>) { require(keys().asSequence().toSet() == keys) }
    private fun JSONObject.long(key: String, min: Long = 0, max: Long = Long.MAX_VALUE): Long {
        val value = get(key); require(value is Long || value is Int)
        return (value as Number).toLong().also { require(it in min..max) }
    }
    private fun JSONObject.number(key: String, max: Double): Double {
        val value = (get(key) as? Number)?.toDouble() ?: error("Invalid numeric field")
        require(value.isFinite() && value >= 0 && value <= max); return value
    }
    private fun JSONObject.bool(key: String) = (get(key) as? Boolean) ?: error("Invalid boolean")
    fun stageDirectory(context: Context, token: Long, createRoot: Boolean = false): File {
        require(token > 0)
        val root = File(context.cacheDir.canonicalFile, "gpu-render")
        if (createRoot && !root.exists()) require(root.mkdir())
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && root.canonicalFile == root)
        return File(root, "gpu-stage-$token").also {
            require(Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) && it.canonicalFile == it)
        }
    }
    fun parseRequest(raw: String, context: Context): GpuRenderRequest {
        val value = objectOf(raw)
        // Schema 1 camera packets are unchanged. Every newer stage is explicit.
        val operation = if (value.has("operation")) GpuRenderOperation.fromWire(value.get("operation")) else GpuRenderOperation.CAMERA_DIFFUSION
        value.exact(if (operation == GpuRenderOperation.CAMERA_DIFFUSION) requestKeys else requestKeys + "operation")
        require(value.long("schemaVersion") == 1L)
        val token = value.long("token", 1); val handle = value.long("parentHandle", 1)
        val directory = value.get("jobDir") as? String ?: error("Invalid directory")
        require(directory.length <= 4096 && directory == stageDirectory(context, token).path)
        val digest = value.get("manifestSha256") as? String ?: error("Invalid digest")
        require(digestPattern.matches(digest))
        val width = value.long("width", 1, 40000).toInt(); val height = value.long("height", 1, 40000).toInt()
        val pixels = value.long("totalPixels", 1, 4_800_000_000L)
        require(pixels == Math.multiplyExact(width.toLong() * height, 3L))
        return GpuRenderRequest(token, handle, directory, digest, value.long("budgetBytes", 1, HARD_CAP), width, height, pixels, operation)
    }
    /** Only named native codes cross IPC, never raw driver text or source paths. */
    fun nativeFailure(operation: GpuRenderOperation, code: Any?): GpuRenderCode {
        if(operation==GpuRenderOperation.FINISH_TAIL && code in setOf("GPU_TAIL_NUMERIC_CHECK","GPU_TAIL_NUMERIC_DOMAIN","GPU_TAIL_NONFINITE","NONFINITE_TAIL_INPUT","NONFINITE_TAIL_OUTPUT","NONFINITE_TAIL_INTERMEDIATE","NONFINITE_TAIL_PARAMETERS","GPU_TAIL_MEMORY_LIMIT","INSUFFICIENT_GPU_TAIL_MEMORY","GPU_TAIL_TIMEOUT","GPU_TAIL_UNSUPPORTED"))return nativeFailure(GpuRenderOperation.SCAN_TAIL,code)
        if(operation==GpuRenderOperation.FINISH_TAIL && code in setOf("GPU_FINISH_NUMERIC_CHECK","GPU_FINISH_NONFINITE","NONFINITE_FINISH_INPUT","NONFINITE_FINISH_OUTPUT","NONFINITE_FINISH_TABLE","GPU_FINISH_MEMORY_LIMIT","INSUFFICIENT_GPU_FINISH_MEMORY","GPU_FINISH_TIMEOUT","GPU_FINISH_UNSUPPORTED"))return nativeFailure(GpuRenderOperation.PRINT_SCAN,code)
        val prefix = when (operation) {
            GpuRenderOperation.CAMERA_DIFFUSION, GpuRenderOperation.PRINT_DIFFUSION -> "GPU_STAGE"
            GpuRenderOperation.PRINT_SCAN, GpuRenderOperation.PRINT_EXPOSURE -> "GPU_FINISH"
            GpuRenderOperation.FILM_OPTICS -> "GPU_OPTICS"
            GpuRenderOperation.FILM_EXPOSURE -> "GPU_EXPOSURE"
            GpuRenderOperation.FILM_DIR -> "GPU_DIR"
            GpuRenderOperation.FILM_GRAIN -> "GPU_GRAIN"
            GpuRenderOperation.SCAN_TAIL -> "GPU_TAIL"
            GpuRenderOperation.FINISH_TAIL -> "GPU_FUSION"
            GpuRenderOperation.AUTO_EXPOSURE, GpuRenderOperation.HIGHLIGHT_BOOST, GpuRenderOperation.EXPOSURE_LOG -> "GPU_GLOBAL"
        }
        val data = prefix.removePrefix("GPU_")
        return when (code) {
            "CANCELLED" -> GpuRenderCode.CANCELLED
            "${prefix}_NUMERIC_CHECK", "${prefix}_NUMERIC_DOMAIN", "${prefix}_NONFINITE", "NONFINITE_${data}_INPUT", "NONFINITE_${data}_OUTPUT", "NONFINITE_${data}_TABLE", "NONFINITE_${data}_KERNEL", "NONFINITE_${data}_INTERMEDIATE", "NONFINITE_${data}_PARAMETERS" -> GpuRenderCode.NUMERIC_REJECTED
            "${prefix}_MEMORY_LIMIT", "INSUFFICIENT_${prefix}_MEMORY" -> GpuRenderCode.MEMORY_UNAVAILABLE
            "${prefix}_TIMEOUT" -> GpuRenderCode.TIMED_OUT
            "${prefix}_UNSUPPORTED", "GPU_UNAVAILABLE_OR_LIMITS" -> GpuRenderCode.UNSUPPORTED
            "GPU_TAIL_GLARE_AMBIGUOUS", "GPU_TAIL_GLARE_STATE", "NONFINITE_TAIL_GLARE" -> if (operation in setOf(GpuRenderOperation.SCAN_TAIL,GpuRenderOperation.FINISH_TAIL)) GpuRenderCode.NUMERIC_REJECTED else GpuRenderCode.NATIVE_FAILED
            "GPU_TAIL_GLARE_LIMIT", "GPU_TAIL_GLARE_ITERATIONS" -> if (operation in setOf(GpuRenderOperation.SCAN_TAIL,GpuRenderOperation.FINISH_TAIL)) GpuRenderCode.UNSUPPORTED else GpuRenderCode.NATIVE_FAILED
            "GPU_FUSION_PREFIX_NUMERIC_CHECK" -> if(operation==GpuRenderOperation.FINISH_TAIL)GpuRenderCode.NUMERIC_REJECTED else GpuRenderCode.NATIVE_FAILED
            "GPU_GRAIN_INTEGER_RNG_CHECK", "GPU_GRAIN_MT_RNG_CHECK", "GPU_GRAIN_SAMPLER_TUPLE_CHECK", "GPU_GRAIN_GUARDED_REJECTION" -> if (operation == GpuRenderOperation.FILM_GRAIN) GpuRenderCode.NUMERIC_REJECTED else GpuRenderCode.NATIVE_FAILED
            "GPU_GRAIN_COUNT_LIMIT", "GPU_GRAIN_SAMPLER_LIMIT", "GPU_GRAIN_UNSUPPORTED_CURVES", "GPU_GRAIN_LAYER_LIMIT", "GPU_GRAIN_SEQUENTIAL_PENDING", "GPU_GRAIN_GUARDED_PENDING" -> if (operation == GpuRenderOperation.FILM_GRAIN) GpuRenderCode.UNSUPPORTED else GpuRenderCode.NATIVE_FAILED
            "GPU_OPTICS_MEMORY_LIMIT" -> if (operation == GpuRenderOperation.FILM_GRAIN) GpuRenderCode.MEMORY_UNAVAILABLE else GpuRenderCode.NATIVE_FAILED
            "GPU_OPTICS_NUMERIC_CHECK" -> if (operation in setOf(GpuRenderOperation.FILM_DIR, GpuRenderOperation.FILM_GRAIN)) GpuRenderCode.NUMERIC_REJECTED else GpuRenderCode.NATIVE_FAILED
            else -> GpuRenderCode.NATIVE_FAILED
        }
    }
    fun nativePlan(operation: GpuRenderOperation, directory: String, token: Long): String = when (operation) {
        GpuRenderOperation.CAMERA_DIFFUSION -> NativeGpuProbeBridge.nativePlanGpuOpticalStage(directory, token)
        GpuRenderOperation.PRINT_SCAN -> NativeGpuProbeBridge.nativePlanGpuPrintScan(directory, token)
        GpuRenderOperation.FILM_OPTICS -> NativeGpuProbeBridge.nativePlanGpuFilmOpticsStage(directory, token)
        GpuRenderOperation.FILM_EXPOSURE -> NativeGpuProbeBridge.nativePlanGpuFilmExposureStage(directory, token)
        GpuRenderOperation.FILM_DIR -> NativeGpuProbeBridge.nativePlanGpuFilmDirStage(directory, token)
        GpuRenderOperation.AUTO_EXPOSURE -> NativeGpuProbeBridge.nativePlanGpuAutoExposureStage(directory, token)
        GpuRenderOperation.HIGHLIGHT_BOOST -> NativeGpuProbeBridge.nativePlanGpuHighlightBoostStage(directory, token)
        GpuRenderOperation.EXPOSURE_LOG -> NativeGpuProbeBridge.nativePlanGpuExposureLogStage(directory, token)
        GpuRenderOperation.FILM_GRAIN -> NativeGpuProbeBridge.nativePlanGpuFilmGrainStage(directory, token)
        GpuRenderOperation.PRINT_EXPOSURE -> NativeGpuProbeBridge.nativePlanGpuPrintExposureStage(directory, token)
        GpuRenderOperation.PRINT_DIFFUSION -> NativeGpuProbeBridge.nativePlanGpuPrintDiffusionStage(directory, token)
        GpuRenderOperation.SCAN_TAIL -> NativeGpuProbeBridge.nativePlanGpuScanTailStage(directory, token)
        GpuRenderOperation.FINISH_TAIL -> NativeGpuProbeBridge.nativePlanGpuFinishTailStage(directory, token)
    }
    fun nativeRun(plan: GpuStagePlan, directory: String): String = when (plan.operation) {
        GpuRenderOperation.CAMERA_DIFFUSION -> NativeGpuProbeBridge.nativeRunGpuOpticalStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.PRINT_SCAN -> NativeGpuProbeBridge.nativeRunGpuPrintScan(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.FILM_OPTICS -> NativeGpuProbeBridge.nativeRunGpuFilmOpticsStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.FILM_EXPOSURE -> NativeGpuProbeBridge.nativeRunGpuFilmExposureStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.FILM_DIR -> NativeGpuProbeBridge.nativeRunGpuFilmDirStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.AUTO_EXPOSURE -> NativeGpuProbeBridge.nativeRunGpuAutoExposureStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.HIGHLIGHT_BOOST -> NativeGpuProbeBridge.nativeRunGpuHighlightBoostStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.EXPOSURE_LOG -> NativeGpuProbeBridge.nativeRunGpuExposureLogStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.FILM_GRAIN -> NativeGpuProbeBridge.nativeRunGpuFilmGrainStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.PRINT_EXPOSURE -> NativeGpuProbeBridge.nativeRunGpuPrintExposureStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.PRINT_DIFFUSION -> NativeGpuProbeBridge.nativeRunGpuPrintDiffusionStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.SCAN_TAIL -> NativeGpuProbeBridge.nativeRunGpuScanTailStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
        GpuRenderOperation.FINISH_TAIL -> NativeGpuProbeBridge.nativeRunGpuFinishTailStage(directory, plan.combinedPeakBytes, plan.token, plan.digest)
    }
    fun nativeCancel(operation: GpuRenderOperation, token: Long) = when (operation) {
        GpuRenderOperation.CAMERA_DIFFUSION -> NativeGpuProbeBridge.nativeCancelGpuOpticalStage(token)
        GpuRenderOperation.PRINT_SCAN -> NativeGpuProbeBridge.nativeCancelGpuPrintScan(token)
        GpuRenderOperation.FILM_OPTICS -> NativeGpuProbeBridge.nativeCancelGpuFilmOpticsStage(token)
        GpuRenderOperation.FILM_EXPOSURE -> NativeGpuProbeBridge.nativeCancelGpuFilmExposureStage(token)
        GpuRenderOperation.FILM_DIR -> NativeGpuProbeBridge.nativeCancelGpuFilmDirStage(token)
        GpuRenderOperation.AUTO_EXPOSURE -> NativeGpuProbeBridge.nativeCancelGpuAutoExposureStage(token)
        GpuRenderOperation.HIGHLIGHT_BOOST -> NativeGpuProbeBridge.nativeCancelGpuHighlightBoostStage(token)
        GpuRenderOperation.EXPOSURE_LOG -> NativeGpuProbeBridge.nativeCancelGpuExposureLogStage(token)
        GpuRenderOperation.FILM_GRAIN -> NativeGpuProbeBridge.nativeCancelGpuFilmGrainStage(token)
        GpuRenderOperation.PRINT_EXPOSURE -> NativeGpuProbeBridge.nativeCancelGpuPrintExposureStage(token)
        GpuRenderOperation.PRINT_DIFFUSION -> NativeGpuProbeBridge.nativeCancelGpuPrintDiffusionStage(token)
        GpuRenderOperation.SCAN_TAIL -> NativeGpuProbeBridge.nativeCancelGpuScanTailStage(token)
        GpuRenderOperation.FINISH_TAIL -> NativeGpuProbeBridge.nativeCancelGpuFinishTailStage(token)
    }
    fun parseStagePlan(raw: String, token: Long, operation: GpuRenderOperation): GpuStagePlan = when (operation) {
        GpuRenderOperation.CAMERA_DIFFUSION, GpuRenderOperation.PRINT_DIFFUSION -> parsePlan(raw, token, operation)
        GpuRenderOperation.PRINT_SCAN, GpuRenderOperation.PRINT_EXPOSURE -> GpuPrintScanProtocol.parsePlan(raw, token, operation)
        GpuRenderOperation.FILM_OPTICS -> GpuFilmOpticsProtocol.parsePlan(raw, token)
        GpuRenderOperation.FILM_EXPOSURE -> GpuFilmExposureProtocol.parsePlan(raw, token)
        GpuRenderOperation.FILM_DIR -> GpuFilmDirProtocol.parsePlan(raw, token)
        GpuRenderOperation.FILM_GRAIN -> GpuFilmGrainProtocol.parsePlan(raw, token)
        GpuRenderOperation.SCAN_TAIL -> GpuScanTailProtocol.parsePlan(raw, token)
        GpuRenderOperation.FINISH_TAIL -> GpuFinishTailProtocol.parsePlan(raw, token)
        GpuRenderOperation.AUTO_EXPOSURE, GpuRenderOperation.HIGHLIGHT_BOOST, GpuRenderOperation.EXPOSURE_LOG -> GpuGlobalProtocol.parsePlan(raw, token, operation)
    }
    fun parseStageNativeResult(raw: String, plan: GpuStagePlan): Pair<GpuRenderCode, GpuStageSummary?> = when (plan) {
        is GpuRenderPlan -> parseNativeResult(raw, plan)
        is GpuPrintScanPlan -> GpuPrintScanProtocol.parseNativeResult(raw, plan)
        is GpuFilmOpticsPlan -> GpuFilmOpticsProtocol.parseNativeResult(raw, plan)
        is GpuFilmExposurePlan -> GpuFilmExposureProtocol.parseNativeResult(raw, plan)
        is GpuFilmDirPlan -> GpuFilmDirProtocol.parseNativeResult(raw, plan)
        is GpuFilmGrainPlan -> GpuFilmGrainProtocol.parseNativeResult(raw, plan)
        is GpuScanTailPlan -> GpuScanTailProtocol.parseNativeResult(raw, plan)
        is GpuFinishTailPlan -> GpuFinishTailProtocol.parseNativeResult(raw, plan)
        is GpuGlobalPlan -> GpuGlobalProtocol.parseNativeResult(raw, plan)
    }
    fun parseStageResult(raw: String, plan: GpuStagePlan): Pair<GpuRenderCode, GpuStageSummary?> = when (plan) {
        is GpuRenderPlan -> parseResult(raw, plan)
        is GpuPrintScanPlan -> GpuPrintScanProtocol.parseResult(raw, plan)
        is GpuFilmOpticsPlan -> GpuFilmOpticsProtocol.parseResult(raw, plan)
        is GpuFilmExposurePlan -> GpuFilmExposureProtocol.parseResult(raw, plan)
        is GpuFilmDirPlan -> GpuFilmDirProtocol.parseResult(raw, plan)
        is GpuFilmGrainPlan -> GpuFilmGrainProtocol.parseResult(raw, plan)
        is GpuScanTailPlan -> GpuScanTailProtocol.parseResult(raw, plan)
        is GpuFinishTailPlan -> GpuFinishTailProtocol.parseResult(raw, plan)
        is GpuGlobalPlan -> GpuGlobalProtocol.parseResult(raw, plan)
    }
    fun parsePlan(raw: String, token: Long, operation: GpuRenderOperation = GpuRenderOperation.CAMERA_DIFFUSION): GpuRenderPlan {
        require(operation in setOf(GpuRenderOperation.CAMERA_DIFFUSION, GpuRenderOperation.PRINT_DIFFUSION))
        val root = objectOf(raw)
        root.exact(setOf("ok", "schemaVersion", "operation", "plan"))
        require(root.bool("ok") && root.long("schemaVersion") == 1L && root.get("operation") == if (operation == GpuRenderOperation.CAMERA_DIFFUSION) "planGpuOpticalStage" else "planGpuPrintDiffusionStage")
        val p = root.getJSONObject("plan"); p.exact(planKeys)
        require(p.long("schemaVersion") == 1L && p.get("operation") == operation.wire && p.long("token", 1) == token)
        val digest = p.get("manifestSha256") as? String ?: error("Invalid digest"); require(digestPattern.matches(digest))
        val plan = GpuRenderPlan(token, digest, p.long("width", 1, 40000).toInt(), p.long("height", 1, 40000).toInt(),
            p.long("radius", 0, 2047).toInt(), p.long("fftSide", 2, 4096).toInt(), p.long("core", 1, 4096).toInt(),
            p.long("tileCount", 1, MAX_TILES), p.long("deviceBytes", 1, HARD_CAP), p.long("hostBytes", 1, HARD_CAP),
            p.long("driverAllowanceBytes", 1, HARD_CAP), p.long("helperAllowanceBytes", 1, HARD_CAP),
            p.long("combinedPeakBytes", 1, HARD_CAP), p.long("diskBytes", 1, 64L * 1024 * MIB), operation)
        validatePlan(plan); return plan
    }
    fun validatePlan(p: GpuRenderPlan) {
        require(p.operation in setOf(GpuRenderOperation.CAMERA_DIFFUSION, GpuRenderOperation.PRINT_DIFFUSION))
        require(p.token > 0 && p.width in 1..40000 && p.height in 1..40000 && p.radius in 0..2047)
        var side = 2
        val need = 2 * p.radius + maxOf(p.width, p.height)
        while (side < need && side < 4096) side *= 2
        require(p.fftSide == side && p.core == side - 2 * p.radius && p.core > 0)
        require(p.core >= 256 || (p.width <= p.core && p.height <= p.core))
        val tiles = ((p.width.toLong() + p.core - 1) / p.core) * ((p.height.toLong() + p.core - 1) / p.core) * 3
        require(p.tileCount == tiles && tiles in 1..MAX_TILES)
        val n = p.fftSide.toLong(); val k = 2L * p.radius + 1
        val readback = minOf(8 * MIB, p.core.toLong() * p.core * 8)
        val device = 3 * n * n * 8 + k * k * 4 + n * 8 + readback + 8 * MIB + 65536
        require(p.deviceBytes == device && p.hostBytes == 56 * MIB && p.driverAllowanceBytes == 64 * MIB && p.helperAllowanceBytes == 64 * MIB)
        val combined = Math.addExact(Math.addExact(p.deviceBytes, p.hostBytes), Math.addExact(p.driverAllowanceBytes, p.helperAllowanceBytes))
        require(combined == p.combinedPeakBytes && combined <= HARD_CAP)
        val disk = Math.addExact(6L * p.width * p.height * 8, 3 * k * k * 8 + 65536)
        require(p.diskBytes == disk)
    }
    fun matches(request: GpuRenderRequest, plan: GpuStagePlan): Boolean = request.operation == plan.operation && request.token == plan.token && request.digest == plan.digest && request.budgetBytes == plan.combinedPeakBytes && request.width == plan.width && request.height == plan.height
    fun parseNativeResult(raw: String, plan: GpuRenderPlan): Pair<GpuRenderCode, GpuRenderSummary?> {
        val value = objectOf(raw)
        if (!value.bool("ok")) return nativeFailure(plan.operation, value.opt("code")) to null
        require(value.long("schemaVersion") == 1L && value.get("operation") == plan.operation.wire)
        require(value.long("token") == plan.token && value.get("manifestSha256") == plan.digest)
        require(value.bool("gpuUsed") && value.bool("fullImage") && value.long("channels") == 3L)
        require(value.long("width") == plan.width.toLong() && value.long("height") == plan.height.toLong() && value.long("tileCount") == plan.tileCount && value.long("deviceBytes") == plan.deviceBytes)
        require(value.get("backend") == "Vulkan f32 FFT and scatter")
        return GpuRenderCode.OK to GpuRenderSummary(plan.width, plan.height, plan.tileCount,
            value.number("elapsedMs", 125000.0), value.long("vendor", 0, 0xffffffffL), value.bool("shaderF64Advertised"),
            value.long("uploadBytes", 0, 128L * 1024 * MIB), value.long("readbackBytes", 0, 128L * 1024 * MIB),
            value.long("dispatches", 1, 1_000_000), plan.deviceBytes,
            value.long("sampleCount", plan.tileCount, plan.tileCount), value.number("sampleMaxAbs", 1e300), value.number("sampleRms", 1e300),
            value.number("sampleToleranceRelative", 2e-5).also { require(it == 2e-5) }, value.number("sampleToleranceAbsolute", 2e-5).also { require(it == 2e-5) }, plan.operation)
    }
    fun encodeResult(code: GpuRenderCode, summary: GpuStageSummary?): String = JSONObject().put("schemaVersion", 1).put("code", code.name)
        .apply { summary?.let { put("summary", it.json()) } }.toString().also { require(it.toByteArray().size <= MAX_IPC) }
    fun parseResult(raw: String, plan: GpuRenderPlan): Pair<GpuRenderCode, GpuRenderSummary?> {
        val root = objectOf(raw, MAX_IPC); require(root.long("schemaVersion") == 1L)
        val code = GpuRenderCode.valueOf(root.getString("code"))
        root.exact(if (code == GpuRenderCode.OK) setOf("schemaVersion", "code", "summary") else setOf("schemaVersion", "code"))
        if (code != GpuRenderCode.OK) return code to null
        val s = root.getJSONObject("summary")
        s.exact(setOf("schemaVersion", "gpuUsed", "fullImage", "width", "height", "channels", "tileCount", "elapsedMs", "backend", "vendor", "shaderF64Advertised", "uploadBytes", "readbackBytes", "dispatches", "deviceBytes", "sampleCount", "sampleMaxAbs", "sampleRms", "sampleToleranceRelative", "sampleToleranceAbsolute") + if (plan.operation == GpuRenderOperation.PRINT_DIFFUSION) setOf("operation") else emptySet())
        if (plan.operation == GpuRenderOperation.PRINT_DIFFUSION) require(s.get("operation") == plan.operation.wire)
        require(s.get("backend") == "vulkan_f32_fft_and_scatter")
        val native = JSONObject(s.toString()).put("ok", true).put("operation", plan.operation.wire)
            .put("token", plan.token).put("manifestSha256", plan.digest).put("backend", "Vulkan f32 FFT and scatter")
        return parseNativeResult(native.toString(), plan)
    }
}
