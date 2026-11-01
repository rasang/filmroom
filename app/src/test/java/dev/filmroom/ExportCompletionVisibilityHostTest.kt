package dev.filmroom

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w360dp-h640dp-mdpi")
class ExportCompletionVisibilityHostTest {
    private fun render(a:ExportQueueActivity,jobs:List<ExportJob>){ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(a,jobs)}
    private fun job(state:ExportJobState)=ExportJob(ExportJobStore.newId(),System.nanoTime(),EditorState(),2,2,ExportFormat.PNG,95,state)
    private fun layout(a:ExportQueueActivity){val root=a.findViewById<ViewGroup>(android.R.id.content);root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY));root.layout(0,0,360,640)}
    private fun queue(a:ExportQueueActivity):ExportQueue {
        val field=ExportQueueActivity::class.java.getDeclaredField("queue").apply{isAccessible=true}
        await{field.get(a)!=null};return field.get(a) as ExportQueue
    }
    private fun await(predicate:()->Boolean){repeat(300){shadowOf(android.os.Looper.getMainLooper()).idle();if(predicate())return;Thread.sleep(10)};assertTrue("Timed out awaiting read state",predicate())}
    private fun draw(a:ExportQueueActivity){a.findViewById<ScrollView>(R.id.export_queue_scroll).viewTreeObserver.dispatchOnDraw();shadowOf(android.os.Looper.getMainLooper()).idle()}
    @Test fun foregroundDrawReadsOnlyVisibleCompletedStatusAndScrollReadsOthers(){
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            c.windowFocusChanged(true);val a=c.get();val q=queue(a)
            val jobs=(0..9).map{job(ExportJobState.COMPLETED)};jobs.forEach(q.store::update)
            render(a,jobs);layout(a)
            assertTrue(q.jobs.value.none{it.completionViewed}) // layout/publication alone is not a view
            draw(a);await{q.store.get(jobs.last().id)!!.completionViewed}
            assertFalse(q.store.get(jobs.first().id)!!.completionViewed)
            val scroll=a.findViewById<ScrollView>(R.id.export_queue_scroll);scroll.scrollTo(0,100000);layout(a);draw(a)
            await{q.store.get(jobs.first().id)!!.completionViewed}
            assertTrue(q.jobs.value.any{!it.completionViewed}) // middle history was never viewed
        }
    }
    @Test fun backgroundAndUnfocusedDrawCannotAcknowledgeButResumingCan(){
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            val a=c.get();val q=queue(a);val done=job(ExportJobState.COMPLETED);q.store.update(done);render(a,listOf(done));layout(a)
            c.windowFocusChanged(false);draw(a);assertFalse(q.store.get(done.id)!!.completionViewed)
            c.windowFocusChanged(true);c.pause();draw(a);assertFalse(q.store.get(done.id)!!.completionViewed)
            c.resume();draw(a);await{q.store.get(done.id)!!.completionViewed}
        }
    }
    @Test fun runningCardDoesNotReadFutureCompletionUntilCompletedFrame(){
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            c.windowFocusChanged(true);val a=c.get();val q=queue(a);val running=job(ExportJobState.RUNNING)
            q.store.update(running);render(a,listOf(running));layout(a);draw(a)
            q.store.change(running.id){it.copy(state=ExportJobState.COMPLETED)}
            assertFalse(q.store.get(running.id)!!.completionViewed)
            render(a,q.jobs.value);layout(a);draw(a);await{q.store.get(running.id)!!.completionViewed}
        }
    }
    @Test fun transientReadWriteFailureRetriesWithoutNewCompletionOrScroll(){
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use{c->
            c.windowFocusChanged(true);val a=c.get();val q=queue(a);val done=job(ExportJobState.COMPLETED)
            q.store.update(done);render(a,listOf(done));layout(a)
            val manifest=java.io.File(q.store.directory(done.id),"manifest.json");val bytes=manifest.readBytes()
            manifest.delete();manifest.mkdir();java.io.File(manifest,"block").writeText("block")
            draw(a)
            val delayField=ExportQueueActivity::class.java.getDeclaredField("readRetryDelay").apply{isAccessible=true}
            await{delayField.getLong(a)>2_000L}
            assertFalse(q.store.get(done.id)!!.completionViewed)
            manifest.deleteRecursively();manifest.writeBytes(bytes)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            draw(a);await{q.store.get(done.id)!!.completionViewed}
        }
    }

}
