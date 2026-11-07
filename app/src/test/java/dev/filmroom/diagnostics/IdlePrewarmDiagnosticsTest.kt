package dev.filmroom.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class IdlePrewarmDiagnosticsTest {
    @Test fun nativePrewarmCountsRoundTripThroughTypedPhoneDiagnostics(){
        val cache=JSONObject().put("prewarmAttempts",4).put("prewarmPublished",2).put("prewarmUsed",1).put("prewarmWasted",1).put("prewarmSkipped",1).put("prewarmCancelled",1)
        val result=JSONObject().put("preparedSpectral",JSONObject().put("cache",cache))
        val info=GpuRuntimeInfo.fromNativeResult(result);val restored=SafeInfo.parse(info.fields())
        assertEquals(4L,restored.prewarmNativeAttempts);assertEquals(2L,restored.prewarmPublished)
        assertEquals(1L,restored.prewarmUsed);assertEquals(1L,restored.prewarmWasted)
        assertEquals(1L,restored.prewarmNativeSkipped);assertEquals(1L,restored.prewarmCancelled)
    }
    @Test fun invalidOrMissingCountersNeverBecomeFabricatedHits(){
        val cache=JSONObject().put("prewarmUsed","7").put("prewarmWasted",-1).put("prewarmPublished",Long.MAX_VALUE)
        val info=GpuRuntimeInfo.fromNativeResult(JSONObject().put("preparedSpectral",JSONObject().put("cache",cache)))
        assertNull(info.prewarmUsed);assertNull(info.prewarmWasted);assertNull(info.prewarmPublished)
        assertNull(GpuRuntimeInfo.fromNativeResult(JSONObject()).prewarmUsed)
    }
}
