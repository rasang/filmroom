package dev.filmroom.engine

import org.json.JSONObject

/** Only closed enums and bounded numbers are persisted or sent across Binder. */
enum class GpuProbeStatus {
    COMPLETE, CANCELLED, START_REVOKED, TIMED_OUT, HELPER_DIED, MEMORY_UNAVAILABLE, SNAPSHOT_INVALID,
    NATIVE_UNAVAILABLE, GPU_UNAVAILABLE, NATIVE_FAILED, PROTOCOL_REJECTED, BIND_FAILED
}
enum class GpuProbeDeviceType { INTEGRATED_GPU, DISCRETE_GPU }
enum class GpuProbePhase { IDLE, PLANNING, WAITING_FOR_MEMORY, BINDING, RUNNING, STOPPING, FINISHED }
data class GpuProbeState(
    val token: Long? = null,
    val phase: GpuProbePhase = GpuProbePhase.IDLE,
    val lastSummary: GpuProbeSummary? = null,
) { fun displayText(): String = GpuProbeDisplay.state(this) }
data class GpuProbeMeasurements(
    val originalWidth: Int, val originalHeight: Int, val fftSide: Int, val radius: Int,
    val roiWidth: Int, val roiHeight: Int, val combinedPeakBytes: Long,
    val vendor: Long, val deviceType: GpuProbeDeviceType, val f64Advertised: Boolean,
    val preparationMs: Double, val initializationMs: Double, val comparisonMs: Double,
    val totalMs: Double, val stageTotalMs: Double,
    val maxAbs: Double, val rms: Double, val referencePeak: Double, val maxAbsOverPeak: Double,
    val changedSamples: Long, val bitExact: Boolean,
)
data class GpuProbeSummary(val status: GpuProbeStatus, val measurements: GpuProbeMeasurements? = null) {
    /** Reconstructed from typed values; never contains native adapter/driver prose or paths. */
    fun toSafeJson(): String = GpuProbeProtocol.encodeSummary(this)
    fun displayText(): String = GpuProbeDisplay.summary(this)
    companion object {
        fun fromSafeJson(raw: String): GpuProbeSummary? = runCatching { GpuProbeProtocol.parseSummary(raw) }.getOrNull()
    }
}

internal data class GpuProbePlan(
    val token: Long, val manifestSha256: String,
    val originalWidth: Int, val originalHeight: Int, val fftSide: Int, val radius: Int,
    val roiWidth: Int, val roiHeight: Int, val channel: Int,
    val deviceBytes: Long, val hostBytes: Long, val driverAllowanceBytes: Long,
    val helperAllowanceBytes: Long, val combinedPeakBytes: Long, val snapshotBytes: Long,
)

internal object GpuProbeProtocol {
    const val MAX_NATIVE_BYTES = 16 * 1024
    const val MAX_IPC_BYTES = 4096
    const val COMBINED_CAP = 320L * 1024 * 1024
    const val ALLOWANCE = 64L * 1024 * 1024
    const val SNAPSHOT_CAP = 64L * 1024 * 1024
    private val hash = Regex("[0-9a-f]{64}")
    private val planKeys = setOf("token", "manifestSha256", "originalWidth", "originalHeight", "fftSide", "radius", "roiWidth", "roiHeight", "channel", "deviceBytes", "hostBytes", "driverAllowanceBytes", "helperAllowanceBytes", "combinedPeakBytes", "snapshotBytes")
    private val measurementKeys = setOf("originalWidth", "originalHeight", "fftSide", "radius", "roiWidth", "roiHeight", "combinedPeakBytes", "vendor", "deviceType", "f64Advertised", "preparationMs", "initializationMs", "comparisonMs", "totalMs", "stageTotalMs", "maxAbs", "rms", "referencePeak", "maxAbsOverPeak", "changedSamples", "bitExact")

    fun boundedObject(text: String, limit: Int): JSONObject {
        require(text.length <= limit && text.toByteArray(Charsets.UTF_8).size <= limit)
        return JSONObject(text)
    }
    private fun JSONObject.keysExactly(names: Set<String>) { require(keys().asSequence().toSet() == names) }
    private fun JSONObject.long(name: String, min: Long = 0, max: Long = Long.MAX_VALUE): Long {
        val number = get(name)
        require(number is Int || number is Long)
        return (number as Number).toLong().also { require(it in min..max) }
    }
    private fun JSONObject.int(name: String, min: Int, max: Int): Int = long(name, min.toLong(), max.toLong()).toInt()
    private fun JSONObject.bool(name: String): Boolean = (get(name) as? Boolean) ?: error("Invalid boolean")
    private fun JSONObject.number(name: String, max: Double = 1e300): Double {
        val value = (get(name) as? Number)?.toDouble() ?: error("Invalid number")
        require(value.isFinite() && value >= 0.0 && value <= max)
        return value
    }
    private fun JSONObject.literal(name: String, expected: String) { require(get(name) == expected) }
    private fun plan(value: JSONObject, token: Long): GpuProbePlan {
        value.keysExactly(planKeys)
        require(token > 0 && value.long("token", 1) == token)
        val digest = value.get("manifestSha256") as? String ?: error("Invalid digest")
        require(hash.matches(digest))
        val result = GpuProbePlan(token, digest,
            value.int("originalWidth", 1, 40000), value.int("originalHeight", 1, 40000),
            value.int("fftSide", 2, 2048), value.int("radius", 0, 1023),
            value.int("roiWidth", 1, 256), value.int("roiHeight", 1, 256), value.int("channel", 1, 1),
            value.long("deviceBytes", 1), value.long("hostBytes", 1), value.long("driverAllowanceBytes"),
            value.long("helperAllowanceBytes"), value.long("combinedPeakBytes", 1, COMBINED_CAP),
            value.long("snapshotBytes", 1, SNAPSHOT_CAP))
        validatePlan(result)
        return result
    }
    fun validatePlan(p: GpuProbePlan) {
        require(p.driverAllowanceBytes == ALLOWANCE && p.helperAllowanceBytes == ALLOWANCE)
        val total = Math.addExact(Math.addExact(p.deviceBytes, p.hostBytes), Math.addExact(p.driverAllowanceBytes, p.helperAllowanceBytes))
        require(total == p.combinedPeakBytes && total <= COMBINED_CAP)
        validateGeometry(p.originalWidth, p.originalHeight, p.fftSide, p.radius, p.roiWidth, p.roiHeight)
        val kernelSide = 2L * p.radius + 1
        val n = p.fftSide.toLong()
        val pixels = p.roiWidth.toLong() * p.roiHeight
        val core = maxOf(p.roiWidth, p.roiHeight).toLong()
        val data = (n * n + kernelSide * kernelSide) * 4 + n * 8
        val expectedDevice = 3 * n * n * 8 + data + core * core * 8 + 65536
        val expectedHost = data + kernelSide * kernelSide * 8 + n * 8 + pixels * 24 + 1024 * 1024
        require(p.deviceBytes == expectedDevice && p.hostBytes == expectedHost)
        val snapshot = Math.addExact(Math.multiplyExact(p.fftSide.toLong() * p.fftSide + kernelSide * kernelSide + p.roiWidth.toLong() * p.roiHeight, 8L), 8192L)
        require(snapshot == p.snapshotBytes && snapshot <= SNAPSHOT_CAP)
    }
    private fun validateGeometry(w: Int, h: Int, n: Int, r: Int, cw: Int, ch: Int) {
        require(w in 1..40000 && h in 1..40000 && r in 0..1023 && n in 2..2048)
        val need = (2 * r + minOf(256, w, h)).coerceAtLeast(2)
        var expected = 2
        while (expected < need) expected *= 2
        require(n == expected && n > 2 * r)
        require(cw == minOf(256, n - 2 * r, w) && ch == minOf(256, n - 2 * r, h))
    }
    fun parsePlan(raw: String, token: Long): GpuProbePlan {
        val root = boundedObject(raw, MAX_NATIVE_BYTES)
        root.keysExactly(setOf("ok", "schemaVersion", "operation", "plan"))
        require(root.bool("ok") && root.int("schemaVersion", 1, 1) == 1)
        root.literal("operation", "planGpuDiffusionProbe")
        return plan(root.getJSONObject("plan"), token)
    }
    fun availableBudget(plan: GpuProbePlan, memory: NativeMemorySnapshot): Long? {
        validatePlan(plan)
        val mib = NativeMemoryPolicy.chooseBudgetMiB(memory.availableBytes, memory.thresholdBytes, memory.lowMemory) ?: return null
        val limit = minOf(COMBINED_CAP, mib * NativeMemoryPolicy.MIB)
        return plan.combinedPeakBytes.takeIf { it <= limit }
    }
    fun parseNativeResult(raw: String, expected: GpuProbePlan): GpuProbeSummary {
        val root = boundedObject(raw, MAX_NATIVE_BYTES)
        require(root.int("schemaVersion", 1, 1) == 1 && root.bool("diagnosticOnly") && !root.bool("outputReplaced"))
        if (!root.bool("ok")) {
            // Never copy error strings, even if a future driver returns arbitrary prose.
            val status = when (root.opt("code")) {
                "CANCELLED" -> GpuProbeStatus.CANCELLED
                "GPU_UNAVAILABLE_OR_LIMITS" -> GpuProbeStatus.GPU_UNAVAILABLE
                "INSUFFICIENT_PROBE_MEMORY", "PROBE_BUDGET_EXCEEDED" -> GpuProbeStatus.MEMORY_UNAVAILABLE
                "SNAPSHOT_IO", "INVALID_MANIFEST", "NONFINITE_MANIFEST", "NONFINITE_SNAPSHOT", "SNAPSHOT_HASH_MISMATCH", "STALE_PROBE_PLAN", "INVALID_PROBE_PLAN", "INVALID_TOKEN", "INVALID_ARGUMENT", "ORIGINAL_PSF_EXCEEDS_PROBE_LIMIT" -> GpuProbeStatus.SNAPSHOT_INVALID
                else -> GpuProbeStatus.NATIVE_FAILED
            }
            return GpuProbeSummary(status)
        }
        root.literal("operation", "runGpuDiffusionProbe")
        require(!root.bool("fullRenderMeasured") && plan(root.getJSONObject("plan"), expected.token) == expected)
        for ((key, value) in listOf("originalWidth" to expected.originalWidth, "originalHeight" to expected.originalHeight, "fftSide" to expected.fftSide, "radius" to expected.radius, "roiWidth" to expected.roiWidth, "roiHeight" to expected.roiHeight, "channel" to 1)) require(root.long(key) == value.toLong())
        val metrics = root.getJSONObject("metrics")
        metrics.literal("backend", "Vulkan research f32 FFT (not f64)")
        require(metrics.bool("hardwareGpu") && !metrics.bool("exactF64Claim"))
        require(metrics.long("devicePayloadBytes") == expected.deviceBytes && metrics.long("tiles") == 1L)
        metrics.long("uploadBytes", 0, COMBINED_CAP * 2)
        metrics.long("readbackBytes", 0, COMBINED_CAP)
        metrics.long("dispatches", 1, 100000)
        val deviceType = when (metrics.get("adapterDeviceType")) {
            "IntegratedGpu" -> GpuProbeDeviceType.INTEGRATED_GPU
            "DiscreteGpu" -> GpuProbeDeviceType.DISCRETE_GPU
            else -> error("Unqualified device")
        }
        val error = root.getJSONObject("error")
        require(error.long("nonfiniteSamples") == 0L)
        val changed = error.long("changedSamples", 0, expected.roiWidth.toLong() * expected.roiHeight)
        val bitExact = root.bool("bitExact")
        require(bitExact == (changed == 0L))
        val measurements = GpuProbeMeasurements(expected.originalWidth, expected.originalHeight, expected.fftSide, expected.radius,
            expected.roiWidth, expected.roiHeight, expected.combinedPeakBytes, metrics.long("vendor", 0, 0xffffffffL),
            deviceType, metrics.bool("shaderF64Advertised"), root.number("preparationMs", 600000.0),
            root.number("initializationMs", 600000.0), root.number("comparisonMs", 600000.0), root.number("totalMs", 600000.0),
            metrics.number("stageTotalMs", 600000.0), error.number("maxAbs"), error.number("rms"), error.number("referencePeak"),
            error.number("maxAbsOverPeak"), changed, bitExact)
        return GpuProbeSummary(GpuProbeStatus.COMPLETE, measurements)
    }
    fun encodeSummary(summary: GpuProbeSummary): String {
        require((summary.status == GpuProbeStatus.COMPLETE) == (summary.measurements != null))
        val root = JSONObject().put("schemaVersion", 1).put("status", summary.status.name)
            .put("diagnosticOnly", true).put("outputReplaced", false).put("fullRenderMeasured", false)
        summary.measurements?.let { m ->
            root.put("measurements", JSONObject()
                .put("originalWidth", m.originalWidth).put("originalHeight", m.originalHeight).put("fftSide", m.fftSide).put("radius", m.radius)
                .put("roiWidth", m.roiWidth).put("roiHeight", m.roiHeight).put("combinedPeakBytes", m.combinedPeakBytes)
                .put("vendor", m.vendor).put("deviceType", m.deviceType.name).put("f64Advertised", m.f64Advertised)
                .put("preparationMs", m.preparationMs).put("initializationMs", m.initializationMs).put("comparisonMs", m.comparisonMs)
                .put("totalMs", m.totalMs).put("stageTotalMs", m.stageTotalMs).put("maxAbs", m.maxAbs).put("rms", m.rms)
                .put("referencePeak", m.referencePeak).put("maxAbsOverPeak", m.maxAbsOverPeak).put("changedSamples", m.changedSamples).put("bitExact", m.bitExact))
        }
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_IPC_BYTES); parseSummary(it) }
    }
    fun parseSummary(raw: String): GpuProbeSummary {
        val root = boundedObject(raw, MAX_IPC_BYTES)
        require(root.int("schemaVersion", 1, 1) == 1 && root.bool("diagnosticOnly") && !root.bool("outputReplaced") && !root.bool("fullRenderMeasured"))
        val status = GpuProbeStatus.valueOf(root.getString("status"))
        val keys = setOf("schemaVersion", "status", "diagnosticOnly", "outputReplaced", "fullRenderMeasured")
        root.keysExactly(if (status == GpuProbeStatus.COMPLETE) keys + "measurements" else keys)
        if (status != GpuProbeStatus.COMPLETE) return GpuProbeSummary(status)
        val m = root.getJSONObject("measurements")
        m.keysExactly(measurementKeys)
        val w = m.int("originalWidth", 1, 40000); val h = m.int("originalHeight", 1, 40000)
        val n = m.int("fftSide", 2, 2048); val r = m.int("radius", 0, 1023)
        val cw = m.int("roiWidth", 1, 256); val ch = m.int("roiHeight", 1, 256)
        validateGeometry(w, h, n, r, cw, ch)
        val changed = m.long("changedSamples", 0, cw.toLong() * ch)
        val exact = m.bool("bitExact"); require(exact == (changed == 0L))
        return GpuProbeSummary(status, GpuProbeMeasurements(w, h, n, r, cw, ch,
            m.long("combinedPeakBytes", 2 * ALLOWANCE, COMBINED_CAP), m.long("vendor", 0, 0xffffffffL),
            GpuProbeDeviceType.valueOf(m.getString("deviceType")), m.bool("f64Advertised"),
            m.number("preparationMs", 600000.0), m.number("initializationMs", 600000.0), m.number("comparisonMs", 600000.0),
            m.number("totalMs", 600000.0), m.number("stageTotalMs", 600000.0), m.number("maxAbs"), m.number("rms"),
            m.number("referencePeak"), m.number("maxAbsOverPeak"), changed, exact))
    }
}
