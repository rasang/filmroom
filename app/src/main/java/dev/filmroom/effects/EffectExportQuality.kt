package dev.filmroom.effects

import kotlin.math.*

/** Resolution is the scene canvas, not a claim of lossless video or gallery compatibility. */
enum class EffectExportQuality(val longEdge: Int) { ORIGINAL(0), P1920(1920), P1080(1080) }
data class EffectExportSize(val width: Int, val height: Int) {
    val longEdge get() = max(width,height)
}
data class EffectExportReport(val requested: EffectExportSize, val actual: EffectExportSize,
    val inputLimited: Boolean, val fallback: Boolean) {
    val reduced get() = inputLimited || fallback || actual.width < requested.width || actual.height < requested.height
}
object EffectExportResolution {
    fun desired(kind: EffectKind, sourceWidth: Int, sourceHeight: Int, resultWidth: Int, resultHeight: Int,
                quality: EffectExportQuality): EffectExportSize {
        require(minOf(sourceWidth,sourceHeight,resultWidth,resultHeight)>0)
        val aspect=EffectGeometry.sceneAspect(kind,sourceWidth.toFloat()/sourceHeight).toDouble()
        // Scene fractions match the renderer's contain() image viewport, including its bezel.
        val fullWidth = if(kind==EffectKind.LIGHT_TABLE)
            max(resultWidth/(.93*.916), resultHeight/(.88*.866)*aspect)
        else {
            val layout=EffectGeometry.opticalLayout(sourceWidth.toFloat()/sourceHeight)
            max(max(sourceWidth.toDouble(),resultWidth.toDouble())/(1-2*layout.padX),
                max(sourceHeight.toDouble(),resultHeight.toDouble())/(1-2*layout.padY)*aspect)
        }
        val nativeEdge=max(fullWidth,fullWidth/aspect)
        val edge=if(quality==EffectExportQuality.ORIGINAL)nativeEdge else min(nativeEdge,quality.longEdge.toDouble())
        return fit(aspect,edge.toInt().coerceAtLeast(2))
    }
    fun fit(aspect:Double,longEdge:Int):EffectExportSize {
        fun even(v:Double)=max(2,v.toInt()/2*2)
        return if(aspect>=1)EffectExportSize(even(longEdge.toDouble()),even(longEdge/aspect))
               else EffectExportSize(even(longEdge*aspect),even(longEdge.toDouble()))
    }
    /** Decoder + textures + encoder surfaces need headroom, not just the Java bitmap count. */
    fun budgetEdge(maxHeapBytes:Long):Int = when {
        maxHeapBytes>=512L*1024*1024 -> 6144
        maxHeapBytes>=256L*1024*1024 -> 4096
        else -> 2560
    }
    fun candidates(requested:EffectExportSize, available:EffectExportSize, budgetEdge:Int):List<EffectExportSize> {
        val top=minOf(requested.longEdge,available.longEdge,budgetEdge)
        return (listOf(top)+listOf(6144,5120,4096,3840,2560,1920,1280,1080,720,480).filter{it<top})
            .map{fit(requested.width.toDouble()/requested.height,it)}.distinct()
    }
}
