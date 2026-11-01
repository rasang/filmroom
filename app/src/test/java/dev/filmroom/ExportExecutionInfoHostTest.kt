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
class ExportExecutionInfoHostTest {
    private fun result(execution: JSONObject) = JSONObject().put("elapsedMs", 125.0).put("metrics",
        JSONObject().put("exportExecution", execution).put("gpu", JSONObject().put("requested",false).put("active",false).put("backend","cpu")))
    @Test fun exactTypedTelemetryAndGpuSurviveRoundTrip() {
        val info=ExportExecutionInfo.fromNativeResult(result(JSONObject().put("poolStatus","private_bounded")
            .put("workerCap",4).put("admissionWaitMs",2.25).put("interactiveWaits",3).put("interactiveSerialChunks",5).put("activePoolWorkers",4).put("parallelParticleChunks",288).put("serialParticleChunks",5).put("interactiveWaitMs",123.5)
            .put("policy","/private/photo.jpg")))
        assertEquals(4L,info.exportWorkerCap);assertEquals(2.25,info.exportAdmissionWaitMs)
        assertEquals(3L,info.exportInteractiveWaits);assertEquals(5L,info.exportInteractiveSerialChunks)
        assertEquals(4L,info.exportWorkers);assertEquals(288L,info.exportParallelChunks)
        assertEquals(5L,info.exportSerialChunks);assertEquals(123.5,info.exportInteractiveWaitMs)
        assertEquals(ExportPoolStatus.PRIVATE_BOUNDED,info.exportPoolStatus)
        assertEquals(false,info.gpuActive);assertEquals(GpuBackend.CPU,info.gpuBackend)
        assertEquals(info.typedFields(),SafeInfo.parse(info.fields()).typedFields())
        assertFalse(info.fields().toString().contains("private/photo"))
    }
    @Test fun malformedValuesAreNotCoercedOrRetained() {
        val info=ExportExecutionInfo.fromNativeResult(result(JSONObject().put("poolStatus","/private/path")
            .put("activePoolWorkers",5).put("parallelParticleChunks","288").put("serialParticleChunks",-1).put("interactiveWaitMs","123")))
        assertNull(info.exportWorkers);assertNull(info.exportParallelChunks);assertNull(info.exportSerialChunks)
        assertNull(info.exportInteractiveWaitMs);assertNull(info.exportPoolStatus)
        assertEquals(false,info.gpuActive)
        val disk=SafeInfo.parse(mapOf("exportWorkers" to "99", "exportInteractiveWaitMs" to "NaN", "exportPoolStatus" to "secret"))
        assertTrue(disk.typedFields().isEmpty())
    }
    @Test fun hugeNegativeAndNonFinitePersistedTelemetryIsFiltered() {
        val info=ExportExecutionInfo.fromNativeResult(result(JSONObject().put("activePoolWorkers",4.0)
            .put("workerCap",-1).put("parallelParticleChunks",Long.MAX_VALUE).put("admissionWaitMs",604_800_001.0)))
        assertNull(info.exportWorkers);assertNull(info.exportWorkerCap);assertNull(info.exportParallelChunks);assertNull(info.exportAdmissionWaitMs)
        for (value in listOf("NaN","Infinity","-Infinity","-1","1e300")) {
            assertTrue(SafeInfo.parse(mapOf("exportAdmissionWaitMs" to value)).typedFields().isEmpty())
        }
    }
    @Test fun missingExecutionNeverInventsParallelUse() {
        val info=ExportExecutionInfo.fromNativeResult(JSONObject().put("gpu",JSONObject().put("active",false)))
        assertNull(info.exportPoolStatus);assertNull(info.exportWorkers);assertEquals(false,info.gpuActive)
    }
}
