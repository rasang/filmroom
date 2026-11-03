package dev.filmroom.engine

import org.json.JSONObject

internal data class GpuFilmExposurePlan(
    override val token: Long, override val digest: String, override val width: Int, override val height: Int,
    val chunkPixels: Int, val chunkCount: Long, val tableScalars: Long,
    override val deviceBytes: Long, override val hostBytes: Long, override val driverAllowanceBytes: Long,
    override val helperAllowanceBytes: Long, override val combinedPeakBytes: Long, override val diskBytes: Long,
) : GpuStagePlan { override val operation get() = GpuRenderOperation.FILM_EXPOSURE }
internal data class GpuFilmExposureSummary(
    val width: Int, val height: Int, val chunkCount: Long,
    val elapsedMs: Double, val setupMs: Double, val uploadMs: Double, val dispatchReadbackMs: Double, val guardMs: Double, val vendor: Long, val f64Advertised: Boolean,
    val uploadBytes: Long, val readbackBytes: Long, val dispatches: Long, val deviceBytes: Long,
    val sampleCount: Long, val sampleMaxAbs: Double, val sampleRms: Double,
    val sampleToleranceRelative: Double, val sampleToleranceAbsolute: Double,
) : GpuStageSummary {
    override fun json(): JSONObject = JSONObject().put("schemaVersion", 1).put("gpuUsed", true).put("fullImage", true)
        .put("operation", GpuRenderOperation.FILM_EXPOSURE.wire).put("width", width).put("height", height).put("channels", 3)
        .put("chunkCount", chunkCount).put("elapsedMs", elapsedMs)
        .put("setupMs", setupMs).put("uploadMs", uploadMs).put("dispatchReadbackMs", dispatchReadbackMs).put("guardMs", guardMs)
        .put("backend", GpuFilmExposureProtocol.SAFE_BACKEND).put("vendor", vendor).put("shaderF64Advertised", f64Advertised)
        .put("uploadBytes", uploadBytes).put("readbackBytes", readbackBytes).put("dispatches", dispatches).put("deviceBytes", deviceBytes)
        .put("sampleCount", sampleCount).put("sampleMaxAbs", sampleMaxAbs).put("sampleRms", sampleRms)
        .put("sampleToleranceRelative", sampleToleranceRelative).put("sampleToleranceAbsolute", sampleToleranceAbsolute)
}

/** Closed export exposure schema: original two matrices and bicubic table, not preview math. */
internal object GpuFilmExposureProtocol {
    const val HARD_CAP = 384L * GpuRenderProtocol.MIB
    const val NATIVE_BACKEND = "Vulkan f32 resident two-matrix bicubic exposure"
    const val SAFE_BACKEND = "vulkan_f32_resident_two_matrix_bicubic_exposure"
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val planKeys = setOf("schemaVersion", "operation", "token", "manifestSha256", "width", "height", "chunkPixels", "chunkCount", "tableScalars", "deviceBytes", "hostBytes", "driverAllowanceBytes", "helperAllowanceBytes", "combinedPeakBytes", "diskBytes")
    private val summaryKeys = setOf("schemaVersion", "operation", "gpuUsed", "fullImage", "width", "height", "channels", "chunkCount", "elapsedMs", "setupMs", "uploadMs", "dispatchReadbackMs", "guardMs", "backend", "vendor", "shaderF64Advertised", "uploadBytes", "readbackBytes", "dispatches", "deviceBytes", "sampleCount", "sampleMaxAbs", "sampleRms", "sampleToleranceRelative", "sampleToleranceAbsolute")
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
    fun parsePlan(raw: String, token: Long): GpuFilmExposurePlan {
        val root = GpuRenderProtocol.objectOf(raw)
        root.exact(setOf("ok", "schemaVersion", "operation", "plan"))
        require(root.bool("ok") && root.long("schemaVersion") == 1L && root.get("operation") == "planGpuFilmExposureStage")
        val p = root.getJSONObject("plan"); p.exact(planKeys)
        require(p.long("schemaVersion") == 1L && p.get("operation") == GpuRenderOperation.FILM_EXPOSURE.wire && p.long("token", 1) == token)
        val digest = p.get("manifestSha256") as? String ?: error("Invalid digest"); require(digestPattern.matches(digest))
        val plan = GpuFilmExposurePlan(token, digest, p.long("width", 1, 40000).toInt(), p.long("height", 1, 40000).toInt(),
            p.long("chunkPixels", 1, 65536).toInt(), p.long("chunkCount", 1, 1_600_000_000),
            p.long("tableScalars", 12, 3L * 1024 * 1024), p.long("deviceBytes", 1, HARD_CAP), p.long("hostBytes", 1, HARD_CAP),
            p.long("driverAllowanceBytes", 1, HARD_CAP), p.long("helperAllowanceBytes", 1, HARD_CAP),
            p.long("combinedPeakBytes", 1, HARD_CAP), p.long("diskBytes", 1, 64L * 1024 * GpuRenderProtocol.MIB))
        validatePlan(plan); return plan
    }
    fun validatePlan(p: GpuFilmExposurePlan) {
        require(p.token > 0 && p.width in 1..40000 && p.height in 1..40000)
        val pixels = p.width.toLong() * p.height
        require(p.chunkPixels == minOf(65536L, pixels).toInt())
        require(p.chunkCount == (pixels + p.chunkPixels - 1) / p.chunkPixels)
        require(p.tableScalars in 12..(3L * 1024 * 1024))
        val side = kotlin.math.sqrt(p.tableScalars / 3.0).toLong()
        require(side in 2..1024 && side * side * 3 == p.tableScalars)
        val c = p.chunkPixels.toLong(); val t = p.tableScalars
        require(p.deviceBytes == 4 * t + 36 * c + 4096 + 8)
        require(p.hostBytes == 16 * t + 128 * c + 2 * GpuRenderProtocol.MIB)
        require(p.driverAllowanceBytes == 64 * GpuRenderProtocol.MIB && p.helperAllowanceBytes == 64 * GpuRenderProtocol.MIB)
        val combined = Math.addExact(Math.addExact(p.deviceBytes, p.hostBytes), Math.addExact(p.driverAllowanceBytes, p.helperAllowanceBytes))
        require(combined == p.combinedPeakBytes && combined <= HARD_CAP)
        require(p.diskBytes == 36 * pixels + 8 * t + 65536)
    }
    fun expectedSamples(p: GpuFilmExposurePlan): Long {
        val last = p.width.toLong() * p.height - (p.chunkCount - 1) * p.chunkPixels
        return 3 * ((p.chunkCount - 1) * minOf(8, p.chunkPixels) + minOf(8L, last))
    }
    fun parseNativeResult(raw: String, p: GpuFilmExposurePlan): Pair<GpuRenderCode, GpuFilmExposureSummary?> {
        val value = GpuRenderProtocol.objectOf(raw)
        if (!value.bool("ok")) return GpuRenderProtocol.nativeFailure(p.operation, value.opt("code")) to null
        require(value.long("schemaVersion") == 1L && value.get("operation") == GpuRenderOperation.FILM_EXPOSURE.wire)
        require(value.long("token") == p.token && value.get("manifestSha256") == p.digest)
        require(value.bool("gpuUsed") && value.bool("fullImage") && value.long("channels") == 3L)
        require(value.long("width") == p.width.toLong() && value.long("height") == p.height.toLong())
        require(value.long("chunkCount") == p.chunkCount && value.long("deviceBytes") == p.deviceBytes)
        require(value.get("backend") == NATIVE_BACKEND)
        return GpuRenderCode.OK to GpuFilmExposureSummary(p.width, p.height, p.chunkCount,
            value.number("elapsedMs", 125000.0), value.number("setupMs", 125000.0), value.number("uploadMs", 125000.0),
            value.number("dispatchReadbackMs", 125000.0), value.number("guardMs", 125000.0), value.long("vendor", 0, 0xffffffffL), value.bool("shaderF64Advertised"),
            value.long("uploadBytes", 4 * p.tableScalars + 128 + 12L * p.width * p.height + 4 * p.chunkCount + 4, 4 * p.tableScalars + 128 + 12L * p.width * p.height + 4 * p.chunkCount + 4),
            value.long("readbackBytes", 12L * p.width * p.height + 4 * p.chunkCount, 12L * p.width * p.height + 4 * p.chunkCount),
            value.long("dispatches", p.chunkCount, p.chunkCount), p.deviceBytes,
            value.long("sampleCount", expectedSamples(p), expectedSamples(p)), value.number("sampleMaxAbs", 1e300), value.number("sampleRms", 1e300),
            value.number("sampleToleranceRelative", 2e-5).also { require(it == 2e-5) }, value.number("sampleToleranceAbsolute", 2e-5).also { require(it == 2e-5) })
    }
    fun parseResult(raw: String, plan: GpuFilmExposurePlan): Pair<GpuRenderCode, GpuFilmExposureSummary?> {
        val root = GpuRenderProtocol.objectOf(raw, GpuRenderProtocol.MAX_IPC); require(root.long("schemaVersion") == 1L)
        val code = GpuRenderCode.valueOf(root.getString("code"))
        root.exact(if (code == GpuRenderCode.OK) setOf("schemaVersion", "code", "summary") else setOf("schemaVersion", "code"))
        if (code != GpuRenderCode.OK) return code to null
        val s = root.getJSONObject("summary"); s.exact(summaryKeys)
        require(s.get("backend") == SAFE_BACKEND)
        return parseNativeResult(JSONObject(s.toString()).put("ok", true).put("token", plan.token)
            .put("manifestSha256", plan.digest).put("backend", NATIVE_BACKEND).toString(), plan)
    }
}
