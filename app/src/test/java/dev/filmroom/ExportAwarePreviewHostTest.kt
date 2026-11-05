package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import dev.filmroom.engine.ExportWorkAdmissions
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Production editor and production admission boundary; only scientific pixels are injected.
 * These tests assert scheduling/ownership, not native/GPU correctness or device latency.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class ExportAwarePreviewHostTest {
    private data class Fixture(val vm:EditorViewModel,val state:MutableStateFlow<EditorUiState>,val calls:MutableList<Pair<Float,Boolean>>,val bitmaps:MutableList<Bitmap>)
    private fun TestScope.waitFor(predicate:()->Boolean){repeat(1000){runCurrent();if(predicate())return;advanceTimeBy(10);Thread.sleep(2)};error("Preview did not settle")}
    @Suppress("UNCHECKED_CAST") private fun TestScope.fixture():Fixture {
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("local-session",0).edit().clear().commit()
        val vm=EditorViewModel(app).apply{borderMemorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}}
        val op=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        waitFor{(op.get(vm) as? Job)?.isCompleted==true}
        val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
        val bitmaps=mutableListOf<Bitmap>()
        fun image(detail:Boolean)=Bitmap.createBitmap(if(detail)192 else 96,if(detail)128 else 64,Bitmap.Config.ARGB_8888).also{bitmaps+=it}
        state.value=EditorUiState(photo=PhotoSource(File("export-aware-preview"),"fixture","image/jpeg",6000,4000,0),original=image(true))
        val calls=mutableListOf<Pair<Float,Boolean>>()
        vm.cancelPreviewEngine={}
        vm.detailPreviewEdge={192}
        vm.renderPreview={_,p,_,detail->calls+=p.brightness to detail;image(detail)}
        return Fixture(vm,state,calls,bitmaps)
    }
    private suspend fun Fixture.dispose(){vm.cancel();(EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}.get(vm) as? Job)?.cancelAndJoin();vm.viewModelScope.cancel();bitmaps.forEach{if(!it.isRecycled)it.recycle()}}
    @Test fun exportKeepsPresentationAndQuickEditsThenRefinesOnlyLatestScience()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        val release=CompletableDeferred<Unit>();var export:Job?=null
        try {
            f=fixture();val h=f
            export=launch{ExportWorkAdmissions.run{release.await()}};runCurrent();assertTrue(ExportWorkAdmissions.active.value)
            h.vm.parameters(EditorState(brightness=.1f));waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            assertEquals(listOf(.1f to false),h.calls)
            var cancellations=0;h.vm.cancelPreviewEngine={cancellations++}
            repeat(100){i->h.vm.parameters(h.state.value.parameters.copy(
                crop=CropSettings(mode=if(i%2==0)CropMode.RATIO_3_2 else CropMode.RATIO_2_3),
                imprint=dev.filmroom.imprint.ImprintSettings(dateStyle="contax_t2",dateEnabled=true,manualDateTime="2026-11-05 12:00:00",size=.6+i*.01,corner=if(i%2==0)"bottom_right" else "bottom_left")
            ),true)}
            waitFor{h.state.value.renderedParameters==h.state.value.parameters && !h.state.value.previewRefining}
            assertEquals("presentation never enters native",listOf(.1f to false),h.calls)
            assertEquals("presentation never cancels science",0,cancellations)
            h.vm.parameters(h.state.value.parameters.copy(brightness=.2f));waitFor{h.state.value.renderedParameters?.brightness==.2f && !h.state.value.previewRefining}
            assertEquals(listOf(.1f to false,.2f to false),h.calls)
            release.complete(Unit);export.join()
            waitFor{h.state.value.compositionSource?.width==192 && !h.state.value.previewRefining}
            assertEquals(listOf(.1f to false,.2f to false,.2f to true),h.calls)
            assertEquals(h.state.value.parameters,h.state.value.renderedParameters)
        }finally{release.complete(Unit);export?.cancelAndJoin();f?.dispose();Dispatchers.resetMain()}
    }
    @Test fun sourceReplacementCannotResumeOldDeferredRefinementAndCancellationClearsDemand()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        val release=CompletableDeferred<Unit>();var export:Job?=null
        try {
            f=fixture();val h=f
            export=launch{ExportWorkAdmissions.run{release.await()}};runCurrent()
            h.vm.parameters(EditorState(brightness=.1f));waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            h.state.value=h.state.value.copy(photo=PhotoSource(File("new-export-aware-preview"),"new","image/jpeg",6000,4000,0))
            h.vm.parameters(EditorState(brightness=.2f));waitFor{h.state.value.renderedParameters?.brightness==.2f && !h.state.value.previewRefining}
            h.vm.cancel();release.complete(Unit);export.join();advanceUntilIdle()
            assertEquals(listOf(.1f to false,.2f to false),h.calls)
        }finally{release.complete(Unit);export?.cancelAndJoin();f?.dispose();Dispatchers.resetMain()}
    }
    @Test fun presentationCommitDoesNotCreateRefinementForUnsettledScience()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var f:Fixture?=null
        try {
            f=fixture();val h=f
            h.vm.parameters(EditorState(brightness=.1f),false);waitFor{h.state.value.canExport && !h.state.value.previewRefining}
            var cancellations=0;h.vm.cancelPreviewEngine={cancellations++}
            h.vm.parameters(h.state.value.parameters.copy(crop=CropSettings(mode=CropMode.RATIO_2_3)))
            waitFor{h.state.value.renderedParameters==h.state.value.parameters && !h.state.value.previewRefining}
            advanceUntilIdle()
            assertEquals(listOf(.1f to false),h.calls);assertEquals(0,cancellations)
        }finally{f?.dispose();Dispatchers.resetMain()}
    }

}
