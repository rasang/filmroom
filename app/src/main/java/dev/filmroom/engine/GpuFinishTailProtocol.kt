package dev.filmroom.engine

import dev.filmroom.engine.GpuScienceFields.bool
import dev.filmroom.engine.GpuScienceFields.long
import dev.filmroom.engine.GpuScienceFields.number

internal data class GpuFinishTailPlan(
    override val token:Long,override val digest:String,override val width:Int,override val height:Int,
    val mode:GpuPrintScanMode,val tableScalars:Long,val passCount:Long,val sampleCount:Long,
    override val deviceBytes:Long,override val hostBytes:Long,override val driverAllowanceBytes:Long,override val helperAllowanceBytes:Long,
    override val combinedPeakBytes:Long,override val diskBytes:Long
):GpuStagePlan {override val operation get()=GpuRenderOperation.FINISH_TAIL}

/** Resident PCHIP -> scanner tail. Only bounded witnesses and the final image return to host. */
internal object GpuFinishTailProtocol {
    const val NATIVE_BACKEND="Vulkan f32 resident print scanner tail"
    const val SAFE_BACKEND="vulkan_f32_resident_print_scanner_tail"
    const val SAMPLE_SCOPE="full_line_f64_tail_f32_and_prefix_f64_witnesses"
    private val extraKeys=setOf("mode","tableScalars","passCount","sampleCount")
    private val summaryKeys=setOf("glareGenerationGpu","glareRandomWords","glareChunks","glarePredicateScope","glareAmbiguityBound",
        "residentFinishTail","intermediateImageReadbackBytes","prefixSampleCount","prefixSampleToleranceAbsolute","prefixSampleToleranceRelative","combinedPeakBytes")
    fun parsePlan(raw:String,token:Long):GpuFinishTailPlan {
        val p=GpuScienceFields.plan(raw,token,GpuRenderOperation.FINISH_TAIL,"planGpuFinishTailStage",extraKeys)
        return GpuFinishTailPlan(token,p.getString("manifestSha256"),p.long("width").toInt(),p.long("height").toInt(),
            GpuPrintScanMode.fromWire(p.get("mode")),p.long("tableScalars",1,GpuPrintScanProtocol.HARD_CAP/16),p.long("passCount",0,7),p.long("sampleCount",1),
            p.long("deviceBytes"),p.long("hostBytes"),p.long("driverAllowanceBytes"),p.long("helperAllowanceBytes"),p.long("combinedPeakBytes"),p.long("diskBytes")).also(::validatePlan)
    }
    fun pointSamples(p:GpuFinishTailPlan):Long {val n=p.width.toLong()*p.height;val c=(n+32767)/32768;return 8*(c-1)+minOf(8,n-(c-1)*32768)}
    fun validatePlan(p:GpuFinishTailPlan){
        require(p.token>0 && p.width in 1..40000 && p.height in 1..40000 && p.digest.matches(Regex("[0-9a-f]{64}")))
        require(p.mode!=GpuPrintScanMode.PRINT_EXPOSURE_ONLY && p.tableScalars in 1..GpuPrintScanProtocol.HARD_CAP/16 && p.passCount in 0..7)
        val n=p.width.toLong()*p.height;val t=p.tableScalars;val mib=GpuRenderProtocol.MIB
        require(n*4<=128*mib)
        // The resident prefix retains the original finish packet admission ceiling.
        require(20*t+164*minOf(65536,n)+4096+130*mib<=GpuPrintScanProtocol.HARD_CAP)
        require(p.deviceBytes==20*n+8*mib+65536+4*t+256)
        require(p.hostBytes==32*mib+16*t && p.driverAllowanceBytes==64*mib && p.helperAllowanceBytes==64*mib)
        require(p.combinedPeakBytes==p.deviceBytes+p.hostBytes+128*mib && p.combinedPeakBytes<=GpuRenderProtocol.HARD_CAP)
        require(p.diskBytes==36*n+8*t+3*mib)
        require(p.sampleCount==p.passCount*(p.width.toLong()+p.height)+6*pointSamples(p))
    }
    fun parseNativeResult(raw:String,p:GpuFinishTailPlan):Pair<GpuRenderCode,GpuScienceSummary?> {
        val v=GpuRenderProtocol.objectOf(raw)
        if(!v.bool("ok"))return GpuRenderProtocol.nativeFailure(p.operation,v.opt("code")) to null
        GpuScienceFields.identity(v,p,NATIVE_BACKEND)
        require(v.bool("residentFinishTail") && v.long("intermediateImageReadbackBytes")==0L && v.long("combinedPeakBytes")==p.combinedPeakBytes)
        require(v.number("prefixSampleToleranceAbsolute")==2e-5 && v.number("prefixSampleToleranceRelative")==2e-5)
        require(v.long("prefixSampleCount")==3*pointSamples(p))
        val n=p.width.toLong()*p.height;val chunks=(n+32767)/32768
        val generated=v.bool("glareGenerationGpu")
        val glareChunks=v.long("glareChunks",if(generated)(n+16383)/16384 else 0,if(generated)n else 0)
        val words=v.long("glareRandomWords",if(generated)n else 0,if(generated)95*n else 0)
        val scope=v.get("glarePredicateScope")
        // Fusion generates glare on device or disables it; no external image field exists.
        require(scope==if(generated)"chacha12_exact_fast_guarded_q48" else "disabled")
        val ambiguity=v.number("glareAmbiguityBound");require(ambiguity==if(generated)1.0/4294967296.0 else 0.0)
        require(v.long("readbackBytes")==12*n+1120*chunks+8*p.passCount*(p.width.toLong()+p.height)+8+12*glareChunks+if(generated)4*pointSamples(p) else 0)
        val result=GpuScienceFields.common(v,p,SAFE_BACKEND,SAMPLE_SCOPE,0.001,p.passCount..p.passCount,p.sampleCount..p.sampleCount,
            maxOf(1L,3*glareChunks)..(100+100*chunks+3*glareChunks))
        result["glareGenerationGpu"]=generated;result["glareChunks"]=glareChunks;result["glareRandomWords"]=words
        result["glarePredicateScope"]=scope;result["glareAmbiguityBound"]=ambiguity
        result["residentFinishTail"]=true;result["intermediateImageReadbackBytes"]=0L;result["combinedPeakBytes"]=p.combinedPeakBytes
        result["prefixSampleCount"]=3*pointSamples(p);result["prefixSampleToleranceAbsolute"]=2e-5;result["prefixSampleToleranceRelative"]=2e-5
        return GpuRenderCode.OK to GpuScienceSummary.validated(result)
    }
    fun parseResult(raw:String,p:GpuFinishTailPlan):Pair<GpuRenderCode,GpuScienceSummary?> = GpuScienceFields.result(raw,p,summaryKeys,SAFE_BACKEND,NATIVE_BACKEND){parseNativeResult(it,p)}
}
