package dev.filmroom
import android.app.Application
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File
import java.util.UUID

private fun restartRoot():File {
 val path=System.getenv("HISTORY_RESTART_ROOT")
 org.junit.Assume.assumeTrue("Opt-in two-JVM history test requires HISTORY_RESTART_ROOT",!path.isNullOrBlank())
 return File(checkNotNull(path))
}
/** These classes are run in separate JVMs, sharing only the test directory. No provider/media mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ExportHistoryRestartWriteHostTest {
 @Test fun persistCompletedRemovalAndPendingConsent(){val root=restartRoot();check(root.listFiles().orEmpty().isEmpty());val store=ExportJobStore(File(root,"jobs"))
  fun job()=ExportJob(UUID.randomUUID().toString(),1,EditorState(),20,20,ExportFormat.PNG,95,ExportJobState.COMPLETED,mediaUri="content://media/external_primary/images/media/42",exportMillis=1730000000000,outputName="export_1730000000000_abcdef.png")
  val removed=job();val pending=job();store.update(removed);store.update(pending)
  val op=store.beginHistory(store.historySnapshot(setOf(removed.id)),false);assertTrue(store.removeHistoryRecord(op,op.targets.single()));store.releaseHistory(op)
  val waiting=store.beginHistory(store.historySnapshot(setOf(pending.id)),true).copy(status="awaiting-consent",consentUri=pending.mediaUri,consentKind="DELETE_REQUEST");store.updateHistoryOperation(waiting)
  File(root,"expectations.json").writeText(JSONObject().put("removed",removed.id).put("pending",pending.id).put("operation",waiting.id).toString())
 }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ExportHistoryRestartReadHostTest {
 @Test fun restartDoesNotResurrectRecordOrReplayPhotoDeletion(){val root=restartRoot();val expected=JSONObject(File(root,"expectations.json").readText());val store=ExportJobStore(File(root,"jobs"));assertNull(store.get(expected.getString("removed")));val pending=store.get(expected.getString("pending"));assertNotNull(pending);assertEquals("content://media/external_primary/images/media/42",pending!!.mediaUri);assertFalse(store.historyReserved(pending.id))
  val operation=store.readHistoryOperation(expected.getString("operation"))!!;assertEquals("awaiting-consent",operation.status);assertTrue(store.restoreHistoryReservation(operation));assertTrue(store.historyReserved(pending.id));store.releaseHistory(operation);assertNotNull(store.get(pending.id))
 }
}
