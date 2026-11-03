package dev.filmroom.engine

import org.junit.Assert.*
import org.junit.Test

class GpuProbeLifetimeTest {
    @Test fun timeoutCancelAndResultNeverReleaseLiveGpuReservation() {
        for (result in listOf(false, true)) {
            val life = GpuProbeLifetime(7)
            assertTrue(life.requestHelper())
            assertTrue(life.acknowledgeStartup(7))
            assertTrue(life.attemptRun(7))
            if (result) assertTrue(life.result(7)) else assertTrue(life.stop(7))
            assertFalse(life.canRelease)
            assertFalse(life.finish())
            assertTrue(life.died(7))
            assertTrue(life.canRelease)
            assertTrue(life.finish())
            assertFalse(life.finish())
        }
    }
    @Test fun preReadyDeathCannotAssertNonStickyProcessTermination() {
        val life = GpuProbeLifetime(7)
        life.requestHelper(); life.stop(7)
        assertFalse(life.died(7))
        assertFalse(life.canRelease)
        assertTrue(life.acknowledgeStartup(7))
        assertTrue(life.died(7))
        assertTrue(life.canRelease)
    }
    @Test fun staleCallbacksAndDuplicateRunsCannotChangeNewSession() {
        val life = GpuProbeLifetime(8)
        assertTrue(life.requestHelper()); assertFalse(life.requestHelper())
        assertFalse(life.acknowledgeStartup(7)); assertFalse(life.result(7)); assertFalse(life.stop(7)); assertFalse(life.died(7))
        assertFalse(life.stopping); assertFalse(life.resultReceived); assertFalse(life.deathConfirmed)
        life.acknowledgeStartup(8); life.attemptRun(8); life.stop(8)
        assertFalse(life.result(8))
        assertTrue(life.died(8)); assertFalse(life.died(8))
    }
    @Test fun failedStartMayReleaseButAcceptedStartWithFailedBindingCannot() {
        val rejected = GpuProbeLifetime(9)
        rejected.requestHelper(); rejected.bindingNotCreated()
        assertTrue(rejected.canRelease)
        val accepted = GpuProbeLifetime(10)
        accepted.requestHelper(); accepted.stop(10)
        assertFalse(accepted.canRelease) // A null/false binding is not Binder death.
    }
    @Test fun permanentNoRunRevocationClosesUnknownStartupWithoutClaimingDeath() {
        val life = GpuProbeLifetime(11)
        life.requestHelper(); life.stop(11)
        assertTrue(life.revokeBeforeRun(11))
        assertTrue(life.canRelease); assertFalse(life.deathConfirmed)
        // Late READY and connection callbacks can never authorize native work.
        life.acknowledgeStartup(11)
        assertFalse(life.attemptRun(11))
        assertTrue(life.finish()); assertFalse(life.attemptRun(11))
    }
    @Test fun cancelDuringReadyAndThrowingRunSendHaveDifferentReleaseProofs() {
        val cancelled = GpuProbeLifetime(12)
        cancelled.requestHelper(); cancelled.acknowledgeStartup(12); cancelled.stop(12)
        assertFalse(cancelled.attemptRun(12)); assertTrue(cancelled.revokeBeforeRun(12))
        val uncertainSend = GpuProbeLifetime(13)
        uncertainSend.requestHelper(); uncertainSend.acknowledgeStartup(13)
        assertTrue(uncertainSend.attemptRun(13)) // BEFORE a send that may throw.
        uncertainSend.stop(13)
        assertFalse(uncertainSend.revokeBeforeRun(13)); assertFalse(uncertainSend.canRelease)
        assertTrue(uncertainSend.died(13)); assertTrue(uncertainSend.canRelease)
    }
    @Test fun eachHelperProcessAcceptsExactlyOnePositiveToken() {
        val gate = GpuProbeProcessGate()
        assertFalse(gate.claim(0)); assertFalse(gate.claim(-1))
        assertTrue(gate.claim(1)); assertTrue(gate.owns(1))
        assertFalse(gate.claim(1)); assertFalse(gate.claim(2)); assertFalse(gate.owns(2))
        assertTrue(GpuProbeProcessGate().claim(2))
    }
    @Test fun processNameGuardMatchesOnlyExactApplicationIdAndSuffix() {
        assertTrue(GpuProbeProcess.isHelper("dev.filmroom.perf", "dev.filmroom.perf:gpu_probe"))
        for (name in listOf(null, "dev.filmroom.perf", "dev.filmroom:gpu_probe", "dev.filmroom.perf:gpu_probe_more", "other:gpu_probe")) {
            assertFalse(GpuProbeProcess.isHelper("dev.filmroom.perf", name))
        }
    }
}
