package dev.filmroom

import dev.filmroom.engine.*
import dev.filmroom.diagnostics.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class GpuWorkProgressHostTest {
    private fun packet()=JSONObject().put("jobId",2L).put("gpuPhase","grain").put("gpuTileIndex",3)
        .put("gpuTileTotal",20).put("gpuBatchIndex",7).put("gpuBatchElapsedMs",42.5)
        .put("gpuChainWaitMs",120.0).put("gpuCompletedSubmits",61)
    @Test fun actualCompletedWorkSurvivesStorageAndSafeLogRoundTrip() {
        val p=packet();val work=checkNotNull(GpuWorkProgress.fromJson(p))
        assertEquals(work,GpuWorkProgress.fromJson(work.toJson()))
        val info=checkNotNull(GpuRuntimeInfo.workFromProgress(p,1L))
        assertEquals(GpuWorkPhase.GRAIN,info.gpuWorkPhase);assertEquals(3L,info.gpuTileIndex)
        assertEquals(info,SafeInfo.parse(info.fields()));assertNull(info.gpuActive)
        assertNull(GpuRuntimeInfo.workFromProgress(p,2L))
    }
    @Test fun gpuTelemetryDoesNotReplaceTheOriginalPresentation() {
        val work=checkNotNull(GpuWorkProgress.fromJson(packet()))
        val display=ExportDisplayProgress(6,30,100,work,ExportVisualProgress(4,20))
        assertTrue(display.compactText.contains("4/7"))
        assertTrue(display.compactText.contains("20%"))
        assertFalse(display.compactText.contains("3/20"))
        assertFalse(display.compactText.contains("42 ms"))
        assertEquals(6,display.stage);assertEquals(30L,display.done)
        assertEquals(work,display.gpu)
    }
    @Test fun invalidUntrustedPhaseCountersAndTimesNeverBecomeCompletedWork() {
        assertNull(GpuWorkProgress.fromJson(JSONObject().put("gpuPhase","/private/photo")))
        val p=packet().put("gpuPhase","/private/photo").put("gpuTileIndex",21)
        val work=checkNotNull(GpuWorkProgress.fromJson(p.put("gpuFailedPhase","scan").put("gpuFailedElapsedMs",5000.0)))
        assertNull(work.phase);assertNull(work.tileIndex);assertNull(work.tileTotal)
        assertEquals(GpuWorkPhase.SCAN,work.failedPhase)
        assertFalse(work.toJson().toString().contains("private"))
        assertNull(GpuWorkProgress.fromJson(JSONObject().put("gpuPhase","grain").put("gpuBatchIndex",0).put("gpuBatchElapsedMs",10.0)))
    }
}
