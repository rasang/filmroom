package dev.filmroom.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GpuGrainAluMemoryContractTest {
    private fun plan(mode: GpuGrainRngMode, width: Int = 257, height: Int = 1): GpuFilmGrainPlan {
        val n = width.toLong() * height
        val device = 20L * n + 8 * GpuRenderProtocol.MIB + 65536 + GpuFilmGrainProtocol.scratchBytes(mode)
        val host = 20 * GpuRenderProtocol.MIB + 9600
        val driver = 128 * GpuRenderProtocol.MIB
        val helper = 64 * GpuRenderProtocol.MIB
        val chunk = minOf(n, mode.chunkLimit.toLong()).toInt()
        return GpuFilmGrainPlan(
            token = 1, digest = "a".repeat(64), width = width, height = height,
            passCount = 0, grainActive = true, sublayers = true, layerCount = 3,
            chunkPixels = chunk, chunkCount = (n + chunk - 1) / chunk,
            rngMode = mode, microActive = false, finalBlurActive = false, amountBlendActive = false,
            deviceBytes = device, hostBytes = host, driverAllowanceBytes = driver, helperAllowanceBytes = helper,
            combinedPeakBytes = device + host + driver + helper,
            diskBytes = 48L * n + 2 * GpuRenderProtocol.MIB,
        )
    }

    @Test fun tinyImagesStillReserveAdmissionFramesAndExactCodeStaging() {
        assertEquals(144064L, GpuFilmGrainProtocol.scratchBytes(GpuGrainRngMode.SEQUENTIAL_MT))
        assertEquals(8917696L, GpuFilmGrainProtocol.scratchBytes(GpuGrainRngMode.COORDINATE))
        for (mode in GpuGrainRngMode.entries) {
            val p = plan(mode, 1)
            GpuFilmGrainProtocol.validatePlan(p)
            val scratch = GpuFilmGrainProtocol.scratchBytes(mode)
            assertThrows(IllegalArgumentException::class.java) {
                GpuFilmGrainProtocol.validatePlan(p.copy(deviceBytes = p.deviceBytes - scratch, combinedPeakBytes = p.combinedPeakBytes - scratch))
            }
            assertThrows(IllegalArgumentException::class.java) {
                GpuFilmGrainProtocol.validatePlan(p.copy(hostBytes = p.hostBytes - 9600, combinedPeakBytes = p.combinedPeakBytes - 9600))
            }
        }
    }

    @Test fun combinedCapIncludesScratchForBothModes() {
        for (mode in GpuGrainRngMode.entries) {
            val fixed = plan(mode, 1).combinedPeakBytes - 20L
            val maxPixels = (GpuRenderProtocol.HARD_CAP - fixed) / 20L
            val height = (maxPixels / 40000L).toInt()
            GpuFilmGrainProtocol.validatePlan(plan(mode, 40000, height))
            assertThrows(IllegalArgumentException::class.java) {
                GpuFilmGrainProtocol.validatePlan(plan(mode, 40000, height + 1))
            }
        }
    }

    @Test fun exactDispatchAndSamplerGatesRemainRouteSpecific() {
        val sequential = plan(GpuGrainRngMode.SEQUENTIAL_MT)
        val coordinate = plan(GpuGrainRngMode.COORDINATE)
        assertEquals(182L..182L, GpuFilmGrainProtocol.dispatchRange(sequential))
        assertEquals(86L..86L, GpuFilmGrainProtocol.dispatchRange(coordinate))
        assertEquals(22L, GpuFilmGrainProtocol.numericFlagReads(sequential))
        assertEquals(13L, GpuFilmGrainProtocol.numericFlagReads(coordinate))
        for (p in listOf(sequential, coordinate)) {
            GpuFilmGrainProtocol.validatePlan(p)
            assertEquals(3280L, GpuFilmGrainProtocol.expectedSamplerSamples(p))
        }
    }
}
