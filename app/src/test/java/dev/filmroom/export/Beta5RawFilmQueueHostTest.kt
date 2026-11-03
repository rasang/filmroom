package dev.filmroom.export

import android.app.ActivityManager
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.BorderCatalog
import dev.filmroom.border.BwBorderDefinitions
import dev.filmroom.codec.NativeCodecBridge
import dev.filmroom.engine.NativeFilmBridge
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.io.ExportFormat
import dev.filmroom.io.LoadedPhoto
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.io.PhotoRepository
import dev.filmroom.model.EditorState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.security.MessageDigest

/**
 * Real full RAW decoding, repository, durable queue, native film engine and JPEG codec.
 * Only Android ContentResolver/MediaStore and memory/storage observations are host doubles.
 * A new queue instance reloads persisted state; this is not a real OS process-death test.
 * Film input is bounded to 64/128 px with a small added 5222 border canvas.
 * Covers current v7 and a pinned persisted v6, not glyph-quality/full-resolution/device QA.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Beta5RawFilmQueueHostTest {
    class Media : ContentProvider() {
        val rows=linkedMapOf<Uri,ContentValues>()
        private var nextId=0
        fun file(uri:Uri)=File(context!!.cacheDir,"beta5-published-${uri.lastPathSegment}.jpg")
        override fun onCreate()=true
        override fun getType(uri:Uri)=rows[uri]?.getAsString(MediaStore.MediaColumns.MIME_TYPE)
        override fun insert(uri:Uri,values:ContentValues?):Uri=Uri.withAppendedPath(uri,(++nextId).toString()).also {
            rows[it]=ContentValues(values).apply {put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,context!!.packageName)}
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,order:String?):Cursor {
            val columns=projection ?: arrayOf(MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            return MatrixCursor(columns).apply {rows[uri]?.let {row->addRow(columns.map {row.get(it)}.toTypedArray())}}
        }
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int {
            val row=rows[uri] ?: return 0; row.putAll(values); return 1
        }
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int {
            file(uri).delete(); return if(rows.remove(uri)!=null) 1 else 0
        }
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            check(rows.containsKey(uri))
            return ParcelFileDescriptor.open(file(uri),if(mode=="r") ParcelFileDescriptor.MODE_READ_ONLY else
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
        }
    }
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {stream->val buffer=ByteArray(65536);while(true){val count=stream.read(buffer);if(count<0)break;digest.update(buffer,0,count)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    @Test fun samsungFullRawReopensAndCompletesRealFilmQueueJpeg()=runBlocking {
        exercise("FILMROOM_SAMSUNG_SAMPLE","FILMROOM_SAMSUNG_EXPECTED_LINEAR","image/x-adobe-dng",64,false)
    }
    @Test fun rw2FullRawReopensAndCompletesRealFilmQueueJpeg()=runBlocking {
        exercise("FILMROOM_RW2_SAMPLE","FILMROOM_RW2_EXPECTED_LINEAR","image/x-panasonic-rw2",128,true)
    }
    private suspend fun exercise(inputName:String,expectedName:String,mime:String,maxEdge:Int,legacy5222:Boolean) {
        assumeTrue("Opt-in requires Linux host JNI libraries",System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        val fixture=File(checkNotNull(System.getenv(inputName)){"Missing real RAW fixture: $inputName"})
        require(fixture.isFile)
        val expectedLinear=checkNotNull(System.getenv(expectedName)){"Missing independently pinned full-linear hash: $expectedName"}
        assertNull("Actual film engine must load",NativeFilmBridge.loadError)
        assertTrue("Actual combined RAW codec must load",NativeCodecBridge.available)
        val fixtureHash=hash(fixture)
        val app=ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir,"export-jobs").deleteRecursively()
        File(app.cacheDir,"photo-sources").deleteRecursively()
        app.getSharedPreferences("filmroom-pending-exports",0).edit().clear().commit()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            availMem=4L*1024*1024*1024;threshold=128L*1024*1024;lowMemory=false
        })
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val media=Media();media.attachInfo(app,android.content.pm.ProviderInfo().apply {authority=MediaStore.AUTHORITY})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,media)
        val repository=PhotoRepository(app)
        // No filename or camera MIME is supplied: actual header/content selects the RAW decoder.
        val inputUri=Uri.parse("content://filmroom.beta5-raw/input")
        fixture.inputStream().use {stream->
            shadowOf(app.contentResolver).registerInputStream(inputUri,stream)
            val loaded=withTimeout(180000){repository.import(inputUri,128)}
            var reopened:LoadedPhoto?=null
            try {
                assertEquals(mime,loaded.source.mimeType)
                assertEquals(fixtureHash,hash(loaded.source.localFile))
                assertTrue(loaded.source.originalWidth>128 && loaded.source.originalHeight>128)
                val spool=checkNotNull(loaded.source.linearFile)
                assertEquals(loaded.source.originalWidth.toLong()*loaded.source.originalHeight*12,spool.length())
                assertEquals("Full RAW output, not an embedded preview",expectedLinear,hash(spool))
                val preview=checkNotNull(loaded.source.linearPreview(128))
                val values=preview.buffer().asFloatBuffer()
                while(values.hasRemaining())assertTrue("Float preview is finite",values.get().isFinite())
                val capture=loaded.source.photoMetadata.toJson()
                val inputAttributes=JSONObject(PhotoMetadata.read(fixture).toJson()).getJSONObject("attributes")
                val attributes=JSONObject(capture).getJSONObject("attributes")
                for(key in listOf("Make","Model","DateTimeOriginal","ExposureTime","FNumber","FocalLength"))
                    assertEquals("Imported capture $key",inputAttributes.optString(key),attributes.optString(key))
                assertTrue("Fixture supplies a real camera identity",attributes.optString("Model").isNotEmpty())
                val stamp=spool.lastModified()
                val restored=repository.reopen(loaded.source.cacheKey,"RAW",64,loaded.source.rawCacheKey)
                reopened=restored
                assertEquals(capture,restored.source.photoMetadata.toJson())
                assertEquals(stamp,checkNotNull(restored.source.linearFile).lastModified())
                assertEquals(expectedLinear,hash(checkNotNull(restored.source.linearFile)))
                // Fixed CPU DoubleX path plus actual catalog WebP/compositor; no film/border double.
                val parameters=EditorState(filmStock="kodak_doublex",filmBorder=true,useGpu=false,grain=0f,halation=0f)
                val initialQueue=ExportQueue(app,startupGate=null)
                val job=initialQueue.enqueue(restored.source,parameters,ExportFormat.JPEG,maxEdge,90)
                assertTrue(job.inputReady);assertEquals("LINEAR_SOURCE",job.inputKind)
                assertEquals(capture,job.photoMetadata)
                val directory=initialQueue.store.directory(job.id)
                assertEquals(expectedLinear,hash(File(directory,"source.rgb")))
                val currentBorder=checkNotNull(job.border)
                assertTrue(currentBorder.assetVersion.startsWith("bw-v7-double_x_5222-positive-asymmetric-white-"))
                // Model an already-persisted Beta4 v6 job without changing current catalog selection.
                // Same version-pinning mechanism as DoubleXPositiveSampledHostTest; exact real asset.
                val pinnedBorder=if(legacy5222) currentBorder.copy(assetVersion=BwBorderDefinitions.legacyDefinitions.single {
                    it.spec.stock=="kodak_doublex" && it.spec.version.startsWith("bw-v6-") && it.spec.holeMatteSrgb==0xffffff
                }.spec.version) else currentBorder
                BorderCatalog.verifySnapshot(pinnedBorder)
                initialQueue.store.update(job.copy(state=ExportJobState.PAUSED,message="host persisted restart checkpoint",border=pinnedBorder))
                val queue=ExportQueue(app,startupGate=null)
                val persisted=checkNotNull(queue.store.get(job.id))
                assertEquals(ExportJobState.PAUSED,persisted.state)
                assertEquals(capture,persisted.photoMetadata)
                assertEquals("kodak_doublex",persisted.parameters.filmStock)
                assertEquals("Frozen material survives queue reload",pinnedBorder,persisted.border)
                queue.retry(job.id)
                withTimeout(180000){queue.runPending {}}
                val done=checkNotNull(queue.store.get(job.id))
                assertEquals("Actual film queue must complete: ${done.message}",ExportJobState.COMPLETED,done.state)
                assertEquals(capture,done.photoMetadata)
                assertEquals("Completed export retains its exact v6/v7 material",pinnedBorder,done.border)
                assertEquals(pinnedBorder.outputWidth,done.outputWidth)
                assertEquals(pinnedBorder.outputHeight,done.outputHeight)
                assertEquals(maxEdge,maxOf(done.width,done.height))
                val published=Uri.parse(checkNotNull(done.mediaUri))
                val output=media.file(published)
                assertTrue(output.length()>100)
                val decoded=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java).invoke(null,output)
                assertNotNull("Final JPEG must decode",decoded)
                assertEquals(done.outputWidth,decoded.javaClass.getMethod("getWidth").invoke(decoded))
                assertEquals(done.outputHeight,decoded.javaClass.getMethod("getHeight").invoke(decoded))
                assertTrue("Final JPEG includes ICC",String(output.readBytes(),Charsets.ISO_8859_1).contains("ICC_PROFILE"))
                val exif=ExifInterface(output.absolutePath)
                assertEquals(1,exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,0))
                assertEquals(done.outputWidth,exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION,0))
                assertEquals(done.outputHeight,exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION,0))
                for(key in listOf("Make","Model","DateTimeOriginal","OffsetTimeOriginal","SubSecTimeOriginal"))
                    if(attributes.has(key))assertEquals("Exported capture $key",attributes.getString(key),exif.getAttribute(key))
                assertEquals(0,media.rows[published]!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
                assertEquals(1,media.rows.size)
                assertFalse("Queue removes full RAW snapshot after completion",File(directory,"source.rgb").exists())
                assertFalse(File(directory,"input.rgb").exists())
                assertFalse(directory.walkTopDown().any {it.isFile && (it.extension=="part" || it.name.startsWith("film-result-") || it.name.startsWith("codec-output-"))})
                assertTrue("Shared worker leases released",NativeWorkReservations.detailRetryIdle())
                assertEquals("Borrowed editor source survives export",expectedLinear,hash(spool))
                assertEquals(fixtureHash,hash(fixture))
            } finally {
                reopened?.preview?.recycle();loaded.preview.recycle();repository.discard(loaded.source)
            }
        }
        assertEquals("Never rewrite original fixture",fixtureHash,hash(fixture))
    }
}
