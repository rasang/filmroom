package dev.filmroom.codec

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class PhotoCodecsTest {
    @Test fun sizeCodesKeepSafeSpecificMessagesAndImportReason()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("codec-size-test").toFile()
        try {
            for((code,key) in mapOf("PIXEL_LIMIT" to "text_5c55db3ba035","EDGE_LIMIT" to "image_edge_limit_32768")) {
                val api=Fake("{\"ok\":false,\"error\":{\"code\":\"$code\",\"stage\":\"probe\"}}")
                try { PhotoCodecs(root,api,memorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}).probe(File(root,"private"));fail() }
                catch(e:dev.filmroom.io.PhotoException) {
                    assertEquals(dev.filmroom.io.PhotoException.Reason.TOO_LARGE,e.reason)
                    assertEquals(key,e.uiMessage?.key)
                    assertEquals(code,e.codecCode?.name)
                    assertFalse(e.message!!.contains("private"))
                }
            }
        } finally {root.deleteRecursively()}
    }

    private class Fake(var errorResponse:String="{\"ok\":false,\"code\":\"UNSUPPORTED_COMPRESSION\"}"):CodecNativeApi {
        override val available=true
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val running=AtomicBoolean(false);val cancelled=AtomicBoolean(false);val destroyed=AtomicBoolean(false);val creates=AtomicInteger()
        override fun nativeCreate(root:String)=creates.incrementAndGet().toLong()
        override fun nativeCancel(handle:Long){cancelled.set(true);release.countDown()}
        override fun nativeDestroy(handle:Long){check(!running.get());destroyed.set(true)}
        override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String {
            running.set(true);entered.countDown();check(release.await(10,TimeUnit.SECONDS));running.set(false)
            return "{\"ok\":true,\"width\":8,\"height\":8,\"outputValid\":true}"
        }
        override fun nativeProbe(handle:Long,input:String,budget:String)=errorResponse
        override fun nativeEncode(handle:Long,input:String,output:String,parameters:String)=nativeDecode(handle,input,output,parameters)
    }
    @Test fun cancellationStopsWorkerBeforeDestroy()=runBlocking {
        val api=Fake();val root=createTempDir(prefix="codec-test-");val codecs=PhotoCodecs(root,api,memorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)})
        try {
            val work=launch(Dispatchers.Default){codecs.decode(File(root,"input"),File(root,"output"))}
            withContext(Dispatchers.IO){assertTrue(api.entered.await(5,TimeUnit.SECONDS))}
            work.cancelAndJoin()
            assertTrue(api.cancelled.get());assertTrue(api.destroyed.get());assertFalse(api.running.get())
        } finally {root.deleteRecursively()}
    }
    @Test fun safeErrorAndFreshHandleEachCall()=runBlocking {
        val api=Fake();val root=createTempDir(prefix="codec-test-");val codecs=PhotoCodecs(root,api,memorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)})
        try {
            repeat(2){try{codecs.probe(File(root,"secret-name"));fail()}catch(e:dev.filmroom.io.PhotoException){assertTrue(e.message!!.contains("暂不支持"));assertFalse(e.message!!.contains("secret-name"))}}
            assertEquals(2,api.creates.get());assertTrue(api.destroyed.get())
        }finally{root.deleteRecursively()}
    }
    @Test fun lowMemoryRejectsBeforeNativeDecodeButAllowsMetadataRestore()=runBlocking {
        val api=Fake();val root=kotlin.io.path.createTempDirectory("codec-low-memory").toFile();val codecs=PhotoCodecs(root,api,memorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}){0}
        try{
            try{codecs.decode(File(root,"input"),File(root,"output"));fail()}catch(e:dev.filmroom.io.PhotoException){assertTrue(e.message!!.contains("可用内存不足"))}
            assertEquals(1L,api.entered.count)
            try{codecs.probe(File(root,"input"));fail()}catch(e:dev.filmroom.io.PhotoException){assertTrue(e.message!!.contains("暂不支持"))}
        }finally{root.deleteRecursively()}
    }

    @Test fun nativeErrorRetainsOnlyAllowlistedCodeStageAndSafeChinese()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("codec-error-test").toFile()
        try {
            val cases=mapOf("UNSUPPORTED_DNG_OPCODE" to "必需校正", "UNSUPPORTED_DNG_CALIBRATION" to "相机校准", "IO_ERROR" to "读写失败", "INVALID_INPUT" to "不能确认", "PRIVATE_FILENAME" to "确认原因")
            cases.forEach { (code, expected) ->
                val api=Fake(org.json.JSONObject().put("ok",false).put("technical",org.json.JSONObject().put("rawWidth",4096).put("rawBitsMin","16").put("privatePath","content://secret/photo.dng")).put("error",org.json.JSONObject().put("code",code).put("stage","probe").put("message","content://secret/photo.dng")).toString())
                try { PhotoCodecs(root,api,memorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}).probe(File(root,"private-name"));fail() }
                catch(e:dev.filmroom.io.PhotoException) {
                    assertEquals(dev.filmroom.diagnostics.CodecErrorCode.fromId(code),e.codecCode)
                    assertEquals(dev.filmroom.diagnostics.CodecStage.PROBE,e.codecStage)
                    assertEquals(mapOf("rawWidth" to 4096L),e.rawTechnical!!.fields())
                    assertTrue(e.message!!.contains(expected));assertFalse(e.message!!.contains("secret"));assertFalse(e.message!!.contains("PRIVATE_FILENAME"))
                    assertTrue(api.destroyed.get())
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun freshOsLowMemoryRejectsHeavyCallAndMissingProviderFailsClosed()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("codec-os-memory").toFile()
        try {
            for(low in listOf(true,false)) {
                val api=Fake()
                val codec=if(low)PhotoCodecs(root,api,memorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(8L*1024*1024*1024,128L*1024*1024,true)}) else PhotoCodecs(root,api)
                try{codec.decode(File(root,"input"),File(root,"output"));fail()}catch(e:dev.filmroom.io.PhotoException){assertTrue(e.message!!.contains("可用内存不足"))}
                assertEquals(1L,api.entered.count);assertTrue(api.destroyed.get())
            }
        }finally{root.deleteRecursively()}
    }
    @Test fun waitingCodecUsesSharedTwoOperationLimitAndCancelsBeforeJni()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("codec-shared-limit").toFile()
        val memory={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        val release=CompletableDeferred<Unit>();val one=CompletableDeferred<Unit>();val two=CompletableDeferred<Unit>()
        val first=launch{dev.filmroom.engine.NativeWorkReservations.run(120L*1024*1024,memory){one.complete(Unit);release.await()}}
        val second=launch{dev.filmroom.engine.NativeWorkReservations.run(224L*1024*1024,memory){two.complete(Unit);release.await()}}
        one.await();two.await()
        val api=Fake();val codec=PhotoCodecs(root,api,memorySnapshot=memory)
        try {
            val waiting=launch{codec.decode(File(root,"input"),File(root,"output"))}
            delay(200);assertEquals(1L,api.entered.count)
            waiting.cancelAndJoin();assertTrue(api.destroyed.get());assertEquals(1L,api.entered.count)
        }finally{release.complete(Unit);first.join();second.join();root.deleteRecursively()}
        assertEquals(5,dev.filmroom.engine.NativeWorkReservations.run(120L*1024*1024,memory){5})
    }

}
