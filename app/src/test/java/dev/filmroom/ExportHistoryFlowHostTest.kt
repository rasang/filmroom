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
 private fun language(){dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(),"zh")}
 @Test fun batchPartialSuccessKeepsMissingUnsafeAndActiveRecords(){language();val p=provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val q=ExportQueue(c.get());val success=job(42);val missing=job(43);val unsafe=job(44);val active=job(45).copy(state=ExportJobState.PENDING);listOf(success,missing,unsafe,active).forEach(q.store::update);row(p,success);row(p,unsafe,"other.app")
  ExportHistoryPreferences(c.get()).set(ExportHistoryAction.CLEAR,ExportHistoryMode.RECORDS_AND_PHOTOS);val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.CLEAR,q.historySnapshot(setOf(success.id,missing.id,unsafe.id,active.id)));waitFor(flow)
  assertNull(q.store.get(success.id));assertNotNull(q.store.get(missing.id));assertNotNull(q.store.get(unsafe.id));assertNotNull(q.store.get(active.id));assertEquals(listOf(Uri.parse(success.mediaUri)),p.deleted);assertTrue(p.rows.containsKey(Uri.parse(unsafe.mediaUri)));assertEquals("未找到导出照片，可能已移动或删除。",ShadowToast.getTextOfLatestToast());flow.close(false)
 }}
 @Test fun recordsOnlyNeverCallsPhotoDeletionAndReleasesReservation(){language();val p=provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->val q=ExportQueue(c.get());val j=job(42);q.store.update(j);row(p,j);ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_ONLY);val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));waitFor(flow);assertNull(q.store.get(j.id));assertTrue(p.rows.containsKey(Uri.parse(j.mediaUri)));assertTrue(p.deleted.isEmpty());assertFalse(q.store.historyReserved(j.id));flow.close(false)}}
 @Test fun permissionFailureIsNotMissingAndKeepsRecord(){language();val p=provider().apply{queryError=SecurityException("revoked")};Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->val q=ExportQueue(c.get());val j=job(42);q.store.update(j);row(p,j);ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_AND_PHOTOS);val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));waitFor(flow);assertNotNull(q.store.get(j.id));assertTrue(p.deleted.isEmpty());assertNotEquals("未找到导出照片，可能已移动或删除。",ShadowToast.getTextOfLatestToast());flow.close(false)}}
 @Test fun missingUriNeverDeletesHistoryWhenPhotosWereRequested(){language();provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->val q=ExportQueue(c.get());val j=job(42).copy(mediaUri=null,state=ExportJobState.FAILED);q.store.update(j);ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_AND_PHOTOS);val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));waitFor(flow);assertNotNull(q.store.get(j.id));assertEquals("未找到导出照片，可能已移动或删除。",ShadowToast.getTextOfLatestToast());flow.close(false)}}
 @Test fun cancellationDuringReservationAcquisitionDoesNotOrphanLockOrDeleteRecord(){language();provider();Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
  val q=ExportQueue(c.get());val j=job(42);q.store.update(j);val mutex=ExportQueue::class.java.getDeclaredField("storageLock").apply{isAccessible=true}.get(q) as kotlinx.coroutines.sync.Mutex;kotlinx.coroutines.runBlocking{mutex.lock()}
  ExportHistoryPreferences(c.get()).set(ExportHistoryAction.SINGLE,ExportHistoryMode.RECORDS_ONLY);val flow=ExportHistoryRemovalController(c.get()){};flow.attach(q);flow.request(ExportHistoryAction.SINGLE,q.historySnapshot(setOf(j.id)));shadowOf(android.os.Looper.getMainLooper()).idle();flow.close(false);mutex.unlock();waitFor(flow)
  assertNotNull(q.store.get(j.id));assertFalse(q.store.historyReserved(j.id))
 }}

}
