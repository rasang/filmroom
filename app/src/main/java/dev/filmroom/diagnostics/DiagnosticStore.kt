package dev.filmroom.diagnostics

import java.io.File
import java.io.FileOutputStream
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
        fun parse(line: String): DiagnosticEvent? = runCatching {
            if (line.length > 4096) return null
            val split = line.trimEnd('\r', '\n').split('\t')
            if (split.size != 12 || split[0] != "1") return null
            val body = split.take(11).joinToString("\t")
            if (split[11].toLongOrNull(16) != CRC32().apply { update(body.toByteArray(Charsets.UTF_8)) }.value) return null
            val wall = split[1].toLong(); val elapsed = split[2].toLong()
            val sequence = split[4].toLong(); val id = split[5].toLong()
            if (wall !in 0..253402300799999L || elapsed < 0 || sequence < 0 || id < 0 || !split[3].matches(Regex("[0-9a-f]{32}"))) return null
            val fields = split[10].split(';').mapNotNull {
                val at = it.indexOf('='); if (at > 0) it.substring(0, at) to it.substring(at + 1) else null
            }.toMap()
            DiagnosticEvent(wall, elapsed, split[3], sequence, id, Operation.valueOf(split[6]), Stage.valueOf(split[7]),
                split[8].takeUnless { it == "-" }?.let(Outcome::valueOf),
                split[9].takeUnless { it == "-" }?.let(ErrorCode::valueOf), SafeInfo.parse(fields))
        }.getOrNull()
    }
}

internal data class DiagnosticSnapshot(
    val minutes: Int, val snapshotWallMs: Long, val snapshotElapsedMs: Long,
    val events: List<DiagnosticEvent>, val effectiveWallTimes: List<Long>,
    val crossRunCount: Int, val recoveredIncomplete: Boolean, val storageHealthy: Boolean,
    val expiredDiscarded: Long = 0, val capacityDiscarded: Long = 0, val malformedDiscarded: Long = 0, val futureDiscarded: Long = 0,
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
 * Live records <=1MiB, temporary compaction <=1MiB, total log directory <=2MiB.
 */
internal class DiagnosticStore(
    private val directory: File,
    private val clock: DiagnosticClock,
    private val run: String = UUID.randomUUID().toString().replace("-", ""),
    private val maxRecordBytes: Int = 1024 * 1024,
) {
    companion object { const val RETENTION_MS = 24L * 60 * 60 * 1000 }
    private val lock = Any()
    private val records = ArrayList<DiagnosticEvent>()
    private var nextSequence = 1L
    private var nextOperation = 1L
    private val operations = LinkedHashMap<Long, Operation>()
    private val segmentBytes = maxRecordBytes / 2
    private var healthy = true
    private var expiredDiscarded = 0L
    private var capacityDiscarded = 0L
    private var malformedDiscarded = 0L
    private var futureDiscarded = 0L

    init {
        require(maxRecordBytes >= 4096 && run.matches(Regex("[0-9a-f]{32}")))
        synchronized(lock) {
            runCatching {
                check(directory.isDirectory || directory.mkdirs())
                listOf("events.0.new", "events.1.new").forEach { File(directory, it).delete() }
                records += readSegments()
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
        val id = OperationId(nextOperation++)
        if (operations.size >= 256) operations.remove(operations.keys.first())
        operations[id.value] = operation
        append(id, operation, Stage.START, info)
        id
    }

    fun event(id: OperationId, stage: Stage, info: SafeInfo, error: ErrorCode?) = synchronized(lock) {
        val operation = operations[id.value] ?: return@synchronized
        // START/END are owned by begin/end, preventing forged lifecycle records.
        if (stage == Stage.START || stage == Stage.END || stage == Stage.PREVIOUS_UNFINISHED) return@synchronized
        append(id, operation, stage, info, error = error)
    }

    fun end(id: OperationId, outcome: Outcome, info: SafeInfo = SafeInfo()) = synchronized(lock) {
        val operation = operations.remove(id.value) ?: return@synchronized
        append(id, operation, Stage.END, info, outcome = outcome)
    }

    fun snapshot(minutes: Int): DiagnosticSnapshot = synchronized(lock) {
        require(minutes in 1..1440) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_0a9cd9640dfe) }
        pruneAndRewriteIfNeeded()
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
            expiredDiscarded, capacityDiscarded, malformedDiscarded, futureDiscarded)
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
                if (previous.exists()) check(previous.delete())
                if (current.exists()) check(current.renameTo(previous))
                val previousSize = records.size
                records.clear(); records += readSegments()
                capacityDiscarded += (previousSize - records.size).coerceAtLeast(0)
            }
            FileOutputStream(current, true).use { stream -> stream.write(bytes); stream.flush(); stream.fd.sync() }
            records += event
        }.onFailure { healthy = false }
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

    private fun readSegments(): List<DiagnosticEvent> {
        val found = LinkedHashMap<Pair<String, Long>, DiagnosticEvent>()
        for (name in listOf("events.1.log", "events.0.log")) {
            val file = File(directory, name)
            if (!file.isFile) continue
            file.inputStream().buffered().use { input ->
                val line = StringBuilder()
                var discard = false
                var read = 0
                while (read < segmentBytes) {
                    val value = input.read(); if (value < 0) break
                    read++
                    if (value == 10) {
                        val parsed = if (discard) null else DiagnosticEvent.parse(line.toString())
                        if (parsed == null) malformedDiscarded++ else found[parsed.run to parsed.sequence] = parsed
                        line.setLength(0); discard = false
                    } else if (!discard) {
                        if (line.length >= 4096 || value !in 9..126) { discard = true; line.setLength(0) }
                        else line.append(value.toChar())
                    }
                }
                // Unterminated last line is deliberately ignored.
                if (line.isNotEmpty() || discard) malformedDiscarded++
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
            FileOutputStream(temporary).use { stream ->
                events.forEach { stream.write(it.line().toByteArray(Charsets.UTF_8)) }
                stream.flush(); stream.fd.sync()
            }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
}
