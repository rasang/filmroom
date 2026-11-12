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
    @Test fun failureDetailsRoundTripAndRejectUntrustedValues() {
        val p=packet().put("gpuFailedPhase","scan").put("gpuFailedElapsedMs",29650.0)
            .put("gpuFailureKind","map_timeout").put("gpuSubmitMs",12.0).put("gpuPollMs",29500.0).put("gpuMapWaitMs",29600.0)
        val work=checkNotNull(GpuWorkProgress.fromJson(p))
        assertEquals(GpuFailureKind.MAP_TIMEOUT,work.failureKind)
        assertEquals(work,GpuWorkProgress.fromJson(work.toJson()))
        val info=checkNotNull(GpuRuntimeInfo.workFromProgress(p,1L))
        assertEquals(info,SafeInfo.parse(info.fields()))
        assertEquals("map_timeout",info.fields()["gpuFailureKind"])
        val bad=checkNotNull(GpuWorkProgress.fromJson(p.put("gpuFailureKind","private driver error").put("gpuSubmitMs",-1)))
        assertNull(bad.failureKind);assertNull(bad.submitMs)
        assertFalse(bad.toJson().toString().contains("private"))
    }

    @Test fun geometryAndTimeoutSnapshotSurviveSafeLogAndStorage() {
        val p=packet().put("gpuTilePhase","film_log").put("gpuCoreWidth",58).put("gpuCoreHeight",58)
            .put("gpuExpandedWidth",1240).put("gpuExpandedHeight",1240).put("gpuHalo",591)
            .put("gpuExpandedRatio",401.11).put("gpuPlanBytes",536870912L).put("gpuReservedBytes",13500136L)
            .put("gpuFailedPhase","halation").put("gpuFailedElapsedMs",23560.67)
            .put("gpuMapTimeoutElapsedMs",5001.5).put("gpuMapPollCount",2500L).put("gpuMapMaxPollMs",1.25)
            .put("gpuMapMaxSleepMs",10.0).put("gpuMapCompletedMaps",0).put("gpuMapExpectedMaps",1)
            .put("gpuHalationComponent",3).put("gpuHalationChannel",1).put("gpuHalationRadius",399)
            .put("gpuHalationTileWidth",1240).put("gpuHalationTileHeight",1240)
        val work=checkNotNull(GpuWorkProgress.fromJson(p))
        assertEquals(work,GpuWorkProgress.fromJson(work.toJson()))
        val info=checkNotNull(GpuRuntimeInfo.workFromProgress(p,1L))
        assertEquals(info,SafeInfo.parse(info.fields()))
        assertEquals("film_log",info.fields()["gpuTilePhase"])
        assertEquals("58",info.fields()["gpuTileCoreWidth"])
        assertEquals("5001.5",info.fields()["gpuMapTimeoutElapsedMs"])
        assertEquals("23560.67",info.fields()["gpuFailedElapsedMs"])
        assertEquals("3",info.fields()["gpuHalationComponent"])
        assertNull(info.gpuActive)
        for(phase in listOf("physical_halation_h","physical_halation_v")) {
            assertEquals(phase,checkNotNull(GpuRuntimeInfo.workFromProgress(p.put("gpuTilePhase",phase),1L)).fields()["gpuTilePhase"])
        }
    }
    @Test fun snapshotRejectsStringsAndOutOfRangeValues() {
        val p=packet().put("gpuTilePhase","/private/DCIM/picture.jpg").put("gpuCoreWidth",40001)
            .put("gpuExpandedRatio",-1).put("gpuPlanBytes","536870912").put("gpuReservedBytes",-1)
            .put("gpuMapPollCount",3.5).put("gpuMapTimeoutElapsedMs",-1)
            .put("gpuHalationComponent",4).put("gpuHalationChannel",3).put("gpuHalationRadius",-1)
        val work=checkNotNull(GpuWorkProgress.fromJson(p))
        assertNull(work.tilePhase);assertNull(work.coreWidth);assertNull(work.expandedRatio)
        assertNull(work.planBytes);assertNull(work.reservedBytes);assertNull(work.mapPollCount)
        assertNull(work.mapTimeoutElapsedMs);assertNull(work.halationComponent);assertNull(work.halationChannel)
        assertFalse(work.toJson().toString().contains("private"))
        assertEquals(work,GpuWorkProgress.fromJson(work.toJson()))
    }

}
