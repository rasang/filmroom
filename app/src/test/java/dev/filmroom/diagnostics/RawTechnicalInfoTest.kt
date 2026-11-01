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
@Config(sdk=[35],manifest=Config.NONE)
class RawTechnicalInfoTest {
    private val keys=listOf("rawFileBytes","rawWidth","rawHeight","rawCompression","rawBitsMin","rawBitsMax","rawSamplesPerPixel","rawPhotometric","rawCfaRows","rawCfaCols","rawTileWidth","rawTileHeight","rawRowsPerStrip","rawMaxBlockBytes","rawBlockCount","rawDngVersion","rawProbeStatus","rawOpcodeBytes","rawOpcodeCount","rawRequiredOpcodeId","rawRequiredOpcodeVersion","rawRequiredOpcodeFlags","rawCalibrationMask","rawIfdEntries")
    private fun max(key:String)=when(key){"rawFileBytes"->512L*1024*1024;"rawProbeStatus"->2L;"rawOpcodeBytes"->3L*512*1024*1024;"rawOpcodeCount"->192L;"rawCalibrationMask"->3L;"rawIfdEntries"->256L;else->0xffffffffL}
    @Test fun allNumericFieldsRespectInclusiveBoundsAndRemainJsonNumbers() {
        val json=JSONObject();keys.forEach { json.put(it,max(it)) }
        val technical=RawTechnicalInfo.fromNative(json)!!
        assertEquals(keys.toSet(),technical.fields().keys)
        keys.forEach { assertEquals(max(it),technical.fields()[it]) }
        val info=SafeInfo(rawTechnical=technical)
        assertEquals(info,SafeInfo.parse(info.fields()))
        assertTrue(info.typedFields().values.all { it is Long })
        keys.forEach { key ->
            assertNull(RawTechnicalInfo.fromNative(JSONObject().put(key,max(key)+1)))
            assertEquals(0L,RawTechnicalInfo.fromNative(JSONObject().put(key,0))!!.fields()[key])
        }
    }
    @Test fun nativeFloatsStringsBooleansNegativesAndNestedObjectsAreRejected() {
        val bad=listOf<Any>("123",true,false,1.0,0.0,-1,Double.POSITIVE_INFINITY.toString(),JSONObject().put("path","content://secret/photo"))
        keys.forEach { key -> bad.forEach { value -> assertNull("$key rejected ${value.javaClass.simpleName}",RawTechnicalInfo.fromNative(JSONObject().put(key,value))) } }
        assertNull(RawTechnicalInfo.fromNative(null))
        assertNull(RawTechnicalInfo.fromNative(JSONObject("{\"rawWidth\":1e2}")))
        assertNull(RawTechnicalInfo.fromNative(JSONObject("{\"rawWidth\":1.0}")))
    }
    @Test fun diskInputCannotBypassNumericSchemaAndUnknownFieldsAreNeverPreserved() {
        val secret="content://private/photo.dng GPS=22.3 token=private"
        val bad=listOf(secret,"1.0","1e2","true","-1","+1"," 1","4294967296")
        keys.forEach { key -> bad.forEach { value -> assertNull(RawTechnicalInfo.fromFields(mapOf(key to value))) } }
        val info=SafeInfo.parse(mapOf("rawWidth" to "4096","filename" to secret,"rawModel" to secret,"rawExif" to secret))
        assertEquals(mapOf("rawWidth" to "4096"),info.fields())
        assertFalse(info.fields().toString().contains(secret))
    }
    @Test fun failureTraceAndDiskRoundTripContainOnlyWhitelistedFacts() {
        val secret="content://private/photo.dng"
        val technical=RawTechnicalInfo.fromNative(JSONObject().put("rawWidth",4096).put("rawHeight",3072).put("rawProbeStatus",0).put("rawFileBytes",30000000).put("rawDngVersion",0x01040000).put("name",secret).put("gps",secret).put("rawBitsMin",secret))!!
        val failure=PhotoException(PhotoException.Reason.UNREADABLE,secret,IllegalArgumentException(secret),codecCode=CodecErrorCode.UNSUPPORTED_DNG_OPCODE,codecStage=CodecStage.PROBE,rawTechnical=technical)
        val safe=OperationTrace.failureInfo(failure,136)
        val event=DiagnosticEvent(1000,10,"a".repeat(32),1,1,Operation.IMPORT,Stage.ERROR,info=safe)
        assertEquals(event,DiagnosticEvent.parse(event.line()))
        val snapshot=DiagnosticSnapshot(5,1000,10,listOf(event),listOf(1000L),0,false,true)
        val json=ChineseDiagnostics.json(snapshot,DiagnosticMetadata())
        val info=JSONObject(json).getJSONArray("events").getJSONObject(0).getJSONObject("info")
        assertEquals(4096,info.getInt("rawWidth"));assertEquals(0x01040000,info.getInt("rawDngVersion"))
        assertTrue(info.get("rawFileBytes") is Number)
        assertFalse(json.contains(secret));assertFalse(snapshot.text("").contains(secret));assertFalse(event.line().contains(secret))
        assertFalse(info.has("rawBitsMin"));assertFalse(info.has("name"));assertFalse(info.has("gps"))
    }
}
