package dev.filmroom.diagnostics

import android.app.Application
import dev.filmroom.OperationTrace
import dev.filmroom.engine.NativeWorkUnavailable
import dev.filmroom.engine.PreviewAssetIdentityException
import dev.filmroom.i18n.L10n
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PreviewFailureDiagnosticsHostTest {
    private val secret="content://private/photo.jpg GPS=22.3 /data/user/0/private token"
    private fun <T> store(work:(DiagnosticStore)->T):T {
        val root=Files.createTempDirectory("preview-failure-").toFile()
        val clock=object:DiagnosticClock { override fun wallMs()=1000L;override fun elapsedMs()=1000L }
        val field=Diagnostics::class.java.getDeclaredField("store").apply{isAccessible=true}
        val previous=field.get(null)
        return try {DiagnosticStore(root,clock).use {store->field.set(null,store);work(store)}}
        finally {field.set(null,previous);root.deleteRecursively()}
    }
    @Test fun everyPreviewFailureStagePersistsAndRetainsOriginalThrowable()=store { store->runBlocking {
        for(stage in PreviewFailureStage.entries) {
            val failure=IllegalArgumentException(secret)
            val actual=try {OperationTrace.run(Operation.PREVIEW_RENDER) { trace->trace.previewStage(stage);throw failure };null}
                catch(t:Throwable){t}
            assertSame(failure,actual)
        }
        val events=store.snapshot(5).events
        val errors=events.filter{it.stage==Stage.ERROR}
        assertEquals(PreviewFailureStage.entries,errors.map{it.info.previewFailureStage})
        errors.forEach {
            assertEquals(ErrorCode.INVALID_ARGUMENT,it.error)
            assertEquals(PreviewExceptionType.ILLEGAL_ARGUMENT_EXCEPTION,it.info.previewExceptionType)
            assertEquals(PreviewFailureReason.PRECONDITION_REJECTED,it.info.previewFailureReason)
            assertEquals(it,DiagnosticEvent.parse(it.line()))
            assertFalse(it.line().contains(secret))
        }
        assertEquals(PreviewFailureStage.entries.size,events.count{it.stage==Stage.END && it.outcome==Outcome.FAILED})
    } }
    @Test fun failureClassificationDistinguishesLinkageAdmissionMemoryAndUnexpectedErrors() {
        val cases=listOf(
            UnsatisfiedLinkError(secret) to Pair(PreviewExceptionType.UNSATISFIED_LINK_ERROR,ErrorCode.ENGINE_UNAVAILABLE),
            NoClassDefFoundError(secret) to Pair(PreviewExceptionType.NO_CLASS_DEF_FOUND_ERROR,ErrorCode.ENGINE_UNAVAILABLE),
            NativeWorkUnavailable() to Pair(PreviewExceptionType.NATIVE_WORK_UNAVAILABLE,ErrorCode.MEMORY_LIMIT),
            OutOfMemoryError(secret) to Pair(PreviewExceptionType.OUT_OF_MEMORY_ERROR,ErrorCode.MEMORY_LIMIT),
            SecurityException(secret) to Pair(PreviewExceptionType.SECURITY_EXCEPTION,ErrorCode.PERMISSION_LOST),
            java.io.IOException(secret) to Pair(PreviewExceptionType.IO_EXCEPTION,ErrorCode.RENDER_FAILED),
            org.json.JSONException(secret) to Pair(PreviewExceptionType.JSON_EXCEPTION,ErrorCode.RENDER_FAILED),
            IllegalStateException(secret) to Pair(PreviewExceptionType.ILLEGAL_STATE_EXCEPTION,ErrorCode.RENDER_FAILED),
            object:Throwable(secret){} to Pair(PreviewExceptionType.OTHER_THROWABLE,ErrorCode.RENDER_FAILED),
        )
        for((failure,expected) in cases) {
            val info=OperationTrace.failureInfo(failure,1,PreviewFailureStage.CREATE_HANDLE)
            assertEquals(expected.first,info.previewExceptionType)
            assertEquals(expected.second,OperationTrace.errorCode(failure,Operation.PREVIEW_RENDER))
            assertFalse(info.fields().toString().contains(secret))
            assertEquals(info,SafeInfo.parse(info.fields()))
        }
        assertEquals(ErrorCode.UNKNOWN,OperationTrace.errorCode(IllegalArgumentException(secret),Operation.IMPORT))
    }
    @Test fun assetFailuresKeepPreciseSafeReasonAndCleanupDoesNotReplacePrimary() {
        for(reason in listOf(PreviewFailureReason.ASSET_PATH_REJECTED,PreviewFailureReason.ASSET_MANIFEST_MISSING)) {
            val error=PreviewAssetIdentityException(reason).apply { addSuppressed(UnsatisfiedLinkError(secret)) }
            val info=OperationTrace.failureInfo(error,2,PreviewFailureStage.CACHE_IDENTITY)
            assertEquals(reason,info.previewFailureReason)
            assertEquals(PreviewExceptionType.ASSET_IDENTITY_EXCEPTION,info.previewExceptionType)
            assertEquals(PreviewExceptionType.UNSATISFIED_LINK_ERROR,info.previewCleanupExceptionType)
            assertFalse(info.fields().toString().contains(secret))
        }
    }
    @Test fun cancellationStillPersistsCancellationRatherThanFailure()=store {store->runBlocking {
        val cancelled=CancellationException(secret)
        try {OperationTrace.run(Operation.PREVIEW_RENDER){it.previewStage(PreviewFailureStage.CACHE_IDENTITY);throw cancelled};fail("Expected cancellation")}
        catch(actual:CancellationException){assertSame(cancelled,actual)}
        val events=store.snapshot(5).events
        assertTrue(events.none{it.stage==Stage.ERROR})
        assertEquals(Outcome.CANCELLED,events.last().outcome)
        assertTrue(events.any{it.stage==Stage.CANCEL_REQUESTED && it.error==ErrorCode.CANCELLED})
    } }
    @Test fun untrustedDiagnosticValuesCannotBecomeExceptionText() {
        val safe=SafeInfo.parse(mapOf("previewFailureStage" to secret,"previewExceptionType" to secret,
            "previewFailureReason" to secret,"previewCleanupExceptionType" to secret,"stackTrace" to secret))
        assertTrue(safe.fields().isEmpty())
    }
    @Test fun localizedTextAndJsonShareOneEngineVersion() {
        val app=RuntimeEnvironment.getApplication()
        val meta=DiagnosticMetadata(appVersionCode=47)
        val snapshot=DiagnosticSnapshot(5,1000,10,emptyList(),emptyList(),0,false,true)
        val json=JSONObject(ChineseDiagnostics.json(snapshot,meta))
        assertEquals(DiagnosticEngineVersion.BUILD,json.getInt("engineBuildRevision"))
        assertEquals(DiagnosticEngineVersion.INTERFACE,json.getInt("engineInterfaceVersion"))
        try {for(language in L10n.supported) {
            L10n.choose(app,language)
            val text=meta.text()
            val expectedNumbers=listOf(meta.appVersionCode,DiagnosticEngineVersion.BUILD,DiagnosticEngineVersion.INTERFACE,
                meta.androidApi,meta.processorCount,meta.heapMaxBytes,meta.memoryClassMb).map{it.toString()}
            assertEquals(expectedNumbers,Regex("\\d+").findAll(text).map{it.value}.toList())
            assertFalse(text.contains("%7"));assertFalse(text.contains("%8"))
        }}finally{L10n.choose(app,"zh")}
    }
}
