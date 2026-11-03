package dev.filmroom.diagnostics

import android.util.JsonReader
import android.util.JsonToken
import dev.filmroom.OperationTrace
import dev.filmroom.io.PhotoException
import java.io.StringReader
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class DiagnosticJsonRegressionTest {
    private fun strictJson(text:String) {
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient=false
            fun consume() { when(reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {reader.beginObject();while(reader.hasNext()){reader.nextName();consume()};reader.endObject()}
                JsonToken.BEGIN_ARRAY -> {reader.beginArray();while(reader.hasNext())consume();reader.endArray()}
                JsonToken.STRING -> reader.nextString()
                JsonToken.NUMBER -> reader.nextDouble()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> reader.nextNull()
                else -> error("Unexpected JSON token")
            } }
            consume();assertEquals(JsonToken.END_DOCUMENT,reader.peek())
        }
    }
    private fun report(info:SafeInfo):String {
        val event=DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.IMPORT,Stage.ERROR,info=info)
        return ChineseDiagnostics.json(DiagnosticSnapshot(5,1000,10,listOf(event),listOf(1000L),0,false,true),DiagnosticMetadata())
    }
    @Test fun everyEnumFieldAndEveryEnumValueIsQuotedAndRoundTrips() {
        // Reflect the typed schema so future added enum fields also get coverage.
        SafeInfo::class.java.declaredFields.filter { it.type.isEnum }.forEach { field ->
            checkNotNull(field.type.enumConstants).forEach { value ->
                val enum=value as Enum<*>
                val id=runCatching { value.javaClass.getMethod("getId").invoke(value) as String }.getOrDefault(enum.name)
                val fields=mutableMapOf(field.name to id)
                if(field.name=="metadataPhase") {
                    assertFalse(SafeInfo.parse(fields).fields().containsKey("metadataPhase"))
                    fields["metadataIssues"]=MetadataIssues.encode(listOf(MetadataIssue(MetadataField.METADATA,MetadataIssueReason.READ_FAILED)))
                }
                val safe=SafeInfo.parse(fields)
                assertEquals(id,safe.fields()[field.name])
                val text=report(safe);strictJson(text)
                val parsed=JSONObject(text).getJSONArray("events").getJSONObject(0).getJSONObject("info")
                assertTrue(parsed.get(field.name) is String);assertEquals(id,parsed.getString(field.name))
            }
        }
    }
    @Test fun feedbackDefaultsRemainTypedAndFinite() {
        val info=SafeInfo(diffusionFilter=DiffusionProfile.OFF,outputRoute=OutputRoute.PRINT,outputColorSpace=OutputColorProfile.SRGB,width=3072,height=4096,useGpu=true,brightness=0.0,contrast=Double.NaN)
        val json=report(info);strictJson(json)
        val parsed=JSONObject(json).getJSONArray("events").getJSONObject(0).getJSONObject("info")
        assertEquals("off",parsed.get("diffusionFilter"));assertEquals("print",parsed.get("outputRoute"));assertEquals("sRGB",parsed.get("outputColorSpace"))
        assertTrue(parsed.get("width") is Number);assertTrue(parsed.get("useGpu") is Boolean);assertFalse(parsed.has("contrast"))
    }
    @Test fun nativeHeartbeatSerialIsBoundedNumericAndSurvivesRestart() {
        val info=SafeInfo(nativeUpdateSerial=Long.MAX_VALUE)
        val event=DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.EXPORT_RENDER,Stage.NATIVE_PROGRESS,info=info)
        assertEquals(Long.MAX_VALUE,DiagnosticEvent.parse(event.line())!!.info.nativeUpdateSerial)
        val json=report(info);strictJson(json)
        assertEquals(Long.MAX_VALUE,JSONObject(json).getJSONArray("events").getJSONObject(0).getJSONObject("info").getLong("nativeUpdateSerial"))
        assertFalse(SafeInfo(nativeUpdateSerial=-1).fields().containsKey("nativeUpdateSerial"))
        assertFalse(SafeInfo.parse(mapOf("nativeUpdateSerial" to "private/path")).fields().containsKey("nativeUpdateSerial"))
    }
    @Test fun escapedStringRoundTripsWithStrictParser() {
        val input="引号\"反斜线\\\n\r\t\b\u0000\u001f 😀"
        val json="{\"value\":"+jsonString(input)+"}"
        strictJson(json);assertEquals(input,JSONObject(json).getString("value"))
    }
    @Test fun oldDiskEventsKeepCompatibilityAndNewCodecFieldsSurviveRestart() {
        val old=DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.IMPORT,Stage.ERROR,error=ErrorCode.DECODE_FAILED,info=SafeInfo(outputRoute=OutputRoute.PRINT))
        assertEquals(old,DiagnosticEvent.parse(old.line()))
        val new=old.copy(info=old.info.copy(codecCode=CodecErrorCode.UNSUPPORTED_DNG_OPCODE,codecStage=CodecStage.PROBE))
        assertEquals(new,DiagnosticEvent.parse(new.line()))
        strictJson(report(DiagnosticEvent.parse(new.line())!!.info))
    }
    @Test fun untrustedCodecStringsAndExceptionContentsNeverReachDiagnostics() {
        val secret="content://private/photo.dng GPS=22.3 secret-token"
        val safe=SafeInfo.parse(mapOf("codecCode" to secret,"codecStage" to secret,"filename" to secret,"gpuReason" to secret))
        assertEquals(CodecErrorCode.UNKNOWN,safe.codecCode);assertEquals(CodecStage.UNKNOWN,safe.codecStage)
        val exception=PhotoException(PhotoException.Reason.UNREADABLE,secret,IllegalArgumentException(secret),codecCode=safe.codecCode,codecStage=safe.codecStage)
        val info=OperationTrace.failureInfo(exception,136)
        val json=report(info);strictJson(json)
        assertFalse(json.contains(secret));assertFalse(json.contains("content://"));assertFalse(info.fields().toString().contains(secret))
        assertEquals("UNKNOWN",info.fields()["codecCode"])
    }
}
