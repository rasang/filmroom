package dev.filmroom.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class PreparedCalibrationDiagnosticsTest {
    private fun result(prepared:JSONObject)=JSONObject().put("preparedSpectral",prepared)
    @Test fun frameAndRetainedMetricsRoundTripWithoutInventingGpuWork() {
        val prepared=JSONObject().put("pipelinePrepareMs",12.5).put("frontHits",1).put("frontMisses",0)
            .put("frontBuildNanoseconds",0L).put("calibrationHits",1).put("calibrationMisses",0)
            .put("calibrationBuildNanoseconds",3_000_000_001L)
            .put("cache",JSONObject().put("available",true).put("liveBytes",4096).put("retainedBytes",3072).put("calibrationRetainedBytes",1024))
        val info=GpuRuntimeInfo.fromNativeResult(result(prepared))
        assertEquals(12L,info.preparedPipelinePrepareMs)
        assertEquals(1L,info.preparedFrontHits);assertEquals(0L,info.preparedFrontMisses)
        assertEquals(0L,info.preparedFrontBuildNanoseconds)
        assertEquals(1L,info.preparedCalibrationHits);assertEquals(0L,info.preparedCalibrationMisses)
        assertEquals(3_000_000_001L,info.preparedCalibrationBuildNanoseconds)
        assertEquals(4096L,info.preparedCacheLiveBytes);assertEquals(3072L,info.preparedCacheRetainedBytes)
        assertEquals(1024L,info.preparedCalibrationRetainedBytes)
        assertNull(info.gpuActive);assertNull(info.gpuBatches)
        assertEquals(info,SafeInfo.parse(info.fields()))
    }
    @Test fun cumulativeCacheHitsCannotBecomeFrameHits() {
        val info=GpuRuntimeInfo.fromNativeResult(result(JSONObject().put("cache",JSONObject()
            .put("hits",99).put("calibrationHits",88).put("calibrationPublications",77))))
        assertNull(info.preparedFrontHits);assertNull(info.preparedCalibrationHits)
        assertNull(info.preparedCalibrationBuildNanoseconds);assertNull(info.gpuActive)
    }
    @Test fun missingAndMalformedFieldsRemainUnknown() {
        for(prepared in listOf(JSONObject(),JSONObject().put("pipelinePrepareMs","12").put("frontHits","1")
            .put("frontMisses",-1).put("frontBuildNanoseconds",1.5).put("calibrationHits",true)
            .put("calibrationMisses",1.0).put("calibrationBuildNanoseconds",Long.MAX_VALUE)
            .put("cache",JSONObject().put("available",true).put("liveBytes","1").put("retainedBytes",-1).put("calibrationRetainedBytes",8L*1024*1024+1)))) {
            val info=GpuRuntimeInfo.fromNativeResult(result(prepared))
            assertNull(info.preparedPipelinePrepareMs);assertNull(info.preparedFrontHits);assertNull(info.preparedFrontMisses)
            assertNull(info.preparedFrontBuildNanoseconds);assertNull(info.preparedCalibrationHits)
            assertNull(info.preparedCalibrationMisses);assertNull(info.preparedCalibrationBuildNanoseconds)
            assertNull(info.preparedCacheLiveBytes);assertNull(info.preparedCacheRetainedBytes);assertNull(info.preparedCalibrationRetainedBytes)
            assertFalse(info.fields().keys.any{it.startsWith("prepared")})
        }
        assertNull(GpuRuntimeInfo.fromNativeResult(JSONObject()).preparedCalibrationHits)
    }
    @Test fun exactLimitsAcceptedButOutOfRangeTypedValuesNotPersisted() {
        val fields=mapOf("preparedFrontHits" to "1800000000","preparedCalibrationBuildNanoseconds" to "604800000000000",
            "preparedCacheLiveBytes" to "8388608","preparedPipelinePrepareMs" to "604800000")
        val restored=SafeInfo.parse(fields)
        assertEquals(fields,restored.fields())
        val invalid=SafeInfo(preparedFrontHits=-1,preparedCalibrationBuildNanoseconds=604_800_000_000_001L,
            preparedCacheLiveBytes=8L*1024*1024+1,preparedPipelinePrepareMs=-1)
        assertTrue(invalid.fields().isEmpty())
        assertNull(SafeInfo.parse(mapOf("preparedCalibrationHits" to "-1")).preparedCalibrationHits)
        assertNull(SafeInfo.parse(mapOf("preparedCacheLiveBytes" to "8388609")).preparedCacheLiveBytes)
    }
    @Test fun unavailableOrMissingLedgerDoesNotReportObservedBytes() {
        for(available in listOf<Any?>(false,null,"true")) {
            val cache=JSONObject().put("liveBytes",0).put("retainedBytes",0).put("calibrationRetainedBytes",0)
            if(available!=null)cache.put("available",available)
            val info=GpuRuntimeInfo.fromNativeResult(result(JSONObject().put("cache",cache)))
            assertNull(info.preparedCacheLiveBytes);assertNull(info.preparedCacheRetainedBytes)
            assertNull(info.preparedCalibrationRetainedBytes)
        }
    }
    @Test fun oldFrontOnlyNativeResultDoesNotFabricateCalibrationSupport() {
        val info=GpuRuntimeInfo.fromNativeResult(result(JSONObject().put("frontHits",1).put("frontMisses",0)
            .put("frontBuildNanoseconds",0).put("cache",JSONObject().put("available",true).put("liveBytes",1024).put("retainedBytes",1024))))
        assertEquals(1L,info.preparedFrontHits);assertEquals(1024L,info.preparedCacheLiveBytes)
        assertNull(info.preparedCalibrationHits);assertNull(info.preparedCalibrationRetainedBytes)
    }
}
