package dev.filmroom.engine

import dev.filmroom.diagnostics.NativePhase
import org.junit.Assert.*
import org.junit.Test

class NativeProgressLogGateTest {
    @Test fun recordsOnlyRealNativeUpdatesAndLimitsHeartbeatRate() {
        val gate=NativeProgressLogGate(10_000)
        val p=EngineProgress(1,3,NativePhase.CAMERA_DIFFUSION,0,72_000_000,updateSerial=1)
        assertTrue(gate.shouldLog(p,1000))
        assertFalse(gate.shouldLog(p,15000))
        assertFalse(gate.shouldLog(p.copy(updateSerial=2),5000))
        assertTrue(gate.shouldLog(p.copy(updateSerial=2),11000))
        assertFalse(gate.shouldLog(p.copy(updateSerial=2),25000))
        assertTrue(gate.shouldLog(p.copy(passId=4),25001))
    }
}
