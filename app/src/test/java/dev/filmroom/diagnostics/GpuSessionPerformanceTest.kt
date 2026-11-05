package dev.filmroom.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class GpuSessionPerformanceTest {
    @Test fun onlyObservedClosedCountersRoundTrip() {
        val session=JSONObject().put("pipelineCacheHits",8).put("bufferCacheHits",41).put("clearSubmissions",3).put("sessionRecycles",1).put("epoch",991).put("privatePath","/private/source")
        val info=SafeInfo(performance=PerformanceInfo.fromNative(JSONObject().put("gpuSession",session),PerformanceInfo()))
        val fields=info.fields()
        assertEquals("8.0",fields["gpuSessionPipelineCacheHits"])
        assertEquals("41.0",fields["gpuSessionBufferCacheHits"])
        assertEquals("3.0",fields["gpuSessionClearSubmissions"])
        assertEquals("1.0",fields["gpuSessionRecycles"])
        assertEquals(fields,SafeInfo.parse(fields).fields())
        assertFalse(fields.toString().contains("private"));assertFalse(fields.toString().contains("991"))
    }
    @Test fun absentAndMalformedCountersAreNotInvented() {
        val absent=PerformanceInfo.fromNative(JSONObject(),PerformanceInfo()).typedFields()
        assertFalse(absent.keys.any{it.startsWith("gpuSession")})
        val s=JSONObject().put("pipelineCacheHits",-1).put("bufferCacheHits",1.5).put("clearSubmissions","5").put("sessionRecycles",1000001)
        assertTrue(PerformanceInfo.fromNative(JSONObject().put("gpuSession",s),PerformanceInfo()).numbers.isEmpty())
    }
}
