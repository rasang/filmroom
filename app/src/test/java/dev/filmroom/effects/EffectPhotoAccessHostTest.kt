package dev.filmroom.effects

import android.app.Application
import android.net.Uri
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class EffectPhotoAccessHostTest {
 private val context get()=RuntimeEnvironment.getApplication()
 private fun store()=ExportJobStore(File(context.cacheDir,"effect-link-${UUID.randomUUID()}"))
 private fun row(original:String?="content://docs/original")=ExportJob(UUID.randomUUID().toString(),1,EditorState(),20,30,ExportFormat.PNG,95,
    state=ExportJobState.COMPLETED,mediaUri="content://docs/exported",outputName="out.png",originalUri=original)
 private fun remember(s:ExportJobStore,j:ExportJob,original:Boolean=true,expected:String?=if(original)j.originalUri else j.mediaUri,grant:Boolean=true,name:String?="out.png")=
    EffectPhotoAccess.remember(context,s,j.id,original,expected,Uri.parse("content://docs/reselected"),retain={grant},outputName={name})
 @Test fun sourceRelinkSurvivesFreshStoreAndNeverTouchesOutput(){
  val s=store();val j=row();s.update(j)
  assertEquals(EffectPhotoAccess.Status.SAVED,remember(s,j))
  val fresh=ExportJobStore(s.root).get(j.id)!!
  assertEquals("content://docs/reselected",fresh.originalUri)
  assertEquals(j.copy(originalUri=fresh.originalUri),fresh)
  assertEquals(j.mediaUri,fresh.mediaUri);assertEquals(j.outputName,fresh.outputName)
 }
 @Test fun legacyNullSourceAndRestoredArchiveBothSurviveColdReload(){
  for(archived in listOf(false,true)){
   val s=store();val j=row(null)
   if(archived)s.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(j)))else s.update(j)
   val before=s.get(j.id)!!
   assertEquals(EffectPhotoAccess.Status.SAVED,remember(s,before))
   val fresh=ExportJobStore(s.root).get(j.id)!!
   assertEquals(before.copy(originalUri="content://docs/reselected"),fresh)
   assertEquals(archived,fresh.archivedHistory)
   val backed=ExportHistoryBackup.validateRows(s.exportBackupHistory()).single()
   assertEquals(fresh.originalUri,backed.originalUri);assertEquals(j.mediaUri,backed.mediaUri)
  }
 }
 @Test fun deniedGrantAndStaleScreenCannotOverwriteRecord(){
  val s=store();val j=row();s.update(j)
  assertEquals(EffectPhotoAccess.Status.TEMPORARY_PERMISSION,remember(s,j,grant=false));assertEquals(j,s.get(j.id))
  assertEquals(EffectPhotoAccess.Status.RECORD_CHANGED,remember(s,j,expected="content://docs/stale"));assertEquals(j,s.get(j.id))
  assertEquals(j,ExportJobStore(s.root).get(j.id))
 }
 @Test fun resultRelinkNeverTouchesOriginalAndRequiresMatchingIdentity(){
  for(archived in listOf(false,true)){
   val s=store();val j=row();if(archived)s.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(j)))else s.update(j)
   val before=s.get(j.id)!!
   assertEquals(EffectPhotoAccess.Status.INVALID_RESULT,remember(s,before,original=false,name=null))
   assertEquals(before,s.get(j.id))
   assertEquals(EffectPhotoAccess.Status.SAVED,remember(s,before,original=false))
   val fresh=ExportJobStore(s.root).get(j.id)!!
   assertEquals(before.copy(mediaUri="content://docs/reselected"),fresh)
  }
 }
 @Test fun committedLinkWinsStaleRecreationStateButTemporaryChoicesSurviveRotation(){
  val s=store();val old=row();s.update(old)
  // Simulate IO commit completing while the old Activity is destroyed before its callback runs.
  val savedSource=old.originalUri;val savedResult=old.mediaUri
  assertEquals(EffectPhotoAccess.Status.SAVED,remember(s,old))
  val record=ExportJobStore(s.root).get(old.id)
  assertEquals(EffectPhotoAccess.Links("content://docs/reselected",savedResult),
      EffectPhotoAccess.restoredLinks(record,savedSource,savedResult,false,false))
  // Explicitly session-only selections are not discarded on ordinary rotation.
  assertEquals(EffectPhotoAccess.Links("content://docs/session",savedResult),
      EffectPhotoAccess.restoredLinks(record,"content://docs/session",savedResult,true,false))
  assertEquals(EffectPhotoAccess.Links("content://docs/reselected","content://docs/session-result"),
      EffectPhotoAccess.restoredLinks(record,savedSource,"content://docs/session-result",false,true))
 }
 @Test fun repeatedPickerDeliveryAfterCommitIsIdempotentDespiteStaleExpectedUri(){
  for(original in listOf(false,true))for(archived in listOf(false,true)) {
   val s=store();val j=row();if(archived)s.mergeBackupHistory(ExportHistoryBackup.exportRows(listOf(j)))else s.update(j)
   val before=s.get(j.id)!!
   assertEquals(EffectPhotoAccess.Status.SAVED,remember(s,before,original=original))
   val committed=s.get(j.id)
   assertEquals(EffectPhotoAccess.Status.SAVED,remember(s,before,original=original))
   assertEquals(committed,s.get(j.id));assertEquals(committed,ExportJobStore(s.root).get(j.id))
   assertEquals(if(original)before.mediaUri else before.originalUri,if(original)committed!!.mediaUri else committed!!.originalUri)
  }
 }
 @Test fun failedAtomicWriteDoesNotPublishOrClaimSavedLink(){
  val s=store();val j=row();s.update(j)
  val blocker=File(s.directory(j.id),"manifest.json.new");assertTrue(blocker.mkdir())
  assertEquals(EffectPhotoAccess.Status.SAVE_FAILED,remember(s,j))
  assertEquals(j,s.get(j.id));assertEquals(j,ExportJobStore(s.root).get(j.id))
 }
 @Test fun deletedOrReservedRecordIsNotResurrected(){
  val s=store();val j=row()
  assertEquals(EffectPhotoAccess.Status.RECORD_UNAVAILABLE,remember(s,j))
  s.update(j)
  val op=s.beginHistory(s.historySnapshot(setOf(j.id)),false)
  assertEquals(EffectPhotoAccess.Status.RECORD_CHANGED,remember(s,j))
  assertEquals(j,s.get(j.id));s.releaseHistory(op)
 }
}
