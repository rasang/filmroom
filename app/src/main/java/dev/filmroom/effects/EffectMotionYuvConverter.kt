package dev.filmroom.effects

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import android.media.MediaFormat
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Reconstructed beta27 CPU fallback converter. */
class EffectMotionYuvConverter(private val edge: Int, private val rotation: Int) {
    private var scratchPixels = IntArray(0)
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)

    fun convert(image: Image, format: MediaFormat): Bitmap {
        require(image.format == 35 && image.planes.size == 3) { "Unsupported motion decoder image" }
        val range = if (format.containsKey("color-range")) format.getInteger("color-range") else 2
        val standard = if (format.containsKey("color-standard")) format.getInteger("color-standard")
            else if (image.cropRect.height() > 576) 1 else 4
        return convert(image.cropRect, image.planes.map { Plane(it.buffer, it.rowStride, it.pixelStride) }, range, standard)
    }

    fun convert(crop: Rect, planes: List<Plane>, range: Int, standard: Int): Bitmap {
        require(crop.width() > 0 && crop.height() > 0 && planes.size == 3)
        val scale = minOf(1.0, edge.toDouble() / maxOf(crop.width(), crop.height()))
        val width = (crop.width() * scale).roundToInt().coerceAtLeast(1)
        val height = (crop.height() * scale).roundToInt().coerceAtLeast(1)
        val (yp, up, vp) = planes
        val yb = yp.buffer; val ub = up.buffer; val vb = vp.buffer
        val yBase = yb.position(); val uBase = ub.position(); val vBase = vb.position()
        val full = range == 1
        val coefficients = when (standard) {
            1 -> if (full) intArrayOf(256, 403, 48, 120, 475) else intArrayOf(298, 459, 55, 136, 541)
            6 -> if (full) intArrayOf(256, 377, 42, 146, 482) else intArrayOf(298, 430, 48, 167, 548)
            else -> if (full) intArrayOf(256, 359, 88, 183, 454) else intArrayOf(298, 409, 100, 208, 516)
        }
        if (scratchPixels.size != width * height) scratchPixels = IntArray(width * height)
        for (y in 0 until height) {
            val sy = crop.top + crop.height() * y / height
            val yr = yBase + yp.rowStride * sy
            val ur = uBase + up.rowStride * (sy / 2)
            val vr = vBase + vp.rowStride * (sy / 2)
            for (x in 0 until width) {
                val sx = crop.left + crop.width() * x / width
                val yy = (yb.get(yr + yp.pixelStride * sx).toInt() and 255) - if (full) 0 else 16
                val uu = (ub.get(ur + up.pixelStride * (sx / 2)).toInt() and 255) - 128
                val vv = (vb.get(vr + vp.pixelStride * (sx / 2)).toInt() and 255) - 128
                val luma = coefficients[0] * yy
                val r = ((luma + coefficients[1] * vv + 128) shr 8).coerceIn(0, 255)
                val g = ((luma - coefficients[2] * uu - coefficients[3] * vv + 128) shr 8).coerceIn(0, 255)
                val b = ((luma + coefficients[4] * uu + 128) shr 8).coerceIn(0, 255)
                scratchPixels[y * width + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(scratchPixels, width, height, Bitmap.Config.ARGB_8888)
        if (((rotation % 360) + 360) % 360 == 0) return bitmap
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (t: Throwable) { bitmap.recycle(); throw t }
    }
}
