package dev.filmroom

import android.app.Application
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState
import dev.filmroom.export.ExportJobStore
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Host store tests: a blocked job directory makes an attempted write observable. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ExportProgressPersistenceHostTest {
    @Test fun repeatedProgressDoesNotWriteButChangedProgressIsDurableAndRecoversPaused() {
        val root=createTempDir(prefix="progress-store-")
        try {
            val store=ExportJobStore(root)
            val progress=ExportDisplayProgress(3,10,100)
            val job=ExportJob(ExportJobStore.newId(),1,EditorState(),2,2,ExportFormat.PNG,95,
                state=ExportJobState.RUNNING,progress=progress)
            store.update(job)
            val directory=store.directory(job.id)
            val savedDirectory=File(root,"saved-job")
            assertTrue(directory.renameTo(savedDirectory))
            directory.writeText("block")

            // No new notification is returned and no filesystem write is attempted.
            assertNull(store.changeProgress(job.id,progress))
            assertEquals(job,store.get(job.id))
            try {
                store.changeProgress(job.id,ExportDisplayProgress(3,11,100))
                fail("Changed progress must attempt a durable write")
            } catch (_:Exception) { }
            assertEquals(job,store.get(job.id))

            assertTrue(directory.delete());assertTrue(savedDirectory.renameTo(directory))
            val advanced=ExportDisplayProgress(3,11,100)
            assertEquals(advanced,store.changeProgress(job.id,advanced)?.progress)
            val recovered=ExportJobStore(root).get(job.id)!!
            assertEquals(ExportJobState.PAUSED,recovered.state)
            assertEquals(advanced,recovered.progress)
        } finally { root.deleteRecursively() }
    }

    @Test fun checkpointAndStatusUpdatesStillWriteWhenProgressIsUnchanged() {
        val root=createTempDir(prefix="checkpoint-store-")
        try {
            val store=ExportJobStore(root)
            val job=ExportJob(ExportJobStore.newId(),1,EditorState(),2,2,ExportFormat.PNG,95,
                state=ExportJobState.RUNNING,progress=ExportDisplayProgress(6,1,1))
            store.update(job)
            val uri="content://media/external/images/media/42"
            store.change(job.id){it.copy(mediaUri=uri,outputName="result.png")}
            store.change(job.id){it.copy(state=ExportJobState.PAUSED)}
            val recovered=ExportJobStore(root).get(job.id)!!
            assertEquals(ExportJobState.PAUSED,recovered.state)
            assertEquals(job.progress,recovered.progress)
            assertEquals(uri,recovered.mediaUri)
            assertEquals("result.png",recovered.outputName)
        } finally { root.deleteRecursively() }
    }
}
