package dev.filmroom

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.codec.*
import dev.filmroom.io.*
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf

/** Framework/ownership test with explicit fake JNI, not a real DNG decoding claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RawOwnershipHostTest {
    private class Api(private val warningCode:String?=null):CodecNativeApi {
        override val available=true
        var blocking=false;val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var metadataOnly=false
        var lastBatch:String?=null
        override fun nativeCreate(root:String)=1L
        override fun nativeCancel(handle:Long){release.countDown()}
        override fun nativeDestroy(handle:Long){}
        private fun response()=JSONObject("{\"ok\":true,\"width\":8,\"height\":8,\"outputOrientation\":1,\"schemaVersion\":1,\"format\":\"DNG\",\"orientationApplied\":true,\"outputBytes\":768,\"warnings\":[],\"outputValid\":true}").apply{warningCode?.let{put("warnings",org.json.JSONArray().put(JSONObject().put("code",it).put("message","PRIVATE RAW NAME")))}}.toString()
        override fun nativeProbe(handle:Long,input:String,budget:String):String{metadataOnly=JSONObject(budget).optBoolean("metadataOnly");return response()}
        override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String{
            lastBatch=JSONObject(budget).optString("rawBatch")
            File(output).writeBytes(ByteArray(8*8*12));entered.countDown()
            if(blocking)check(release.await(10,TimeUnit.SECONDS))
            return response()
        }
        override fun nativeEncode(handle:Long,input:String,output:String,parameters:String)="{\"ok\":false,\"code\":\"INVALID_INPUT\"}"
    }
    private fun setup(api:Api,preference:()->dev.filmroom.model.RawBatchMode={dev.filmroom.model.RawBatchMode.AUTO}):Pair<PhotoRepository,Uri> {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val uri=Uri.parse("content://dev.filmroom.synthetic-test/raw")
        shadowOf(context.contentResolver).registerInputStream(uri,ByteArrayInputStream(byteArrayOf(0x49,0x49,42,0,8,0,0,0)))
        return PhotoRepository(context,PhotoCodecs(context.cacheDir,api,memorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)},rawBatchPreference=preference)) to uri
    }
    @Test fun batchChoiceIsFrozenBeforePrivateInputCopyAndOnlyNextImportUsesNewSetting()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        var choice=dev.filmroom.model.RawBatchMode.SMALL
        var calls=0
        val api=Api();val(repo,uri)=setup(api){calls++;choice}
        val bytes=byteArrayOf(0x49,0x49,42,0,8,0,0,0)
        shadowOf(app.contentResolver).registerInputStream(uri,object:ByteArrayInputStream(bytes){
            override fun read(b:ByteArray,off:Int,len:Int):Int {choice=dev.filmroom.model.RawBatchMode.LARGE;return super.read(b,off,len)}
        })
        val first=repo.import(uri)
        try{assertEquals("small",api.lastBatch);assertEquals(1,calls)}finally{first.preview.recycle();repo.discard(first.source)}
        shadowOf(app.contentResolver).registerInputStream(uri,ByteArrayInputStream(bytes))
        val second=repo.import(uri)
        try{assertEquals("large",api.lastBatch);assertEquals(2,calls)}finally{second.preview.recycle();repo.discard(second.source)}
    }
    @Test fun rawRestoreUsesMetadataOnlyAndPreservesBorrowedSpool()=runBlocking {
        val api=Api();val (repo,uri)=setup(api);val loaded=repo.import(uri)
        try{
            val raw=loaded.source.linearFile!!;assertEquals(768L,raw.length())
            val restored=repo.reopen(loaded.source.cacheKey,"照片",512,loaded.source.rawCacheKey)
            try{assertTrue(api.metadataOnly);assertEquals(raw,restored.source.linearFile);assertEquals(8,restored.preview.width)}finally{restored.preview.recycle()}
            assertTrue(raw.exists());assertTrue(loaded.source.localFile.exists())
            assertThrows(PhotoException::class.java){runBlocking{repo.reopen(loaded.source.cacheKey,"照片",512,"invalid")}}
            assertTrue(raw.exists())
        }finally{loaded.preview.recycle();repo.discard(loaded.source)}
    }
    @Test fun cancelledNativeImportCleansBothNewFiles()=runBlocking {
        val api=Api().apply{blocking=true};val(repo,uri)=setup(api)
        val context=ApplicationProvider.getApplicationContext<Application>();val dir=File(context.cacheDir,"photo-sources")
        val before=dir.listFiles().orEmpty().map{it.name}.toSet()
        val task=launch(Dispatchers.Default){repo.import(uri)}
        withContext(Dispatchers.IO){assertTrue(api.entered.await(5,TimeUnit.SECONDS))}
        task.cancelAndJoin()
        assertEquals(before,dir.listFiles().orEmpty().map{it.name}.toSet())
    }
    @Test fun hsvWarningFlowsIntoPhotoInfoSourceAndSurvivesRawRestore()=runBlocking {
        val api=Api("DNG_CAMERA_HSV_LOOK_NOT_APPLIED");val(repo,uri)=setup(api);val loaded=repo.import(uri)
        try {
            val details=checkNotNull(loaded.source.developmentWarning)
            assertTrue(details.contains("色相／饱和度校正表"));assertTrue(details.contains("不复刻相机直出"));assertFalse(details.contains("PRIVATE"))
            val restored=repo.reopen(loaded.source.cacheKey,"照片",512,loaded.source.rawCacheKey)
            try {assertEquals(details,restored.source.developmentWarning);assertTrue(api.metadataOnly)}
            finally {restored.preview.recycle()}
        } finally {loaded.preview.recycle();repo.discard(loaded.source)}
    }

    @Test fun displayBitmapClippingCannotChangeRawEffectInputOrBeRecycledByCacheRelease()=runBlocking {
        val root=kotlin.io.path.createTempDirectory("raw-display-").toFile()
        try {
            val raw=File(root,"input.rgb")
            val bytes=java.nio.ByteBuffer.allocate(4*4*12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(16){bytes.putFloat(5.734f).putFloat(-0.02f).putFloat(0.1234567f)}
            raw.writeBytes(bytes.array())
            val display=LinearSpool.preview(raw,4,4,4,coroutineContext)
            try {
                val source=PhotoSource(raw,"test","image/x-adobe-dng",4,4,raw.length(),linearFile=raw)
                assertEquals(255,(display.getPixel(0,0) ushr 16) and 255)
                assertEquals(0,(display.getPixel(0,0) ushr 8) and 255)
                val input=source.linearPreview(4)!!.buffer()
                assertEquals(5.734f,input.float,0f);assertEquals(-0.02f,input.float,0f)
                source.clearLinearPreview();assertFalse(display.isRecycled)
                assertArrayEquals(bytes.array(),raw.readBytes())
            }finally{display.recycle()}
        }finally{root.deleteRecursively()}
    }
}
