package dev.filmroom
import android.app.Application
import android.content.ContentValues
import android.net.Uri
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.*
import dev.filmroom.ui.ExportHistoryRemovalController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowToast
import java.util.UUID
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ExportHistoryFlowHostTest {
 private val stamp=1730000000000L
 private fun job(id:Int)=ExportJob(UUID.randomUUID().toString(),id.toLong(),EditorState(),20,20,ExportFormat.PNG,95,ExportJobState.COMPLETED,mediaUri="content://media/external_primary/images/media/$id",exportMillis=stamp,outputName="export_${stamp}_abcdef.png")
 private fun provider():ExportMediaDeletionCoordinatorHostTest.Provider {val app=RuntimeEnvironment.getApplication();return ExportMediaDeletionCoordinatorHostTest.Provider().apply{attachInfo(app,android.content.pm.ProviderInfo().apply{authority="media"});ShadowContentResolver.registerProviderInternal("media",this)}}
 private fun row(p:ExportMediaDeletionCoordinatorHostTest.Provider,j:ExportJob,owner:String=RuntimeEnvironment.getApplication().packageName){p.rows[Uri.parse(j.mediaUri)]=ContentValues().apply{put("_id",Uri.parse(j.mediaUri).lastPathSegment!!.toLong());put("owner_package_name",owner);put("relative_path","Pictures/暗房/");put("_display_name",j.outputName)}}
 private fun waitFor(flow:ExportHistoryRemovalController){repeat(500){shadowOf(android.os.Looper.getMainLooper()).idle();if(!flow.busy)return;Thread.sleep(5)};fail("History operation did not settle")}
 private fun confirm(){org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()}
 private fun language(){dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(),"zh")}
 @Test fun batchRemovesMissingUnsafeRecordsButPreservesTheirAlbumAndActiveTasks(){language();val p=provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val q=ExportQueue(c.get());val success=job(42);val missing=job(43);val unsafe=job(44);val active=job(45).copy(state=ExportJobState.PENDING);listOf(success,missing,unsafe,active).forEach(q.store::update);row(p,success);row(p,unsafe,"other.app")
  ExportHistoryPreferences(c.get()).set(ExportHistoryAction.CLEAR,ExportHistoryMode.RECORDS_AND_PHOTOS);val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.CLEAR,q.historySnapshot(setOf(success.id,missing.id,unsafe.id,active.id)));confirm();waitFor(flow)
  assertNull(q.store.get(success.id));assertNull(q.store.get(missing.id));assertNull(q.store.get(unsafe.id));assertNotNull(q.store.get(active.id));assertEquals(listOf(Uri.parse(success.mediaUri)),p.deleted);assertTrue(p.rows.containsKey(Uri.parse(unsafe.mediaUri)));assertEquals("已移除 3 条记录；未能确认删除的相册文件已保留。",ShadowToast.getTextOfLatestToast());flow.close(false)
 }}
 @Test fun recordsOnlyNeverCallsPhotoDeletionAndReleasesReservation(){language();val p=provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->val q=ExportQueue(c.get());val j=job(42);q.store.update(j);row(p,j);ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_ONLY);val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));confirm();waitFor(flow);assertNull(q.store.get(j.id));assertTrue(p.rows.containsKey(Uri.parse(j.mediaUri)));assertTrue(p.deleted.isEmpty());assertFalse(q.store.historyReserved(j.id));flow.close(false)}}
 @Test fun permissionFailureRemovesOnlyRecordAndDoesNotTouchAlbum(){language();val p=provider().apply{queryError=SecurityException("revoked")};Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->val q=ExportQueue(c.get());val j=job(42);q.store.update(j);row(p,j);ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_AND_PHOTOS);val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));confirm();waitFor(flow);assertNull(q.store.get(j.id));assertTrue(p.rows.containsKey(Uri.parse(j.mediaUri)));assertTrue(p.deleted.isEmpty());assertNotEquals("未找到导出照片，可能已移动或删除。",ShadowToast.getTextOfLatestToast());flow.close(false)}}
 @Test fun missingUriCanRemoveHistoryWhenPhotosWereRequested(){language();provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->val q=ExportQueue(c.get());val j=job(42).copy(mediaUri=null,state=ExportJobState.FAILED);q.store.update(j);ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_AND_PHOTOS);val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));confirm();waitFor(flow);assertNull(q.store.get(j.id));assertEquals("已移除 1 条记录；未能确认删除的相册文件已保留。",ShadowToast.getTextOfLatestToast());flow.close(false)}}
 @Test fun cancellationDuringReservationAcquisitionDoesNotOrphanLockOrDeleteRecord(){language();provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val q=ExportQueue(c.get());val j=job(42);q.store.update(j);val mutex=ExportQueue::class.java.getDeclaredField("storageLock").apply{isAccessible=true}.get(q) as kotlinx.coroutines.sync.Mutex;kotlinx.coroutines.runBlocking{mutex.lock()}
  ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_ONLY);val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));confirm();shadowOf(android.os.Looper.getMainLooper()).idle();flow.close(false);mutex.unlock();waitFor(flow)
  assertNotNull(q.store.get(j.id));assertFalse(q.store.historyReserved(j.id))
 }}

 @Test fun everySavedModeRequiresConfirmationForSingleAndClearAndCancelKeepsEverything(){language();val p=provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val q=ExportQueue(c.get());val j=job(142);q.store.update(j);row(p,j)
  for(action in ExportHistoryAction.entries)for(mode in ExportHistoryMode.entries){
   ExportHistoryPreferences(c.get()).set(action,mode)
   val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q)
   flow.request(action,q.historySnapshot(setOf(j.id)))
   shadowOf(android.os.Looper.getMainLooper()).idle()
   val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
   assertTrue(dialog.isShowing);assertFalse(flow.busy)
   assertNotNull(q.store.get(j.id));assertTrue(p.deleted.isEmpty());assertFalse(q.store.historyReserved(j.id))
   dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
   shadowOf(android.os.Looper.getMainLooper()).idle()
   assertNotNull(q.store.get(j.id));assertTrue(p.deleted.isEmpty());flow.close(false)
  }
 }}
 @Test fun dismissAndActivityCloseDoNotApproveRememberedPhotoRemoval(){language();val p=provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val q=ExportQueue(c.get());val j=job(143);q.store.update(j);row(p,j)
  ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_AND_PHOTOS)
  val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q)
  flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)))
  org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().cancel()
  assertNotNull(q.store.get(j.id));assertTrue(p.deleted.isEmpty())
  flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));flow.close(false)
  shadowOf(android.os.Looper.getMainLooper()).idle();assertNotNull(q.store.get(j.id));assertTrue(p.deleted.isEmpty())
 }}

 @Test fun missingOrdinaryPhotoRemovesPrivateCacheAndRecord(){language();val p=provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val q=ExportQueue(c.get());val j=job(222);q.store.update(j)
  val dir=q.store.directory(j.id);java.io.File(dir,"private-preview.bin").writeText("cached")
  ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_AND_PHOTOS)
  val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));confirm();waitFor(flow)
  assertNull(q.store.get(j.id));assertFalse(dir.exists());assertTrue(p.deleted.isEmpty());assertEquals("已移除 1 条记录。",ShadowToast.getTextOfLatestToast());flow.close(false)
 }}
 private fun liveJob(id:Int):ExportJob {val base=job(id);val stem="Filmroom_0123456789abcdef0123456789abcdef_MP";return base.copy(liveEffect="LIGHT_TABLE",outputName="$stem.jpeg",livePublishedUris=listOf(base.mediaUri!!,"content://media/external_primary/video/media/${id+1}"),livePublishedNames=listOf("$stem.jpeg","$stem.mp4"))}
 @Test fun missingLiveImageStillDeletesVerifiedCompanionAndRecord(){language();val p=provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val q=ExportQueue(c.get());val j=liveJob(232);q.store.update(j)
  val video=Uri.parse(j.livePublishedUris[1]);p.rows[video]=ContentValues().apply{put("_id",233);put("owner_package_name",c.get().packageName);put("relative_path","DCIM/Filmroom/");put("_display_name",j.livePublishedNames[1])}
  val dir=q.store.directory(j.id);java.io.File(dir,"clean-result.bin").writeText("private clean result")
  ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_AND_PHOTOS)
  val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));confirm();waitFor(flow)
  assertNull(q.store.get(j.id));assertFalse(dir.exists());assertEquals(listOf(video),p.deleted);assertFalse(p.rows.containsKey(video));flow.close(false)
 }}
 @Test fun inaccessibleLivePairRemovesOnlyRecordAndPrivateCache(){language();val p=provider().apply{queryError=SecurityException("no access")};Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val q=ExportQueue(c.get());val j=liveJob(242);q.store.update(j);row(p,j)
  val dir=q.store.directory(j.id);java.io.File(dir,"clean-result.bin").writeText("private clean result")
  ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_AND_PHOTOS)
  val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));confirm();waitFor(flow)
  assertNull(q.store.get(j.id));assertFalse(dir.exists());assertTrue(p.deleted.isEmpty());assertTrue(p.rows.containsKey(Uri.parse(j.mediaUri)));assertEquals("已移除 1 条记录；未能确认删除的相册文件已保留。",ShadowToast.getTextOfLatestToast());flow.close(false)
 }}
 @Test fun actualDeleteFailureKeepsRecordForExplicitRetry(){language();val p=provider().apply{deleteError=IllegalStateException("provider failure")};Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val q=ExportQueue(c.get());val j=job(252);q.store.update(j);row(p,j)
  ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_AND_PHOTOS)
  val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));confirm();waitFor(flow)
  assertNotNull(q.store.get(j.id));assertTrue(p.rows.containsKey(Uri.parse(j.mediaUri)));flow.close(false)
 }}

}
