package dev.filmroom

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditHistory
import dev.filmroom.model.EditorState
import dev.filmroom.ui.AutoExposureChoice
import dev.filmroom.ui.SettledChoiceSelector
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], qualifiers="w360dp-h780dp-mdpi")
class AutoExposureChoiceHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}
    private fun model(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
    private fun build(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("buildTools").apply{isAccessible=true}.invoke(a)
    @Test fun allLegacyBooleanAndMethodPairsKeepSchemaAndMapToCorrectOption() {
        assertEquals(listOf("off","average","median","center_weighted","partial","matrix","multi_zone","highlight_weighted"),AutoExposureChoice.options.map{it.first})
        for ((index,method) in AutoExposureChoice.options.drop(1).withIndex()) for(enabled in listOf(false,true)) {
            val old=EditorState(autoExposure=enabled,metering=method.first)
            val restored=EditorState.fromJson(old.json().toString())
            assertEquals(old,restored)
            assertEquals(if(enabled)index+1 else 0,AutoExposureChoice.options.indexOfFirst{it.first==AutoExposureChoice.value(restored)})
            val snapshot=org.json.JSONObject(restored.json().toString())
            assertEquals(enabled,snapshot.getBoolean("autoExposure"));assertEquals(method.first,snapshot.getString("metering"))
        }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun actualRailCommitsBothFieldsOnceWithOnePreviewUndoRedoOffAndReset() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            val a=c.get();shadowOf(Looper.getMainLooper()).idle();val vm=model(a)
            fun await(message:String,ready:()->Boolean) {
                val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
                while(System.nanoTime()<deadline) {
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                    if(ready())return
                    Thread.sleep(2)
                }
                val state=vm.state.value
                fun pendingField(name:String)=EditorViewModel::class.java.getDeclaredField(name).apply{isAccessible=true}.get(vm)
                fail("$message: startup=${state.startupReady} updating=${state.updating} refining=${state.previewRefining} " +
                    "renderJob=${pendingField("renderJob")} pending=${pendingField("pendingScientificRender")} " +
                    "parameters=${state.parameters} renderedParameters=${state.renderedParameters}")
            }
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await("Startup must finish before installing the UI fixture") {(operation.get(vm) as? kotlinx.coroutines.Job)?.isCompleted==true}
            // This test owns real rail/history/scheduler behavior, not native AE pixels.
            // A tiny deterministic pixel boundary keeps missing JNI and source I/O out of it.
            vm.localDetailEnabled=false;vm.cancelPreviewEngine={};vm.detailPreviewEdge={8}
            vm.borderMemorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
            val renders=java.util.concurrent.atomic.AtomicInteger()
            vm.renderPreview={source,_,_,_->renders.incrementAndGet();source.copy(Bitmap.Config.ARGB_8888,false)}
            val renderJob=EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}
            val pending=EditorViewModel::class.java.getDeclaredField("pendingScientificRender").apply{isAccessible=true}
            fun settle()=await("Previous render and its pending mailbox must finish") {
                renderJob.get(vm)==null && pending.get(vm)==null && !vm.state.value.updating && !vm.state.value.previewRefining
            }
            val initial=EditorState(metering="median")
            val mutable=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
            EditorViewModel::class.java.getDeclaredField("history").apply{isAccessible=true}.set(vm,EditHistory(initial))
            mutable.value=EditorUiState(photo=PhotoSource(File(a.cacheDir,"ae-fixture"),"ae.jpg","image/jpeg",8,8,1),original=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888),parameters=initial)
            field("group").set(a,"film");field("selected").set(a,"autoExposure");build(a)
            val generation=EditorViewModel::class.java.getDeclaredField("generation").apply{isAccessible=true}
            fun select(index:Int) {
                // Undo/redo may legitimately coalesce in the scheduler mailbox. Drain that
                // work before measuring this separate user selection's single request.
                settle()
                build(a)
                val selector=all(a.window.decorView).filterIsInstance<SettledChoiceSelector>().single()
                val before=generation.getLong(vm)
                val beforeRenders=renders.get()
                (((selector.getChildAt(1) as HorizontalScrollView).getChildAt(0) as ViewGroup).getChildAt(index)).performClick()
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
                settle()
                assertEquals("One user selection schedules exactly one preview",before+1,generation.getLong(vm))
                assertEquals("The selection reaches the pixel boundary exactly once",beforeRenders+1,renders.get())
                assertEquals(vm.state.value.parameters,vm.state.value.renderedParameters)
            }
            select(5);val enabled=initial.copy(autoExposure=true,metering="matrix")
            assertEquals(enabled,vm.state.value.parameters)
            vm.undo();assertEquals(initial,vm.state.value.parameters);assertFalse(vm.state.value.canUndo)
            vm.redo();assertEquals(enabled,vm.state.value.parameters)
            select(7);assertEquals("highlight_weighted",vm.state.value.parameters.metering)
            vm.undo();assertEquals(enabled,vm.state.value.parameters);vm.redo()
            select(0);val off=vm.state.value.parameters
            assertFalse(off.autoExposure);assertEquals("highlight_weighted",off.metering)
            vm.undo();assertTrue(vm.state.value.parameters.autoExposure);vm.redo();assertEquals(off,vm.state.value.parameters)
            vm.reset();assertEquals(EditorState(),vm.state.value.parameters)
            vm.undo();assertEquals(off,vm.state.value.parameters);vm.redo();assertEquals(EditorState(),vm.state.value.parameters)
        }
    }
    @Test fun legacySelectedToolMigratesOnRecreationWithoutChangingParameters() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            shadowOf(Looper.getMainLooper()).idle()
            field("group").set(c.get(),"film");field("selected").set(c.get(),"metering")
            val vm=model(c.get());val old=vm.state.value.parameters
            c.recreate();shadowOf(Looper.getMainLooper()).idle()
            assertSame(vm,model(c.get()));assertEquals(old,vm.state.value.parameters)
            assertEquals("autoExposure",field("selected").get(c.get()))
            assertFalse(vm.state.value.canUndo)
            val rail=all(c.get().window.decorView).filterIsInstance<SettledChoiceSelector>().single()
            assertTrue(rail.contentDescription.contains("关闭"))
            assertFalse(all(c.get().window.decorView).any{it.tag=="metering"})
        }
    }
}
