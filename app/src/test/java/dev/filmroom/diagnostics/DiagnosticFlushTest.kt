package dev.filmroom.diagnostics

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DiagnosticFlushTest {
    private val folders = mutableListOf<File>()
    private val stores = mutableListOf<DiagnosticStore>()
    private class Clock(var now: Long = 1000) : DiagnosticClock {
        override fun wallMs() = 1_800_000_000_000 + now
        override fun elapsedMs() = now
    }
    private class Scheduler : DiagnosticFlushScheduler {
        data class Job(val delay: Long, val task: () -> Unit, var cancelled: Boolean = false)
        val jobs = mutableListOf<Job>()
        override fun schedule(delayMs: Long, task: () -> Unit): Closeable {
            val job = Job(delayMs, task); jobs += job
            return Closeable { job.cancelled = true }
        }
        fun runNext() { jobs.first { !it.cancelled }.also { it.cancelled = true }.task() }
    }
    private class Io {
        var writes = 0; var flushes = 0; var syncs = 0
        var failSync = false
        val actions = mutableListOf<String>()
        fun open(file: File, append: Boolean): DiagnosticOutput {
            val real = FileDiagnosticOutput(file, append)
            return object : DiagnosticOutput {
                override fun write(bytes: ByteArray) { writes++; actions += "write:${file.name}"; real.write(bytes) }
                override fun flush() { flushes++; real.flush() }
                override fun sync() {
                    syncs++; actions += "sync:${file.name}"
                    if (failSync) throw IOException("injected sync failure")
                    real.sync()
                }
                override fun close() = real.close()
            }
        }
        fun reset() { writes = 0; flushes = 0; syncs = 0; actions.clear() }
    }
    private class Fixture(val directory: File, val clock: Clock, val io: Io, val scheduler: Scheduler, val store: DiagnosticStore)
    private fun fixture(capacity: Int = 1024 * 1024): Fixture {
        val directory = Files.createTempDirectory("filmroom-flush-").toFile().also { folders += it }
        val clock = Clock(); val io = Io(); val scheduler = Scheduler()
        val store = DiagnosticStore(directory, clock, "a".repeat(32), capacity, io::open, scheduler).also { stores += it }
        return Fixture(directory, clock, io, scheduler, store)
    }
    @After fun cleanup() { stores.forEach { it.close() }; folders.forEach { it.deleteRecursively() } }
    private fun Fixture.progress(id: OperationId) = store.event(id, Stage.NATIVE_PROGRESS, SafeInfo(), null)

    @Test fun routineWritesRemainVisibleButSyncOnlyAtCountBoundary() {
        val f = fixture(); val id = f.store.begin(Operation.PREVIEW_RENDER, SafeInfo()); f.io.reset()
        repeat(15) { f.progress(id) }
        assertEquals(15, f.io.writes); assertEquals(15, f.io.flushes); assertEquals(0, f.io.syncs)
        assertEquals(17, File(f.directory, "events.0.log").readLines().size)
        f.progress(id)
        assertEquals(16, f.io.writes); assertEquals(16, f.io.flushes); assertEquals(1, f.io.syncs)
        assertTrue(f.scheduler.jobs.all { it.cancelled })
    }
    @Test fun idleTailHasOneNonSlidingTimerAndNoEmptyRepeatedSync() {
        val f = fixture(); val id = f.store.begin(Operation.IMPORT, SafeInfo()); f.io.reset()
        repeat(3) { f.progress(id) }
        assertEquals(1, f.scheduler.jobs.size); assertEquals(1000L, f.scheduler.jobs.single().delay)
        f.scheduler.runNext()
        assertEquals(3, f.io.writes); assertEquals(4, f.io.flushes); assertEquals(1, f.io.syncs)
        f.store.flush(); assertEquals(1, f.io.syncs)
    }
    @Test fun allCriticalStagesAndErrorTaggedProgressSyncBeforeReturn() {
        for (stage in Stage.entries.filter { it !in setOf(Stage.START, Stage.END, Stage.PREVIOUS_UNFINISHED,
                Stage.DECODE_HEADER, Stage.DECODE_STRIPE, Stage.NATIVE_PROGRESS, Stage.ENCODE_PROGRESS) }) {
            val f = fixture(); val id = f.store.begin(Operation.EXPORT_RENDER, SafeInfo()); f.io.reset()
            f.progress(id); f.store.event(id, stage, SafeInfo(), null)
            assertEquals(stage.name, 1, f.io.syncs)
            assertTrue(f.scheduler.jobs.all { it.cancelled })
        }
        val f = fixture(); val id = f.store.begin(Operation.IMPORT, SafeInfo()); f.io.reset()
        f.store.event(id, Stage.DECODE_STRIPE, SafeInfo(), ErrorCode.DECODE_FAILED)
        assertEquals(1, f.io.syncs)
    }
    @Test fun beginEndSnapshotAndCloseAreDurabilityBoundaries() {
        val f = fixture(); f.io.reset()
        val id = f.store.begin(Operation.IMPORT, SafeInfo()); assertEquals(1, f.io.syncs)
        f.progress(id); f.store.snapshot(5); assertEquals(2, f.io.syncs)
        f.progress(id); f.store.end(id, Outcome.CANCELLED); assertEquals(3, f.io.syncs)
        val other = f.store.begin(Operation.PREVIEW_RENDER, SafeInfo())
        f.progress(other); f.store.close(); assertEquals(5, f.io.syncs)
        f.store.close(); assertEquals(5, f.io.syncs)
        val writes = f.io.writes
        f.progress(other); f.store.begin(Operation.IMPORT, SafeInfo()); f.store.end(other, Outcome.SUCCESS)
        assertEquals(writes, f.io.writes)
    }
    @Test fun staleCancelledTimerCannotFlushNewBatch() {
        val f = fixture(); val id = f.store.begin(Operation.IMPORT, SafeInfo()); f.io.reset()
        f.progress(id); val stale = f.scheduler.jobs.last()
        f.store.flush(); f.progress(id)
        stale.task(); assertEquals(1, f.io.syncs)
        f.scheduler.runNext(); assertEquals(2, f.io.syncs)
    }
    @Test fun syncFailureIsStickyAndPendingTailCanRetry() {
        val f = fixture(); val id = f.store.begin(Operation.IMPORT, SafeInfo()); f.io.reset()
        f.progress(id); f.io.failSync = true; f.scheduler.runNext()
        assertEquals(1, f.io.syncs)
        f.io.failSync = false; f.store.flush(); assertEquals(2, f.io.syncs)
        assertFalse(f.store.snapshot(5).storageHealthy)
        assertEquals(3, f.store.snapshot(5).events.size)
    }
    @Test fun criticalSyncFailureDoesNotEscapeLogging() {
        val f = fixture(); val id = f.store.begin(Operation.IMPORT, SafeInfo())
        f.io.failSync = true
        f.store.event(id, Stage.NATIVE_ENTER, SafeInfo(), null)
        f.io.failSync = false
        assertFalse(f.store.snapshot(5).storageHealthy)
    }
    @Test fun rotationSyncsOldInodeBeforeWritingNewSegmentAndRemainsBounded() {
        val f = fixture(4096); val id = f.store.begin(Operation.EXPORT_ENCODE, SafeInfo()); f.io.reset()
        var rotated = false
        repeat(100) {
            val current = File(f.directory, "events.0.log")
            val previous = File(f.directory, "events.1.log")
            val oldLength = current.length(); val oldPrevious = previous.length()
            val from = f.io.actions.size
            f.progress(id)
            if (current.length() < oldLength && oldPrevious == 0L) {
                rotated = true
                assertTrue(f.io.actions.drop(from).first() == "sync:events.0.log")
            }
        }
        assertTrue(rotated)
        val snapshot = f.store.snapshot(5)
        assertTrue(snapshot.capacityDiscarded > 0); assertEquals(0L, snapshot.malformedDiscarded)
        assertTrue(f.directory.listFiles()!!.sumOf { it.length() } <= 4096)
    }
    @Test fun rewriteKeepsBothAtomicSyncsAndCancelsPendingTimer() {
        val f = fixture(); val id = f.store.begin(Operation.IMPORT, SafeInfo()); f.progress(id); f.io.reset()
        f.clock.now += DiagnosticStore.RETENTION_MS + 1
        assertTrue(f.store.snapshot(5).events.isEmpty())
        assertEquals(listOf("sync:events.1.new", "sync:events.0.new"), f.io.actions)
        assertTrue(f.scheduler.jobs.all { it.cancelled })
    }
    @Test fun completeNativeAndEndRecordsSurviveReopenWithTornTailIgnored() {
        val f = fixture(); val id = f.store.begin(Operation.PREVIEW_RENDER, SafeInfo())
        f.store.event(id, Stage.NATIVE_ENTER, SafeInfo(), null)
        f.progress(id); f.store.end(id, Outcome.SUCCESS); f.store.close()
        File(f.directory, "events.0.log").appendText("torn")
        val restarted = DiagnosticStore(f.directory, f.clock, "b".repeat(32)).also { stores += it }
        val snapshot = restarted.snapshot(5)
        assertFalse(snapshot.recoveredIncomplete)
        assertEquals(1L, snapshot.readRejections[DiagnosticReadRejection.INCOMPLETE])
        assertTrue(snapshot.events.any { it.stage == Stage.NATIVE_ENTER })
        assertTrue(snapshot.events.any { it.stage == Stage.END })
    }
    @Test fun concurrentWritersAndTimerFlushesKeepOrderedUniqueRecords() {
        val f = fixture(); val id = f.store.begin(Operation.PREVIEW_RENDER, SafeInfo())
        val pool = Executors.newFixedThreadPool(4)
        try {
            val writers = (0..2).map { pool.submit { repeat(100) { f.progress(id) } } }
            val flush = pool.submit { repeat(100) { f.store.flush() } }
            (writers + flush).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        val snapshot = f.store.snapshot(5)
        assertTrue(snapshot.storageHealthy); assertEquals(302, snapshot.events.size)
        assertTrue(snapshot.events.zipWithNext().all { it.first.sequence < it.second.sequence })
    }
    @Test fun cancelledTimerAfterCloseDoesNotTouchDisk() {
        val f = fixture(); val id = f.store.begin(Operation.IMPORT, SafeInfo())
        f.progress(id); val callback = f.scheduler.jobs.single().task
        f.store.close(); f.io.reset(); callback()
        assertEquals(0, f.io.writes); assertEquals(0, f.io.flushes); assertEquals(0, f.io.syncs)
    }
    @Test fun schedulingFailureFallsBackToImmediateSyncAndPreservesEvent() {
        val f = fixture(); f.store.close()
        val store = DiagnosticStore(f.directory, f.clock, "b".repeat(32), output = f.io::open,
            scheduler = DiagnosticFlushScheduler { _, _ -> throw IllegalStateException("injected scheduling failure") })
        stores += store
        val id = store.begin(Operation.IMPORT, SafeInfo()); f.io.reset()
        store.event(id, Stage.NATIVE_PROGRESS, SafeInfo(), null)
        assertEquals(1, f.io.syncs)
        val snapshot = store.snapshot(5)
        assertEquals(1, f.io.syncs); assertFalse(snapshot.storageHealthy)
        assertEquals(Stage.NATIVE_PROGRESS, snapshot.events.last().stage)
    }
    @Test fun defaultPolicyOnlyDefersTheFourRoutineStages() {
        val routine = setOf(Stage.DECODE_HEADER, Stage.DECODE_STRIPE, Stage.NATIVE_PROGRESS, Stage.ENCODE_PROGRESS)
        Stage.entries.forEach {
            assertEquals(it !in routine, DiagnosticStore.requiresDurability(it, null))
            assertTrue(DiagnosticStore.requiresDurability(it, ErrorCode.UNKNOWN))
        }
    }
}
