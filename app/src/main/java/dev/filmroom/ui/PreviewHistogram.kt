package dev.filmroom.ui

import android.graphics.Bitmap
import dev.filmroom.EditorUiState
import dev.filmroom.model.CompositionTransform
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.floor

/** Display-referred sRGB luma, not linear metering, RAW clipping or export statistics. */
internal data class PreviewHistogram(val bins: IntArray, val samples: Int) {
    val peak: Int = bins.maxOrNull() ?: 0
}

/** Immutable published preview only. Never owns/recycles the bitmap or requests another render. */
internal data class PreviewHistogramInput(
    val bitmap: Bitmap, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val generation: Int = bitmap.generationId,
    val rotation:CompositionTransform?=null,val canonicalWidth:Int=bitmap.width,val canonicalHeight:Int=bitmap.height,
) {
    companion object {
        fun from(state: EditorUiState): PreviewHistogramInput? {
            if (state.photo == null || state.busy != null || state.compositionEditing) return null
            val original = state.compare || state.rendered == null
            val adopted = state.renderedParameters
            val requested = state.parameters
            // PhotoView can keep a live crop transform after commit/cancel/undo while the exact
            // compositor is still catching up. Do not show statistics for its previous window.
            // Ordinary exposure/film changes keep the old photo visible and may keep its graph.
            if (!original && adopted != null && (adopted.composition != requested.composition ||
                    adopted.crop != requested.crop || adopted.filmBorder != requested.filmBorder ||
                    adopted.filmBorderStage != requested.filmBorderStage ||
                    adopted.effectiveMediumFormat != requested.effectiveMediumFormat ||
                    adopted.effectiveMediumPortrait != requested.effectiveMediumPortrait)) return null
            val preImaging = !original && adopted?.preImagingBorder == true
            val bitmap = (if (original) state.original else if (preImaging) state.rendered else state.compositionSource ?: state.rendered)
                ?.takeUnless { it.isRecycled || it.config == Bitmap.Config.HARDWARE } ?: return null
            // Pre-imaging pixels already include the crop and rotation. Sample only their
            // fixed output aperture, never transform or crop them a second time. Baked-in
            // dates inside that aperture count as displayed pixels; frame rails do not.
            val aperture = state.presentation?.let { p ->
                CompositionTransform.Rect(p.apertureLeft / p.outputWidth, p.apertureTop / p.outputHeight,
                    p.photoWidth.toDouble() / p.outputWidth, p.photoHeight.toDouble() / p.outputHeight)
            }
            // Legacy pre-frame sources still need their adopted source-space rotation.
            val transform = if (!original && !preImaging && bitmap === state.compositionSource)
                state.presentation?.composition ?: state.cropPresentation?.let {
                    it.settings.effectiveTransform(it.canonicalWidth, it.canonicalHeight)
                } else null
            val rotation = transform?.takeIf { it.straightenDegrees != 0.0 }
            val rect = if (original) null else if (preImaging) aperture ?: return null
            else if (rotation != null) rotation.sourceBounds(state.photo.originalWidth, state.photo.originalHeight)
            else if (bitmap === state.compositionSource)
                state.presentation?.sourceRect ?: state.cropPresentation?.sourceRect
            else state.presentation?.let { p ->
                CompositionTransform.Rect(p.apertureLeft / p.outputWidth, p.apertureTop / p.outputHeight,
                    p.photoWidth.toDouble() / p.outputWidth, p.photoHeight.toDouble() / p.outputHeight)
            }
            val left = floor((rect?.left ?: 0.0) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = floor((rect?.top ?: 0.0) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val right = ceil((rect?.right ?: 1.0) * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = ceil((rect?.bottom ?: 1.0) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
            return PreviewHistogramInput(bitmap,left,top,right,bottom,rotation=rotation,
                canonicalWidth=state.photo.originalWidth,canonicalHeight=state.photo.originalHeight)
        }
    }
}

internal object PreviewHistogramSampler {
    const val BINS = 128

    suspend fun sample(input: PreviewHistogramInput): PreviewHistogram? {
        val context = currentCoroutineContext()
        context.ensureActive()
        val bitmap = input.bitmap
        if (bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE) return null
        val width = input.right - input.left
        val height = input.bottom - input.top
        if (width <= 0 || height <= 0) return null
        // Previews already have a bounded resolution. Count every source-crop pixel:
        // a regular sparse grid can alias stripes/checkerboards into a single colour.
        val pixels = IntArray(width)
        val bins = IntArray(BINS)
        var samples=0
        // Count each source pixel inside the rotated crop once, without a sparse grid or frame/glyph pixels.
        val transform=input.rotation?.clamped(input.canonicalWidth,input.canonicalHeight)
        val crop=transform?.rect(input.canonicalWidth,input.canonicalHeight)
        val theta=Math.toRadians(transform?.straightenDegrees ?: 0.0)
        val cos=kotlin.math.cos(theta);val sin=kotlin.math.sin(theta)
        val aspect=input.canonicalWidth.toDouble()/input.canonicalHeight
        try {
            for (row in 0 until height) {
                context.ensureActive()
                bitmap.getPixels(pixels, 0, width, input.left, input.top + row, width, 1)
                for ((column,color) in pixels.withIndex()) {
                    if(transform!=null && crop!=null){
                        val dx=(input.left+column+.5)/bitmap.width-transform.centerX
                        val dy=(input.top+row+.5)/bitmap.height-transform.centerY
                        val u=.5+(cos*dx-sin*dy/aspect)/crop.width
                        val v=.5+(sin*dx*aspect+cos*dy)/crop.height
                        if(u<0.0 || u>=1.0 || v<0.0 || v>=1.0)continue
                    }
                    samples++
                    // getPixels returns encoded sRGB. Use exact decimal Rec.709 weights
                    // (0.2126, 0.7152, 0.0722), round half up to 8 bits, then pair bins.
                    // This is photographic luma Y', not scene-linear luminance Y.
                    val value = (2126 * ((color ushr 16) and 255) + 7152 * ((color ushr 8) and 255) +
                        722 * (color and 255) + 5000) / 10000
                    bins[value ushr 1]++
                }
            }
        } catch (_: IllegalStateException) {
            // Defensive only: published editor previews are not recycled by their producer.
            return null
        }
        context.ensureActive()
        return if(samples==0)null else PreviewHistogram(bins, samples)
    }
}
