package dev.filmroom.diagnostics

import dev.filmroom.OperationTrace
import dev.filmroom.io.PhotoException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], manifest=Config.NONE)
class CodecDiagnosticDetailTest {
    private fun valid() = JSONObject().put("diagnosticSchema",1).put("reason","D65_MATRIX_MISSING").put("source","DNG")
        .put("line",375).put("column",20).put("tag",50721)
    @Test fun detailSurvivesFailureDiskTextAndJsonWithoutExceptionText() {
        val secret="content://private/photo.dng GPS=22.3"
        val detail=CodecDiagnosticDetail.fromNative(valid().put("message",secret))!!
        val failure=PhotoException(PhotoException.Reason.UNREADABLE,secret,
            codecCode=CodecErrorCode.INVALID_COLOR_METADATA,codecStage=CodecStage.PROBE,codecDetail=detail)
        val safe=OperationTrace.failureInfo(failure,162)
        assertEquals("D65_MATRIX_MISSING",safe.fields()["codecReason"])
        assertEquals("50721",safe.fields()["codecTag"])
        assertTrue(safe.typedFields()["codecLine"] is Long)
        assertEquals(safe,SafeInfo.parse(safe.fields()))
        val event=DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.IMPORT,Stage.ERROR,info=safe)
        assertEquals(event,DiagnosticEvent.parse(event.line()))
        val snapshot=DiagnosticSnapshot(5,1000,10,listOf(event),listOf(1000L),0,false,true)
        val json=ChineseDiagnostics.json(snapshot,DiagnosticMetadata())
        assertTrue(json.contains("D65_MATRIX_MISSING"))
        assertTrue(snapshot.text("").contains("codecSource=DNG"))
        assertFalse(json.contains(secret)); assertFalse(snapshot.text("").contains(secret))
        assertFalse(event.line().contains(secret))
    }
    @Test fun multipleValidationFailuresReachExportedLogs() {
        val cases = listOf(
            Triple("MISSING_TAG", "DNG", 50728),
            Triple("WHITE_BALANCE_RANGE", "DNG", 50728),
            Triple("MATRIX_SINGULAR", "COMPAT_CALIBRATION", 50964),
            Triple("TAG_VALUE", "TIFF", 258),
            Triple("COLOR_TRANSFORM", "DNG_COLOR_SPEC", 0),
            Triple("UNSUPPORTED_VALUE", "DNG", 50720),
            Triple("RESOURCE_LIMIT", "TIFF", 330),
            Triple("TAG_SHAPE", "COMPAT", 50942),
        )
        cases.forEachIndexed { index, (reason, source, tag) ->
            val payload = valid().put("reason",reason).put("source",source).put("line",100+index)
            if (tag == 0) payload.remove("tag") else payload.put("tag",tag)
            val detail = CodecDiagnosticDetail.fromNative(payload)!!
            val failure = PhotoException(PhotoException.Reason.UNREADABLE,"PRIVATE EXCEPTION",
                codecCode=CodecErrorCode.INVALID_COLOR_METADATA,codecStage=CodecStage.PROBE,codecDetail=detail)
            val safe = OperationTrace.failureInfo(failure,162)
            val event = DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.IMPORT,Stage.ERROR,info=safe)
            val restored = DiagnosticEvent.parse(event.line())!!
            assertEquals(event,restored)
            val snapshot=DiagnosticSnapshot(5,1000,10,listOf(restored),listOf(1000L),0,false,true)
            val text=snapshot.text("")
            val json=ChineseDiagnostics.json(snapshot,DiagnosticMetadata())
            assertTrue(text.contains("codecReason=$reason")); assertTrue(json.contains(reason))
            assertTrue(text.contains("codecSource=$source")); assertTrue(json.contains(source))
            assertFalse(text.contains("PRIVATE EXCEPTION")); assertFalse(json.contains("PRIVATE EXCEPTION"))
        }
    }
    @Test fun genericOtherSourceKeepsCoordinatesAndLegacyErrorDoesNotInventDetails() {
        val other=CodecDiagnosticDetail.fromNative(valid().put("source","OTHER"))!!
        assertEquals("OTHER",other.fields()["codecSource"])
        assertEquals(375L,other.fields()["codecLine"])
        assertEquals(20L,other.fields()["codecColumn"])
        assertNull(CodecDiagnosticDetail.fromNative(JSONObject().put("code","INVALID_COLOR_METADATA").put("stage","probe")))
        assertNull(CodecDiagnosticDetail.fromNative(valid().apply { remove("diagnosticSchema") }))
    }
    @Test fun unknownOrMalformedIdentityAndCoordinatesAreDropped() {
        assertNull(CodecDiagnosticDetail.fromNative(null))
        for(value in listOf<Any>(0,2,"1",1.0,true,JSONObject.NULL)) {
            assertNull(CodecDiagnosticDetail.fromNative(valid().put("diagnosticSchema",value)))
        }
        for(key in listOf("reason","source")) {
            for(value in listOf<Any>("content://secret", "DNG\nGPS=1", 1, true, JSONObject.NULL)) {
                assertNull(CodecDiagnosticDetail.fromNative(valid().put(key,value)))
            }
        }
        for(key in listOf("line","column")) {
            for(value in listOf<Any>(0,-1,100001,1.0,"1",true,JSONObject.NULL)) {
                assertNull(CodecDiagnosticDetail.fromNative(valid().put(key,value)))
            }
        }
    }
    @Test fun tagsAreIdentifiersOnlyAndPersistedInputsAreRevalidated() {
        for(value in listOf<Any>(0,34853,65535,"50721",50721.0,true,"GPS=22.3")) {
            assertFalse(CodecDiagnosticDetail.fromNative(valid().put("tag",value))!!.fields().containsKey("codecTag"))
        }
        val fields=CodecDiagnosticDetail.fromNative(valid())!!.fields().mapValues { it.value.toString() }
        for(key in listOf("codecLine","codecColumn")) {
            for(value in listOf("1.0","1e2","+1"," 1","-1","100001","secret")) {
                assertNull(CodecDiagnosticDetail.fromFields(fields+(key to value)))
            }
        }
        for(value in listOf("0","2","1.0","true","secret")) {
            assertNull(CodecDiagnosticDetail.fromFields(fields+("codecDiagnosticSchema" to value)))
        }
        assertNull(CodecDiagnosticDetail.fromFields(fields+("codecSource" to "private/path")))
        assertNull(CodecDiagnosticDetail.fromFields(fields+("codecReason" to "private/path")))
        assertFalse(SafeInfo.parse(fields+("codecExif" to "secret")).fields().containsKey("codecExif"))
    }
}
