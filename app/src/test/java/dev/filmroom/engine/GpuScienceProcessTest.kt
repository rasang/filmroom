package dev.filmroom.engine

import org.junit.Assert.*
import org.junit.Test

class GpuScienceProcessTest {
    @Test fun onlyExactScienceIdentityCanSelfKillAndSkipsApplicationStartup() {
        assertTrue(GpuProbeProcess.isScienceHelper("dev.filmroom", "dev.filmroom:gpu_science"))
        assertTrue(GpuProbeProcess.isWorker("dev.filmroom", "dev.filmroom:gpu_science"))
        assertFalse(GpuProbeProcess.isScienceHelper("dev.filmroom", "dev.filmroom"))
        assertFalse(GpuProbeProcess.isScienceHelper("dev.filmroom", "other:gpu_science"))
        assertFalse(GpuProbeProcess.isScienceHelper("dev.filmroom", "dev.filmroom:gpu_science_extra"))
        assertFalse(GpuProbeProcess.isScienceHelper("dev.filmroom", null))
    }
    @Test fun scienceIpcIsBoundedAndDistinctFromDeathOnlyWireSchema() {
        assertEquals(16 * 1024, GpuScienceIpc.MAX_JSON_BYTES)
        assertEquals(120000L, GpuScienceIpc.HELPER_DEADLINE_MS)
        assertEquals(15000L, GpuScienceIpc.IDLE_TIMEOUT_MS)
        assertTrue(GpuScienceIpc.STAGE_SEALED >= 100)
        val gate = GpuProbeProcessGate()
        assertTrue(gate.claim(7)); assertFalse(gate.claim(7)); assertFalse(gate.claim(8))
    }
}
