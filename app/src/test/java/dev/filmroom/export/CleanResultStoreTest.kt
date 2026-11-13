package dev.filmroom.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.effects.EffectImageDecoder
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
import java.io.FileNotFoundException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35], application=Application::class)
class CleanResultStoreTest {
    private fun fixture(block:(File,File,ExportJob)->Unit) {
        dev.filmroom.i18n.L10n.initialize(ApplicationProvider.getApplicationContext<Application>())
        val root=Files.createTempDirectory("clean-result-test").toFile()
        try {
            val image=File(root,"input.png")
            val bitmap=Bitmap.createBitmap(48,24,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED);image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
            val job=ExportJob(ExportJobStore.newId(),123,EditorState(),48,24,ExportFormat.PNG,100,
                state=ExportJobState.COMPLETED,mediaUri="content://media/external/images/media/1",liveEffect="LIGHT_TABLE")
            block(root,image,job)
        } finally { root.deleteRecursively() }
    }
    @Test fun fullResolutionBytesPersistWithoutThumbnailOrRecompression()=fixture {root,image,job->
        val dir=File(root,"job");CleanResultStore.write(dir,job,image)
        val saved=CleanResultStore.resolveDirectory(dir,job)!!
        assertArrayEquals(image.readBytes(),saved.readBytes())
        val decoded=BitmapFactory.decodeFile(saved.path)
        assertEquals(48,decoded.width);assertEquals(24,decoded.height);assertEquals(Color.RED,decoded.getPixel(10,10));decoded.recycle()
        assertNotNull(CleanResultStore.resolveDirectory(dir,job.copy(exportMillis=456,mediaUri="content://media/external/images/media/2")))
        assertNull(CleanResultStore.resolveDirectory(dir,job.copy(liveEffect="OPTICAL_LEAK")))
        assertNull(CleanResultStore.resolveDirectory(dir,job.copy(parameters=job.parameters.copy(brightness=.2f))))
        saved.appendText("corrupt")
        assertNull(CleanResultStore.resolveDirectory(dir,job))
    }
    @Test fun fullSizeCleanAboveSourcePreviewCapRetainsDimensionsAndPixels()=fixture {root,_,base->
        val width=3000;val height=1500
        val job=base.copy(width=width,height=height,sourceWidth=width,sourceHeight=height)
        val input=File(root,"full-size.png")
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        // Distinct far-edge pixels detect any hidden thumbnail scaling or crop.
        bitmap.setPixel(width-1,0,Color.BLUE)
        bitmap.setPixel(0,height-1,Color.GREEN)
        bitmap.setPixel(width-1,height-1,Color.YELLOW)
        input.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
        val dir=File(root,"full-job");CleanResultStore.write(dir,job,input)
        val saved=CleanResultStore.resolveDirectory(dir,job)!!
        assertArrayEquals(input.readBytes(),saved.readBytes())
        val decoded=BitmapFactory.decodeFile(saved.path)
        try {
            assertTrue(decoded.width>RawSourcePreviewStore.MAX_EDGE)
            assertEquals(width,decoded.width);assertEquals(height,decoded.height)
            assertEquals(Color.RED,decoded.getPixel(1000,750))
            assertEquals(Color.BLUE,decoded.getPixel(width-1,0))
            assertEquals(Color.GREEN,decoded.getPixel(0,height-1))
            assertEquals(Color.YELLOW,decoded.getPixel(width-1,height-1))
        } finally { decoded.recycle() }
    }
    @Test fun invalidDimensionsAndCancelledWriteAreNotPublished()=fixture {root,image,job->
        val dir=File(root,"job")
        try { CleanResultStore.write(dir,job.copy(width=49),image);fail() } catch(_:IllegalStateException){}
        assertNull(CleanResultStore.resolveDirectory(dir,job))
        val cancelled=Job().apply{cancel()}
        try { CleanResultStore.write(dir,job,image,cancelled);fail() } catch(_:java.util.concurrent.CancellationException){}
        assertNull(CleanResultStore.resolveDirectory(dir,job))
    }
    @Test fun historyRemovalPurgesCleanAndManifestTogether()=fixture {root,image,job->
        val store=ExportJobStore(File(root,"jobs"));store.update(job)
        val dir=store.directory(job.id);CleanResultStore.write(dir,job,image)
        val reloaded=ExportJobStore(File(root,"jobs")).get(job.id)!!
        assertEquals(job.liveEffect,reloaded.liveEffect)
        val snapshot=store.historySnapshot(setOf(job.id)).single();val op=store.beginHistory(listOf(snapshot),false)
        assertTrue(store.removeHistoryRecord(op,snapshot));assertFalse(dir.exists())
    }
    @Test fun backupPreservesLiveMarkerButNotPrivateCleanArtifact()=fixture {root,image,job->
        val dir=File(root,"job");CleanResultStore.write(dir,job,image)
        val rows=ExportHistoryBackup.exportRows(listOf(job.copy(outputName="live.jpg")))
        val archived=ExportHistoryBackup.validateRows(rows).single()
        assertEquals("LIGHT_TABLE",archived.liveEffect)
        assertTrue(archived.archivedHistory)
        assertFalse(rows.toString().contains("clean-result"));assertFalse(rows.toString().contains(root.path))
        assertNull(CleanResultStore.resolve(ApplicationProvider.getApplicationContext(),archived))
    }
    @Test fun liveResultReadsPrivateCleanInsteadOfPublishedCover()=fixture {_,image,job->
        val app=ApplicationProvider.getApplicationContext<Application>()
        val dir=File(File(app.filesDir,"export-jobs"),job.id)
        try {
            CleanResultStore.write(dir,job,image)
            runBlocking {
                EffectImageDecoder(app,sourceRecord=job,systemRead={uri,_,_,onSize->
                    assertTrue(uri.startsWith("file:"));assertFalse(uri==job.mediaUri)
                    onSize(dev.filmroom.effects.EffectExportSize(48,24))
                    BitmapFactory.decodeFile(android.net.Uri.parse(uri).path)
                }).use { decoder ->
                    assertEquals(dev.filmroom.effects.EffectExportSize(48,24),decoder.bounds(job.mediaUri!!,allowRawFallback=false))
                    val pixels=decoder.read(job.mediaUri,100,allowRawFallback=false)
                    assertEquals(Color.RED,pixels.getPixel(5,5));pixels.recycle()
                    // A changed private artifact invalidates operation-local verification.
                    File(dir,"clean-result.image").appendText("changed")
                    try { decoder.read(job.mediaUri!!,100,allowRawFallback=false);fail() } catch(_:FileNotFoundException){}
                }
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun missingCleanLiveNeverFallsBackToDecoratedCover()=fixture {_,_,job->
        runBlocking {
            EffectImageDecoder(ApplicationProvider.getApplicationContext(),sourceRecord=job,
                systemRead={_,_,_,_->error("must not read decorated cover")}).use { decoder ->
                try { decoder.read(job.mediaUri!!,100,allowRawFallback=false);fail() } catch(_:FileNotFoundException){}
            }
        }
    }
    @Test fun legacyPhotoStillReadsItsGalleryResult()=fixture {_,_,job->
        runBlocking {
            var reads=0
            EffectImageDecoder(ApplicationProvider.getApplicationContext(),sourceRecord=job.copy(liveEffect=null),
                systemRead={uri,_,_,_-> assertEquals(job.mediaUri,uri);reads++;Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)}).use { decoder ->
                decoder.read(job.mediaUri!!,100,allowRawFallback=false).recycle()
            }
            assertEquals(1,reads)
        }
    }
    @Test fun liveUrisRoundTripWithManifest()=fixture {root,_,job->
        val uris=listOf(job.mediaUri!!,"content://media/external/video/media/2")
        val names=listOf("live.jpg","live.mp4")
        val store=ExportJobStore(File(root,"jobs"));store.update(job.copy(livePublishedUris=uris,livePublishedNames=names))
        val reloaded=ExportJobStore(File(root,"jobs")).get(job.id)!!
        assertEquals(uris,reloaded.livePublishedUris);assertEquals(names,reloaded.livePublishedNames)
    }
}
