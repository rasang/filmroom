package dev.filmroom

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.codec.*
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Real Linux codec + authored fixture. No Android device, film stock or MediaStore claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class BorderCodecIntegrationHostTest {
    @Test fun finalFloatSpoolEncodesRealJpegPngTiffWithOutputDimensionsAndPrecision()=runBlocking {
        assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        assertTrue(NativeCodecBridge.available)
        val app=ApplicationProvider.getApplicationContext<Application>()
        val jobs=File(app.filesDir,"export-jobs").apply{mkdirs()}
        val job=File(jobs,UUID.randomUUID().toString()).apply{mkdir()}
        val asset=object:BorderRaster {
            override val stock="fixture_only";override val version="authored-test-1"
            override val railRatio=.2;override val endRatio=.03;override val featherTotalAt3072=4.0
            override val residentBytes=12L
            override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb[0]=.8f;rgb[1]=.3f;rgb[2]=.1f}
        }
        try {
            val s=BorderSnapshot.fixed135(asset.stock,asset.version,96,64,composition=dev.filmroom.model.CompositionTransform(.6,.4,2.0),featherTotalAt3072=asset.featherTotalAt3072)
            val input=File(job,"input.rgb");val b=ByteBuffer.allocate(96*64*12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(96*64){b.putFloat(.1234567f);b.putFloat(.2345678f);b.putFloat(.3456789f)};input.writeBytes(b.array())
            val codecs=PhotoCodecs(app.cacheDir,memorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)},exportJobsRoot=jobs)
            for(space in OutputColorSpace.entries) {
                val framed=File(job,"film-result-${UUID.randomUUID()}.rgb")
                BorderCompositor.composite(input,framed,s,asset,space)
                for(format in listOf("JPEG","TIFF16","PNG")) {
                    val encoded=File(job,"codec-output-${UUID.randomUUID()}.${if(format=="JPEG")"jpg" else if(format=="TIFF16")"tif" else "png"}")
                    if(format=="PNG")framed.inputStream().use{source->encoded.outputStream().use{out->
                        LinearRgbPng.encode(source,s.outputWidth,s.outputHeight,out,colorSpace=space,
                            iccProfile=if(space==OutputColorSpace.SRGB)null else app.assets.open("color-profiles/${space.iccAsset}").use{it.readBytes()})
                    }}else codecs.encode(framed,encoded,s.outputWidth,s.outputHeight,format,95,space.id)
                    val captured=PhotoMetadata.fromJson(org.json.JSONObject(PhotoMetadata.fromAttributes(mapOf("Make" to "Authored fixture", "DateTimeOriginal" to "2001:02:03 04:05:06", "PhotographicSensitivity" to "400")).toJson()).put("sourceCreateDate","2001-02-03T04:05:06+09:00").toString())
                    val outFormat=when(format){"JPEG"->ExportFormat.JPEG;"TIFF16"->ExportFormat.TIFF16;else->ExportFormat.PNG}
                    PhotoMetadataContainer.inject(encoded,outFormat,captured.exifBytes(s.outputWidth,s.outputHeight,space.id,1793444462987L),1793444462987L)
                    assertEquals(captured.captureTimeMillis(),PhotoMetadata.read(encoded).captureTimeMillis())
                    val exif=androidx.exifinterface.media.ExifInterface(encoded.absolutePath)
                    assertEquals("2001:02:03 04:05:06",exif.getAttribute("DateTimeOriginal"))
                    assertEquals("+09:00",exif.getAttribute("OffsetTimeOriginal"))
                    assertEquals(400,exif.getAttributeInt("PhotographicSensitivity",0))
                    assertEquals(s.outputWidth,exif.getAttributeInt("PixelXDimension",0))
                    assertEquals(s.outputHeight,exif.getAttributeInt("PixelYDimension",0))
                    assertEquals(1,exif.getAttributeInt("Orientation",0))
                    val decoded=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java).invoke(null,encoded)
                    assertNotNull(decoded);assertEquals(s.outputWidth,decoded.javaClass.getMethod("getWidth").invoke(decoded));assertEquals(s.outputHeight,decoded.javaClass.getMethod("getHeight").invoke(decoded))
                    val bytes=encoded.readBytes()
                    if(format=="JPEG")assertTrue(String(bytes,Charsets.ISO_8859_1).contains("ICC_PROFILE"))
                    if(format=="TIFF16"){
                        val buffer=ByteBuffer.wrap(bytes).order(if(bytes[0]==73.toByte())ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                        val offset=buffer.getInt(4);val count=buffer.getShort(offset).toInt()and 65535
                        assertTrue((0 until count).any{(buffer.getShort(offset+2+it*12).toInt()and 65535)==34675})
                        val raster=decoded.javaClass.getMethod("getRaster").invoke(decoded)
                        val sample=raster.javaClass.getMethod("getSample",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).invoke(raster,s.left+40,s.top+30,0) as Int
                        val linear=.1234567
                        val transfer=if(space==OutputColorSpace.ADOBE_RGB)Math.pow(linear,256.0/563.0)else 1.055*Math.pow(linear,1.0/2.4)-.055
                        assertEquals(Math.round(transfer*65535).toInt(),sample)
                    }
                    encoded.delete()
                }
                framed.delete()
            }
            assertArrayEquals(b.array(),input.readBytes())
        }finally{job.deleteRecursively()}
    }
}
