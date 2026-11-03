package dev.filmroom.engine

import dev.filmroom.engine.GpuScienceFields.bool
import dev.filmroom.engine.GpuScienceFields.long
import dev.filmroom.engine.GpuScienceFields.number

internal enum class GpuGrainRngMode(val wire:String,val chunkLimit:Int,val sequential:Long) {
    COORDINATE("coordinateSplitMix64",16384,0), SEQUENTIAL_MT("sequentialMt19937",256,1);
    companion object {fun fromWire(value:Any?)=entries.singleOrNull{it.wire==value} ?: error("Invalid grain RNG mode")}
}
internal data class GpuFilmGrainPlan(
    override val token: Long, override val digest: String, override val width: Int, override val height: Int,
    val passCount: Long, val grainActive: Boolean, val sublayers: Boolean, val layerCount: Int, val chunkPixels: Int, val chunkCount: Long,
    val rngMode:GpuGrainRngMode,val microActive:Boolean,val finalBlurActive:Boolean,val amountBlendActive:Boolean,
    override val deviceBytes: Long, override val hostBytes: Long, override val driverAllowanceBytes: Long, override val helperAllowanceBytes: Long,
    override val combinedPeakBytes: Long, override val diskBytes: Long,
) : GpuStagePlan { override val operation get() = GpuRenderOperation.FILM_GRAIN }

internal object GpuFilmGrainProtocol {
    const val NATIVE_BACKEND = "Vulkan guarded binary64 grain sampler with f32 spatial graph"
    const val SAFE_BACKEND = "vulkan_guarded_binary64_grain_f32_spatial_graph"
    const val SAMPLE_SCOPE = "per_axis_f64_on_gpu_f32_input"
    const val DISTRIBUTION = "guardedSoftwareBinary64"
    const val STOCHASTIC_SCOPE = "fixed_synthetic_reference_tuple_census"
    const val MICROSTRUCTURE = "original_words_guarded_f32_box_muller"
    private val graphKeys=setOf("rngMode","distributionArithmetic","microActive","finalBlurActive","amountBlendActive")
    private val planKeys = setOf("passCount", "grainActive", "sublayers", "layerCount", "chunkPixels", "chunkCount")+graphKeys
    private val censusKeys = setOf("poissonCountMismatches", "binomialCountMismatches", "randomWordCountMismatches", "samplerTupleMismatches")
    private val summaryKeys = setOf("grainActive", "sublayers", "layerCount", "chunkCount", "samplerSampleCount", "exactRngWordsChecked", "samplerMaxAbs", "samplerRms",
        "softwareArithmeticChecks","numericFlagReads","stochasticScope","microstructureArithmetic")+censusKeys+graphKeys
    fun parsePlan(raw: String, token: Long): GpuFilmGrainPlan {
        val p = GpuScienceFields.plan(raw, token, GpuRenderOperation.FILM_GRAIN, "planGpuFilmGrainStage", planKeys)
        require(p.get("distributionArithmetic")==DISTRIBUTION)
        return GpuFilmGrainPlan(token, p.getString("manifestSha256"), p.long("width").toInt(), p.long("height").toInt(),
            p.long("passCount", 0, 15), p.bool("grainActive"), p.bool("sublayers"), p.long("layerCount", 0, 64).toInt(), p.long("chunkPixels", 1, 16384).toInt(), p.long("chunkCount", 1),
            GpuGrainRngMode.fromWire(p.get("rngMode")),p.bool("microActive"),p.bool("finalBlurActive"),p.bool("amountBlendActive"),
            p.long("deviceBytes"), p.long("hostBytes"), p.long("driverAllowanceBytes"), p.long("helperAllowanceBytes"), p.long("combinedPeakBytes"), p.long("diskBytes")).also(::validatePlan)
    }
    fun validatePlan(p: GpuFilmGrainPlan) {
        val n = p.width.toLong() * p.height
        GpuScienceFields.validateMemory(p, n, 20, 48 * n + 2 * GpuRenderProtocol.MIB)
        require(p.passCount in 0..15 && p.chunkPixels == minOf(p.rngMode.chunkLimit.toLong(), n).toInt() && p.chunkCount == (n + p.chunkPixels - 1) / p.chunkPixels)
        require(!p.microActive || (p.grainActive && p.sublayers))
        require(!p.finalBlurActive || (p.grainActive && p.passCount>=3))
        require(!p.amountBlendActive || p.grainActive)
        val finalPasses=if(p.finalBlurActive)3L else 0L
        require(if(p.sublayers)p.passCount in finalPasses..(3L*p.layerCount+(if(p.microActive)3 else 0)+finalPasses) else !p.microActive && p.passCount==finalPasses)
        require(if (!p.grainActive) p.layerCount == 0 && p.passCount == 0L && !p.microActive && !p.finalBlurActive && !p.amountBlendActive else if (p.sublayers) p.layerCount == 3 else p.layerCount in 1..64)
    }
    fun expectedSamplerSamples(@Suppress("UNUSED_PARAMETER") p: GpuFilmGrainPlan): Long = 3152
    fun dispatchRange(p: GpuFilmGrainPlan): LongRange {
        val c=p.chunkCount;val s=p.rngMode.sequential;val l=p.layerCount.toLong()
        fun Boolean.count()=if(this)1L else 0L
        val exact=16+3*(c+3+l*((1+3*s)*c+1+s)+p.microActive.count()*(c+1+s)+p.finalBlurActive.count()+p.amountBlendActive.count())+4*p.passCount
        return exact..exact
    }
    fun numericFlagReads(p:GpuFilmGrainPlan):Long {
        // Native sequential sampling retains 256-pixel kernels, but reads the
        // sticky error flag once per bounded batch of 16 ordered submissions.
        val batches=if(p.rngMode.sequential==1L)(p.chunkCount+15L)/16L else p.chunkCount
        return 1+3*(p.layerCount*(1+p.rngMode.sequential)*batches+(if(p.microActive)batches else 0)+1)
    }
    fun expectedReadback(p:GpuFilmGrainPlan):Long=12L*p.width*p.height+1264320+8*p.passCount*(p.width.toLong()+p.height)+4*numericFlagReads(p)
    fun parseNativeResult(raw: String, p: GpuFilmGrainPlan): Pair<GpuRenderCode, GpuScienceSummary?> {
        val v = GpuRenderProtocol.objectOf(raw)
        if (!v.bool("ok")) return GpuRenderProtocol.nativeFailure(p.operation, v.opt("code")) to null
        GpuScienceFields.identity(v, p, NATIVE_BACKEND)
        require(v.bool("grainActive") == p.grainActive && v.bool("sublayers") == p.sublayers && v.long("layerCount") == p.layerCount.toLong() && v.long("chunkCount") == p.chunkCount)
        require(v.get("rngMode")==p.rngMode.wire && v.get("distributionArithmetic")==DISTRIBUTION)
        require(v.bool("microActive")==p.microActive && v.bool("finalBlurActive")==p.finalBlurActive && v.bool("amountBlendActive")==p.amountBlendActive)
        require(v.get("stochasticScope")==STOCHASTIC_SCOPE && v.get("microstructureArithmetic")==MICROSTRUCTURE)
        val spatialSamples = p.passCount * (p.width.toLong() + p.height)
        val result = GpuScienceFields.common(v, p, SAFE_BACKEND, SAMPLE_SCOPE, 0.001, p.passCount..p.passCount, spatialSamples..spatialSamples, dispatchRange(p))
        require(v.long("readbackBytes") == expectedReadback(p))
        result["grainActive"] = p.grainActive; result["sublayers"] = p.sublayers; result["layerCount"] = p.layerCount; result["chunkCount"] = p.chunkCount
        result["rngMode"]=p.rngMode.wire;result["distributionArithmetic"]=DISTRIBUTION
        result["microActive"]=p.microActive;result["finalBlurActive"]=p.finalBlurActive;result["amountBlendActive"]=p.amountBlendActive
        result["stochasticScope"]=STOCHASTIC_SCOPE;result["microstructureArithmetic"]=MICROSTRUCTURE
        result["samplerSampleCount"] = v.long("samplerSampleCount", 3120, 3120)
        result["exactRngWordsChecked"] = v.long("exactRngWordsChecked", 98304, 98304)
        result["softwareArithmeticChecks"]=v.long("softwareArithmeticChecks",1280,1280)
        result["numericFlagReads"]=v.long("numericFlagReads",numericFlagReads(p),numericFlagReads(p))
        // Guarded sampler adoption requires exact reference words/counts/value tuples.
        for (key in censusKeys) result[key] = v.long(key, 0, 0)
        result["samplerMaxAbs"] = v.number("samplerMaxAbs",max=1e-4); result["samplerRms"] = v.number("samplerRms",max=1e-4)
        return GpuRenderCode.OK to GpuScienceSummary.validated(result)
    }
    fun parseResult(raw: String, p: GpuFilmGrainPlan): Pair<GpuRenderCode, GpuScienceSummary?> = GpuScienceFields.result(raw, p, summaryKeys, SAFE_BACKEND, NATIVE_BACKEND) { parseNativeResult(it, p) }
}
