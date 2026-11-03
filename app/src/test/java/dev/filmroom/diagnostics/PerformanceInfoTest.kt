package dev.filmroom.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class PerformanceInfoTest {
    @Test fun brokenGpuCallbackHasClosedDiagnosticCode(){
        assertEquals(NativeEngineCode.GPU_CALLBACK_UNAVAILABLE,NativeEngineCode.fromId("GPU_CALLBACK_UNAVAILABLE"))
        assertEquals(NativeEngineCode.UNKNOWN,NativeEngineCode.fromId("/private/driver error"))
    }
    @Test fun measuredFieldsSurvivePersistedRoundTrip() {
        val native=JSONObject("""{"previewReuseHit":true,"previewCacheBytes":1024,"metrics":{"checkpointResumeStage":0,"checkpointIdentityMs":3.2,"checkpointCommitMs":4.3,"stages":[{"stage":"camera_diffusion","elapsedMs":26000},{"stage":"grain","elapsedMs":16000}],"gpu":{"resourceReuseBatches":0,"densityCalibration":{"fixturePixels":32768,"cpuMedianMs":1.25,"originalGpuMedianMs":2.5,"originalGatePassed":false,"candidateCpuBitEqual":false}}}}""")
        val info=SafeInfo(performance=PerformanceInfo.fromNative(native,PerformanceInfo.selection(48,true,true,false)))
        val fields=info.fields();assertEquals(fields,SafeInfo.parse(fields).fields())
        assertEquals("48.0",fields["optimizationMask"]);assertEquals("26000.0",fields["timing_camera_diffusion"])
        assertEquals("true",fields["cpuComparison"]);assertEquals("false",fields["gpuCalOriginalPassed"])
        assertFalse(fields.containsKey("gpuCalCandidateMedianMs"))
    }
    @Test fun malformedPrivateAndUnknownValuesNeverEscape() {
        val native=JSONObject("""{"previewCacheBytes":99999999999,"previewReuseHit":"/private/secret","path":"/private/photo.jpg","metrics":{"checkpointResumeStage":-1,"stages":[{"stage":"/private/photo.jpg","elapsedMs":42},{"stage":"grain","elapsedMs":-1}],"gpu":{"densityCalibration":{"cpuMedianMs":"1.0","originalGpuMedianMs":-2,"candidateStatus":"/private/path"}}}}""")
        val fields=SafeInfo(performance=PerformanceInfo.fromNative(native,PerformanceInfo.selection(0,false,false,false))).fields()
        assertEquals(4,fields.size);assertFalse(fields.toString().contains("private"))
        val parsed=SafeInfo.parse(mapOf("optimizationMask" to "1.5","timing_grain" to "NaN","gpuCalCpuMedianMs" to "Infinity","previewReuseHit" to "yes","arbitrary" to "/secret"))
        assertTrue(parsed.fields().isEmpty())
    }
    @Test fun absentMetricsRemainUnknownNotFalseOrZero() {
        val fields=SafeInfo(performance=PerformanceInfo.fromNative(JSONObject(),PerformanceInfo.selection(16,false,false,true))).typedFields()
        assertEquals(4,fields.size);assertTrue(fields["cpuComparison"] is Boolean);assertTrue(fields["optimizationMask"] is Number)
        assertFalse(fields.containsKey("checkpointResumeStage"));assertFalse(fields.containsKey("previewReuseHit"))
    }
    @Test fun fullGpuBackendAndMeasuredTimingsSurviveWithoutPrivateStrings(){
        val native=JSONObject("""{"metrics":{"gpuDiffusion":{"requested":true,"backend":"gpu_f32_v1","attempted":true,"fallbackReason":"none","resumed":false,"helperTotalMs":2345,"gpuStageMs":2200,"setupMs":35,"tiles":12,"fftSide":4096,"combinedPeakBytes":590000000,"jobDir":"/private/image","adapter":"private-driver-text"}}}""")
        val info=SafeInfo(performance=PerformanceInfo.fromNative(native,PerformanceInfo.selection(127,false,false,false)))
        val fields=info.fields();assertEquals(fields,SafeInfo.parse(fields).fields())
        assertEquals("gpu_f32_v1",fields["gpuDiffusionBackend"]);assertEquals("2200.0",fields["gpuDiffusionStageMs"])
        assertFalse(fields.toString().contains("private"))
        val bad=JSONObject("""{"metrics":{"gpuDiffusion":{"backend":"/private/photo","fallbackReason":"driver secret","gpuStageMs":-1,"tiles":999,"resumed":"yes"}}}""")
        val rejected=SafeInfo(performance=PerformanceInfo.fromNative(bad,PerformanceInfo.selection(0,false,false,false))).fields()
        assertEquals(4,rejected.size)
    }
    @Test fun nativeClosedFallbackReasonsSurviveAllStageRoundTrips() {
        for (reason in listOf("inactive", "unsupported_model", "unsupported_configuration")) {
            val metrics=JSONObject()
            for (stage in listOf("gpuDiffusion", "gpuScanner", "gpuOptical", "gpuExposure", "gpuDir")) {
                metrics.put(stage, JSONObject().put("backend", "cpu_f64").put("fallbackReason", reason))
            }
            val fields=SafeInfo(performance=PerformanceInfo.fromNative(JSONObject().put("metrics",metrics),PerformanceInfo.selection(0,false,false,false))).fields()
            for (stage in listOf("gpuDiffusion", "gpuScanner", "gpuOptical", "gpuExposure", "gpuDir")) assertEquals(reason,fields[stage+"Fallback"])
            assertEquals(fields,SafeInfo.parse(fields).fields())
        }
    }
    @Test fun maximumTelemetrySurvivesDiskAndShareJson() {
        val performance=PerformanceInfo(PerformanceNumber.entries.associateWith{it.max},PerformanceFlag.entries.associateWith{true},CalibrationStatus.MEASURED,ProbeCaptureStatus.CAPTURED,FullGpuBackend.GPU,FullGpuFallback.DELEGATE_UNAVAILABLE,
            scannerGpuBackend=ScannerGpuBackend.GPU,scannerGpuFallback=FullGpuFallback.UNSUPPORTED_CONFIGURATION,
            opticalGpuBackend=OpticalGpuBackend.GPU,opticalGpuFallback=FullGpuFallback.UNSUPPORTED_CONFIGURATION,
            exposureGpuBackend=ExposureGpuBackend.GPU,exposureGpuFallback=FullGpuFallback.UNSUPPORTED_CONFIGURATION,
            dirGpuBackend=DirGpuBackend.GPU,dirGpuFallback=FullGpuFallback.UNSUPPORTED_CONFIGURATION,
            prepareErrors=GpuPrepareStage.entries.associateWith{GpuPrepareCode.parse("GPU_PREPARE_STAGE_PARENT_CANONICAL_EOPNOTSUPP")!!})
        val info=SafeInfo(performance=performance,durationMs=604800000,exportWorkers=4,exportWorkerCap=4,exportParallelChunks=1800000000,exportSerialChunks=1800000000,exportInteractiveWaitMs=604800000.0,exportAdmissionWaitMs=604800000.0,exportInteractiveWaits=1800000000,exportInteractiveSerialChunks=1800000000,gpuRequested=true,gpuActive=false,gpuBatches=1800000000,gpuFallbacks=1800000000,gpuReason=GpuReason.NO_MEASURED_BENEFIT)
        val event=DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.EXPORT_RENDER,Stage.NATIVE_EXIT,info=info)
        val line=event.line();assertTrue("Event is ${line.length} characters",line.length<MAX_DIAGNOSTIC_EVENT_BYTES)
        val restored=DiagnosticEvent.parse(line)!!;assertEquals(info.fields(),restored.info.fields())
        assertTrue("Five-stage payload must exercise the old cap",line.length>4096)
        assertNull(DiagnosticEvent.parse("x".repeat(MAX_DIAGNOSTIC_EVENT_BYTES+1)))
        val directory=java.nio.file.Files.createTempDirectory("five-stage-telemetry-").toFile()
        try {
            val clock=object:DiagnosticClock {override fun wallMs()=1000L;override fun elapsedMs()=10L}
            val store=DiagnosticStore(directory,clock,"a".repeat(32))
            val id=store.begin(Operation.EXPORT_RENDER,SafeInfo())
            store.event(id,Stage.NATIVE_EXIT,info,null)
            store.end(id,Outcome.SUCCESS)
            assertTrue(store.snapshot(5).storageHealthy)
            val reopened=DiagnosticStore(directory,clock,"b".repeat(32)).snapshot(5)
            assertTrue(reopened.storageHealthy)
            assertEquals(info.fields(),reopened.events.single{it.stage==Stage.NATIVE_EXIT}.info.fields())
            assertTrue(directory.walkTopDown().filter{it.isFile}.sumOf{it.length()}<=2L*1024*1024)
        } finally {directory.deleteRecursively()}

        val json=ChineseDiagnostics.json(DiagnosticSnapshot(5,1000,10,listOf(restored),listOf(1000L),0,false,true),DiagnosticMetadata())
        val fields=JSONObject(json).getJSONArray("events").getJSONObject(0).getJSONObject("info")
        assertEquals(127.0,fields.getDouble("optimizationMask"),0.0)
        assertEquals("measured_diagnostic_only",fields.getString("gpuCalCandidateStatus"))
    }

}
