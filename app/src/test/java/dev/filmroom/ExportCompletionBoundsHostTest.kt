package dev.filmroom

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.export.ExportJob
import dev.filmroom.export.ExportJobState
import dev.filmroom.export.ExportQueue
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w400dp-h1200dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportCompletionBoundsHostTest {
    private fun views(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList())
    private fun render(a:ExportQueueActivity,jobs:List<ExportJob>)=ExportQueueActivity::class.java.getDeclaredMethod("render",List::class.java).apply{isAccessible=true}.invoke(a,jobs)
    private fun field(a:ExportQueueActivity,name:String)=ExportQueueActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun queue(a:ExportQueueActivity)=field(a,"queue") as? ExportQueue
    private fun root(a:ExportQueueActivity)=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun layout(a:ExportQueueActivity) {
        val r=root(a);val c=a.resources.configuration
        r.measure(View.MeasureSpec.makeMeasureSpec(c.screenWidthDp,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(c.screenHeightDp,View.MeasureSpec.EXACTLY))
        r.layout(0,0,c.screenWidthDp,c.screenHeightDp)
        r.viewTreeObserver.dispatchOnPreDraw()
    }
    private fun card(a:ExportQueueActivity,j:ExportJob)=views(root(a)).single{it.tag=="export-job:${j.id}"}
    private fun frame(a:ExportQueueActivity,j:ExportJob)=views(root(a)).single{it.tag=="job-thumbnail:${j.id}"}.parent as View
    private fun rect(v:View)=Rect(v.left,v.top,v.right,v.bottom)
    private fun tick(ms:Long)=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun seekBounds(fraction:Float) {
        // Drive the actual framework animators deterministically; Robolectric's Looper and
        // transition frame clock are not advanced by the coroutine test scheduler.
        val method=android.transition.Transition::class.java.getDeclaredMethod("getRunningAnimators").apply{isAccessible=true}
        val running=method.invoke(null) as Map<*,*>
        val animators=running.keys.filterIsInstance<android.animation.Animator>()
        assertTrue("Real bounds animators must be scheduled",animators.isNotEmpty())
        fun seek(animator:android.animation.Animator) { when(animator) {
            is android.animation.ValueAnimator -> animator.setCurrentFraction(fraction)
            // AnimatorSet defers a seek on a running set to its next animation pulse.
            // Drive its real child properties at the same fraction for deterministic inspection.
            is android.animation.AnimatorSet -> animator.childAnimations.forEach { seek(it) }
        } }
        animators.forEach { seek(it) }
    }
    private fun finish(a:ExportQueueActivity)=android.transition.TransitionManager.endTransitions(field(a,"rows") as ViewGroup)
    private fun job(state:ExportJobState=ExportJobState.RUNNING,created:Long=10)=ExportJob(UUID.randomUUID().toString(),created,EditorState(),4000,3000,ExportFormat.JPEG,95,state,ExportDisplayProgress(7,1,10))
    private fun done(j:ExportJob)=j.copy(state=ExportJobState.COMPLETED,progress=ExportDisplayProgress(8,100,100),mediaUri="content://media/external/images/media/123")
    private fun TestScope.ready(a:ExportQueueActivity) {
        a.previewSequenceEnabled=false // This suite isolates bounds; real sequence/dwell is tested in LoadingHostTest.
        repeat(500){tick(1);runCurrent();if(queue(a)!=null)return;Thread.sleep(5)}
        error("Queue did not initialize")
    }
    @Test fun nativePathAnimatorChildSeekProducesIntermediateCoordinates() {
        class PointTarget { var point=android.graphics.PointF() }
        val target=PointTarget()
        val property=object:android.util.Property<PointTarget,android.graphics.PointF>(android.graphics.PointF::class.java,"point") {
            override fun get(t:PointTarget)=t.point
            override fun set(t:PointTarget,p:android.graphics.PointF){t.point=android.graphics.PointF(p.x,p.y)}
        }
        val path=android.graphics.Path().apply{moveTo(0f,0f);lineTo(100f,200f)}
        val child=android.animation.ObjectAnimator.ofObject(target,property,null,path)
        val set=android.animation.AnimatorSet().apply{playTogether(child);duration=320;start()}
        child.setCurrentFraction(.35f)
        assertTrue("Real native path must interpolate x=${target.point.x}",target.point.x>0f && target.point.x<100f)
        assertTrue(target.point.y>0f && target.point.y<200f)
        set.end()
        assertEquals(100f,target.point.x,.01f)
    }
    @Test fun completionInterpolatesPreviewAndCardBoundsWhileStatusIsAlreadyComplete()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { c ->
            val a=c.get();ready(a);val running=job();render(a,listOf(running));layout(a);tick(20)
            val oldFrame=rect(frame(a,running));val oldCard=rect(card(a,running))
            val completed=done(running);render(a,listOf(completed));layout(a)
            assertEquals("The first frame preserves the old size",oldFrame,rect(frame(a,running)))
            assertEquals(oldCard.height(),card(a,running).height)
            assertTrue("Completed state is immediate",views(card(a,running)).filterIsInstance<TextView>().any{it.text.toString()==ExportJobPresentation.from(completed).progress})
            seekBounds(.35f)
            val midFrame=rect(frame(a,running));val midHeight=card(a,running).height
            finish(a)
            val endFrame=rect(frame(a,running));val endHeight=card(a,running).height
            assertTrue("frame old=$oldFrame mid=$midFrame end=$endFrame",endFrame.width()<midFrame.width() && midFrame.width()<oldFrame.width())
            assertTrue(endFrame.height()<midFrame.height() && midFrame.height()<oldFrame.height())
            assertTrue(endHeight<midHeight && midHeight<oldCard.height())
            assertEquals(100,endFrame.height())
            render(a,listOf(completed.copy(completionViewed=true)));layout(a)
            assertEquals("Completion acknowledgement never replays resizing",endFrame,rect(frame(a,running)))
        }} finally {runCurrent();Dispatchers.resetMain()}
    }
    @Test fun simultaneousCompletionsMoveFollowingCardsTogether()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { c ->
            val a=c.get();ready(a);val one=job(created=30);val two=job(created=20);val third=job(ExportJobState.PENDING,10)
            render(a,listOf(one,two,third));layout(a);tick(20)
            val oldOne=card(a,one).height;val oldTwo=card(a,two).height;val oldTop=card(a,third).top
            render(a,listOf(done(one),done(two),third));layout(a);seekBounds(.35f)
            val midOne=card(a,one).height;val midTwo=card(a,two).height;val midTop=card(a,third).top
            finish(a)
            assertTrue("one old=$oldOne mid=$midOne end=${card(a,one).height}; two old=$oldTwo mid=$midTwo end=${card(a,two).height}; next old=$oldTop mid=$midTop end=${card(a,third).top}",card(a,one).height<midOne && midOne<oldOne)
            assertTrue(card(a,two).height<midTwo && midTwo<oldTwo)
            assertTrue(card(a,third).top<midTop && midTop<oldTop)
            assertEquals(0f,card(a,third).translationY,0f)
        }} finally {runCurrent();Dispatchers.resetMain()}
    }
    @Test fun historyFailureCancellationAndReducedMotionUseDirectFinalLayout()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scale=android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}
        try {Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { c ->
            val a=c.get();ready(a)
            val historical=done(job());render(a,listOf(historical));layout(a)
            assertEquals(100,frame(a,historical).height);val historicalRect=rect(card(a,historical));tick(100)
            assertEquals(historicalRect,rect(card(a,historical)))
            for(state in listOf(ExportJobState.FAILED,ExportJobState.CANCELLED,ExportJobState.COMPLETED)) {
                val running=job();render(a,listOf(running));layout(a)
                if(state==ExportJobState.COMPLETED)scale.invoke(null,0f)
                render(a,listOf(running.copy(state=state)));layout(a)
                val initial=rect(card(a,running));val preview=rect(frame(a,running));tick(100);finish(a)
                assertEquals(initial,rect(card(a,running)));assertEquals(preview,rect(frame(a,running)))
                scale.invoke(null,1f)
            }
        }} finally {scale.invoke(null,1f);runCurrent();Dispatchers.resetMain()}
    }
    @Test fun touchPauseAndRecreationFinishWithoutReplayingOrMovingScroll()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { c ->
            val a=c.get();ready(a);val running=job();val completed=done(running)
            render(a,listOf(running));layout(a);render(a,listOf(completed));layout(a);tick(70)
            val scroll=field(a,"scroll") as ScrollView
            val down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,20f,20f,0)
            try{scroll.dispatchTouchEvent(down)}finally{down.recycle()}
            assertEquals(100,frame(a,running).height)
            val y=scroll.scrollY;tick(400);assertEquals(y,scroll.scrollY)
            val next=job();render(a,listOf(next));layout(a);render(a,listOf(done(next)));layout(a);tick(70)
            c.pause();assertEquals(100,frame(a,next).height)
            c.resume();render(a,listOf(done(next)));layout(a);assertEquals(100,frame(a,next).height)
            c.recreate();val restored=c.get();ready(restored);render(restored,listOf(done(next)));layout(restored)
            assertEquals(100,frame(restored,next).height);tick(100);assertEquals(100,frame(restored,next).height)
        }} finally {runCurrent();Dispatchers.resetMain()}
    }
    @Test @Config(qualifiers="w800dp-h400dp-land-mdpi")
    fun landscapePreviewAndCardAlsoInterpolate()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { c ->
            val a=c.get();ready(a);val running=job();render(a,listOf(running));layout(a);tick(20)
            val old=rect(frame(a,running));render(a,listOf(done(running)));layout(a);seekBounds(.35f)
            val mid=rect(frame(a,running));finish(a);val end=rect(frame(a,running))
            assertTrue("landscape old=$old mid=$mid end=$end",end.width()<mid.width() && mid.width()<old.width())
            assertTrue(end.height()<mid.height() && mid.height()<old.height())
        }} finally {runCurrent();Dispatchers.resetMain()}
    }
}
