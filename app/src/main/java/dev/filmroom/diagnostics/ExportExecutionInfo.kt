package dev.filmroom.diagnostics

import org.json.JSONObject

/** Fixed enum/numeric telemetry only. Never retain native policy prose or paths. */
enum class ExportPoolStatus(val id: String) {
    PRIVATE_BOUNDED("private_bounded"),
    LOW_OR_UNKNOWN_CPU_SERIAL("low_or_unknown_cpu_serial_sampling"),
    POOL_UNAVAILABLE_SERIAL("pool_unavailable_serial_sampling")
}
object ExportExecutionInfo {
    fun fromNativeResult(result: JSONObject): SafeInfo {
        val base = GpuRuntimeInfo.fromNativeResult(result)
        val execution = result.optJSONObject("metrics")?.optJSONObject("exportExecution") ?: return base
        fun count(key: String, max: Long = 1_800_000_000L): Long? {
            val raw = execution.opt(key)
            val value = when (raw) { is Int -> raw.toLong(); is Long -> raw; else -> return null }
            return value.takeIf { it in 0..max }
        }
        fun duration(key: String) = (execution.opt(key) as? Number)?.toDouble()
            ?.takeIf { it.isFinite() && it in 0.0..604_800_000.0 }
        return base.copy(
            exportPoolStatus = ExportPoolStatus.entries.firstOrNull { it.id == execution.opt("poolStatus") },
            exportWorkers = count("activePoolWorkers", 4),
            exportParallelChunks = count("parallelParticleChunks"),
            exportSerialChunks = count("serialParticleChunks"),
            exportInteractiveWaitMs = duration("interactiveWaitMs"),
            exportAdmissionWaitMs = duration("admissionWaitMs"),
            exportWorkerCap = count("workerCap", 4),
            exportInteractiveWaits = count("interactiveWaits"),
            exportInteractiveSerialChunks = count("interactiveSerialChunks"),
        )
    }
}
