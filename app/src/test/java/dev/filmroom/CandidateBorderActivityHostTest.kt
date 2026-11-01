package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import dev.filmroom.border.*
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.ui.PhotoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Actual candidate Activity controls + compiled asset definitions/loader. Film science is stubbed;
 * this tests enabled UI wiring and worker atlas loading, not native film or Android device behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CandidateBorderActivityHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun field(a:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.get(a)
    private fun model(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
    @Suppress("UNCHECKED_CAST") private fun state(vm:EditorViewModel)=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun await(done:()->Boolean){repeat(1500){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(done())return;Thread.sleep(5)};error("Candidate Activity flow timed out")}
    @Test fun actualSwitchLoadsBundledFrameOnWorkerThenEditsLocksAndDisables(){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val a=controller.get();val vm=model(a);val state=state(vm)
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            vm.borderMemorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
            vm.localDetailEnabled=false
            val photo=field(a,"photo") as PhotoView
            photo.disableDetail()
            val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800)
            val workerLoads=CopyOnWriteArrayList<Boolean>()
            val resolved=CopyOnWriteArrayList<BorderRaster>()
            BorderCatalog.initialize(app.assets){vm.borderMemorySnapshot()}
            BorderCatalog.clearCache() // No previous test's global cache hit can hide first decode.
            vm.borderProvider=object:BorderProvider by BorderCatalog {
                override fun resolve(snapshot:BorderSnapshot,checkActive:()->Unit):BorderRaster {
                    val offMain=Looper.myLooper()!=Looper.getMainLooper();workerLoads+=offMain;kotlin.check(offMain)
                    return BorderCatalog.resolve(snapshot,checkActive).also{resolved+=it}
                }
            }
            var filmCalls=0
            vm.renderPreview={source,_,_,_->filmCalls++;source.copy(Bitmap.Config.ARGB_8888,false)}
            vm.detailPreviewEdge={96}
            val original=Bitmap.createBitmap(96,72,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff527682.toInt())}
            val initial=original.copy(Bitmap.Config.ARGB_8888,false)
            val params=EditorState()
            state.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"candidate-ui-source"),"fixture.jpg","image/jpeg",640,480,0),original=original,rendered=initial,parameters=params,renderedParameters=params)
            await{photo.bitmap===initial}
            fun toggle()=all(root).filterIsInstance<Switch>().single{it.text.toString()=="边框水印"}
            val action=field(a,"compositionButton") as Button
            assertFalse(toggle().isChecked);assertTrue(toggle().isEnabled);assertEquals(View.VISIBLE,action.visibility)
            toggle().performClick()
            await{state.value.presentation!=null && state.value.canExport && action.visibility==View.VISIBLE}
            assertTrue(workerLoads.isNotEmpty());assertTrue(workerLoads.all{it});assertEquals(1,resolved.distinct().size)
            assertEquals(1,filmCalls);assertTrue(state.value.parameters.filmBorder)
            action.performClick();await{state.value.compositionEditing && photo.isCompositionEditing}
            val frame=photo.compositionAperture()
            photo.pinch(2.0,photo.width/2.0,photo.height/2.0);photo.pan(5.0,-3.0);photo.finishCompositionGesture()
            assertEquals(frame,photo.compositionAperture());assertEquals(1,filmCalls);assertEquals(1,resolved.distinct().size)
            all(root).single{it.tag=="crop-confirm"}.performClick();await{!state.value.compositionEditing && !photo.isCompositionEditing && state.value.canExport}
            action.performClick();await{state.value.compositionEditing}
            all(root).single{it.tag=="crop-cancel"}.performClick();await{!state.value.compositionEditing}
            toggle().performClick()
            await{!state.value.parameters.filmBorder && !state.value.compositionEditing && action.visibility==View.VISIBLE && state.value.presentation==null && state.value.canExport}
            assertFalse(photo.isCompositionEditing);assertEquals(1,filmCalls)
            toggle().performClick();await{state.value.presentation!=null && state.value.canExport}
            assertEquals(1,resolved.distinct().size)
            toggle().performClick();await{state.value.presentation==null && state.value.canExport}
            // A failed worker asset load must not export a silently unframed result.
            vm.borderProvider=LazyBorderRegistry(CandidateBorderDefinitions.definitions,CandidateBorderDefinitions.profiles){_,_->throw java.io.IOException("authored asset-read failure")}
            toggle().performClick();await{state.value.parameters.filmBorder && !state.value.updating && state.value.message!=null}
            assertFalse(state.value.canExport);assertNull(state.value.presentation)
            toggle().performClick();await{!state.value.parameters.filmBorder && state.value.canExport && action.visibility==View.VISIBLE}
        }finally{controller.pause().stop().destroy();BorderCatalog.clearCache();BorderCatalog.initialize(app.assets)}
    }
}
