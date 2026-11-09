package dev.filmroom.export

import android.app.Application
import dev.filmroom.ExportJobPresentation
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ExportHistoryBackupHostTest {
    @org.junit.Before fun initializeLanguage() { dev.filmroom.i18n.L10n.initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()) }
    private fun job(state:ExportJobState=ExportJobState.COMPLETED)=ExportJob(
        ExportJobStore.newId(),1780000000000,EditorState(),640,480,ExportFormat.JPEG,95,state,
        mediaUri="content://media/external/images/media/123",message="private /data/user/0/source.raw",
        outputName="private-filename.jpg",metadataWarning="secret",photoMetadata=null)
    private fun temp(block:(File)->Unit){val dir=Files.createTempDirectory("backup-history").toFile();try{block(dir)}finally{dir.deleteRecursively()}}
    private fun invalid(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalArgumentException){}catch(_:org.json.JSONException){}}
    @Test fun exportAllowlistKeepsOnlyRequestedImageLinksAndExcludesDiagnostics(){
        val text=ExportHistoryBackup.exportRows(listOf(job())).toString()
        assertEquals(job().mediaUri,JSONArray(text).getJSONObject(0).getString("mediaUri"));assertTrue(text.contains("private-filename"))
        listOf("/data/","secret","parameters","photoMetadata","message").forEach{assertFalse(it,text.contains(it))}
    }
    @Test fun newBackupsPreserveCompositionAndTypedCaptureMetadata()=temp{dir->
        val crop=dev.filmroom.crop.CropSnapshot.create(640,480)
        val metadata=dev.filmroom.io.PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2026:11:09 12:30:00","ExposureTime" to "1/125"))
        val original=job().copy(crop=crop,photoMetadata=JSONObject(metadata.toJson()).put("warning","private /data/path").toString())
        val rows=ExportHistoryBackup.exportRows(listOf(original))
        assertFalse(rows.toString().contains("private /data/path"))
        val store=ExportJobStore(dir);store.mergeBackupHistory(rows)
        val restored=ExportJobStore(dir).get(original.id)!!
        assertEquals(crop,restored.crop);assertNull(restored.border)
        assertEquals(metadata.imprintDateTime(),dev.filmroom.io.PhotoMetadata.fromJson(restored.photoMetadata!!).imprintDateTime())
        assertFalse(restored.inputReady);assertTrue(restored.archivedHistory)
    }
    @Test fun borderBackupKeepsSourceAndOutputDimensionsDistinct(){
        val base=job()
        val border=dev.filmroom.border.BorderSnapshot(base.parameters.filmStock,"v1",1,640,480,10,10)
        val original=base.copy(parameters=base.parameters.copy(filmBorder=true),border=border)
        val restored=ExportHistoryBackup.validateRows(ExportHistoryBackup.exportRows(listOf(original))).single()
        assertEquals(border,restored.border);assertEquals(original.outputWidth,restored.outputWidth)
        assertEquals(640,restored.width);assertEquals(original.parameters,restored.parameters)
        val document=dev.filmroom.backup.AppBackupCodec.Document(JSONObject().put("resetOnImport",true).put("restoreLastPhoto",false),emptyList(),ExportHistoryBackup.exportRows(listOf(original)))
        val decoded=dev.filmroom.backup.AppBackupCodec.read(dev.filmroom.backup.AppBackupCodec.encode(document).inputStream())
        assertEquals(border,ExportHistoryBackup.validateRows(decoded.history).single().border)
    }
    @Test fun legacyBackupsWithoutNewFieldsStayReadable(){
        val restored=ExportHistoryBackup.validateRows(ExportHistoryBackup.exportRows(listOf(job()))).single()
        assertNull(restored.crop);assertNull(restored.border);assertNull(restored.photoMetadata)
    }
    @Test fun malformedGeometryRejectsInsteadOfMakingWrongComposition(){
        val rows=ExportHistoryBackup.exportRows(listOf(job()))
        rows.getJSONObject(0).put("crop","{}")
        invalid{ExportHistoryBackup.validateRows(rows)}
    }
    @Test fun restoredRunningRowsNeverQueueOrRetryAndAreNotShownAsSaved()=temp{dir->
        val store=ExportJobStore(dir);val row=job(ExportJobState.RUNNING)
        assertEquals(1,store.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(row))))
        val restored=store.get(row.id)!!
        assertTrue(restored.archivedHistory);assertFalse(restored.inputReady);assertFalse(restored.canRetry)
        assertEquals(ExportJobState.CANCELLED,restored.state);assertEquals("RUNNING",restored.archivedSourceState)
        assertNull(restored.mediaUri);assertNull(restored.photoMetadata);assertNull(restored.outputName);assertNull(store.next())
        assertFalse(ExportJobPresentation.from(restored).canRetry);assertFalse(ExportJobPresentation.from(restored).canCancel)
        assertTrue(restored.localizedMessage!!.isNotEmpty());assertFalse(store.directory(row.id).exists())
        assertTrue(ExportJobStore(dir).get(row.id)!!.archivedHistory)
    }
    @Test fun existingRowsWinAndReimportIsIdempotent()=temp{dir->
        val store=ExportJobStore(dir);val existing=job();store.update(existing)
        val fresh=job();val rows=ExportHistoryBackup.exportRows(listOf(existing,fresh))
        assertEquals(1,store.mergeBackupHistory(rows));assertEquals(0,store.mergeBackupHistory(rows))
        assertEquals(existing,store.get(existing.id));assertEquals(2,store.jobs.value.size)
    }
    @Test fun unknownFieldsAndCoercedNumericTypesRejectBeforeMutation()=temp{dir->
        val store=ExportJobStore(dir)
        val rows=ExportHistoryBackup.exportRows(listOf(job()))
        rows.getJSONObject(0).put("mediaUri","file:///secret")
        invalid{store.mergeBackupHistory(rows)};assertTrue(store.jobs.value.isEmpty())
        rows.getJSONObject(0).remove("mediaUri");rows.getJSONObject(0).put("width","640")
        invalid{store.mergeBackupHistory(rows)};assertTrue(store.jobs.value.isEmpty())
    }
    @Test fun archiveDeletesOnlyItsRecordEvenUnderDeletePhotosPreference()=temp{dir->
        val store=ExportJobStore(dir);val original=job();store.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(original)))
        val snapshot=store.historySnapshot(setOf(original.id)).single();assertEquals(original.mediaUri,snapshot.mediaUri)
        val op=store.beginHistory(listOf(snapshot),true)
        assertTrue(store.removeHistoryRecord(op,snapshot));assertTrue(store.jobs.value.isEmpty())
        assertTrue(ExportJobStore(dir).jobs.value.isEmpty())
    }
    @Test fun corruptedRowsDoNotPartiallyImport()=temp{dir->
        val store=ExportJobStore(dir);val rows=ExportHistoryBackup.exportRows(listOf(job(),job()))
        rows.getJSONObject(1).put("id","../../victim")
        invalid{store.mergeBackupHistory(rows)};assertTrue(store.jobs.value.isEmpty());assertFalse(File(dir,".backup-history.json").exists())
    }
    @Test fun archiveSnapshotRoundTripsAndCannotBecomeExecutable()=temp{dir->
        val store=ExportJobStore(dir);store.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(job())))
        val snapshot=store.archiveSnapshot();val restored=store.jobs.value.single()
        invalid{store.update(restored.copy(state=ExportJobState.PENDING))}
        store.restoreArchiveSnapshot(JSONArray());assertTrue(store.jobs.value.isEmpty())
        store.restoreArchiveSnapshot(snapshot);assertEquals(1,store.jobs.value.size)
        assertNull(store.next())
    }
    @Test fun duplicateIdentifiersReject(){val first=ExportHistoryBackup.exportRows(listOf(job())).getJSONObject(0)
        invalid{ExportHistoryBackup.validateRows(JSONArray().put(first).put(JSONObject(first.toString())))}
    }
    @Test fun corruptArchiveCannotBreakLiveQueueOrBeSilentlyOverwritten()=temp{dir->
        val live=job();ExportJobStore(dir).update(live)
        val file=File(dir,".backup-history.json");file.writeText("broken")
        val store=ExportJobStore(dir)
        assertEquals(live,store.get(live.id))
        try{store.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(job())));fail("Expected failure")}catch(_:IllegalStateException){}
        try{store.exportBackupHistory();fail("Expected failure")}catch(_:IllegalStateException){}
        assertEquals("broken",file.readText())
    }

    @Test fun allSupportedColorSpacesIncludingAdobeRoundTrip(){
        dev.filmroom.io.OutputColorSpace.entries.forEach { color->
            val original=job().copy(parameters=EditorState(outputColorSpace=color.id))
            assertEquals(color.id,ExportHistoryBackup.validateRows(ExportHistoryBackup.exportRows(listOf(original))).single().parameters.outputColorSpace)
        }
    }
    @Test fun startupCleanupNeverRemovesArchive()=temp{root->
        val files=File(root,"files").apply{mkdir()};val cache=File(root,"cache").apply{mkdir()}
        val jobs=File(files,"export-jobs");val original=job()
        ExportJobStore(jobs).mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(original)))
        dev.filmroom.lifecycle.PrivateWorkRecovery.recover(cache,files,null)
        assertTrue(File(jobs,".backup-history.json").isFile)
        assertTrue(ExportJobStore(jobs).get(original.id)!!.archivedHistory)
    }

}
