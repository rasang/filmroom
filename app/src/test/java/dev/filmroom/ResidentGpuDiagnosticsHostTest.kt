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
class ResidentGpuDiagnosticsHostTest {
    private fun packet(stage:String="full-resident-chain")=JSONObject().put("requested",true).put("active",true)
        .put("backend","wgpu").put("acceleratedStage",stage).put("gpuBatches",7).put("cpuFallbacks",0)
        .put("startupSelfTestPassed",false).put("numericGuardPassed",true).put("reason","approximate_resident_chain")
    private fun read(value:JSONObject)=GpuRuntimeInfo.fromNativeResult(JSONObject().put("gpu",value))
    @Test fun actualResidentAndBandedExecutionDoesNotInventCpuEqualitySelfTest() {
        for(stage in listOf("full-resident-chain","banded-chain","tiled-chain")) {
            val info=read(packet(stage));assertEquals(true,info.gpuActive);assertEquals(false,info.gpuSelfTest)
            assertEquals(true,info.gpuNumericGuard);assertEquals(GpuBackend.WGPU_CHAIN,info.gpuBackend)
            assertEquals(info,SafeInfo.parse(info.fields()))
        }
    }
    @Test fun requestedAndBackendWithoutMeasuredExecutionAreNotGpuProof() {
        assertNull(read(packet().put("gpuBatches",0)).gpuActive)
        assertNull(read(packet().put("numericGuardPassed","true")).gpuActive)
        assertNull(read(packet().put("acceleratedStage","unknown-chain")).gpuActive)
        assertNull(read(packet().put("requested",false)).gpuActive)
    }
    @Test fun fallbackAndRestoredCheckpointRemainCpuExecution() {
        for(reason in listOf("gpu_memory_budget_fallback","gpu_binding_limit_fallback","gpu_unavailable_fallback","gpu_execution_fallback","cpu_checkpoint_resume","gpu_checkpoint_restored")) {
            val info=read(packet("banded-chain").put("backend","cpu").put("active",false).put("gpuBatches",0).put("reason",reason))
            assertEquals(false,info.gpuActive);assertEquals(reason,info.gpuReason!!.id)
        }
    }
    @Test fun untrustedReasonAndNonBooleanGuardStayBounded() {
        val info=read(packet().put("reason","/private/photo/secret").put("numericGuardPassed",1))
        assertEquals(GpuReason.UNKNOWN,info.gpuReason);assertNull(info.gpuNumericGuard);assertNull(info.gpuActive)
        assertFalse(info.fields().values.any{it.contains("secret")})
    }
    @Test fun gpuProgressPhasesAndMeasuredTimingsSurviveSafeSerialization() {
        for(id in listOf("gpu_full_chain","gpu_banded_chain","gpu_tiled_chain","gpu_diffusion_prepass")) {
            val phase=checkNotNull(NativePhase.fromId(id))
            val info=SafeInfo(nativePhase=phase,previewPreparationMs=12,previewAllocationMs=3,previewJniWallMs=101)
            assertEquals(info,SafeInfo.parse(info.fields()))
        }
    }
    @Test fun persistentFallbackSurvivesTerminalAndRejectsUntrustedReasons() {
        for(active in listOf(true,false)) {
            val data=JSONObject().put("active",active).put("gpuFallbackReason","gpu_memory_budget_fallback")
            val info=checkNotNull(GpuRuntimeInfo.fallbackFromProgress(data))
            assertEquals(GpuReason.GPU_MEMORY_BUDGET_FALLBACK,info.gpuReason)
            assertEquals(false,info.gpuActive);assertNull(info.gpuBatches)
            assertEquals(info,SafeInfo.parse(info.fields()))
        }
        assertNull(GpuRuntimeInfo.fallbackFromProgress(JSONObject().put("gpuFallbackReason","/private/path")))
        assertNull(GpuRuntimeInfo.fallbackFromProgress(JSONObject().put("gpuFallbackReason",true)))
        assertNull(GpuRuntimeInfo.fallbackFromProgress(JSONObject().put("gpuFallbackReason","approximate_tiled_chain")))
    }

    @Test fun priorWarmHandleFallbackCannotBeAttributedToNewJob() {
        val data=JSONObject().put("jobId",42L).put("gpuFallbackReason","gpu_memory_budget_fallback")
        assertNull(GpuRuntimeInfo.fallbackFromProgress(data,42L))
        assertNotNull(GpuRuntimeInfo.fallbackFromProgress(data.put("jobId",43L),42L))
        assertNull(GpuRuntimeInfo.fallbackFromProgress(data.put("jobId","43"),42L))
        assertNull(GpuRuntimeInfo.fallbackFromProgress(data.put("jobId",43.5),42L))
    }

    @Test fun tileGeometryIsBoundedAndSurvivesExportSerialization() {
        val geometry=JSONObject().put("coreWidth",192).put("coreHeight",203).put("expandedWidth",888).put("expandedHeight",899)
        val result=JSONObject().put("metrics",JSONObject().put("gpu",packet("tiled-chain").put("reason","approximate_tiled_chain")).put("gpuTiles",geometry))
        val info=GpuRuntimeInfo.fromNativeResult(result)
        assertEquals(true,info.gpuActive);assertEquals(GpuReason.APPROXIMATE_TILED_CHAIN,info.gpuReason)
        assertEquals(192L,info.gpuTileCoreWidth);assertEquals(899L,info.gpuTileExpandedHeight)
        assertEquals(info,SafeInfo.parse(info.fields()))
        geometry.put("coreWidth",40001).put("expandedHeight","899")
        val bad=GpuRuntimeInfo.fromNativeResult(result)
        assertNull(bad.gpuTileCoreWidth);assertNull(bad.gpuTileExpandedHeight)
    }

}
