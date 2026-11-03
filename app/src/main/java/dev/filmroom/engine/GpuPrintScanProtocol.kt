package dev.filmroom.engine

import org.json.JSONObject

internal enum class GpuPrintScanMode(val wire: String) {
    DIRECT_FILM("directFilm"), PRINT_FROM_DENSITY("printFromDensity"), PRINT_FROM_RAW("printFromRaw"), PRINT_EXPOSURE_ONLY("printExposureOnly");
    companion object { fun fromWire(value: Any?): GpuPrintScanMode = entries.singleOrNull { it.wire == value } ?: error("Invalid GPU scan mode") }
}
internal data class GpuPrintScanPlan(
    override val token: Long, override val digest: String, override val width: Int, override val height: Int,
    val mode: GpuPrintScanMode, val chunkPixels: Int, val chunkCount: Long, val tableScalars: Long,
    override val deviceBytes: Long, override val hostBytes: Long, override val driverAllowanceBytes: Long,
    override val helperAllowanceBytes: Long, override val combinedPeakBytes: Long, override val diskBytes: Long,
    override val operation: GpuRenderOperation = GpuRenderOperation.PRINT_SCAN,
) : GpuStagePlan
internal data class GpuPrintScanSummary(
    val width: Int, val height: Int, val mode: GpuPrintScanMode, val chunkCount: Long,
    val elapsedMs: Double, val vendor: Long, val f64Advertised: Boolean,
    val uploadBytes: Long, val readbackBytes: Long, val dispatches: Long, val deviceBytes: Long,
    val sampleCount: Long, val sampleMaxAbs: Double, val sampleRms: Double,
    val sampleToleranceRelative: Double, val sampleToleranceAbsolute: Double,
    val operation: GpuRenderOperation = GpuRenderOperation.PRINT_SCAN,
) : GpuStageSummary {
    override fun json(): JSONObject = JSONObject().put("schemaVersion", 1).put("gpuUsed", true).put("fullImage", true)
        .put("operation", operation.wire).put("width", width).put("height", height).put("channels", 3)
        .put("mode", mode.wire).put("chunkCount", chunkCount).put("elapsedMs", elapsedMs)
        .put("backend", GpuPrintScanProtocol.safeBackend(operation)).put("vendor", vendor).put("shaderF64Advertised", f64Advertised)
        .put("uploadBytes", uploadBytes).put("readbackBytes", readbackBytes).put("dispatches", dispatches).put("deviceBytes", deviceBytes)
        .put("sampleCount", sampleCount).put("sampleMaxAbs", sampleMaxAbs).put("sampleRms", sampleRms)
        .put("sampleToleranceRelative", sampleToleranceRelative).put("sampleToleranceAbsolute", sampleToleranceAbsolute)
}

/** Separate, closed scanner schema. Diffusion geometry must never validate scanner budgets. */
internal object GpuPrintScanProtocol {
    const val HARD_CAP = 384L * GpuRenderProtocol.MIB
    const val NATIVE_BACKEND = "Vulkan f32 resident PCHIP chain; CPU f64 scan finish"
    const val SAFE_BACKEND = "vulkan_f32_resident_pchip_chain_cpu_f64_scan_finish"
    const val PRINT_NATIVE_BACKEND = "Vulkan f32 original print exposure"
    const val PRINT_SAFE_BACKEND = "vulkan_f32_original_print_exposure"
    fun nativeBackend(operation: GpuRenderOperation) = if (operation == GpuRenderOperation.PRINT_EXPOSURE) PRINT_NATIVE_BACKEND else NATIVE_BACKEND
    fun safeBackend(operation: GpuRenderOperation) = if (operation == GpuRenderOperation.PRINT_EXPOSURE) PRINT_SAFE_BACKEND else SAFE_BACKEND
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val planKeys = setOf("schemaVersion", "operation", "token", "manifestSha256", "width", "height", "mode", "chunkPixels", "chunkCount", "tableScalars", "deviceBytes", "hostBytes", "driverAllowanceBytes", "helperAllowanceBytes", "combinedPeakBytes", "diskBytes")
    private val summaryKeys = setOf("schemaVersion", "operation", "gpuUsed", "fullImage", "width", "height", "channels", "mode", "chunkCount", "elapsedMs", "backend", "vendor", "shaderF64Advertised", "uploadBytes", "readbackBytes", "dispatches", "deviceBytes", "sampleCount", "sampleMaxAbs", "sampleRms", "sampleToleranceRelative", "sampleToleranceAbsolute")
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
    fun parsePlan(raw: String, token: Long, operation: GpuRenderOperation = GpuRenderOperation.PRINT_SCAN): GpuPrintScanPlan {
        require(operation in setOf(GpuRenderOperation.PRINT_SCAN, GpuRenderOperation.PRINT_EXPOSURE))
        val root = GpuRenderProtocol.objectOf(raw)
        root.exact(setOf("ok", "schemaVersion", "operation", "plan"))
        require(root.bool("ok") && root.long("schemaVersion") == 1L && root.get("operation") == if (operation == GpuRenderOperation.PRINT_SCAN) "planGpuPrintScan" else "planGpuPrintExposureStage")
        val p = root.getJSONObject("plan"); p.exact(planKeys)
        require(p.long("schemaVersion") == 1L && p.get("operation") == operation.wire && p.long("token", 1) == token)
        val digest = p.get("manifestSha256") as? String ?: error("Invalid digest"); require(digestPattern.matches(digest))
        val plan = GpuPrintScanPlan(token, digest, p.long("width", 1, 40000).toInt(), p.long("height", 1, 40000).toInt(),
            GpuPrintScanMode.fromWire(p.get("mode")), p.long("chunkPixels", 1, 65536).toInt(), p.long("chunkCount", 1, 1_600_000_000),
            p.long("tableScalars", 1, HARD_CAP / 16), p.long("deviceBytes", 1, HARD_CAP), p.long("hostBytes", 1, HARD_CAP),
            p.long("driverAllowanceBytes", 1, HARD_CAP), p.long("helperAllowanceBytes", 1, HARD_CAP),
            p.long("combinedPeakBytes", 1, HARD_CAP), p.long("diskBytes", 1, 64L * 1024 * GpuRenderProtocol.MIB), operation)
        validatePlan(plan); return plan
    }
    fun validatePlan(p: GpuPrintScanPlan) {
        require(p.operation in setOf(GpuRenderOperation.PRINT_SCAN, GpuRenderOperation.PRINT_EXPOSURE))
        require((p.operation == GpuRenderOperation.PRINT_EXPOSURE) == (p.mode == GpuPrintScanMode.PRINT_EXPOSURE_ONLY))
        require(p.token > 0 && p.width in 1..40000 && p.height in 1..40000)
        val pixels = p.width.toLong() * p.height
        require(p.chunkPixels == minOf(65536L, pixels).toInt())
        require(p.chunkCount == (pixels + p.chunkPixels - 1) / p.chunkPixels)
        require(p.tableScalars in 1..(HARD_CAP / 16))
        val c = p.chunkPixels.toLong(); val t = p.tableScalars
        require(p.deviceBytes == 4 * t + 36 * c + 4096)
        require(p.hostBytes == 16 * t + 128 * c + 2 * GpuRenderProtocol.MIB)
        require(p.driverAllowanceBytes == 64 * GpuRenderProtocol.MIB && p.helperAllowanceBytes == 64 * GpuRenderProtocol.MIB)
        val combined = Math.addExact(Math.addExact(p.deviceBytes, p.hostBytes), Math.addExact(p.driverAllowanceBytes, p.helperAllowanceBytes))
        require(combined == p.combinedPeakBytes && combined <= HARD_CAP)
        require(p.diskBytes == 48 * pixels + 8 * t + 65536)
    }
    fun expectedSamples(p: GpuPrintScanPlan): Long {
        val last = p.width.toLong() * p.height - (p.chunkCount - 1) * p.chunkPixels
        return 3 * ((p.chunkCount - 1) * minOf(8, p.chunkPixels) + minOf(8L, last))
    }
    fun parseNativeResult(raw: String, p: GpuPrintScanPlan): Pair<GpuRenderCode, GpuPrintScanSummary?> {
        val value = GpuRenderProtocol.objectOf(raw)
        if (!value.bool("ok")) return GpuRenderProtocol.nativeFailure(p.operation, value.opt("code")) to null
        require(value.long("schemaVersion") == 1L && value.get("operation") == p.operation.wire)
        require(value.long("token") == p.token && value.get("manifestSha256") == p.digest)
        require(value.bool("gpuUsed") && value.bool("fullImage") && value.long("channels") == 3L)
        require(value.long("width") == p.width.toLong() && value.long("height") == p.height.toLong())
        require(value.get("mode") == p.mode.wire && value.long("chunkCount") == p.chunkCount && value.long("deviceBytes") == p.deviceBytes)
        require(value.get("backend") == nativeBackend(p.operation))
        return GpuRenderCode.OK to GpuPrintScanSummary(p.width, p.height, p.mode, p.chunkCount,
            value.number("elapsedMs", 125000.0), value.long("vendor", 0, 0xffffffffL), value.bool("shaderF64Advertised"),
            value.long("uploadBytes", 0, 128L * 1024 * GpuRenderProtocol.MIB), value.long("readbackBytes", 0, 128L * 1024 * GpuRenderProtocol.MIB),
            value.long("dispatches", 1, 1_000_000), p.deviceBytes,
            value.long("sampleCount", expectedSamples(p), expectedSamples(p)), value.number("sampleMaxAbs", 1e300), value.number("sampleRms", 1e300),
            value.number("sampleToleranceRelative", 2e-5).also { require(it == 2e-5) }, value.number("sampleToleranceAbsolute", 2e-5).also { require(it == 2e-5) }, p.operation)
    }
    fun parseResult(raw: String, plan: GpuPrintScanPlan): Pair<GpuRenderCode, GpuPrintScanSummary?> {
        val root = GpuRenderProtocol.objectOf(raw, GpuRenderProtocol.MAX_IPC); require(root.long("schemaVersion") == 1L)
        val code = GpuRenderCode.valueOf(root.getString("code"))
        root.exact(if (code == GpuRenderCode.OK) setOf("schemaVersion", "code", "summary") else setOf("schemaVersion", "code"))
        if (code != GpuRenderCode.OK) return code to null
        val s = root.getJSONObject("summary"); s.exact(summaryKeys)
        require(s.get("backend") == safeBackend(plan.operation))
        return parseNativeResult(JSONObject(s.toString()).put("ok", true).put("token", plan.token)
            .put("manifestSha256", plan.digest).put("backend", nativeBackend(plan.operation)).toString(), plan)
    }
}
