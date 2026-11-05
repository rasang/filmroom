package dev.filmroom.engine

import org.json.JSONObject

/** No configuration, paths, adapter names, or arbitrary native strings cross Binder. */
internal object GpuScienceFields {
    val basePlanKeys = setOf("schemaVersion", "operation", "token", "manifestSha256", "width", "height", "deviceBytes", "hostBytes", "driverAllowanceBytes", "helperAllowanceBytes", "combinedPeakBytes", "diskBytes")
    val commonSummaryKeys = setOf("schemaVersion", "operation", "gpuUsed", "fullImage", "width", "height", "channels", "backend", "elapsedMs", "setupMs", "uploadMs", "dispatchWaitMs", "readbackMs", "guardMs", "vendor", "shaderF64Advertised", "uploadBytes", "readbackBytes", "dispatches", "deviceBytes", "passCount", "sampleCount", "sampleMaxAbs", "sampleRms", "sampleToleranceAbsolute", "sampleToleranceRelative", "sampleScope")
    fun JSONObject.exact(keys: Set<String>) { require(keys().asSequence().toSet() == keys) }
    fun JSONObject.long(key: String, min: Long = 0, max: Long = Long.MAX_VALUE): Long {
        val value = get(key); require(value is Long || value is Int)
        return (value as Number).toLong().also { require(it in min..max) }
    }
    fun JSONObject.number(key: String, min: Double = 0.0, max: Double = 1e300): Double {
        val value = (get(key) as? Number)?.toDouble() ?: error("Invalid numeric field")
        require(value.isFinite() && value in min..max); return value
    }
    fun JSONObject.bool(key: String) = get(key) as? Boolean ?: error("Invalid boolean")
    fun plan(raw: String, token: Long, operation: GpuRenderOperation, wrapper: String, extra: Set<String> = emptySet()): JSONObject {
        val root = GpuRenderProtocol.objectOf(raw)
        root.exact(setOf("ok", "schemaVersion", "operation", "plan"))
        require(root.bool("ok") && root.long("schemaVersion") == 1L && root.get("operation") == wrapper)
        return root.getJSONObject("plan").also {
            it.exact(basePlanKeys + extra)
            require(it.long("schemaVersion") == 1L && it.get("operation") == operation.wire && it.long("token", 1) == token)
            require((it.get("manifestSha256") as? String)?.matches(Regex("[0-9a-f]{64}")) == true)
            it.long("width", 1, 40000); it.long("height", 1, 40000)
        }
    }
    fun validateMemory(p: GpuStagePlan, devicePixels: Long, hostMiB: Long, disk: Long, driverMiB: Long = 64, extraDeviceBytes: Long = 0, extraHostBytes: Long = 0) {
        require(p.token > 0 && p.width in 1..40000 && p.height in 1..40000 && p.digest.matches(Regex("[0-9a-f]{64}")))
        require(devicePixels in 1..(128 * GpuRenderProtocol.MIB / 4))
        require(extraDeviceBytes >= 0 && extraHostBytes >= 0)
        require(p.deviceBytes == Math.addExact(20 * devicePixels + 8 * GpuRenderProtocol.MIB + 65536, extraDeviceBytes))
        require(p.hostBytes == Math.addExact(hostMiB * GpuRenderProtocol.MIB, extraHostBytes) && p.driverAllowanceBytes == driverMiB * GpuRenderProtocol.MIB && p.helperAllowanceBytes == 64 * GpuRenderProtocol.MIB)
        val combined = Math.addExact(Math.addExact(p.deviceBytes, p.hostBytes), Math.addExact(p.driverAllowanceBytes, p.helperAllowanceBytes))
        require(p.combinedPeakBytes == combined && combined <= GpuRenderProtocol.HARD_CAP && p.diskBytes == disk)
    }
    fun identity(v: JSONObject, p: GpuStagePlan, backend: String) {
        require(v.long("schemaVersion") == 1L && v.get("operation") == p.operation.wire)
        require(v.long("token") == p.token && v.get("manifestSha256") == p.digest)
        require(v.bool("gpuUsed") && v.bool("fullImage") && v.get("backend") == backend)
        require(v.long("width") == p.width.toLong() && v.long("height") == p.height.toLong() && v.long("deviceBytes") == p.deviceBytes)
        if (v.has("channels")) require(v.long("channels") == 3L)
    }
    fun common(v: JSONObject, p: GpuStagePlan, safeBackend: String, scope: String, tolerance: Double, passes: LongRange, samples: LongRange, dispatches: LongRange): LinkedHashMap<String, Any> {
        require(v.get("sampleScope") == scope)
        require(v.number("sampleToleranceAbsolute") == tolerance && v.number("sampleToleranceRelative") == tolerance)
        return linkedMapOf<String, Any>("schemaVersion" to 1, "operation" to p.operation.wire, "gpuUsed" to true, "fullImage" to true,
            "width" to p.width, "height" to p.height, "channels" to 3, "backend" to safeBackend, "deviceBytes" to p.deviceBytes,
            "sampleScope" to scope, "sampleToleranceAbsolute" to tolerance, "sampleToleranceRelative" to tolerance).apply {
            for (key in listOf("elapsedMs", "setupMs", "uploadMs", "dispatchWaitMs", "readbackMs", "guardMs")) put(key, v.number(key, max = 125000.0))
            put("vendor", v.long("vendor", 0, 0xffffffffL)); put("shaderF64Advertised", v.bool("shaderF64Advertised"))
            for (key in listOf("uploadBytes", "readbackBytes")) put(key, v.long(key, 0, 128L * 1024 * GpuRenderProtocol.MIB))
            put("passCount", v.long("passCount", passes.first, passes.last)); put("sampleCount", v.long("sampleCount", samples.first, samples.last))
            put("dispatches", v.long("dispatches", dispatches.first, dispatches.last))
            put("sampleMaxAbs", v.number("sampleMaxAbs")); put("sampleRms", v.number("sampleRms"))
        }
    }
    fun result(raw: String, p: GpuStagePlan, keys: Set<String>, safeBackend: String, nativeBackend: String, parse: (String) -> Pair<GpuRenderCode, GpuScienceSummary?>): Pair<GpuRenderCode, GpuScienceSummary?> {
        val root = GpuRenderProtocol.objectOf(raw, GpuRenderProtocol.MAX_IPC)
        require(root.long("schemaVersion") == 1L)
        val code = GpuRenderCode.valueOf(root.getString("code"))
        root.exact(if (code == GpuRenderCode.OK) setOf("schemaVersion", "code", "summary") else setOf("schemaVersion", "code"))
        if (code != GpuRenderCode.OK) return code to null
        val summary = root.getJSONObject("summary"); summary.exact(commonSummaryKeys + keys)
        require(summary.get("backend") == safeBackend)
        return parse(JSONObject(summary.toString()).put("ok", true).put("token", p.token).put("manifestSha256", p.digest).put("backend", nativeBackend).toString())
    }
}

/** Constructed only from fixed per-operation field lists and independently bounded primitives. */
internal class GpuScienceSummary private constructor(private val fields: Map<String, Any>) : GpuStageSummary {
    override fun json(): JSONObject = JSONObject(fields)
    companion object { fun validated(fields: Map<String, Any>): GpuScienceSummary = GpuScienceSummary(fields.toMap()) }
}
