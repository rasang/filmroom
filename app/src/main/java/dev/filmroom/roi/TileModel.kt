package dev.filmroom.roi

import android.graphics.Bitmap
import dev.filmroom.diagnostics.DetailTiming
import kotlin.math.*

/** Strings must identify immutable snapshots, never a mutable path/parameter object. */
data class RenderKey(
    val sourceRevision: String,
    val parametersRevision: String,
    val algorithmRevision: String,
    val dataRevision: String,
    val orientedSourceMapping: String,
    val virtualFullWidth: Int,
    val virtualFullHeight: Int,
    val displaySamplingRevision: String = "bilinear-valid-gutter1-v1"
) {
    init { require(virtualFullWidth > 0 && virtualFullHeight > 0) }
}
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun sampleRect(k: RenderKey) = PixelRect(max(0,left-1), max(0,top-1), min(k.virtualFullWidth,right+1), min(k.virtualFullHeight,bottom+1))
    val width get() = right - left
    val height get() = bottom - top
}
data class TileId(val x: Int, val y: Int) {
    fun rect(k: RenderKey) = PixelRect(x * EDGE, y * EDGE,
        min((x.toLong() + 1) * EDGE, k.virtualFullWidth.toLong()).toInt(),
        min((y.toLong() + 1) * EDGE, k.virtualFullHeight.toLong()).toInt())
    companion object { const val EDGE = 256 }
}
enum class TileQuality { Exact, ApproximateTestOnly }
/** Provider transfers exclusive ownership; it must not recycle or mutate after returning. */
data class TileResult(val key: RenderKey, val coreRect: PixelRect, val sampleRect: PixelRect, val bitmap: Bitmap, val quality: TileQuality, val diagnosticTiming:DetailTiming?=null) {
    val outputRect get() = coreRect // Global logical coverage, excluding display-filter samples.
}
fun interface TileProvider {
    fun startTiming():DetailTiming?=null
    fun renderTimed(key:RenderKey,outputRect:PixelRect,cancelled:()->Boolean,timing:DetailTiming?):TileResult=render(key,outputRect,cancelled)
    /** Only worker calls this. Return the requested core and its 1px valid-image sampleRect.
     * RegionScheduler requests a tight visible rectangle; legacy TileScheduler requests fixed cells.
     * Render gutter from the SAME global coordinates/phase, not replicated core edges.
     * Read cancellation cooperatively; no global engine.cancel(). */
    fun render(key: RenderKey, outputRect: PixelRect, cancelled: () -> Boolean): TileResult
}

/** Coordinates are normalized in the already-oriented, cropped source, not in preview pixels. */
class Viewport {
    var zoom = 1.0; private set
    var centerX = 0.5; private set
    var centerY = 0.5; private set
    var width = 0; private set
    var height = 0; private set
    private var aspect = 1.0
    fun size(w: Int, h: Int) { width = w; height = h; clamp() }
    fun sourceAspect(value: Double) { require(value > 0 && value.isFinite()); aspect = value; clamp() }
    val imageWidth get() = min(width.toDouble(), height * aspect) * zoom
    val imageHeight get() = imageWidth / aspect
    val left get() = width / 2.0 - centerX * imageWidth
    val top get() = height / 2.0 - centerY * imageHeight
    fun restore(zoomValue: Double, offsetX: Double, offsetY: Double) {
        zoom = zoomValue.coerceIn(1.0,6.0)
        centerX = if(imageWidth>0) .5-offsetX/imageWidth else .5
        centerY = if(imageHeight>0) .5-offsetY/imageHeight else .5
        clamp()
    }
    fun reset() { zoom = 1.0; centerX = .5; centerY = .5 }
    fun drag(dx: Double, dy: Double) {
        if (imageWidth <= 0 || imageHeight <= 0) return
        centerX -= dx / imageWidth; centerY -= dy / imageHeight; clamp()
    }
    fun pinch(factor: Double, focusX: Double, focusY: Double) {
        if (imageWidth <= 0 || imageHeight <= 0 || !factor.isFinite() || factor <= 0) return
        val sourceX = (focusX - left) / imageWidth
        val sourceY = (focusY - top) / imageHeight
        zoom = (zoom * factor).coerceIn(1.0, 6.0)
        centerX = sourceX - (focusX - width / 2.0) / imageWidth
        centerY = sourceY - (focusY - height / 2.0) / imageHeight
        clamp()
    }
    private fun clamp() {
        if (imageWidth <= width || imageWidth <= 0) centerX = .5
        else centerX = centerX.coerceIn(width / (2 * imageWidth), 1 - width / (2 * imageWidth))
        if (imageHeight <= height || imageHeight <= 0) centerY = .5
        else centerY = centerY.coerceIn(height / (2 * imageHeight), 1 - height / (2 * imageHeight))
    }
    fun visibleRect(k: RenderKey): PixelRect? {
        if (width <= 0 || height <= 0) return null
        val x0 = ((-left / imageWidth).coerceIn(0.0, 1.0) * k.virtualFullWidth).toInt()
        val y0 = ((-top / imageHeight).coerceIn(0.0, 1.0) * k.virtualFullHeight).toInt()
        val x1 = ceil(((width - left) / imageWidth).coerceIn(0.0, 1.0) * k.virtualFullWidth).toInt()
        val y1 = ceil(((height - top) / imageHeight).coerceIn(0.0, 1.0) * k.virtualFullHeight).toInt()
        if (x1 <= x0 || y1 <= y0) return null
        return PixelRect(x0,y0,x1,y1)
    }
    fun visibleTiles(k: RenderKey): List<TileId> {
        val r=visibleRect(k) ?: return emptyList()
        val x0=r.left;val y0=r.top;val x1=r.right;val y1=r.bottom
        val result = mutableListOf<TileId>()
        for (y in y0 / 256..(y1 - 1) / 256) for (x in x0 / 256..(x1 - 1) / 256) result += TileId(x,y)
        return result.sortedBy { val r = it.rect(k); (r.left + r.right - x0 - x1).toDouble().pow(2) + (r.top + r.bottom - y0 - y1).toDouble().pow(2) }
    }
}
