package dev.filmroom.engine

import org.json.JSONObject

internal data class GpuFilmDirPlan(
    override val token: Long, override val digest: String, override val width: Int, override val height: Int,
    val dirActive: Boolean, val curveKnots: Int, val preCurveKnots: Int, val chunkPixels: Int, val chunkCount: Long,
    val passCount: Int, override val deviceBytes: Long, override val hostBytes: Long,
    override val driverAllowanceBytes: Long, override val helperAllowanceBytes: Long,
    override val combinedPeakBytes: Long, override val diskBytes: Long,
) : GpuStagePlan { override val operation get() = GpuRenderOperation.FILM_DIR }
internal data class GpuFilmDirSummary(
    val width: Int, val height: Int, val dirActive: Boolean, val chunkCount: Long, val passCount: Int, val elapsedMs: Double, val setupMs: Double,
    val uploadMs: Double, val dispatchWaitMs: Double, val readbackMs: Double, val guardMs: Double,
    val vendor: Long, val f64Advertised: Boolean, val uploadBytes: Long, val readbackBytes: Long,
    val dispatches: Long, val deviceBytes: Long, val sampleCount: Long, val sampleMaxAbs: Double, val sampleRms: Double,
) : GpuStageSummary {
    override fun json(): JSONObject = JSONObject().put("schemaVersion", 1).put("operation", GpuRenderOperation.FILM_DIR.wire)
        .put("gpuUsed", true).put("fullImage", true).put("width", width).put("height", height).put("channels", 3)
        .put("dirActive", dirActive).put("chunkCount", chunkCount).put("passCount", passCount).put("elapsedMs", elapsedMs).put("setupMs", setupMs).put("uploadMs", uploadMs)
        .put("dispatchWaitMs", dispatchWaitMs).put("readbackMs", readbackMs).put("guardMs", guardMs)
        .put("backend", GpuFilmDirProtocol.SAFE_BACKEND).put("vendor", vendor).put("shaderF64Advertised", f64Advertised)
        .put("uploadBytes", uploadBytes).put("readbackBytes", readbackBytes).put("dispatches", dispatches).put("deviceBytes", deviceBytes)
        .put("sampleCount", sampleCount).put("sampleMaxAbs", sampleMaxAbs).put("sampleRms", sampleRms)
        .put("sampleToleranceAbsolute", 0.001).put("sampleToleranceRelative", 0.001)
        .put("sampleScope", GpuFilmDirProtocol.SAMPLE_SCOPE)
        .put("pointwiseToleranceAbsolute", 2e-5).put("pointwiseToleranceRelative", 2e-5)
}

/** Original curve provenance, full-plane DIR and bounded per-chunk pointwise guards. */
internal object GpuFilmDirProtocol {
    const val NATIVE_BACKEND = "Vulkan f32 resident initial density and DIR"
    const val SAFE_BACKEND = "vulkan_f32_resident_initial_density_and_dir"
    const val SAMPLE_SCOPE = "per_axis_and_chunk_f64_on_gpu_f32_input"
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val planKeys = setOf("schemaVersion", "operation", "token", "manifestSha256", "width", "height", "dirActive", "curveKnots", "preCurveKnots", "chunkPixels", "chunkCount", "passCount", "deviceBytes", "hostBytes", "driverAllowanceBytes", "helperAllowanceBytes", "combinedPeakBytes", "diskBytes")
    private val summaryKeys = setOf("schemaVersion", "operation", "gpuUsed", "fullImage", "width", "height", "channels", "dirActive", "chunkCount", "passCount", "elapsedMs", "setupMs", "uploadMs", "dispatchWaitMs", "readbackMs", "guardMs", "backend", "vendor", "shaderF64Advertised", "uploadBytes", "readbackBytes", "dispatches", "deviceBytes", "sampleCount", "sampleMaxAbs", "sampleRms", "sampleToleranceAbsolute", "sampleToleranceRelative", "sampleScope", "pointwiseToleranceAbsolute", "pointwiseToleranceRelative")
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
    fun parsePlan(raw: String, token: Long): GpuFilmDirPlan {
        val root = GpuRenderProtocol.objectOf(raw)
        root.exact(setOf("ok", "schemaVersion", "operation", "plan"))
        require(root.bool("ok") && root.long("schemaVersion") == 1L && root.get("operation") == "planGpuFilmDirStage")
        val p = root.getJSONObject("plan"); p.exact(planKeys)
        require(p.long("schemaVersion") == 1L && p.get("operation") == GpuRenderOperation.FILM_DIR.wire && p.long("token", 1) == token)
        val digest = p.get("manifestSha256") as? String ?: error("Invalid digest"); require(digestPattern.matches(digest))
        val cap = GpuRenderProtocol.HARD_CAP
        val plan = GpuFilmDirPlan(token, digest, p.long("width", 1, 40000).toInt(), p.long("height", 1, 40000).toInt(),
            p.bool("dirActive"), p.long("curveKnots", 2, 4096).toInt(), p.long("preCurveKnots", 2, 4096).toInt(),
            p.long("chunkPixels", 1, 65536).toInt(), p.long("chunkCount", 1, 1_600_000_000), p.long("passCount", 0, 12).toInt(),
            p.long("deviceBytes", 1, cap), p.long("hostBytes", 1, cap), p.long("driverAllowanceBytes", 1, cap), p.long("helperAllowanceBytes", 1, cap),
            p.long("combinedPeakBytes", 1, cap), p.long("diskBytes", 1, 64L * 1024 * GpuRenderProtocol.MIB))
        validatePlan(plan); return plan
    }
    fun validatePlan(p: GpuFilmDirPlan) {
        require(p.token > 0 && p.width in 1..40000 && p.height in 1..40000 && p.passCount in setOf(0, 12) && (p.dirActive || p.passCount == 0))
        val pixels = p.width.toLong() * p.height
        require(p.curveKnots in 2..4096 && p.preCurveKnots in 2..4096)
        require(p.chunkPixels == minOf(65536L, pixels).toInt() && p.chunkCount == (pixels + p.chunkPixels - 1) / p.chunkPixels)
        require(4 * pixels <= 128 * GpuRenderProtocol.MIB)
        require(p.deviceBytes == 20 * pixels + 8 * GpuRenderProtocol.MIB + 65536)
        require(p.hostBytes == 16 * GpuRenderProtocol.MIB)
        require(p.driverAllowanceBytes == 64 * GpuRenderProtocol.MIB && p.helperAllowanceBytes == 64 * GpuRenderProtocol.MIB)
        val combined = Math.addExact(Math.addExact(p.deviceBytes, p.hostBytes), Math.addExact(p.driverAllowanceBytes, p.helperAllowanceBytes))
        require(combined == p.combinedPeakBytes && combined <= GpuRenderProtocol.HARD_CAP)
        require(p.diskBytes == 48 * pixels + 2 * GpuRenderProtocol.MIB)
    }
    fun expectedSamples(p: GpuFilmDirPlan): Long {
        val last = p.width.toLong() * p.height - (p.chunkCount - 1) * p.chunkPixels
        val points = (p.chunkCount - 1) * minOf(8, p.chunkPixels) + minOf(8L, last)
        return p.passCount.toLong() * (p.width.toLong() + p.height) + (if (p.dirActive) 6 else 3) * points
    }
    fun maximumDispatches(p: GpuFilmDirPlan): Long = 4L * p.passCount + 21 + 15 * p.chunkCount
    fun parseNativeResult(raw: String, p: GpuFilmDirPlan): Pair<GpuRenderCode, GpuFilmDirSummary?> {
        val value = GpuRenderProtocol.objectOf(raw)
        if (!value.bool("ok")) return GpuRenderProtocol.nativeFailure(p.operation, value.opt("code")) to null
        require(value.long("schemaVersion") == 1L && value.get("operation") == GpuRenderOperation.FILM_DIR.wire)
        require(value.long("token") == p.token && value.get("manifestSha256") == p.digest)
        require(value.bool("gpuUsed") && value.bool("fullImage") && value.long("channels") == 3L)
        require(value.long("width") == p.width.toLong() && value.long("height") == p.height.toLong())
        require(value.bool("dirActive") == p.dirActive && value.long("chunkCount") == p.chunkCount)
        require(value.long("passCount") == p.passCount.toLong() && value.long("deviceBytes") == p.deviceBytes)
        require(value.get("backend") == NATIVE_BACKEND && value.get("sampleScope") == SAMPLE_SCOPE)
        require(value.number("sampleToleranceAbsolute", 0.001) == 0.001 && value.number("sampleToleranceRelative", 0.001) == 0.001)
        require(value.number("pointwiseToleranceAbsolute", 2e-5) == 2e-5 && value.number("pointwiseToleranceRelative", 2e-5) == 2e-5)
        val samples = expectedSamples(p)
        return GpuRenderCode.OK to GpuFilmDirSummary(p.width, p.height, p.dirActive, p.chunkCount, p.passCount,
            value.number("elapsedMs", 125000.0), value.number("setupMs", 125000.0), value.number("uploadMs", 125000.0),
            value.number("dispatchWaitMs", 125000.0), value.number("readbackMs", 125000.0), value.number("guardMs", 125000.0),
            value.long("vendor", 0, 0xffffffffL), value.bool("shaderF64Advertised"),
            value.long("uploadBytes", 0, 128L * 1024 * GpuRenderProtocol.MIB), value.long("readbackBytes", 0, 128L * 1024 * GpuRenderProtocol.MIB),
            value.long("dispatches", 1, maximumDispatches(p)), p.deviceBytes, value.long("sampleCount", samples, samples),
            value.number("sampleMaxAbs", 1e300), value.number("sampleRms", 1e300))
    }
    fun parseResult(raw: String, plan: GpuFilmDirPlan): Pair<GpuRenderCode, GpuFilmDirSummary?> {
        val root = GpuRenderProtocol.objectOf(raw, GpuRenderProtocol.MAX_IPC); require(root.long("schemaVersion") == 1L)
        val code = GpuRenderCode.valueOf(root.getString("code"))
        root.exact(if (code == GpuRenderCode.OK) setOf("schemaVersion", "code", "summary") else setOf("schemaVersion", "code"))
        if (code != GpuRenderCode.OK) return code to null
        val s = root.getJSONObject("summary"); s.exact(summaryKeys); require(s.get("backend") == SAFE_BACKEND)
        return parseNativeResult(JSONObject(s.toString()).put("ok", true).put("token", plan.token)
            .put("manifestSha256", plan.digest).put("backend", NATIVE_BACKEND).toString(), plan)
    }
}
