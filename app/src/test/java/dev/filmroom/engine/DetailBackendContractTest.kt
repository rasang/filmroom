package dev.filmroom.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class DetailBackendContractTest {
    private fun packet(gpu:Boolean=true)=JSONObject()
        .put("identity",JSONObject().put("algorithm",if(gpu)DetailBackendContract.GPU_ALGORITHM else DetailBackendContract.CPU_ALGORITHM))
        .put("quality",if(gpu)"ApproximateSameWorkingGrid" else "ExactSameWorkingGrid")
        .put("backend",JSONObject().put("actualBackend",if(gpu)"wgpu" else "cpu")
            .put("requestedGpu",true).put("cpuFallback",!gpu).put("gpuBatches",if(gpu)3 else 0)
            .put("reason",if(gpu)"approximate_resident_detail" else "gpu_unavailable")
            .put("acceleratedStage",if(gpu)"full-resident-detail-chain" else "none"))
    private fun reject(value:JSONObject,requested:Boolean=true) {
        try {DetailBackendContract.validate(value,requested);fail("Malformed or dishonest backend accepted")}
        catch(e:DetailUnavailable){assertEquals("INVALID_RESULT",e.code)}
    }
    @Test fun acceptsExplicitGpuAndCpuFallback() {
        DetailBackendContract.validate(packet(),true);DetailBackendContract.validate(packet(false),true)
        val cpu=packet(false);cpu.getJSONObject("backend").put("requestedGpu",false).put("cpuFallback",false).put("reason","disabled_by_user")
        DetailBackendContract.validate(cpu,false)
    }
    @Test fun requestAndUploadDoNotProveScientificGpuExecution() {
        for(count in listOf<Any>(0,-1,1.5,"3",JSONObject.NULL)) {
            val p=packet();p.getJSONObject("backend").put("gpuBatches",count);reject(p)
        }
        val p=packet();p.getJSONObject("backend").put("acceleratedStage","rgba8-upload");reject(p)
        reject(packet(),false)
    }
    @Test fun rejectsExactGpuClaimsAndCrossBackendAlgorithmIdentity() {
        reject(packet().put("quality","ExactSameWorkingGrid"))
        val p=packet();p.getJSONObject("identity").put("algorithm",DetailBackendContract.CPU_ALGORITHM);reject(p)
        val q=packet(false);q.getJSONObject("backend").put("actualBackend","wgpu");reject(q)
    }
    @Test fun fallbackRequiresReasonAndMatchingFlags() {
        val p=packet(false);p.getJSONObject("backend").remove("reason");reject(p)
        val q=packet(false);q.getJSONObject("backend").put("cpuFallback",false);reject(q)
        val r=packet();r.getJSONObject("backend").put("cpuFallback",true);reject(r)
        val s=packet(false);s.getJSONObject("backend").put("gpuBatches",1);reject(s)
        val t=packet(false);t.getJSONObject("backend").put("reason","/private/source.jpg");reject(t)
    }
}
