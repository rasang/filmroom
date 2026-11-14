package dev.filmroom

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import dev.filmroom.codec.*
import dev.filmroom.io.*
import java.io.File
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
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
    private class Api(var warningCode:String?=null):CodecNativeApi {
        override val available=true
        var blocking=false;val entered=CountDownLatch(1);val release=CountDownLatch(1)
        var metadataOnly=false
        var lastBatch:String?=null
        var policy="camera-matrix-linear-srgb-v1"
        var format="DNG"
        var warningCodes:List<String> = emptyList()
        var decodeCalls=0
        var decodeFailureCode:String?=null
        var pixelValue=0f
        override fun nativeCreate(root:String)=1L
        override fun nativeCancel(handle:Long){release.countDown()}
        override fun nativeDestroy(handle:Long){}
        private fun response()=JSONObject("{\"ok\":true,\"width\":8,\"height\":8,\"outputOrientation\":1,\"schemaVersion\":1,\"orientationApplied\":true,\"outputBytes\":768,\"outputValid\":true}")
            .put("format",format).put("developmentPolicy",policy)
            .put("warnings",org.json.JSONArray().apply {
                for(code in listOfNotNull(warningCode)+warningCodes)
                    put(JSONObject().put("code",code).put("message","PRIVATE RAW NAME"))
            }).toString()
        override fun nativeProbe(handle:Long,input:String,budget:String):String{metadataOnly=JSONObject(budget).optBoolean("metadataOnly");return response()}
        override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String{
            decodeCalls++
            lastBatch=JSONObject(budget).optString("rawBatch")
            val pixels=java.nio.ByteBuffer.allocate(8*8*12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(8*8*3){pixels.putFloat(pixelValue)}
            File(output).writeBytes(pixels.array());entered.countDown()
            if(blocking)check(release.await(10,TimeUnit.SECONDS))
            decodeFailureCode?.let{return JSONObject().put("ok",false).put("code",it).toString()}
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
            assertTrue(RawDevelopmentCache.recordFile(raw).isFile)
            val restored=repo.reopen(loaded.source.cacheKey,"照片",512,loaded.source.rawCacheKey)
            try{assertTrue(api.metadataOnly);assertEquals(raw,restored.source.linearFile);assertEquals(8,restored.preview.width);assertEquals(1,api.decodeCalls)}finally{restored.preview.recycle()}
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

    @Test fun changedPolicyRebuildsPixelsAndWarningsWithoutChangingBorrowedSpool()=runBlocking {
        dev.filmroom.i18n.L10n.choose(ApplicationProvider.getApplicationContext(),"zh")
        val api=Api();val(repo,uri)=setup(api);val loaded=repo.import(uri)
        var refreshed:LoadedPhoto?=null
        try {
            val old=checkNotNull(loaded.source.linearFile);val oldBytes=old.readBytes()
            api.policy="camera-matrix-linear-srgb-lensfun-v1"
            api.warningCode="LENSFUN_DISTORTION_APPLIED";api.pixelValue=0.25f
            val restored=repo.reopen(loaded.source.cacheKey,"照片",512,loaded.source.rawCacheKey)
            refreshed=restored
            val current=checkNotNull(restored.source.linearFile)
            assertNotEquals(old,current);assertEquals(2,api.decodeCalls)
            assertArrayEquals(oldBytes,old.readBytes())
            assertFalse(oldBytes.contentEquals(current.readBytes()))
            assertEquals("已应用匹配镜头配置的畸变校正。",restored.source.developmentWarning)
            assertTrue(RawDevelopmentCache.recordFile(current).isFile)
            val reopened=repo.reopen(restored.source.cacheKey,"照片",512,restored.source.rawCacheKey)
            try {assertEquals(current,reopened.source.linearFile);assertEquals(2,api.decodeCalls)}
            finally {reopened.preview.recycle()}
        } finally {
            refreshed?.let{it.preview.recycle();repo.discard(it.source)}
            loaded.preview.recycle();repo.discard(loaded.source)
        }
    }

    @Test fun legacyMissingOrMalformedPolicyRecordsRequireFreshDecode()=runBlocking {
        for(malformed in listOf(false,true)) {
            val api=Api();val(repo,uri)=setup(api);val loaded=repo.import(uri)
            var refreshed:LoadedPhoto?=null
            try {
                val old=checkNotNull(loaded.source.linearFile)
                val record=RawDevelopmentCache.recordFile(old)
                if(malformed)record.writeText("{incomplete") else assertTrue(record.delete())
                val restored=repo.reopen(loaded.source.cacheKey,"照片",512,loaded.source.rawCacheKey)
                refreshed=restored
                assertEquals(2,api.decodeCalls)
                assertNotEquals(old,restored.source.linearFile)
                assertTrue(old.isFile)
                assertTrue(RawDevelopmentCache.recordFile(checkNotNull(restored.source.linearFile)).isFile)
            } finally {
                refreshed?.let{it.preview.recycle();repo.discard(it.source)}
                loaded.preview.recycle();repo.discard(loaded.source)
            }
        }
    }

    private data class OpticsRoute(val format:String,val oldPolicy:String,val newPolicy:String,val prefix:String,val oldDistortion:Boolean=true)
    private val opticsRoutes=listOf(
        OpticsRoute("ARW","camera-matrix-linear-srgb-v1","camera-matrix-linear-srgb-embedded-v2","EMBEDDED_LENS"),
        OpticsRoute("RAF","raf-xtrans-linear-srgb-v1","raf-xtrans-linear-srgb-embedded-v2","EMBEDDED_LENS"),
        OpticsRoute("CR3","camera-matrix-linear-srgb-lensfun-v1","camera-matrix-linear-srgb-lensfun-v2","LENSFUN"),
        OpticsRoute("CR3","camera-matrix-linear-srgb-lensfun-v2","camera-matrix-linear-srgb-lensfun-v3","LENSFUN"),
        OpticsRoute("RAF","raf-xtrans-linear-srgb-v1","raf-xtrans-linear-srgb-lensfun-v3","LENSFUN",false),
        OpticsRoute("DNG","rawler0.7.2 ColorMatrix(D65)+AsShotNeutral; not full Adobe/Camera2 processing","dng-cfa-linear-srgb-lensfun-v3","LENSFUN",false))
    private fun opticsApi(route:OpticsRoute)=Api().apply {
        format=route.format;policy=route.oldPolicy;warningCode=if(route.oldDistortion) "${route.prefix}_DISTORTION_APPLIED" else null
    }
    private fun upgradeOptics(api:Api,route:OpticsRoute) {
        api.policy=route.newPolicy;api.pixelValue=0.375f
        api.warningCode="${route.prefix}_DISTORTION_APPLIED"
        api.warningCodes=listOf("${route.prefix}_VIGNETTING_APPLIED","${route.prefix}_TCA_APPLIED")
    }

    @Test fun allOpticsPolicyUpgradesRebuildPixelsAndMessagesOnceThenReuseTheirCurrentSpool()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        dev.filmroom.i18n.L10n.choose(app,"zh")
        for(route in opticsRoutes) {
            val api=opticsApi(route);val(repo,uri)=setup(api);val loaded=repo.import(uri)
            var refreshed:LoadedPhoto?=null
            try {
                val sourceBytes=loaded.source.localFile.readBytes()
                val old=checkNotNull(loaded.source.linearFile);val oldBytes=old.readBytes()
                val oldRecord=RawDevelopmentCache.recordFile(old);val oldRecordBytes=oldRecord.readBytes()
                assertEquals(route.oldPolicy,JSONObject(oldRecord.readText()).getString("developmentPolicy"))
                assertFalse(loaded.source.developmentWarning.orEmpty().contains("暗角"))
                upgradeOptics(api,route)
                val restored=repo.reopen(loaded.source.cacheKey,"照片",512,loaded.source.rawCacheKey);refreshed=restored
                val current=checkNotNull(restored.source.linearFile)
                assertNotEquals(route.format,old,current);assertEquals(2,api.decodeCalls)
                assertArrayEquals(sourceBytes,loaded.source.localFile.readBytes())
                assertArrayEquals(oldBytes,old.readBytes());assertArrayEquals(oldRecordBytes,oldRecord.readBytes())
                assertFalse(oldBytes.contentEquals(current.readBytes()))
                assertEquals(route.newPolicy,JSONObject(RawDevelopmentCache.recordFile(current).readText()).getString("developmentPolicy"))
                val messages=restored.source.developmentWarning!!
                assertEquals(3,messages.lines().size)
                assertTrue(messages.contains("畸变") && messages.contains("暗角") && messages.contains("色差"))
                assertFalse(messages.contains("PRIVATE"))
                val reopened=repo.reopen(restored.source.cacheKey,"照片",512,restored.source.rawCacheKey)
                try {
                    assertEquals(current,reopened.source.linearFile);assertEquals(2,api.decodeCalls)
                    assertEquals(messages,reopened.source.developmentWarning)
                    dev.filmroom.i18n.L10n.choose(app,"en")
                    assertTrue(reopened.source.developmentWarning!!.contains("vignetting correction"))
                    assertTrue(reopened.source.developmentWarning!!.contains("chromatic aberration correction"))
                    assertEquals(2,api.decodeCalls)
                } finally {reopened.preview.recycle();dev.filmroom.i18n.L10n.choose(app,"zh")}
            } finally {
                refreshed?.let{it.preview.recycle();repo.discard(it.source)}
                loaded.preview.recycle();repo.discard(loaded.source)
            }
        }
    }

    @Test fun allOpticsMigrationFailuresPreservePreviousSessionSourceSpoolAndRecordUntilExplicitRetry()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        for(route in opticsRoutes)for(code in listOf("DISK_BUDGET","MEMORY_BUDGET","DECODE_FAILED")) {
            val api=opticsApi(route);val(repo,uri)=setup(api);val loaded=repo.import(uri)
            val preferences=app.getSharedPreferences("raw-optics-migration-session",0)
            preferences.edit().clear().putString("source",loaded.source.cacheKey)
                .putString("rawSource",loaded.source.rawCacheKey).commit()
            var refreshed:LoadedPhoto?=null
            try {
                val old=checkNotNull(loaded.source.linearFile);val oldBytes=old.readBytes()
                val sourceBytes=loaded.source.localFile.readBytes()
                val record=RawDevelopmentCache.recordFile(old);val recordBytes=record.readBytes()
                val directory=checkNotNull(old.parentFile)
                val before=directory.listFiles().orEmpty().map{it.name}.toSet()
                upgradeOptics(api,route);api.decodeFailureCode=code
                val error=assertThrows(PhotoException::class.java){runBlocking{repo.reopen(loaded.source.cacheKey,"照片",512,loaded.source.rawCacheKey)}}
                assertTrue("${route.format} / $code",RawRestoreSession.retainAfterFailure(preferences,error))
                assertEquals(2,api.decodeCalls)
                assertEquals(loaded.source.cacheKey,preferences.getString("source",null))
                assertEquals(old.name,preferences.getString("rawSource",null))
                assertEquals(before,directory.listFiles().orEmpty().map{it.name}.toSet())
                dev.filmroom.lifecycle.PrivateWorkRecovery.recover(app.cacheDir,app.filesDir,
                    preferences.getString("source",null),preferences.getString("rawSource",null))
                assertArrayEquals(sourceBytes,loaded.source.localFile.readBytes())
                assertArrayEquals(oldBytes,old.readBytes());assertArrayEquals(recordBytes,record.readBytes())
                assertEquals(2,api.decodeCalls) // Recovery only preserves the saved files; it never retries decode.
                api.decodeFailureCode=null
                val restored=repo.reopen(preferences.getString("source",null)!!,"照片",512,preferences.getString("rawSource",null));refreshed=restored
                val current=checkNotNull(restored.source.linearFile)
                assertNotEquals(old,current);assertEquals(3,api.decodeCalls)
                assertEquals(route.newPolicy,JSONObject(RawDevelopmentCache.recordFile(current).readText()).getString("developmentPolicy"))
                assertEquals(3,restored.source.developmentWarning!!.lines().size)
                assertArrayEquals(oldBytes,old.readBytes());assertArrayEquals(recordBytes,record.readBytes())
            } finally {
                refreshed?.let{it.preview.recycle();repo.discard(it.source)}
                loaded.preview.recycle();repo.discard(loaded.source);preferences.edit().clear().commit()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.awaitRestoreState(check:()->Boolean) {
        repeat(600) { advanceUntilIdle();if(check())return;Thread.sleep(5) }
        fail("Editor restore state did not settle")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun editorOpticsMigrationWaitsForManualRetryAndLaterKeepsSavedSession()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app=ApplicationProvider.getApplicationContext<Application>()
        val settings=dev.filmroom.model.EditorPreferences(app)
        val previousRestore=settings.restoreLastPhoto
        val preferences=app.getSharedPreferences("local-session",0)
        try {
            settings.restoreLastPhoto=true
            for((postpone,route) in listOf(false to opticsRoutes.last(),true to opticsRoutes.first())) {
                val api=opticsApi(route);val(repo,uri)=setup(api);val loaded=repo.import(uri)
                val old=checkNotNull(loaded.source.linearFile);val oldBytes=old.readBytes()
                preferences.edit().clear().putString("source",loaded.source.cacheKey)
                    .putString("rawSource",loaded.source.rawCacheKey).putString("name","照片").commit()
                var model:EditorViewModel?=null
                try {
                    upgradeOptics(api,route);api.decodeFailureCode="MEMORY_BUDGET"
                    val editor=EditorViewModel(app);model=editor
                    // Replace only JNI's repository boundary before the queued initializer runs.
                    // The actual ViewModel restore/retry state machine and disk ownership stay real.
                    EditorViewModel::class.java.getDeclaredField("repository").apply{isAccessible=true}.set(editor,repo)
                    editor.setPrewarmForeground(false)
                    awaitRestoreState {editor.state.value.restoreRetryAvailable}
                    assertEquals(2,api.decodeCalls)
                    assertNull(editor.state.value.photo);assertNull(editor.state.value.busy)
                    assertTrue(editor.state.value.startupReady)
                    assertEquals(loaded.source.cacheKey,preferences.getString("source",null))
                    assertEquals(old.name,preferences.getString("rawSource",null))
                    advanceTimeBy(120_000);runCurrent()
                    assertEquals("No automatic retry while the prompt is visible",2,api.decodeCalls)
                    assertArrayEquals(oldBytes,old.readBytes())
                    if(postpone) {
                        editor.postponeRestore()
                        assertFalse(editor.state.value.restoreRetryAvailable)
                        assertNull(editor.state.value.message)
                        editor.retryRestore() // A hidden/dismissed prompt does not initiate another attempt.
                        advanceTimeBy(120_000);runCurrent()
                        assertEquals(2,api.decodeCalls)
                        dev.filmroom.lifecycle.PrivateWorkRecovery.recover(app.cacheDir,app.filesDir,
                            preferences.getString("source",null),preferences.getString("rawSource",null))
                        assertEquals(loaded.source.cacheKey,preferences.getString("source",null))
                        assertEquals(old.name,preferences.getString("rawSource",null))
                        assertTrue(loaded.source.localFile.isFile)
                        assertArrayEquals(oldBytes,old.readBytes());assertTrue(RawDevelopmentCache.recordFile(old).isFile)
                    } else {
                        api.decodeFailureCode=null
                        editor.retryRestore();editor.retryRestore()
                        awaitRestoreState {editor.state.value.photo!=null && editor.state.value.busy==null}
                        assertEquals("Two taps must start one explicit retry",3,api.decodeCalls)
                        assertFalse(editor.state.value.restoreRetryAvailable)
                        val restored=editor.state.value.photo!!
                        assertNotEquals(old,restored.linearFile)
                        assertEquals(restored.rawCacheKey,preferences.getString("rawSource",null))
                        assertEquals(route.newPolicy,JSONObject(RawDevelopmentCache.recordFile(restored.linearFile!!).readText()).getString("developmentPolicy"))
                        assertArrayEquals(oldBytes,old.readBytes())
                    }
                } finally {
                    model?.viewModelScope?.cancel()
                    model?.state?.value?.original?.let{if(!it.isRecycled)it.recycle()}
                    model?.state?.value?.photo?.let{repo.discard(it)}
                    loaded.preview.recycle();repo.discard(loaded.source)
                    preferences.edit().clear().commit()
                }
            }
        } finally {
            settings.restoreLastPhoto=previousRestore
            preferences.edit().clear().commit();Dispatchers.resetMain()
        }
    }

    @Test fun failedPolicyRebuildRetainsSessionThroughRecoveryAndCanRetry()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        for(code in listOf("DISK_BUDGET","MEMORY_BUDGET","DECODE_FAILED")) {
            val api=Api();val(repo,uri)=setup(api);val loaded=repo.import(uri)
            val preferences=app.getSharedPreferences("raw-migration-session",0)
            preferences.edit().clear().putString("source",loaded.source.cacheKey)
                .putString("rawSource",loaded.source.rawCacheKey).commit()
            var refreshed:LoadedPhoto?=null
            try {
                val old=checkNotNull(loaded.source.linearFile);val oldBytes=old.readBytes()
                val directory=checkNotNull(old.parentFile)
                val before=directory.listFiles().orEmpty().map{it.name}.toSet()
                api.policy="camera-matrix-linear-srgb-lensfun-v1";api.decodeFailureCode=code
                val error=assertThrows(PhotoException::class.java){runBlocking{repo.reopen(loaded.source.cacheKey,"照片",512,loaded.source.rawCacheKey)}}
                assertTrue("Keep valid session after $code",RawRestoreSession.retainAfterFailure(preferences,error))
                assertEquals(2,api.decodeCalls)
                assertEquals(loaded.source.cacheKey,preferences.getString("source",null))
                assertEquals(old.name,preferences.getString("rawSource",null))
                assertArrayEquals(oldBytes,old.readBytes())
                assertTrue(loaded.source.localFile.isFile)
                assertTrue(RawDevelopmentCache.recordFile(old).isFile)
                assertEquals(before,directory.listFiles().orEmpty().map{it.name}.toSet())
                dev.filmroom.lifecycle.PrivateWorkRecovery.recover(app.cacheDir,app.filesDir,
                    preferences.getString("source",null),preferences.getString("rawSource",null))
                assertTrue(loaded.source.localFile.isFile);assertTrue(old.isFile)
                assertTrue(RawDevelopmentCache.recordFile(old).isFile)
                api.decodeFailureCode=null
                val restored=repo.reopen(preferences.getString("source",null)!!,"照片",512,preferences.getString("rawSource",null))
                refreshed=restored
                assertNotEquals(old,restored.source.linearFile);assertEquals(3,api.decodeCalls)
            } finally {
                refreshed?.let{it.preview.recycle();repo.discard(it.source)}
                loaded.preview.recycle();repo.discard(loaded.source);preferences.edit().clear().commit()
            }
        }
    }

    @Test fun missingOriginalExpiresSessionInsteadOfOfferingRepeatedRestore()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val api=Api();val(repo,_)=setup(api)
        val preferences=app.getSharedPreferences("raw-missing-session",0)
        preferences.edit().clear().putString("source","source-missing.image")
            .putString("rawSource","raw-source-12345678-abcd-4321-abcd-123456789abc.rgb").commit()
        val error=assertThrows(PhotoException::class.java){runBlocking{repo.reopen(preferences.getString("source",null)!!,"照片",512,preferences.getString("rawSource",null))}}
        assertFalse(RawRestoreSession.retainAfterFailure(preferences,error))
        assertFalse(preferences.contains("source"));assertFalse(preferences.contains("rawSource"))
        assertEquals(0,api.decodeCalls)
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
