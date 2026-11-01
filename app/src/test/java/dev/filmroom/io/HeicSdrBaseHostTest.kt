package dev.filmroom.io

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import dev.filmroom.engine.NativeMemorySnapshot
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native host bitmap/gainmap semantics, NOT Android HEVC/device decoding or HDR rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34,35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeicSdrBaseHostTest {
    private val plentiful={NativeMemorySnapshot(8L*1024*1024*1024,128L*1024*1024,false)}
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
    private fun attachGainmap(bitmap:Bitmap):Bitmap {
        val contents=Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888).apply{
            val colors=IntArray(width*height){i->(255 shl 24) or (((i*17)%256) shl 16) or (((i*29)%256) shl 8) or ((i*43)%256)}
            setPixels(colors,0,width,0,0,width,height)
        }
        bitmap.setGainmap(Gainmap(contents).apply{
            setRatioMin(1f,1f,1f);setRatioMax(4f,8f,2f)
            setGamma(1.1f,.8f,1.3f)
            setEpsilonSdr(1f/64,1f/32,1f/128);setEpsilonHdr(1f/128,1f/64,1f/256)
            setDisplayRatioForFullHdr(8f);setMinDisplayRatioForHdrTransition(1f)
        })
        return contents
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

    @Test fun removingNontrivialGainmapLeavesEverySdrBasePixelExactlyUnchanged() {
        val bitmap=base().let { mutable ->
            checkNotNull(mutable.copy(Bitmap.Config.ARGB_8888,false)).also { mutable.recycle() }
        }
        val before=pixels(bitmap);val gainmap=attachGainmap(bitmap)
        try {
            assertFalse("Production ImageDecoder bitmap is immutable",bitmap.isMutable)
            assertTrue(bitmap.hasGainmap());assertNotNull(bitmap.gainmap)
            assertArrayEquals(floatArrayOf(4f,8f,2f),bitmap.gainmap!!.ratioMax,0f)
            assertArrayEquals("Attaching metadata must not rewrite stored SDR pixels",before,pixels(bitmap))
            HeicSdrBasePolicy.prepare(bitmap)
            assertFalse(bitmap.hasGainmap());assertNull(bitmap.gainmap)
            assertEquals(Bitmap.Config.ARGB_8888,bitmap.config);assertTrue(bitmap.colorSpace!!.isSrgb)
            assertEquals(96,bitmap.width);assertEquals(64,bitmap.height)
            assertArrayEquals("No gain was applied or tonemapped",before,pixels(bitmap))
            HeicSdrBasePolicy.prepare(bitmap) // Idempotent on already SDR-only bitmap.
            assertArrayEquals(before,pixels(bitmap))
        } finally {bitmap.recycle();gainmap.recycle()}
    }

    @Test fun onlyKnownSdrToHdrDirectionMayHaveAuxiliaryDiscarded() {
        HeicSdrBasePolicy.requireSdrDirection(0)
        for(direction in listOf<Int?>(null,1,-1,2,Int.MIN_VALUE,Int.MAX_VALUE)) {
            try{HeicSdrBasePolicy.requireSdrDirection(direction);fail("Unknown or HDR-base direction accepted: $direction")}
            catch(e:PhotoException){assertEquals(PhotoException.Reason.UNSUPPORTED,e.reason)}
        }
    }

    @Test fun wrongColorSpaceOrPixelConfigurationDoesNotBecomeSdrByRemovingMetadata() {
        val p3=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        val f16=Bitmap.createBitmap(4,3,Bitmap.Config.RGBA_F16,false,ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
        val rgb565=Bitmap.createBitmap(4,3,Bitmap.Config.RGB_565)
        try {
            for(bitmap in listOf(p3,f16,rgb565)) {
                try{HeicSdrBasePolicy.prepare(bitmap);fail("Unsafe base accepted: ${bitmap.config}/${bitmap.colorSpace}")}
                catch(e:PhotoException){assertEquals(PhotoException.Reason.UNSUPPORTED,e.reason)}
            }
        } finally {p3.recycle();f16.recycle();rgb565.recycle()}
    }

    private class BitmapBackend(private val bitmap:Bitmap,private val failRow:Boolean=false):HeicBackend {
        var calls=0;var closes=0
        override fun supported()=true
        override fun decode(file:File,info:HeicContainer.Info,job:CoroutineContext):HeicPixels {
            calls++;HeicSdrBasePolicy.prepare(bitmap)
            check(!bitmap.hasGainmap())
            return object:HeicPixels {
                override val width=bitmap.width;override val height=bitmap.height
                override fun readRow(y:Int,argb:IntArray){
                    if(failRow&&y==1)throw IOException("synthetic row failure")
                    bitmap.getPixels(argb,0,width,0,y,width,1)
                }
                override fun close(){closes++;bitmap.recycle()}
            }
        }
    }

    @Test fun canonicalSpoolMatchesPlainSdrBaseByteForByteWithoutApplyingGain()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("heic-sdr-base-").toFile()
        val withGain=base();val gainContents=attachGainmap(withGain);val plain=base()
        val before=pixels(plain)
        val gainBackend=BitmapBackend(withGain);val plainBackend=BitmapBackend(plain)
        try {
            // The backend is explicitly synthetic. Container admission/device decoding is not
            // exercised by this bitmap→canonical-spool test and is covered separately.
            val source=File(root,"source.heic").apply{writeBytes(byteArrayOf(7,11,19))}
            val info=HeicContainer.Info(96,64,0,6144,6144)
            val gainOutput=File(root,"from-gainmap.rgb");val plainOutput=File(root,"plain.rgb")
            HeicImport(plentiful,gainBackend).write(source,info,gainOutput,coroutineContext)
            HeicImport(plentiful,plainBackend).write(source,info,plainOutput,coroutineContext)
            assertEquals(96L*64*12,gainOutput.length())
            assertArrayEquals(plainOutput.readBytes(),gainOutput.readBytes())
            val expected=ByteBuffer.allocate(96*64*12).order(ByteOrder.LITTLE_ENDIAN)
            for(color in before)for(shift in listOf(16,8,0))expected.putFloat(LinearRgb.srgb8ToLinear[(color ushr shift) and 255])
            assertArrayEquals("Canonical float values are SDR base, not HDR-expanded pixels",expected.array(),gainOutput.readBytes())
            assertEquals(1,gainBackend.calls);assertEquals(1,gainBackend.closes)
            assertEquals(1,plainBackend.calls);assertEquals(1,plainBackend.closes)
            assertTrue(withGain.isRecycled);assertTrue(plain.isRecycled)
            assertArrayEquals(byteArrayOf(7,11,19),source.readBytes())
            assertFalse(root.listFiles().orEmpty().any{it.extension=="part"})
            assertTrue(gainOutput.delete());assertTrue(plainOutput.delete())
        } finally {
            if(!withGain.isRecycled)withGain.recycle();if(!plain.isRecycled)plain.recycle()
            gainContents.recycle();root.deleteRecursively()
        }
    }

    @Test fun rowFailureAfterStrippingGainmapReleasesBitmapAndDeletesPartialSpool()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("heic-sdr-failure-").toFile()
        val bitmap=base();val contents=attachGainmap(bitmap);val backend=BitmapBackend(bitmap,failRow=true)
        try {
            val source=File(root,"source.heic").apply{writeBytes(byteArrayOf(1,2,3))}
            val target=File(root,"incomplete.rgb")
            try{HeicImport(plentiful,backend).write(source,HeicContainer.Info(96,64,0,6144,6144),target,coroutineContext);fail()}
            catch(_:IOException){}
            assertFalse(target.exists());assertTrue(bitmap.isRecycled)
            assertEquals(1,backend.closes);assertArrayEquals(byteArrayOf(1,2,3),source.readBytes())
            assertEquals(setOf("source.heic"),root.listFiles().orEmpty().map{it.name}.toSet())
        } finally {if(!bitmap.isRecycled)bitmap.recycle();contents.recycle();root.deleteRecursively()}
    }
}
