package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PreviewSchedulingHostTest {
    @Suppress("UNCHECKED_CAST")
    private fun state(vm:EditorViewModel)=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
    private fun TestScope.setup():Pair<EditorViewModel,MutableStateFlow<EditorUiState>> {
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        val vm=EditorViewModel(app).apply{borderMemorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}}
        // Repository startup uses Dispatchers.IO. Finish it before injecting a photo,
        // otherwise it can see the test's newly saved session and try to reopen it.
        val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        repeat(500) {
            runCurrent()
            if((operation.get(vm) as? Job)?.isCompleted==true)return vm to state(vm)
            Thread.sleep(10)
        }
        error("startup did not finish")
    }
    private fun seed(s:MutableStateFlow<EditorUiState>){
        s.value=EditorUiState(photo=PhotoSource(File("unused"),"test","image/jpeg",6000,4000,0),original=Bitmap.createBitmap(768,512,Bitmap.Config.ARGB_8888))
    }
    @Test fun liveDisabledIdlePrewarmStillRefinesFilmAndParameterChanges()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);s.value.original!!.recycle();s.value=s.value.copy(original=Bitmap.createBitmap(1536,1024,Bitmap.Config.ARGB_8888));vm.detailPreviewEdge={1536}
            vm.setPreviewForeground(true);vm.setPrewarmForeground(false)
            val calls=mutableListOf<Pair<EditorState,Boolean>>()
            vm.renderPreview={source,p,_,detail->
                if(detail)assertEquals(1536,maxOf(source.width,source.height))
                calls+=p to detail
                Bitmap.createBitmap(if(detail)1536 else 512,if(detail)1024 else 341,Bitmap.Config.ARGB_8888)}
            val first=EditorState(brightness=.1f)
            vm.parameters(first);advanceUntilIdle()
            assertEquals(listOf(false,true),calls.map{it.second})
            val oldState=s.value
            val oldSnapshot=dev.filmroom.effects.HomeMotionSnapshot.capture(oldState,dev.filmroom.effects.EffectKind.LIGHT_TABLE)
            val film=first.copy(filmStock="kodak_ektar_100")
            vm.parameters(film);advanceUntilIdle()
            val adjusted=film.copy(brightness=.4f)
            vm.parameters(adjusted);advanceUntilIdle()
            assertEquals(listOf(false,true,false,true,false,true),calls.map{it.second})
            assertEquals(listOf(first,first,film,film,adjusted,adjusted),calls.map{it.first})
            assertEquals(adjusted,s.value.renderedParameters);assertEquals(1536,s.value.rendered!!.width)
            assertTrue(dev.filmroom.effects.HomeMotionSnapshot.canRetain(oldState,s.value))
            assertFalse(dev.filmroom.effects.HomeMotionSnapshot.matches(oldState,s.value))
            dev.filmroom.effects.HomeMotionSnapshot.capture(s.value,dev.filmroom.effects.EffectKind.LIGHT_TABLE).use{fresh->
                assertEquals(1536,fresh.result.width);assertNotSame(oldSnapshot.result,fresh.result)
            }
            oldSnapshot.close();vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun livePrewarmDisabledDoesNotAdoptLateOldRefinement()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);s.value.original!!.recycle();s.value=s.value.copy(original=Bitmap.createBitmap(1536,1024,Bitmap.Config.ARGB_8888));vm.detailPreviewEdge={1536};vm.setPrewarmForeground(false)
            val calls=mutableListOf<Pair<Float,Boolean>>();var stale:Bitmap?=null;var active=0;var peak=0
            vm.cancelPreviewEngine={}
            vm.renderPreview={source,p,_,detail->
                if(detail)assertEquals(1536,maxOf(source.width,source.height))
                active++;peak=maxOf(peak,active);calls+=p.brightness to detail
                try {
                    if(detail && p.brightness==.1f)withContext(NonCancellable){delay(200)}
                    Bitmap.createBitmap(if(detail)1536 else 512,1,Bitmap.Config.ARGB_8888).also{if(detail && p.brightness==.1f)stale=it}
                }finally{active--}
            }
            vm.parameters(EditorState(brightness=.1f))
            advanceTimeBy(EditorViewModel.SCIENCE_DEBOUNCE_MS+EditorViewModel.REFINEMENT_SETTLE_MS+1);runCurrent()
            assertEquals(listOf(.1f to false,.1f to true),calls)
            vm.parameters(EditorState(brightness=.7f));advanceUntilIdle()
            assertEquals(listOf(.1f to false,.1f to true,.7f to false,.7f to true),calls)
            assertEquals(1,peak);assertTrue(stale!!.isRecycled)
            assertEquals(.7f,s.value.renderedParameters!!.brightness,0f);assertEquals(1536,s.value.rendered!!.width)
            vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun livePreviewForegroundResumesWhileIdlePrewarmingRemainsDisabled()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);s.value.original!!.recycle();s.value=s.value.copy(original=Bitmap.createBitmap(1536,1024,Bitmap.Config.ARGB_8888));vm.detailPreviewEdge={1536};vm.setPrewarmForeground(false)
            val calls=mutableListOf<Boolean>()
            vm.renderPreview={source,_,_,detail->
                if(detail)assertEquals(1536,maxOf(source.width,source.height))
                calls+=detail;delay(100)
                Bitmap.createBitmap(if(detail)1536 else 512,1,Bitmap.Config.ARGB_8888)}
            vm.parameters(EditorState(brightness=.2f));advanceTimeBy(EditorViewModel.SCIENCE_DEBOUNCE_MS+1);runCurrent()
            vm.setPreviewForeground(false);advanceUntilIdle()
            assertEquals(listOf(false),calls);assertEquals(512,s.value.rendered!!.width)
            // Resuming the visible Live cover must not need to re-enable speculative work.
            vm.setPreviewForeground(true);advanceUntilIdle()
            assertEquals(listOf(false,true),calls);assertEquals(1536,s.value.rendered!!.width)
            vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun continuousInputKeepsFirstDeadlineAndOnlyLatestEntersScience()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);val calls=mutableListOf<Float>()
            vm.renderPreview={_,p,_,_->calls+=p.brightness;Bitmap.createBitmap(512,1,Bitmap.Config.ARGB_8888)}
            vm.parameters(EditorState(brightness=.1f),commit=false);runCurrent()
            repeat(5){advanceTimeBy(10);vm.parameters(EditorState(brightness=.2f+it*.1f),commit=false);runCurrent()}
            assertTrue(calls.isEmpty())
            advanceTimeBy(15);runCurrent()
            assertEquals(1,calls.size);assertEquals(.6f,calls.single(),.0001f)
            assertEquals(s.value.parameters,s.value.renderedParameters);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun sustainedDragMakesProgressWithoutRefiningIntermediateStates()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);val starts=mutableListOf<Long>();val details=mutableListOf<Boolean>()
            vm.renderPreview={_,_,_,detail->starts+=testScheduler.currentTime;details+=detail;delay(20);Bitmap.createBitmap(512,1,Bitmap.Config.ARGB_8888)}
            repeat(40){vm.parameters(EditorState(brightness=it*.01f),commit=false);advanceTimeBy(10);runCurrent()}
            assertTrue("Continuous input must not postpone all native starts until pointer-up",starts.size>=3)
            assertTrue(starts.zipWithNext().all{(a,b)->b-a>=EditorViewModel.SCIENCE_DEBOUNCE_MS})
            assertTrue(details.none{it});advanceUntilIdle()
            assertEquals(s.value.parameters,s.value.renderedParameters);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun slowFlightIsNotCancelledAndMailboxRetainsOnlyLatest()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);val calls=mutableListOf<Pair<Float,Boolean>>()
            var active=0;var peak=0;var cancelled=0;var old:Bitmap?=null
            vm.cancelPreviewEngine={cancelled++};vm.detailPreviewEdge={768}
            vm.renderPreview={_,p,_,detail->
                calls+=p.brightness to detail;active++;peak=maxOf(peak,active)
                try {delay(200);Bitmap.createBitmap(if(detail)768 else 512,1,Bitmap.Config.ARGB_8888).also{if(p.brightness==.1f)old=it}}
                finally {active--}
            }
            vm.parameters(EditorState(brightness=.1f),commit=false);advanceTimeBy(65);runCurrent()
            val before=cancelled
            repeat(8){vm.parameters(EditorState(brightness=.2f+it*.05f),commit=false);advanceTimeBy(10);runCurrent()}
            val latest=EditorState(brightness=.9f);vm.parameters(latest,commit=true)
            assertEquals(before,cancelled);assertNull(s.value.rendered)
            advanceUntilIdle()
            assertEquals(listOf(.1f to false,.9f to false,.9f to true),calls)
            assertEquals(1,peak);assertTrue(old!!.isRecycled);assertEquals(latest,s.value.renderedParameters)
            assertEquals(768,s.value.rendered!!.width);assertFalse(s.value.previewRefining);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun newScienceCancelsOnlyRefinementAndWaitsForLateReleaseBeforeLatestQuick()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);vm.detailPreviewEdge={768}
            val calls=mutableListOf<Pair<Float,Boolean>>()
            var active=0;var peak=0;var cancels=0;var stale:Bitmap?=null
            vm.cancelPreviewEngine={cancels++}
            vm.renderPreview={_,p,_,detail->
                active++;peak=maxOf(peak,active);calls+=p.brightness to detail
                try {
                    if(detail)withContext(NonCancellable){delay(200)}
                    Bitmap.createBitmap(if(detail)1536 else 512,1,Bitmap.Config.ARGB_8888).also{if(detail)stale=it}
                }finally{active--}
            }
            vm.parameters(EditorState(brightness=.1f));advanceTimeBy(EditorViewModel.SCIENCE_DEBOUNCE_MS+EditorViewModel.REFINEMENT_SETTLE_MS+1);runCurrent()
            assertEquals(listOf(.1f to false,.1f to true),calls)
            val previous=checkNotNull(s.value.rendered);assertEquals(512,previous.width)
            val before=cancels
            repeat(5){vm.parameters(EditorState(brightness=.2f+it*.1f),commit=false);advanceTimeBy(10);runCurrent()}
            assertEquals(before+1,cancels);assertSame(previous,s.value.rendered);assertFalse(previous.isRecycled)
            assertEquals(2,calls.size) // No overlap while cancelled native work still owns resources.
            advanceUntilIdle()
            assertEquals(1,peak);assertEquals(listOf(.1f to false,.1f to true,.6f to false),calls)
            assertTrue(stale!!.isRecycled);assertEquals(.6f,s.value.renderedParameters!!.brightness,.0001f)
            assertEquals(512,s.value.rendered!!.width);assertFalse(s.value.previewRefining);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }

    @Test fun closingWhilePreemptedRefinementDrainsCannotRestartQueuedQuick()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);vm.detailPreviewEdge={768}
            val calls=mutableListOf<Boolean>();var stale:Bitmap?=null
            vm.cancelPreviewEngine={}
            vm.renderPreview={_,_,_,detail->calls+=detail
                if(detail)withContext(NonCancellable){delay(200)}
                Bitmap.createBitmap(if(detail)1536 else 512,1,Bitmap.Config.ARGB_8888).also{if(detail)stale=it}
            }
            vm.parameters(EditorState(brightness=.1f));advanceTimeBy(EditorViewModel.SCIENCE_DEBOUNCE_MS+EditorViewModel.REFINEMENT_SETTLE_MS+1);runCurrent()
            vm.parameters(EditorState(brightness=.2f),commit=false);runCurrent()
            EditorViewModel::class.java.getDeclaredMethod("onCleared").apply{isAccessible=true}.invoke(vm)
            advanceUntilIdle();assertEquals(listOf(false,true),calls);assertTrue(stale!!.isRecycled)
            vm.retry();advanceUntilIdle();assertEquals(2,calls.size)
        }finally{Dispatchers.resetMain()}
    }

    @Test fun sameSciencePresentationDoesNotCancelRefinement()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);vm.detailPreviewEdge={768}
            val calls=mutableListOf<Boolean>();var cancels=0
            vm.cancelPreviewEngine={cancels++}
            vm.renderPreview={_,_,_,detail->calls+=detail;if(detail)delay(200)
                Bitmap.createBitmap(if(detail)1536 else 512,1,Bitmap.Config.ARGB_8888)}
            val original=EditorState(brightness=.1f)
            vm.parameters(original);advanceTimeBy(EditorViewModel.SCIENCE_DEBOUNCE_MS+EditorViewModel.REFINEMENT_SETTLE_MS+1);runCurrent()
            val before=cancels
            vm.parameters(original);vm.parameters(original.copy(negativeWhiteHoles=true))
            advanceUntilIdle()
            assertEquals(before,cancels);assertEquals(listOf(false,true),calls)
            assertEquals(1536,s.value.rendered!!.width);assertTrue(s.value.renderedParameters!!.negativeWhiteHoles);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }

    @Test fun backgroundRetiresRefinementAndForegroundResumesOnlyAfterRelease()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);vm.detailPreviewEdge={768}
            val calls=mutableListOf<Boolean>();var stale:Bitmap?=null;var active=0;var peak=0
            vm.cancelPreviewEngine={}
            vm.renderPreview={_,_,_,detail->calls+=detail;active++;peak=maxOf(peak,active)
                try {
                    if(detail && calls.size==2)withContext(NonCancellable){delay(200)}
                    Bitmap.createBitmap(if(detail)1536 else 512,1,Bitmap.Config.ARGB_8888).also{if(calls.size==2)stale=it}
                }finally{active--}}
            vm.parameters(EditorState(brightness=.1f));advanceTimeBy(EditorViewModel.SCIENCE_DEBOUNCE_MS+EditorViewModel.REFINEMENT_SETTLE_MS+1);runCurrent()
            val previous=s.value.rendered
            vm.processingScreenLeft();advanceTimeBy(20);runCurrent()
            assertSame(previous,s.value.rendered);assertEquals(listOf(false,true),calls)
            // Returning before the cancelled JNI call drains must not overlap it.
            vm.setPreviewForeground(true);runCurrent();assertEquals(2,calls.size)
            advanceUntilIdle()
            assertTrue(stale!!.isRecycled);assertEquals(1,peak);assertEquals(listOf(false,true,true),calls)
            assertEquals(1536,s.value.rendered!!.width);assertFalse(s.value.previewRefining);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }

    @Test fun backgroundAllowsQuickToFinishButDefersRefinementUntilForeground()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);vm.detailPreviewEdge={768}
            val calls=mutableListOf<Boolean>();var cancels=0
            vm.cancelPreviewEngine={cancels++}
            vm.renderPreview={_,_,_,detail->calls+=detail;delay(200)
                Bitmap.createBitmap(if(detail)1536 else 512,1,Bitmap.Config.ARGB_8888)}
            vm.parameters(EditorState(brightness=.1f));advanceTimeBy(EditorViewModel.SCIENCE_DEBOUNCE_MS+1);runCurrent()
            val before=cancels;vm.processingScreenLeft();advanceUntilIdle()
            assertEquals(before,cancels);assertEquals(listOf(false),calls);assertEquals(512,s.value.rendered!!.width)
            assertFalse(s.value.previewRefining);assertTrue(s.value.canExport)
            vm.setPreviewForeground(true);advanceUntilIdle()
            assertEquals(listOf(false,true),calls);assertEquals(1536,s.value.rendered!!.width);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }

    @Test fun failedObsoleteFlightStillDrainsLatest()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);val calls=mutableListOf<Float>()
            vm.renderPreview={_,p,_,_->calls+=p.brightness;delay(100);if(p.brightness==.1f)error("old failure");Bitmap.createBitmap(512,1,Bitmap.Config.ARGB_8888)}
            vm.parameters(EditorState(brightness=.1f),commit=false);advanceTimeBy(65);runCurrent()
            vm.parameters(EditorState(brightness=.7f),commit=false);advanceUntilIdle()
            assertEquals(listOf(.1f,.7f),calls);assertEquals(.7f,s.value.renderedParameters!!.brightness,0f)
            assertNull(s.value.message);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun cancelDropsMailboxAndRecyclesLateFrame()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);var calls=0;var stale:Bitmap?=null;var cancels=0
            vm.cancelPreviewEngine={cancels++}
            vm.renderPreview={_,_,_,_->calls++;withContext(NonCancellable){delay(200)};Bitmap.createBitmap(512,1,Bitmap.Config.ARGB_8888).also{stale=it}}
            vm.parameters(EditorState(brightness=.1f),commit=false);advanceTimeBy(65);runCurrent()
            vm.parameters(EditorState(brightness=.7f),commit=false);val before=cancels;vm.cancel();advanceUntilIdle()
            assertEquals(before+1,cancels);assertEquals(1,calls);assertTrue(stale!!.isRecycled);assertNull(s.value.rendered)
        }finally{Dispatchers.resetMain()}
    }
    @Test fun replacingSourceNeverDrainsOldMailbox()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);var calls=0;var stale:Bitmap?=null
            vm.renderPreview={_,_,_,_->calls++;delay(200);Bitmap.createBitmap(512,1,Bitmap.Config.ARGB_8888).also{stale=it}}
            vm.parameters(EditorState(brightness=.1f),commit=false);advanceTimeBy(65);runCurrent()
            vm.parameters(EditorState(brightness=.7f),commit=false);seed(s);advanceUntilIdle()
            assertEquals(1,calls);assertTrue(stale!!.isRecycled);assertNull(s.value.rendered);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun clearingOwnerCancelsAndCannotResurrectMailbox()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();seed(s);var calls=0;var stale:Bitmap?=null
            vm.renderPreview={_,_,_,_->calls++;withContext(NonCancellable){delay(200)};Bitmap.createBitmap(512,1,Bitmap.Config.ARGB_8888).also{stale=it}}
            vm.parameters(EditorState(brightness=.1f),commit=false);advanceTimeBy(65);runCurrent()
            vm.parameters(EditorState(brightness=.7f),commit=false)
            EditorViewModel::class.java.getDeclaredMethod("onCleared").apply{isAccessible=true}.invoke(vm)
            advanceUntilIdle();assertEquals(1,calls);assertTrue(stale!!.isRecycled);assertNull(s.value.rendered)
            vm.retry();advanceUntilIdle();assertEquals(1,calls)
        }finally{Dispatchers.resetMain()}
    }

}
