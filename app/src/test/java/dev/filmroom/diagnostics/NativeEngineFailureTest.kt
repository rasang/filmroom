package dev.filmroom.diagnostics

import androidx.test.core.app.ApplicationProvider
import dev.filmroom.OperationTrace
import dev.filmroom.engine.EngineException
import dev.filmroom.engine.EngineFailure
import dev.filmroom.engine.FilmEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class NativeEngineFailureTest {
    private val engine get()=FilmEngine(ApplicationProvider.getApplicationContext())
    private fun failure(code:String,stage:String):EngineException {
        val result=JSONObject().put("ok",false).put("code",code).put("stage",stage)
            .put("message","/private/user-photo GPS secret").put("inputPath","content://private/photo")
        return try {engine.checkNativeResult(result);error("Expected failure")}catch(e:EngineException){e}
    }
    @Test fun privatePathFailureKeepsSafeCauseAndDoesNotSuggestResizing() {
        for(code in listOf("INVALID_PATH","EXPORT_PATH_POLICY")) {
            val error=failure(code,"validate_paths_and_export")
            assertEquals(EngineFailure.PATH,error.failure)
            assertFalse(error.message!!.contains("选择标准尺寸"))
            assertFalse(error.message!!.contains("/private"))
            val info=OperationTrace.failureInfo(error,1)
            assertEquals(code,info.fields()["nativeEngineCode"])
            assertEquals("validate_paths_and_export",info.fields()["nativeEngineStage"])
            val event=DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.EXPORT_RENDER,Stage.ERROR,info=info)
            assertEquals(event,DiagnosticEvent.parse(event.line()))
        }
    }
    @Test fun scientificPreviewFailuresKeepSafeCauseAndAccurateCategory() {
        val cases=listOf(
            "PREVIEW_PATH_POLICY" to EngineFailure.PATH,
            "PREVIEW_DISK_UNAVAILABLE" to EngineFailure.INTERNAL,
            "PREVIEW_REGISTRY_UNAVAILABLE" to EngineFailure.INTERNAL,
            "PREVIEW_DRAIN_REQUIRED" to EngineFailure.INCOMPLETE,
            "PREVIEW_STILL_RUNNING" to EngineFailure.INCOMPLETE,
            "PREVIEW_CLEANUP_IDENTITY" to EngineFailure.INCOMPLETE,
            "PREVIEW_CLEANUP_FAILED" to EngineFailure.INCOMPLETE,
            "GPU_CLEANUP_IDENTITY" to EngineFailure.INCOMPLETE,
            "GPU_RETAINED_LIMIT" to EngineFailure.INCOMPLETE,
            "INSUFFICIENT_DISK_SPACE" to EngineFailure.STORAGE,
            "UNSUPPORTED_PRECISION" to EngineFailure.UNAVAILABLE,
        )
        for((code,category) in cases) {
            val error=failure(code,"scientific_pipeline")
            assertEquals(code,category,error.failure)
            val info=OperationTrace.failureInfo(error,1)
            assertEquals(code,info.fields()["nativeEngineCode"])
            assertEquals("scientific_pipeline",info.fields()["nativeEngineStage"])
            val event=DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.PREVIEW_RENDER,Stage.ERROR,info=info)
            val restored=DiagnosticEvent.parse(event.line())
            assertEquals(code,event,restored)
            assertFalse(event.line().contains("/private"))
            assertFalse(event.line().contains("GPS secret"))
            assertFalse(error.message!!.contains("/private"))
        }
    }
    @Test fun scientificPreviewLookalikeTokensRemainRedacted() {
        for(code in listOf("PREVIEW_PATH_POLICY /private/photo", "PREVIEW_UNRECOGNIZED")) {
            val error=failure(code,"scientific_pipeline /private/photo")
            assertEquals(EngineFailure.INTERNAL,error.failure)
            val safe=OperationTrace.failureInfo(error,1)
            assertEquals(NativeEngineCode.UNKNOWN,safe.nativeEngineCode)
            assertEquals(NativeEngineStage.UNKNOWN,safe.nativeEngineStage)
            assertFalse(safe.fields().toString().contains(code))
            assertFalse(safe.fields().toString().contains("/private"))
        }
    }
    @Test fun nativeDetailsAreStrictlyAllowlistedBeforePersistence() {
        val secret="/private/photo.raw GPS=22.3 token"
        val error=failure(secret,secret)
        assertEquals(EngineFailure.INTERNAL,error.failure)
        val safe=OperationTrace.failureInfo(error,1)
        assertEquals(NativeEngineCode.UNKNOWN,safe.nativeEngineCode)
        assertEquals(NativeEngineStage.UNKNOWN,safe.nativeEngineStage)
        assertFalse(safe.fields().toString().contains(secret))
        val restored=SafeInfo.parse(mapOf("nativeEngineCode" to secret,"nativeEngineStage" to secret,"message" to secret))
        assertEquals("UNKNOWN",restored.fields()["nativeEngineCode"])
        assertEquals("unknown",restored.fields()["nativeEngineStage"])
        assertFalse(restored.fields().toString().contains(secret))
    }
    @Test fun successfulNativeResponseAndLegacyDiagnosticsRemainCompatible() {
        engine.checkNativeResult(JSONObject().put("ok",true))
        assertNull(SafeInfo.parse(emptyMap()).nativeEngineCode)
        assertNull(SafeInfo.parse(emptyMap()).nativeEngineStage)
        val error=failure("CHECKPOINT_INVALID","setup")
        assertEquals(EngineFailure.INCOMPLETE,error.failure)
        assertEquals(NativeEngineCode.CHECKPOINT_INVALID,error.nativeEngineCode)
    }
}
