package dev.filmroom.backup

import android.app.Application
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.export.ExportHistoryBackup
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState
import dev.filmroom.export.ExportJobStore
import dev.filmroom.io.ExportFormat
import java.nio.file.Files
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.CropMode
import dev.filmroom.model.CropSettings
import dev.filmroom.model.EditorState
import dev.filmroom.presets.PresetQrCodec
import dev.filmroom.presets.SavedPresetStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], application=Application::class)
class BackupMediaCompositionCrossHostTest {
    private fun profiles() = JSONArray("""[{"id":"kodak_portra_400","kind":"negative"},{"id":"kodak_portra_endura","kind":"paper"}]""")
    private fun document(state:EditorState) = AppBackupCodec.Document(
        JSONObject().put("resetOnImport",true).put("restoreLastPhoto",false),
        listOf(SavedPresetStore.Preset("83d381d6-3875-4c24-a7f8-1cabd63e6e4e","Integrated recipe",123L,SavedPresetStore.snapshot(state))),
        // History comes from a valid runnable job; the serialized recipe above deliberately keeps every border value.
        ExportHistoryBackup.exportRows(listOf(ExportJob(
            "2b49c3bf-3875-4c24-a7f8-1cabd63e6e4e",123L,state.copy(filmBorder=false),640,480,ExportFormat.JPEG,95,
            ExportJobState.RUNNING,ExportDisplayProgress(1),inputReady=true,
            mediaUri="content://media/external/images/media/42",outputName="private-photo.jpg"))))

    @Test fun fourMediaChoicesAndBorderRotationSurviveBackupThenQrExactly() {
        for(film in listOf("none","kodak_portra_400")) for(paper in listOf("none","kodak_portra_endura"))
            for(stage in 0..1) for(angle in listOf(-15.0,-0.1,0.0,0.1,15.0)) {
                val state=EditorState(filmStock=film,paperStock=paper,filmBorder=true,filmBorderStage=stage,
                    composition=CompositionTransform(centerX=.37,centerY=.63,zoom=2.25,straightenDegrees=angle),
                    crop=CropSettings(CropMode.FREE,CompositionTransform(centerX=.61,centerY=.41,zoom=1.25,
                        targetAspect=1.25,straightenDegrees=if(angle==0.0)0.0 else -angle)))
                val bytes=AppBackupCodec.encode(document(state))
                val restored=AppBackupCodec.read(bytes.inputStream())
                AppBackupCodec.validateStocks(restored,profiles())
                val recovered=restored.recipes.single().parameters()
                assertEquals(state,recovered)
                assertTrue(restored.settings.getBoolean("resetOnImport"))
                assertFalse(restored.settings.getBoolean("restoreLastPhoto"))
                assertEquals(1,restored.history.length())
                assertFalse(String(bytes,Charsets.UTF_8).contains("content://"))
                assertFalse(String(bytes,Charsets.UTF_8).contains("private-photo.jpg"))
                val root=Files.createTempDirectory("backup-media-cross").toFile()
                try {
                    val store=ExportJobStore(root)
                    assertEquals(1,store.mergeBackupHistory(restored.history))
                    assertEquals(0,store.mergeBackupHistory(restored.history))
                    val history=store.jobs.value.single()
                    assertTrue(history.archivedHistory)
                    assertFalse(history.inputReady)
                    assertFalse(history.canRetry)
                    assertEquals(ExportJobState.CANCELLED,history.state)
                    assertEquals("RUNNING",history.archivedSourceState)
                    assertEquals(film,history.parameters.filmStock)
                    assertNull(history.mediaUri)
                    assertNull(history.outputName)
                    assertNull(store.next())
                    assertFalse(store.directory(history.id).exists())
                    val reopened=ExportJobStore(root)
                    assertTrue(reopened.get(history.id)!!.archivedHistory)
                    assertNull(reopened.next())
                } finally { root.deleteRecursively() }
                val qr=PresetQrCodec.decode(PresetQrCodec.encode("Integrated recipe",recovered)).parameters
                assertEquals(state,qr)
                assertEquals(film!="none",qr.hasFilm)
                assertEquals(paper!="none",qr.hasPaper)
            }
    }

    @Test fun explicitNoneDoesNotTurnUnknownOrWrongKindProfilesIntoAcceptedBackup() {
        for(state in listOf(EditorState(filmStock="future_film",paperStock="none"),
            EditorState(filmStock="none",paperStock="future_paper"),
            EditorState(filmStock="kodak_portra_endura",paperStock="none"))) {
            val restored=AppBackupCodec.read(AppBackupCodec.encode(document(state)).inputStream())
            assertThrows(IllegalArgumentException::class.java) { AppBackupCodec.validateStocks(restored,profiles()) }
        }
    }
}
