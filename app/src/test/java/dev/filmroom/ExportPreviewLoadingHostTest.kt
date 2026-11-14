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
        // The production queue is process-wide. Each host test has a new Application;
        // do not let durable fixtures from another test become offscreen history here.
        ExportQueue::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.ActivityManager::class.java)).setMemoryInfo(
            android.app.ActivityManager.MemoryInfo().apply {availMem=512L*1024*1024;threshold=32L*1024*1024;lowMemory=false})
    }
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    private fun render(a:ExportQueueActivity,jobs:List<ExportJob>) {ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(a,jobs)}
    private fun layout(a:ExportQueueActivity,height:Int=480,width:Int=360) {
        a.findViewById<ViewGroup>(android.R.id.content).let { root ->
            root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            root.layout(0,0,width,height)
        }
    }
    private fun image(a:ExportQueueActivity,id:String)=views(a.window.decorView).single{it.tag=="job-thumbnail:$id"} as ExportPreviewView
    private fun card(a:ExportQueueActivity,id:String)=
        (ExportQueueActivity::class.java.getDeclaredField("cards").apply{isAccessible=true}.get(a) as Map<*,*>)[id]!!
    private fun field(a:ExportQueueActivity,id:String,name:String):Any?=card(a,id).let{it.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(it)}
    private fun progress(a:ExportQueueActivity,id:String)=(field(a,id,"progress") as android.widget.TextView).text.toString()
    private fun visibleCompletion(a:ExportQueueActivity,id:String)=card(a,id).let{it.javaClass.getDeclaredMethod("visibleCompletion").apply{isAccessible=true}.invoke(it)}
    private fun assertPresenting(a:ExportQueueActivity,job:ExportJob) {
        assertNotEquals("Saved waits for the last image dwell",ExportJobPresentation.from(job).progress,progress(a,job.id))
        assertEquals("No duplicate saved message during presentation",View.GONE,(field(a,job.id,"message") as View).visibility)
        assertNull("A saved status not yet shown cannot be marked viewed",visibleCompletion(a,job.id))
        assertEquals("View photo follows the displayed status, not early durable completion",View.GONE,(field(a,job.id,"open") as View).visibility)
        assertFalse("Hidden result action is not clickable",(field(a,job.id,"open") as View).isEnabled)
    }
    private fun assertSaved(a:ExportQueueActivity,job:ExportJob) {
        assertEquals(ExportJobPresentation.from(job).progress,progress(a,job.id))
        assertEquals(1,(field(a,job.id,"progress") as android.widget.TextView).minLines)
        val open=field(a,job.id,"open") as View
        assertEquals("Result action appears in the same presentation update as Saved",if(job.mediaUri!=null)View.VISIBLE else View.GONE,open.visibility)
        assertEquals(job.mediaUri!=null,open.isEnabled)
    }
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

    @Test fun realSharperNegativeReplacesDraftWithoutRepeatingPhaseAndCorruptUpgradeKeepsDraft()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(stage=5);val dir=fixture(queue(a)!!,running);dirs+=dir
                val id=dev.filmroom.export.ScanPreviewStore.begin(dir)
                val early=File(dir,"negative-preview-early-$id.png").apply{writeText("early")}
                File(dir,"negative-preview-early-$id.status").writeText("ready")
                val exact=File(dir,"negative-preview-$id.png")
                var validExact=false
                a.thumbnailDecoder={f->when(f.name){early.name->Bitmap.createBitmap(256,160,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GREEN)}
                    exact.name->if(validExact)Bitmap.createBitmap(384,240,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}else null
                    else->solid(Color.BLACK)}}
                render(a,listOf(running));layout(a)
                until("contour visible"){current(image(a,running.id))!=null}
                advanceTimeBy(500);runCurrent();until("early negative visible"){current(image(a,running.id))?.width==256}
                val sequence=field(a,running.id,"sequence") as dev.filmroom.ui.ExportPreviewSequence
                exact.writeText("exact pending valid decode");File(dir,"negative-preview-$id.status").writeText("ready")
                advanceTimeBy(500);runCurrent()
                until("failed upgrade resolved"){(field(a,running.id,"previewTask") as? Job)?.isCompleted==true}
                assertEquals(256,current(image(a,running.id))!!.width)
                assertEquals(early.name,sequence.negativeName)
                validExact=true;advanceTimeBy(1200);runCurrent()
                until("sharper negative replaces early"){current(image(a,running.id))?.width==384}
                assertEquals(exact.name,sequence.negativeName);assertEquals(2,sequence.phase)
                assertEquals("Quality update cannot prolong phase",0L,sequence.remaining(testScheduler.currentTime))
                exact.delete();advanceTimeBy(1200);runCurrent()
                until("missing upgrade resolved"){(field(a,running.id,"previewTask") as? Job)?.isCompleted==true}
                assertEquals("Never downgrade an accepted exact image",384,current(image(a,running.id))!!.width)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun oldCompletedThumbnailLoadsSavedDerivativeWithoutRewritingThumbnail()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false
                until("queue initialized"){queue(a)!=null};runCurrent()
                val done=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://saved/photo")
                val dir=fixture(queue(a)!!,done);dirs+=dir
                val old=File(dir,"final-preview.jpg").apply{writeText("old240")};var reloads=0
                a.thumbnailDecoder={Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GREEN)}}
                a.savedThumbnailDecoder={uri->assertEquals(done.mediaUri,uri);reloads++;Bitmap.createBitmap(1024,683,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}}
                render(a,listOf(done));layout(a);until("saved derivative visible"){current(image(a,done.id))?.width==1024}
                assertEquals("old240",old.readText());assertEquals(1,reloads)
                repeat(3){render(a,listOf(done));layout(a);runCurrent()}
                assertEquals(1,reloads);assertSaved(a,done)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun savedPhotoPermissionFailureKeepsExistingThumbnail()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false
                until("queue initialized"){queue(a)!=null};runCurrent()
                val done=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://saved/no-longer-readable")
                val dir=fixture(queue(a)!!,done);dirs+=dir;File(dir,"final-preview.jpg").writeText("old240")
                a.thumbnailDecoder={Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GREEN)}}
                a.savedThumbnailDecoder={throw SecurityException("fixture permission revoked")}
                render(a,listOf(done));layout(a);until("old valid pixels preserved"){current(image(a,done.id))?.width==240}
                assertEquals(Color.GREEN,current(image(a,done.id))!!.getPixel(5,5));assertSaved(a,done)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun fullSizeFinalAndScanFilesAreNotDownsampledBackBelowTheirNewCaps() {
        val d=java.nio.file.Files.createTempDirectory("preview-decode-caps").toFile()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).get().let{a->
                val decode=ExportQueueActivity::class.java.getDeclaredMethod("decodeThumbnail",File::class.java).apply{isAccessible=true}
                listOf("final-preview.jpg" to 1024,"scan-preview-${UUID.randomUUID()}.jpg" to 768,"negative-preview-${UUID.randomUUID()}.png" to 384).forEach { (name,edge)->
                    val file=File(d,name);val source=Bitmap.createBitmap(edge,edge/2,Bitmap.Config.ARGB_8888)
                    try{file.outputStream().use{source.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{source.recycle()}
                    val decoded=decode.invoke(a,file) as Bitmap
                    try{assertEquals(name,edge,decoded.width);assertEquals(edge/2,decoded.height)}finally{decoded.recycle()}
                }
            }
        }finally{d.deleteRecursively()}
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

    @Test fun genuineThreeImageSequenceAndRailDelaySavedTextButNotDurableCompletion()=runTest {
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
                val done=running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),
                    mediaUri="content://media/external/images/media/42",message="Saved fixture")
                queue(a)!!.store.update(done);render(a,listOf(done));layout(a)
                assertPresenting(a,done)
                assertEquals(ExportJobState.COMPLETED,queue(a)!!.store.get(done.id)!!.state)
                assertEquals(done.mediaUri,queue(a)!!.store.get(done.id)!!.mediaUri)
                (field(a,done.id,"open") as View).performClick()
                assertNull("Even a stale click cannot open before presentation finishes",org.robolectric.Shadows.shadowOf(a).nextStartedActivity)
                val rail=views(a.window.decorView).filterIsInstance<dev.filmroom.ui.ExportStageRail>().single()
                assertEquals(View.VISIBLE,rail.visibility)
                assertEquals("Do not shrink at real completion",176,(view.parent as View).height)
                advanceTimeBy(499);runCurrent();assertNotEquals(Color.GREEN,current(view)!!.getPixel(10,10))
                advanceTimeBy(1);runCurrent();until("genuine negative after contour dwell"){current(view)?.getPixel(10,10)==Color.GREEN}
                layout(a);assertEquals("Keep negative full-size",176,(view.parent as View).height);assertPresenting(a,done)
                advanceTimeBy(499);runCurrent();assertEquals(Color.GREEN,current(view)!!.getPixel(10,10))
                advanceTimeBy(1);runCurrent();until("positive after negative dwell"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertEquals(View.VISIBLE,rail.visibility)
                layout(a);assertEquals("Keep positive full-size during its dwell",176,(view.parent as View).height);assertPresenting(a,done)
                advanceTimeBy(499);runCurrent();assertEquals(View.VISIBLE,rail.visibility);assertPresenting(a,done)
                advanceTimeBy(1);runCurrent();assertEquals(View.GONE,rail.visibility);assertSaved(a,done)
                (field(a,done.id,"open") as View).performClick()
                // Opening now uses the lifecycle coroutine also shared by restored URI checks.
                runCurrent()
                val opened=org.robolectric.Shadows.shadowOf(a).nextStartedActivity
                assertEquals(android.content.Intent.ACTION_VIEW,opened.action)
                assertEquals(done.mediaUri,opened.data.toString())
                assertEquals(View.VISIBLE,(field(a,done.id,"message") as View).visibility)
                assertFalse(queue(a)!!.store.get(done.id)!!.completionViewed)
                controller.windowFocusChanged(true)
                layout(a);a.findViewById<ScrollView>(R.id.export_queue_scroll).viewTreeObserver.dispatchOnDraw()
                until("Saved is acknowledged only after the completed frame"){queue(a)!!.store.get(done.id)!!.completionViewed}
                layout(a);a.window.decorView.viewTreeObserver.dispatchOnPreDraw()
                android.transition.TransitionManager.endTransitions((view.parent.parent.parent.parent as ViewGroup))
                layout(a);assertEquals("Shrink only after positive dwell",100,(view.parent as View).height)
                assertEquals(3,view.transitionCount)
                controller.pause();controller.resume();layout(a);runCurrent()
                assertEquals(3,view.transitionCount)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun viewPhotoRequiresDisplayedCompletionAndASavedUri()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false
                until("queue initialized"){queue(a)!=null};runCurrent()
                val done=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1))
                render(a,listOf(done));layout(a);assertSaved(a,done)
                val saved=done.copy(mediaUri="content://media/external/images/media/73")
                render(a,listOf(saved));layout(a);assertSaved(a,saved)
                for(state in listOf(ExportJobState.RUNNING,ExportJobState.PAUSED,ExportJobState.FAILED,ExportJobState.CANCELLED)) {
                    render(a,listOf(saved.copy(state=state)));layout(a)
                    val open=field(a,done.id,"open") as View
                    assertEquals(View.GONE,open.visibility);assertFalse(open.isEnabled)
                    open.performClick()
                    assertNull(org.robolectric.Shadows.shadowOf(a).nextStartedActivity)
                }
            }
        } finally {runCurrent();Dispatchers.resetMain()}
    }

    @Test fun slowSaveAfterAllImageDwellsShowsSavedImmediatelyWithoutReplaying()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir
                val attempt=dev.filmroom.export.ScanPreviewStore.begin(dir)
                File(dir,"negative-preview.png").writeText("negative")
                File(dir,"scan-preview-$attempt.jpg").writeText("positive")
                a.thumbnailDecoder={file->solid(when {
                    file.name=="negative-preview.png"->Color.GREEN
                    file.name.startsWith("scan-preview-") || file.name=="final-preview.jpg"->Color.BLUE
                    else->Color.BLACK
                })}
                render(a,listOf(running));layout(a);val view=image(a,running.id)
                until("contour"){current(view)!=null}
                advanceTimeBy(500);runCurrent()
                render(a,listOf(running.copy(progress=ExportDisplayProgress(5,1,10))));layout(a)
                until("negative during real work"){current(view)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(500);runCurrent()
                render(a,listOf(running.copy(progress=ExportDisplayProgress(6,1,10))));layout(a)
                until("positive during real work"){current(view)?.getPixel(10,10)==Color.BLUE}
                advanceTimeBy(500);runCurrent()
                val saving=running.copy(progress=ExportDisplayProgress(7))
                render(a,listOf(saving));layout(a)
                assertEquals(ExportJobPresentation.from(saving).progress,progress(a,running.id))
                val done=running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
                File(dir,"final-preview.jpg").writeText("final")
                render(a,listOf(done));layout(a);assertSaved(a,done)
                until("final version loaded"){view.transitionCount==4}
                assertSaved(a,done)
                advanceTimeBy(10000);runCurrent();assertSaved(a,done);assertEquals(4,view.transitionCount)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun rotationDuringPositiveDwellRetainsRemainingTimeAndDelaysSaved()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        val saved=android.os.Bundle()
        val done=job(stage=1).copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
        val decoder:suspend (File)->Bitmap?={file->solid(when(file.name){"negative-preview.png"->Color.GREEN;"final-preview.jpg"->Color.BLUE;else->Color.BLACK})}
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime};a.thumbnailDecoder=decoder
                until("queue initialized"){queue(a)!=null};runCurrent()
                val dir=fixture(queue(a)!!,done);dirs+=dir;File(dir,"final-preview.jpg").writeText("final")
                render(a,listOf(done.copy(state=ExportJobState.RUNNING,progress=ExportDisplayProgress(1))));layout(a)
                val view=image(a,done.id);until("contour"){current(view)!=null}
                queue(a)!!.store.update(done);render(a,listOf(done));layout(a)
                advanceTimeBy(500);runCurrent();until("negative",diagnostic={
                    val sequence=field(a,done.id,"sequence") as dev.filmroom.ui.ExportPreviewSequence
                    fun clockField(name:String)=sequence.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(sequence)
                    "time=${testScheduler.currentTime} phase=${sequence.phase} active=${sequence.active} visible=${clockField("visible")} deadline=${clockField("deadline")} hiddenThrough=${clockField("hiddenThrough")} pixel=${current(view)?.getPixel(10,10)} version=${field(a,done.id,"previewVersion")} key=${field(a,done.id,"previewKey")} task=${field(a,done.id,"previewTask")}"
                }){current(view)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(500);runCurrent();until("positive"){current(view)?.getPixel(10,10)==Color.BLUE}
                advanceTimeBy(200);runCurrent();assertPresenting(a,done)
                controller.saveInstanceState(saved);controller.pause();controller.stop()
            }
            advanceTimeBy(100);runCurrent()
            org.robolectric.RuntimeEnvironment.setQualifiers("w740dp-h360dp-land-mdpi")
            Robolectric.buildActivity(ExportQueueActivity::class.java).also { controller ->
                controller.get().previewClock={testScheduler.currentTime};controller.get().thumbnailDecoder=decoder
            }.create(saved).start().restoreInstanceState(saved).resume().visible().use { controller ->
                val a=controller.get();until("restored queue initialized"){queue(a)!=null};runCurrent()
                render(a,listOf(done));layout(a,360,740);val view=image(a,done.id)
                until("rotation restores the positive, not the contour"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertEquals(1,view.transitionCount);assertPresenting(a,done)
                assertEquals(180,(view.parent as View).height)
                advanceTimeBy(199);runCurrent();assertPresenting(a,done)
                advanceTimeBy(1);runCurrent();assertSaved(a,done)
                assertEquals(1,view.transitionCount)
            }
        } finally {
            org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h640dp-port-mdpi")
            runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()
        }
    }

    @Test fun enteringRunningStageFourAfterNegativeDwellShowsNegativeInsteadOfRejectingOriginal()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime};a.previewWallClock={10_000L+testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(stage=4)
                val dir=fixture(queue(a)!!,running);dirs+=dir
                dev.filmroom.export.ScanPreviewStore.begin(dir)
                File(dir,"scan-preview-attempt").setLastModified(1000)
                File(dir,"original-preview.jpg").setLastModified(1000)
                // Starting a fresh attempt intentionally removes legacy negative pixels.
                // Publish this attempt's fixture after begin, just like the real producer.
                File(dir,"negative-preview.png").apply { writeText("current attempt negative"); setLastModified(2000) }
                assertTrue(File(dir,"negative-preview.png").isFile)
                a.thumbnailDecoder={file->solid(if(file.name=="negative-preview.png")Color.GREEN else Color.RED)}
                render(a,listOf(running));layout(a)
                val view=image(a,running.id)
                until("mature negative loads even while durable progress is stage four"){current(view)?.getPixel(10,10)==Color.GREEN}
                val sequence=field(a,running.id,"sequence") as dev.filmroom.ui.ExportPreviewSequence
                assertEquals(2,sequence.phase);assertEquals(0L,sequence.remaining(testScheduler.currentTime))
                assertEquals(View.GONE,(field(a,running.id,"previewPlaceholder") as View).visibility)
                // A repeated load after scrolling/reentry must not select the older contour.
                controller.pause();controller.resume();layout(a);advanceTimeBy(1000);runCurrent()
                until("negative remains on resume"){current(view)?.getPixel(10,10)==Color.GREEN}
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun completedEntryRestoresOnlyTheRemainderOfRealArtifactTimes()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime};a.previewWallClock={10_000L+testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                a.thumbnailDecoder={file->solid(if(file.name=="final-preview.jpg")Color.BLUE else Color.GREEN)}
                fun completed(positiveAt:Long):ExportJob {
                    val done=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
                    val dir=fixture(queue(a)!!,done);dirs+=dir
                    dev.filmroom.export.ScanPreviewStore.begin(dir)
                    File(dir,"scan-preview-attempt").setLastModified(1000)
                    File(dir,"original-preview.jpg").setLastModified(1000)
                    File(dir,"negative-preview.png").apply{writeText("negative");setLastModified(2000)}
                    File(dir,"final-preview.jpg").apply{writeText("positive");setLastModified(positiveAt)}
                    return done
                }
                val old=completed(3000);val recentPositive=completed(9750)
                render(a,listOf(old,recentPositive));layout(a,1600)
                val oldView=image(a,old.id);val recentView=image(a,recentPositive.id)
                until("both completed entries show real positives directly"){current(oldView)?.getPixel(10,10)==Color.BLUE && current(recentView)?.getPixel(10,10)==Color.BLUE}
                assertSaved(a,old);assertPresenting(a,recentPositive)
                assertEquals(1,oldView.transitionCount);assertEquals(1,recentView.transitionCount)
                advanceTimeBy(249);runCurrent();assertPresenting(a,recentPositive)
                advanceTimeBy(1);runCurrent();assertSaved(a,recentPositive)
                controller.pause();controller.resume();layout(a,1600);runCurrent()
                assertSaved(a,old);assertSaved(a,recentPositive)
                assertEquals(1,oldView.transitionCount);assertEquals(1,recentView.transitionCount)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun heldOriginalDecodeCrossingDwellBoundaryCannotSkipAvailableNegativeAndPositive()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var clock=0L
        val originals=AtomicInteger()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={clock}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir
                File(dir,"final-preview.jpg").writeText("positive")
                a.thumbnailDecoder={file->
                    if(file.name=="original-preview.jpg" && originals.incrementAndGet()==2) {
                        entered.complete(Unit);withContext(NonCancellable){release.await()}
                    }
                    solid(when(file.name){"negative-preview.png"->Color.GREEN;"final-preview.jpg"->Color.BLUE;else->Color.BLACK})
                }
                render(a,listOf(running));layout(a);val view=image(a,running.id)
                until("first contour"){current(view)!=null}
                // Force a real second source decode while the source is still held.
                File(dir,"original-preview.jpg").appendText("new decode version")
                val done=running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
                render(a,listOf(done));layout(a);until("held source decode suspended"){entered.isCompleted}
                // Age the clock without running the timer: this same-generation IO
                // returns across the deadline with its earlier held selection.
                clock=500;release.complete(Unit)
                until("held source callback finished"){(field(a,done.id,"previewTask") as? Job)?.isCompleted==true}
                assertPresenting(a,done)
                val card=card(a,done.id)
                card.javaClass.getDeclaredMethod("updateThumbnailVisibility").apply{isAccessible=true}.invoke(card)
                until("available negative still receives its turn"){current(view)?.getPixel(10,10)==Color.GREEN}
                assertPresenting(a,done)
                assertEquals(2,(field(a,done.id,"sequence") as dev.filmroom.ui.ExportPreviewSequence).phase)
            }
        } finally {release.complete(Unit);runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun recentBackgroundCompletionResumesNegativeInsteadOfReplayingContour()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime};a.previewWallClock={1750L+testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val done=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
                val dir=fixture(queue(a)!!,done);dirs+=dir
                dev.filmroom.export.ScanPreviewStore.begin(dir)
                File(dir,"scan-preview-attempt").setLastModified(1000)
                File(dir,"original-preview.jpg").setLastModified(1000)
                File(dir,"negative-preview.png").apply{writeText("negative");setLastModified(1000)}
                File(dir,"final-preview.jpg").apply{writeText("positive");setLastModified(1000)}
                a.thumbnailDecoder={file->solid(if(file.name=="final-preview.jpg")Color.BLUE else Color.GREEN)}
                render(a,listOf(done));layout(a);val view=image(a,done.id)
                until("only the remaining negative is shown"){current(view)?.getPixel(10,10)==Color.GREEN}
                assertEquals(1,view.transitionCount);assertPresenting(a,done)
                advanceTimeBy(249);runCurrent();assertEquals(Color.GREEN,current(view)!!.getPixel(10,10))
                advanceTimeBy(1);runCurrent();until("positive follows remaining negative time"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertEquals(2,view.transitionCount);assertPresenting(a,done)
                advanceTimeBy(500);runCurrent();assertSaved(a,done)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun stalePresentationHintsNeverReplayViewedOrLegacyCompletedHistory()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                a.thumbnailDecoder={file->solid(if(file.name=="final-preview.jpg")Color.BLUE else Color.BLACK)}
                val interrupted=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),completionViewed=false)
                val dir=fixture(queue(a)!!,interrupted);dirs+=dir;File(dir,"final-preview.jpg").writeText("final")
                dev.filmroom.export.ExportPresentationStore.remember(a,interrupted.id,"legacy",true)
                render(a,listOf(interrupted));layout(a);val view=image(a,interrupted.id)
                until("stale pending marker cannot replay completed history"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertSaved(a,interrupted);assertEquals(1,view.transitionCount)
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
                render(a,listOf(running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")));layout(a)
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
                    render(a,listOf(running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")));layout(a)
                    advanceTimeBy(500);runCurrent();until("available final follows missing/corrupt negative"){current(view)?.getPixel(10,10)==Color.BLUE}
                    assertEquals(2,view.transitionCount)
                    advanceTimeBy(500);runCurrent()
                }
                val history=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),completionViewed=true)
                val dir=fixture(queue(a)!!,history);dirs+=dir;File(dir,"final-preview.jpg").writeText("final")
                render(a,listOf(history));layout(a);val view=image(a,history.id)
                until("history shows final directly"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertEquals(1,view.transitionCount)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun savedWithoutNegativeOrPositiveThumbnailNeverWaitsForever()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                for(corrupt in listOf(false,true)) {
                    val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir
                    if(!corrupt)File(dir,"negative-preview.png").delete()
                    val negativeDecodes=AtomicInteger()
                    a.thumbnailDecoder={file->
                        if(file.name=="negative-preview.png"){negativeDecodes.incrementAndGet();null}else solid(Color.BLACK)
                    }
                    render(a,listOf(running));layout(a);val view=image(a,running.id)
                    until("only the genuine contour exists"){current(view)!=null}
                    val done=running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
                    queue(a)!!.store.update(done);render(a,listOf(done));layout(a);assertPresenting(a,done)
                    advanceTimeBy(499);runCurrent();assertPresenting(a,done)
                    advanceTimeBy(1);runCurrent()
                    until("missing/corrupt negative and no positive cannot withhold Saved: corrupt=$corrupt"){progress(a,done.id)==ExportJobPresentation.from(done).progress}
                    assertSaved(a,done);assertEquals(1,view.transitionCount)
                    assertEquals(ExportJobState.COMPLETED,queue(a)!!.store.get(done.id)!!.state)
                    if(corrupt)assertTrue("Existing but undecodable negative was actually tried",negativeDecodes.get()>0)
                }
                val noPreviews=job().copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),createdAt=200)
                queue(a)!!.store.update(noPreviews)
                dev.filmroom.export.ScanPreviewStore.begin(queue(a)!!.store.directory(noPreviews.id))
                render(a,listOf(noPreviews));layout(a)
                until("no optional preview at all also reveals the real saved status"){progress(a,noPreviews.id)==ExportJobPresentation.from(noPreviews).progress}
                assertSaved(a,noPreviews);assertNull(current(image(a,noPreviews.id)))
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun backgroundCountsRemainingDwellAndResumeDoesNotReplayContour()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir
                File(dir,"final-preview.jpg").writeText("final")
                a.thumbnailDecoder={file->solid(when(file.name){"negative-preview.png"->Color.GREEN;"final-preview.jpg"->Color.BLUE;else->Color.BLACK})}
                render(a,listOf(running));layout(a);val view=image(a,running.id);until("contour"){current(view)!=null}
                val done=running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
                queue(a)!!.store.update(done);render(a,listOf(done));layout(a);runCurrent()
                advanceTimeBy(500);runCurrent();until("negative"){current(view)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(100);runCurrent();controller.pause();controller.stop()
                advanceTimeBy(150);runCurrent();assertEquals(Color.GREEN,current(view)!!.getPixel(10,10));assertPresenting(a,done)
                controller.start();controller.resume();layout(a)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();runCurrent()
                assertSame(view,image(a,done.id));assertTrue(view.isAttachedToWindow)
                advanceTimeBy(249);runCurrent();assertEquals(Color.GREEN,current(view)!!.getPixel(10,10))
                advanceTimeBy(1);runCurrent();until("positive after remaining visible time"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertEquals(3,view.transitionCount);assertPresenting(a,done)
                advanceTimeBy(500);runCurrent();assertSaved(a,done)
            }
        } finally {runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun completedWhileStoppedReturnsFinalWithoutReplayingExpiredStages()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewClock={testScheduler.currentTime}
                val wallStart=System.currentTimeMillis();a.previewWallClock={wallStart+testScheduler.currentTime}
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir
                a.thumbnailDecoder={file->solid(when(file.name){"negative-preview.png"->Color.GREEN;"final-preview.jpg"->Color.BLUE;else->Color.BLACK})}
                render(a,listOf(running));layout(a);val view=image(a,running.id)
                until("foreground contour"){current(view)!=null}
                advanceTimeBy(500);runCurrent();controller.pause();controller.stop()
                File(dir,"final-preview.jpg").writeText("positive")
                val done=running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
                queue(a)!!.store.update(done)
                advanceTimeBy(10000);runCurrent()
                controller.start();controller.resume();layout(a)
                until("return skips elapsed negative and positive dwell"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertSaved(a,done);assertEquals(2,view.transitionCount)
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
                advanceTimeBy(500);runCurrent()
                fun tile(i:Long)=first.copy(progress=ExportDisplayProgress(6,i,20,
                    dev.filmroom.engine.GpuWorkProgress(tileIndex=i,tileTotal=20),dev.filmroom.engine.ExportVisualProgress(5,20)))
                render(a,listOf(tile(1),second));layout(a,1600);until("first negative"){current(one)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(300);runCurrent();render(a,listOf(tile(2),second));layout(a,1600)
                assertFalse(views(a.window.decorView).filterIsInstance<android.widget.TextView>().any{it.text.toString().contains("2/20")})
                dirs.forEach{File(it,"final-preview.jpg").writeText("final")}
                val doneOne=first.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
                val doneTwo=second.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
                render(a,listOf(doneOne,doneTwo));layout(a,1600)
                until("second begins independent negative"){current(two)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(200);runCurrent();until("first positive, second still negative"){current(one)?.getPixel(10,10)==Color.BLUE && current(two)?.getPixel(10,10)==Color.GREEN}
                assertPresenting(a,doneOne);assertPresenting(a,doneTwo)
                advanceTimeBy(300);runCurrent();until("second positive"){current(two)?.getPixel(10,10)==Color.BLUE}
                advanceTimeBy(200);runCurrent();assertSaved(a,doneOne);assertPresenting(a,doneTwo)
                advanceTimeBy(300);runCurrent();assertSaved(a,doneOne);assertSaved(a,doneTwo)
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
                for(state in listOf(ExportJobState.FAILED,ExportJobState.CANCELLED,ExportJobState.PAUSED)) {
                    val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir;File(dir,"final-preview.jpg").writeText("final")
                    render(a,listOf(running));layout(a);val view=image(a,running.id);until("contour"){current(view)!=null}
                    val interrupted=running.copy(state=state,message="Interrupted fixture")
                    render(a,listOf(interrupted));layout(a)
                    assertEquals(ExportJobPresentation.from(interrupted).progress,progress(a,interrupted.id))
                    assertEquals(View.VISIBLE,(field(a,interrupted.id,"message") as View).visibility)
                    val count=view.transitionCount;advanceTimeBy(10000);runCurrent();assertEquals(count,view.transitionCount)
                    assertEquals(ExportJobPresentation.from(interrupted).progress,progress(a,interrupted.id))
                }
                scale.invoke(null,0f)
                val running=job(stage=1);val dir=fixture(queue(a)!!,running);dirs+=dir;File(dir,"final-preview.jpg").writeText("final")
                render(a,listOf(running));layout(a);val view=image(a,running.id);until("reduced motion contour"){current(view)!=null}
                val done=running.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,1,1),mediaUri="content://media/external/images/media/42")
                render(a,listOf(done));layout(a);assertPresenting(a,done)
                advanceTimeBy(500);runCurrent();until("negative without fade"){current(view)?.getPixel(10,10)==Color.GREEN}
                advanceTimeBy(499);runCurrent();assertEquals(Color.GREEN,current(view)!!.getPixel(10,10))
                advanceTimeBy(1);runCurrent();until("positive without fade"){current(view)?.getPixel(10,10)==Color.BLUE}
                assertPresenting(a,done)
                advanceTimeBy(499);runCurrent();assertPresenting(a,done)
                advanceTimeBy(1);runCurrent();assertSaved(a,done)
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

    @Test fun completedMissingOrCorruptFinalRecoversSavedPhotoInsteadOfNegative()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));val dirs=mutableListOf<File>()
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false
                until("queue initialized"){queue(a)!=null};runCurrent()
                val jobs=(0..2).map { index->
                    job(8).copy(state=ExportJobState.COMPLETED,mediaUri="content://saved/$index").also { j->
                        val dir=fixture(queue(a)!!,j);dirs+=dir
                        if(index==1)File(dir,"final-preview.jpg").writeText("corrupt")
                        if(index==2){val token=dev.filmroom.export.ScanPreviewStore.begin(dir);File(dir,"scan-preview-$token.jpg").writeText("corrupt scan")}
                    }
                }
                a.thumbnailDecoder={f->if(f.name.startsWith("scan-preview-"))throw java.io.IOException("bad scan") else if(f.name=="final-preview.jpg")null else solid(Color.GREEN)}
                a.savedThumbnailDecoder={Bitmap.createBitmap(1024,683,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}}
                render(a,jobs);layout(a,1600)
                until("both completed records use saved positive"){jobs.all{current(image(a,it.id))?.getPixel(10,10)==Color.BLUE}}
                assertTrue("Recovery does not race disk writer",!File(dirs[0],"final-preview.jpg").exists())
            }
        }finally{runCurrent();dirs.forEach{it.deleteRecursively()};Dispatchers.resetMain()}
    }

    @Test fun finalPublishedAfterCompletionRefreshesWithinSamePage()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var dir:File?=null
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false
                until("queue initialized"){queue(a)!=null};runCurrent()
                val j=job(8).copy(state=ExportJobState.COMPLETED)
                dir=fixture(queue(a)!!,j)
                a.thumbnailDecoder={f->solid(if(f.name=="final-preview.jpg")Color.BLUE else Color.BLACK)}
                render(a,listOf(j));layout(a)
                until("original fallback"){current(image(a,j.id))?.getPixel(10,10)==Color.BLACK}
                File(dir,"final-preview.jpg").writeText("late final")
                render(a,listOf(j))
                until("late final adopted without reopening"){current(image(a,j.id))?.getPixel(10,10)==Color.BLUE}
            }
        }finally{runCurrent();dir?.deleteRecursively();Dispatchers.resetMain()}
    }

    @Test fun completedUnreadableSavedPhotoDoesNotKeepNegativePixels()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var dir:File?=null
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false
                until("queue initialized"){queue(a)!=null};runCurrent()
                val running=job(5);dir=fixture(queue(a)!!,running)
                a.thumbnailDecoder={solid(Color.GREEN)}
                render(a,listOf(running));layout(a)
                until("negative visible"){current(image(a,running.id))!=null}
                a.thumbnailDecoder={f->if(f.name=="final-preview.jpg")null else solid(Color.GREEN)}
                a.savedThumbnailDecoder={throw SecurityException("permission lost")}
                render(a,listOf(running.copy(state=ExportJobState.COMPLETED,mediaUri="content://saved/lost")))
                until("negative cleared after inaccessible result"){current(image(a,running.id))==null}
            }
        }finally{runCurrent();dir?.deleteRecursively();Dispatchers.resetMain()}
    }

    @Test fun retryDuringSavedPhotoRefinementRejectsOldAttemptAndRecyclesUpgrade()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        val decoded=java.util.concurrent.atomic.AtomicReference<Bitmap>();var dir:File?=null
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false;until("queue initialized"){queue(a)!=null};runCurrent()
                val j=job(8).copy(state=ExportJobState.COMPLETED,mediaUri="content://saved/old-attempt")
                dir=fixture(queue(a)!!,j);dev.filmroom.export.ScanPreviewStore.begin(dir!!)
                File(dir,"final-preview.jpg").writeText("old240")
                a.thumbnailDecoder={Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888)}
                a.savedThumbnailDecoder={entered.complete(Unit);withContext(NonCancellable){gate.await()}
                    Bitmap.createBitmap(1024,683,Bitmap.Config.ARGB_8888).also{decoded.set(it)}}
                render(a,listOf(j));layout(a);until("saved decoder blocked"){entered.isCompleted}
                val view=image(a,j.id)
                dev.filmroom.export.ScanPreviewStore.begin(dir!!)
                gate.complete(Unit)
                until("unaccepted saved derivative recycled"){decoded.get()?.isRecycled==true}
                assertNull("Old attempt refinement cannot appear after retry",current(view))
                controller.pause()
            }
        } finally {gate.complete(Unit);runCurrent();dir?.deleteRecursively();Dispatchers.resetMain()}
    }

    @Test fun backgroundDuringSavedPhotoRefinementRecyclesBothImagesWithoutShowingLatePixels()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        val old=java.util.concurrent.atomic.AtomicReference<Bitmap>();val upgraded=java.util.concurrent.atomic.AtomicReference<Bitmap>()
        var dir:File?=null
        try {
            Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { controller ->
                val a=controller.get();a.previewSequenceEnabled=false;until("queue initialized"){queue(a)!=null};runCurrent()
                val j=job(8).copy(state=ExportJobState.COMPLETED,mediaUri="content://saved/background")
                dir=fixture(queue(a)!!,j);File(dir,"final-preview.jpg").writeText("old240")
                a.thumbnailDecoder={Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888).also{old.set(it)}}
                a.savedThumbnailDecoder={entered.complete(Unit);withContext(NonCancellable){gate.await()}
                    Bitmap.createBitmap(1024,683,Bitmap.Config.ARGB_8888).also{upgraded.set(it)}}
                render(a,listOf(j));layout(a);until("refinement started"){entered.isCompleted}
                val view=image(a,j.id);controller.pause();controller.stop();gate.complete(Unit)
                until("late high resolution derivative recycled"){upgraded.get()?.isRecycled==true}
                assertTrue("The initial thumbnail is also released",old.get().isRecycled)
                assertNull("Background decode cannot populate the view",current(view))
            }
        } finally {gate.complete(Unit);runCurrent();dir?.deleteRecursively();Dispatchers.resetMain()}
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
