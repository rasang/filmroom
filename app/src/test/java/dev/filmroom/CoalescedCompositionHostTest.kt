package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import dev.filmroom.border.*
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** MOVE is coordinates-only; exact shared composition is a commit operation, never a drag loop. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class CoalescedCompositionHostTest {
    private class Fixture:BorderRaster {
        override val stock="kodak_portra_400";override val version="drag-fixture"
        override val railRatio=.2;override val endRatio=.03;override val residentBytes=12L;override val featherTotalAt3072=4.0
        val blockNext=AtomicBoolean(false);val blockTopLeftVisit=AtomicInteger(0);val failNext=AtomicBoolean(false)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){
            if(failNext.compareAndSet(true,false))error("Injected presentation failure")
            val selectedVisit=x==.5 && y==.5 && blockTopLeftVisit.get()>0 && blockTopLeftVisit.decrementAndGet()==0
            if(blockNext.compareAndSet(true,false) || selectedVisit){entered.countDown();check(release.await(10,TimeUnit.SECONDS))}
            rgb.fill(.2f)
        }
    }
    private class Harness(val model:EditorViewModel,val state:MutableStateFlow<EditorUiState>,val raster:Fixture){var scienceCalls=0}
    private suspend fun dispose(h:Harness?) {
        if(h==null)return
        h.raster.release.countDown();h.model.cancel()
        val render=EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}.get(h.model) as? Job
        render?.cancelAndJoin();h.model.viewModelScope.cancel()
        val state=h.state.value
        listOfNotNull(state.original,state.rendered,state.compositionSource,state.interactiveBorder).distinct().forEach{if(!it.isRecycled)it.recycle()}
    }
    private fun TestScope.waitFor(check:()->Boolean){repeat(1000){advanceUntilIdle();runCurrent();if(check())return;Thread.sleep(5)};error("Timed out waiting for bounded preview work")}
    @Suppress("UNCHECKED_CAST") private suspend fun TestScope.prepare():Harness {
        val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("local-session",0).edit().clear().commit()
        val model=EditorViewModel(app)
        model.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
        val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
        waitFor{(operation.get(model) as? Job)?.isCompleted==true}
        val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(model) as MutableStateFlow<EditorUiState>
        state.value=EditorUiState(photo=PhotoSource(File("unused"),"fixture","image/jpeg",96,64,0),original=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888))
        val raster=Fixture();val h=Harness(model,state,raster)
        model.detailPreviewEdge={96}
        model.renderPreview={_,_,_,_->h.scienceCalls++;Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{
            for(y in 0 until height)for(x in 0 until width)setPixel(x,y,android.graphics.Color.rgb(x*255/width,y*255/height,90))
        }}
        model.borderProvider=object:BorderProvider {
            override fun snapshot(stock:String,width:Int,height:Int)=BorderSnapshot.fixed135(stock,raster.version,width,height)
            override fun resolve(snapshot:BorderSnapshot)=raster
        }
        model.parameters(EditorState(filmBorder=true))
        try {
            waitFor{
                val ui=state.value
                if(ui.message!=null && !ui.updating && !ui.previewRefining){
                    val runtime=Runtime.getRuntime()
                    error("Fixture presentation failed: message=${ui.message}; science=${h.scienceCalls}; exact=${model.exactBorderCompositionCalls}; overlays=${model.interactiveBorderBuildCalls}; heapAvailable=${runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory()}; state=$ui")
                }
                ui.canExport && !ui.previewRefining
            }
            return h
        }catch(t:Throwable){dispose(h);throw t}
    }
    @Test fun hundredsOfMovesDoZeroFullCompositionAndCommitExactlyOnce()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        try {
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            m.setCompositionEditing(true)
            val before=state.value;val exact=m.exactBorderCompositionCalls;val layers=m.interactiveBorderBuildCalls
            var final=CompositionTransform()
            repeat(200){i->
                final=CompositionTransform(.55,.45,1.2+i*.005).clamped(96,64)
                m.composition(final,false);advanceUntilIdle()
                assertEquals(exact,m.exactBorderCompositionCalls);assertEquals(layers,m.interactiveBorderBuildCalls)
                assertSame(before.rendered,state.value.rendered);assertSame(before.interactiveBorder,state.value.interactiveBorder)
                assertSame(before.compositionSource,state.value.compositionSource)
            }
            assertEquals(1,fixture.scienceCalls);assertEquals(final,state.value.parameters.composition);assertFalse(state.value.canExport)
            m.composition(final,true);m.setCompositionEditing(false)
            waitFor{state.value.canExport}
            assertEquals(exact+1,m.exactBorderCompositionCalls);assertEquals(layers,m.interactiveBorderBuildCalls)
            assertEquals(1,fixture.scienceCalls);assertSame(before.compositionSource,state.value.compositionSource)
            assertEquals(final,state.value.presentation!!.composition);assertFalse(state.value.compositionEditing)
            val expected=BorderPreview.compose(checkNotNull(before.compositionSource),checkNotNull(state.value.presentation),fixture.raster)
            try {assertTrue(expected.sameAs(state.value.rendered))}finally{expected.recycle()}
        }finally{dispose(h);Dispatchers.resetMain()}
    }
    @Test fun reDragCancelsOldCommitAndLockPublishesOnlyNewestCrop()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        try {
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            val before=state.value;val exact=m.exactBorderCompositionCalls;val layers=m.interactiveBorderBuildCalls
            fixture.raster.blockNext.set(true)
            m.composition(CompositionTransform(.5,.5,1.5),true);waitFor{fixture.raster.entered.count==0L}
            assertEquals(exact+1,m.exactBorderCompositionCalls)
            val oldJob=EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}.get(m) as Job
            val newest=CompositionTransform(.6,.4,2.5)
            m.setCompositionEditing(true);repeat(50){m.composition(newest,false)}
            assertEquals(exact+1,m.exactBorderCompositionCalls);assertSame(before.rendered,state.value.rendered)
            fixture.raster.release.countDown();waitFor{oldJob.isCompleted}
            assertSame(before.rendered,state.value.rendered);assertEquals(newest,state.value.parameters.composition)
            m.composition(newest,true);m.setCompositionEditing(false);waitFor{state.value.canExport}
            assertEquals(exact+2,m.exactBorderCompositionCalls);assertEquals(layers,m.interactiveBorderBuildCalls)
            assertEquals(newest,state.value.presentation!!.composition);assertEquals(1,fixture.scienceCalls)
            assertSame(before.compositionSource,state.value.compositionSource);assertFalse(state.value.compositionEditing)
        }finally{dispose(h);Dispatchers.resetMain()}
    }
    @Test fun draftPointerUpNeverChangesHistoryAndCancelRestoresThenConfirmMakesOneUndoStep()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        try {
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            val baseline=state.value.parameters
            val history=EditorViewModel::class.java.getDeclaredField("history").apply{isAccessible=true}.get(m) as EditHistory
            val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("local-session",0)
            val saved=prefs.getString("parameters",null)
            m.beginComposition();val draft=CompositionTransform(.6,.4,2.0)
            repeat(20){m.composition(draft,false)};m.composition(draft,true)
            waitFor{state.value.renderedParameters==state.value.parameters}
            assertFalse(state.value.canExport);assertEquals(baseline,history.current);assertEquals(saved,prefs.getString("parameters",null))
            m.cancelComposition();waitFor{state.value.canExport}
            assertEquals(baseline,state.value.parameters);assertEquals(baseline,history.current)
            m.beginComposition();m.composition(draft,false);m.composition(draft,true)
            waitFor{state.value.renderedParameters==state.value.parameters};m.confirmComposition();waitFor{state.value.canExport}
            assertEquals(draft,history.current.composition);assertEquals(1,fixture.scienceCalls)
            m.undo();waitFor{state.value.canExport};assertEquals(baseline,state.value.parameters)
        }finally{dispose(h);Dispatchers.resetMain()}
    }

    @Test fun failedImportPreservesDraftBaselineCommittedPhotoAndSingleHistoryStep()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        try {
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            val committed=state.value;val baseline=committed.parameters
            val history=EditorViewModel::class.java.getDeclaredField("history").apply{isAccessible=true}.get(m) as EditHistory
            val operationField=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            m.beginComposition();val draft=CompositionTransform(.6,.4,2.0)
            m.composition(draft,false);m.composition(draft,true)
            waitFor{state.value.renderedParameters==state.value.parameters}
            assertEquals(baseline,history.current)
            // Real repository failure, not a fake success or direct baseline mutation.
            m.importPhoto(android.net.Uri.parse("content://dev.filmroom.unresolvable-crop-test/no-provider.jpg"))
            val attempt=operationField.get(m) as Job
            waitFor{attempt.isCompleted && state.value.busy==null}
            assertNotNull(state.value.message)
            assertSame(committed.photo,state.value.photo);assertSame(committed.original,state.value.original)
            assertSame(committed.compositionSource,state.value.compositionSource)
            assertFalse(checkNotNull(committed.original).isRecycled)
            assertTrue(state.value.compositionEditing);assertEquals(draft,state.value.parameters.composition)
            assertEquals(baseline,history.current)
            m.cancelComposition();waitFor{state.value.canExport}
            assertEquals(baseline,state.value.parameters);assertEquals(baseline,history.current)
            assertSame(committed.photo,state.value.photo);assertEquals(1,fixture.scienceCalls)
            // Only the original border-enable history step exists; the failed import/draft
            // and its Cancel must not insert a phantom edit or reset the history.
            m.undo();waitFor{state.value.canExport}
            assertEquals(EditorState(),state.value.parameters);assertFalse(state.value.canUndo)
            assertSame(committed.photo,state.value.photo)
        }finally{dispose(h);Dispatchers.resetMain()}
    }
    @Test fun busyCompositionCommitIsIgnoredWithoutHistoryOrPresentationWork()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        try {
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            m.beginComposition();val baseline=state.value.parameters
            val history=EditorViewModel::class.java.getDeclaredField("history").apply{isAccessible=true}.get(m) as EditHistory
            val exact=m.exactBorderCompositionCalls;val layers=m.interactiveBorderBuildCalls
            state.value=state.value.copy(busy="读取照片")
            m.composition(CompositionTransform(.6,.4,3.0),true);advanceUntilIdle()
            assertEquals(baseline,state.value.parameters);assertEquals(baseline,history.current)
            assertEquals(exact,m.exactBorderCompositionCalls);assertEquals(layers,m.interactiveBorderBuildCalls)
            assertEquals(1,fixture.scienceCalls)
            state.value=state.value.copy(busy=null);m.cancelComposition();waitFor{state.value.canExport}
            assertEquals(baseline,state.value.parameters)
        }finally{dispose(h);Dispatchers.resetMain()}
    }

    @Test fun moveUpPresetAndConfirmDuringScientificDetailNeverCancelOrRestartScience()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        try {
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            val original=Bitmap.createBitmap(192,128,Bitmap.Config.ARGB_8888)
            val replacedOriginal=state.value.original
            state.value=state.value.copy(original=original)
            replacedOriginal?.takeUnless{it===state.value.compositionSource || it.isRecycled}?.recycle()
            m.detailPreviewEdge={192}
            val calls=mutableListOf<Boolean>();var cancellations=0;var detailed:Bitmap?=null
            m.renderPreview={_,_,_,detail->
                calls+=detail
                if(detail){
                    entered.complete(Unit)
                    try {release.await()}catch(c:CancellationException){cancellations++;throw c}
                }
                Bitmap.createBitmap(if(detail)192 else 96,if(detail)128 else 64,Bitmap.Config.ARGB_8888).also{if(detail)detailed=it}
            }
            m.parameters(state.value.parameters.copy(brightness=.2f));waitFor{entered.isCompleted}
            val quick=checkNotNull(state.value.compositionSource)
            assertEquals(96,quick.width);assertEquals(listOf(false,true),calls)
            m.beginComposition()
            val drag=CompositionTransform(.6,.4,2.0)
            repeat(40){m.composition(drag,false)};m.composition(drag,true)
            m.chooseCompositionMode(CropMode.RATIO_2_3)
            m.chooseCompositionMode(CropMode.RATIO_3_2)
            val expected=state.value.parameters
            m.confirmComposition();runCurrent()
            assertEquals(listOf(false,true),calls);assertEquals(0,cancellations)
            assertFalse(original.isRecycled);assertFalse(quick.isRecycled)
            release.complete(Unit)
            waitFor{state.value.canExport && !state.value.previewRefining && state.value.compositionSource?.width==192}
            assertEquals(listOf(false,true),calls);assertEquals(0,cancellations)
            assertEquals(expected,state.value.renderedParameters)
            assertEquals(expected.composition,state.value.presentation!!.composition)
            assertSame(detailed,state.value.compositionSource)
            assertFalse(original.isRecycled);assertFalse(checkNotNull(detailed).isRecycled)
        }finally{release.complete(Unit);dispose(h);Dispatchers.resetMain()}
    }
    @Test fun rapidLandscapePortraitLandscapeNeverPublishesBlockedObsoleteGeometry()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        var collector:Job?=null
        try {
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            m.parameters(state.value.parameters.copy(composition=CompositionTransform(targetAspect=1.5)))
            waitFor{state.value.canExport && !state.value.previewRefining}
            val baseline=state.value;val published=mutableListOf<EditorState>()
            collector=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){
                state.collect{it.renderedParameters?.let(published::add)}
            }
            m.beginComposition()
            // A new portrait geometry now builds overlay + exact image in one traversal.
            fixture.raster.blockTopLeftVisit.set(1)
            m.chooseCompositionMode(CropMode.RATIO_2_3);waitFor{fixture.raster.entered.count==0L}
            val blockedJob=EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}.get(m) as Job
            assertSame(baseline.rendered,state.value.rendered)
            m.chooseCompositionMode(CropMode.RATIO_3_2);m.confirmComposition()
            assertSame(baseline.rendered,state.value.rendered)
            fixture.raster.release.countDown();waitFor{blockedJob.isCompleted && state.value.canExport}
            assertTrue("Obsolete portrait presentation was published",published.none{it.composition.targetAspect==2.0/3.0})
            assertEquals(1.5,state.value.presentation!!.composition.targetAspect!!,0.0)
            assertEquals(state.value.parameters,state.value.renderedParameters)
            assertSame(baseline.compositionSource,state.value.compositionSource)
            assertFalse(checkNotNull(baseline.compositionSource).isRecycled)
            assertEquals(1,fixture.scienceCalls)
        }finally{collector?.cancel();dispose(h);Dispatchers.resetMain()}
    }

    @Test fun cropEditsAfterDetailReturnsButBeforePresentationAdoptionRetainSameScientificResult()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        try {
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            val original=Bitmap.createBitmap(192,128,Bitmap.Config.ARGB_8888)
            val replacedOriginal=state.value.original
            state.value=state.value.copy(original=original)
            replacedOriginal?.takeUnless{it===state.value.compositionSource || it.isRecycled}?.recycle()
            m.detailPreviewEdge={192}
            val calls=mutableListOf<Boolean>();var detailed:Bitmap?=null
            m.renderPreview={_,_,_,detail->
                calls+=detail
                Bitmap.createBitmap(if(detail)192 else 96,if(detail)128 else 64,Bitmap.Config.ARGB_8888).also{
                    if(detail){detailed=it;fixture.raster.blockNext.set(true)}
                }
            }
            m.parameters(state.value.parameters.copy(brightness=.3f))
            waitFor{fixture.raster.entered.count==0L}
            // renderPreview(detail=true) has RETURNED, but its new overlay/exact composite
            // is blocked before adoption. This is distinct from blocking inside native science.
            val result=checkNotNull(detailed);val quick=checkNotNull(state.value.compositionSource)
            assertEquals(192,result.width);assertEquals(96,quick.width)
            assertEquals(listOf(false,true),calls);assertFalse(result.isRecycled)
            m.beginComposition()
            val drag=CompositionTransform(.6,.4,2.0)
            repeat(30){m.composition(drag,false)};m.composition(drag,true)
            m.chooseCompositionMode(CropMode.RATIO_2_3);m.chooseCompositionMode(CropMode.RATIO_3_2)
            val expected=state.value.parameters;m.confirmComposition();runCurrent()
            assertEquals(listOf(false,true),calls);assertFalse(result.isRecycled)
            fixture.raster.release.countDown()
            waitFor{state.value.canExport && !state.value.previewRefining && state.value.compositionSource===result}
            assertEquals(listOf(false,true),calls)
            assertSame(result,state.value.compositionSource);assertFalse(result.isRecycled)
            assertFalse(original.isRecycled);assertFalse(quick.isRecycled)
            assertEquals(expected,state.value.renderedParameters)
            assertEquals(expected.composition,state.value.presentation!!.composition)
        }finally{dispose(h);Dispatchers.resetMain()}
    }

    @Test fun whiteHolePreferenceRecomposesCachedScienceThroughSevenArgumentProvider()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        try{
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            val source=state.value.compositionSource;val science=fixture.scienceCalls
            val requested=mutableListOf<Boolean>()
            fun raster(white:Boolean)=object:BorderRaster{
                override val stock=fixture.raster.stock
                override val railRatio=fixture.raster.railRatio;override val endRatio=fixture.raster.endRatio
                override val residentBytes=fixture.raster.residentBytes;override val featherTotalAt3072=fixture.raster.featherTotalAt3072
                override val version=if(white)"fixture-white" else "fixture-black"
                override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb.fill(if(white)1f else 0f)}
            }
            m.borderProvider=object:BorderProvider{
                override fun snapshot(stock:String,width:Int,height:Int):BorderSnapshot=error("Must route presentation preference")
                override fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:CompositionTransform,negativeWhiteHoles:Boolean):BorderSnapshot{
                    requested+=negativeWhiteHoles
                    return BorderSnapshot.fixed135(stock,raster(negativeWhiteHoles).version,width,height,canonicalWidth,canonicalHeight,composition)
                }
                override fun resolve(snapshot:BorderSnapshot)=raster(snapshot.assetVersion=="fixture-white")
            }
            var darkPixel:Int?=null
            for(white in listOf(false,true)){
                m.parameters(state.value.parameters.copy(negativeWhiteHoles=white))
                waitFor{
                    check(state.value.message==null){"Preference fixture failed: ${state.value.message}"}
                    state.value.canExport && state.value.renderedParameters?.negativeWhiteHoles==white
                }
                assertEquals(science,fixture.scienceCalls);assertSame(source,state.value.compositionSource)
                assertEquals(white,requested.last())
                assertEquals(if(white)"fixture-white" else "fixture-black",state.value.presentation!!.assetVersion)
                val pixel=state.value.rendered!!.getPixel(0,0)
                if(!white)darkPixel=pixel else assertNotEquals(darkPixel,pixel)
                assertEquals(state.value.parameters,state.value.renderedParameters)
            }
        }finally{dispose(h);Dispatchers.resetMain()}
    }

    @Test fun failedFusedPresentationRetainsDisplayedBitmapsAndRetryUsesScienceCache()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        try {
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            val previous=state.value;val science=fixture.scienceCalls
            fixture.raster.failNext.set(true)
            m.parameters(state.value.parameters.copy(composition=CompositionTransform(targetAspect=2.0/3.0)))
            waitFor{state.value.message!=null && !state.value.previewRefining}
            assertSame(previous.rendered,state.value.rendered)
            assertSame(previous.interactiveBorder,state.value.interactiveBorder)
            assertSame(previous.compositionSource,state.value.compositionSource)
            assertFalse(checkNotNull(previous.rendered).isRecycled)
            assertFalse(checkNotNull(previous.interactiveBorder).isRecycled)
            m.retry();waitFor{state.value.canExport && !state.value.previewRefining}
            assertNull(state.value.message);assertEquals(science,fixture.scienceCalls)
            assertSame(previous.compositionSource,state.value.compositionSource)
            assertNotSame(previous.interactiveBorder,state.value.interactiveBorder)
            assertFalse(checkNotNull(previous.interactiveBorder).isRecycled)
            assertEquals(state.value.parameters,state.value.renderedParameters)
        }finally{dispose(h);Dispatchers.resetMain()}
    }

    @Test fun routeContextSeparatesOverlayWhileMatteRetainsScientificBitmap()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler));var h:Harness?=null
        try {
            val fixture=prepare();h=fixture;val m=fixture.model;val state=fixture.state
            val requested=mutableListOf<FrameAppearanceContext>()
            fun raster(s:BorderSnapshot)=object:BorderRaster {
                override val stock=s.stock;override val version=s.assetVersion
                override val railRatio=.2;override val endRatio=.03;override val residentBytes=12L;override val featherTotalAt3072=4.0
                override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb.fill(if(s.appearance!!.material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION).65f else .15f)}
            }
            m.borderProvider=object:BorderProvider {
                override fun snapshot(stock:String,width:Int,height:Int):BorderSnapshot=error("Must capture route")
                override fun snapshot(stock:String,width:Int,height:Int,canonicalWidth:Int,canonicalHeight:Int,composition:CompositionTransform,negativeWhiteHoles:Boolean,outputRoute:String):BorderSnapshot {
                    val c=FrameAppearanceContext.forProfile(stock,outputRoute,negativeWhiteHoles);requested+=c
                    return BorderSnapshot.fixed135(stock,"fixture-$outputRoute-$negativeWhiteHoles",width,height,canonicalWidth,canonicalHeight,composition).copy(appearance=c)
                }
                override fun resolve(snapshot:BorderSnapshot)=raster(snapshot)
            }
            var directSource:Bitmap?=null;var directCalls=0
            for((route,white)in listOf("direct" to false,"direct" to true,"print" to true,"direct" to true)){
                m.parameters(state.value.parameters.copy(outputRoute=route,negativeWhiteHoles=white))
                waitFor{check(state.value.message==null){state.value.message!!};state.value.canExport && state.value.renderedParameters==state.value.parameters}
                val expected=FrameAppearanceContext.forProfile(state.value.parameters.filmStock,route,white)
                assertEquals(expected,requested.last());assertEquals(expected,state.value.presentation!!.appearance)
                assertEquals("fixture-$route-$white",state.value.presentation!!.assetVersion)
                assertEquals(state.value.rendered!!.getPixel(0,0),state.value.interactiveBorder!!.getPixel(0,0))
                if(route=="direct" && !white){directSource=state.value.compositionSource;directCalls=fixture.scienceCalls}
                if(route=="direct" && white && directSource!=null){assertSame(directSource,state.value.compositionSource);assertEquals(directCalls,fixture.scienceCalls);directSource=null}
            }
            val p=state.value.parameters.copy(filmStock="kodak_ektachrome_100",outputRoute="direct")
            m.parameters(p);waitFor{check(state.value.message==null){state.value.message!!};state.value.canExport && state.value.renderedParameters==p}
            assertEquals(FrameAppearanceContext.Material.POSITIVE_DISPLAY,state.value.presentation!!.appearance!!.material)
            assertEquals(0xffffff,state.value.presentation!!.appearance!!.holeMatteSrgb)
        }finally{dispose(h);Dispatchers.resetMain()}
    }
}
