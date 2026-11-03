package dev.filmroom.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class SciencePerformanceInfoTest {
    @Test fun adoptedScienceBackendsAndNegativeEvRoundTripWithoutNativeStrings(){
        val science=JSONObject()
        for(stage in ScienceStage.entries)science.put(stage.objectKey,JSONObject().put("requested",true).put("attempted",true).put("resumed",false)
            .put("backend",stage.gpuBackend).put("fallbackReason","none").put("autoExposureEv",-3.5).put("sampleRms",0.002)
            .put("adapter","SECRET").put("sourcePath","/private/photo").put("prepareErrorCode","GPU_PREPARE_COPY_VERIFY_EIO"))
        val info=PerformanceInfo.fromNative(JSONObject().put("metrics",JSONObject().put("gpuScience",science)),PerformanceInfo())
        assertEquals(8,info.science.size)
        val fields=info.typedFields();assertFalse(fields.toString().contains("SECRET"));assertFalse(fields.toString().contains("/private"))
        val restored=PerformanceInfo.fromFields(fields.mapValues{it.value.toString()})!!
        assertEquals(info.science,restored.science)
        assertEquals(-3.5,restored.science.getValue(ScienceStage.AUTO_EXPOSURE).numbers[ScienceNumber.EV]!!,0.0)
        assertFalse(restored.science.getValue(ScienceStage.GRAIN).numbers.containsKey(ScienceNumber.EV))
        assertTrue(ScienceStage.entries.all{restored.science.getValue(it).gpu==true})
    }
    @Test fun unknownBackendsNonfiniteFractionalCountersAndArbitraryCodesDoNotBecomeSafeFacts(){
        val stage=ScienceStage.GRAIN
        val bad=SciencePerformanceInfo.fromNative(stage,JSONObject().put("backend","gpu_everything_PRIVATE").put("fallbackReason","/private")
            .put("requested","true").put("prepareErrorCode","GPU_PREPARE_COPY_VERIFY_/secret").put("dispatches",2.5).put("sampleRms","Infinity"))
        assertNull(bad.gpu);assertNull(bad.fallback);assertNull(bad.requested);assertNull(bad.prepare);assertTrue(bad.numbers.isEmpty())
        assertNull(SciencePerformanceInfo.decode(stage,bad.encode(stage).replace("|-|","|SECRET|")))
        assertNull(SciencePerformanceInfo.decode(stage,"x".repeat(769)))
    }
    @Test fun residentAliasesKeepGpuProvenanceWithoutDuplicatingMeasurement(){
        val scanner=JSONObject().put("backend","gpu_pchip_f32_v1").put("fallbackReason","included_in_resident").put("attempted",true).put("gpuStageMs",12.0).put("uploadBytes",100L)
        val tail=JSONObject().put("backend",ScienceStage.SCAN_TAIL.gpuBackend).put("fallbackReason","included_in_resident").put("gpuStageMs",12.0).put("readbackBytes",100L)
        val fused=JSONObject().put("backend",ScienceStage.FINISH_TAIL.gpuBackend).put("fallbackReason","none").put("gpuStageMs",12.0).put("readbackBytes",100L)
        val info=PerformanceInfo.fromNative(JSONObject().put("metrics",JSONObject().put("gpuScanner",scanner).put("gpuScience",JSONObject().put("scanTail",tail).put("finishTail",fused))),PerformanceInfo())
        assertEquals(ScannerGpuBackend.GPU,info.scannerGpuBackend);assertEquals(FullGpuFallback.INCLUDED_IN_RESIDENT,info.scannerGpuFallback)
        assertFalse(info.numbers.containsKey(PerformanceNumber.GPU_SCAN_STAGE));assertFalse(info.numbers.containsKey(PerformanceNumber.GPU_SCAN_UPLOAD))
        assertTrue(info.science.getValue(ScienceStage.SCAN_TAIL).numbers.isEmpty())
        assertEquals(12.0,info.science.getValue(ScienceStage.FINISH_TAIL).numbers[ScienceNumber.STAGE_MS]!!,0.0)
        assertEquals(info,PerformanceInfo.fromFields(info.typedFields().mapValues{it.value.toString()}))
    }
    @Test fun compactV2AndHistoricalV1RemainReadableWithoutFreeText(){
        val stage=ScienceStage.AUTO_EXPOSURE
        val current=SciencePerformanceInfo(true,FullGpuFallback.NONE,true,true,false,null,mapOf(ScienceNumber.SAMPLES to 3120.0))
        val v2=current.encode(stage);assertTrue(v2.startsWith("2|g|"));assertEquals(current,SciencePerformanceInfo.decode(stage,v2))
        val v1=v2.replaceFirst("2|g|","1|${stage.gpuBackend}|");assertEquals(current,SciencePerformanceInfo.decode(stage,v1))
        assertNull(SciencePerformanceInfo.decode(stage,v2.replaceFirst("2|g|","2|PRIVATE|")))
    }
    @Test fun thirteenStageWorstCaseStaysBelowUnchangedEightKiBEventBoundary(){
        val science=ScienceStage.entries.associateWith{stage->SciencePerformanceInfo(true,FullGpuFallback.UNSUPPORTED_CONFIGURATION,true,true,true,
            GpuPrepareCode.parse("GPU_PREPARE_STAGE_PARENT_CANONICAL_EOPNOTSUPP"),ScienceNumber.entries.filter{it!=ScienceNumber.EV || stage==ScienceStage.AUTO_EXPOSURE}.associateWith{it.max})}
        val performance=PerformanceInfo(PerformanceNumber.entries.associateWith{it.max},PerformanceFlag.entries.associateWith{true},CalibrationStatus.MEASURED,ProbeCaptureStatus.CAPTURED,
            FullGpuBackend.GPU,FullGpuFallback.UNSUPPORTED_CONFIGURATION,ScannerGpuBackend.GPU,FullGpuFallback.UNSUPPORTED_CONFIGURATION,
            OpticalGpuBackend.GPU,FullGpuFallback.UNSUPPORTED_CONFIGURATION,GpuPrepareStage.entries.associateWith{GpuPrepareCode.parse("GPU_PREPARE_STAGE_PARENT_CANONICAL_EOPNOTSUPP")!!},
            ExposureGpuBackend.GPU,FullGpuFallback.UNSUPPORTED_CONFIGURATION,DirGpuBackend.GPU,FullGpuFallback.UNSUPPORTED_CONFIGURATION,science)
        val info=SafeInfo(performance=performance,durationMs=604800000,exportWorkers=4,exportWorkerCap=4,exportParallelChunks=1800000000,exportSerialChunks=1800000000,
            exportInteractiveWaitMs=604800000.0,exportAdmissionWaitMs=604800000.0,exportInteractiveWaits=1800000000,exportInteractiveSerialChunks=1800000000,
            gpuRequested=true,gpuActive=false,gpuBatches=1800000000,gpuFallbacks=1800000000,gpuReason=GpuReason.NO_MEASURED_BENEFIT)
        val line=DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.EXPORT_RENDER,Stage.NATIVE_EXIT,info=info).line()
        assertEquals(8192,MAX_DIAGNOSTIC_EVENT_BYTES)
        assertTrue("Thirteen-stage event is ${line.toByteArray(Charsets.UTF_8).size} bytes",line.toByteArray(Charsets.UTF_8).size<=MAX_DIAGNOSTIC_EVENT_BYTES)
        assertEquals(info.fields(),DiagnosticEvent.parse(line)!!.info.fields())
    }
}
