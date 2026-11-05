package dev.filmroom.engine

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Plan inputs are executed native artifacts; result edits below are deliberate hostile IPC fixtures. */
internal object GpuScienceFixtures {
    val files = linkedMapOf(GpuRenderOperation.AUTO_EXPOSURE to "auto-exposure", GpuRenderOperation.HIGHLIGHT_BOOST to "highlight-boost",
        GpuRenderOperation.EXPOSURE_LOG to "exposure-log", GpuRenderOperation.FILM_GRAIN to "grain", GpuRenderOperation.PRINT_EXPOSURE to "print-exposure",
        GpuRenderOperation.PRINT_DIFFUSION to "print-diffusion", GpuRenderOperation.SCAN_TAIL to "scan-tail",GpuRenderOperation.FINISH_TAIL to "finish-tail")
    fun envelope(operation: GpuRenderOperation, token: Long = 100 + operation.ordinal.toLong()): String {
        val source = requireNotNull(javaClass.classLoader!!.getResourceAsStream("gpu-contracts/${files.getValue(operation)}.json")) { "Missing executed native plan fixture: $operation" }
        return source.bufferedReader().use { JSONObject(it.readText()) }.apply { getJSONObject("plan").put("token", token) }.toString()
    }
    fun plan(operation: GpuRenderOperation, token: Long = 100 + operation.ordinal.toLong()) = GpuRenderProtocol.parseStagePlan(envelope(operation,token),token,operation)
    fun request(context: Context, operation: GpuRenderOperation, token: Long = 100 + operation.ordinal.toLong()): String {
        val p = plan(operation,token)
        val directory = File(context.cacheDir,"gpu-render/gpu-stage-$token").apply { mkdirs() }
        return JSONObject().put("schemaVersion",1).put("operation",operation.wire).put("token",token).put("parentHandle",11L)
            .put("jobDir",directory.canonicalPath).put("manifestSha256",p.digest).put("budgetBytes",p.combinedPeakBytes)
            .put("width",p.width).put("height",p.height).put("totalPixels",3L*p.width*p.height).toString()
    }
    fun result(p: GpuStagePlan): JSONObject {
        val v = JSONObject().put("ok",true).put("schemaVersion",1).put("operation",p.operation.wire).put("token",p.token).put("manifestSha256",p.digest)
            .put("gpuUsed",true).put("fullImage",true).put("width",p.width).put("height",p.height).put("channels",3).put("deviceBytes",p.deviceBytes)
            .put("elapsedMs",123.0).put("setupMs",1.0).put("uploadMs",2.0).put("dispatchWaitMs",3.0).put("readbackMs",4.0).put("guardMs",5.0)
            .put("vendor",20803).put("shaderF64Advertised",false).put("uploadBytes",1234L).put("readbackBytes",1234L).put("dispatches",1L)
            .put("passCount",0L).put("sampleCount",0L).put("sampleMaxAbs",1e-7).put("sampleRms",1e-8).put("sampleToleranceAbsolute",0.001).put("sampleToleranceRelative",0.001)
            .put("adapter","/private/photo SECRET").put("hostWork","PRIVATE_NATIVE_PROSE")
        when (p) {
            is GpuFilmGrainPlan -> {
                val samples=p.passCount*(p.width.toLong()+p.height);val sampler=GpuFilmGrainProtocol.expectedSamplerSamples(p)
                v.put("backend",GpuFilmGrainProtocol.NATIVE_BACKEND).put("sampleScope",GpuFilmGrainProtocol.SAMPLE_SCOPE)
                    .put("grainActive",p.grainActive).put("sublayers",p.sublayers).put("layerCount",p.layerCount).put("chunkCount",p.chunkCount)
                    .put("passCount",p.passCount).put("sampleCount",samples).put("samplerSampleCount",sampler).put("exactRngWordsChecked",98304L)
                    .put("rngMode",p.rngMode.wire).put("distributionArithmetic",GpuFilmGrainProtocol.DISTRIBUTION)
                    .put("microActive",p.microActive).put("finalBlurActive",p.finalBlurActive).put("amountBlendActive",p.amountBlendActive)
                    .put("softwareArithmeticChecks",1280L).put("numericFlagReads",GpuFilmGrainProtocol.numericFlagReads(p))
                    .put("stochasticScope",GpuFilmGrainProtocol.STOCHASTIC_SCOPE).put("microstructureArithmetic",GpuFilmGrainProtocol.MICROSTRUCTURE)
                    .put("samplerMaxAbs",1e-6).put("samplerRms",1e-7).put("readbackBytes",GpuFilmGrainProtocol.expectedReadback(p))
                    .put("dispatches",GpuFilmGrainProtocol.dispatchRange(p).first)
                for(key in listOf("poissonCountMismatches","binomialCountMismatches","randomWordCountMismatches","samplerTupleMismatches"))v.put(key,0L)
            }
            is GpuGlobalPlan -> {
                val n=p.width.toLong()*p.height;val chunks=(n+65535)/65536
                v.put("backend",GpuGlobalProtocol.NATIVE_BACKEND).put("sampleToleranceAbsolute",0.002).put("sampleToleranceRelative",0.002)
                if(p.operation==GpuRenderOperation.AUTO_EXPOSURE){val(w,h)=GpuGlobalProtocol.sampleGrid(p.width,p.height)
                    v.put("sampleScope",GpuGlobalProtocol.METER_SCOPE).put("sampleCount",minOf(8L,w.toLong()*h)).put("dispatches",4L)
                        .put("meteredEv",-2.0).put("autoExposureScale",0.25).put("sampleWidth",w).put("sampleHeight",h).put("meterMethod","average").put("separateF32AutoExposureScale",true)
                }else{v.put("sampleScope",GpuGlobalProtocol.PLANE_SCOPE).put("sampleCount",3*(8*(chunks-1)+minOf(8,n-65536*(chunks-1)))+if(p.operation==GpuRenderOperation.HIGHLIGHT_BOOST)1 else 0)
                    .put("dispatches",6*chunks+if(p.operation==GpuRenderOperation.HIGHLIGHT_BOOST)4 else 1)
                    if(p.operation==GpuRenderOperation.HIGHLIGHT_BOOST)v.put("highlightPeak",1.0)
                }
            }
            is GpuFinishTailPlan -> {
                val n=p.width.toLong()*p.height;val chunks=(n+32767)/32768
                v.put("backend",GpuFinishTailProtocol.NATIVE_BACKEND).put("sampleScope",GpuFinishTailProtocol.SAMPLE_SCOPE)
                    .put("passCount",p.passCount).put("sampleCount",p.sampleCount).put("glareGenerationGpu",false)
                    .put("glareRandomWords",0L).put("glareChunks",0L).put("glarePredicateScope","disabled").put("glareAmbiguityBound",0.0)
                    .put("residentFinishTail",true).put("intermediateImageReadbackBytes",0L).put("combinedPeakBytes",p.combinedPeakBytes)
                    .put("prefixSampleCount",3*GpuFinishTailProtocol.pointSamples(p)).put("prefixSampleToleranceAbsolute",2e-5).put("prefixSampleToleranceRelative",2e-5)
                    .put("readbackBytes",12*n+1120*chunks+8*p.passCount*(p.width.toLong()+p.height)+8)
            }
            is GpuScanTailPlan -> v.put("backend",GpuScanTailProtocol.NATIVE_BACKEND).put("sampleScope",GpuScanTailProtocol.SAMPLE_SCOPE)
                .put("passCount",p.passCount).put("sampleCount",p.sampleCount).put("glareGenerationGpu",false)
                .put("glareRandomWords",0L).put("glareChunks",0L).put("glarePredicateScope","disabled").put("glareAmbiguityBound",0.0)
            is GpuPrintScanPlan -> v.put("backend",GpuPrintScanProtocol.nativeBackend(p.operation)).put("mode",p.mode.wire).put("chunkCount",p.chunkCount)
                .put("sampleCount",GpuPrintScanProtocol.expectedSamples(p)).put("sampleToleranceAbsolute",2e-5).put("sampleToleranceRelative",2e-5)
            is GpuRenderPlan -> v.put("backend","Vulkan f32 FFT and scatter").put("tileCount",p.tileCount).put("sampleCount",p.tileCount)
                .put("sampleToleranceAbsolute",2e-5).put("sampleToleranceRelative",2e-5)
            else -> error("Expected scientific stage")
        }
        return v
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class GpuScienceProtocolTest {
    @Test fun allEightNativeSchemasRejectUndersizedBudgetsWrongRoutesAndUnknownKeys() {
        for(operation in GpuScienceFixtures.files.keys) {
            val valid=JSONObject(GpuScienceFixtures.envelope(operation));val token=valid.getJSONObject("plan").getLong("token")
            for(key in listOf("deviceBytes","hostBytes","combinedPeakBytes","diskBytes")) {
                val packet=JSONObject(valid.toString());packet.getJSONObject("plan").put(key,1)
                assertTrue("$operation $key",runCatching{GpuRenderProtocol.parseStagePlan(packet.toString(),token,operation)}.isFailure)
            }
            val unknown=JSONObject(valid.toString());unknown.getJSONObject("plan").put("nativeMethod","arbitrary")
            assertTrue(runCatching{GpuRenderProtocol.parseStagePlan(unknown.toString(),token,operation)}.isFailure)
            val fractional=JSONObject(valid.toString());fractional.getJSONObject("plan").put("width",1.0)
            assertTrue(runCatching{GpuRenderProtocol.parseStagePlan(fractional.toString(),token,operation)}.isFailure)
            for(other in GpuRenderOperation.entries.filter{it!=operation})assertTrue(runCatching{GpuRenderProtocol.parseStagePlan(valid.toString(),token,other)}.isFailure)
        }
    }
    @Test fun summariesRoundTripWithoutDriverTextAndRejectUntrustedIpc() {
        for(operation in GpuScienceFixtures.files.keys) {
            val plan=GpuScienceFixtures.plan(operation)
            val(code,summary)=GpuRenderProtocol.parseStageNativeResult(GpuScienceFixtures.result(plan).toString(),plan)
            assertEquals(GpuRenderCode.OK,code)
            val safe=GpuRenderProtocol.encodeResult(code,summary)
            assertFalse(safe.contains("PRIVATE"));assertFalse(safe.contains("SECRET"));assertFalse(safe.contains("adapter"));assertFalse(safe.contains(plan.digest))
            assertEquals(safe,GpuRenderProtocol.parseStageResult(safe,plan).let{GpuRenderProtocol.encodeResult(it.first,it.second)})
            val extra=JSONObject(safe);extra.getJSONObject("summary").put("sourcePath","/private")
            assertTrue(runCatching{GpuRenderProtocol.parseStageResult(extra.toString(),plan)}.isFailure)
            for(key in listOf("elapsedMs","sampleRms")){val bad=GpuScienceFixtures.result(plan).put(key,"NaN");assertTrue(runCatching{GpuRenderProtocol.parseStageNativeResult(bad.toString(),plan)}.isFailure)}
            val wrong=GpuScienceFixtures.result(plan).put("operation",GpuRenderOperation.FILM_DIR.wire)
            assertTrue(runCatching{GpuRenderProtocol.parseStageNativeResult(wrong.toString(),plan)}.isFailure)
        }
    }
    @Test fun grainPipelineAllowanceIsReservedAndOldUnderbudgetPlanRejected() {
        val p = GpuScienceFixtures.plan(GpuRenderOperation.FILM_GRAIN) as GpuFilmGrainPlan
        assertEquals(128 * GpuRenderProtocol.MIB, p.driverAllowanceBytes)
        assertEquals(p.deviceBytes + p.hostBytes + p.driverAllowanceBytes + p.helperAllowanceBytes, p.combinedPeakBytes)
        GpuFilmGrainProtocol.validatePlan(p)
        val old = p.copy(driverAllowanceBytes = 64 * GpuRenderProtocol.MIB,
            combinedPeakBytes = p.combinedPeakBytes - 64 * GpuRenderProtocol.MIB)
        assertTrue(runCatching { GpuFilmGrainProtocol.validatePlan(old) }.isFailure)
        assertTrue(runCatching { GpuFilmGrainProtocol.validatePlan(p.copy(combinedPeakBytes = old.combinedPeakBytes)) }.isFailure)
    }
    @Test fun fullTailCompilerAllowanceUsesOnlyClosedPolicyClasses() {
        val p = GpuScienceFixtures.plan(GpuRenderOperation.SCAN_TAIL) as GpuScanTailPlan
        assertEquals(320 * GpuRenderProtocol.MIB, p.driverAllowanceBytes)
        GpuScanTailProtocol.validatePlan(p)
        val old = p.copy(driverAllowanceBytes = 192 * GpuRenderProtocol.MIB,
            combinedPeakBytes = p.combinedPeakBytes - 128 * GpuRenderProtocol.MIB)
        assertTrue(runCatching { GpuScanTailProtocol.validatePlan(old) }.isFailure)
    }
    @Test fun sequentialGrainSeventeenChunksUseTwoBoundedBatches() {
        // Counter-only fixture: explicit independent arithmetic, not a helper-derived expectation.
        val p=(GpuScienceFixtures.plan(GpuRenderOperation.FILM_GRAIN) as GpuFilmGrainPlan).copy(
            width=4352,height=1,chunkPixels=256,chunkCount=17,rngMode=GpuGrainRngMode.SEQUENTIAL_MT,
            layerCount=3,sublayers=true,grainActive=true,microActive=false,finalBlurActive=false,
            amountBlendActive=false,passCount=0)
        assertEquals(40L,GpuFilmGrainProtocol.numericFlagReads(p))
        assertEquals(767L..767L,GpuFilmGrainProtocol.dispatchRange(p))
        assertEquals(3280L,GpuFilmGrainProtocol.expectedSamplerSamples(p))
        assertEquals(1_321_568L,GpuFilmGrainProtocol.expectedReadback(p))
        val micro=p.copy(microActive=true)
        assertEquals(46L,GpuFilmGrainProtocol.numericFlagReads(micro))
        assertEquals(824L..824L,GpuFilmGrainProtocol.dispatchRange(micro))
    }
    @Test fun grainRequiresExactGuardedReferenceCensusAndRejectsOldF32Scope() {
        val plan=GpuScienceFixtures.plan(GpuRenderOperation.FILM_GRAIN) as GpuFilmGrainPlan
        val report=GpuScienceFixtures.result(plan)
        assertEquals(GpuRenderCode.OK,GpuRenderProtocol.parseStageNativeResult(report.toString(),plan).first)
        for((key,value) in listOf("poissonCountMismatches" to 1,"binomialCountMismatches" to 1,"randomWordCountMismatches" to 1,"samplerTupleMismatches" to 1,
            "exactRngWordsChecked" to 49152,"softwareArithmeticChecks" to 0,"numericFlagReads" to 0,"samplerMaxAbs" to .01,
            "distributionArithmetic" to "f32","rngMode" to "arbitrary","backend" to "Vulkan f32 full grain with original integer RNG"))
            assertTrue("$key",runCatching{GpuRenderProtocol.parseStageNativeResult(JSONObject(report.toString()).put(key,value).toString(),plan)}.isFailure)
    }
    @Test fun residentFinishTailRequiresNoIntermediateImageReadbackAndPrefixWitnesses(){
        val plan=GpuScienceFixtures.plan(GpuRenderOperation.FINISH_TAIL) as GpuFinishTailPlan
        val good=GpuScienceFixtures.result(plan)
        assertEquals(GpuRenderCode.OK,GpuRenderProtocol.parseStageNativeResult(good.toString(),plan).first)
        for((key,value) in listOf("residentFinishTail" to false,"intermediateImageReadbackBytes" to 12L*plan.width*plan.height,"prefixSampleCount" to 0,"prefixSampleToleranceRelative" to .001,"readbackBytes" to 1))
            assertTrue("$key",runCatching{GpuRenderProtocol.parseStageNativeResult(JSONObject(good.toString()).put(key,value).toString(),plan)}.isFailure)
    }
    @Test fun glareEvidenceRequiresBoundedCountsAndExactPredicateMode(){
        val p=GpuScienceFixtures.plan(GpuRenderOperation.SCAN_TAIL) as GpuScanTailPlan
        val n=p.width.toLong()*p.height;val chunks=(n+16383)/16384
        val good=GpuScienceFixtures.result(p).put("glareGenerationGpu",true).put("glareChunks",chunks).put("glareRandomWords",2*n)
            .put("glarePredicateScope","chacha12_exact_fast_guarded_q48").put("glareAmbiguityBound",1.0/4294967296.0).put("dispatches",3*chunks+1)
        assertTrue(GpuRenderProtocol.parseStageNativeResult(good.toString(),p).second!!.json().getBoolean("glareGenerationGpu"))
        for((key,value) in listOf("glareRandomWords" to 96*n,"glareChunks" to n+1,"glareAmbiguityBound" to 0.0,"glarePredicateScope" to "arbitrary_rng")){
            assertTrue(runCatching{GpuRenderProtocol.parseStageNativeResult(JSONObject(good.toString()).put(key,value).toString(),p)}.isFailure)
        }
        assertTrue(runCatching{GpuRenderProtocol.parseStageNativeResult(JSONObject(good.toString()).put("glareGenerationGpu",false).toString(),p)}.isFailure)
    }
    @Test fun numericFailuresAndMemoryCodesAreClosedAndPrivacySafe() {
        for(operation in listOf(GpuRenderOperation.CAMERA_DIFFUSION,GpuRenderOperation.PRINT_DIFFUSION))assertEquals(GpuRenderCode.NUMERIC_REJECTED,GpuRenderProtocol.nativeFailure(operation,"GPU_STAGE_NUMERIC_DOMAIN"))
        for(code in listOf("GPU_GRAIN_MT_RNG_CHECK","GPU_GRAIN_SAMPLER_TUPLE_CHECK","GPU_GRAIN_GUARDED_REJECTION"))assertEquals(GpuRenderCode.NUMERIC_REJECTED,GpuRenderProtocol.nativeFailure(GpuRenderOperation.FILM_GRAIN,code))
        for(code in listOf("GPU_FUSION_PREFIX_NUMERIC_CHECK","GPU_FUSION_NUMERIC_DOMAIN","GPU_TAIL_NUMERIC_DOMAIN","GPU_TAIL_NUMERIC_CHECK","GPU_FINISH_NUMERIC_CHECK","NONFINITE_FINISH_TABLE"))assertEquals(GpuRenderCode.NUMERIC_REJECTED,GpuRenderProtocol.nativeFailure(GpuRenderOperation.FINISH_TAIL,code))
        for(code in listOf("GPU_FINISH_MEMORY_LIMIT","INSUFFICIENT_GPU_FINISH_MEMORY","GPU_TAIL_MEMORY_LIMIT"))assertEquals(GpuRenderCode.MEMORY_UNAVAILABLE,GpuRenderProtocol.nativeFailure(GpuRenderOperation.FINISH_TAIL,code))
        for(code in listOf("GPU_TAIL_SHADER_VERSION","GPU_TAIL_ERROR_ABI","GPU_OPTICS_ERROR_ABI"))assertEquals(GpuRenderCode.NATIVE_FAILED,GpuRenderProtocol.nativeFailure(GpuRenderOperation.SCAN_TAIL,code))
        for((operation,prefix) in mapOf(GpuRenderOperation.AUTO_EXPOSURE to "GLOBAL",GpuRenderOperation.FILM_GRAIN to "GRAIN",GpuRenderOperation.SCAN_TAIL to "TAIL",GpuRenderOperation.FINISH_TAIL to "FUSION")) {
            assertEquals(GpuRenderCode.NUMERIC_REJECTED,GpuRenderProtocol.nativeFailure(operation,"NONFINITE_${prefix}_OUTPUT"))
            assertEquals(GpuRenderCode.MEMORY_UNAVAILABLE,GpuRenderProtocol.nativeFailure(operation,"INSUFFICIENT_GPU_${prefix}_MEMORY"))
            assertEquals(GpuRenderCode.NATIVE_FAILED,GpuRenderProtocol.nativeFailure(operation,"/private/photo"))
        }
    }
}
