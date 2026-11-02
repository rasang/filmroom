package dev.filmroom.codec

import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.model.RawBatchMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RawBatchSnapshotHostTest {
    private val mib=1024L*1024
    private val high={NativeMemorySnapshot(4096*mib,128*mib,false)}
    private data class Call(val input:String,val request:JSONObject)
    private class Api(private val entered:CountDownLatch?=null,private val release:CountDownLatch?=null):CodecNativeApi {
        override val available=true
        val decodes=CopyOnWriteArrayList<Call>();val probes=CopyOnWriteArrayList<JSONObject>();val encodes=CopyOnWriteArrayList<JSONObject>()
        private val sequence=AtomicInteger();val destroys=AtomicInteger()
        override fun nativeCreate(root:String)=sequence.incrementAndGet().toLong()
        override fun nativeDestroy(handle:Long){destroys.incrementAndGet()}
        override fun nativeCancel(handle:Long){}
        override fun nativeProbe(handle:Long,input:String,budget:String):String {probes.add(JSONObject(budget));return "{\"ok\":true}"}
        override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String {
            decodes.add(Call(File(input).name,JSONObject(budget)));entered?.countDown()
            check(release?.await(10,TimeUnit.SECONDS)!=false)
            return "{\"ok\":true}"
        }
        override fun nativeEncode(handle:Long,input:String,output:String,parameters:String):String {encodes.add(JSONObject(parameters));return "{\"ok\":true}"}
    }
    private fun tiff(root:File,name:String,dng:Boolean=false):File {
        val b=ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(73,73));b.putShort(42);b.putInt(8);b.putShort(1)
        b.putShort((if(dng)50706 else 256).toShort());b.putShort(1);b.putInt(4);b.putInt(1);b.putInt(0)
        return File(root,name).apply{writeBytes(b.array())}
    }
    @Test fun queuedDecodeFreezesModeAndBudgetWhileLedgerRetainsTheOriginalCharge()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("raw-batch-frozen").toFile();val input=tiff(root,"camera.bin")
        val api=Api();val choice=AtomicReference(RawBatchMode.SMALL);val budget=AtomicInteger(256)
        val settingCalls=AtomicInteger();val budgetCalls=AtomicInteger();val captured=CompletableDeferred<Unit>()
        val memory={NativeMemorySnapshot(1024*mib,128*mib,false)}
        val codec=PhotoCodecs(root,api,memorySnapshot=memory,rawBatchPreference={settingCalls.incrementAndGet();choice.get()},memoryBudgetMiB={budgetCalls.incrementAndGet();budget.get().also{captured.complete(Unit)}})
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val holder=launch{NativeWorkReservations.run(160*mib,memory){entered.complete(Unit);release.await()}}
        entered.await()
        val work=async{codec.decode(input,File(root,"out"))}
        try {
            withTimeout(5000){captured.await()}
            choice.set(RawBatchMode.LARGE);budget.set(128);delay(100)
            assertEquals(1,settingCalls.get());assertEquals(1,budgetCalls.get())
            assertTrue("The frozen256MiB lease cannot enter beside160MiB; lowering the preference must not admit it",api.decodes.isEmpty())
            release.complete(Unit);holder.join();withTimeout(5000){work.await()}
            assertEquals("small",api.decodes.single().request.getString("rawBatch"))
            assertEquals(256,api.decodes.single().request.getInt("memoryBudgetMiB"))
            assertTrue(NativeWorkReservations.detailRetryIdle())
            codec.decode(input,File(root,"next"))
            assertEquals("large",api.decodes.last().request.getString("rawBatch"))
            assertEquals(128,api.decodes.last().request.getInt("memoryBudgetMiB"))
            assertEquals(2,settingCalls.get());assertEquals(2,budgetCalls.get())
        }finally{release.complete(Unit);holder.join();work.cancelAndJoin();root.deleteRecursively()}
    }
    @Test fun explicitImportSnapshotOverridesALaterPreferenceWithoutResampling()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("raw-batch-context").toFile();val api=Api()
        val choice=AtomicReference(RawBatchMode.SMALL);val calls=AtomicInteger()
        val codec=PhotoCodecs(root,api,memorySnapshot=high,rawBatchPreference={calls.incrementAndGet();choice.get()})
        try {
            val snapshot=codec.snapshotRawBatch();choice.set(RawBatchMode.LARGE)
            withContext(snapshot){codec.decode(tiff(root,"photo.dng",true),File(root,"out"))}
            assertEquals(1,calls.get());assertEquals("small",api.decodes.single().request.getString("rawBatch"))
            assertEquals(128,api.decodes.single().request.getInt("memoryBudgetMiB"))
            codec.decode(File(root,"photo.dng"),File(root,"next"))
            assertEquals(2,calls.get());assertEquals("large",api.decodes.last().request.getString("rawBatch"))
        }finally{root.deleteRecursively()}
    }
    @Test fun concurrentImportsHaveIndependentContextSnapshotsAndNoGlobalMode()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("raw-batch-concurrent").toFile()
        val entered=CountDownLatch(2);val release=CountDownLatch(1);val api=Api(entered,release);val calls=AtomicInteger()
        val codec=PhotoCodecs(root,api,memorySnapshot=high,rawBatchPreference={calls.incrementAndGet();RawBatchMode.AUTO})
        val small=tiff(root,"small.dng",true);val large=tiff(root,"large.dng",true)
        val first=async(Dispatchers.Default+RawBatchSnapshot(RawBatchMode.SMALL)){codec.decode(small,File(root,"out-small"))}
        val second=async(Dispatchers.Default+RawBatchSnapshot(RawBatchMode.LARGE)){codec.decode(large,File(root,"out-large"))}
        try {
            withContext(Dispatchers.IO){assertTrue("Both leases may enter independently",entered.await(5,TimeUnit.SECONDS))}
            assertEquals(0,calls.get())
            assertEquals(mapOf("small.dng" to "small","large.dng" to "large"),api.decodes.associate{it.input to it.request.getString("rawBatch")})
            assertTrue(api.decodes.all{it.request.getInt("memoryBudgetMiB")==128})
            release.countDown();withTimeout(5000){first.await();second.await()}
            assertTrue(NativeWorkReservations.detailRetryIdle());assertEquals(2,api.destroys.get())
        }finally{release.countDown();first.cancelAndJoin();second.cancelAndJoin();root.deleteRecursively()}
    }
    @Test fun metadataAndExportNeitherReadTheSettingNorTransmitRawBatch()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("raw-batch-nonimport").toFile();val api=Api();val calls=AtomicInteger()
        val codec=PhotoCodecs(root,api,memorySnapshot=high,rawBatchPreference={calls.incrementAndGet();error("Only imports read RAW batching")})
        try {
            val raw=tiff(root,"camera.bin");val dng=tiff(root,"photo.dng",true)
            codec.probe(raw);codec.probe(dng);codec.encode(dng,File(root,"out"),8,8,"JPEG",95)
            assertEquals(0,calls.get());assertEquals(listOf(256,128),api.probes.map{it.getInt("memoryBudgetMiB")})
            assertEquals(128,api.encodes.single().getInt("memoryBudgetMiB"))
            assertTrue((api.probes+api.encodes).all{!it.has("rawBatch")})
        }finally{root.deleteRecursively()}
    }
}
