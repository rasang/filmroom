package dev.filmroom.presets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import dev.filmroom.R
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

internal object PresetQrImage {
    const val PIXELS_PER_MODULE = 8
    // Whole pixels per module prevent uneven downscaling from damaging dense on-screen codes.
    fun displayWidth(bitmapWidth: Int, availablePixels: Int, density: Float): Int {
        val modules = (bitmapWidth / PIXELS_PER_MODULE).coerceAtLeast(1)
        val available = availablePixels.coerceAtLeast(1)
        val preferred = minOf(available, maxOf((260 * density + .5f).toInt(), modules * 3))
        return ((preferred / modules).coerceAtLeast(1) * modules).coerceAtMost(available)
    }

    // H correction reserves headroom for the bounded real app icon. QR pixels stay unfiltered.
    fun render(context: Context, text: String): Bitmap {
        require(text.length <= PresetQrCodec.MAX_TEXT)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 4))
        val scale = PIXELS_PER_MODULE
        val size = matrix.width * scale
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        val modules = matrix.width - 8 // quiet zone is never covered
        val canvas = Canvas(bitmap)
        val icon = checkNotNull(context.resources.getDrawableForDensity(R.mipmap.ic_launcher, 640, context.theme))
        try {
            for (patch in logoPatchModules(modules) downTo 3 step 2) for (mask in -1..7) {
                // Some dense masks interact poorly with a central icon. Try another valid mask
                // before making the icon smaller; no settings or correction bytes are removed.
                val candidate = if (mask < 0) matrix else QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 4, EncodeHintType.QR_MASK_PATTERN to mask))
                for (index in pixels.indices) pixels[index] = if (candidate[index % size / scale, index / size / scale]) Color.BLACK else Color.WHITE
                bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
                val start = (matrix.width - patch) / 2 * scale
                canvas.drawRect(start.toFloat(), start.toFloat(), (start + patch * scale).toFloat(),
                    (start + patch * scale).toFloat(), Paint().apply { color = Color.WHITE })
                // Shipping launcher icon, not a recreation. One white module surrounds it.
                icon.setBounds(start + scale, start + scale, start + (patch - 1) * scale, start + (patch - 1) * scale)
                icon.draw(canvas)
                // H is not a promise that any occlusion works. Never share a known unreadable code.
                if (runCatching { decode(bitmap) }.getOrNull() == text) return bitmap
            }
            throw PresetQrCodec.Failure(PresetQrCodec.Problem.INVALID)
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }

    // Odd size and module-aligned edges. At most 17% of the code side / 2.9% area.
    internal fun logoPatchModules(modules: Int): Int = minOf(11, (modules * .17).toInt()).let { if (it % 2 == 0) it - 1 else it }.coerceAtLeast(3)

    fun read(context: Context, uri: Uri, log: RecipeLog? = null): String {
        log?.at(dev.filmroom.diagnostics.RecipeStep.IMAGE_READ)
        require(uri.scheme == "content")
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000 && bounds.outWidth.toLong() * bounds.outHeight <= 80_000_000)
        var sample = 1
        while (bounds.outWidth / sample > 1536 || bounds.outHeight / sample > 1536) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IllegalArgumentException()
        return try {
            log?.dimensions(bitmap.width, bitmap.height)
            log?.at(dev.filmroom.diagnostics.RecipeStep.QR_DECODE)
            decode(bitmap)
        } finally { bitmap.recycle() }
    }

    fun decode(bitmap: Bitmap): String {
        require(bitmap.width.toLong() * bitmap.height <= 2048L * 2048L)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        // Keep adaptive thresholding for uneven lighting. On dense, antialiased perspective
        // images its local thresholds can hide a finder pattern; a global threshold can
        // recover it from the same pixels. At most four attempts, with no resizing or blur.
        fun readSource(luminance: LuminanceSource) =
            try { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance)), hints) }
            catch (_: com.google.zxing.ReaderException) {
                QRCodeReader().decode(BinaryBitmap(GlobalHistogramBinarizer(luminance)), hints)
            }
        val result = try { readSource(source) }
            catch (_: com.google.zxing.ReaderException) { readSource(source.invert()) }
        require(result.text.length <= PresetQrCodec.MAX_TEXT)
        return result.text
    }
}
