package dev.filmroom.codec

import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.io.PhotoException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class JxlDngAdmissionTest {
    private val mib = 1024L * 1024
    private val high = { NativeMemorySnapshot(4096*mib,128*mib,false) }
    private class Api: CodecNativeApi {
        override val available=true
        val budgets=CopyOnWriteArrayList<Int>()
        override fun nativeCreate(root:String)=1L
        override fun nativeDestroy(handle:Long){}
        override fun nativeCancel(handle:Long){}
        override fun nativeProbe(handle:Long,input:String,budget:String):String { budgets.add(JSONObject(budget).getInt("memoryBudgetMiB")); return "{\"ok\":true}" }
        override fun nativeDecode(handle:Long,input:String,output:String,budget:String)=nativeProbe(handle,input,budget)
        override fun nativeEncode(handle:Long,input:String,output:String,parameters:String)=nativeProbe(handle,input,parameters)
    }
    private fun tiff(root:File,name:String="image.bin",compression:Int=52546,dng:Boolean=true,sub:Boolean=false,big:Boolean=false,photometric:Int=34892,subfileType:Int=0):File {
        val b=ByteBuffer.allocate(if(sub)92 else 62).order(if(big)ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        b.put(if(big)byteArrayOf(77,77) else byteArrayOf(73,73)); b.putShort(42); b.putInt(8); b.putShort((if(sub)2 else 4).toShort())
        b.putShort((if(dng)50706 else 256).toShort()); b.putShort(1); b.putInt(4); b.put(byteArrayOf(1,4,0,0))
        b.putShort((if(sub)330 else 259).toShort()); b.putShort((if(sub)4 else 3).toShort()); b.putInt(1)
        if(sub)b.putInt(38) else {b.putShort(compression.toShort());b.putShort(0)}
        if(!sub) {
            b.putShort(262);b.putShort(3);b.putInt(1);b.putShort(photometric.toShort());b.putShort(0)
            b.putShort(254);b.putShort(4);b.putInt(1);b.putInt(subfileType)
        }
        b.putInt(0)
        if(sub) {b.putShort(4);b.putShort(259);b.putShort(3);b.putInt(1);b.putShort(compression.toShort());b.putShort(0);b.putShort(256);b.putShort(4);b.putInt(1);b.putInt(6000);b.putShort(262);b.putShort(3);b.putInt(1);b.putShort(photometric.toShort());b.putShort(0);b.putShort(254);b.putShort(4);b.putInt(1);b.putInt(subfileType);b.putInt(0)}
        return File(root,name).apply {writeBytes(b.array())}
    }
    @Test fun boundedContentDetectionIgnoresSuffixAndMaker() {
        val root=kotlin.io.path.createTempDirectory("jxl-content").toFile()
        try {
            for(big in listOf(false,true)) for(sub in listOf(false,true)) assertTrue(JxlDngAdmission.matches(tiff(root,big=big,sub=sub)))
            assertFalse(JxlDngAdmission.matches(tiff(root,"Samsung.DNG",compression=7)))
            assertFalse(JxlDngAdmission.matches(tiff(root,"Samsung.DNG",dng=false)))
            assertFalse(JxlDngAdmission.matches(tiff(root,photometric=2)))
            assertFalse(JxlDngAdmission.matches(tiff(root,subfileType=1)))
            assertFalse(JxlDngAdmission.matches(tiff(root,sub=true,photometric=2)))
            assertFalse(JxlDngAdmission.matches(tiff(root,sub=true,subfileType=1)))
            assertFalse(JxlDngAdmission.matches(File(root,"spoof.DNG").apply{writeText("Samsung JPEG XL 52546")}))
            assertFalse(JxlDngAdmission.matches(File(root,"missing.DNG")))
            val valid=tiff(root,sub=true).readBytes()
            for(size in 0 until valid.size) assertFalse("truncation $size",JxlDngAdmission.matches(File(root,"short.DNG").apply{writeBytes(valid.copyOf(size))}))
            fun bad(at:Int,value:Int) {val bytes=valid.copyOf();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at,value);assertFalse(JxlDngAdmission.matches(File(root,"bad.DNG").apply{writeBytes(bytes)}))}
            bad(30,8) // cyclic SubIFD
            bad(30,Int.MAX_VALUE) // offset outside file
            bad(26,Int.MAX_VALUE) // huge count
            bad(34,8) // root next cycle, even though JXL exists
            val excessive=valid.copyOf();excessive[8]=1;excessive[9]=1
            assertFalse(JxlDngAdmission.matches(File(root,"many.DNG").apply{writeBytes(excessive)}))
        } finally {root.deleteRecursively()}
    }
    @Test fun onlyJxlDecodeReceives384AndNormalRoutesStayUnchanged()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("jxl-budget").toFile();val api=Api()
        val codec=PhotoCodecs(root,api,memorySnapshot=high,memoryBudgetMiB={512})
        try {
            codec.decode(tiff(root,"renamed.nef",sub=true),File(root,"out"))
            codec.decode(tiff(root,"normal.dng",compression=7),File(root,"out"))
            codec.decode(tiff(root,"camera.dng",dng=false),File(root,"out"))
            codec.decode(File(root,"spoof.DNG").apply{writeText("JPEG XL")},File(root,"out"))
            val outside=kotlin.io.path.createTempDirectory("outside-jxl").toFile()
            try {codec.decode(tiff(outside),File(root,"out"))} finally {outside.deleteRecursively()}
            codec.probe(tiff(root));codec.encode(File(root,"in"),File(root,"out"),8,8,"JPEG",90)
            assertEquals(listOf(384,128,256,128,128,128,128),api.budgets.toList())
        } finally {root.deleteRecursively()}
    }
    @Test fun missingBudgetAndLowMemoryRefuseBeforeDecode()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("jxl-low").toFile();val input=tiff(root)
        try {
            val cases=listOf<()->PhotoCodecs>(
                {PhotoCodecs(root,Api())},
                {PhotoCodecs(root,Api(),memorySnapshot=high,memoryBudgetMiB={0})},
                {PhotoCodecs(root,Api(),memorySnapshot={NativeMemorySnapshot(4096*mib,128*mib,true)},memoryBudgetMiB={512})},
                {PhotoCodecs(root,Api(),memorySnapshot={NativeMemorySnapshot(768*mib,128*mib,false)},memoryBudgetMiB={512})})
            for(make in cases) {try {make().decode(input,File(root,"out"));fail("must refuse")}catch(_:PhotoException){}}
            val api=Api();PhotoCodecs(root,api,memorySnapshot=high,memoryBudgetMiB={192}).decode(input,File(root,"out"))
            assertEquals(listOf(192),api.budgets.toList()) // do not override a smaller device budget
        } finally {root.deleteRecursively()}
    }
    @Test fun sharedLedgerChargesFrozen384BeforeJni()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("jxl-ledger").toFile();val api=Api();val calls=AtomicInteger()
        val memory={NativeMemorySnapshot(1280*mib,128*mib,false)} // capacity544
        val codec=PhotoCodecs(root,api,memorySnapshot=memory,memoryBudgetMiB={calls.incrementAndGet();512})
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val holder=launch {NativeWorkReservations.run(160*mib,memory){entered.complete(Unit);release.await()}}
        entered.await()
        try {
            val decode=async {codec.decode(tiff(root),File(root,"out"))}
            withTimeout(5000){while(calls.get()==0)delay(10)};delay(150)
            assertTrue("400+176 exceeds544; a128/256 lease would wrongly enter",api.budgets.isEmpty())
            release.complete(Unit);holder.join();withTimeout(5000){decode.await()}
            assertEquals(listOf(384),api.budgets.toList());assertEquals(1,calls.get());assertTrue(NativeWorkReservations.detailRetryIdle())
        } finally {release.complete(Unit);holder.join();root.deleteRecursively()}
    }
}
