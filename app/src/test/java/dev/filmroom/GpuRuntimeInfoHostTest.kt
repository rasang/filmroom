package dev.filmroom

import dev.filmroom.diagnostics.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class GpuRuntimeInfoHostTest {
    private fun gpu()=JSONObject().put("requested",true).put("active",true)
        .put("backend","vulkan-adreno-partial").put("acceleratedStage","density_curve_interp")
        .put("adapter","Adreno (TM) 830").put("gpuBatches",6).put("cpuFallbacks",1)
        .put("startupSelfTestPassed",true)
        .put("reason","Runtime device, numeric self-check and measured cost gate passed; partial density acceleration")
    @Test fun previewAndExportEnvelopesReadActualRuntimeProof() {
        val preview=GpuRuntimeInfo.fromNativeResult(JSONObject().put("elapsedMs",12.75).put("gpu",gpu()))
        val export=GpuRuntimeInfo.fromNativeResult(JSONObject().put("elapsedMs",12.75).put("metrics",JSONObject().put("gpu",gpu())))
        assertEquals(preview,export)
        assertEquals(true,preview.gpuRequested);assertEquals(true,preview.gpuActive)
        assertEquals(GpuBackend.VULKAN_ADRENO_PARTIAL,preview.gpuBackend)
        assertEquals(GpuStage.DENSITY_CURVE_INTERP,preview.gpuStage)
        assertEquals(830,preview.gpuAdapterModel)
        assertEquals(6L,preview.gpuBatches);assertEquals(1L,preview.gpuFallbacks)
        assertEquals(12L,preview.durationMs)
        assertEquals(GpuReason.ACTIVE_PARTIAL,preview.gpuReason)
    }
    @Test fun requestedGpuAndEligibleAdapterDoNotImplyAcceleration() {
        val value=gpu().put("active",false).put("gpuBatches",0).put("backend","cpu")
            .put("reason","Eligible GPU, but no accelerated batches used in this operation")
        val result=GpuRuntimeInfo.fromNativeResult(JSONObject().put("gpu",value))
        assertEquals(true,result.gpuRequested);assertEquals(false,result.gpuActive)
        assertEquals(GpuBackend.CPU,result.gpuBackend);assertEquals(GpuReason.ELIGIBLE_UNUSED,result.gpuReason)
        value.put("active",true)
        assertNull(GpuRuntimeInfo.fromNativeResult(JSONObject().put("gpu",value)).gpuActive)
    }
    @Test fun unavailableOrCpuFallbackCanBeLoggedWithoutDriverText() {
        val value=gpu().put("active",false).put("gpuBatches",0).put("backend","cpu")
            .put("reason","GPU numerical check failed; CPU fallback")
        val result=GpuRuntimeInfo.fromNativeResult(JSONObject().put("metrics",JSONObject().put("gpu",value)))
        assertEquals(false,result.gpuActive);assertEquals(GpuReason.NUMERIC_FAILURE,result.gpuReason)
        value.put("reason","No measured acceleration benefit; CPU fallback (CPU 5.432 ms, GPU 6.789 ms including transfers)")
        assertEquals(GpuReason.NO_MEASURED_BENEFIT,GpuRuntimeInfo.fromNativeResult(JSONObject().put("gpu",value)).gpuReason)
    }
    @Test fun hostileNativeStringsNeverEnterSafeFields() {
        val hostile="/private/DCIM/user@example.com/content:secret"
        val value=gpu().put("adapter","Adreno (TM) 830 $hostile").put("reason",hostile)
            .put("backend",hostile).put("acceleratedStage",hostile)
        val result=GpuRuntimeInfo.fromNativeResult(JSONObject().put("gpu",value))
        assertNull(result.gpuAdapterModel);assertNull(result.gpuBackend);assertNull(result.gpuStage)
        assertNull(result.gpuActive);assertEquals(GpuReason.UNKNOWN,result.gpuReason)
        assertFalse(result.fields().toString().contains(hostile))
        assertFalse(result.fields().toString().contains("Adreno"))
    }
    @Test fun malformedTypesAndOutOfBoundsCountersAreOmitted() {
        val value=gpu().put("requested","true").put("active","true").put("startupSelfTestPassed","true")
            .put("gpuBatches",1.5).put("cpuFallbacks",Long.MAX_VALUE).put("adapter","Adreno 99999")
        val result=GpuRuntimeInfo.fromNativeResult(JSONObject().put("elapsedMs",-1).put("gpu",value))
        assertNull(result.gpuRequested);assertNull(result.gpuActive);assertNull(result.gpuSelfTest)
        assertNull(result.gpuBatches);assertNull(result.gpuFallbacks);assertNull(result.gpuAdapterModel);assertNull(result.durationMs)
    }
    @Test fun missingTelemetryRemainsUnknownEvenWhenTopLevelBackendClaimsGpu() {
        val result=GpuRuntimeInfo.fromNativeResult(JSONObject().put("elapsedMs",3).put("backend","vulkan-adreno"))
        assertEquals(3L,result.durationMs);assertNull(result.gpuActive);assertNull(result.gpuBackend)
        assertEquals(mapOf("durationMs" to "3"),result.fields())
    }
    @Test fun meteredExposureIsBoundedNumericAndSupportsBothNativeEnvelopes() {
        val preview=GpuRuntimeInfo.fromNativeResult(JSONObject().put("meteredEv",0.22497).put("gpu",gpu()))
        val export=GpuRuntimeInfo.fromNativeResult(JSONObject().put("metrics",JSONObject().put("meteredEv",0.24630)))
        assertEquals(0.22497,preview.meteredEv!!,0.0);assertEquals(0.24630,export.meteredEv!!,0.0)
        assertTrue(preview.typedFields()["meteredEv"] is Double)
        assertEquals(preview.meteredEv,SafeInfo.parse(preview.fields()).meteredEv)
        for(value in listOf(JSONObject.NULL,"0.3",true,65.0,-65.0,"/private/photo")) {
            assertNull(GpuRuntimeInfo.fromNativeResult(JSONObject().put("meteredEv",value)).meteredEv)
        }
        assertFalse(SafeInfo(meteredEv=Double.NaN).fields().containsKey("meteredEv"))
        assertFalse(SafeInfo(meteredEv=Double.POSITIVE_INFINITY).fields().containsKey("meteredEv"))
        assertNull(GpuRuntimeInfo.fromNativeResult(JSONObject()).meteredEv)
    }
}
