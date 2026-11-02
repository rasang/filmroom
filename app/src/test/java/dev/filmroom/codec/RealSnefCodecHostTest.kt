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
class RealSnefCodecHostTest {
    private val mib=1024L*1024
    private fun input(name:String):File {assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null);assumeTrue(System.getenv(name)!=null);return File(System.getenv(name)!!)}
    private fun hash(file:File):String {val h=MessageDigest.getInstance("SHA-256");file.inputStream().use {s->val b=ByteArray(65536);while(true){val n=s.read(b);if(n<0)break;h.update(b,0,n)}};return h.digest().joinToString(""){"%02x".format(it)}}
    private val memory={NativeMemorySnapshot(4L*1024*mib,128*mib,false)}
    @Test fun actualSnefHandlesShareAdmissionCancelCleanAndRetry()=runBlocking {
        val originalA=input("FILMROOM_SNEF_SAMPLE");val originalB=input("FILMROOM_SNEF_B_SAMPLE")
        assertTrue(NativeCodecBridge.available)
        val root=kotlin.io.path.createTempDirectory("real-snef-codec").toFile().canonicalFile
        val probeCalls=AtomicInteger();val decodeCalls=AtomicInteger();val destroyed=AtomicInteger()
        val api=object:CodecNativeApi by NativeCodecBridge {
            override fun nativeProbe(handle:Long,input:String,budget:String):String {probeCalls.incrementAndGet();return NativeCodecBridge.nativeProbe(handle,input,budget)}
            override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String {decodeCalls.incrementAndGet();return NativeCodecBridge.nativeDecode(handle,input,output,budget)}
            override fun nativeDestroy(handle:Long){NativeCodecBridge.nativeDestroy(handle);destroyed.incrementAndGet()}
        }
        try {
            val a=originalA.copyTo(File(root,"a.NEF"));val b=originalB.copyTo(File(root,"b.NEF"));val beforeA=hash(a);val beforeB=hash(b)
            val codec=PhotoCodecs(root,api,memorySnapshot=memory)
            // Two actual production worker leases must block a real-camera probe
            // before JNI, and cancellation must dispose its freshly created handle.
            val release=CompletableDeferred<Unit>();val one=CompletableDeferred<Unit>();val two=CompletableDeferred<Unit>()
            val holdA=launch {NativeWorkReservations.run(128*mib,memory){one.complete(Unit);release.await()}}
            val holdB=launch {NativeWorkReservations.run(128*mib,memory){two.complete(Unit);release.await()}}
            one.await();two.await()
            try {val waiting=launch {codec.probe(a)};delay(150);assertEquals(0,probeCalls.get());waiting.cancelAndJoin();assertEquals(0,probeCalls.get());assertEquals(1,destroyed.get())}
            finally {release.complete(Unit);holdA.join();holdB.join()}
            assertTrue(codec.probe(a).getBoolean("ok"));assertTrue(codec.probe(b).getBoolean("ok"))
            val outA=File(root,"a.linear");val outB=File(root,"b.linear")
            val first=launch {codec.decode(a,outA)};val second=async {codec.decode(b,outB)}
            withTimeout(10000){while(decodeCalls.get()<2)delay(10)}
            assertFalse("Both actual codec operations own shared worker leases",NativeWorkReservations.detailRetryIdle())
            delay(25);first.cancelAndJoin();val resultB=withTimeout(180000){second.await()}
            assertFalse("Canceled sNEF output is removed",outA.exists());assertTrue(resultB.getBoolean("ok"))
            assertEquals(System.getenv("FILMROOM_SNEF_B_EXPECTED_LINEAR"),hash(outB))
            val retry=codec.decode(a,outA);assertTrue(retry.getBoolean("ok"))
            assertEquals(System.getenv("FILMROOM_SNEF_EXPECTED_LINEAR"),hash(outA))
            val jpeg=File(root,"a.jpg");assertTrue(codec.encode(outA,jpeg,retry.getInt("width"),retry.getInt("height"),"JPEG",90).getBoolean("ok"));assertTrue(jpeg.length()>100)
            assertEquals(beforeA,hash(a));assertEquals(beforeB,hash(b));assertTrue(NativeWorkReservations.detailRetryIdle())
            assertFalse("Private sNEF operation directories are removed",root.listFiles()!!.any{it.isDirectory})
        }finally{root.deleteRecursively()}
    }
    @Test fun actualSnefRepositoryImportFloatPreviewAndReopenPreserveSource()=runBlocking {
        val original=input("FILMROOM_SNEF_SAMPLE");assertTrue(NativeCodecBridge.available)
        val app=ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryInfo(ActivityManager.MemoryInfo().apply{availMem=4L*1024*mib;threshold=128*mib;lowMemory=false})
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000);ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val uri=Uri.parse("content://filmroom.snef/input");val stream=original.inputStream();shadowOf(app.contentResolver).registerInputStream(uri,stream)
        val repo=PhotoRepository(app)
        try {val loaded=withTimeout(180000){repo.import(uri,256)}
            try {
                assertEquals(hash(original),hash(loaded.source.localFile));val spool=checkNotNull(loaded.source.linearFile)
                assertEquals(System.getenv("FILMROOM_SNEF_EXPECTED_LINEAR"),hash(spool));assertEquals(loaded.source.originalWidth.toLong()*loaded.source.originalHeight*12,spool.length())
                val preview=checkNotNull(loaded.source.linearPreview(256));val values=preview.buffer().asFloatBuffer();while(values.hasRemaining())assertTrue(values.get().isFinite());assertTrue(preview.buffer().isReadOnly)
                val stamp=spool.lastModified();val reopened=repo.reopen(loaded.source.cacheKey,"RAW",128,loaded.source.rawCacheKey)
                try {assertEquals(stamp,checkNotNull(reopened.source.linearFile).lastModified());assertEquals(hash(spool),hash(checkNotNull(reopened.source.linearFile)))}finally{reopened.preview.recycle()}
            }finally{loaded.preview.recycle();repo.discard(loaded.source)}
        }finally{stream.close()}
    }
}
