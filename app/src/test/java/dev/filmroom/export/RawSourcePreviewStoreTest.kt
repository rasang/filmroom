package dev.filmroom.export

import android.app.Application
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.exifinterface.media.ExifInterface
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35], application=Application::class, shadows=[FileSourceImageDecoderShadow::class])
class RawSourcePreviewStoreTest {
    private fun withSource(block:(File,File,ExportJob)->Unit) {
        dev.filmroom.i18n.L10n.initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>())
        val dir=Files.createTempDirectory("raw-source-preview").toFile()
        try {
            val job=ExportJob(ExportJobStore.newId(),1,EditorState(),24,48,ExportFormat.JPEG,95,
                originalUri="content://test/raw",sourceWidth=24,sourceHeight=48)
            val raw=File(dir,"input.rgb")
            val data=ByteBuffer.allocate(24*48*12).order(ByteOrder.LITTLE_ENDIAN)
            repeat(48){y->repeat(24){x->
                data.putFloat(if(x<12)1f else 0f)
                data.putFloat(if(y>=24)1f else 0f)
                data.putFloat(if(x>=12 && y<24)1f else 0f)
            }}
            raw.writeBytes(data.array());block(dir,raw,job)
        } finally {dir.deleteRecursively()}
    }
    @Test fun canonicalUncomposedPixelsSurvivePersistenceAndBoundedReads()=withSource { dir,raw,job ->
        RawSourcePreviewStore.write(dir,job,raw,Job())
        raw.delete() // completion removes the large source; persistent preview remains usable
        var w=0;var h=0
        val bitmap=RawSourcePreviewStore.readDirectory(dir,job,2560){a,b->w=a;h=b}!!
        assertEquals(24,w);assertEquals(48,h);assertEquals(24,bitmap.width);assertEquals(48,bitmap.height)
        assertTrue(Color.red(bitmap.getPixel(3,3))>220)
        assertTrue(Color.blue(bitmap.getPixel(20,3))>220)
        assertTrue(Color.green(bitmap.getPixel(3,40))>220)
        bitmap.recycle()
        val tiny=RawSourcePreviewStore.readDirectory(dir,job,1){a,b->w=a;h=b}!!
        assertEquals(24,w);assertEquals(48,h);assertEquals(1,tiny.height);tiny.recycle()
        assertTrue(dir.listFiles()!!.any{it.extension=="preview"})
    }
    @Test fun missingCorruptAndRelinkedRecordsDoNotSupplyOldPixels()=withSource {dir,raw,job->
        assertNull(RawSourcePreviewStore.readDirectory(dir,job,100))
        RawSourcePreviewStore.write(dir,job,raw,Job())
        assertNull(RawSourcePreviewStore.readDirectory(dir,job.copy(originalUri="content://test/other"),100))
        assertNull(RawSourcePreviewStore.readDirectory(dir,job.copy(sourceWidth=25),100))
        File(dir,"source-raw.preview").writeText("not an image")
        assertNull(RawSourcePreviewStore.readDirectory(dir,job,100))
    }
    @Test fun cancelledGenerationLeavesNoPublishedPreview()=withSource {dir,raw,job->
        val coroutine=Job()
        coroutine.cancel()
        try {RawSourcePreviewStore.write(dir,job,raw,coroutine);fail("Expected cancellation")}
        catch(_:java.util.concurrent.CancellationException){}
        assertFalse(File(dir,"source-raw.preview").exists())
        assertFalse(File(dir,"source-raw.meta").exists())
    }
    @Test fun truncatedJpegCannotBeAcceptedAsPartialPreview()=withSource {dir,raw,job->
        RawSourcePreviewStore.write(dir,job,raw,Job())
        val image=File(dir,"source-raw.preview")
        val bytes=image.readBytes()
        image.writeBytes(bytes.copyOf(bytes.size-2))
        assertNull(RawSourcePreviewStore.readDirectory(dir,job,100))
    }
    @Test fun historyRemovalDeletesPreviewWithRecord()=withSource {root,raw,job->
        val store=ExportJobStore(File(root,"jobs"))
        val complete=job.copy(state=ExportJobState.COMPLETED)
        store.update(complete)
        val dir=store.directory(complete.id)
        RawSourcePreviewStore.write(dir,complete,raw,Job())
        val snapshot=store.historySnapshot(setOf(complete.id)).single()
        val operation=store.beginHistory(listOf(snapshot),false)
        assertTrue(store.removeHistoryRecord(operation,snapshot))
        assertFalse(dir.exists())
    }
    @Test fun backupContainsNoPrivatePreviewOrPath()=withSource {dir,raw,job->
        RawSourcePreviewStore.write(dir,job,raw,Job())
        val backup=ExportHistoryBackup.exportRows(listOf(job.copy(state=ExportJobState.COMPLETED))).toString()
        assertFalse(backup.contains("source-raw"))
        assertFalse(backup.contains(dir.path))
    }
    @Test fun clearRemovesOnlyPrivateArtifacts()=withSource {dir,raw,job->
        RawSourcePreviewStore.write(dir,job,raw,Job())
        RawSourcePreviewStore.clear(dir)
        assertTrue(raw.exists())
        assertNull(RawSourcePreviewStore.readDirectory(dir,job,100))
    }
    private fun raster(file:File, format:Bitmap.CompressFormat=Bitmap.CompressFormat.JPEG) {
        val bitmap=Bitmap.createBitmap(48,24,Bitmap.Config.ARGB_8888)
        try {
            for(y in 0 until 24)for(x in 0 until 48)bitmap.setPixel(x,y,
                if(y<12){if(x<24)Color.RED else Color.BLUE}
                else {if(x<24)Color.GREEN else Color.YELLOW})
            file.outputStream().use { assertTrue(bitmap.compress(format,100,it)) }
        } finally { bitmap.recycle() }
    }
    private fun assertColor(expected:Int,actual:Int) {
        assertTrue("red: $expected vs $actual",kotlin.math.abs(Color.red(expected)-Color.red(actual))<35)
        assertTrue("green: $expected vs $actual",kotlin.math.abs(Color.green(expected)-Color.green(actual))<35)
        assertTrue("blue: $expected vs $actual",kotlin.math.abs(Color.blue(expected)-Color.blue(actual))<35)
    }
    @Test fun jpegExifSixAndEightAreNormalizedExactlyOnce()=withSource {dir,_,job->
        runBlocking {
            for((orientation,topLeft,topRight,bottomLeft,bottomRight) in listOf(
                listOf(ExifInterface.ORIENTATION_ROTATE_90,Color.GREEN,Color.RED,Color.YELLOW,Color.BLUE),
                listOf(ExifInterface.ORIENTATION_ROTATE_270,Color.BLUE,Color.YELLOW,Color.RED,Color.GREEN))) {
                val image=File(dir,"oriented.jpg")
                raster(image)
                ExifInterface(image).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()
                }
                RawSourcePreviewStore.writeSource(dir,job,image,false,coroutineContext)
                val saved=RawSourcePreviewStore.readDirectory(dir,job,2560)!!
                try {
                    assertEquals(24,saved.width);assertEquals(48,saved.height)
                    assertColor(topLeft,saved.getPixel(5,8));assertColor(topRight,saved.getPixel(18,8))
                    assertColor(bottomLeft,saved.getPixel(5,39));assertColor(bottomRight,saved.getPixel(18,39))
                } finally {saved.recycle()}
            }
        }
    }
    @Test fun pngTransparencyUsesSameWhiteCompositeAndSrgbAsEditor()=withSource {dir,_,job->
        runBlocking {
            val image=File(dir,"alpha.png")
            val bitmap=Bitmap.createBitmap(48,24,Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.TRANSPARENT)
                for(y in 0 until 24)for(x in 24 until 48)bitmap.setPixel(x,y,Color.argb(128,255,0,0))
                image.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
            } finally {bitmap.recycle()}
            val record=job.copy(sourceWidth=48,sourceHeight=24)
            RawSourcePreviewStore.writeSource(dir,record,image,false,coroutineContext)
            val saved=RawSourcePreviewStore.readDirectory(dir,record,2560)!!
            try {
                assertColor(Color.WHITE,saved.getPixel(5,12))
                assertColor(Color.rgb(255,127,127),saved.getPixel(40,12))
                assertEquals(ColorSpace.get(ColorSpace.Named.SRGB),saved.colorSpace)
                assertFalse(saved.hasAlpha())
            } finally {saved.recycle()}
        }
    }
    @Test fun encodedPreviewSurvivesRemovalOfOriginalWithoutSourceAccess()=withSource {dir,_,job->
        runBlocking {
            val image=File(dir,"source.jpg");raster(image)
            val record=job.copy(originalUri="content://revoked/photo",sourceWidth=48,sourceHeight=24)
            RawSourcePreviewStore.writeSource(dir,record,image,false,coroutineContext)
            assertTrue(image.delete())
            val saved=RawSourcePreviewStore.readDirectory(dir,record,2560)!!
            try {assertEquals(48,saved.width);assertColor(Color.RED,saved.getPixel(5,5))}
            finally {saved.recycle()}
        }
    }
    @Test fun jpegWithAppendedVideoBytesCachesOnlyStillWithoutMutatingSource()=withSource {dir,_,job->
        runBlocking {
            val image=File(dir,"motion.jpg");raster(image)
            // MP4 ftyp box after JPEG EOI models the extra payload of a single-file Live.
            image.appendBytes(byteArrayOf(0,0,0,20)+"ftypisom".toByteArray()+byteArrayOf(0,0,0,0)+"isom".toByteArray())
            val original=image.readBytes()
            val record=job.copy(originalUri="content://test/live",sourceWidth=48,sourceHeight=24)
            RawSourcePreviewStore.writeSource(dir,record,image,false,coroutineContext)
            assertArrayEquals(original,image.readBytes())
            val saved=RawSourcePreviewStore.readDirectory(dir,record,2560)!!
            try {assertEquals(48,saved.width);assertEquals(24,saved.height);assertColor(Color.BLUE,saved.getPixel(40,5))}
            finally {saved.recycle()}
        }
    }

}
