package dev.filmroom.codec

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.io.PhotoRepository
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

/** Actual Linux codec JNI through production Kotlin reservations; NOT Android runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class RealRw2CodecHostTest {
    private val mib=1024L*1024
    private fun input(name:String):File {assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null);assumeTrue(System.getenv(name)!=null);return File(System.getenv(name)!!)}
    private fun hash(file:File):String {val h=MessageDigest.getInstance("SHA-256");file.inputStream().use {s->val b=ByteArray(65536);while(true){val n=s.read(b);if(n<0)break;h.update(b,0,n)}};return h.digest().joinToString(""){"%02x".format(it)}}
    private val memory={NativeMemorySnapshot(4L*1024*mib,128*mib,false)}
    @Test fun actualRw2HandlesShareAdmissionCancelCleanAndRetry()=runBlocking {
        val originalA=input("FILMROOM_RW2_SAMPLE");val originalB=input("FILMROOM_RW2_B_SAMPLE")
        assertTrue(NativeCodecBridge.available)
        val root=kotlin.io.path.createTempDirectory("real-rw2-codec").toFile().canonicalFile
        val probeCalls=AtomicInteger();val decodeCalls=AtomicInteger();val destroyed=AtomicInteger()
        val api=object:CodecNativeApi by NativeCodecBridge {
            override fun nativeProbe(handle:Long,input:String,budget:String):String {probeCalls.incrementAndGet();return NativeCodecBridge.nativeProbe(handle,input,budget)}
            override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String {assertEquals(256,org.json.JSONObject(budget).getInt("memoryBudgetMiB"));decodeCalls.incrementAndGet();return NativeCodecBridge.nativeDecode(handle,input,output,budget)}
            override fun nativeDestroy(handle:Long){NativeCodecBridge.nativeDestroy(handle);destroyed.incrementAndGet()}
        }
        try {
            val a=originalA.copyTo(File(root,"a.not-a-raw-extension"));val b=originalB.copyTo(File(root,"b.not-a-raw-extension"));val beforeA=hash(a);val beforeB=hash(b)
            val codec=PhotoCodecs(root,api,memorySnapshot=memory)
            // Two actual production worker leases must block a real-camera probe
            // before JNI, and cancellation must dispose its freshly created handle.
            val release=CompletableDeferred<Unit>();val one=CompletableDeferred<Unit>();val two=CompletableDeferred<Unit>()
            val holdA=launch {NativeWorkReservations.run(128*mib,memory){one.complete(Unit);release.await()}}
            val holdB=launch {NativeWorkReservations.run(128*mib,memory){two.complete(Unit);release.await()}}
            one.await();two.await()
            try {val waiting=launch {codec.probe(a)};delay(150);assertEquals(0,probeCalls.get());waiting.cancelAndJoin();assertEquals(0,probeCalls.get());assertEquals(1,destroyed.get())}
            finally {release.complete(Unit);holdA.join();holdB.join()}
            assertEquals(RawFormat.RW2,RawMetadata.parse(codec.probe(a)).format);assertEquals(RawFormat.RW2,RawMetadata.parse(codec.probe(b)).format)
            val outA=File(root,"a.linear");val outB=File(root,"b.linear")
            val first=launch {codec.decode(a,outA)};val second=async {codec.decode(b,outB)}
            withTimeout(10000){while(decodeCalls.get()<2)delay(10)}
            assertFalse("Both actual codec operations own shared worker leases",NativeWorkReservations.detailRetryIdle())
            delay(25);first.cancelAndJoin();val resultB=withTimeout(180000){second.await()}
            assertFalse("Canceled RW2 output is removed",outA.exists());assertTrue(resultB.getBoolean("ok"))
            assertEquals(System.getenv("FILMROOM_RW2_B_EXPECTED_LINEAR"),hash(outB))
            val retry=codec.decode(a,outA);assertTrue(retry.getBoolean("ok"))
            assertEquals(System.getenv("FILMROOM_RW2_EXPECTED_LINEAR"),hash(outA))
            val jpeg=File(root,"a.jpg");assertTrue(codec.encode(outA,jpeg,retry.getInt("width"),retry.getInt("height"),"JPEG",90).getBoolean("ok"));assertTrue(jpeg.length()>100)
            assertEquals(beforeA,hash(a));assertEquals(beforeB,hash(b));assertTrue(NativeWorkReservations.detailRetryIdle())
            assertFalse("Private RW2 operation directories are removed",root.listFiles()!!.any{it.isDirectory})
        }finally{root.deleteRecursively()}
    }
    @Test fun actualRw2RepositoryImportFloatPreviewAndReopenPreserveSource()=runBlocking {
        val original=input("FILMROOM_RW2_SAMPLE");assertTrue(NativeCodecBridge.available)
        val app=ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply{availMem=4L*1024*mib;threshold=128*mib;lowMemory=false})
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000);ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val uri=Uri.parse("content://filmroom.rw2/input");val stream=original.inputStream();shadowOf(app.contentResolver).registerInputStream(uri,stream)
        val repo=PhotoRepository(app)
        try {val loaded=withTimeout(180000){repo.import(uri,256)}
            try {
                assertEquals(hash(original),hash(loaded.source.localFile))
                val capture=org.json.JSONObject(loaded.source.photoMetadata.toJson()).getJSONObject("attributes")
                val originalCapture=org.json.JSONObject(dev.filmroom.io.PhotoMetadata.read(original).toJson()).getJSONObject("attributes")
                assertEquals("Panasonic",capture.getString("Make"));assertEquals(originalCapture.getString("Model"),capture.getString("Model"))
                for(key in listOf("Make","Model","DateTimeOriginal","ExposureTime","FNumber","FocalLength")) assertEquals(originalCapture.optString(key),capture.optString(key))
                val spool=checkNotNull(loaded.source.linearFile)
                assertEquals(System.getenv("FILMROOM_RW2_EXPECTED_LINEAR"),hash(spool));assertEquals(loaded.source.originalWidth.toLong()*loaded.source.originalHeight*12,spool.length())
                val preview=checkNotNull(loaded.source.linearPreview(256));val values=preview.buffer().asFloatBuffer();while(values.hasRemaining())assertTrue(values.get().isFinite());assertTrue(preview.buffer().isReadOnly)
                // Actual imported CF2 linear spool survives durable queue handoff;
                // no JPEG fallback and no native film render is mocked as completed.
                val queue=dev.filmroom.export.ExportQueue(app,startupGate=null)
                val queued=queue.enqueue(loaded.source,dev.filmroom.model.EditorState(),dev.filmroom.io.ExportFormat.JPEG,64,90)
                try {
                    assertTrue(queued.inputReady);assertEquals("LINEAR_SOURCE",queued.inputKind)
                    assertEquals(loaded.source.originalWidth,queued.sourceWidth);assertEquals(loaded.source.originalHeight,queued.sourceHeight)
                    assertEquals(loaded.source.photoMetadata.toJson(),queued.photoMetadata)
                    assertEquals(hash(spool),hash(File(queue.store.directory(queued.id),"source.rgb")))
                }finally{queue.cancel(queued.id)}
                assertTrue(spool.exists())
                val stamp=spool.lastModified();val reopened=repo.reopen(loaded.source.cacheKey,"RAW",128,loaded.source.rawCacheKey)
                try {assertEquals(loaded.source.photoMetadata.toJson(),reopened.source.photoMetadata.toJson());assertEquals(stamp,checkNotNull(reopened.source.linearFile).lastModified());assertEquals(hash(spool),hash(checkNotNull(reopened.source.linearFile)))}finally{reopened.preview.recycle()}
            }finally{loaded.preview.recycle();repo.discard(loaded.source)}
        }finally{stream.close()}
    }
}
