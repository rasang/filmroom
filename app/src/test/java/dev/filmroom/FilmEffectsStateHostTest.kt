package dev.filmroom

import dev.filmroom.model.EditHistory
import dev.filmroom.model.EditorState
import dev.filmroom.diagnostics.DiffusionProfile
import dev.filmroom.diagnostics.SafeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class FilmEffectsStateHostTest {
    @Test fun negativeWhiteHolesIsPersistentUndoableAndNeverScientific(){
        val default=EditorState.fromJson("{}")
        assertTrue(default.negativeWhiteHoles)
        val dark=default.copy(negativeWhiteHoles=false)
        assertEquals(dark,EditorState.fromJson(dark.json()))
        assertEquals(default.json(includePresentation=false),dark.json(includePresentation=false))
        assertFalse(org.json.JSONObject(dark.json(includePresentation=false)).has("negativeWhiteHoles"))
        val history=EditHistory(default);history.update(dark)
        assertFalse(history.current.negativeWhiteHoles);history.undo();assertTrue(history.current.negativeWhiteHoles)
        history.redo();assertFalse(history.current.negativeWhiteHoles)
        assertTrue(EditorState().negativeWhiteHoles)
    }

    @Test fun oldSessionsKeepAllNewEffectsNeutral(){
        val state=EditorState.fromJson("""{"filmStock":"kodak_gold_200","brightnessEv":0.5}""")
        assertEquals("off",state.diffusionFilter)
        assertEquals(0f,state.printYellow)
        assertEquals(0f,state.printMagenta)
        assertEquals(0f,state.preflash)
        assertEquals(0f,state.scanSharpenAmount)
        assertEquals(0.5f,state.brightness)
    }
    @Test fun newEffectFieldsRoundTripAndReachNativeJson(){
        val state=EditorState(diffusionFilter="black_pro_mist",diffusionStrength=1.2f,diffusionSize=0.75f,
            printYellow=-20f,printMagenta=14f,preflash=0.2f,scanSharpenAmount=1.5f,scanSharpenRadius=2.4f)
        assertEquals(state,EditorState.fromJson(state.json("export")))
        val json=org.json.JSONObject(state.json())
        for(key in listOf("diffusionFilter","diffusionStrength","diffusionSize","printYellow","printMagenta","preflash","scanSharpenAmount","scanSharpenRadius"))assertTrue(json.has(key))
    }
    @Test fun completeAdvancedContractRoundTripsWithSafeDiagnostics(){
        val p=EditorState(grainRedScale=2f,grainGreenScale=.5f,grainBlueScale=3f,halationColor="amber",
            printDiffusionFilter="cinebloom",printDiffusionStrength=1.2f,neutralFiltersFromDatabase=false,
            neutralCyan=20f,neutralMagenta=30f,neutralYellow=40f,paperGammaRed=1.4f,paperDeveloperExhaustion=.4f,
            outputColorSpace="Display P3",outputGamut=false,autoExposure=true,metering="matrix",useGpu=false)
        assertEquals(p,EditorState.fromJson(p.json("export")))
        val fields=p.safeInfo().fields()
        assertEquals("amber",fields["halationColor"]);assertEquals("2.0",fields["grainRedScale"])
        assertEquals("cinebloom",fields["printDiffusionFilter"])
        assertEquals(fields,SafeInfo.parse(fields).fields())
    }
    @Test fun legacyScatterMigrationPreservesDisabledHalation(){
        assertEquals(0f,EditorState.fromJson("{\"halationAmount\":0}").scatterAmount)
        assertEquals(1f,EditorState.fromJson("{\"halationAmount\":0.5}").scatterAmount)
    }
    @Test fun undoRedoRetainsAllAdvancedValues(){
        val initial=EditorState()
        val adjusted=initial.copy(diffusionFilter="glimmerglass",printYellow=10f,preflash=0.05f,scanSharpenAmount=0.4f)
        val history=EditHistory(initial);history.update(adjusted);history.undo();assertEquals(initial,history.current)
        history.redo();assertEquals(adjusted,history.current)
    }
    @Test fun newDiagnosticFieldsAreBoundedAndDoNotAcceptArbitraryFilterStrings(){
        val valid=EditorState(diffusionFilter="black_pro_mist",preflash=0.2f,scanSharpenAmount=2f).safeInfo().fields()
        assertEquals("black_pro_mist",valid["diffusionFilter"])
        assertEquals("0.2",valid["preflash"])
        assertEquals(valid,SafeInfo.parse(valid).fields())
        val rejected=SafeInfo.parse(mapOf("diffusionFilter" to "/private/photo.dng","diffusionStrength" to "NaN","preflash" to "9","scanSharpenRadius" to "99")).fields()
        assertTrue(rejected.isEmpty())
    }
}
