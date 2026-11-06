package dev.filmroom.diagnostics

import java.io.File
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32

internal interface DiagnosticClock { fun wallMs(): Long; fun elapsedMs(): Long }

internal data class DiagnosticEvent(
    val wallMs: Long, val elapsedMs: Long, val run: String, val sequence: Long,
    val operationId: Long, val operation: Operation, val stage: Stage,
    val outcome: Outcome? = null, val error: ErrorCode? = null, val info: SafeInfo = SafeInfo(),
) {
    fun line(): String {
        val fields = info.fields().entries.joinToString(";") { "${it.key}=${it.value}" }
        val body = listOf("1", wallMs, elapsedMs, run, sequence, operationId, operation.name, stage.name,
            outcome?.name ?: "-", error?.name ?: "-", fields).joinToString("\t")
        return body + "\t" + CRC32().apply { update(body.toByteArray(Charsets.UTF_8)) }.value.toString(16) + "\n"
    }

    companion object {
        fun parse(line: String): DiagnosticEvent? = inspect(line).event

        fun inspect(line: String): DiagnosticLineRead {
            fun rejected(reason: DiagnosticReadRejection) = DiagnosticLineRead(rejection = reason)
            if (line.length > 4096) return rejected(DiagnosticReadRejection.OVERSIZED)
            val split = line.trimEnd('\r', '\n').split('\t')
            if (split.size != 12) return rejected(DiagnosticReadRejection.INVALID_FIELDS)
            if (split[0] != "1") return rejected(
                if (split[0].toIntOrNull() != null) DiagnosticReadRejection.UNSUPPORTED_VERSION else DiagnosticReadRejection.INVALID_FIELDS)
            val body = split.take(11).joinToString("\t")
            if (split[11].toLongOrNull(16) != CRC32().apply { update(body.toByteArray(Charsets.UTF_8)) }.value)
                return rejected(DiagnosticReadRejection.CHECKSUM)
            val operation = Operation.entries.firstOrNull { it.name == split[6] }
                ?: return rejected(DiagnosticReadRejection.UNSUPPORTED_ENUM)
            val stage = Stage.entries.firstOrNull { it.name == split[7] }
                ?: return rejected(DiagnosticReadRejection.UNSUPPORTED_ENUM)
            val outcome = if (split[8] == "-") null else Outcome.entries.firstOrNull { it.name == split[8] }
                ?: return rejected(DiagnosticReadRejection.UNSUPPORTED_ENUM)
            val error = if (split[9] == "-") null else ErrorCode.entries.firstOrNull { it.name == split[9] }
                ?: return rejected(DiagnosticReadRejection.UNSUPPORTED_ENUM)
            return runCatching {
                val wall = split[1].toLong(); val elapsed = split[2].toLong()
                val sequence = split[4].toLong(); val id = split[5].toLong()
                if (wall !in 0..253402300799999L || elapsed < 0 || sequence < 0 || id < 0 || !split[3].matches(Regex("[0-9a-f]{32}")))
                    return rejected(DiagnosticReadRejection.INVALID_FIELDS)
                val fields = split[10].split(';').mapNotNull {
                    val at = it.indexOf('='); if (at > 0) it.substring(0, at) to it.substring(at + 1) else null
                }.toMap()
                DiagnosticLineRead(event = DiagnosticEvent(wall, elapsed, split[3], sequence, id, operation, stage,
                    outcome, error, SafeInfo.parse(fields)))
            }.getOrElse { rejected(DiagnosticReadRejection.INVALID_FIELDS) }
        }
    }
}

/** Counts read rejections, not proven lost events; never retains rejected payloads. */
internal enum class DiagnosticReadRejection {
    UNSUPPORTED_VERSION, UNSUPPORTED_ENUM, INVALID_FIELDS, CHECKSUM, INVALID_BYTE, OVERSIZED, INCOMPLETE
}
internal data class DiagnosticLineRead(val event: DiagnosticEvent? = null, val rejection: DiagnosticReadRejection? = null)

internal data class DiagnosticSnapshot(
    val minutes: Int, val snapshotWallMs: Long, val snapshotElapsedMs: Long,
    val events: List<DiagnosticEvent>, val effectiveWallTimes: List<Long>,
    val crossRunCount: Int, val recoveredIncomplete: Boolean, val storageHealthy: Boolean,
    val expiredDiscarded: Long = 0, val capacityDiscarded: Long = 0, val malformedDiscarded: Long = 0, val futureDiscarded: Long = 0,
    val startupReadRejected: Long = 0, val rotationReadRejected: Long = 0,
    val readRejections: Map<DiagnosticReadRejection, Long> = emptyMap(),
) {
    fun text(metadata: String): String = buildString {
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1c751a529433)).append(minutes).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5b3a75c53bbd))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_79aa64a23d25)).append(Instant.ofEpochMilli(snapshotWallMs)).append('\n')
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_02e29a8a9e56)).append(Instant.ofEpochMilli(snapshotWallMs - minutes * 60_000L)).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_73ba3e48606b))
            .append(Instant.ofEpochMilli(snapshotWallMs)).append('\n')
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4b5117d9bd17)).append(events.size).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e52827dd4ad4)).append(crossRunCount).append('\n')
        if (effectiveWallTimes.isNotEmpty()) append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c8768200958b)).append(Instant.ofEpochMilli(effectiveWallTimes.min())).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8727f227e2c6))
            .append(Instant.ofEpochMilli(effectiveWallTimes.max())).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bf7b123c114d))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ccc4edebf400)).append(expiredDiscarded).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1a58fbf44b39)).append(capacityDiscarded)
            .append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4e203ca8ccb7)).append(malformedDiscarded).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_338aa9ce6405)).append(futureDiscarded).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b028650697a5))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.diagnostics_read_rejections_detail, startupReadRejected, rotationReadRejected))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bbbde15a1296))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e78eb8f72096))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0cbe74b0ae0a))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f068c05d35bf))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_de9b9a98a215))
        append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9e46400a4c40))
        if (recoveredIncomplete) append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d93adff8dfc6))
        if (!storageHealthy) append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f51871fbed21))
        if (events.isEmpty()) append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_fd756ca7b128))
        append(metadata).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_42892fd91f62))
        events.forEachIndexed { index, event ->
            append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f3650cb3e121)).append(Instant.ofEpochMilli(effectiveWallTimes[index]))
            append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a6225820802e)).append(Instant.ofEpochMilli(event.wallMs))
            append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_af560d1154f3)).append(event.elapsedMs).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0d863965cf79)).append(event.run)
            append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_da3f4b1e2ad6)).append(event.sequence).append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b3660b2a6a62)).append(event.operationId)
            append(' ').append(event.operation.name).append('/').append(event.stage.name)
            event.outcome?.let { append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e564bb482cfa)).append(it.name) }
            event.error?.let { append(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_815ce3797ff8)).append(it.name) }
            event.info.fields().forEach { (key, value) -> append(' ').append(key).append('=').append(value) }
            append('\n')
        }
    }
}

/**
 * Two bounded append-only segments, typed CRC-protected lines, synchronized
 * snapshots. A torn line is ignored. Compaction uses fsync + atomic replacement.
 * Progress-only lines sync in batches of 16 or after 1 second (subject to thread/OS
 * scheduling); process/power loss can lose that unsynced tail. Lifecycle, error and
 * native/ownership boundaries sync before returning, unless storage fails.
 * Live records <=1MiB, temporary compaction <=1MiB, total log directory <=2MiB.
 */
internal class DiagnosticStore(
    private val directory: File,
    private val clock: DiagnosticClock,
    private val run: String = UUID.randomUUID().toString().replace("-", ""),
    private val maxRecordBytes: Int = 1024 * 1024,
    private val output: (File, Boolean) -> DiagnosticOutput = ::FileDiagnosticOutput,
    private val scheduler: DiagnosticFlushScheduler = SharedDiagnosticFlushScheduler,
) : Closeable {
    companion object {
        const val RETENTION_MS = 24L * 60 * 60 * 1000
        const val MAX_UNSYNCED_EVENTS = 16
        const val MAX_UNSYNCED_MS = 1000L

        // Default durable: new stages must explicitly opt into batching.
        internal fun requiresDurability(stage: Stage, error: ErrorCode?): Boolean = error != null || when (stage) {
            Stage.DECODE_HEADER, Stage.DECODE_STRIPE, Stage.NATIVE_PROGRESS, Stage.ENCODE_PROGRESS -> false
            else -> true
        }
    }
    private val lock = Any()
    private val records = ArrayList<DiagnosticEvent>()
    private var nextSequence = 1L
    private var nextOperation = 1L
    private val operations = LinkedHashMap<Long, Operation>()
    private val segmentBytes = maxRecordBytes / 2
    private var healthy = true
    private var closed = false
    private var dirtyEvents = 0
    private var pendingFlush: Closeable? = null
    private var flushGeneration = 0L
    private var expiredDiscarded = 0L
    private var capacityDiscarded = 0L
    private var malformedDiscarded = 0L
    private var futureDiscarded = 0L
    private var startupReadRejected = 0L
    private var rotationReadRejected = 0L
    private val readRejections = java.util.EnumMap<DiagnosticReadRejection, Long>(DiagnosticReadRejection::class.java)

    init {
        require(maxRecordBytes >= 4096 && run.matches(Regex("[0-9a-f]{32}")))
        synchronized(lock) {
            runCatching {
                check(directory.isDirectory || directory.mkdirs())
                listOf("events.0.new", "events.1.new").forEach { File(directory, it).delete() }
                records += readSegments(startup = true)
                prune()
                // Only the immediately preceding retained run can describe "last
                // operation". An ancient unresolved run must not be re-reported
                // after an intervening clean run.
                val previousRun = records.lastOrNull { it.run != run }?.run
                val open = LinkedHashMap<Pair<String, Long>, Operation>()
                records.forEach { event ->
                    if (event.run != previousRun || event.operation == Operation.SESSION) return@forEach
                    val key = event.run to event.operationId
                    if (event.stage == Stage.END) open.remove(key) else open[key] = event.operation
                }
                val previous = open.entries.lastOrNull { it.key.first != run }?.value
                rewrite()
                append(OperationId(0), Operation.SESSION, Stage.START)
                if (previous != null) {
                    append(OperationId(0), Operation.SESSION, Stage.PREVIOUS_UNFINISHED,
                        info = SafeInfo(previousOperation = previous), error = ErrorCode.PREVIOUS_TERMINATION_UNCERTAIN)
                }
            }.onFailure { healthy = false }
        }
    }

    fun begin(operation: Operation, info: SafeInfo): OperationId = synchronized(lock) {
        if (closed) return@synchronized OperationId(0)
        val id = OperationId(nextOperation++)
        if (operations.size >= 256) operations.remove(operations.keys.first())
        operations[id.value] = operation
        append(id, operation, Stage.START, info)
        id
    }

    fun event(id: OperationId, stage: Stage, info: SafeInfo, error: ErrorCode?) = synchronized(lock) {
        if (closed) return@synchronized
        val operation = operations[id.value] ?: return@synchronized
        // START/END are owned by begin/end, preventing forged lifecycle records.
        if (stage == Stage.START || stage == Stage.END || stage == Stage.PREVIOUS_UNFINISHED) return@synchronized
        append(id, operation, stage, info, error = error)
    }

    fun end(id: OperationId, outcome: Outcome, info: SafeInfo = SafeInfo()) = synchronized(lock) {
        if (closed) return@synchronized
        val operation = operations.remove(id.value) ?: return@synchronized
        append(id, operation, Stage.END, info, outcome = outcome)
    }

    fun snapshot(minutes: Int): DiagnosticSnapshot = synchronized(lock) {
        require(minutes in 1..1440) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0a9cd9640dfe) }
        pruneAndRewriteIfNeeded()
        flushLocked()
        val wall = clock.wallMs(); val elapsed = clock.elapsedMs()
        val cutoff = wall - minutes * 60_000L
        val selected = records.mapNotNull { event ->
            val effective = if (event.run == run) {
                val age = elapsed - event.elapsedMs
                if (age < 0) return@mapNotNull null
                wall - age
            } else event.wallMs
            if (effective < cutoff || effective > wall) null else event to effective
        }
        DiagnosticSnapshot(minutes, wall, elapsed, selected.map { it.first }, selected.map { it.second },
            selected.count { it.first.run != run }, selected.any { it.first.run == run && it.first.stage == Stage.PREVIOUS_UNFINISHED }, healthy,
            expiredDiscarded, capacityDiscarded, malformedDiscarded, futureDiscarded,
            startupReadRejected, rotationReadRejected, readRejections.toMap())
    }

    private fun append(id: OperationId, operation: Operation, stage: Stage, info: SafeInfo = SafeInfo(), outcome: Outcome? = null, error: ErrorCode? = null) {
        val event = DiagnosticEvent(clock.wallMs(), clock.elapsedMs(), run, nextSequence++, id.value, operation, stage, outcome, error, info)
        runCatching {
            pruneAndRewriteIfNeeded()
            val bytes = event.line().toByteArray(Charsets.UTF_8)
            check(bytes.size <= 4096 && bytes.size <= segmentBytes)
            val current = File(directory, "events.0.log")
            val previous = File(directory, "events.1.log")
            if (current.length() + bytes.size > segmentBytes) {
                // Sync the old inode before rename; a later sync of events.0 cannot cover it.
                syncPending()
                if (previous.exists()) check(previous.delete())
                if (current.exists()) check(current.renameTo(previous))
                val previousSize = records.size
                records.clear(); records += readSegments()
                capacityDiscarded += (previousSize - records.size).coerceAtLeast(0)
            }
            output(current, true).use { stream ->
                // Keep line visibility/recovery unchanged. Only stable-storage sync is batched.
                stream.write(bytes)
                stream.flush()
                dirtyEvents++
                if (requiresDurability(stage, error) || dirtyEvents >= MAX_UNSYNCED_EVENTS) {
                    stream.sync()
                    markSynced()
                }
            }
            records += event
            if (dirtyEvents > 0) scheduleFlush()
        }.onFailure { healthy = false }
    }

    /** Best-effort durability boundary; failures remain visible in storageHealthy. */
    fun flush() = synchronized(lock) { flushLocked() }

    override fun close() = synchronized(lock) {
        if (!closed) {
            flushLocked()
            closed = true
            cancelScheduledFlush()
            operations.clear()
        }
    }

    private fun flushLocked() {
        runCatching { syncPending() }.onFailure { healthy = false }
    }

    private fun syncPending() {
        if (dirtyEvents == 0) return
        output(File(directory, "events.0.log"), true).use { stream ->
            stream.flush()
            stream.sync()
        }
        markSynced()
    }

    private fun cancelScheduledFlush() {
        flushGeneration++
        pendingFlush?.close()
        pendingFlush = null
    }

    private fun markSynced() {
        dirtyEvents = 0
        cancelScheduledFlush()
    }

    private fun scheduleFlush() {
        if (pendingFlush != null || closed) return
        val generation = flushGeneration
        try {
            pendingFlush = scheduler.schedule(MAX_UNSYNCED_MS) {
                synchronized(lock) {
                    // A cancelled task can already be waiting for this monitor.
                    if (generation == flushGeneration && !closed) {
                        pendingFlush = null
                        flushLocked()
                    }
                }
            }
        } catch (_: Exception) {
            // Scheduling unavailable: fall back to synchronous durability rather
            // than leaving an idle tail waiting indefinitely for another event.
            healthy = false
            syncPending()
        }
    }

    private fun pruneAndRewriteIfNeeded() { if (prune()) runCatching { rewrite() }.onFailure { healthy = false } }

    private fun prune(): Boolean {
        val wall = clock.wallMs(); val elapsed = clock.elapsedMs()
        return records.removeAll { event ->
            val age = if (event.run == run) elapsed - event.elapsedMs else wall - event.wallMs
            if (age < 0) futureDiscarded++ else if (age > RETENTION_MS) expiredDiscarded++
            age < 0 || age > RETENTION_MS
        }
    }

    private fun readSegments(startup: Boolean = false): List<DiagnosticEvent> {
        fun rejected(reason: DiagnosticReadRejection) {
            malformedDiscarded++ // Legacy aggregate retained for report consumers.
            if (startup) startupReadRejected++ else rotationReadRejected++
            readRejections[reason] = (readRejections[reason] ?: 0L) + 1
        }
        val found = LinkedHashMap<Pair<String, Long>, DiagnosticEvent>()
        for (name in listOf("events.1.log", "events.0.log")) {
            val file = File(directory, name)
            if (!file.isFile) continue
            file.inputStream().buffered().use { input ->
                val line = StringBuilder()
                var discard: DiagnosticReadRejection? = null
                var read = 0
                while (read < segmentBytes) {
                    val value = input.read(); if (value < 0) break
                    read++
                    if (value == 10) {
                        val result = discard?.let { DiagnosticLineRead(rejection = it) } ?: DiagnosticEvent.inspect(line.toString())
                        val parsed = result.event
                        if (parsed == null) rejected(checkNotNull(result.rejection)) else found[parsed.run to parsed.sequence] = parsed
                        line.setLength(0); discard = null
                    } else if (discard == null) {
                        discard = when {
                            line.length >= 4096 -> DiagnosticReadRejection.OVERSIZED
                            value !in 9..126 -> DiagnosticReadRejection.INVALID_BYTE
                            else -> null
                        }
                        if (discard != null) line.setLength(0) else line.append(value.toChar())
                    }
                }
                // Unterminated last line is deliberately ignored.
                if (line.isNotEmpty() || discard != null) rejected(discard ?: DiagnosticReadRejection.INCOMPLETE)
            }
        }
        return found.values.toList()
    }

    private fun rewrite() {
        val newest = ArrayList<DiagnosticEvent>()
        var used = 0
        for (event in records.asReversed()) {
            val size = event.line().toByteArray(Charsets.UTF_8).size
            if (used + size > maxRecordBytes) break
            newest += event; used += size
        }
        newest.reverse()
        val current = ArrayList<DiagnosticEvent>()
        var currentSize = 0
        for (event in newest.asReversed()) {
            val size = event.line().toByteArray(Charsets.UTF_8).size
            if (currentSize + size > segmentBytes) break
            current += event; currentSize += size
        }
        current.reverse()
        val previous = newest.dropLast(current.size).takeLastWhileWithin(segmentBytes)
        writeSegment(1, previous); writeSegment(0, current)
        capacityDiscarded += (records.size - previous.size - current.size).coerceAtLeast(0)
        records.clear(); records += previous; records += current
        markSynced()
    }

    private fun List<DiagnosticEvent>.takeLastWhileWithin(limit: Int): List<DiagnosticEvent> {
        var used = 0
        val kept = ArrayList<DiagnosticEvent>()
        for (event in asReversed()) {
            val size = event.line().toByteArray(Charsets.UTF_8).size
            if (used + size > limit) break
            kept += event; used += size
        }
        kept.reverse(); return kept
    }

    private fun writeSegment(index: Int, events: List<DiagnosticEvent>) {
        val target = File(directory, "events.$index.log")
        val temporary = File(directory, "events.$index.new")
        try {
            output(temporary, false).use { stream ->
                events.forEach { stream.write(it.line().toByteArray(Charsets.UTF_8)) }
                stream.flush(); stream.sync()
            }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
}
