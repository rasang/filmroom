package dev.filmroom.diagnostics

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption

/**
 * Eight fixed slots, two 32KiB segments per slot. A kernel lock is held for each
 * writer's lifetime; no process ever rotates another live process's journal.
 * Slot reuse retains the immediately preceding bounded history. File contents
 * are always parsed, never copied into reports. Writes and syncs run off main.
 * An acknowledged record survives process death; power-loss durability of
 * directory renames is not promised. Read sizes and lock attempts are bounded;
 * filesystem latency itself has no hard OS bound.
 */
internal class RenderLifecycleJournal(
    private val directory: File,
    private val segmentBytes: Int = SEGMENT_BYTES,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
) : Closeable {
    private val owner: RandomAccessFile
    private val lease: FileLock
    internal val slot: Int
    private var closed = false
    private var ownerRun: String? = null
    private val crumbs = LinkedHashMap<Triple<String, RenderLifecycleScope, Long>, Pair<Int, RenderLifecycleEvent>>()
    private val banks = IntArray(BREADCRUMB_COUNT)

    init {
        require(segmentBytes in 1024..SEGMENT_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) && directory.canonicalFile == directory.absoluteFile)
        val chosen = synchronized(localOwners) {
            var acquired: Triple<Int, RandomAccessFile, FileLock>? = null
            for (index in 0 until SLOT_COUNT) {
                val lockFile = File(directory, "slot-$index.lock")
                if (lockFile.absolutePath in localOwners || Files.isSymbolicLink(lockFile.toPath())) continue
                val handle = RandomAccessFile(lockFile, "rw")
                val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock != null) {
                    acquired = Triple(index, handle, lock)
                    localOwners += lockFile.absolutePath
                    break
                }
                handle.close()
            }
            checkNotNull(acquired)
        }
        slot = chosen.first; owner = chosen.second; lease = chosen.third
        try {
            repairTail(segment(0))
            readBreadcrumbs(breadcrumbFile()).forEach { (index, bank, event) ->
                val key = Triple(event.run, event.scope, event.token)
                val prior = crumbs[key]
                if (prior == null || newer(event, prior.second)) {
                    crumbs[key] = index to event; banks[index] = bank
                }
            }
            // A prior partial append is never joined to a new process's first record.
        } catch (failure: Throwable) {
            lease.release(); owner.close()
            synchronized(localOwners) { localOwners.remove(File(directory, "slot-$slot.lock").absolutePath) }
            throw failure
        }
    }

    @Synchronized fun append(event: RenderLifecycleEvent): Boolean {
        if (closed) return false
        return runCatching {
            if (ownerRun != event.run) {
                require(ownerRun == null) // One journal instance belongs to one process run.
                val marker = File(directory, "slot-$slot.owner")
                require(!Files.isSymbolicLink(marker.toPath()))
                FileOutputStream(marker).use { it.write((event.run + "\n").toByteArray(Charsets.US_ASCII)) }
                ownerRun = event.run
            }
            val bytes = event.line().toByteArray(Charsets.US_ASCII)
            require(bytes.size <= MAX_LIFECYCLE_LINE_BYTES && bytes.size <= segmentBytes)
            val current = segment(0)
            require(!Files.isSymbolicLink(current.toPath()))
            if (current.length() + bytes.size > segmentBytes) {
                val previous = segment(1)
                require(!Files.isSymbolicLink(previous.toPath()))
                if (current.exists()) Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // Fixed per-token alternating breadcrumbs preserve the last meaningful
            // phase even when unrelated tokens rotate both history segments.
            // They are written before the journal fsync. Process termination
            // preserves completed writes; power-loss guarantees are not claimed.
            writeBreadcrumb(event)
            FileOutputStream(current, true).use { stream -> stream.write(bytes); stream.flush(); sync(stream) }
            true
        }.getOrDefault(false)
    }

    private fun breadcrumbFile() = File(directory, "slot-$slot.crumb")
    private fun writeBreadcrumb(event: RenderLifecycleEvent) {
        if (event.token == 0L || event.phase in sampledWaitPhases) return
        val key = Triple(event.run, event.scope, event.token)
        val existing = crumbs[key]
        val index = existing?.first ?: run {
            val used = crumbs.values.map { it.first }.toSet()
            (0 until BREADCRUMB_COUNT).firstOrNull { it !in used } ?: run {
                // Prefer closed/previous-process work before evicting an open token.
                val victim = crumbs.entries.firstOrNull { it.value.second.run != event.run || it.value.second.phase in closedPhases }
                    ?: crumbs.entries.first()
                val freed = victim.value.first; crumbs.remove(victim.key); freed
            }
        }
        val file = breadcrumbFile()
        require(!Files.isSymbolicLink(file.toPath()))
        val bank = 1 - banks[index]
        val bytes = event.line().toByteArray(Charsets.US_ASCII)
        val record = ByteArray(MAX_LIFECYCLE_LINE_BYTES)
        bytes.copyInto(record)
        RandomAccessFile(file, "rw").use { stream ->
            if (stream.length() > BREADCRUMB_BYTES) stream.setLength(BREADCRUMB_BYTES.toLong())
            if (existing == null) {
                // A new token/run must not compete with an older bank having an
                // identical millisecond timestamp after slot reuse.
                stream.seek((index * 2 * MAX_LIFECYCLE_LINE_BYTES).toLong())
                stream.write(ByteArray(MAX_LIFECYCLE_LINE_BYTES * 2))
            }
            stream.seek(((index * 2 + bank) * MAX_LIFECYCLE_LINE_BYTES).toLong())
            stream.write(record)
        }
        banks[index] = bank
        crumbs.remove(key); crumbs[key] = index to event
    }
    private fun segment(index: Int) = File(directory, "slot-$slot.$index.log")
    private fun repairTail(file: File) {
        if (!file.exists()) return
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        RandomAccessFile(file, "rw").use { stream ->
            val length = minOf(stream.length(), segmentBytes.toLong()).toInt()
            val bytes = ByteArray(length); stream.readFully(bytes)
            val complete = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
            if (stream.length() != complete.toLong()) { stream.setLength(complete.toLong()); stream.fd.sync() }
        }
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try { lease.release() } finally {
            owner.close()
            synchronized(localOwners) { localOwners.remove(File(directory, "slot-$slot.lock").absolutePath) }
        }
    }

    companion object {
        const val SLOT_COUNT = 8
        const val SEGMENT_BYTES = 32 * 1024
        const val BREADCRUMB_COUNT = 64
        const val BREADCRUMB_BYTES = BREADCRUMB_COUNT * 2 * MAX_LIFECYCLE_LINE_BYTES
        const val MAX_TOTAL_BYTES = SLOT_COUNT * (2 * SEGMENT_BYTES + BREADCRUMB_BYTES + 33)
        // Never reopen a lock held in this JVM. Closing another fd for a POSIX
        // lock inode can release the process's original lock on some platforms.
        private val localOwners = HashSet<String>()
        private val sampledWaitPhases = setOf(GpuLifecyclePhase.WAIT, GpuLifecyclePhase.PROGRESS_WAIT, GpuLifecyclePhase.HEARTBEAT)
        private val closedPhases = setOf(GpuLifecyclePhase.STAGE_END, GpuLifecyclePhase.OPERATION_END,
            GpuLifecyclePhase.OPERATION_FAILED, GpuLifecyclePhase.OPERATION_CANCELLED, GpuLifecyclePhase.NOT_STARTED, GpuLifecyclePhase.EXPORT_END,
            GpuLifecyclePhase.RESULT_SENT, GpuLifecyclePhase.DRAIN_END)
        private fun newer(a: RenderLifecycleEvent, b: RenderLifecycleEvent): Boolean =
            if (a.run == b.run) a.sequence > b.sequence else a.wallMs > b.wallMs || a.wallMs == b.wallMs && a.uptimeMs > b.uptimeMs
        private fun readBreadcrumbs(file: File): List<Triple<Int, Int, RenderLifecycleEvent>> {
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return emptyList()
            val found = ArrayList<Triple<Int, Int, RenderLifecycleEvent>>()
            RandomAccessFile(file, "r").use { stream ->
                val record = ByteArray(MAX_LIFECYCLE_LINE_BYTES)
                for (index in 0 until BREADCRUMB_COUNT) {
                    var latest: Pair<Int, RenderLifecycleEvent>? = null
                    for (bank in 0..1) {
                        val offset = ((index * 2 + bank) * MAX_LIFECYCLE_LINE_BYTES).toLong()
                        if (stream.length() < offset + MAX_LIFECYCLE_LINE_BYTES) continue
                        stream.seek(offset); stream.readFully(record)
                        val end = record.indexOf('\n'.code.toByte())
                        if (end < 0) continue
                        val event = RenderLifecycleEvent.parse(String(record, 0, end, Charsets.US_ASCII)) ?: continue
                        if (latest == null || newer(event, latest.second)) latest = bank to event
                    }
                    latest?.let { found += Triple(index, it.first, it.second) }
                }
            }
            return found
        }
        const val DIRECTORY = "render-lifecycle"
        /**
         * Context.filesDir can contain a platform-managed parent path alias.
         * Resolve only that trusted root, before appending our untrusted leaf:
         * canonicalizing the full journal path would conceal a leaf symlink.
         * Writers and snapshots must use the same path for local lock identity.
         * Call on the diagnostic IO worker, never on the UI or watchdog thread.
         */
        fun directoryUnder(filesDirectory: File): File = File(filesDirectory.canonicalFile, DIRECTORY)

        fun snapshot(directory: File, minutes: Int, now: Long): RenderLifecycleSnapshot {
            require(minutes in 1..1440)
            val found = LinkedHashMap<Pair<String, Long>, RenderLifecycleEvent>()
            val activeRuns = HashSet<String>()
            var malformed = 0L
            var unavailable = 0
            var historyMayBeTruncated = false
            if (!directory.exists()) return RenderLifecycleSnapshot()
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) || directory.canonicalFile != directory.absoluteFile) {
                return RenderLifecycleSnapshot(storageHealthy = false, unavailableSlots = SLOT_COUNT)
            }
            for (slot in 0 until SLOT_COUNT) {
                val slotEvents = ArrayList<RenderLifecycleEvent>()
                for (segment in listOf(1, 0)) {
                    val file = File(directory, "slot-$slot.$segment.log")
                    if (!file.exists()) continue
                    if (segment == 1) historyMayBeTruncated = true
                    runCatching {
                        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                        file.inputStream().buffered().use { input ->
                            val line = StringBuilder(); var discard = false; var read = 0
                            while (read < SEGMENT_BYTES) {
                                val value = input.read(); if (value < 0) break
                                read++
                                if (value == 10) {
                                    val event = if (discard) null else RenderLifecycleEvent.parse(line.toString())
                                    if (event == null) malformed++ else slotEvents += event
                                    line.setLength(0); discard = false
                                } else if (!discard) {
                                    if (line.length >= MAX_LIFECYCLE_LINE_BYTES || value != 9 && value !in 32..126) {
                                        discard = true; line.setLength(0)
                                    } else line.append(value.toChar())
                                }
                            }
                            if (line.isNotEmpty() || discard) malformed++
                            if (file.length() > SEGMENT_BYTES) malformed++
                        }
                    }.onFailure { unavailable++ }
                }
                runCatching { readBreadcrumbs(File(directory, "slot-$slot.crumb")) }
                    .onSuccess { records -> records.forEach { slotEvents += it.third } }
                    .onFailure { unavailable++ }
                // A newly acquired slot can contain older runs. Only the newest run
                // could be its live owner; historical runs cannot inherit liveness.
                if (writerActive(File(directory, "slot-$slot.lock"))) {
                    val marker = File(directory, "slot-$slot.owner")
                    val ownerRun = runCatching {
                        if (!Files.isRegularFile(marker.toPath(), NOFOLLOW_LINKS) || marker.length() != 33L) null
                        else marker.readText(Charsets.US_ASCII).removeSuffix("\n").takeIf { it.matches(Regex("[0-9a-f]{32}")) }
                    }.getOrNull()
                    // An unreadable owner identity is uncertain, never proof that
                    // a historical operation's process is no longer alive.
                    if (ownerRun != null) activeRuns += ownerRun else activeRuns += slotEvents.map { it.run }
                }
                slotEvents.forEach { found[it.run to it.sequence] = it }
            }
            val selected = found.values.filter { it.wallMs in (now - minutes * 60_000L)..now }
                .sortedWith(compareBy<RenderLifecycleEvent> { it.wallMs }.thenBy { it.uptimeMs }.thenBy { it.run }.thenBy { it.sequence })
            return RenderLifecycleSnapshot(selected, selected.map { it.run }.toSet() - activeRuns,
                malformed, unavailable, unavailable == 0, historyMayBeTruncated = historyMayBeTruncated)
        }
        private fun writerActive(file: File): Boolean = synchronized(localOwners) {
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return@synchronized false
            if (file.absolutePath in localOwners) return@synchronized true
            runCatching {
                RandomAccessFile(file, "rw").use { stream ->
                    val lock = try { stream.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) true else { lock.release(); false }
                }
            }.getOrDefault(true) // Unknown must not be reported as confirmed inactive.
        }
    }
}
