package dev.filmroom.diagnostics

import dev.filmroom.engine.GpuRenderCode
import dev.filmroom.engine.GpuRenderOperation
import java.util.zip.CRC32

/** Closed vocabulary shared by CPU preview/detail/export and the isolated GPU process. */
internal enum class RenderLifecycleVariant { QUICK, STABLE, PREVIEW, EXPORT, OPEN, BUILD, TILE, REGION }
internal enum class RenderLifecycleScope { PREVIEW, DETAIL, EXPORT, GPU_STAGE }
internal enum class GpuLifecyclePhase {
    LINK, STAGE_BEGIN, PLAN_ENTER, PLAN_EXIT, RESERVE_ENTER, RESERVED, START_REQUEST,
    BIND_REQUEST, CONNECTED, HELLO_SENT, READY_RECEIVED, RUN_SEND_REQUEST, RUN_SENT,
    WAIT, RESULT_RECEIVED, CANCEL_REQUEST, STOP_REQUEST, TIMEOUT, DEATH_OBSERVED,
    DRAIN_START, DRAIN_END, QUARANTINED, NOT_STARTED, STAGE_END,
    SERVICE_CREATED, START_RECEIVED, HELLO_RECEIVED, READY_SENT, RUN_RECEIVED,
    LOCK_ENTER, LOCK_ACQUIRED, NATIVE_ENTER, NATIVE_RETURN, RESULT_SENT,
    CANCEL_RECEIVED, HARD_STOP_ARMED, PREVIEW_PREPARE, ADMISSION_WAIT, ADMITTED,
    JNI_PREPARE, NOT_ENTERED, JNI_ENTER, JNI_RETURN, EXPORT_BEGIN, EXPORT_END, HEARTBEAT,
    MUTEX_WAIT, PREPARE_BEGIN, PREPARE_END, MEMORY_WAIT, MEMORY_EVICTION,
    MEMORY_REJECTED, MEMORY_RELEASED, JNI_FAILED, PROGRESS_WAIT, FALLBACK,
    CLEANUP_BEGIN, CLEANUP_END, CLEANUP_RETAINED, OPERATION_END, OPERATION_FAILED, OPERATION_CANCELLED,
    WORKER_RELEASED, NATIVE_CREATE_ENTER, NATIVE_CREATE_RETURN, PLAN_RETURN, NATIVE_PHASE,
}
internal enum class NativeGpuPhase(val code: Int) {
    UNKNOWN(0), PREPARE(1), DEVICE(2), PIPELINE(3), UPLOAD(4), COMPUTE(5), READBACK(6), GUARD(7), COMMIT(8), CLEANUP(9),
}
internal enum class NativeGpuPhaseState(val code: Int) { UNKNOWN(0), STARTED(1), ENDED(2), ABORTED(3) }
internal enum class NativeGpuTerminal(val code: Int) { ACTIVE(0), SUCCESS(1), FAILED(2), CANCELLED(3), PANIC(4) }
internal enum class NativeGpuActivity(val code: Int) { STAGE(0), SYNTHETIC_ADMISSION(1) }
internal enum class NativeGpuStep(val code: Int) { GENERAL(0), ADAPTER_REQUEST(1), DEVICE_REQUEST(2), SHADER_MODULE(3), COMPUTE_PIPELINE(4), DRIVER_VALIDATION(5) }
internal enum class NativeGpuDiagnosticCode(val code: Int) { AVAILABLE(0), CALLBACK_UNAVAILABLE(1) }

internal enum class RenderMemoryWaitReason {
    CHECK, WORKER_LIMIT, CAPACITY, LOW_MEMORY, EVICTION, ADMITTED, REJECTED,
    WORKER_RELEASED, BYTES_RELEASED, WORKERS, BYTES, WORKERS_AND_BYTES, EXCLUSIVE, UNKNOWN,
}
internal data class RenderMemoryInfo(
    val requestedBytes: Long? = null, val chargedBytes: Long? = null,
    val availableBytes: Long? = null, val reservedBytes: Long? = null,
    val activeWorkers: Int? = null, val reservationCount: Int? = null,
    val waitReason: RenderMemoryWaitReason? = null,
) {
    fun valid(): Boolean = listOf(requestedBytes, chargedBytes, availableBytes, reservedBytes).all { it == null || it in 0..(1L shl 40) } &&
        (activeWorkers == null || activeWorkers in 0..4096) && (reservationCount == null || reservationCount in 0..65536)
    fun json(): String = buildList {
        requestedBytes?.let { add("\"requestedBytes\":$it") }; chargedBytes?.let { add("\"chargedBytes\":$it") }
        availableBytes?.let { add("\"availableBytes\":$it") }; reservedBytes?.let { add("\"reservedBytes\":$it") }
        activeWorkers?.let { add("\"activeWorkers\":$it") }; reservationCount?.let { add("\"reservationCount\":$it") }
        waitReason?.let { add("\"waitReason\":${jsonString(it.name)}") }
    }.joinToString(",", "{", "}")
}

/** Native numeric fields are parsed and range checked before reaching the journal. */
internal data class NativeGpuLifecycle(
    val sequence: Long,
    val phase: NativeGpuPhase,
    val state: NativeGpuPhaseState,
    val elapsedMs: Long,
    val totalElapsedMs: Long,
    val terminal: NativeGpuTerminal,
    val diagnosticCode: NativeGpuDiagnosticCode,
    val activity: NativeGpuActivity = NativeGpuActivity.STAGE,
    val step: NativeGpuStep = NativeGpuStep.GENERAL,
    val failureCode: Int = 0,
) {
    init { require(failureCode in 0..NativeGpuFailureLegend.MAX_CODE && sequence >= 0 && elapsedMs in 0..MAX_LIFECYCLE_DURATION_MS && totalElapsedMs in 0..MAX_LIFECYCLE_DURATION_MS) }
}

internal const val MAX_LIFECYCLE_LINE_BYTES = 768
internal const val MAX_LIFECYCLE_DURATION_MS = 30L * 24 * 60 * 60 * 1000
internal const val MAX_LIFECYCLE_WALL_MS = 253402300799999L

/** No file paths, photo metadata, exception text, or arbitrary key/value payloads. */
internal data class RenderLifecycleEvent(
    val wallMs: Long,
    val uptimeMs: Long,
    val run: String,
    val pid: Int,
    val sequence: Long,
    val token: Long,
    val scope: RenderLifecycleScope,
    val operation: GpuRenderOperation?,
    val phase: GpuLifecyclePhase,
    val code: GpuRenderCode? = null,
    val elapsedMs: Long? = null,
    val native: NativeGpuLifecycle? = null,
    val parentToken: Long? = null,
    val memory: RenderMemoryInfo? = null,
    val operationId: Long? = null, val variant: RenderLifecycleVariant? = null,
) {
    init {
        require(wallMs in 0..MAX_LIFECYCLE_WALL_MS && uptimeMs >= 0)
        require(run.matches(Regex("[0-9a-f]{32}")) && pid >= 0 && sequence > 0 && token >= 0)
        require(elapsedMs == null || elapsedMs in 0..MAX_LIFECYCLE_DURATION_MS)
        require(parentToken == null || parentToken > 0)
        require(memory == null || memory.valid())
        require(operationId == null || operationId >= 0)
        require((phase == GpuLifecyclePhase.NATIVE_PHASE) == (native != null))
    }
    fun line(): String {
        val body = listOf("1", wallMs, uptimeMs, run, pid, sequence, token, scope.name,
            operation?.name ?: "-", phase.name, code?.name ?: "-", elapsedMs ?: "-",
            native?.sequence ?: "-", native?.phase?.name ?: "-", native?.state?.name ?: "-",
            native?.elapsedMs ?: "-", native?.totalElapsedMs ?: "-", native?.terminal?.name ?: "-",
            native?.diagnosticCode?.name ?: "-", native?.activity?.name ?: "-", native?.step?.name ?: "-", native?.failureCode ?: "-", parentToken ?: "-",
            memory?.requestedBytes ?: "-", memory?.chargedBytes ?: "-", memory?.availableBytes ?: "-",
            memory?.reservedBytes ?: "-", memory?.activeWorkers ?: "-", memory?.reservationCount ?: "-",
            memory?.waitReason?.name ?: "-", operationId ?: "-", variant?.name ?: "-").joinToString("\t")
        return "$body\t${CRC32().apply { update(body.toByteArray(Charsets.US_ASCII)) }.value.toString(16)}\n"
    }
    fun json(): String = buildString {
        append("{\"recordedUtcMs\":").append(wallMs).append(",\"uptimeMs\":").append(uptimeMs)
        append(",\"runId\":").append(jsonString(run)).append(",\"pid\":").append(pid)
        append(",\"sequence\":").append(sequence).append(",\"token\":").append(token)
        append(",\"scope\":").append(jsonString(scope.name)).append(",\"phase\":").append(jsonString(phase.name))
        operationId?.let { append(",\"operationId\":").append(it) }
        variant?.let { append(",\"variant\":").append(jsonString(it.name)) }
        parentToken?.let { append(",\"parentToken\":").append(it) }
        operation?.let { append(",\"operation\":").append(jsonString(it.name)) }
        code?.let { append(",\"code\":").append(jsonString(it.name)) }
        elapsedMs?.let { append(",\"elapsedMs\":").append(it) }
        memory?.let { append(",\"memory\":").append(it.json()) }
        native?.let {
            append(",\"native\":{\"sequence\":").append(it.sequence)
            append(",\"phase\":").append(jsonString(it.phase.name)).append(",\"state\":").append(jsonString(it.state.name))
            append(",\"elapsedMs\":").append(it.elapsedMs).append(",\"totalElapsedMs\":").append(it.totalElapsedMs)
            append(",\"terminal\":").append(jsonString(it.terminal.name))
            append(",\"diagnosticCode\":").append(jsonString(it.diagnosticCode.name)).append(",\"activity\":").append(jsonString(it.activity.name)).append(",\"step\":").append(jsonString(it.step.name)).append(",\"failureCode\":").append(it.failureCode).append(",\"failureName\":").append(jsonString(NativeGpuFailureLegend.name(it.failureCode))).append('}')
        }
        append('}')
    }
    companion object {
        fun parse(line: String): RenderLifecycleEvent? = runCatching {
            if (line.length > MAX_LIFECYCLE_LINE_BYTES || line.any { it != '\t' && it != '\n' && it.code !in 32..126 }) return null
            val parts = line.removeSuffix("\n").split('\t')
            if (parts.size != 33 || parts[0] != "1") return null
            val body = parts.take(32).joinToString("\t")
            if (parts[32].toLongOrNull(16) != CRC32().apply { update(body.toByteArray(Charsets.US_ASCII)) }.value) return null
            val native = if (parts[12] == "-") {
                if (parts.subList(12, 22).any { it != "-" }) return null
                null
            } else NativeGpuLifecycle(parts[12].toLong(), NativeGpuPhase.valueOf(parts[13]),
                NativeGpuPhaseState.valueOf(parts[14]), parts[15].toLong(), parts[16].toLong(),
                NativeGpuTerminal.valueOf(parts[17]), NativeGpuDiagnosticCode.valueOf(parts[18]), NativeGpuActivity.valueOf(parts[19]), NativeGpuStep.valueOf(parts[20]), parts[21].toInt())
            val memory = if (parts.subList(23, 30).all { it == "-" }) null else RenderMemoryInfo(
                parts[23].takeUnless { it == "-" }?.toLong(), parts[24].takeUnless { it == "-" }?.toLong(),
                parts[25].takeUnless { it == "-" }?.toLong(), parts[26].takeUnless { it == "-" }?.toLong(),
                parts[27].takeUnless { it == "-" }?.toInt(), parts[28].takeUnless { it == "-" }?.toInt(),
                parts[29].takeUnless { it == "-" }?.let(RenderMemoryWaitReason::valueOf))
            RenderLifecycleEvent(parts[1].toLong(), parts[2].toLong(), parts[3], parts[4].toInt(),
                parts[5].toLong(), parts[6].toLong(), RenderLifecycleScope.valueOf(parts[7]),
                parts[8].takeUnless { it == "-" }?.let(GpuRenderOperation::valueOf),
                GpuLifecyclePhase.valueOf(parts[9]), parts[10].takeUnless { it == "-" }?.let(GpuRenderCode::valueOf),
                parts[11].takeUnless { it == "-" }?.toLong(), native, parts[22].takeUnless { it == "-" }?.toLong(), memory,
                parts[30].takeUnless { it == "-" }?.toLong(), parts[31].takeUnless { it == "-" }?.let(RenderLifecycleVariant::valueOf))
        }.getOrNull()
    }
}

internal data class RenderLifecycleSnapshot(
    val events: List<RenderLifecycleEvent> = emptyList(),
    val inactiveRuns: Set<String> = emptySet(),
    val malformedDiscarded: Long = 0,
    val unavailableSlots: Int = 0,
    val storageHealthy: Boolean = true,
    val droppedThisProcess: Long = 0,
    val historyMayBeTruncated: Boolean = false,
) {
    // A missing end is evidence of an unfinished operation, not proof of a crash or ANR.
    val unfinishedInactiveTokens: Int get() = events.filter { it.token > 0 }.groupBy { Triple(it.run, it.scope, it.token) }
        .count { (key, events) -> key.first in inactiveRuns && run {
            // Cleanup/heartbeat records after a terminal cannot reopen work.
            val start = events.filter { it.phase in setOf(GpuLifecyclePhase.STAGE_BEGIN, GpuLifecyclePhase.EXPORT_BEGIN, GpuLifecyclePhase.PREVIEW_PREPARE) }.maxOfOrNull { it.sequence } ?: 0
            events.none { it.sequence >= start && it.phase in terminalPhases }
        } }
    fun text(): String = buildString {
        append("\n=== RENDER LIFECYCLE V1 ===\n")
        append("events=").append(events.size).append(" malformedDiscarded=").append(malformedDiscarded)
        append(" unavailableSlots=").append(unavailableSlots).append(" storageHealthy=").append(storageHealthy)
        append(" droppedThisProcess=").append(droppedThisProcess).append(" historyMayBeTruncated=").append(historyMayBeTruncated)
        append(" unfinishedInactiveTokens=").append(unfinishedInactiveTokens).append('\n')
        append("Inactive unfinished records do not prove a crash. Native samples are bounded; repeated work between durable samples may be absent. Cross-process wall time may be uncertain. History is bounded to 8 process slots, 64 token breadcrumbs per slot and the selected time window; older records can be evicted.\n")
        events.forEach { event ->
            append("utcMs=").append(event.wallMs).append(" uptimeMs=").append(event.uptimeMs)
            append(" run=").append(event.run).append(" pid=").append(event.pid)
            append(" sequence=").append(event.sequence).append(" token=").append(event.token)
            append(" scope=").append(event.scope.name).append(" phase=").append(event.phase.name)
            event.operationId?.let { append(" operationId=").append(it) }
            event.variant?.let { append(" variant=").append(it.name) }
            event.parentToken?.let { append(" parentToken=").append(it) }
            event.operation?.let { append(" operation=").append(it.name) }
            event.code?.let { append(" code=").append(it.name) }
            event.elapsedMs?.let { append(" elapsedMs=").append(it) }
            event.memory?.let { append(" memory=").append(it.json()) }
            event.native?.let { append(" nativeSequence=").append(it.sequence).append(" nativePhase=").append(it.phase.name)
                .append(" nativeState=").append(it.state.name).append(" nativeElapsedMs=").append(it.elapsedMs)
                .append(" nativeTotalElapsedMs=").append(it.totalElapsedMs).append(" nativeTerminal=").append(it.terminal.name)
                .append(" nativeDiagnosticCode=").append(it.diagnosticCode.name).append(" nativeActivity=").append(it.activity.name).append(" nativeStep=").append(it.step.name).append(" nativeFailureCode=").append(it.failureCode).append(" nativeFailureName=").append(NativeGpuFailureLegend.name(it.failureCode)) }
            append('\n')
        }
    }
    fun json(): String = "{\"schemaVersion\":1,\"storageHealthy\":$storageHealthy,\"malformedDiscarded\":$malformedDiscarded," +
        "\"unavailableSlots\":$unavailableSlots,\"droppedThisProcess\":$droppedThisProcess,\"unfinishedInactiveTokens\":$unfinishedInactiveTokens," +
        "\"historyMayBeTruncated\":$historyMayBeTruncated,\"maxJournalBytes\":${RenderLifecycleJournal.MAX_TOTAL_BYTES},\"perProcessTokenLimit\":64,\"crossProcessTimeUncertain\":true,\"nativeSamplesBounded\":true,\"events\":[${events.joinToString(",") { it.json() }}]}"
    companion object {
        private val terminalPhases = setOf(GpuLifecyclePhase.STAGE_END, GpuLifecyclePhase.EXPORT_END,
            GpuLifecyclePhase.OPERATION_END, GpuLifecyclePhase.OPERATION_FAILED, GpuLifecyclePhase.OPERATION_CANCELLED, GpuLifecyclePhase.NOT_STARTED, GpuLifecyclePhase.NOT_ENTERED,
            GpuLifecyclePhase.RESULT_SENT, GpuLifecyclePhase.DRAIN_END, GpuLifecyclePhase.DEATH_OBSERVED)
    }
}
