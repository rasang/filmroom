package dev.filmroom.io

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ColorSpace
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** API 29 native bitmap contract isolated from the SDK 34/35 Gainmap test class.
 * Host SDR policy only, not device HEVC decoding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeicSdrLegacyHostTest {
    private fun base():Bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888,false,
        ColorSpace.get(ColorSpace.Named.SRGB)).apply {
        val colors=IntArray(width*height){i->
            val x=i%width;val y=i/width
            (255 shl 24) or ((16+x*220/95) shl 16) or ((12+y*224/63) shl 8) or (24+(x+3*y)%180)
        }
        setPixels(colors,0,width,0,0,width,height)
    }
    private fun pixels(bitmap:Bitmap)=IntArray(bitmap.width*bitmap.height).also{
        bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    }

    @Test
    @Config(sdk=[29])
    fun olderPlatformPlainSdrPreparationDoesNotTouchPixelsOrRequireGainmapApi() {
        val bitmap=base();val before=pixels(bitmap)
        try {
            HeicSdrBasePolicy.prepare(bitmap)
            assertArrayEquals(before,pixels(bitmap))
            assertEquals(Bitmap.Config.ARGB_8888,bitmap.config)
            assertTrue(bitmap.colorSpace!!.isSrgb)
        } finally {bitmap.recycle()}
    }

}
