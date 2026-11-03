package dev.filmroom.codec

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.io.PhotoRepository
import dev.filmroom.io.PhotoException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in actual Linux JNI / Kotlin repository integration, not Android device or film-render evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class RealJxlDngCodecHostTest {
    private val mib=1024L*1024
    private val expected="be061c70d43a361623a4e1a7a44ffd2fe6a2db912d8477b5bc953c8f603ea169"
    private val memory={NativeMemorySnapshot(4096*mib,128*mib,false)}
    private fun hash(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {stream->val bytes=ByteArray(65536);while(true){val n=stream.read(bytes);if(n<0)break;digest.update(bytes,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun requireSpace(directory:File,additional:Long) {
        assertTrue("Keep 2 GiB free beyond test allocation",directory.usableSpace>=2048*mib+additional)
    }
    @Test fun actualJxlImportPreviewReopenQueueEncodeAndFailureCleanup()=runBlocking {
        assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        assumeTrue(System.getenv("FILMROOM_JXL_DNG_SAMPLE")!=null)
        val original=File(System.getenv("FILMROOM_JXL_DNG_SAMPLE")!!)
        val before=hash(original)
        assertTrue(NativeCodecBridge.available)
        val scratch=kotlin.io.path.createTempDirectory("real-jxl-dng").toFile().canonicalFile
        try {
            requireSpace(scratch,350*mib)
            val input=original.copyTo(File(scratch,"renamed.raw"))
            val failed=File(scratch,"failed.rgb")
            try {PhotoCodecs(scratch,memorySnapshot=memory,memoryBudgetMiB={128}).decode(input,failed);fail("128 MiB must refuse actual JXL decode")}
            catch(e:PhotoException){assertEquals(dev.filmroom.diagnostics.CodecErrorCode.MEMORY_BUDGET,e.codecCode)}
            assertFalse(failed.exists());assertTrue(NativeWorkReservations.detailRetryIdle())
            val entered=AtomicInteger()
            val api=object:CodecNativeApi by NativeCodecBridge {
                override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String {
                    assertEquals(384,org.json.JSONObject(budget).getInt("memoryBudgetMiB"));entered.incrementAndGet()
                    return NativeCodecBridge.nativeDecode(handle,input,output,budget)
                }
            }
            val cancelled=File(scratch,"cancelled.rgb")
            val job=launch {PhotoCodecs(scratch,api,memorySnapshot=memory,memoryBudgetMiB={384}).decode(input,cancelled)}
            withTimeout(10000){while(entered.get()==0)delay(10)};delay(25);job.cancelAndJoin()
            assertFalse(cancelled.exists());assertTrue(NativeWorkReservations.detailRetryIdle())
            assertFalse(scratch.listFiles()!!.any{it.isDirectory});assertEquals(before,hash(input))
            println("Actual JXL low-budget refusal and cancellation cleaned outputs and leases")
        } finally {scratch.deleteRecursively()}

        val app=ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply {
            availMem=4096*mib;threshold=128*mib;lowMemory=false
        })
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        requireSpace(app.cacheDir,400*mib)
        val uri=Uri.parse("content://filmroom.jxl-dng/input")
        val stream=original.inputStream();shadowOf(app.contentResolver).registerInputStream(uri,stream)
        // Use the production factory, including its ActivityManager budget selection.
        val repo=PhotoRepository(app)
        try {
            val loaded=withTimeout(180000){repo.import(uri,256)}
            try {
                assertEquals(5712,loaded.source.originalWidth);assertEquals(4284,loaded.source.originalHeight)
                assertEquals(before,hash(loaded.source.localFile))
                val spool=checkNotNull(loaded.source.linearFile)
                assertEquals(5712L*4284*12,spool.length());assertEquals(expected,hash(spool))
                val preview=checkNotNull(loaded.source.linearPreview(256));val values=preview.buffer().asFloatBuffer()
                while(values.hasRemaining())assertTrue(values.get().isFinite())
                assertTrue(preview.buffer().isReadOnly)
                println("Production repository imported 5712 x 4284 JXL DNG, expected spool hash and finite preview verified")
                val stamp=spool.lastModified()
                val reopened=repo.reopen(loaded.source.cacheKey,"RAW",128,loaded.source.rawCacheKey)
                try {
                    assertEquals(loaded.source.photoMetadata.toJson(),reopened.source.photoMetadata.toJson())
                    assertEquals(spool.canonicalPath,checkNotNull(reopened.source.linearFile).canonicalPath)
                    assertEquals(stamp,checkNotNull(reopened.source.linearFile).lastModified())
                    assertEquals(expected,hash(checkNotNull(reopened.source.linearFile)))
                } finally {reopened.preview.recycle()}
                println("Reopen reused the same unchanged linear spool")
                if(app.filesDir.usableSpace>=2048*mib+spool.length()+32*mib) {
                    val queue=dev.filmroom.export.ExportQueue(app,startupGate=null)
                    val queued=queue.enqueue(loaded.source,dev.filmroom.model.EditorState(),dev.filmroom.io.ExportFormat.JPEG,64,90)
                    try {
                        assertTrue(queued.inputReady);assertEquals("LINEAR_SOURCE",queued.inputKind)
                        assertEquals(5712,queued.sourceWidth);assertEquals(4284,queued.sourceHeight)
                        assertEquals(loaded.source.photoMetadata.toJson(),queued.photoMetadata)
                        assertEquals(expected,hash(File(queue.store.directory(queued.id),"source.rgb")))
                        requireSpace(app.filesDir,0)
                    } finally {queue.cancel(queued.id)}
                    println("Durable queue captured the actual linear source; queue cancelled without any film render")
                } else println("Queue handoff NOT RUN: maintaining 2 GiB free-space floor")
                requireSpace(app.cacheDir,64*mib)
                val jpeg=File(spool.parentFile,"jxl-full-frame.jpg")
                try {
                    assertTrue(PhotoCodecs(app.cacheDir,memorySnapshot=memory,memoryBudgetMiB={384}).encode(spool,jpeg,5712,4284,"JPEG",90).getBoolean("ok"))
                    assertTrue(jpeg.length()>100)
                    val imageIo=Class.forName("javax.imageio.ImageIO")
                    val decoded=imageIo.getMethod("read",File::class.java).invoke(null,jpeg)
                    assertEquals(5712,decoded.javaClass.getMethod("getWidth").invoke(decoded))
                    assertEquals(4284,decoded.javaClass.getMethod("getHeight").invoke(decoded))
                    requireSpace(app.cacheDir,0)
                    println("Actual full-frame native JPEG encode verified at 5712 x 4284")
                } finally {jpeg.delete()}
                assertEquals(before,hash(original));assertEquals(before,hash(loaded.source.localFile))
                assertTrue(NativeWorkReservations.detailRetryIdle())
            } finally {loaded.preview.recycle();repo.discard(loaded.source)}
        } finally {stream.close()}
    }
}
