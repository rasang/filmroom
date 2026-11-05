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
    @Test fun uncertainGpuCompletionRetainsItsOwnFailureAndRestartInstruction() {
        val error=failure("GPU_COMPLETION_UNCERTAIN","scientific_pipeline")
        assertEquals(EngineFailure.GPU_COMPLETION_UNCERTAIN,error.failure)
        assertEquals(ErrorCode.GPU_COMPLETION_UNCERTAIN,OperationTrace.errorCode(error))
        assertEquals(NativeEngineCode.GPU_COMPLETION_UNCERTAIN,error.nativeEngineCode)
        assertEquals(NativeEngineStage.SCIENTIFIC_PIPELINE,error.nativeEngineStage)
        assertEquals("gpu_completion_uncertain",error.uiMessage.key)
        assertTrue(error.message!!.contains("完全关闭并重新打开"))
        assertFalse(error.message!!.contains("/private"))
        val safe=OperationTrace.failureInfo(error,1)
        assertEquals(safe,SafeInfo.parse(safe.fields()))
        val event=DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.EXPORT_RENDER,Stage.ERROR,
            error=OperationTrace.errorCode(error),info=safe)
        assertEquals(event,DiagnosticEvent.parse(event.line()))
        assertTrue(event.line().contains("GPU_COMPLETION_UNCERTAIN"))
        // Genuine memory failures keep their separate remedy; uncertain GPU work is not OOM.
        assertEquals(ErrorCode.MEMORY_LIMIT,OperationTrace.errorCode(failure("MEMORY_BUDGET_TOO_SMALL","render_pipeline")))
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
