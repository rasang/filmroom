package dev.filmroom.presets

import android.app.Application
import dev.filmroom.model.EditorState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class PresetQrCodecHostTest {
    private fun full() = SavedPresetStoreHostTest::class.java.getDeclaredMethod("fullState").apply { isAccessible = true }.invoke(SavedPresetStoreHostTest()) as EditorState
    private fun payload(json: String): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream().also { output -> DeflaterOutputStream(output).use { it.write(bytes) } }.toByteArray()
        return PresetQrCodec.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes) + compressed)
    }
    private fun root(parameters: JSONObject = JSONObject(SavedPresetStore.snapshot(full()))) = JSONObject().put("version",1).put("name","Golden 120").put("parameters",parameters)
    private fun fails(problem: PresetQrCodec.Problem? = null, action: () -> Unit) {
        try { action(); fail("Expected QR rejection") } catch (failure: PresetQrCodec.Failure) { if (problem != null) assertEquals(problem, failure.problem) }
    }
    @Test fun completeCurrentPresetsRoundTripAndMeasureCapacity() {
        listOf("default" to EditorState(), "all89" to full()).forEach { (name, input) ->
            val text = PresetQrCodec.encode(name, input)
            val output = PresetQrCodec.decode(text)
            assertEquals(input, output.parameters); assertEquals(name, output.name)
            println("CAPACITY $name snapshot=${SavedPresetStore.snapshot(input).toByteArray().size} envelope=${root(JSONObject(SavedPresetStore.snapshot(input))).toString().toByteArray().size} qr=${text.length}")
            assertTrue(text.length <= PresetQrCodec.MAX_TEXT)
        }
    }
    @Test fun missingNewTopLevelAndNestedFieldsUseDefaultsWithoutChangingPresentValues() {
        val source = JSONObject(SavedPresetStore.snapshot(full()))
        source.remove("brightnessEv"); source.remove("paperGammaGreen"); source.remove("mediumPortrait")
        source.getJSONObject("imprint").remove("size")
        val decoded = PresetQrCodec.decode(payload(root(source).toString())).parameters
        assertEquals(full().copy(brightness=EditorState().brightness, paperGammaGreen=EditorState().paperGammaGreen,
            mediumPortrait=EditorState().mediumPortrait, imprint=full().imprint.copy(size=EditorState().imprint.size)), decoded)
    }
    @Test fun frozenV1CodeRemainsReadableAfterNewParametersAreAdded() {
        val payload = checkNotNull(javaClass.getResourceAsStream("/presets/qr-v1-code54.txt")).bufferedReader().use { it.readText().trim() }
        val result = PresetQrCodec.decode(payload)
        val defaults = EditorState()
        assertEquals("旧二维码兼容测试", result.name)
        assertEquals(defaults.copy(filmStock="kodak_gold_200",paperStock="kodak_2393",brightness=.75f,grain=.4f,
            imprint=defaults.imprint.copy(dateStyle="contax_t2",corner="top_left",size=1.2,glow=.3,dateEnabled=true)), result.parameters)
    }
    @Test fun longUnicodeNamesRemainWholeAndWithinCapacity() {
        val name = "🌅".repeat(60)
        val qr = PresetQrCodec.encode(name, full())
        assertEquals(name, PresetQrCodec.decode(qr).name)
        assertEquals(full(), PresetQrCodec.decode(qr).parameters)
        assertTrue(qr.length <= PresetQrCodec.MAX_TEXT)
    }
    @Test fun sparseLegacySnapshotMigratesAdditively() {
        val parameters = JSONObject().put("schemaVersion",1).put("filmStock","kodak_gold_200").put("brightnessEv",.75)
        assertEquals(EditorState(filmStock="kodak_gold_200",brightness=.75f), PresetQrCodec.decode(payload(root(parameters).toString())).parameters)
        val oldImprint = JSONObject().put("version",1).put("dateStyle","contax_t2").put("corner","top_left").put("size",1).put("glow",.5).put("exposure",false)
        parameters.put("imprint",oldImprint)
        val result = PresetQrCodec.decode(payload(root(parameters).toString())).parameters
        assertTrue(result.imprint.dateEnabled); assertEquals("photo", result.imprint.datePlacement); assertNull(result.imprint.manualDateTime)
    }
    @Test fun futureVersionsUnknownFieldsAndEnumsAreNeverSilentlyLost() {
        listOf(root().put("version",2), root().put("parameters",JSONObject().put("schemaVersion",2)),
            root().apply { getJSONObject("parameters").put("futureKnob",true) },
            root().apply { getJSONObject("parameters").getJSONObject("imprint").put("futureStyle",1) },
            root().apply { getJSONObject("parameters").put("outputRoute","future") },
            root().apply { getJSONObject("parameters").getJSONObject("imprint").put("version",4) }).forEach { fails(PresetQrCodec.Problem.INCOMPATIBLE) { PresetQrCodec.decode(payload(it.toString())) } }
    }
    @Test fun allSupportedMeteringModesAndDiffusionChoicesRemainShareable() {
        for (meter in dev.filmroom.diagnostics.MeteringMode.entries) {
            val state = full().copy(metering=meter.id)
            assertEquals(state, PresetQrCodec.decode(PresetQrCodec.encode("Metering",state)).parameters)
        }
        for (filter in listOf("off","glimmerglass","black_pro_mist","pro_mist","cinebloom")) {
            val state = full().copy(diffusionFilter=filter,printDiffusionFilter=filter)
            assertEquals(state, PresetQrCodec.decode(PresetQrCodec.encode("Diffusion",state)).parameters)
        }
    }
    @Test fun sampledHighEntropyValuesAreMeasuredWithoutDroppingParameters() {
        @Suppress("UNCHECKED_CAST")
        val ranges = PresetQrParameters::class.java.getDeclaredField("ranges").apply { isAccessible=true }.get(PresetQrParameters) as Map<String,ClosedFloatingPointRange<Double>>
        val random = java.util.Random(54)
        var maximum=0;var rejected=0
        repeat(100) {
            val json=JSONObject(SavedPresetStore.snapshot(full()))
            for ((key,range) in ranges) if (key !in setOf("seed","grainLayers","halationBounces")) json.put(key,(range.start+random.nextDouble()*(range.endInclusive-range.start)).toFloat().toDouble())
            val state=EditorState.fromJson(json.toString())
            val name=(0 until 60).joinToString("") { (0x4e00+random.nextInt(10000)).toChar().toString() }
            try { val text=PresetQrCodec.encode(name,state); maximum=maxOf(maximum,text.length); assertEquals(state,PresetQrCodec.decode(text).parameters) }
            catch(error:PresetQrCodec.Failure) { assertEquals(PresetQrCodec.Problem.TOO_LARGE,error.problem);rejected++ }
        }
        println("CAPACITY entropy100 max=$maximum rejected=$rejected limit=${PresetQrCodec.MAX_TEXT}")
    }
    @Test fun oldNestedVersionsNeverDiscardExplicitNewerFields() {
        for ((key, value) in listOf("dateEnabled" to false, "datePlacement" to "edge", "manualDateTime" to "bad", "layoutVersion" to 999)) {
            val imprint = JSONObject().put("version",1).put("dateStyle","contax_t2").put(key,value)
            fails(PresetQrCodec.Problem.INCOMPATIBLE) { PresetQrCodec.decode(payload(root(JSONObject().put("imprint",imprint)).toString())) }
        }
    }
    @Test fun inputLimitsCorruptionInvalidTypesAndNonFiniteNumbersFailClosed() {
        val good = PresetQrCodec.encode("Valid", full())
        listOf("https://example.com", "FILMROOM:9:abc", good + " ", good.dropLast(10), "x".repeat(2201)).forEach { fails { PresetQrCodec.decode(it) } }
        listOf("brightnessEv" to "1", "grainLayers" to 1.5, "useGpu" to "true", "grainAmount" to 999, "brightnessEv" to JSONObject.NULL).forEach { (key, value) ->
            fails { PresetQrCodec.decode(payload(root().apply { getJSONObject("parameters").put(key,value) }.toString())) }
        }
        fails { PresetQrCodec.decode(payload("{\"version\":1,\"version\":1,\"name\":\"x\",\"parameters\":{}}")) }
        fails { PresetQrCodec.decode(payload(root().toString()+"{}")) }
        fails { PresetQrCodec.decode(payload(root().toString().replace("Golden 120", "\\u+123"))) }
        fails { PresetQrCodec.decode(payload(root().toString().replace("0.75","1e999"))) }
        fails(PresetQrCodec.Problem.TOO_LARGE) { PresetQrCodec.decode(payload(" ".repeat(40000))) }
        fails { PresetQrCodec.decode(payload("{\"a\":".repeat(20)+"{}"+"}".repeat(20))) }
    }
    @Test fun changingIntegrityBytesOrAddingCompressedTailFails() {
        val good = payload(root().toString())
        val data = Base64.getUrlDecoder().decode(good.removePrefix(PresetQrCodec.PREFIX))
        data[0] = (data[0].toInt() xor 1).toByte()
        fails { PresetQrCodec.decode(PresetQrCodec.PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(data)) }
        val clean = Base64.getUrlDecoder().decode(good.removePrefix(PresetQrCodec.PREFIX))
        fails { PresetQrCodec.decode(PresetQrCodec.PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(clean+byteArrayOf(1))) }
    }
}
