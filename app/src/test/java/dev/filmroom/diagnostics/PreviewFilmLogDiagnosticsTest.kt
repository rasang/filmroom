package dev.filmroom.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class PreviewFilmLogDiagnosticsTest {
    @Test fun prefixReuseRetainsActualSuffixGpuEvidenceAndRoundTrips() {
        val cache=JSONObject("""{"previewCache":{"hit":false,"filmLogHit":true,"filmLogRetained":true,"retainedBytes":102400,"filmLogPrefixMs":0.0,"filmLogSuffixMs":12.5}}""")
        val actual=GpuRuntimeInfo.withPreviewCache(cache,SafeInfo(gpuActive=true))
        assertEquals(true,actual.gpuActive)
        assertEquals(false,actual.previewScienceCacheHit)
        assertEquals(true,actual.previewFilmLogCacheHit)
        assertEquals(true,actual.previewFilmLogRetained)
        assertEquals(0L,actual.previewFilmLogPrefixMs)
        assertEquals(12L,actual.previewFilmLogSuffixMs)
        assertEquals(actual,SafeInfo.parse(actual.fields()))
    }
    @Test fun cacheTelemetryCannotInventGpuActivityOrCoerceMalformedFields() {
        val cache=JSONObject("""{"previewCache":{"filmLogHit":"true","filmLogRetained":1,"filmLogPrefixMs":-1,"filmLogSuffixMs":"12"}}""")
        val actual=GpuRuntimeInfo.withPreviewCache(cache,SafeInfo())
        assertNull(actual.gpuActive)
        assertNull(actual.previewFilmLogCacheHit)
        assertNull(actual.previewFilmLogRetained)
        assertNull(actual.previewFilmLogPrefixMs)
        assertNull(actual.previewFilmLogSuffixMs)
    }
    @Test fun correctedDensityEvidenceRoundTripsWithoutInventingGpuActivity() {
        for(hit in listOf(false,true))for(retained in listOf(false,true)) {
            val result=JSONObject().put("previewCache",JSONObject()
                .put("correctedDensityHit",hit).put("correctedDensityRetained",retained))
            val actual=GpuRuntimeInfo.withPreviewCache(result,SafeInfo())
            assertEquals(hit,actual.previewCorrectedDensityCacheHit)
            assertEquals(retained,actual.previewCorrectedDensityRetained)
            assertNull(actual.gpuActive)
            assertEquals(actual,SafeInfo.parse(actual.fields()))
        }
    }
    @Test fun absentOrMalformedDensityEvidenceStaysUnknown() {
        for(cache in listOf(JSONObject(),JSONObject().put("correctedDensityHit","true").put("correctedDensityRetained",1))) {
            val actual=GpuRuntimeInfo.withPreviewCache(JSONObject().put("previewCache",cache),SafeInfo())
            assertNull(actual.previewCorrectedDensityCacheHit)
            assertNull(actual.previewCorrectedDensityRetained)
            assertFalse(actual.fields().containsKey("previewCorrectedDensityCacheHit"))
            assertFalse(actual.fields().containsKey("previewCorrectedDensityRetained"))
        }
        val malformed=SafeInfo.parse(mapOf("previewCorrectedDensityCacheHit" to "1", "previewCorrectedDensityRetained" to "TRUE"))
        assertNull(malformed.previewCorrectedDensityCacheHit)
        assertNull(malformed.previewCorrectedDensityRetained)
    }

}
