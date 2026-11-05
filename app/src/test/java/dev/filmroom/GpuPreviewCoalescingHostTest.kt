package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import dev.filmroom.diagnostics.PreviewDisposition
import dev.filmroom.engine.OptimizationPreferences
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Real production scheduler; gates stand in for a native frame's completion/seal. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class GpuPreviewCoalescingHostTest {
    private class Fixture(val vm:EditorViewModel,val state:MutableStateFlow<EditorUiState>) {
        val bitmaps=mutableListOf<Bitmap>()
        var cancels=0
        val calls=mutableListOf<Pair<Float,Boolean>>()
        val dispositions=mutableListOf<PreviewDisposition>()
        fun bitmap(detail:Boolean=false)=Bitmap.createBitmap(if(detail)192 else 96,if(detail)128 else 64,Bitmap.Config.ARGB_8888).also{bitmaps+=it}
        suspend fun close(){
            vm.cancel()
            (EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}.get(vm) as? Job)?.cancelAndJoin()
            vm.viewModelScope.cancel()
            bitmaps.distinct().forEach{if(!it.isRecycled)it.recycle()}
            RuntimeEnvironment.getApplication().getSharedPreferences("exact-optimization-test",0).edit().clear().commit()
        }
    }
    private fun TestScope.waitFor(predicate:()->Boolean){
        repeat(1000){runCurrent();if(predicate())return;advanceTimeBy(10);Thread.sleep(2)}
        error("Timed out waiting for preview scheduler")
    }
    @Suppress("UNCHECKED_CAST") private fun TestScope.fixture(gpu:Boolean=true):Fixture {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        app.getSharedPreferences("exact-optimization-test",0).edit().clear().commit()
        OptimizationPreferences(app).setGpuRendering(gpu)
        val vm=EditorViewModel(app)
        val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        waitFor{(operation.get(vm) as? Job)?.isCompleted==true}
        val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
        val f=Fixture(vm,state)
        state.value=EditorUiState(photo=PhotoSource(File("unused-gpu-preview"),"fixture","image/jpeg",6000,4000,0),original=f.bitmap(true))
        vm.cancelPreviewEngine={f.cancels++}
        vm.detailPreviewEdge={192}
        vm.previewAdoptionSink={f.dispositions+=it.disposition}
        vm.renderPreview={_,p,_,detail->f.calls+=p.brightness to detail;f.bitmap(detail)}
        return f
    }
    @Test fun rapidChangesKeepOneActiveOneLatestAndFinalCommitRefines()=runTest {
        assumeTrue(BuildConfig.PERF_BUILD)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        val gate=CompletableDeferred<Unit>();var cancelled=false;var obsolete:Bitmap?=null
        try {
            f=fixture();val h=f
            h.vm.renderPreview={_,p,_,detail->
                h.calls+=p.brightness to detail
                if(p.brightness==.1f){try{gate.await()}catch(c:CancellationException){cancelled=true;throw c};h.bitmap().also{obsolete=it}}
                else h.bitmap(detail)
            }
            h.vm.parameters(EditorState(brightness=.1f),false);advanceTimeBy(121);runCurrent()
            val initialCancels=h.cancels
            for(i in 2..20)h.vm.parameters(EditorState(brightness=i/100f),false)
            h.vm.parameters(EditorState(brightness=.2f),true)
            advanceTimeBy(1000);runCurrent()
            assertEquals(listOf(.1f to false),h.calls);assertFalse(cancelled)
            assertEquals(initialCancels,h.cancels);assertNull(h.state.value.rendered)
            gate.complete(Unit)
            waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            assertEquals(listOf(.1f to false,.2f to false,.2f to true),h.calls)
            assertTrue(obsolete!!.isRecycled);assertEquals(PreviewDisposition.STALE_GENERATION,h.dispositions.first())
            assertEquals(.2f,h.state.value.renderedParameters!!.brightness)
            assertEquals(initialCancels,h.cancels);assertTrue(h.state.value.canUndo)
        }finally{gate.complete(Unit);f?.close();Dispatchers.resetMain()}
    }
    @Test fun completedGpuFrameKeepsEngineEpochOnNextParameterEdit()=runTest {
        assumeTrue(BuildConfig.PERF_BUILD)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f
            h.vm.parameters(EditorState(brightness=.1f));waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            val initialCancels=h.cancels
            h.vm.parameters(EditorState(brightness=.2f));waitFor{h.state.value.renderedParameters?.brightness==.2f && !h.state.value.previewRefining}
            assertEquals(initialCancels,h.cancels)
        }finally{f?.close();Dispatchers.resetMain()}
    }
    @Test fun explicitCancelDropsPendingAndPreventsLatePublication()=runTest {
        assumeTrue(BuildConfig.PERF_BUILD)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null;val gate=CompletableDeferred<Unit>()
        try {
            f=fixture();val h=f;var obsolete:Bitmap?=null
            h.vm.renderPreview={_,p,_,detail->h.calls+=p.brightness to detail;withContext(NonCancellable){gate.await()};h.bitmap().also{obsolete=it}}
            h.vm.parameters(EditorState(brightness=.1f),false);advanceTimeBy(121);runCurrent()
            h.vm.parameters(EditorState(brightness=.2f));val before=h.cancels;h.vm.cancel()
            assertEquals(before+1,h.cancels)
            gate.complete(Unit);waitFor{obsolete?.isRecycled==true};advanceUntilIdle()
            assertEquals(1,h.calls.size);assertNull(h.state.value.rendered);assertFalse(h.state.value.updating)
        }finally{gate.complete(Unit);f?.close();Dispatchers.resetMain()}
    }
    @Test fun cpuModeStillCancelsSupersededScience()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null;val gate=CompletableDeferred<Unit>()
        try {
            f=fixture(false);val h=f;var cancelled=false
            h.vm.renderPreview={_,p,_,detail->h.calls+=p.brightness to detail
                if(p.brightness==.1f)try{gate.await()}catch(c:CancellationException){cancelled=true;throw c}
                h.bitmap(detail)}
            h.vm.parameters(EditorState(brightness=.1f),false);advanceTimeBy(121);runCurrent()
            val before=h.cancels;h.vm.parameters(EditorState(brightness=.2f));runCurrent()
            assertTrue(cancelled);assertEquals(before+1,h.cancels)
            waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            assertEquals(.2f,h.state.value.renderedParameters!!.brightness)
        }finally{gate.complete(Unit);f?.close();Dispatchers.resetMain()}
    }
    @Test fun failedGpuFrameRequiresHardResetBeforeRetry()=runTest {
        assumeTrue(BuildConfig.PERF_BUILD)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f
            h.vm.renderPreview={_,_,_,_->error("fake native failure")}
            h.vm.parameters(EditorState(brightness=.1f),false);waitFor{h.state.value.message!=null}
            val before=h.cancels
            h.vm.renderPreview={_,_,_,detail->h.bitmap(detail)}
            h.vm.retry();waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            assertEquals(before+1,h.cancels)
        }finally{f?.close();Dispatchers.resetMain()}
    }
    @Test fun sourceReplacementAndSettingsChangeHardCancelAndDropPending()=runTest {
        assumeTrue(BuildConfig.PERF_BUILD)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for(changeSource in listOf(true,false)){
                val h=fixture();val gate=CompletableDeferred<Unit>();var obsolete:Bitmap?=null
                try {
                    h.vm.renderPreview={_,p,_,detail->h.calls+=p.brightness to detail
                        if(p.brightness==.1f){withContext(NonCancellable){gate.await()};h.bitmap().also{obsolete=it}}else h.bitmap(detail)}
                    h.vm.parameters(EditorState(brightness=.1f),false);advanceTimeBy(121);runCurrent()
                    h.vm.parameters(EditorState(brightness=.2f));val before=h.cancels
                    if(changeSource){
                        h.state.value=h.state.value.copy(photo=PhotoSource(File("replacement-gpu-preview"),"new fixture","image/jpeg",6000,4000,0))
                        h.vm.retry()
                    }else{
                        OptimizationPreferences(RuntimeEnvironment.getApplication()).setGpuRendering(false)
                        assertTrue(h.vm.refreshGpuRenderingChoice())
                    }
                    assertTrue(h.cancels>before)
                    waitFor{h.state.value.canExport && !h.state.value.previewRefining}
                    val latest=h.state.value.rendered
                    gate.complete(Unit);waitFor{obsolete?.isRecycled==true};advanceUntilIdle()
                    assertSame(latest,h.state.value.rendered)
                    assertEquals(listOf(.1f to false,.2f to false,.2f to true),h.calls)
                }finally{gate.complete(Unit);h.close()}
            }
        }finally{Dispatchers.resetMain()}
    }
    @Test fun clearingViewModelDropsPendingAndRecyclesLateFrame()=runTest {
        assumeTrue(BuildConfig.PERF_BUILD)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null;val gate=CompletableDeferred<Unit>()
        try {
            f=fixture();val h=f;var obsolete:Bitmap?=null
            h.vm.renderPreview={_,p,_,detail->h.calls+=p.brightness to detail;withContext(NonCancellable){gate.await()};h.bitmap().also{obsolete=it}}
            h.vm.parameters(EditorState(brightness=.1f),false);advanceTimeBy(121);runCurrent()
            h.vm.parameters(EditorState(brightness=.2f))
            androidx.lifecycle.ViewModelStore().apply{put("preview",h.vm);clear()}
            assertNull(EditorViewModel::class.java.getDeclaredField("pendingScientificPreview").apply{isAccessible=true}.get(h.vm))
            gate.complete(Unit);waitFor{obsolete?.isRecycled==true};advanceUntilIdle()
            assertEquals(1,h.calls.size);assertNull(h.state.value.rendered)
        }finally{gate.complete(Unit);f?.close();Dispatchers.resetMain()}
    }

    @Test fun returningToActiveScienceStillKeepsFinalCommitRefinement()=runTest {
        assumeTrue(BuildConfig.PERF_BUILD)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null;val gate=CompletableDeferred<Unit>()
        try {
            f=fixture();val h=f;var first=true
            h.vm.renderPreview={_,p,_,detail->h.calls+=p.brightness to detail
                if(first){first=false;gate.await()};h.bitmap(detail)}
            h.vm.parameters(EditorState(brightness=.1f),false);advanceTimeBy(121);runCurrent()
            h.vm.parameters(EditorState(brightness=.2f),false)
            h.vm.parameters(EditorState(brightness=.1f),false)
            h.vm.parameters(EditorState(brightness=.1f),true)
            gate.complete(Unit);waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            assertEquals(listOf(.1f to false,.1f to false,.1f to true),h.calls)
            assertEquals(PreviewDisposition.STALE_GENERATION,h.dispositions.first())
            assertTrue(h.state.value.canUndo)
        }finally{gate.complete(Unit);f?.close();Dispatchers.resetMain()}
    }

    @Test fun lateOldRefinementFailureCannotPoisonReplacementSession()=runTest {
        assumeTrue(BuildConfig.PERF_BUILD)
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null;val gate=CompletableDeferred<Unit>()
        try {
            f=fixture();val h=f;var refining=false
            h.vm.renderPreview={_,p,_,detail->h.calls+=p.brightness to detail
                if(p.brightness==.1f && detail){refining=true;withContext(NonCancellable){gate.await();error("late old refinement failure")}}
                h.bitmap(detail)}
            h.vm.parameters(EditorState(brightness=.1f));waitFor{refining}
            val oldJob=EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}.get(h.vm) as Job
            h.state.value=h.state.value.copy(photo=PhotoSource(File("replacement-refinement"),"fixture","image/jpeg",6000,4000,0))
            h.vm.parameters(EditorState(brightness=.2f));waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            gate.complete(Unit);waitFor{oldJob.isCompleted}
            val before=h.cancels
            h.vm.parameters(EditorState(brightness=.3f));waitFor{h.state.value.renderedParameters?.brightness==.3f && !h.state.value.previewRefining}
            assertEquals(before,h.cancels)
        }finally{gate.complete(Unit);f?.close();Dispatchers.resetMain()}
    }

}
