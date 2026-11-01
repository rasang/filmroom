package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.LinearRgbPng
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow

/**
 * CAPABILITY PROBE ONLY. Confirms why Robolectric4.14.1 cannot validate the
 * production region-spool path: its region shadow returns a blank bitmap.
 * This is not an Android device test or a passing full-resolution acceptance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighResolutionCodecCapabilityHostTest {
    @Test fun nativeImageDecoderReadsRealPixelsButRegionDecoderRemainsBlankStub() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("synthetic-codec-probe-", ".png", context.cacheDir)
        val floats = ByteBuffer.allocate(4 * 3 * 12).order(ByteOrder.LITTLE_ENDIAN)
        repeat(12) { floats.putFloat(LinearRgb.srgb8ToLinear[200]); floats.putFloat(LinearRgb.srgb8ToLinear[70]); floats.putFloat(LinearRgb.srgb8ToLinear[20]) }
        try {
            file.outputStream().use { LinearRgbPng.encode(ByteArrayInputStream(floats.array()), 4, 3, it) }
            val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
            try { assertEquals(Color.rgb(200, 70, 20), image.getPixel(2, 1)) } finally { image.recycle() }
            @Suppress("DEPRECATION")
            val regionDecoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)!!
            try {
                assertEquals("org.robolectric.shadows.ShadowBitmapRegionDecoder", Shadow.extract<Any>(regionDecoder).javaClass.name)
                val region = regionDecoder.decodeRegion(Rect(1, 1, 3, 3), BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })!!
                try {
                    assertEquals("Known host limitation: source pixels are NOT decoded", 0, region.getPixel(0, 0))
                    assertNotEquals(Color.rgb(200, 70, 20), region.getPixel(0, 0))
                } finally { region.recycle() }
            } finally { regionDecoder.recycle() }
        } finally { file.delete() }
    }
}
