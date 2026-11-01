package dev.filmroom.io

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Region-decoded RGB spool. Never decodes the complete original into a Bitmap. */
internal object OriginalSpool {
    private const val MAX_STRIP_PIXELS = 1_048_576 // 4MiB Bitmap + 4MiB IntArray.

    @Suppress("DEPRECATION")
    fun write(source: File, expectedWidth: Int, expectedHeight: Int, destination: File, job: CoroutineContext): PhotoLimits.Dimensions {
        job.ensureActive()
        val orientation = ExifInterface(source.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            .let { if (it in 1..8) it else 1 }
        val decoder = BitmapRegionDecoder.newInstance(source.absolutePath, false)
            ?: throw IOException("This device cannot region-decode the original image")
        try {
            val mapping = ExifMapping(decoder.width, decoder.height, orientation)
            if (!PhotoLimits.validSource(mapping.width, mapping.height) ||
                mapping.width != expectedWidth || mapping.height != expectedHeight) {
                throw IOException("Original dimensions or EXIF orientation changed")
            }
            val stripRows = minOf(64, maxOf(1, MAX_STRIP_PIXELS / mapping.width))
            val pixels = IntArray(mapping.width * stripRows)
            val rowBytes = ByteArray(mapping.width * 12)
            val rowFloats = ByteBuffer.wrap(rowBytes).order(ByteOrder.LITTLE_ENDIAN)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
            FileOutputStream(destination).use { file ->
                file.buffered(256 * 1024).use { output ->
                    var y = 0
                    while (y < mapping.height) {
                        job.ensureActive()
                        val rows = minOf(stripRows, mapping.height - y)
                        val ax = mapping.rawX(0, y)
                        val ay = mapping.rawY(0, y)
                        val bx = mapping.rawX(mapping.width - 1, y + rows - 1)
                        val by = mapping.rawY(mapping.width - 1, y + rows - 1)
                        val region = Rect(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx) + 1, maxOf(ay, by) + 1)
                        val bitmap = decoder.decodeRegion(region, options)
                            ?: throw IOException("Could not decode an original image stripe")
                        try {
                            if (bitmap.width != region.width() || bitmap.height != region.height()) {
                                throw IOException("Region decoder unexpectedly rescaled a stripe")
                            }
                            // getPixels returns unpremultiplied sRGB ARGB8 values.
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            for (localY in 0 until rows) {
                                job.ensureActive()
                                rowFloats.position(0)
                                val outputY = y + localY
                                for (x in 0 until mapping.width) {
                                    val rawX = mapping.rawX(x, outputY) - region.left
                                    val rawY = mapping.rawY(x, outputY) - region.top
                                    val argb = pixels[rawY * bitmap.width + rawX]
                                    val alpha = argb ushr 24
                                    val inverse = 255 - alpha
                                    // Same opaque-white policy as preview, before sRGB inverse transfer.
                                    val red = (((argb ushr 16) and 255) * alpha + 255 * inverse + 127) / 255
                                    val green = (((argb ushr 8) and 255) * alpha + 255 * inverse + 127) / 255
                                    val blue = ((argb and 255) * alpha + 255 * inverse + 127) / 255
                                    rowFloats.putFloat(LinearRgb.srgb8ToLinear[red])
                                    rowFloats.putFloat(LinearRgb.srgb8ToLinear[green])
                                    rowFloats.putFloat(LinearRgb.srgb8ToLinear[blue])
                                }
                                output.write(rowBytes)
                            }
                        } finally { bitmap.recycle() }
                        y += rows
                    }
                    job.ensureActive()
                    output.flush()
                    file.fd.sync()
                }
            }
            if (destination.length() != LinearRgb.byteCount(mapping.width, mapping.height)) {
                throw IOException("Incomplete original RGB spool")
            }
            job.ensureActive()
            return PhotoLimits.Dimensions(mapping.width, mapping.height)
        } finally { decoder.recycle() }
    }
}
