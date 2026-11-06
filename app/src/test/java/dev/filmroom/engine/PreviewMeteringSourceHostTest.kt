package dev.filmroom.engine

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import dev.filmroom.io.DetailSourceFactory
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.PhotoSource
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real source, native host ImageDecoder/Bitmap, filesystem, cache and admission.
 * Only meter JNI is a recording double: these tests do NOT prove native AE numerical output.
 * No Assume/skip/catch-and-return for unavailable decoder/JNI: unsupported host paths FAIL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewMeteringSourceHostTest {
    private val memory={NativeMemorySnapshot(8L*1024*1024*1024,128L*1024*1024,false)}
    private class Root:AutoCloseable {
        val dir=Files.createTempDirectory("metering-source-host-").toFile().canonicalFile
        val sources=File(dir,"cache/photo-sources").apply{mkdirs()}
        val manifest=File(dir,"files/engine-data/.prepared-v2").apply{parentFile!!.mkdirs();writeText("test-assets-v1")}
        override fun close(){dir.deleteRecursively()}
    }
    private data class Frame(val width:Int,val height:Int,val pixels:FloatArray) {
        fun rgb(x:Int,y:Int)=pixels.copyOfRange((y*width+x)*3,(y*width+x)*3+3)
    }
    private open class Meter:PreviewMeteringNative {
        val frames=mutableListOf<Frame>()
        var capabilitiesCalls=0
        override fun capabilities():String {
            capabilitiesCalls++
            return JSONObject().put("sharedPreviewMeteringAbi",1).put("sharedPreviewMeteringAlgorithm",PreviewMeteringResult.ALGORITHM).toString()
        }
        override fun measure(input:ByteBuffer,width:Int,height:Int,method:String):String {
            assertTrue(input.isDirect);assertTrue(input.isReadOnly);assertEquals(0,input.position());assertEquals(ByteOrder.nativeOrder(),input.order())
            assertTrue(width in 1..256 && height in 1..256);assertEquals(width*height*12,input.remaining())
            val values=FloatArray(width*height*3){input.float};assertTrue(values.all{it.isFinite()})
            frames+=Frame(width,height,values)
            return JSONObject().put("ok",true).put("algorithm",PreviewMeteringResult.ALGORITHM).put("method",method).put("ev",0.75).toString()
        }
    }
    private fun bitmapFile(root:Root,name:String,width:Int,height:Int,format:Bitmap.CompressFormat,pixel:(Int,Int)->Int):File {
        val file=File(root.sources,name);val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            val pixels=IntArray(width*height){i->pixel(i%width,i/width)};bitmap.setPixels(pixels,0,width,0,0,width,height)
            file.outputStream().use{assertTrue("Host encoder must actually produce fixture",bitmap.compress(format,100,it))}
            assertTrue(file.length()>0)
        }finally{bitmap.recycle()}
        return file
    }
    private fun photo(file:File,w:Int,h:Int,mime:String="image/png")=PhotoSource(file,file.name,mime,w,h,file.length())
    private suspend fun resolve(root:Root,source:PhotoSource,meter:Meter,method:String="average")=
        PreviewMetering.resolveWithNative(source,root.dir,method,memory,meter)

    @Test fun actualExifRotatedJpegMetersWholeOrientedFrame()=runBlocking {
        Root().use { root ->
            val file=bitmapFile(root,"rotated.jpg",80,48,Bitmap.CompressFormat.JPEG){x,y->
                if(y<24){if(x<40)Color.RED else Color.GREEN}else{if(x<40)Color.BLUE else Color.WHITE}
            }
            ExifInterface(file.absolutePath).apply{setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString());saveAttributes()}
            val source=photo(file,48,80,"image/jpeg");val meter=Meter();val result=resolve(root,source,meter)
            assertEquals(1,meter.frames.size);val frame=meter.frames.single();assertEquals(48,frame.width);assertEquals(80,frame.height)
            // Clockwise rotation: TL=original BL blue, TR=original TL red,
            // BL=original BR white, BR=original TR green. Samples avoid JPEG edges.
            val expected=listOf(floatArrayOf(0f,0f,1f),floatArrayOf(1f,0f,0f),floatArrayOf(1f,1f,1f),floatArrayOf(0f,1f,0f))
            val points=listOf(12 to 20,36 to 20,12 to 60,36 to 60)
            points.zip(expected).forEach{(point,rgb)->assertArrayEquals(rgb,frame.rgb(point.first,point.second),0.08f)}
            assertEquals(source.previewSourceRevision(),result.sourceRevision)
            assertTrue(result.mappingRevision.contains("image/jpeg:48x80:linear=false:snapshot="))
            assertTrue(result.mappingRevision.contains("image-decoder-srgb-white-v1"))
            assertFalse("Metering must not create original/grid float spools",File(root.dir,"cache/detail-preview").exists())
            assertSame(result,resolve(root,source,meter));assertEquals(1,meter.frames.size)
        }
    }

    @Test fun actualTransparentPngCompositesWhiteBeforeLinearization()=runBlocking {
        Root().use { root ->
            val colors=intArrayOf(Color.TRANSPARENT,Color.argb(128,255,0,0),Color.BLUE,Color.rgb(128,128,128))
            val file=bitmapFile(root,"alpha.png",4,1,Bitmap.CompressFormat.PNG){x,_->colors[x]}
            val meter=Meter();resolve(root,photo(file,4,1),meter);val frame=meter.frames.single()
            assertArrayEquals(floatArrayOf(1f,1f,1f),frame.rgb(0,0),0.00001f)
            assertArrayEquals(floatArrayOf(1f,LinearRgb.srgb8ToLinear[127],LinearRgb.srgb8ToLinear[127]),frame.rgb(1,0),0.01f)
            assertArrayEquals(floatArrayOf(0f,0f,1f),frame.rgb(2,0),0.00001f)
            val gray=LinearRgb.srgb8ToLinear[128];assertArrayEquals(floatArrayOf(gray,gray,gray),frame.rgb(3,0),0.00001f)
            assertTrue("sRGB8 must be linearized, not passed as 128/255",frame.rgb(3,0)[0]<0.25f)
        }
    }

    @Test fun realSourceCacheKeysInvalidateOnOwnerMethodStatAndAssetRevision()=runBlocking {
        Root().use { root ->
            val file=bitmapFile(root,"source.png",8,4,Bitmap.CompressFormat.PNG){_,_->Color.GRAY}
            val a=photo(file,8,4);val b=photo(file,8,4);val meter=Meter()
            val first=resolve(root,a,meter);repeat(4){assertSame(first,resolve(root,a,meter))};assertEquals(1,meter.frames.size)
            val other=resolve(root,b,meter);assertNotEquals(first.sourceRevision,other.sourceRevision);assertNotEquals(first.owner,other.owner);assertEquals(2,meter.frames.size)
            val partial=resolve(root,a,meter,"partial");assertEquals(first.sourceRevision,partial.sourceRevision);assertEquals("partial",partial.method);assertEquals(3,meter.frames.size)
            root.manifest.writeText("test-assets-v2-different-content")
            val dataChanged=resolve(root,a,meter);assertNotEquals(first.dataRevision,dataChanged.dataRevision);assertEquals(4,meter.frames.size)
            assertTrue(file.setLastModified(file.lastModified()+2000))
            val statChanged=resolve(root,a,meter);assertNotEquals(first.sourceRevision,statChanged.sourceRevision);assertEquals(5,meter.frames.size)
            assertEquals(first.owner,statChanged.owner)
        }
    }

    @Test fun rawRetainsHdrAndNegativeValuesAndBindsDetailSnapshotWithoutDisplayDecode()=runBlocking {
        Root().use { root ->
            val original=File(root.sources,"original.raw").apply{writeText("not a display JPEG and intentionally not decodable")}
            val linear=File(root.sources,"linear.rgb");val bytes=ByteBuffer.allocate(2*2*12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(4){bytes.putFloat(-0.125f).putFloat(4f).putFloat(0.25f)};linear.writeBytes(bytes.array())
            val source=PhotoSource(original,"raw","image/x-adobe-dng",2,2,original.length(),linearFile=linear)
            val meter=Meter();val result=resolve(root,source,meter)
            repeat(4){i->assertArrayEquals(floatArrayOf(-0.125f,4f,0.25f),meter.frames.single().rgb(i%2,i/2),0f)}
            assertTrue(result.mappingRevision.contains("linear=true"));assertTrue(result.mappingRevision.contains("linear-bilinear-center-v1"))
            DetailSourceFactory(root.dir,memory).prepare(source,2).use{detail->
                assertEquals(result.mappingRevision.substringBefore(":meter-grid:"),detail.mappingRevision.substringBefore(":bilinear-center-v1:"))
                assertNotEquals("Process token must not pretend to be content SHA256",result.sourceRevision,detail.sourceRevision)
            }
            assertArrayEquals(bytes.array(),linear.readBytes());assertTrue(original.exists())
        }
    }

    @Test fun closeDuringNativeBoundaryKeepsLeaseThenRejectsLatePublication()=runBlocking {
        Root().use { root ->
            val file=bitmapFile(root,"close.png",4,2,Bitmap.CompressFormat.PNG){_,_->Color.RED};val source=photo(file,4,2)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val meter=object:Meter(){override fun measure(input:ByteBuffer,width:Int,height:Int,method:String):String{
                entered.countDown();check(release.await(10,TimeUnit.SECONDS));return super.measure(input,width,height,method)
            }}
            val late=async(Dispatchers.Default){runCatching{resolve(root,source,meter)}}
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));source.discardWhenUnused()
                assertTrue("In-flight source lease must delay deletion",file.exists())
            }finally{release.countDown()}
            assertTrue(late.await().exceptionOrNull() is IllegalStateException)
            assertFalse("Last lease must release after rejected late result",file.exists())
            try{resolve(root,source,meter);fail("Discarded owner accepted another reader")}catch(_:IllegalStateException){}
            assertEquals(1,meter.frames.size);assertTrue(NativeWorkReservations.detailRetryIdle())
        }
    }

    @Test fun oldAbiOrMissingJniCannotBecomeSuccessfulMetering()=runBlocking {
        Root().use { root ->
            val file=bitmapFile(root,"abi.png",4,2,Bitmap.CompressFormat.PNG){_,_->Color.RED}
            for(missing in listOf(false,true)) {
                val source=photo(file,4,2);val meter=object:Meter(){override fun capabilities():String{
                    if(missing)throw UnsatisfiedLinkError("missing test symbol")
                    return JSONObject().put("bitmapPreview",true).put("nativeAbiRevision",2).toString()
                }}
                try{resolve(root,source,meter);fail("Missing shared-meter ABI/symbol was accepted")}
                catch(e:EngineException){assertEquals(EngineFailure.UNAVAILABLE,e.failure)}
                assertEquals(0,meter.frames.size);assertTrue(file.exists())
            }
        }
    }

    @Test fun wrongOrientationDimensionsAndBudgetFailBeforeMeterAndAllowRetry()=runBlocking {
        Root().use { root ->
            val file=bitmapFile(root,"dimensions.png",8,4,Bitmap.CompressFormat.PNG){_,_->Color.BLUE};val meter=Meter()
            try{resolve(root,photo(file,4,8),meter);fail("Changed orientation accepted")}catch(_:IllegalStateException){}
            assertTrue(meter.frames.isEmpty())
            val source=photo(file,8,4)
            try{PreviewMetering.resolveWithNative(source,root.dir,"average",{NativeMemorySnapshot(0,0,true)},meter);fail("Budget denied but work executed")}
            catch(_:NativeWorkUnavailable){}
            assertTrue(meter.frames.isEmpty());assertTrue(file.exists())
            resolve(root,source,meter);assertEquals(1,meter.frames.size)
        }
    }
}
