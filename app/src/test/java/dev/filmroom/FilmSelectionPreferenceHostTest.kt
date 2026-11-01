package dev.filmroom

import dev.filmroom.model.EditorState
import dev.filmroom.model.EditHistory
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobStore
import dev.filmroom.export.ExportJobState
import dev.filmroom.io.ExportFormat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class FilmSelectionPreferenceHostTest {
    private fun EditorState.e100()=selectFilm("kodak_ektachrome_100","kodak_portra_endura","direct","kodak_portra_endura")
    private fun EditorState.gold()=selectFilm("kodak_gold_200","kodak_portra_endura","print","kodak_portra_endura")
    @Test fun untouchedDefaultsFollowFilmButExplicitDirectAndPrintSurviveReversalRoundTrip(){
        val auto=EditorState().e100();assertEquals("direct",auto.outputRoute);assertNull(auto.preferredOutputRoute)
        assertEquals("print",auto.gold().outputRoute);assertNull(auto.gold().preferredOutputRoute)
        for(route in listOf("direct","print")){
            val chosen=EditorState().selectOutputRoute(route)
            assertEquals(route,chosen.gold().outputRoute)
            val reversal=chosen.e100();assertEquals("direct",reversal.outputRoute);assertEquals(route,reversal.preferredOutputRoute)
            assertEquals(route,reversal.gold().outputRoute);assertEquals(route,reversal.gold().preferredOutputRoute)
        }
    }
    @Test fun stockChangePreservesEveryUnrelatedManualControlAndExistingPaperPolicy(){
        val p=EditorState(brightness=.5f,filmExposure=1.1f,grain=.3f,halation=.6f,paperStock="kodak_2393",scanWhiteCorrection=true,scanBlackCorrection=true).selectOutputRoute("direct")
        assertEquals(p.copy(filmStock="kodak_ektachrome_100"),p.e100())
        assertEquals(p.copy(filmStock="kodak_gold_200"),p.e100().gold())
        val autoPaper=EditorState().selectFilm("kodak_vision3_50d","kodak_2383","print","kodak_portra_endura")
        assertEquals("kodak_2383",autoPaper.paperStock)
        assertEquals("kodak_2393",p.selectFilm("kodak_vision3_50d","kodak_2383","print","kodak_portra_endura").paperStock)
    }
    @Test fun legacySessionMigrationIsConservativeAndNewSessionRoundTripsExactly(){
        fun legacy(film:String,route:String)=JSONObject(EditorState(filmStock=film,outputRoute=route).json()).apply{remove("preferredOutputRoute")}.toString()
        val negative=EditorState.fromJson(legacy("kodak_gold_200","direct"));assertEquals("direct",negative.preferredOutputRoute);assertEquals("direct",negative.e100().gold().outputRoute)
        val reversal=EditorState.fromJson(legacy("kodak_ektachrome_100","direct"));assertNull(reversal.preferredOutputRoute);assertEquals("print",reversal.gold().outputRoute)
        val oldInvalid=EditorState.fromJson(legacy("kodak_ektachrome_100","print"));assertEquals("direct",oldInvalid.outputRoute);assertEquals("print",oldInvalid.preferredOutputRoute)
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("route-session-test",0)
        val current=EditorState(scanWhiteCorrection=true).selectOutputRoute("print").e100()
        prefs.edit().putString("parameters",current.json()).commit()
        assertEquals(current,EditorState.fromJson(prefs.getString("parameters",null)!!))
        assertNull(EditorState.fromJson(EditorState().json()).preferredOutputRoute)
    }
    @Test fun unsupportedCorrectionIsTemporarilyOffOnlyAtNativeBoundaryAndDiagnostics(){
        val stored=EditorState(scanWhiteCorrection=true,scanBlackCorrection=true).selectOutputRoute("direct")
        val native=JSONObject(stored.json(includePresentation=false));val saved=JSONObject(stored.json())
        assertTrue(saved.getBoolean("scanWhiteCorrection"));assertTrue(saved.getBoolean("scanBlackCorrection"))
        assertFalse(native.getBoolean("scanWhiteCorrection"));assertFalse(native.getBoolean("scanBlackCorrection"))
        assertFalse(native.has("preferredOutputRoute"));assertEquals(false,stored.safeInfo().scanWhiteCorrection);assertEquals(false,stored.safeInfo().scanBlackCorrection)
        assertEquals(stored,EditorState.fromJson(saved.toString()))
        assertTrue(JSONObject(stored.e100().json(includePresentation=false)).getBoolean("scanWhiteCorrection"))
        assertTrue(JSONObject(stored.selectOutputRoute("print").json(includePresentation=false)).getBoolean("scanBlackCorrection"))
        assertEquals("No scientific cache change for intent alone",EditorState().json(includePresentation=false),EditorState().selectOutputRoute("print").json(includePresentation=false))
    }
    @Test fun undoRedoAndPresetResetTreatRouteIntentAsPartOfTheEditorState(){
        val selected=EditorState().selectOutputRoute("print")
        val history=EditHistory(selected);history.update(selected.e100());history.update(history.current.gold())
        history.undo();assertEquals("direct",history.current.outputRoute);assertEquals("print",history.current.preferredOutputRoute)
        history.undo();assertEquals(selected,history.current);history.redo();assertEquals(selected.e100(),history.current)
        val reset=selected.e100().processingDefaults("kodak_portra_endura","direct")
        assertNull(reset.preferredOutputRoute);assertEquals("print",reset.gold().outputRoute)
    }
    @Test fun exportQueueFreezesIntentAndStoredCorrectionsButNativeGetsEffectiveValues(){
        val dir=File(RuntimeEnvironment.getApplication().cacheDir,"route-queue-${System.nanoTime()}")
        try{
            val frozen=EditorState(scanWhiteCorrection=true).selectOutputRoute("direct")
            val job=ExportJob(ExportJobStore.newId(),123,frozen,32,24,ExportFormat.PNG,95,state=ExportJobState.PAUSED)
            ExportJobStore(dir).update(job)
            val loaded=ExportJobStore(dir).get(job.id)!!
            assertEquals(frozen,loaded.parameters);assertTrue(loaded.parameters.scanWhiteCorrection)
            assertFalse(JSONObject(loaded.parameters.json("export",includePresentation=false)).getBoolean("scanWhiteCorrection"))
            val edited=frozen.selectOutputRoute("print");assertNotEquals(edited,loaded.parameters)
            assertEquals("direct",loaded.parameters.e100().gold().outputRoute)
        }finally{dir.deleteRecursively()}
    }
    @Test fun legacyQueueFilesWithoutIntentMigrateWithoutChangingFrozenScience(){
        val dir=File(RuntimeEnvironment.getApplication().cacheDir,"legacy-route-queue-${System.nanoTime()}")
        try{
            for(film in listOf("kodak_gold_200","kodak_ektachrome_100")){
                val frozen=EditorState(filmStock=film,outputRoute="direct",scanWhiteCorrection=false,scanBlackCorrection=false)
                val job=ExportJob(ExportJobStore.newId(),123,frozen,32,24,ExportFormat.PNG,95,state=ExportJobState.PAUSED)
                val store=ExportJobStore(dir);store.update(job)
                val file=File(store.directory(job.id),"manifest.json")
                val manifest=JSONObject(file.readText())
                assertEquals(1,manifest.getInt("version"))
                val legacyParams=JSONObject(manifest.getString("parameters")).apply{remove("preferredOutputRoute")}
                file.writeText(manifest.put("parameters",legacyParams.toString()).toString())
                val loaded=ExportJobStore(dir).get(job.id)!!
                assertEquals(ExportJobState.PAUSED,loaded.state)
                assertEquals(frozen.json("export",includePresentation=false),loaded.parameters.json("export",includePresentation=false))
                if(film=="kodak_gold_200"){
                    assertEquals("direct",loaded.parameters.preferredOutputRoute)
                    assertEquals("direct",loaded.parameters.e100().gold().outputRoute)
                }else{
                    assertNull(loaded.parameters.preferredOutputRoute)
                    assertEquals("print",loaded.parameters.gold().outputRoute)
                }
                assertEquals(job.id,loaded.id);assertEquals(job.createdAt,loaded.createdAt)
            }
        }finally{dir.deleteRecursively()}
    }

}
