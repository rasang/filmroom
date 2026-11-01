package dev.filmroom

import android.app.*
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.codec.NativeCodecBridge
import dev.filmroom.engine.*
import dev.filmroom.export.*
import dev.filmroom.io.*
import dev.filmroom.model.EditorState
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.CropSettings
import dev.filmroom.model.CropMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Opt-in real TIFF16 queue+metadata, fake film engine, immutable final crop and uncropped retry checkpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PlainCropQueueIntegrationHostTest {
    @Test fun restartUsesFrozenCropAndUncroppedCheckpointThenWritesCaptureMetadataAndDimensions()=runBlocking {
        assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null);assertTrue(NativeCodecBridge.available)
        val app=ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir,"export-jobs").deleteRecursively()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply{
            availMem=4L*1024*1024*1024;threshold=128L*1024*1024;lowMemory=false
        })
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val media=RealNativeExportQueueHostTest.Media();media.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,media)
        app.getSharedPreferences("filmroom-pending-exports",0).edit().clear().commit()
        val raw=File(app.cacheDir,"authored-border-queue.rgb")
        val bytes=ByteBuffer.allocate(96*64*12).order(ByteOrder.LITTLE_ENDIAN).apply{repeat(96*64*3){putFloat(.1234567f)}}.array()
        raw.writeBytes(bytes)
        var failOnce=true;var renders=0
        val processing=object:ExportProcessing {
            override suspend fun plan(width:Int,height:Int,parameters:EditorState):NativeExportPlan {
                assertEquals(96,width);assertEquals(64,height);val n=width.toLong()*height
                return NativeExportPlan(width,height,n*12,n*12,0,n*24)
            }
            override suspend fun render(input:File,width:Int,height:Int,parameters:EditorState,workspace:File,onProgress:(EngineProgress)->Unit):File {
                renders++;assertArrayEquals(bytes,input.readBytes());assertEquals(96,width);assertEquals(64,height)
                assertFalse(parameters.json("export",includePresentation=false).contains("filmBorder"))
                val checkpoint=File(workspace,"authored-checkpoint.rgb")
                if(!checkpoint.exists())checkpoint.writeBytes(bytes)else assertArrayEquals(bytes,checkpoint.readBytes())
                if(failOnce){failOnce=false;throw java.io.IOException("authored interruption after uncropped checkpoint")}
                return File(workspace,"film-result-${UUID.randomUUID()}.rgb").also{checkpoint.copyTo(it)}
            }
            override fun cancel(){}
        }
        try {
            val source=PhotoSource(raw,"fixture","application/octet-stream",96,64,raw.length(),linearFile=raw,
                photoMetadata=PhotoMetadata.fromJson(org.json.JSONObject(PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2001:02:03 04:05:06","PhotographicSensitivity" to "400")).toJson()).put("sourceCreateDate","2001-02-03T04:05:06+09:00").toString()))
            val initial=ExportQueue(app,processing)
            val settings=CropSettings(CropMode.SQUARE,CompositionTransform(.6,.4,4.0/3.0))
            val job=initial.enqueue(source,EditorState(crop=settings),ExportFormat.TIFF16,null,95)
            assertNull(job.border);assertEquals(settings,job.crop!!.settings)
            assertEquals(48,job.outputWidth);assertEquals(48,job.outputHeight)
            initial.runPending{}
            assertEquals(ExportJobState.FAILED,initial.store.get(job.id)!!.state)
            assertArrayEquals(bytes,File(initial.store.directory(job.id),"authored-checkpoint.rgb").readBytes())
            assertFalse(initial.store.directory(job.id).listFiles()!!.any{it.name.endsWith(".part")})
            val recovered=ExportQueue(app,processing)
            assertEquals(settings,recovered.store.get(job.id)!!.crop!!.settings)
            assertEquals(96,recovered.store.get(job.id)!!.crop!!.sourceWidth)
            recovered.retry(job.id);recovered.runPending{}
            val done=recovered.store.get(job.id)!!
            assertEquals(done.message,ExportJobState.COMPLETED,done.state)
            assertEquals(2,renders);assertEquals(1,media.inserts)
            val output=media.file(android.net.Uri.parse(done.mediaUri))
            val decoded=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java).invoke(null,output)
            assertEquals(done.outputWidth,decoded.javaClass.getMethod("getWidth").invoke(decoded));assertEquals(done.outputHeight,decoded.javaClass.getMethod("getHeight").invoke(decoded))
            val expectedCapture=java.time.Instant.parse("2001-02-02T19:05:06Z").toEpochMilli()
            assertEquals(expectedCapture,PhotoMetadata.read(output).captureTimeMillis())
            assertEquals(expectedCapture,media.rows[android.net.Uri.parse(done.mediaUri)]!!.getAsLong(MediaStore.Images.Media.DATE_TAKEN))
            val exif=androidx.exifinterface.media.ExifInterface(output.absolutePath)
            assertEquals("2001:02:03 04:05:06",exif.getAttribute("DateTimeOriginal"));assertEquals("+09:00",exif.getAttribute("OffsetTimeOriginal"));assertEquals(done.outputWidth,exif.getAttributeInt("PixelXDimension",0))
            assertArrayEquals(bytes,raw.readBytes());recovered.runPending{};assertEquals(1,media.inserts)
        }finally{raw.delete();File(app.filesDir,"export-jobs").deleteRecursively()}
    }
}
