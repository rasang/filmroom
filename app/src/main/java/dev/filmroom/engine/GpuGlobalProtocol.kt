package dev.filmroom.engine

import dev.filmroom.engine.GpuScienceFields.bool
import dev.filmroom.engine.GpuScienceFields.long
import dev.filmroom.engine.GpuScienceFields.number

internal data class GpuGlobalPlan(
    override val operation: GpuRenderOperation, override val token: Long, override val digest: String, override val width: Int, override val height: Int,
    val sampleWidth: Int, val sampleHeight: Int, val gridVersion: Int,
    override val deviceBytes: Long, override val hostBytes: Long, override val driverAllowanceBytes: Long, override val helperAllowanceBytes: Long,
    override val combinedPeakBytes: Long, override val diskBytes: Long,
) : GpuStagePlan

internal object GpuGlobalProtocol {
    const val NATIVE_BACKEND = "Vulkan f32 original global science"
    const val SAFE_BACKEND = "vulkan_f32_original_global_science"
    const val METER_SCOPE = "bounded luminance witnesses; full GPU statistics and median validated by host test oracle"
    const val PLANE_SCOPE = "bounded f64 point witnesses on GPU f32 inputs and global peak; all output scalars finite validated"
    private val wrappers = mapOf(GpuRenderOperation.AUTO_EXPOSURE to "planGpuAutoExposureStage", GpuRenderOperation.HIGHLIGHT_BOOST to "planGpuHighlightBoostStage", GpuRenderOperation.EXPOSURE_LOG to "planGpuExposureLogStage")
    private val meterKeys = setOf("meteredEv", "autoExposureScale", "sampleWidth", "sampleHeight", "meterMethod", "separateF32AutoExposureScale")
    private val methods = setOf("average", "median", "center_weighted", "partial", "matrix", "multi_zone", "highlight_weighted", "unknown")
    fun parsePlan(raw: String, token: Long, operation: GpuRenderOperation): GpuGlobalPlan {
        val p = GpuScienceFields.plan(raw, token, operation, wrappers[operation] ?: error("Invalid global operation"), setOf("sampleWidth", "sampleHeight", "gridVersion"))
        return GpuGlobalPlan(operation, token, p.getString("manifestSha256"), p.long("width").toInt(), p.long("height").toInt(), p.long("sampleWidth",0,256).toInt(), p.long("sampleHeight",0,256).toInt(), p.long("gridVersion",0,1).toInt(),
            p.long("deviceBytes"), p.long("hostBytes"), p.long("driverAllowanceBytes"), p.long("helperAllowanceBytes"), p.long("combinedPeakBytes"), p.long("diskBytes")).also(::validatePlan)
    }
    fun validatePlan(p: GpuGlobalPlan) {
        require(p.operation in wrappers)
        val n = p.width.toLong() * p.height
        val meter = p.operation == GpuRenderOperation.AUTO_EXPOSURE
        val grid = sampleGrid(p.width, p.height)
        require(if (meter) p.gridVersion == 1 && p.sampleWidth == grid.first && p.sampleHeight == grid.second else p.gridVersion == 0 && p.sampleWidth == 0 && p.sampleHeight == 0)
        GpuScienceFields.validateMemory(p, if (meter) 65536 else n, 16, (if (meter) 28L * grid.first * grid.second else 48 * n) + 65536)
    }
    fun sampleGrid(width: Int, height: Int): Pair<Int, Int> {
        val side = maxOf(width, height)
        if (side <= 256) return width to height
        val scale = 256.0 / side
        return maxOf(1, kotlin.math.floor(width * scale + 0.5).toInt()) to maxOf(1, kotlin.math.floor(height * scale + 0.5).toInt())
    }
    fun parseNativeResult(raw: String, p: GpuGlobalPlan): Pair<GpuRenderCode, GpuScienceSummary?> {
        val v = GpuRenderProtocol.objectOf(raw)
        if (!v.bool("ok")) return GpuRenderProtocol.nativeFailure(p.operation, v.opt("code")) to null
        GpuScienceFields.identity(v, p, NATIVE_BACKEND)
        val meter = p.operation == GpuRenderOperation.AUTO_EXPOSURE
        val n = p.width.toLong() * p.height; val chunks = (n + 65535) / 65536
        val grid = sampleGrid(p.width, p.height)
        val samples = if (meter) minOf(8L, grid.first.toLong() * grid.second) else 3 * (8 * (chunks - 1) + minOf(8, n - 65536 * (chunks - 1))) + if (p.operation == GpuRenderOperation.HIGHLIGHT_BOOST) 1 else 0
        val result = GpuScienceFields.common(v, p, SAFE_BACKEND, if (meter) METER_SCOPE else PLANE_SCOPE, 0.002, 0L..0L, samples..samples, if (meter) 4L..140L else (6 * chunks + if (p.operation == GpuRenderOperation.HIGHLIGHT_BOOST) 4 else 1).let { it..it })
        if (meter) {
            require(v.long("sampleWidth") == grid.first.toLong() && v.long("sampleHeight") == grid.second.toLong())
            val method = v.get("meterMethod") as? String; require(method in methods && v.bool("separateF32AutoExposureScale"))
            result["meteredEv"] = v.number("meteredEv", -1024.0, 1024.0)
            result["autoExposureScale"] = v.number("autoExposureScale", 0.0, Float.MAX_VALUE.toDouble()).also { require(it > 0) }
            result["sampleWidth"] = grid.first; result["sampleHeight"] = grid.second; result["meterMethod"] = requireNotNull(method)
            result["separateF32AutoExposureScale"] = true
        } else if (p.operation == GpuRenderOperation.HIGHLIGHT_BOOST) result["highlightPeak"] = v.number("highlightPeak", -Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble())
        return GpuRenderCode.OK to GpuScienceSummary.validated(result)
    }
    fun parseResult(raw: String, p: GpuGlobalPlan): Pair<GpuRenderCode, GpuScienceSummary?> = GpuScienceFields.result(raw, p,
        when (p.operation) { GpuRenderOperation.AUTO_EXPOSURE -> meterKeys; GpuRenderOperation.HIGHLIGHT_BOOST -> setOf("highlightPeak"); else -> emptySet() }, SAFE_BACKEND, NATIVE_BACKEND) { parseNativeResult(it, p) }
}
