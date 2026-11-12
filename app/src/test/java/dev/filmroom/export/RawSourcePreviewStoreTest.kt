package dev.filmroom.export

import android.app.Application
import android.graphics.Color
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import kotlinx.coroutines.Job
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
@Config(sdk=[35], application=Application::class)
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
}
