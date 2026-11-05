package dev.filmroom.engine

import dev.filmroom.engine.GpuScienceFields.bool
import dev.filmroom.engine.GpuScienceFields.long
import dev.filmroom.engine.GpuScienceFields.number

internal data class GpuScanTailPlan(
    override val token: Long, override val digest: String, override val width: Int, override val height: Int,
    val passCount: Long, val sampleCount: Long,
    override val deviceBytes: Long, override val hostBytes: Long, override val driverAllowanceBytes: Long, override val helperAllowanceBytes: Long,
    override val combinedPeakBytes: Long, override val diskBytes: Long,
) : GpuStagePlan { override val operation get() = GpuRenderOperation.SCAN_TAIL }

internal object GpuScanTailProtocol {
    const val NATIVE_BACKEND = "Vulkan f32 original scanner tail"
    const val SAFE_BACKEND = "vulkan_f32_original_scanner_tail"
    const val SAMPLE_SCOPE = "full_line_f64_and_tail_f32_arithmetic_witnesses"
    private val glareKeys = setOf("glareGenerationGpu", "glareRandomWords", "glareChunks", "glarePredicateScope", "glareAmbiguityBound")
    fun parsePlan(raw: String, token: Long): GpuScanTailPlan {
        val p = GpuScienceFields.plan(raw, token, GpuRenderOperation.SCAN_TAIL, "planGpuScanTailStage", setOf("passCount", "sampleCount"))
        return GpuScanTailPlan(token, p.getString("manifestSha256"), p.long("width").toInt(), p.long("height").toInt(), p.long("passCount",0,7), p.long("sampleCount",0),
            p.long("deviceBytes"), p.long("hostBytes"), p.long("driverAllowanceBytes"), p.long("helperAllowanceBytes"), p.long("combinedPeakBytes"), p.long("diskBytes")).also(::validatePlan)
    }
    fun validatePlan(p: GpuScanTailPlan) {
        val n = p.width.toLong() * p.height
        require(p.driverAllowanceBytes in setOf(64 * GpuRenderProtocol.MIB, 320 * GpuRenderProtocol.MIB))
        GpuScienceFields.validateMemory(p, n, 24, 44 * n + 2 * GpuRenderProtocol.MIB, driverMiB = p.driverAllowanceBytes / GpuRenderProtocol.MIB)
        val chunks = (n + 32767) / 32768
        require(p.passCount in 0..7 && p.sampleCount == p.passCount * (p.width.toLong() + p.height) + 3 * (8 * (chunks - 1) + minOf(8, n - (chunks - 1) * 32768)))
    }
    fun parseNativeResult(raw: String, p: GpuScanTailPlan): Pair<GpuRenderCode, GpuScienceSummary?> {
        val v = GpuRenderProtocol.objectOf(raw)
        if (!v.bool("ok")) return GpuRenderProtocol.nativeFailure(p.operation, v.opt("code")) to null
        GpuScienceFields.identity(v, p, NATIVE_BACKEND)
        val n = p.width.toLong() * p.height; val chunks = (n + 32767) / 32768
        val generated = v.bool("glareGenerationGpu")
        val glareChunks = v.long("glareChunks", if (generated) (n + 16383) / 16384 else 0, if (generated) n else 0)
        val words = v.long("glareRandomWords", if (generated) n else 0, if (generated) 95 * n else 0)
        val scope = v.get("glarePredicateScope") as? String
        require(if (generated) scope == "chacha12_exact_fast_guarded_q48" else scope in setOf("external_original_field", "disabled"))
        val ambiguity = v.number("glareAmbiguityBound")
        require(ambiguity == if (generated) 1.0 / 4294967296.0 else 0.0)
        val result = GpuScienceFields.common(v, p, SAFE_BACKEND, SAMPLE_SCOPE, 0.001, p.passCount..p.passCount, p.sampleCount..p.sampleCount, maxOf(1L, 3 * glareChunks)..(100L + 100 * chunks + 3 * glareChunks))
        result["glareGenerationGpu"] = generated; result["glareRandomWords"] = words; result["glareChunks"] = glareChunks
        result["glarePredicateScope"] = requireNotNull(scope); result["glareAmbiguityBound"] = ambiguity
        return GpuRenderCode.OK to GpuScienceSummary.validated(result)
    }
    fun parseResult(raw: String, p: GpuScanTailPlan): Pair<GpuRenderCode, GpuScienceSummary?> = GpuScienceFields.result(raw, p, glareKeys, SAFE_BACKEND, NATIVE_BACKEND) { parseNativeResult(it, p) }
}
