package dev.filmroom.codec

import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.io.PhotoException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RawMetadataAdmissionTest {
    private val mib=1024L*1024
    private class Api:CodecNativeApi {
        override val available=true
        val probes=AtomicInteger();val destroyed=AtomicInteger();val handles=AtomicInteger()
        override fun nativeCreate(root:String)=handles.incrementAndGet().toLong()
        override fun nativeProbe(handle:Long,input:String,budget:String):String {probes.incrementAndGet();return "{\"ok\":true}"}
        override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String=error("not expected")
        override fun nativeEncode(handle:Long,input:String,output:String,parameters:String):String=error("not expected")
        override fun nativeCancel(handle:Long){}
        override fun nativeDestroy(handle:Long){destroyed.incrementAndGet()}
    }
    private fun file(root:File,dng:Boolean,little:Boolean=true,name:String="photo.bin"):File {
        val bytes=ByteBuffer.allocate(26).order(if(little)ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        bytes.put(if(little)byteArrayOf(73,73) else byteArrayOf(77,77));bytes.putShort(42);bytes.putInt(8)
        bytes.putShort(1);bytes.putShort((if(dng)50706 else 256).toShort());bytes.putShort(1);bytes.putInt(4);bytes.putInt(1);bytes.putInt(0)
        return File(root,name).apply {writeBytes(bytes.array())}
    }
    @Test fun rootVersionNotExtensionControlsResourceHint() {
        val root=kotlin.io.path.createTempDirectory("raw-probe-hint").toFile()
        try {
            for(little in listOf(true,false)) {
                assertEquals(0L,RawMetadataAdmission.estimatedBytes(file(root,true,little,"misleading.NEF")))
                assertEquals(32*mib,RawMetadataAdmission.estimatedBytes(file(root,false,little,"misleading.DNG")))
            }
            assertEquals(0L,RawMetadataAdmission.estimatedBytes(File(root,"jpeg").apply {writeBytes(byteArrayOf(-1,-40,-1))}))
        } finally {root.deleteRecursively()}
    }
    @Test fun rw2ContentUsesTheCameraLeaseRegardlessOfExtension() {
        val root=kotlin.io.path.createTempDirectory("rw2-probe-hint").toFile()
        try {
            val input=file(root,false,name="misleading.jpeg")
            val bytes=input.readBytes();bytes[2]=85;input.writeBytes(bytes)
            assertEquals(32*mib,RawMetadataAdmission.estimatedBytes(input))
            bytes[4]=0;input.writeBytes(bytes)
            assertEquals(0L,RawMetadataAdmission.estimatedBytes(input))
        }finally{root.deleteRecursively()}
    }
    @Test fun rafSignatureUsesCameraLeaseWithoutTrustingExtension() {
        val root=kotlin.io.path.createTempDirectory("raf-probe-hint").toFile()
        try {
            val input=File(root,"renamed.jpg")
            input.writeBytes("FUJIFILMCCD-RAW 0201".toByteArray(Charsets.US_ASCII))
            assertEquals(32*mib,RawMetadataAdmission.estimatedBytes(input))
            assertTrue(dev.filmroom.io.formats.PhotoHeaders.isFujifilmRaf(input.readBytes()))
            val hint=dev.filmroom.io.formats.PhotoHeaders.sniff(input.readBytes())
            assertEquals(dev.filmroom.io.formats.PhotoFormat.CAMERA_RAW,hint.format)
            assertTrue(hint.requiresFullDecoderValidation)
            input.writeBytes("FUJIFILMinvalid".toByteArray(Charsets.US_ASCII))
            assertEquals(0L,RawMetadataAdmission.estimatedBytes(input))
            assertFalse(dev.filmroom.io.formats.PhotoHeaders.isFujifilmRaf(input.readBytes()))
        }finally{root.deleteRecursively()}
    }
    @Test fun cr3OrfAndOriContentKeepTheCameraMetadataLease() {
        val root=kotlin.io.path.createTempDirectory("additional-raw-hint").toFile()
        try {
            val input=File(root,"renamed.jpeg")
            for(magic in listOf("IIRO","IIRS","MMOR")) {
                input.writeBytes(magic.toByteArray(Charsets.US_ASCII)+ByteArray(12))
                assertEquals(32*mib,RawMetadataAdmission.estimatedBytes(input))
            }
            input.writeBytes(byteArrayOf(0,0,0,32)+"ftypcrx ".toByteArray(Charsets.US_ASCII)+ByteArray(4))
            assertEquals(32*mib,RawMetadataAdmission.estimatedBytes(input))
            input.writeBytes(byteArrayOf(0,0,0,32)+"ftypheic".toByteArray(Charsets.US_ASCII)+ByteArray(4))
            assertEquals(0L,RawMetadataAdmission.estimatedBytes(input))
        }finally{root.deleteRecursively()}
    }
    @Test fun malformedRootCannotSelectUnchargedCameraProfilePath() {
        val root=kotlin.io.path.createTempDirectory("raw-probe-malformed").toFile()
        try {
            val input=file(root,false)
            var bytes=input.readBytes();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(8,257.toShort());input.writeBytes(bytes)
            assertEquals(0L,RawMetadataAdmission.estimatedBytes(input)) // Native rejects count before camera parser.
            bytes=file(root,false).readBytes();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4,Int.MAX_VALUE);input.writeBytes(bytes)
            assertEquals(0L,RawMetadataAdmission.estimatedBytes(input)) // Native fails root read, never RawLoader.
            bytes=file(root,true).readBytes();ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(12,99.toShort());input.writeBytes(bytes)
            assertEquals(0L,RawMetadataAdmission.estimatedBytes(input)) // Bad version type still belongs to DNG rejection.
        } finally {root.deleteRecursively()}
    }
    @Test fun missingMemoryEvidencePreservesDngRestoreButRejectsCameraBeforeJni()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("raw-probe-admission").toFile();val api=Api();val codec=PhotoCodecs(root,api)
        try {
            codec.probe(file(root,true,name="raw.nef"));assertEquals(1,api.probes.get())
            try {codec.probe(file(root,false,name="raw.dng"));fail("camera metadata must be admitted")}
            catch(_:PhotoException){}
            assertEquals(1,api.probes.get());assertEquals(2,api.destroyed.get())
        } finally {root.deleteRecursively()}
    }
    @Test fun cameraMetadataUses32Not128AndIgnoresDecodeBudgetPreference()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("raw-probe-small-budget").toFile();val api=Api()
        val codec=PhotoCodecs(root,api,memorySnapshot={NativeMemorySnapshot(320*mib,128*mib,false)},memoryBudgetMiB={0})
        try {codec.probe(file(root,false));assertEquals(1,api.probes.get());assertEquals(1,api.destroyed.get())}
        finally {root.deleteRecursively()}
    }
    @Test fun cameraMetadataWaitsBehindTwoHeavyJobsAndCancellationClosesHandle()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("raw-probe-concurrent").toFile();val api=Api()
        val memory={NativeMemorySnapshot(4L*1024*mib,128*mib,false)}
        val release=CompletableDeferred<Unit>();val one=CompletableDeferred<Unit>();val two=CompletableDeferred<Unit>()
        val a=launch {NativeWorkReservations.run(120*mib,memory){one.complete(Unit);release.await()}}
        val b=launch {NativeWorkReservations.run(128*mib,memory){two.complete(Unit);release.await()}}
        one.await();two.await()
        try {
            val codec=PhotoCodecs(root,api,memorySnapshot=memory)
            val waiting=launch {codec.probe(file(root,false,name="camera.raw"))}
            delay(150);assertEquals(0,api.probes.get())
            codec.probe(file(root,true,name="retained.dng"));assertEquals(1,api.probes.get())
            waiting.cancelAndJoin();assertEquals(1,api.probes.get());assertEquals(2,api.destroyed.get())
        } finally {release.complete(Unit);a.join();b.join();root.deleteRecursively()}
    }
}
