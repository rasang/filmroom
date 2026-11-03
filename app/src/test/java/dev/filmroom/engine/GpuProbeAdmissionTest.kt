package dev.filmroom.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class GpuProbeAdmissionTest {
    private val memory = NativeMemorySnapshot(2L shl 30, 0, false)
    @Test fun noRunStartupFailureReleasesExportGateWorkerAndMemoryForNextCpuExport() = runBlocking {
        val exports = ExportWorkAdmission()
        val limiter = NativeWorkLimiter()
        val finished = CompletableDeferred<Unit>()
        val diagnostic = async {
            exports.run {
                val lease = limiter.retainForWork(160 * NativeMemoryPolicy.MIB, memory)
                try {
                    lease.run({ memory }) {
                        val life = GpuProbeLifetime(21)
                        life.requestHelper() // start accepted; bind never delivered.
                        life.stop(21)
                        assertTrue(life.revokeBeforeRun(21))
                        assertTrue(life.canRelease)
                    }
                } finally { lease.close() }
            }
            finished.complete(Unit)
        }
        withTimeout(2000) { finished.await(); diagnostic.await() }
        assertEquals(0, limiter.activeCount()); assertEquals(0L, limiter.reservedBytes())
        assertEquals("CPU export admitted", withTimeout(2000) {
            exports.run { limiter.run(160 * NativeMemoryPolicy.MIB, { memory }) { "CPU export admitted" } }
        })
    }
    @Test fun attemptedGpuRunKeepsFullReservationUntilConfirmedDeath() = runBlocking {
        val limiter = NativeWorkLimiter()
        val lease = limiter.retainForWork(160 * NativeMemoryPolicy.MIB, memory)
        val death = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        val job = async {
            try {
                lease.run({ memory }) {
                    val life = GpuProbeLifetime(22)
                    life.requestHelper(); life.acknowledgeStartup(22); life.attemptRun(22)
                    life.stop(22) // timeout is not termination.
                    assertFalse(life.revokeBeforeRun(22)); assertFalse(life.canRelease)
                    running.complete(Unit)
                    death.await()
                    assertTrue(life.died(22)); assertTrue(life.canRelease)
                }
            } finally { lease.close() }
        }
        withTimeout(2000) { running.await() }
        assertEquals(1, limiter.activeCount())
        assertEquals(176 * NativeMemoryPolicy.MIB, limiter.reservedBytes())
        death.complete(Unit)
        withTimeout(2000) { job.await() }
        assertEquals(0, limiter.activeCount()); assertEquals(0L, limiter.reservedBytes())
    }
}
