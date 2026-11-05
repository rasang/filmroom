package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState
import dev.filmroom.export.ExportQueue
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import dev.filmroom.ui.ExportContourCache
import dev.filmroom.ui.ExportPreviewView
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Bounded fault injection at the decode boundary; no native export, network or timing-based sleeps in production. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportPreviewLoadingHostTest {
    @org.junit.Before fun adequateContourMemory() {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.ActivityManager::class.java)).setMemoryInfo(
            android.app.ActivityManager.MemoryInfo().apply {availMem=512L*1024*1024;threshold=32L*1024*1024;lowMemory=false})
    }
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    private fun render(a:ExportQueueActivity,jobs:List<ExportJob>) {ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(a,jobs)}
    private fun layout(a:ExportQueueActivity,height:Int=480) {
        a.findViewById<ViewGroup>(android.R.id.content).let { root ->
            root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            root.layout(0,0,360,height)
        }
    }
    private fun image(a:ExportQueueActivity,id:String)=views(a.window.decorView).single{it.tag=="job-thumbnail:$id"} as ExportPreviewView
    private fun current(v:ExportPreviewView)=ExportPreviewView::class.java.getDeclaredField("current").apply{isAccessible=true}.get(v) as? Bitmap
    private fun value(v:ExportPreviewView,name:String)=ExportPreviewView::class.java.getDeclaredField(name).apply{isAccessible=true}.get(v)
    private fun queue(a:ExportQueueActivity)=ExportQueueActivity::class.java.getDeclaredField("queue").apply{isAccessible=true}.get(a) as? ExportQueue
    private fun job(stage:Int=2,created:Long=100)=ExportJob(UUID.randomUUID().toString(),created,EditorState(),320,200,ExportFormat.JPEG,95,ExportJobState.RUNNING,ExportDisplayProgress(stage,1,10))
    private fun solid(color:Int)=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
    private fun TestScope.until(label:String,diagnostic:()->String={""},condition:()->Boolean) {
        repeat(500){
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            runCurrent()
            if(condition())return
            Thread.sleep(10)
        }
        error("Timed out (5 seconds): $label ${runCatching{diagnostic()}.getOrElse{it.toString()}}")
    }
    private fun fixture(q:ExportQueue,j:ExportJob):File=q.store.directory(j.id).apply {
        mkdirs();File(this,"original-preview.jpg").writeText("decode hook source")
        File(this,"negative-preview.png").writeText("decode hook negative")
    }

    @Test fun repeatedLayoutAndVisibilityUpdatesDoNotRewriteTextOrRequestAnotherLayout()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(stage=1);dirs+=fixture(queue(a)!!,running)
                a.thumbnailDecoder={solid(Color.BLACK)}
                render(a,listOf(running));layout(a);until("contour appears"){current(image(a,running.id))!=null}
                val cards=ExportQueueActivity::class.java.getDeclaredField("cards").apply{isAccessible=true}.get(a) as Map<*,*>
                val card=cards[running.id]!!
                val progress=card.javaClass.getDeclaredField("progress").apply{isAccessible=true}.get(card) as android.widget.TextView
                val update=card.javaClass.getDeclaredMethod("updateThumbnailVisibility").apply{isAccessible=true}
                layout(a);runCurrent();val text=progress.text
                var writes=0
                progress.addTextChangedListener(object:android.text.TextWatcher {
                    override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                    override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){writes++}
                    override fun afterTextChanged(s:android.text.Editable?) {}
                })
                repeat(20){update.invoke(card);layout(a);runCurrent()}
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(0,writes)
                assertSame(text,progress.text)
                assertFalse(progress.isLayoutRequested)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun genuineThreeImageSequenceAndRailFollowWhileCompletionIsImmediate()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir
                File(dir,"final-preview.jpg").writeText("final")
                a.thumbnailDecoder={file->solid(when{file.name.startsWith("negative-preview")->Color.GREEN;file.name=="final-preview.jpg"->Color.BLUE;else->Color.BLACK})}
                render(a,listOf(running));layout(a);val view=image(a,running.id)
                until("contour appears"){current(view)!=null}
                val done=running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1))
                render(a,listOf(done));layout(a)
                assertTrue(views(a.window.decorView).filterIsInstance<android.widget.TextView>().any{it.text.toString()==ExportJobPresentation.from(done).progress})
                val rail=views(a.window.decorView).filterIsInstance<dev.filmroom.ui.ExportStageRail>().single()
                assertEquals(View.VISIBLE,rail.visibility)
                advanceTimeBy(2499);runCurrent();assertNotEquals(Color.GREEN,current(view)!!.getPixel(10,10))
                advanceTimeBy(1);runCurrent();until("genuine negative after contour dwell"){current(view)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(1949);runCurrent();assertEquals(Color.GREEN,current(view)!!.getPixel(10,10))
                advanceTimeBy(1);runCurrent();until("positive after negative dwell"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertEquals(View.VISIBLE,rail.visibility)
                advanceTimeBy(1949);runCurrent();assertEquals(View.VISIBLE,rail.visibility)
                advanceTimeBy(1);runCurrent();assertEquals(View.GONE,rail.visibility)
                assertEquals(3,view.transitionCount)
                controller.pause();controller.resume();layout(a);runCurrent()
                assertEquals(3,view.transitionCount)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun coalescedUnviewedCompletionStartsSequenceAndReentryDoesNotReplay()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            val done=job(stage=1).copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),completionViewed=false)
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val dir=fixture(queue(a)!!,done);dirs+=dir;File(dir,"final-preview.jpg").writeText("final")
                a.thumbnailDecoder={file->solid(when(file.name){"negative-preview.png"->Color.GREEN;"final-preview.jpg"->Color.BLUE;else->Color.BLACK})}
                // No RUNNING state is ever delivered to this card.
                render(a,listOf(done));layout(a);val view=image(a,done.id)
                until("coalesced completion still begins with genuine contour"){current(view)!=null}
                assertNotEquals(Color.BLUE,current(view)!!.getPixel(10,10))
                assertTrue(views(a.window.decorView).filterIsInstance<android.widget.TextView>().any{it.text.toString()==ExportJobPresentation.from(done).progress})
                advanceTimeBy(2500);runCurrent();until("coalesced completion negative"){current(view)?.getPixel(10,10)==Color.GREEN}
                // Notification acknowledgment is allowed immediately, independently of visuals.
                val acknowledged=done.copy(completionViewed=true)
                queue(a)!!.store.update(acknowledged);render(a,listOf(acknowledged));layout(a);runCurrent()
                assertTrue(dev.filmroom.export.ExportPresentationStore.pending(a,done.id,"legacy"))
                controller.pause();controller.stop();advanceTimeBy(10000);runCurrent()
                controller.start();controller.resume();layout(a)
                // Lifecycle resubscription must see the same durable job, and the posted
                // onResume viewport update must run before advancing the visible clock.
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();runCurrent()
                assertSame(view,image(a,done.id));assertTrue(view.isAttachedToWindow)
                assertEquals(Color.GREEN,current(view)!!.getPixel(10,10));assertEquals(2,view.transitionCount)
                advanceTimeBy(1950);runCurrent();until("coalesced completion positive"){current(view)?.getPixel(10,10)==Color.BLUE}
                advanceTimeBy(1950);runCurrent()
                render(a,listOf(done.copy(completionViewed=true)));layout(a);runCurrent()
                assertEquals(3,view.transitionCount)
            }
            // Install the decoder before lifecycle collection sees the persisted fixture.
            // A genuinely viewed card in a fresh Activity goes directly to final.
            Robolectric.buildActivity(ExportQueueActivity::class.java).also { controller ->
                controller.get().thumbnailDecoder={file->solid(if(file.name=="final-preview.jpg")Color.BLUE else Color.GREEN)}
                controller.get().previewClock={testScheduler.currentTime}
            }.setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("fresh queue initialized"){queue(a)!=null};runCurrent()
                a.thumbnailDecoder={file->solid(if(file.name=="final-preview.jpg")Color.BLUE else Color.GREEN)}
                render(a,listOf(done.copy(completionViewed=true)));layout(a);val view=image(a,done.id)
                until("viewed completion opens as history"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertEquals(1,view.transitionCount)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun persistedPresentationResumesViewedCompletionButRejectsDifferentAttempt()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                a.thumbnailDecoder={file->solid(if(file.name=="final-preview.jpg")Color.BLUE else Color.BLACK)}
                val interrupted=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),completionViewed=true)
                val dir=fixture(queue(a)!!,interrupted);dirs+=dir;File(dir,"final-preview.jpg").writeText("final")
                dev.filmroom.export.ExportPresentationStore.remember(a,interrupted.id,"legacy",true)
                render(a,listOf(interrupted));layout(a);val view=image(a,interrupted.id)
                until("independent pending marker resumes presentation"){current(view)!=null}
                assertNotEquals(Color.BLUE,current(view)!!.getPixel(10,10))
                val historical=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),completionViewed=true)
                val historyDir=fixture(queue(a)!!,historical);dirs+=historyDir;File(historyDir,"final-preview.jpg").writeText("final")
                val oldAttempt=dev.filmroom.export.ScanPreviewStore.begin(historyDir)
                dev.filmroom.export.ExportPresentationStore.remember(a,historical.id,oldAttempt,true)
                dev.filmroom.export.ScanPreviewStore.begin(historyDir)
                render(a,listOf(historical));layout(a);val historyView=image(a,historical.id)
                until("stale attempt cannot replay viewed history"){current(historyView)?.getPixel(10,10)==Color.BLUE}
                assertEquals(1,historyView.transitionCount)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun lateMatchingNegativeIsPolledAfterCompletionWithoutUsingOlderGeneration()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir
                val token=dev.filmroom.export.ScanPreviewStore.begin(dir)
                File(dir,"negative-preview-$token.status").writeText("pending")
                File(dir,"negative-preview-12345678-1234-1234-1234-123456789abc.png").writeText("stale")
                File(dir,"final-preview.jpg").writeText("final")
                a.thumbnailDecoder={file->solid(when{file.name=="negative-preview-$token.png"->Color.GREEN;file.name=="final-preview.jpg"->Color.BLUE;else->Color.BLACK})}
                render(a,listOf(running));layout(a);val view=image(a,running.id)
                until("contour appears"){current(view)!=null}
                render(a,listOf(running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1))));layout(a)
                advanceTimeBy(3000);runCurrent();assertNotEquals(Color.BLUE,current(view)!!.getPixel(10,10))
                File(dir,"negative-preview-$token.png").writeText("real negative")
                File(dir,"negative-preview-$token.status").writeText("ready")
                advanceTimeBy(300);runCurrent();until("late genuine negative"){current(view)?.getPixel(10,10)==Color.GREEN}
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun missingCorruptNegativeAndCompletedHistoryNeverFabricateOrReplay()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                for(corrupt in listOf(false,true)) {
                    val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir
                    if(!corrupt)File(dir,"negative-preview.png").delete()
                    File(dir,"final-preview.jpg").writeText("final")
                    a.thumbnailDecoder={file->if(file.name=="negative-preview.png")null else solid(if(file.name=="final-preview.jpg")Color.BLUE else Color.BLACK)}
                    render(a,listOf(running));layout(a);val view=image(a,running.id)
                    until("contour appears"){current(view)!=null}
                    render(a,listOf(running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1))));layout(a)
                    advanceTimeBy(2500);runCurrent();until("available final follows missing/corrupt negative"){current(view)?.getPixel(10,10)==Color.BLUE}
                    assertEquals(2,view.transitionCount)
                    advanceTimeBy(1950);runCurrent()
                }
                val history=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),completionViewed=true)
                val dir=fixture(queue(a)!!,history);dirs+=dir;File(dir,"final-preview.jpg").writeText("final")
                render(a,listOf(history));layout(a);val view=image(a,history.id)
                until("history shows final directly"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertEquals(1,view.transitionCount)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun backgroundPausesRemainingDwellAndResumeDoesNotReplayContour()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir
                File(dir,"final-preview.jpg").writeText("final")
                a.thumbnailDecoder={file->solid(when(file.name){"negative-preview.png"->Color.GREEN;"final-preview.jpg"->Color.BLUE;else->Color.BLACK})}
                render(a,listOf(running));layout(a);val view=image(a,running.id);until("contour"){current(view)!=null}
                val done=running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1))
                queue(a)!!.store.update(done);render(a,listOf(done));layout(a);runCurrent()
                advanceTimeBy(2500);runCurrent();until("negative"){current(view)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(500);runCurrent();controller.pause();controller.stop()
                advanceTimeBy(10000);runCurrent();assertEquals(Color.GREEN,current(view)!!.getPixel(10,10))
                controller.start();controller.resume();layout(a)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();runCurrent()
                assertSame(view,image(a,done.id));assertTrue(view.isAttachedToWindow)
                advanceTimeBy(1449);runCurrent();assertEquals(Color.GREEN,current(view)!!.getPixel(10,10))
                advanceTimeBy(1);runCurrent();until("positive after remaining visible time"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertEquals(3,view.transitionCount)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun repeatedGpuUpdatesAndIndependentCardsDoNotRestartNegativeDwell()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val first=job(stage=1,created=100);val second=job(stage=1,created=200)
                listOf(first,second).forEach{dirs+=fixture(queue(a)!!,it)}
                a.thumbnailDecoder={file->solid(when(file.name){"negative-preview.png"->Color.GREEN;"final-preview.jpg"->Color.BLUE;else->Color.BLACK})}
                render(a,listOf(first,second));layout(a,1600)
                val one=image(a,first.id);val two=image(a,second.id)
                until("both contours"){current(one)!=null && current(two)!=null}
                advanceTimeBy(2500);runCurrent()
                fun tile(i:Long)=first.copy(progress=ExportDisplayProgress(6,i,20,
                    dev.filmroom.engine.GpuWorkProgress(tileIndex=i,tileTotal=20),dev.filmroom.engine.ExportVisualProgress(5,20)))
                render(a,listOf(tile(1),second));layout(a,1600);until("first negative"){current(one)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(300);runCurrent();render(a,listOf(tile(2),second));layout(a,1600)
                assertFalse(views(a.window.decorView).filterIsInstance<android.widget.TextView>().any{it.text.toString().contains("2/20")})
                dirs.forEach{File(it,"final-preview.jpg").writeText("final")}
                val doneOne=first.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1))
                val doneTwo=second.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1))
                render(a,listOf(doneOne,doneTwo));layout(a,1600)
                until("second begins independent negative"){current(two)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(1650);runCurrent();until("first positive, second still negative"){current(one)?.getPixel(10,10)==Color.BLUE && current(two)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(300);runCurrent();until("second positive"){current(two)?.getPixel(10,10)==Color.BLUE}
                assertEquals(3,one.transitionCount);assertEquals(3,two.transitionCount)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun failureCancellationStopQueueAndReducedMotionStillHoldsEachRealImage()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        val scale=android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                a.thumbnailDecoder={file->solid(when(file.name){"negative-preview.png"->Color.GREEN;"final-preview.jpg"->Color.BLUE;else->Color.BLACK})}
                for(state in listOf(ExportJobState.FAILED,ExportJobState.CANCELLED)) {
                    val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir;File(dir,"final-preview.jpg").writeText("final")
                    render(a,listOf(running));layout(a);val view=image(a,running.id);until("contour"){current(view)!=null}
                    render(a,listOf(running.copy(state=state)));layout(a)
                    val count=view.transitionCount;advanceTimeBy(10000);runCurrent();assertEquals(count,view.transitionCount)
                }
                scale.invoke(null,0f)
                val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir;File(dir,"final-preview.jpg").writeText("final")
                render(a,listOf(running));layout(a);val view=image(a,running.id);until("reduced motion contour"){current(view)!=null}
                render(a,listOf(running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1))));layout(a)
                advanceTimeBy(1500);runCurrent();until("negative without fade"){current(view)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(1499);runCurrent();assertEquals(Color.GREEN,current(view)!!.getPixel(10,10))
                advanceTimeBy(1);runCurrent();until("positive without fade"){current(view)?.getPixel(10,10)==Color.BLUE}
            }
        } finally {scale.invoke(null,1f);runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun ignoredCancellationFromSlowDecodeCannotOverwriteNewStageOrReplacementJob()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gates=listOf(CompletableDeferred<Unit>(),CompletableDeferred<Unit>())
        val starts=AtomicInteger();val finishes=AtomicInteger();val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false;until("queue initialized"){queue(a)!=null};runCurrent()
                val q=queue(a)!!;val old=job();dirs+=fixture(q,old)
                a.thumbnailDecoder={file ->
                    if(file.name=="original-preview.jpg") {
                        val index=starts.getAndIncrement()
                        // Deliberately ignore cancellation like a non-cooperative platform codec.
                        withContext(NonCancellable){gates[index].await()}
                        finishes.incrementAndGet();solid(Color.RED)
                    } else solid(Color.GREEN)
                }
                render(a,listOf(old));layout(a);until("first decoder started"){starts.get()==1}
                val oldView=image(a,old.id)
                render(a,listOf(old.copy(progress=ExportDisplayProgress(5,1,10))));layout(a)
                until("negative wins while source remains blocked"){current(oldView)?.getPixel(10,10)==Color.GREEN}
                val negative=current(oldView)
                gates[0].complete(Unit);until("late cancelled source returned"){finishes.get()==1};runCurrent()
                assertSame("Late original cannot replace mature negative",negative,current(oldView))

                render(a,listOf(old));layout(a);until("second source decoder started"){starts.get()==2}
                val replacement=job(5);dirs+=fixture(q,replacement)
                render(a,listOf(replacement));layout(a)
                val replacementView=image(a,replacement.id)
                until("replacement decoded"){current(replacementView)?.getPixel(10,10)==Color.GREEN}
                val replacementBitmap=current(replacementView)
                gates[1].complete(Unit);until("removed job decoder returned"){finishes.get()==2};runCurrent()
                assertNotSame(oldView,replacementView)
                assertSame("Removed task's late decode cannot rebind replacement",replacementBitmap,current(replacementView))
                assertSame("Removed card also refuses its cancelled decode",negative,current(oldView))
                assertFalse(oldView.animating)
                controller.pause();assertFalse(replacementView.animating)
            }
        } finally {gates.forEach{it.complete(Unit)};runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun retryDuringScanDecodeRejectsOldAttemptAndRecyclesUnacceptedPhoto()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate=CompletableDeferred<Unit>();val started=AtomicInteger();val decoded=java.util.concurrent.atomic.AtomicReference<Bitmap>();var dir:File?=null
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false;until("queue initialized"){queue(a)!=null};runCurrent()
                val j=job(6);dir=fixture(queue(a)!!,j)
                val first=dev.filmroom.export.ScanPreviewStore.begin(dir!!)
                File(dir,"scan-preview-$first.jpg").writeText("hook")
                a.thumbnailDecoder={file->
                    if(file.name.startsWith("scan-preview-")) {
                        started.incrementAndGet();withContext(NonCancellable){gate.await()}
                        solid(Color.RED).also{decoded.set(it)}
                    } else solid(Color.GREEN)
                }
                render(a,listOf(j));layout(a);until("scan decoder blocked"){started.get()==1}
                val view=image(a,j.id)
                // No new render/bind: generation and stage key deliberately remain unchanged.
                dev.filmroom.export.ScanPreviewStore.begin(dir!!)
                gate.complete(Unit)
                until("old attempt bitmap recycled"){decoded.get()?.isRecycled==true}
                assertNull("Stale attempt never enters visible frame",current(view))
                controller.pause()
            }
        } finally {gate.complete(Unit);runCurrent();dir?.deleteRecursively();Dispatchers.resetMain()}
    }

    @Test fun scrollingOffscreenClearsBitmapThenReturnsCachedContourWithoutBlackReplay()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dirs=mutableListOf<File>();val decodes=AtomicInteger()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false;until("queue initialized"){queue(a)!=null};runCurrent()
                val q=queue(a)!!;val target=job();dirs+=fixture(q,target)
                // Force the original/contour path; these fixtures are only fake decoder inputs.
                File(dirs.single(),"negative-preview.png").delete()
                a.thumbnailDecoder={decodes.incrementAndGet();solid(Color.BLACK).also{b->Canvas(b).drawRect(70f,20f,250f,170f,Paint().apply{color=Color.WHITE})}}
                ExportContourCache.clear();val before=ExportContourCache.computations
                val fillers=(0..9).map{job(created=it.toLong()).copy(state=ExportJobState.PENDING)}
                render(a,listOf(target)+fillers);layout(a)
                val view=image(a,target.id)
                until("initial contour",diagnostic={
                    val scroll=views(a.window.decorView).filterIsInstance<ScrollView>().single()
                    val card=ExportQueueActivity::class.java.getDeclaredField("cards").apply{isAccessible=true}.get(a).let{(it as Map<*,*>)[target.id]}
                    fun field(name:String)=card?.javaClass?.getDeclaredField(name)?.apply{isAccessible=true}?.get(card)
                    val container=field("container") as? View
                    val memory=dev.filmroom.engine.NativeWorkReservations.snapshot(a)
                    val runtime=Runtime.getRuntime()
                    "decodes=${decodes.get()} computations=${ExportContourCache.computations} cachedBytes=${ExportContourCache.cachedBytes} lifecycle=${a.lifecycle.currentState} scroll=${scroll.scrollY}/${scroll.height} card=${container?.top}..${container?.bottom} key=${field("previewKey")} generation=${field("previewGeneration")} version=${field("previewVersion")} task=${field("previewTask")} memory=$memory workerIdle=${dev.filmroom.engine.NativeWorkReservations.detailRetryIdle()} heapHeadroom=${runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory()}"
                }){current(view)!=null}
                val contour=current(view)
                assertEquals(before+1,ExportContourCache.computations)
                val scroll=views(a.window.decorView).filterIsInstance<ScrollView>().single()
                scroll.scrollTo(0,10000);runCurrent()
                assertTrue(scroll.scrollY>500);assertNull(current(view));assertFalse(view.animating)
                scroll.scrollTo(0,0);layout(a)
                until("contour restored"){current(view)!=null}
                assertSame(view,image(a,target.id));assertSame(contour,current(view))
                assertEquals("Contour processing reused memory cache",before+1,ExportContourCache.computations)
                assertEquals("Cached contour returns without decoding JPEG again",1,decodes.get())
                assertEquals("Returning tile never replays black intro",1f,value(view,"blend"))
                assertNull(value(view,"previous"))
                controller.pause();assertFalse(view.animating)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }
    @Test fun deletingUnrelatedJobDoesNotStrandVisibleQueuedContour()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val lock=ExportContourCache::class.java.getDeclaredField("computeLock").apply{isAccessible=true}.get(ExportContourCache)!!
        val held=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val decoderThread=java.util.concurrent.atomic.AtomicReference<Thread>()
        val holder=Thread {synchronized(lock){held.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}}
        val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false;until("queue initialized"){queue(a)!=null};runCurrent()
                val q=queue(a)!!;val target=job();val unrelated=job(created=1).copy(state=ExportJobState.PENDING)
                dirs+=fixture(q,target);File(dirs.single(),"negative-preview.png").delete()
                a.thumbnailDecoder={decoderThread.set(Thread.currentThread());solid(Color.BLACK)}
                ExportContourCache.clear();holder.start();assertTrue(held.await(2,java.util.concurrent.TimeUnit.SECONDS))
                render(a,listOf(target,unrelated));layout(a)
                until("contour waits for compute lock"){decoderThread.get()?.state==Thread.State.BLOCKED}
                val view=image(a,target.id)
                render(a,listOf(target)) // No scroll/layout after deletion: the visible card must not need a gesture.
                release.countDown();holder.join(2000)
                val cards=ExportQueueActivity::class.java.getDeclaredField("cards").apply{isAccessible=true}.get(a) as Map<*,*>
                val card=cards[target.id]!!
                until("visible contour task terminal") {
                    (card.javaClass.getDeclaredField("previewTask").apply{isAccessible=true}.get(card) as? Job)?.isCompleted==true
                }
                assertNotNull("Deleting another job must not permanently leave the visible card blank",current(view))
                assertSame(view,image(a,target.id));assertEquals(1,ExportContourCache.cachedBytes/(320*200*4))
            }
        } finally {release.countDown();if(holder.isAlive)holder.join(2000);runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

}
