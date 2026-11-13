package dev.filmroom
import android.app.Application
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import dev.filmroom.model.ExportHistoryAction
import dev.filmroom.model.ExportHistoryMode
import dev.filmroom.model.ExportHistoryPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ExportHistoryStoreHostTest {
 private fun root()=File(RuntimeEnvironment.getApplication().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
 private fun job(state:ExportJobState)=ExportJob(UUID.randomUUID().toString(),1,EditorState(),20,20,ExportFormat.JPEG,95,state,mediaUri=if(state==ExportJobState.COMPLETED)"content://media/external_primary/images/media/42"else null,exportMillis=123456,outputName="Filmroom_123456_abcdef.jpg")
 @Test fun completedRecordRemovalNeverDeletesAlbumOrOriginal(){val root=root();val store=ExportJobStore(root);val j=job(ExportJobState.COMPLETED);store.update(j)
  val original=File(root.parentFile,"source-${UUID.randomUUID()}").apply{writeText("original-data")};val album=File(root.parentFile,"album-${UUID.randomUUID()}").apply{writeText("exported-data")}
  File(store.directory(j.id),"final-preview.jpg").writeText("preview");val snapshot=store.historySnapshot(setOf(j.id));val op=store.beginHistory(snapshot,false)
  assertTrue(store.removeHistoryRecord(op,snapshot.single()));assertNull(store.get(j.id));assertEquals("original-data",original.readText());assertEquals("exported-data",album.readText());assertTrue(ExportJobStore(root).jobs.value.isEmpty())
 }
 @Test fun allStatesEligibilityAndSnapshotDoesNotIncludeLaterCompletion(){val store=ExportJobStore(root());val jobs=ExportJobState.entries.map(::job);jobs.forEach(store::update)
  val before=store.historySnapshot(jobs.map{it.id}.toSet());assertEquals(setOf(ExportJobState.COMPLETED,ExportJobState.FAILED,ExportJobState.CANCELLED),before.map{it.state}.toSet())
  val later=job(ExportJobState.COMPLETED);store.update(later);val op=store.beginHistory(before,false);op.targets.forEach{assertTrue(store.removeHistoryRecord(op,it))};assertNotNull(store.get(later.id));assertEquals(5,store.jobs.value.size)
 }
 @Test fun resumedFailedRecordAndStaleIdCannotBeRemoved(){val store=ExportJobStore(root());val j=job(ExportJobState.FAILED);store.update(j);val snapshot=store.historySnapshot(setOf(j.id));store.change(j.id){it.copy(state=ExportJobState.RETRY)}
  assertTrue(store.beginHistory(snapshot,false).targets.isEmpty());assertTrue(store.historySnapshot(setOf(UUID.randomUUID().toString())).isEmpty());assertNotNull(store.get(j.id))
 }
 @Test fun repeatedTapReservesOnceAndBlocksRetryUntilRelease(){val store=ExportJobStore(root());val j=job(ExportJobState.FAILED);store.update(j);val snap=store.historySnapshot(setOf(j.id));val one=store.beginHistory(snap,false);val two=store.beginHistory(snap,true);assertTrue(two.targets.isEmpty())
  store.change(j.id){it.copy(state=ExportJobState.RETRY)};assertEquals(ExportJobState.FAILED,store.get(j.id)!!.state);store.releaseHistory(one);store.change(j.id){it.copy(state=ExportJobState.RETRY)};assertEquals(ExportJobState.RETRY,store.get(j.id)!!.state)
 }
 @Test fun reopenedStoreDoesNotDeleteOrReplayPendingPhotoIntent(){val root=root();val first=ExportJobStore(root);val j=job(ExportJobState.COMPLETED);first.update(j);val op=first.beginHistory(first.historySnapshot(setOf(j.id)),true).copy(status="awaiting-consent",consentUri=j.mediaUri,consentKind="DELETE_REQUEST");first.updateHistoryOperation(op)
  val second=ExportJobStore(root);assertNotNull(second.get(j.id));assertEquals(op,second.readHistoryOperation(op.id));assertFalse(second.historyReserved(j.id));assertTrue(second.restoreHistoryReservation(op));assertTrue(second.historyReserved(j.id))
 }
 @Test fun restoredConsentRejectsChangedOutputIdentity(){val root=root();val first=ExportJobStore(root);val j=job(ExportJobState.COMPLETED);first.update(j);val op=first.beginHistory(first.historySnapshot(setOf(j.id)),true).copy(status="awaiting-consent");first.updateHistoryOperation(op)
  val second=ExportJobStore(root);second.update(j.copy(mediaUri="content://media/external_primary/images/media/99"));assertFalse(second.restoreHistoryReservation(op));assertNotNull(second.get(j.id))
 }
 @Test fun completionReadAckDoesNotInvalidateExactHistorySnapshot(){val store=ExportJobStore(root());val j=job(ExportJobState.COMPLETED);store.update(j);val snapshot=store.historySnapshot(setOf(j.id));store.markCompletionsViewed(listOf(j));assertTrue(store.historyMatches(snapshot.single()))}
 @Test fun tombstoneSurvivesRestartWithoutSynthesizingFailed(){val root=root();val store=ExportJobStore(root);val j=job(ExportJobState.FAILED);store.update(j);val from=store.directory(j.id);assertTrue(from.renameTo(File(root,".history-trash-${j.id}")));assertTrue(ExportJobStore(root).jobs.value.isEmpty())}
 @Test fun outputIdentityRoundtripsAndLegacyMissingStaysMissing(){val root=root();val store=ExportJobStore(root);val current=job(ExportJobState.COMPLETED);val old=job(ExportJobState.COMPLETED).copy(outputName=null);store.update(current);store.update(old);val reopened=ExportJobStore(root);assertEquals(current.outputName,reopened.get(current.id)!!.outputName);assertNull(reopened.get(old.id)!!.outputName)}
 @Test fun photoModeCannotRemoveManifestBeforeExactPhotoSuccessIsDurable(){val store=ExportJobStore(root());val j=job(ExportJobState.COMPLETED);store.update(j);val op=store.beginHistory(store.historySnapshot(setOf(j.id)),true);val snapshot=op.targets.single();assertFalse(store.removeHistoryRecord(op,snapshot));assertNotNull(store.get(j.id));val forged=op.copy(status="photo-deleted:${j.id}");assertFalse(store.removeHistoryRecord(forged,snapshot));store.updateHistoryOperation(forged);assertTrue(store.removeHistoryRecord(forged,snapshot))}
 @Test fun malformedIdsCannotBecomeDeletionPaths(){val store=ExportJobStore(root());assertTrue(store.historySnapshot(setOf("../album")).isEmpty());try{store.directory("../album");fail()}catch(_:IllegalArgumentException){}}
 @Test fun preferencesAreIndependentDurableAndSafeByDefault(){val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("export-history-preferences",0).edit().clear().commit();val prefs=ExportHistoryPreferences(context);assertEquals(ExportHistoryMode.ASK,prefs.get(ExportHistoryAction.SINGLE));assertEquals(ExportHistoryMode.ASK,prefs.get(ExportHistoryAction.CLEAR));prefs.set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_AND_PHOTOS);assertEquals(ExportHistoryMode.ASK,prefs.get(ExportHistoryAction.CLEAR));val reopened=ExportHistoryPreferences(context);assertEquals(ExportHistoryMode.RECORDS_AND_PHOTOS,reopened.get(ExportHistoryAction.SINGLE));reopened.set(ExportHistoryAction.CLEAR,ExportHistoryMode.RECORDS_ONLY);assertEquals(ExportHistoryMode.RECORDS_AND_PHOTOS,reopened.get(ExportHistoryAction.SINGLE))}
 @Test fun corruptPreferenceNeverOptsIntoDestructiveMode(){val context=RuntimeEnvironment.getApplication();context.getSharedPreferences("export-history-preferences",0).edit().putInt(ExportHistoryAction.SINGLE.key,2).putString(ExportHistoryAction.CLEAR.key,"unknown").commit();val p=ExportHistoryPreferences(context);assertEquals(ExportHistoryMode.ASK,p.get(ExportHistoryAction.SINGLE));assertEquals(ExportHistoryMode.ASK,p.get(ExportHistoryAction.CLEAR))}
 @Test fun recordOnlyFallbackRequiresDurableExactDecision(){val store=ExportJobStore(root());val j=job(ExportJobState.COMPLETED);store.update(j);val op=store.beginHistory(store.historySnapshot(setOf(j.id)),true);val snapshot=op.targets.single();val fallback=op.copy(status="record-only:${j.id}")
  assertFalse(store.removeHistoryRecord(fallback,snapshot));store.updateHistoryOperation(fallback);assertTrue(store.removeHistoryRecord(fallback,snapshot));assertNull(store.get(j.id))
 }

}
