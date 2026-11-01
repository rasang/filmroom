package dev.filmroom.export

import android.app.Application
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import dev.filmroom.ui.ExportQueueSummary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ExportCompletionReadHostTest {
    private fun job(state:ExportJobState)=ExportJob(ExportJobStore.newId(),1,EditorState(),2,2,ExportFormat.PNG,95,state)
    private fun temp(block:(ExportJobStore,File)->Unit){val dir=Files.createTempDirectory("completion-read").toFile();try{block(ExportJobStore(dir),dir)}finally{dir.deleteRecursively()}}
    @Test fun onlyCompletedJobsAreUnreadAndCountIsIndependent()=temp {store,_->
        ExportJobState.entries.filter{it!=ExportJobState.COMPLETED}.forEach{store.update(job(it))}
        assertFalse(ExportQueueSummary.from(store.jobs.value).hasUnreadCompleted)
        val pending=ExportQueueSummary.from(store.jobs.value).pending
        val done=job(ExportJobState.COMPLETED);store.update(done)
        assertTrue(ExportQueueSummary.from(store.jobs.value).hasUnreadCompleted)
        assertEquals(pending,ExportQueueSummary.from(store.jobs.value).pending)
        store.markCompletionsViewed(store.jobs.value)
        assertFalse(ExportQueueSummary.from(store.jobs.value).hasUnreadCompleted)
        assertTrue(store.jobs.value.filter{it.state!=ExportJobState.COMPLETED}.none{it.completionViewed})
    }
    @Test fun readStateSurvivesRestartWithoutChangingManifestVersion()=temp {store,dir->
        val done=job(ExportJobState.COMPLETED);store.update(done)
        assertFalse(ExportJobStore(dir).get(done.id)!!.completionViewed)
        store.markCompletionsViewed(listOf(done))
        assertTrue(ExportJobStore(dir).get(done.id)!!.completionViewed)
        val json=org.json.JSONObject(File(store.directory(done.id),"manifest.json").readText())
        assertEquals(1,json.getInt("version"))
    }
    @Test fun legacyCompletedManifestDefaultsToUnread()=temp {store,dir->
        val done=job(ExportJobState.COMPLETED);store.update(done)
        val file=File(store.directory(done.id),"manifest.json")
        val json=org.json.JSONObject(file.readText());json.remove("completionViewed");file.writeText(json.toString())
        assertTrue(ExportQueueSummary.from(ExportJobStore(dir).jobs.value).hasUnreadCompleted)
    }
    @Test fun visibleSnapshotDoesNotAcknowledgeOffscreenOrLaterCompletions()=temp {store,_->
        val visible=job(ExportJobState.COMPLETED);val offscreen=job(ExportJobState.COMPLETED);val running=job(ExportJobState.RUNNING)
        listOf(visible,offscreen,running).forEach(store::update)
        store.change(running.id){it.copy(state=ExportJobState.COMPLETED)}
        store.markCompletionsViewed(listOf(visible,running)) // stale RUNNING frame cannot read the new result
        assertTrue(store.get(visible.id)!!.completionViewed)
        assertFalse(store.get(offscreen.id)!!.completionViewed)
        assertFalse(store.get(running.id)!!.completionViewed)
        store.markCompletionsViewed(store.jobs.value)
        val later=job(ExportJobState.COMPLETED);store.update(later)
        assertTrue(ExportQueueSummary.from(store.jobs.value).hasUnreadCompleted)
    }
    @Test fun staleWorkerCopyCannotUndoAcknowledgment()=temp {store,dir->
        val done=job(ExportJobState.COMPLETED);store.update(done)
        store.markCompletionsViewed(listOf(done));store.update(done.copy(message="已保存"))
        assertTrue(store.get(done.id)!!.completionViewed)
        assertTrue(ExportJobStore(dir).get(done.id)!!.completionViewed)
    }
    @Test fun noAcknowledgmentBeforeCompletedPublication()=temp {store,_->
        val running=job(ExportJobState.RUNNING);store.update(running)
        store.markCompletionsViewed(listOf(running.copy(state=ExportJobState.COMPLETED)))
        assertFalse(store.get(running.id)!!.completionViewed)
        store.change(running.id){it.copy(state=ExportJobState.COMPLETED)}
        assertTrue(ExportQueueSummary.from(store.jobs.value).hasUnreadCompleted)
    }
    @Test fun failedReadPersistenceKeepsNotification()=temp {store,_->
        val done=job(ExportJobState.COMPLETED);store.update(done)
        val manifest=File(store.directory(done.id),"manifest.json");manifest.delete();manifest.mkdir();File(manifest,"block").writeText("block")
        assertThrows(Exception::class.java){store.markCompletionsViewed(listOf(done))}
        assertFalse(store.get(done.id)!!.completionViewed)
        assertTrue(ExportQueueSummary.from(store.jobs.value).hasUnreadCompleted)
    }
    @Test fun emptyAndUnknownAcknowledgmentsAreNoOps()=temp {store,_->
        store.markCompletionsViewed(emptyList());store.markCompletionsViewed(listOf(job(ExportJobState.COMPLETED)))
        assertTrue(store.jobs.value.isEmpty());assertFalse(ExportQueueSummary.from(store.jobs.value).hasUnreadCompleted)
    }
    @Test fun oneUnwritableManifestDoesNotStarveOtherViewedCards()=temp {store,_->
        val broken=job(ExportJobState.COMPLETED);val healthy=job(ExportJobState.COMPLETED)
        store.update(broken);store.update(healthy)
        val manifest=File(store.directory(broken.id),"manifest.json");manifest.delete();manifest.mkdir();File(manifest,"block").writeText("block")
        assertThrows(Exception::class.java){store.markCompletionsViewed(listOf(broken,healthy))}
        assertFalse(store.get(broken.id)!!.completionViewed)
        assertTrue(store.get(healthy.id)!!.completionViewed)
    }

}
