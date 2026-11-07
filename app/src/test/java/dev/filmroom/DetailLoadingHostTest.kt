package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.roi.*
import dev.filmroom.ui.PhotoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import java.io.File

/** Actual host Activity/Skia renders, not phone screenshots or device validation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h792dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DetailLoadingHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun field(activity:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(activity)
    private fun update(activity:MainActivity,status:DetailStatus)=MainActivity::class.java.getDeclaredMethod("updateDetailStatus",DetailStatus::class.java).apply{isAccessible=true}.invoke(activity,status)
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun layout(v:View,w:Int,h:Int){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)}
    @Suppress("UNCHECKED_CAST")
    private fun install(activity:MainActivity):EditorViewModel{
        val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(activity) as EditorViewModel
        val job=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        var ready=false
        repeat(1000){if(!ready){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));ready=(job.get(vm) as? Job)?.isCompleted==true;if(!ready)Thread.sleep(2)}}
        check(ready);vm.localDetailEnabled=true
        (field(activity,"photo") as PhotoView).disableDetail() // Presentation-only phase injection; actual controller path has separate host tests.
        val bitmap=Bitmap.createBitmap(432,768,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(110,126,132))}
        val params=EditorState()
        val mutable=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
        mutable.value=EditorUiState(photo=PhotoSource(File(activity.cacheDir,"spinner-fixture"),"fixture.jpg","image/jpeg",1080,1920,100),original=bitmap,rendered=bitmap,parameters=params,renderedParameters=params)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));return vm
    }
    private fun dispose(controller:org.robolectric.android.controller.ActivityController<MainActivity>){
        // Stop the deliberately never-returning Preparing provider and its animation before
        // framework teardown can drain tasks. No scheduler/animation assertions are bypassed.
        val activity=controller.get()
        (field(activity,"photo") as PhotoView).disableDetail()
        val spinner=field(activity,"detailLoading") as ProgressBar
        spinner.visibility=View.INVISIBLE
        (spinner.indeterminateDrawable as? android.graphics.drawable.Animatable)?.stop()
        controller.pause().stop().destroy()
    }
    @Test fun smallTopRightOverlayPreservesPortraitLandscapeAndLargeFontLayout(){
        val out=File("build/ui-host-renders").apply{mkdirs()}
        for((w,h) in listOf(360 to 792,792 to 360))for(font in listOf(1f,2f)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi");RuntimeEnvironment.setFontScale(font)
            RuntimeEnvironment.getApplication().getSharedPreferences("local-session",0).edit().clear().commit()
            val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
            try{
                val activity=controller.get();val vm=install(activity)
                val root=activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                layout(root,w,h)
                val photo=field(activity,"photo") as PhotoView
                val spinner=field(activity,"detailLoading") as ProgressBar
                val chip=field(activity,"badge") as TextView
                val geometry=listOf(photo.left,photo.top,photo.width,photo.height)
                for(phase in DetailPhase.values()){
                    update(activity,DetailStatus(phase));layout(root,w,h)
                    assertEquals(geometry,listOf(photo.left,photo.top,photo.width,photo.height))
                    assertEquals(if(phase in setOf(DetailPhase.Preparing,DetailPhase.Refining))View.VISIBLE else View.INVISIBLE,spinner.visibility)
                }
                update(activity,DetailStatus(DetailPhase.Preparing));layout(root,w,h)
                val parent=spinner.parent as ViewGroup
                assertEquals(24,spinner.width);assertEquals(24,spinner.height)
                assertEquals(parent.width-12,spinner.right);assertEquals(12,spinner.top)
                assertTrue("Chip must leave a fixed gap for spinner",chip.right<=spinner.left)
                assertTrue(all(root).filterIsInstance<TextView>().none{it.text.contains("准备放大预览")})
                // Hold a real controller open in Preparing while Android performs layout/animation traversals.
                // A manually injected status alone is correctly replaced by PhotoView's next lifecycle callback.
                photo.configureDetail(EditorDetailProvider{_,_->kotlinx.coroutines.awaitCancellation()})
                photo.bindDetail(vm.state.value.photo,vm.state.value.parameters,true)
                photo.pinch(3.0,photo.width/2.0,photo.height/2.0)
                repeat(50){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));Thread.sleep(2)}
                assertEquals(DetailPhase.Preparing,photo.detailStatus.phase)
                val render=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(Canvas(render))
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));root.draw(Canvas(render))
                assertEquals(View.VISIBLE,spinner.visibility)
                val icon=Bitmap.createBitmap(spinner.width,spinner.height,Bitmap.Config.ARGB_8888)
                spinner.draw(Canvas(icon));val pixels=IntArray(icon.width*icon.height);icon.getPixels(pixels,0,icon.width,0,0,icon.width,icon.height)
                assertTrue("Loading icon must actually draw, not merely be VISIBLE",pixels.any{Color.alpha(it)>0})
                icon.recycle()
                File(out,"detail-loading-${w}x${h}-font$font.png").outputStream().use{render.compress(Bitmap.CompressFormat.PNG,100,it)}
                render.recycle()
                update(activity,DetailStatus(DetailPhase.Ready,432,768,3));assertEquals(View.INVISIBLE,spinner.visibility);assertFalse((spinner.indeterminateDrawable as android.graphics.drawable.Animatable).isRunning)
            }finally{dispose(controller)}
        }
        RuntimeEnvironment.setFontScale(1f)
    }
    @Test fun activityToastsAreDeduplicatedAndNeverEmittedInBackground(){
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try{
            val activity=controller.get();install(activity);ShadowToast.reset()
            val failure=DetailStatus(DetailPhase.Fallback,failure=DetailFailure.MemoryBudget)
            repeat(20){update(activity,failure)};assertEquals(1,ShadowToast.shownToastCount())
            controller.pause()
            val next=failure.copy(failure=DetailFailure.Unsupported)
            repeat(20){update(activity,next)};assertEquals(1,ShadowToast.shownToastCount())
            update(activity,DetailStatus(DetailPhase.Preparing));assertEquals(View.INVISIBLE,(field(activity,"detailLoading") as View).visibility)
            controller.resume();update(activity,next);assertEquals(2,ShadowToast.shownToastCount())
            controller.recreate();install(controller.get());update(controller.get(),next)
            assertEquals("Rotation must not repeat a previously shown photo/reason",2,ShadowToast.shownToastCount())
        }finally{dispose(controller)}
    }
    @Test fun detachStopsTicksAndDisabledAnimationsKeepAVisibleStaticIcon(){
        val scale=android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}
        scale.invoke(null,1f)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try{
            val activity=controller.get();install(activity)
            val spinner=field(activity,"detailLoading") as ProgressBar
            update(activity,DetailStatus(DetailPhase.Preparing));spinner.layout(0,0,24,24)
            val icon=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888);spinner.draw(Canvas(icon))
            val animation=spinner.indeterminateDrawable as android.graphics.drawable.Animatable
            assertTrue(animation.isRunning)
            (spinner.parent as ViewGroup).removeView(spinner);assertFalse(animation.isRunning)
            scale.invoke(null,0f)
            val still=dev.filmroom.ui.SmallLoadingDrawable(Color.WHITE,1f)
            still.setBounds(0,0,24,24);still.start();icon.eraseColor(Color.TRANSPARENT);still.draw(Canvas(icon))
            assertFalse(still.isRunning)
            val pixels=IntArray(24*24);icon.getPixels(pixels,0,24,0,0,24,24)
            assertTrue(pixels.any{Color.alpha(it)>0});still.stop();icon.recycle()
        }finally{dispose(controller);scale.invoke(null,1f)}
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun preImagingWholeFrameShowsOnlyActualForegroundRefinement() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity=controller.get();val vm=install(activity)
            val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
            val params=state.value.parameters.copy(filmBorder=true,filmBorderStage=1)
            state.value=state.value.copy(parameters=params,renderedParameters=params,previewRefining=true)
            val spinner=field(activity,"detailLoading") as ProgressBar
            update(activity,DetailStatus(DetailPhase.WaitingForPreview))
            assertEquals(View.VISIBLE,spinner.visibility)
            state.value=state.value.copy(previewRefining=false)
            update(activity,DetailStatus(DetailPhase.WaitingForPreview));assertEquals(View.INVISIBLE,spinner.visibility)
            state.value=state.value.copy(previewRefining=true,compare=true)
            update(activity,DetailStatus(DetailPhase.WaitingForPreview));assertEquals(View.INVISIBLE,spinner.visibility)
            state.value=state.value.copy(compare=false)
            MainActivity::class.java.getDeclaredField("detailForeground").apply{isAccessible=true}.set(activity,false)
            update(activity,DetailStatus(DetailPhase.WaitingForPreview));assertEquals(View.INVISIBLE,spinner.visibility)
        }finally{dispose(controller)}
    }

}
