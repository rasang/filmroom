package dev.filmroom.presets

import android.app.Application
import dev.filmroom.model.EditorState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,manifest=Config.NONE)
class PresetQrCompactHostTest {
    internal fun samples(): List<Pair<String,EditorState>> {
        val rows = JSONArray(checkNotNull(javaClass.getResourceAsStream("/presets/qr-v2-sample-states.json")).bufferedReader().use { it.readText() })
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let { it.getString("name") to EditorState.fromJson(it.getJSONObject("parameters").toString()) } }
    }
    private fun raw(bytes: ByteArray, mode: Int = 0, digest: ByteArray = bytes): String = PresetQrCodec.COMPACT_PREFIX + PresetBase45.encode(MessageDigest.getInstance("SHA-256").digest(digest) + byteArrayOf(mode.toByte()) + bytes)
    private fun fails(block: () -> Unit) { try { block(); fail("Must reject") } catch (_: IllegalArgumentException) {} catch (_: PresetQrCodec.Failure) {} catch (_: IllegalStateException) {} }
    @Test fun base45RfcVectorsAndAllBytesAreCanonical() {
        for ((plain, encoded) in listOf("AB" to "BB8", "Hello!!" to "%69 VD92EX0", "base-45" to "UJCLQE7W581")) {
            assertEquals(encoded,PresetBase45.encode(plain.toByteArray()));assertEquals(plain,String(PresetBase45.decode(encoded)))
        }
        for (size in 0..512) { val bytes=ByteArray(size) { (it*37+size).toByte() };assertArrayEquals(bytes,PresetBase45.decode(PresetBase45.encode(bytes))) }
        listOf("A","AAAa",":::" ,"::", "ABC\n").forEach { fails { PresetBase45.decode(it) } }
    }
    @Test fun literalVersionTwoCodesRemainReadableAcrossAppUpgrades() {
        val samples=samples()
        for ((file,index) in listOf("default" to 0,"all-fields" to 6)) {
            // Do not trim: space belongs to the Base45 alphabet.
            val text=checkNotNull(javaClass.getResourceAsStream("/presets/qr-v2-code55-$file.txt")).bufferedReader().use { it.readText() }
            val (name,state)=samples[index]
            assertEquals(PresetQrCodec.Draft(name,state),PresetQrCodec.decode(text))
        }
    }
    @Test fun realPresetsAndExactFloatDoubleValuesRoundTrip() {
        for ((name,state) in samples()) {
            val encoded=PresetQrCodec.encode(name,state)
            assertTrue(encoded.startsWith(PresetQrCodec.COMPACT_PREFIX)); assertEquals(PresetQrCodec.Draft(name,state),PresetQrCodec.decode(encoded))
            assertTrue(encoded.length < if (name=="全89项") 1000 else 170)
        }
        val random=java.util.Random(20261107)
        repeat(100) {
            val state=EditorState(brightness=random.nextFloat()*4f-2f,filmExposure=random.nextFloat()*8f-4f,
                contrast=.5f+random.nextFloat(),paperGammaRed=.5f+random.nextFloat()*1.5f,
                composition=dev.filmroom.model.CompositionTransform(centerX=random.nextDouble(),centerY=random.nextDouble(),zoom=1.0+random.nextDouble()*5.0,targetAspect=.5+random.nextDouble()*2.0))
            assertEquals(state,PresetQrCodec.decode(PresetQrCodec.encode("精度$it",state)).parameters)
        }
    }
    @Test fun frozenSchemaIsImmutableAndNewFieldsTravelInExtensions() {
        val definition=PresetQrSchemaV2.BASELINE+"\n"+PresetQrSchemaV2.fields.joinToString("\n") { "${it.first}:${it.second}" }+"\n"+PresetQrSchemaV2.strings.joinToString("\n")
        val hash=MessageDigest.getInstance("SHA-256").digest(definition.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("e93dffdb24e2248dd97e5d13f92a305f6b24dd218d8eb39b358a01d7cacc98d3",hash)
        assertEquals(111,PresetQrSchemaV2.fields.size)
        val current=JSONObject(SavedPresetStore.snapshot(EditorState())).put("brightnessEv",.75)
        current.put("futureControl",.375);current.getJSONObject("imprint").put("futureMode","safe")
        val restored=PresetQrBinary.decode(PresetQrBinary.encode("Future",current)).second
        assertEquals(.75,restored.getDouble("brightnessEv"),0.0)
        assertEquals(.375,restored.getDouble("futureControl"),0.0)
        assertEquals("safe",restored.getJSONObject("imprint").getString("futureMode"))
        fails { PresetQrParameters.read(restored) } // Old app refuses new settings instead of silently dropping them.
        val frozen=PresetQrBinary.decode(byteArrayOf(1,1,120,0,0)).second
        assertEquals(0.0,frozen.getDouble("brightnessEv"),0.0)
        assertEquals("kodak_portra_400",frozen.getString("filmStock"))
        assertFalse(frozen.has("futureControl")) // Future reader alone supplies this newly introduced field's default.
    }
    @Test fun futureAppDefaultsDoNotReinterpretExistingNullableOrNumericSlots() {
        val old=PresetQrBinary.decode(byteArrayOf(1,1,120,0,0)).second
        val future=JSONObject(SavedPresetStore.snapshot(EditorState())).put("brightnessEv",.9).put("mediumFormat","MF67")
            .put("preferredOutputRoute","direct").put("newControl",.125)
        future.getJSONObject("imprint").put("manualDateTime","2026-11-07 10:00:00")
        future.getJSONObject("composition").put("version",2).put("targetAspect",1.7)
        future.getJSONObject("crop").getJSONObject("transform").put("version",2).put("targetAspect",1.7)
        val merged=PresetQrParameters.mergeForImport(old,future,frozenNulls=true)
        assertEquals(0.0,merged.getDouble("brightnessEv"),0.0)
        assertFalse(merged.has("mediumFormat")); assertTrue(merged.isNull("preferredOutputRoute"))
        assertTrue(merged.getJSONObject("imprint").isNull("manualDateTime"))
        assertFalse(merged.getJSONObject("composition").has("targetAspect"))
        assertFalse(merged.getJSONObject("crop").getJSONObject("transform").has("targetAspect"))
        assertEquals(.125,merged.getDouble("newControl"),0.0)
        assertEquals(EditorState(),EditorState.fromJson(merged.toString()))
    }
    @Test fun futureRenderingStageMigrationDoesNotAccidentallyUseTheNewEditorDefault() {
        val old=PresetQrBinary.decode(byteArrayOf(1,1,120,0,0)).second
        val future=JSONObject(SavedPresetStore.snapshot(EditorState())).put("filmBorderStage",1)
        assertEquals(0,PresetQrParameters.mergeForImport(old,future,true).getInt("filmBorderStage"))
        assertEquals(1,PresetQrParameters.mergeForImport(JSONObject(old.toString()).put("filmBorderStage",1),future,true).getInt("filmBorderStage"))
        for (value in listOf(1.5,1.0,"1",true,2,-1,JSONObject.NULL))
            fails { PresetQrParameters.mergeForImport(JSONObject(old.toString()).put("filmBorderStage",value),future,true) }
        // Until the current model contains this field, it is still unknown, not silently ignored.
        if (!JSONObject(SavedPresetStore.snapshot(EditorState())).has("filmBorderStage"))
            fails { PresetQrParameters.read(JSONObject(old.toString()).put("filmBorderStage",1),true) }
    }
    @Test fun binaryMalformedLengthsDuplicateIdsAndFrozenOverridesAreRejected() {
        // schema=1, name=x, count=... ; field 0 is a Boolean.
        val prefix=byteArrayOf(1,1,120)
        listOf(byteArrayOf(2,1,120,0,0),prefix+byteArrayOf(1,127,1,0),prefix+byteArrayOf(2,0,1,0,0,0),
            prefix+byteArrayOf(1,0,2,0),prefix+byteArrayOf(0,0,42),byteArrayOf(1,0x81.toByte(),0,120,0,0),
            byteArrayOf(1,1,0xff.toByte(),0,0)).forEach { fails { PresetQrCodec.decode(raw(it)) } }
        for (extra in listOf("{\"brightnessEv\":1}","{\"imprint\":null}","{\"imprint\":{\"size\":1.2}}","{\"composition\":{\"targetAspect\":1.2}}","{\"new\":1,\"new\":2}")) {
            val e=extra.toByteArray();fails { PresetQrCodec.decode(raw(prefix+byteArrayOf(0,e.size.toByte())+e)) }
        }
    }
    @Test fun integrityRawCompressedLimitsAndTrailingBytesFailClosed() {
        val source=PresetQrBinary.encode("x",JSONObject(SavedPresetStore.snapshot(EditorState())))
        fails { PresetQrCodec.decode(raw(source,9)) }
        fails { PresetQrCodec.decode(raw(source+byteArrayOf(9),digest=source)) }
        fun zip(b:ByteArray):ByteArray { val d=Deflater(9,true);val o=ByteArrayOutputStream();try { d.setInput(b);d.finish();val c=ByteArray(1024);while(!d.finished())o.write(c,0,d.deflate(c)) }finally{d.end()};return o.toByteArray() }
        assertEquals(EditorState(),PresetQrCodec.decode(raw(zip(source),1,source)).parameters)
        fails { PresetQrCodec.decode(raw(zip(source)+byteArrayOf(1),1,source)) }
        val bomb=ByteArray(PresetQrCodec.MAX_JSON_BYTES+1)
        fails { PresetQrCodec.decode(raw(zip(bomb),1,bomb)) }
        val good=PresetQrCodec.encode("spaced",EditorState());fails { PresetQrCodec.decode(good+" ") }
        val data=PresetBase45.decode(good.removePrefix(PresetQrCodec.COMPACT_PREFIX));data[0]=(data[0].toInt() xor 1).toByte()
        fails { PresetQrCodec.decode(PresetQrCodec.COMPACT_PREFIX+PresetBase45.encode(data)) }
    }
    @Test fun actualPreImagingStageAndStraightenAnglesUseExtensionsLosslessly() {
        for (stage in 0..1) for (angle in listOf(-15.0, -0.1, 0.0, 0.1, 15.0)) {
            val transform = dev.filmroom.model.CompositionTransform(
                centerX=.37, centerY=.63, zoom=2.25, straightenDegrees=angle)
            val state = EditorState(filmBorder=true, filmBorderStage=stage, composition=transform,
                crop=dev.filmroom.model.CropSettings(dev.filmroom.model.CropMode.FREE,
                    // JSON zero has no IEEE sign; use canonical zero in the exact-state fixture.
                    transform.copy(targetAspect=1.25, straightenDegrees=if (angle == 0.0) 0.0 else -angle)))
            val snapshot = JSONObject(SavedPresetStore.snapshot(state))
            val binary = PresetQrBinary.decode(PresetQrBinary.encode("Stage and angle", snapshot)).second
            assertEquals(stage, binary.getInt("filmBorderStage"))
            assertEquals(state, PresetQrParameters.read(binary, frozenNulls=true))
            assertEquals(state, PresetQrCodec.decode(PresetQrCodec.encode("Stage and angle", state)).parameters)
            assertNull(state.composition.targetAspect)
            assertFalse(PresetQrSchemaV2.fields.any { it.first == "filmBorderStage" || it.first.endsWith("straightenDegrees") })
        }
    }
    @Test fun legacyLiteralCodesKeepPostImagingAndZeroAngle() {
        for (file in listOf("qr-v1-code54.txt", "qr-v2-code55-default.txt", "qr-v2-code55-all-fields.txt")) {
            val input = checkNotNull(javaClass.getResourceAsStream("/presets/$file")).bufferedReader().use { it.readText() }
            val state = PresetQrCodec.decode(if (file.contains("v1")) input.trim() else input).parameters
            assertEquals(0, state.filmBorderStage)
            assertEquals(0.0, state.composition.straightenDegrees, 0.0)
            assertEquals(0.0, state.crop.transform.straightenDegrees, 0.0)
        }
    }
    @Test fun invalidStageAndAngleExtensionsFailClosedWithoutNullableSlotCoercion() {
        fun snapshot() = JSONObject(SavedPresetStore.snapshot(EditorState(filmBorder=true, filmBorderStage=1,
            composition=dev.filmroom.model.CompositionTransform(straightenDegrees=4.0),
            crop=dev.filmroom.model.CropSettings(transform=dev.filmroom.model.CompositionTransform(straightenDegrees=-4.0)))))
        fun rejected(value: JSONObject) {
            fails { PresetQrParameters.read(value, frozenNulls=false) }
            fails { PresetQrParameters.read(value, frozenNulls=true) }
            fails { PresetQrCodec.decode(raw(PresetQrBinary.encode("Invalid extension", value))) }
        }
        for (value in listOf(JSONObject.NULL, "1", true, 1.5, -1, 2)) rejected(snapshot().put("filmBorderStage", value))
        for (inCrop in listOf(false, true)) {
            fun target(root: JSONObject) = if (inCrop) root.getJSONObject("crop").getJSONObject("transform") else root.getJSONObject("composition")
            for (value in listOf(JSONObject.NULL, "4", true, -15.1, 15.1)) {
                val root = snapshot(); target(root).put("straightenDegrees", value); rejected(root)
            }
            for (version in 1..2) {
                val root = snapshot(); target(root).put("version", version); rejected(root)
            }
            val missing = snapshot(); target(missing).remove("straightenDegrees"); rejected(missing)
        }
    }

}
