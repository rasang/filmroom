package dev.filmroom.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class GpuScienceSessionLifetimeTest {
    private fun ready(): GpuScienceSessionLifetime = GpuScienceSessionLifetime(7).also {
        assertTrue(it.requestHelper()); assertTrue(it.acknowledgeStartup(7)); assertTrue(it.attemptOpen(7)); assertTrue(it.opened(7))
    }
    private fun ticket(generation: Long, sequence: Long, token: Long = sequence) = GpuScienceStageTicket(7, generation, sequence, token, "a".repeat(64))
    private fun frame(life: GpuScienceSessionLifetime, generation: Long) {
        assertTrue(life.attemptBeginFrame(7, generation)); assertTrue(life.frameOpened(7, generation))
    }
    @Test fun stageAndFrameSealsNeverReleaseSessionLeaseAndSequenceNeverResets() {
        val life = ready(); frame(life, 10)
        assertTrue(life.attemptStage(ticket(10, 1))); assertFalse(life.attemptStage(ticket(10, 2)))
        assertFalse(life.stageSealed(ticket(10, 1, 9))); assertTrue(life.stageSealed(ticket(10, 1)))
        assertFalse(life.canReleaseSessionLease()); assertTrue(life.attemptFinishFrame(7, 10))
        assertTrue(life.frameSealed(7, 10, 1)); assertTrue(life.isIdle()); assertFalse(life.canReleaseSessionLease())
        assertFalse(life.attemptBeginFrame(7, 10)); frame(life, 11)
        assertFalse(life.attemptStage(ticket(11, 1))); assertTrue(life.attemptStage(ticket(11, 2)))
        assertFalse(life.stageSealed(ticket(10, 1)))
    }
    @Test fun everyStopCausePermanentlyRetiresEpochAndCannotTurnSealsIntoDeath() {
        for (cause in listOf("cancel", "trim", "idle", "driver", "protocol")) {
            val life = ready(); frame(life, 1); assertTrue(life.attemptStage(ticket(1, 1)))
            assertTrue(cause, life.requestStop(7)); assertFalse(life.stageSealed(ticket(1, 1)))
            assertFalse(life.canReleaseSessionLease()); assertFalse(life.attemptBeginFrame(7, 2))
            assertFalse(life.confirmDead(8)); assertTrue(life.confirmDead(7)); assertTrue(life.canReleaseSessionLease())
            assertTrue(life.finish()); assertFalse(life.finish()); assertFalse(life.attemptOpen(7))
        }
    }
    @Test fun uncertainOpenOrRunCannotClaimDefiniteNoStart() {
        val life = ready(); life.requestStop(7)
        assertFalse(life.confirmStartRejected(7)); assertFalse(life.canReleaseSessionLease())
        val notStarted = GpuScienceSessionLifetime(8)
        assertTrue(notStarted.requestHelper()); assertTrue(notStarted.requestStop(8)); assertTrue(notStarted.confirmStartRejected(8))
        assertTrue(notStarted.canReleaseSessionLease()); assertFalse(notStarted.attemptOpen(8))
    }
    @Test fun leaseStaysChargedAcrossSealsAndQuarantineUntilDeathExactlyOnce() {
        val life = ready(); val closes = AtomicInteger(); val quarantines = AtomicInteger()
        val lease = GpuScienceSessionLease.forTesting(GpuScienceSessionBudget(256 * 1024 * 1024L, 128 * 1024 * 1024L, 8), life, object : RetainedNativeWork {
            override fun close() { closes.incrementAndGet() }
            override fun quarantine() { quarantines.incrementAndGet() }
            override suspend fun <T> run(memory: () -> NativeMemorySnapshot, work: suspend () -> T): T = error("No nested worker")
        })
        frame(life, 1); life.attemptStage(ticket(1, 1)); life.stageSealed(ticket(1, 1)); life.attemptFinishFrame(7, 1); life.frameSealed(7, 1, 1)
        assertTrue(runCatching { lease.releaseAfterStopProof() }.isFailure); assertEquals(0, closes.get())
        life.requestStop(7); lease.quarantine(); assertEquals(1, quarantines.get()); assertEquals(0, closes.get())
        life.confirmDead(7); assertTrue(lease.releaseAfterStopProof()); assertFalse(lease.releaseAfterStopProof()); assertEquals(1, closes.get())
    }
    @Test fun concurrentStageDispatchAndStopCannotReopenOrReleaseALiveSession() {
        repeat(100) {
            val life = ready(); frame(life, 1); val start = CountDownLatch(1)
            val run = Thread { start.await(); life.attemptStage(ticket(1, 1)) }
            val stop = Thread { start.await(); life.requestStop(7) }
            run.start(); stop.start(); start.countDown(); run.join(); stop.join()
            assertTrue(life.isRetiring()); assertFalse(life.canReleaseSessionLease()); assertFalse(life.attemptStage(ticket(1, 2)))
            assertTrue(life.confirmDead(7)); assertTrue(life.canReleaseSessionLease())
        }
    }
    @Test fun enlargedLedgerLimitDoesNotSilentlyRaiseDefaultSessionCap() {
        assertTrue(runCatching { GpuScienceSessionBudget(1024 * 1024 * 1024L, 1, 8).validate() }.isFailure)
        assertTrue(runCatching { GpuScienceSessionBudget(1, 2, 8).validate() }.isFailure)
    }
}
