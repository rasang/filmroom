package dev.filmroom.presets

import android.app.Application
import dev.filmroom.model.EditorState
import dev.filmroom.backup.AppBackupCodec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** Migration regression for beta7's removed independent switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,manifest=Config.NONE)
class NoFilmGrainOptInHostTest {
    private fun state()=EditorState(filmStock="none",paperStock="none",grain=1.7f)
    @Test fun noFilmUsesOrdinaryGrainControlsAndDoesNotSerializeExtraSwitch() {
        val p=state()
        for(id in listOf("grain","grainAmount","grainSize","grainSublayers","grainLayers","grainBlur","grainDyeCloud","grainUniformity","grainRedScale","grainGreenScale","grainBlueScale","grainClumpSizeUm","grainClumpStrengthNm")) assertTrue(id,p.controlSupported(id))
        assertFalse(p.controlSupported("halation"))
        assertFalse(JSONObject(p.json()).has("noFilmGrainEnabled"))
        assertEquals(p,EditorState.fromJson(p.json()))
    }
    @Test fun beta7SessionFlagIsIgnoredWithoutChangingOrdinaryParameters() {
        for(flag in listOf(false,true)) for(amount in listOf(0f,1.7f)) {
            val p=state().copy(grain=amount)
            val old=JSONObject(p.json()).put("noFilmGrainEnabled",flag)
            assertEquals(p,EditorState.fromJson(old.toString()))
        }
    }
    @Test fun frozenQrSchemaAndLegacyCodesRemainReadable() {
        assertFalse(PresetQrSchemaV2.BASELINE.contains("noFilmGrainEnabled"))
        val p=state()
        assertEquals(p,PresetQrCodec.decode(PresetQrCodec.encode("Portra",p)).parameters)
        for(flag in listOf(false,true)) {
            val old=JSONObject(SavedPresetStore.snapshot(p)).put("noFilmGrainEnabled",flag)
            val decoded=PresetQrBinary.decode(PresetQrBinary.encode("Portra",old)).second
            assertEquals(p,PresetQrParameters.read(decoded,true))
            assertFalse(PresetQrParameters.mergeForImport(decoded,JSONObject(SavedPresetStore.snapshot(EditorState())),true).has("noFilmGrainEnabled"))
        }
    }
    @Test fun backupsIgnoreObsoleteSwitchAndKeepZeroGrain() {
        for(flag in listOf(false,true)) for(amount in listOf(0f,1.7f)) {
            val p=state().copy(grain=amount)
            val old=JSONObject(SavedPresetStore.snapshot(p)).put("noFilmGrainEnabled",flag)
            val doc=AppBackupCodec.Document(JSONObject(),listOf(SavedPresetStore.Preset(UUID.randomUUID().toString(),"Portra",1L,old.toString())),JSONArray())
            val restored=AppBackupCodec.read(AppBackupCodec.encode(doc).inputStream())
            assertEquals(p,restored.recipes.single().parameters())
        }
    }
    @Test fun malformedLegacyQrFlagIsStillRejected() {
        for(v in listOf<Any>("true",1,JSONObject.NULL)) {
            val input=JSONObject(SavedPresetStore.snapshot(state())).put("noFilmGrainEnabled",v)
            try { PresetQrParameters.read(input); fail("Must reject $v") } catch(_:PresetQrCodec.Failure) {}
        }
    }
}
