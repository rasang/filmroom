package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import dev.filmroom.engine.OptimizationPreferences
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Actual ViewModel cache/adoption path, with only the scientific renderer replaced by a bounded fake. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class GpuRenderingRefreshHostTest {
    private class Fixture(val vm:EditorViewModel,val state:MutableStateFlow<EditorUiState>,val preferences:OptimizationPreferences) {
        val produced=mutableListOf<Bitmap>()
        val choices=mutableListOf<Boolean>()
        var invalidations=0
        fun bitmap()=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).also{produced+=it}
        suspend fun close(){
            vm.cancel()
            (EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}.get(vm) as? Job)?.cancelAndJoin()
            vm.viewModelScope.cancel()
            (produced+listOfNotNull(state.value.original,state.value.rendered,state.value.compositionSource)).distinct().forEach{if(!it.isRecycled)it.recycle()}
            RuntimeEnvironment.getApplication().getSharedPreferences("exact-optimization-test",0).edit().clear().commit()
        }
    }
    private fun TestScope.waitFor(predicate:()->Boolean){
        repeat(1000){runCurrent();if(predicate())return;advanceTimeBy(10);Thread.sleep(2)}
        error("Timed out waiting for rendering choice")
    }
    @Suppress("UNCHECKED_CAST") private fun TestScope.fixture():Fixture {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        app.getSharedPreferences("exact-optimization-test",0).edit().clear().commit()
        val vm=EditorViewModel(app)
        val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        waitFor{(operation.get(vm) as? Job)?.isCompleted==true}
        val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
        val f=Fixture(vm,state,OptimizationPreferences(app))
        state.value=EditorUiState(photo=PhotoSource(File("private-choice-source.nef"),"fixture","image/x-nikon-nef",7360,4912,0),original=f.bitmap(),parameters=EditorState(grain=.7f))
        vm.detailPreviewEdge={96};vm.detailInvalidator={f.invalidations++}
        vm.renderPreview={_,_,_,_->f.choices+=f.preferences.snapshot().gpuRenderingEnabled;f.bitmap()}
        return f
    }
    @Test fun changedMasterInvalidatesCompletedPreviewAndDetailWithoutChangingPhotoHistory()=runTest {
        if(!BuildConfig.PERF_BUILD)return@runTest
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f;val original=h.state.value.parameters;val source=h.state.value.photo
            h.vm.retry();waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            val cpu=h.state.value.rendered
            h.preferences.setGpuRendering(true);assertTrue(h.vm.refreshGpuRenderingChoice())
            assertNull(h.state.value.renderedParameters);assertFalse(h.state.value.canExport)
            waitFor{h.choices.size==2 && h.state.value.canExport && !h.state.value.previewRefining}
            assertNotSame(cpu,h.state.value.rendered);assertEquals(listOf(false,true),h.choices);assertEquals(1,h.invalidations)
            assertFalse(h.vm.refreshGpuRenderingChoice());h.vm.retry();advanceUntilIdle();assertEquals(2,h.choices.size)
            h.preferences.setGpuRendering(false);assertTrue(h.vm.refreshGpuRenderingChoice())
            waitFor{h.choices.size==3 && h.state.value.canExport && !h.state.value.previewRefining}
            assertEquals(listOf(false,true,false),h.choices);assertEquals(2,h.invalidations)
            assertEquals(original,h.state.value.parameters);assertSame(source,h.state.value.photo)
            assertEquals(42,org.json.JSONObject(h.state.value.parameters.json()).getInt("seed"))
            assertFalse(h.state.value.canUndo);assertFalse(h.state.value.canRedo)
        }finally{f?.close();Dispatchers.resetMain()}
    }
    @Test fun lateOldBackendCompletionCannotAdoptAfterMasterChange()=runTest {
        if(!BuildConfig.PERF_BUILD)return@runTest
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null;val gate=CompletableDeferred<Unit>()
        try {
            f=fixture();val h=f;var entered=false;var obsolete:Bitmap?=null
            h.vm.renderPreview={_,_,_,_->val gpu=h.preferences.snapshot().gpuRenderingEnabled;h.choices+=gpu
                if(!gpu){entered=true;withContext(NonCancellable){gate.await()};h.bitmap().also{obsolete=it}}else h.bitmap()}
            h.vm.retry();advanceTimeBy(121);runCurrent();assertTrue(entered)
            h.preferences.setGpuRendering(true);assertTrue(h.vm.refreshGpuRenderingChoice())
            waitFor{h.state.value.canExport && h.choices==listOf(false,true)}
            val current=h.state.value.rendered
            gate.complete(Unit);waitFor{obsolete?.isRecycled==true}
            assertSame(current,h.state.value.rendered);assertEquals(1,h.invalidations)
        }finally{gate.complete(Unit);f?.close();Dispatchers.resetMain()}
    }
    @Test fun busyCaptureFinishesBeforeDeferredRefreshAndNoNewPreferenceMeansNoWork()=runTest {
        if(!BuildConfig.PERF_BUILD)return@runTest
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f
            h.vm.retry();waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            h.state.value=h.state.value.copy(busy="capture",exporting=true)
            h.preferences.setGpuRendering(true);repeat(3){assertFalse(h.vm.refreshGpuRenderingChoice())};runCurrent()
            assertEquals(1,h.choices.size);assertEquals(0,h.invalidations);assertTrue(h.state.value.exporting)
            h.state.value=h.state.value.copy(busy=null,exporting=false)
            waitFor{h.choices.size==2 && h.state.value.canExport && !h.state.value.previewRefining}
            assertEquals(listOf(false,true),h.choices);assertEquals(1,h.invalidations)
            assertFalse(h.vm.refreshGpuRenderingChoice());advanceUntilIdle();assertEquals(2,h.choices.size)
        }finally{f?.close();Dispatchers.resetMain()}
    }
}
