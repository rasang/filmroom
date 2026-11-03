package dev.filmroom.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class OptimizationPreferencesTest {
    @Test fun snapshotIsImmutableAndMalformedSettingsFailClosed(){
        val app=RuntimeEnvironment.getApplication()
        val store=app.getSharedPreferences("exact-optimization-test",0)
        store.edit().clear().commit()
        val preferences=OptimizationPreferences(app)
        assertEquals(OptimizationSnapshot(),preferences.snapshot())
        preferences.save(OptimizationSnapshot(5,true))
        val captured=preferences.snapshot()
        preferences.save(OptimizationSnapshot(10,false))
        assertEquals(OptimizationSnapshot(5,true),captured)
        assertEquals(OptimizationSnapshot(10,false),preferences.snapshot())
        store.edit().putString("exportMask","15").putString("cpuComparison","true").commit()
        assertEquals(OptimizationSnapshot(),preferences.snapshot())
        store.edit().putInt("exportMask",128).commit()
        assertEquals(0,preferences.snapshot().exportMask)
        store.edit().clear().commit()
    }
    @Test fun fullGpuIsExplicitPreviewAndExportAndCpuReferenceOverridesIt(){
        val selected=OptimizationSnapshot(gpuDiffusionExperimental=true)
        assertFalse(OptimizationSnapshot().experimentalDiffusionEnabled(true,"export"))
        assertTrue(selected.experimentalDiffusionEnabled(true,"export"))
        assertFalse(selected.experimentalDiffusionEnabled(false,"export"))
        assertTrue(selected.experimentalDiffusionEnabled(true,"preview"))
        assertFalse(selected.experimentalDiffusionEnabled(true,"export",true))
        assertFalse(selected.copy(cpuComparison=true).experimentalDiffusionEnabled(true,"export"))
        val app=RuntimeEnvironment.getApplication();val store=app.getSharedPreferences("exact-optimization-test",0)
        store.edit().clear().commit()
        val preferences=OptimizationPreferences(app);preferences.save(selected.withGpuRendering(true))
        assertEquals(selected.withGpuRendering(true),preferences.snapshot())
        store.edit().remove("gpuRenderingEnabled").putString("gpuDiffusionExperimental","true").commit()
        assertFalse(preferences.snapshot().gpuDiffusionExperimental)
        store.edit().clear().commit()
    }
    @Test fun scannerExperimentIsIndependentExplicitPreviewAndExportAndFailsClosed(){
        val selected=OptimizationSnapshot(gpuScannerExperimental=true)
        assertFalse(OptimizationSnapshot().experimentalScannerEnabled(true,"export"))
        assertTrue(selected.experimentalScannerEnabled(true,"export"))
        assertFalse(selected.experimentalDiffusionEnabled(true,"export"))
        assertFalse(selected.experimentalScannerEnabled(false,"export"))
        assertTrue(selected.experimentalScannerEnabled(true,"preview"))
        assertFalse(selected.experimentalScannerEnabled(true,"export",true))
        assertFalse(selected.copy(cpuComparison=true).experimentalScannerEnabled(true,"export"))
        val app=RuntimeEnvironment.getApplication();val store=app.getSharedPreferences("exact-optimization-test",0)
        store.edit().clear().commit()
        val preferences=OptimizationPreferences(app)
        preferences.save(selected.withGpuRendering(true))
        val captured=preferences.snapshot()
        preferences.save(OptimizationSnapshot())
        assertTrue(captured.gpuDiffusionExperimental);assertTrue(captured.gpuScannerExperimental)
        assertEquals(OptimizationSnapshot(),preferences.snapshot())
        store.edit().remove("gpuRenderingEnabled").putString("gpuScannerExperimental","true").commit()
        assertFalse(preferences.snapshot().gpuScannerExperimental)
        store.edit().clear().commit()
    }
    @Test fun sciencePreferencesAreExplicitFailClosedAndPreservePhotoParameters(){
        val selected=OptimizationSnapshot(gpuScienceExperimental=true)
        assertTrue(selected.experimentalScienceEnabled(true,"export"))
        assertFalse(OptimizationSnapshot().experimentalScienceEnabled(true,"export"))
        assertFalse(selected.experimentalScienceEnabled(false,"export"))
        assertTrue(selected.experimentalScienceEnabled(true,"preview"))
        assertFalse(selected.experimentalScienceEnabled(true,"export",true))
        assertFalse(selected.copy(cpuComparison=true).experimentalScienceEnabled(true,"export"))
        val p=JSONObject().put("seed",42).put("grain",0.8).put("width",6000).put("height",4000)
        applyGpuSciencePolicy(p,true)
        assertTrue(p.getBoolean("gpuScienceExperimental"));assertFalse(p.getBoolean("useGpu"));assertFalse(p.getBoolean("gpuResourceReuse"))
        assertEquals(42,p.getInt("seed"));assertEquals(0.8,p.getDouble("grain"),0.0);assertEquals(6000,p.getInt("width"));assertEquals(4000,p.getInt("height"))
        applyGpuSciencePolicy(p,false);assertFalse(p.has("gpuScienceExperimental"))
        val app=RuntimeEnvironment.getApplication();val store=app.getSharedPreferences("exact-optimization-test",0)
        store.edit().clear().commit();val preferences=OptimizationPreferences(app);preferences.save(selected.withGpuRendering(true));assertEquals(selected.withGpuRendering(true),preferences.snapshot())
        store.edit().remove("gpuRenderingEnabled").putString("gpuScienceExperimental","true").commit();assertFalse(preferences.snapshot().gpuScienceExperimental);store.edit().clear().commit()
    }
    @Test fun scientificPreflightCannotOverwriteResumedGpuOrUnknownProvenance(){
        val result=JSONObject("""{"metrics":{"checkpointResumeStage":4,"gpuScience":{"grain":{"backend":"gpu_grain_guarded64_spatial_f32_v2","resumed":true},"scanTail":{"backend":"cpu_f64"}}}}""")
        annotateGpuSciencePreflightFallback(result)
        val science=result.getJSONObject("metrics").getJSONObject("gpuScience")
        assertEquals("gpu_grain_guarded64_spatial_f32_v2",science.getJSONObject("grain").getString("backend"))
        assertFalse(science.has("autoExposure"));assertEquals("preflight_memory",science.getJSONObject("scanTail").getString("fallbackReason"))
        val unknown=JSONObject("""{"metrics":{}}""");annotateGpuSciencePreflightFallback(unknown);assertFalse(unknown.getJSONObject("metrics").has("gpuScience"))
    }
    @Test fun experimentalCameraStageDoesNotEnableOtherGpuAlgorithms(){
        val params=JSONObject("""{"seed":42,"width":4096,"height":3072,"useGpu":true,"gpuResourceReuse":true}""")
        applyFullGpuDiffusionPolicy(params,true)
        assertTrue(params.getBoolean("gpuDiffusionExperimental"));assertFalse(params.getBoolean("useGpu"));assertFalse(params.getBoolean("gpuResourceReuse"))
        assertEquals(42,params.getInt("seed"));assertEquals(4096,params.getInt("width"));assertEquals(3072,params.getInt("height"))
        applyFullGpuDiffusionPolicy(params,false);assertFalse(params.has("gpuDiffusionExperimental"))
    }
    @Test fun preflightFallbackCannotRelabelResumedGpuPixels(){
        val resumed=JSONObject("""{"metrics":{"checkpointResumeStage":3,"gpuDiffusion":{"backend":"gpu_f32_v1","resumed":true,"requested":true,"attempted":false,"fallbackReason":"none"}}}""")
        val before=resumed.toString();annotateGpuPreflightFallback(resumed);assertEquals(before,resumed.toString())
        val fresh=JSONObject("""{"metrics":{"checkpointResumeStage":0,"gpuDiffusion":{"backend":"cpu_f64","requested":true,"fallbackReason":"delegate_unavailable"}}}""")
        annotateGpuPreflightFallback(fresh);assertEquals("preflight_memory",fresh.getJSONObject("metrics").getJSONObject("gpuDiffusion").getString("fallbackReason"))
        val unknown=JSONObject("""{"metrics":{}}""");annotateGpuPreflightFallback(unknown);assertFalse(unknown.getJSONObject("metrics").has("gpuDiffusion"))
    }
    @Test fun opticalPolicyHasIndependentDefaultAndStage3ResumeBoundary(){
        val selected=OptimizationSnapshot(gpuOpticalExperimental=true)
        assertTrue(selected.experimentalOpticalEnabled(true,"export"))
        assertFalse(OptimizationSnapshot().experimentalOpticalEnabled(true,"export"))
        assertFalse(selected.experimentalOpticalEnabled(false,"export"))
        assertTrue(selected.experimentalOpticalEnabled(true,"preview"))
        assertFalse(selected.experimentalOpticalEnabled(true,"export",true))
        assertFalse(selected.copy(cpuComparison=true).experimentalOpticalEnabled(true,"export"))
        val params=JSONObject().put("seed",42).put("width",4096)
        applyGpuOpticalPolicy(params,true);assertTrue(params.getBoolean("gpuOpticalExperimental"));assertFalse(params.getBoolean("useGpu"))
        assertEquals(42,params.getInt("seed"));assertEquals(4096,params.getInt("width"))
        val resumed=JSONObject("""{"metrics":{"checkpointResumeStage":3,"gpuOptical":{"backend":"gpu_spatial_f32_v1","resumed":true}}}""")
        val before=resumed.toString();annotateGpuOpticalPreflightFallback(resumed);assertEquals(before,resumed.toString())
        val fresh=JSONObject("""{"metrics":{"checkpointResumeStage":2,"gpuOptical":{"backend":"cpu_f64"}}}""")
        annotateGpuOpticalPreflightFallback(fresh);assertEquals("preflight_memory",fresh.getJSONObject("metrics").getJSONObject("gpuOptical").getString("fallbackReason"))
        val info=dev.filmroom.diagnostics.PerformanceInfo.fromNative(resumed,dev.filmroom.diagnostics.PerformanceInfo())
        assertEquals("gpu_spatial_f32_v1",info.typedFields()["gpuOpticalBackend"])
        assertEquals(info,dev.filmroom.diagnostics.PerformanceInfo.fromFields(info.typedFields().mapValues{it.value.toString()}))
    }
    @Test fun scannerPolicyPreservesSeedAndResumeProvenance(){
        val params=JSONObject("""{"seed":42,"width":4096,"height":3072,"useGpu":true}""")
        applyGpuScannerPolicy(params,true)
        assertTrue(params.getBoolean("gpuScannerExperimental"));assertFalse(params.getBoolean("useGpu"))
        assertEquals(42,params.getInt("seed"));assertEquals(4096,params.getInt("width"))
        applyGpuScannerPolicy(params,false);assertFalse(params.has("gpuScannerExperimental"))
        val resumed=JSONObject("""{"metrics":{"checkpointResumeStage":6,"gpuScanner":{"backend":"gpu_pchip_f32_v1","resumed":true}}}""")
        val before=resumed.toString();annotateGpuScannerPreflightFallback(resumed);assertEquals(before,resumed.toString())
        val fresh=JSONObject("""{"metrics":{"checkpointResumeStage":5,"gpuScanner":{"backend":"cpu_f64"}}}""")
        annotateGpuScannerPreflightFallback(fresh)
        assertEquals("preflight_memory",fresh.getJSONObject("metrics").getJSONObject("gpuScanner").getString("fallbackReason"))
    }
    @Test fun scannerTelemetryIsClosedAndPreservesUnknowns(){
        val raw=JSONObject("""{"metrics":{"gpuScanner":{"backend":"gpu_pchip_f32_v1","requested":true,"attempted":true,"fallbackReason":"none","chunks":10,"sampleCount":240,"sampleMaxAbs":0.000001,"privatePath":"secret-image","adapter":"private-device"}}}""")
        val info=dev.filmroom.diagnostics.PerformanceInfo.fromNative(raw,dev.filmroom.diagnostics.PerformanceInfo())
        val fields=info.typedFields()
        assertEquals("gpu_pchip_f32_v1",fields["gpuScannerBackend"])
        assertEquals(10.0,fields["gpuScannerChunks"])
        assertFalse(fields.containsKey("gpuScannerStageMs"))
        assertFalse(fields.toString().contains("secret-image"));assertFalse(fields.toString().contains("private-device"))
        assertEquals(info,dev.filmroom.diagnostics.PerformanceInfo.fromFields(fields.mapValues{it.value.toString()}))
        raw.getJSONObject("metrics").getJSONObject("gpuScanner").put("backend","secret-image").put("fallbackReason","secret-image").put("chunks",0.5)
        val rejected=dev.filmroom.diagnostics.PerformanceInfo.fromNative(raw,dev.filmroom.diagnostics.PerformanceInfo()).typedFields()
        assertFalse(rejected.containsKey("gpuScannerBackend"));assertFalse(rejected.containsKey("gpuScannerFallback"));assertFalse(rejected.containsKey("gpuScannerChunks"))
    }
    @Test fun preparationDiagnosticsAdmitOnlyClosedPhasesAndErrnoClasses(){
        val good=JSONObject("""{"metrics":{"gpuDiffusion":{"prepareErrorCode":"GPU_PREPARE_SOURCE_LINK_EXDEV"},"gpuScanner":{"prepareErrorCode":"GPU_PREPARE_COPY_RESERVE"},"gpuOptical":{"prepareErrorCode":"GPU_PREPARE_STAGE_MKDIR_EACCES"}}}""")
        val info=dev.filmroom.diagnostics.PerformanceInfo.fromNative(good,dev.filmroom.diagnostics.PerformanceInfo())
        assertEquals(3,info.prepareErrors.size)
        assertEquals(info,dev.filmroom.diagnostics.PerformanceInfo.fromFields(info.typedFields().mapValues{it.value.toString()}))
        for (code in listOf("GPU_PREPARE_SOURCE_LINK_EACCES", "GPU_PREPARE_COPY_HASH", "GPU_PREPARE_COPY_VERIFY_EACCES", "GPU_PREPARE_COPY_VERIFY_EOF")) {
            assertEquals(code, dev.filmroom.diagnostics.GpuPrepareCode.parse(code)?.id)
        }
        for(code in listOf("/private/photo", "GPU_PREPARE_SOURCE_LINK_EXDEV /private/photo", "GPU_PREPARE_SECRET_EPERM", "GPU_STAGE_IO")){
            assertNull(dev.filmroom.diagnostics.GpuPrepareCode.parse(code))
        }
    }
    @Test fun exposureAndDirAreExplicitPreviewAndExportAndPreserveResumeIdentity(){
        val selected=OptimizationSnapshot(gpuExposureExperimental=true,gpuDirExperimental=true)
        assertFalse(OptimizationSnapshot().experimentalExposureEnabled(true,"export"))
        assertFalse(OptimizationSnapshot().experimentalDirEnabled(true,"export"))
        for (enabled in listOf<(OptimizationSnapshot,Boolean,String,Boolean)->Boolean>(
            {s,p,m,c->s.experimentalExposureEnabled(p,m,c)}, {s,p,m,c->s.experimentalDirEnabled(p,m,c)})) {
            assertTrue(enabled(selected,true,"export",false))
            assertFalse(enabled(selected,false,"export",false))
            assertTrue(enabled(selected,true,"preview",false))
            assertFalse(enabled(selected,true,"unknown",false))
            assertFalse(enabled(selected,true,"export",true))
            assertFalse(enabled(selected.copy(cpuComparison=true),true,"export",false))
        }
        val p=JSONObject().put("seed",42).put("width",4096).put("height",3072)
        applyGpuExposurePolicy(p,true);applyGpuDirPolicy(p,true)
        assertTrue(p.getBoolean("gpuExposureExperimental"));assertTrue(p.getBoolean("gpuDirExperimental"))
        assertFalse(p.getBoolean("useGpu"));assertFalse(p.getBoolean("gpuResourceReuse"))
        assertEquals(42,p.getInt("seed"));assertEquals(4096,p.getInt("width"));assertEquals(3072,p.getInt("height"))
        for ((stage,boundary,annotate) in listOf(
            Triple("gpuExposure",2,::annotateGpuExposurePreflightFallback),Triple("gpuDir",4,::annotateGpuDirPreflightFallback))) {
            val resumed=JSONObject().put("metrics",JSONObject().put("checkpointResumeStage",boundary).put(stage,JSONObject().put("resumed",true)))
            val before=resumed.toString();annotate(resumed);assertEquals(before,resumed.toString())
            val fresh=JSONObject().put("metrics",JSONObject().put("checkpointResumeStage",boundary-1).put(stage,JSONObject().put("backend","cpu_f64")))
            annotate(fresh);assertEquals("preflight_memory",fresh.getJSONObject("metrics").getJSONObject(stage).getString("fallbackReason"))
        }
    }
    @Test fun choicesChangeExecutionOnlyAndNeverSeedOrResolution(){
        for(mask in 0..127){
            val params=JSONObject("{\"seed\":42,\"width\":768,\"brightnessEv\":1.5,\"useGpu\":true}")
            OptimizationSnapshot(mask,true).applyTo(params)
            assertEquals(mask,params.getInt("exportOptimizationMask"))
            assertFalse(params.getBoolean("useGpu"))
            assertEquals(42,params.getInt("seed"));assertEquals(768,params.getInt("width"))
            assertEquals(1.5,params.getDouble("brightnessEv"),0.0)
        }
        assertEquals(!dev.filmroom.BuildConfig.PERF_BUILD,OptimizationSnapshot().applyTo(JSONObject().put("useGpu",true)).getBoolean("useGpu"))
        assertTrue(OptimizationSnapshot().applyTo(JSONObject().put("useGpu",true),perfBuild=false).getBoolean("useGpu"))
    }
    @Test fun diagnosticsKeepBoundedCountersButNeverPathsOrParameters(){
        val app=RuntimeEnvironment.getApplication()
        val store=app.getSharedPreferences("exact-optimization-test",0);store.edit().clear().commit()
        val preferences=OptimizationPreferences(app)
        preferences.record("export",OptimizationSnapshot(5,true),JSONObject("""{"ok":true,"elapsedMs":12,"privatePath":"secret-photo","effectiveParams":{"private":"secret-photo"},"metrics":{"checkpointCommitMs":2,"checkpointIdentityMs":3,"checkpointResumeStage":3,"stages":[{"stage":"grain","elapsedMs":4},{"stage":"secret-photo","elapsedMs":8}],"exportExecution":{"activePoolWorkers":2,"workerCap":3,"privatePath":"secret-photo"},"gpu":{"resourceReuseBatches":6,"resourceReuseExactChecks":1,"resourceReuseReason":"secret-photo"}}}"""),15.0)
        val raw=store.getString("exportTiming",null)!!
        assertFalse(raw.contains("secret-photo"));assertFalse(raw.contains("effectiveParams"))
        val timing=JSONObject(raw)
        assertEquals(3,timing.getInt("checkpointResumeStage"));assertEquals(1,timing.getJSONArray("stages").length())
        assertEquals(6,timing.getJSONObject("gpu").getInt("resourceReuseBatches"))
        assertTrue(preferences.lastTiming().contains("Resume stage=3"))
        assertTrue(preferences.lastTiming().contains("grain: 4.0 ms"))
        store.edit().clear().commit()
    }

}
