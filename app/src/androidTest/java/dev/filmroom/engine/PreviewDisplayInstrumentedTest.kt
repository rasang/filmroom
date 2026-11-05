package dev.filmroom.engine

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.runBlocking
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only declaration; native symbol is bounded and intentionally present in the library. */
internal object NativePreviewDisplayTestBridge {
    init { check(NativeFilmBridge.loadError==null) }
    external fun pack(input:ByteBuffer,output:ByteBuffer,width:Int,height:Int,cancelAtCheck:Int):String
}

@RunWith(AndroidJUnit4::class)
class PreviewDisplayInstrumentedTest {
    private fun buffer(bytes:Int)=ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    private fun input(values:FloatArray)=buffer(values.size*4).apply { values.forEach { putFloat(it) }; rewind() }
    // Intentionally retain the actual removed Kotlin formula as the JVM oracle.
    private fun legacy(v:Float):Int {
        check(v.isFinite())
        val x=v.coerceIn(0f,1f).toDouble()
        return ((if(x<=0.0031308)x*12.92 else 1.055*x.pow(1.0/2.4)-0.055)*255.0+0.5).toInt().coerceIn(0,255)
    }
    private fun pack(values:FloatArray,width:Int,height:Int,cancel:Int=0):Pair<JSONObject,ByteBuffer> {
        val out=buffer(width*height*4)
        for(i in 0 until out.capacity())out.put(i,77.toByte())
        val result=JSONObject(NativePreviewDisplayTestBridge.pack(input(values),out,width,height,cancel))
        return result to out
    }

    @Test fun nativeBytesMatchJvmAtEveryRoundingThresholdAndDenseSample() {
        val samples=mutableListOf(-Float.MAX_VALUE,-1f,-0f,0f,Float.MIN_VALUE,1f,Float.MAX_VALUE)
        fun neighbors(v:Float) { for(offset in -8..8)samples.add(Float.fromBits(v.toRawBits()+offset)) }
        for(code in 0 until 255) {
            val s=(code+0.5)/255.0
            neighbors((if(s<=0.04045)s/12.92 else ((s+0.055)/1.055).pow(2.4)).toFloat())
        }
        neighbors(0.0031308f);neighbors(1f)
        for(i in 0..65535)samples.add(i/65535f)
        // Batch into bounded, one-column images so every sample is also a row.
        for(batch in samples.chunked(512)) {
            val pixels=FloatArray(batch.size*3) { batch[it/3] }
            val (result,out)=pack(pixels,1,batch.size)
            assertTrue(result.toString(),result.getBoolean("outputValid"))
            batch.forEachIndexed { i,v ->
                for(channel in 0..2)assertEquals("float bits=${v.toRawBits()} channel=$channel",legacy(v),out.get(i*4+channel).toInt() and 255)
                assertEquals(255,out.get(i*4+3).toInt() and 255)
            }
        }
    }

    @Test fun primaryColorsAlphaAndOddWidthRowsSurviveBitmapCopy() {
        for(width in listOf(1,3,5,17,257)) {
            val height=3
            val pixels=FloatArray(width*height*3)
            val expected=IntArray(width*height)
            for(i in expected.indices) {
                val c=i%3;pixels[i*3+c]=1f
                expected[i]=when(c){0->Color.RED;1->Color.GREEN;else->Color.BLUE}
            }
            val (result,out)=pack(pixels,width,height)
            assertTrue(result.toString(),result.getBoolean("outputValid"))
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                assertEquals("Packed row contract must hold or production rejects",width*4,bitmap.rowBytes)
                PreviewDisplay.copyRgba8(bitmap,out) {}
                val actual=IntArray(expected.size)
                bitmap.getPixels(actual,0,width,0,0,width,height)
                assertArrayEquals(expected,actual)
                for(color in actual)assertEquals(255,Color.alpha(color))
            } finally {bitmap.recycle()}
        }
    }

    @Test fun nonfiniteIsInvalidWithoutCommittingAnyBytes() {
        for(invalid in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            val (result,out)=pack(floatArrayOf(1f,0f,0f,0f,invalid,1f),1,2)
            assertFalse(result.getBoolean("outputValid"))
            assertEquals("NONFINITE_OUTPUT",result.getString("code"))
            for(i in 0 until out.capacity())assertEquals(77,out.get(i).toInt())
        }
    }

    @Test fun nativeCancellationIncludingAfterLastWriteNeverPublishesSuccess() {
        for(check in 1..6) {
            val (result,_)=pack(FloatArray(6){1f},1,2,check)
            assertFalse("checkpoint=$check",result.getBoolean("outputValid"))
            assertEquals("CANCELLED",result.getString("code"))
        }
    }

    @Test fun copyCancellationBeforeAndAfterMemcpyPreventsCapture() {
        for(stop in 1..3) {
            val (_,out)=pack(floatArrayOf(1f,0f,0f),1,1)
            val bitmap=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
            var checks=0;var captured=false
            try {
                try {
                    PreviewDisplay.copyRgba8(bitmap,out) { if(++checks==stop)throw CancellationException("test") }
                    captured=true
                    fail("Expected cancellation")
                } catch(_:CancellationException) {}
                assertFalse(captured)
                if(stop<3)assertEquals(Color.TRANSPARENT,bitmap.getPixel(0,0))
            } finally {bitmap.recycle()}
        }
    }

    @Test fun invalidCapacityReadonlyAndAliasedRangesAreRejected() {
        val source=input(floatArrayOf(1f,0f,0f))
        val tiny=buffer(3)
        assertEquals("INVALID_BUFFER",JSONObject(NativePreviewDisplayTestBridge.pack(source,tiny,1,1,0)).getString("code"))
        val readOnly=buffer(4).asReadOnlyBuffer().order(ByteOrder.nativeOrder())
        assertEquals("INVALID_BUFFER",JSONObject(NativePreviewDisplayTestBridge.pack(source,readOnly,1,1,0)).getString("code"))
        val alias=source.duplicate().order(ByteOrder.nativeOrder())
        assertEquals("INVALID_BUFFER",JSONObject(NativePreviewDisplayTestBridge.pack(source,alias,1,1,0)).getString("code"))
        assertEquals("INVALID_DIMENSIONS",JSONObject(NativePreviewDisplayTestBridge.pack(source,buffer(4),0,1,0)).getString("code"))
    }

    @Test fun unequalAdjacentSlicesSucceedInBothOrdersAndOverlapsFail() {
        fun slice(storage:ByteBuffer,start:Int,length:Int)=storage.duplicate().apply {
            position(start);limit(start+length)
        }.slice().order(ByteOrder.nativeOrder())
        for(outputFirst in listOf(false,true)) {
            val storage=buffer(16)
            val source=slice(storage,if(outputFirst)4 else 0,12)
            val out=slice(storage,if(outputFirst)0 else 12,4)
            source.putFloat(1f).putFloat(0f).putFloat(0f).rewind()
            val result=JSONObject(NativePreviewDisplayTestBridge.pack(source,out,1,1,0))
            assertTrue(result.toString(),result.getBoolean("outputValid"))
            assertEquals(255,out.get(0).toInt() and 255)
            assertEquals(0,out.get(1).toInt())
            assertEquals(0,out.get(2).toInt())
            assertEquals(255,out.get(3).toInt() and 255)
        }
        val storage=buffer(16)
        val result=JSONObject(NativePreviewDisplayTestBridge.pack(slice(storage,0,12),slice(storage,8,4),1,1,0))
        assertFalse(result.getBoolean("outputValid"))
        assertEquals("INVALID_BUFFER",result.getString("code"))
    }

    @Test fun invalidPositionLimitByteOrderAndHeapBuffersAreRejected() {
        val wrongOrder=if(ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN)ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        fun reject(source:ByteBuffer,out:ByteBuffer) {
            val result=JSONObject(NativePreviewDisplayTestBridge.pack(source,out,1,1,0))
            assertFalse(result.getBoolean("outputValid"))
            assertEquals("INVALID_BUFFER",result.getString("code"))
        }
        reject(buffer(12).apply {position(4)},buffer(4))
        reject(buffer(12),buffer(8).apply {position(4)})
        reject(buffer(12).apply {limit(8)},buffer(4))
        reject(buffer(12),buffer(4).apply {limit(3)})
        reject(buffer(12).order(wrongOrder),buffer(4))
        reject(buffer(12),buffer(4).order(wrongOrder))
        reject(ByteBuffer.allocate(12).order(ByteOrder.nativeOrder()),buffer(4))
        reject(buffer(12),ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()))
    }

    @Test fun realNormalPreviewPackedBytesMatchRetainedFloatApi() = runBlocking {
        // Reuse the existing asset-install fixture pattern, including packed LUT
        // restoration, rather than creating a divergent test-only data installer.
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val engine=FilmEngine(context)
        val method=FilmEngine::class.java.getDeclaredMethod("copyData",Continuation::class.java).apply {isAccessible=true}
        val root=suspendCoroutine<File> { continuation ->
            val result=method.invoke(engine,continuation)
            if(result!==COROUTINE_SUSPENDED)continuation.resume(result as File)
        }
        val handle=NativeFilmBridge.nativeCreate(root.absolutePath)
        assertTrue("Native engine must load real bundled assets",handle!=0L)
        try {
            val width=7;val height=5;val count=width*height
            val values=FloatArray(count*3) { ((it*17)%101)/100f }
            val source=input(values)
            val floats=buffer(count*12);val packed=buffer(count*4)
            val params=JSONObject().put("useGpu",false).put("autoExposure",false)
                .put("grainAmount",0).put("scanGlarePercent",0).put("memoryBudgetMiB",256).toString()
            val legacyResult=JSONObject(NativeFilmBridge.nativeRender(handle,source,floats,width,height,params))
            assertTrue(legacyResult.toString(),legacyResult.getBoolean("outputValid"))
            val packedResult=JSONObject(NativeFilmBridge.nativeRenderRgba8(handle,source,packed,width,height,params))
            assertTrue(packedResult.toString(),packedResult.getBoolean("outputValid"))
            assertEquals("srgb-rgba8",packedResult.getString("outputFormat"))
            assertTrue(packedResult.getDouble("displayEncodeMs")>=0.0)
            assertTrue(packedResult.getDouble("elapsedMs")>=0.0)
            assertFalse(packedResult.getJSONArray("warnings").toString().contains("Float32 input/output ABI"))
            for(i in 0 until count) {
                for(channel in 0..2)assertEquals("pixel=$i channel=$channel",legacy(floats.float),packed.get(i*4+channel).toInt() and 255)
                assertEquals(255,packed.get(i*4+3).toInt() and 255)
            }
        } finally {NativeFilmBridge.nativeDestroy(handle)}
    }

}
