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
class PreviewRefinementHostTest {
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
    @Test fun quickThenDetailAndDragOnlyQuick()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();assertTrue(vm.localDetailEnabled);runCurrent();seed(s)
            val calls=mutableListOf<Boolean>()
            vm.detailPreviewEdge={768}
            vm.renderPreview={_,_,_,detail->calls+=detail;Bitmap.createBitmap(if(detail)768 else 512,1,Bitmap.Config.ARGB_8888)}
            vm.parameters(EditorState(brightness=0.2f));advanceTimeBy(121);runCurrent()
            assertEquals(listOf(false),calls);assertEquals(512,s.value.rendered!!.width);assertTrue(s.value.canExport)
            advanceTimeBy(350);runCurrent();assertEquals(listOf(false,true),calls);assertEquals(768,s.value.rendered!!.width)
            vm.parameters(EditorState(brightness=0.3f),commit=false);advanceUntilIdle()
            assertEquals(listOf(false,true,false),calls);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun explicitDisabledPathAlsoKeepsOrdinary768Refinement()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();vm.localDetailEnabled=false;runCurrent();seed(s);vm.detailPreviewEdge={768}
            vm.renderPreview={_,_,_,detail->Bitmap.createBitmap(if(detail)768 else 512,1,Bitmap.Config.ARGB_8888)}
            vm.parameters(EditorState());advanceUntilIdle()
            assertEquals(768,s.value.rendered!!.width);assertFalse(s.value.previewRefining);assertTrue(s.value.canExport);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun lateCancelledDetailNeverReplacesNewerParameters()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();runCurrent();seed(s);vm.detailPreviewEdge={768}
            var stale:Bitmap?=null
            vm.renderPreview={_,_,_,detail->
                if(detail)withContext(NonCancellable){delay(1000)}
                Bitmap.createBitmap(if(detail)768 else 512,1,Bitmap.Config.ARGB_8888).also{if(detail)stale=it}
            }
            vm.parameters(EditorState(brightness=0.2f));advanceTimeBy(471);runCurrent()
            val latest=EditorState(brightness=0.4f)
            vm.parameters(latest,commit=false);advanceUntilIdle()
            assertEquals(latest,s.value.renderedParameters);assertEquals(512,s.value.rendered!!.width)
            assertTrue(stale!!.isRecycled);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun detailFailureKeepsQuickFrameExportable()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();runCurrent();seed(s);vm.detailPreviewEdge={768}
            vm.renderPreview={_,_,_,detail->if(detail)throw OutOfMemoryError("test");Bitmap.createBitmap(512,1,Bitmap.Config.ARGB_8888)}
            vm.parameters(EditorState());advanceUntilIdle()
            assertEquals(512,s.value.rendered!!.width);assertTrue(s.value.canExport);assertNull(s.value.message);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }
    @Test fun borderExpansionDoesNotSuppressPhotoDetailRefinement()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();runCurrent()
            s.value=EditorUiState(photo=PhotoSource(File("unused"),"fixture","image/jpeg",600,600,0),original=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888))
            val calls=mutableListOf<Boolean>();vm.detailPreviewEdge={768}
            vm.renderPreview={_,_,_,detail->calls+=detail;Bitmap.createBitmap(if(detail)600 else 512,if(detail)600 else 512,Bitmap.Config.ARGB_8888)}
            val asset=object:dev.filmroom.border.BorderRaster {
                override val stock="kodak_portra_400";override val version="test-only"
                override val railRatio=.23;override val endRatio=.04;override val featherTotalAt3072=4.0;override val residentBytes=12L
                override fun sample(snapshot:dev.filmroom.border.BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb.fill(.2f)}
            }
            vm.borderProvider=object:dev.filmroom.border.BorderProvider {
                override fun snapshot(stock:String,width:Int,height:Int)=dev.filmroom.border.BorderSnapshot.create(stock,asset.version,width,height,.23,.04,4.0)
                override fun resolve(snapshot:dev.filmroom.border.BorderSnapshot)=asset
            }
            vm.parameters(EditorState(filmBorder=true))
            for(i in 0..500){advanceUntilIdle();runCurrent();if(!s.value.previewRefining)break;Thread.sleep(5)}
            assertEquals(listOf(false,true),calls);assertEquals(584,s.value.rendered!!.height);assertTrue(s.value.canExport)
            vm.cancel()
        }finally{Dispatchers.resetMain()}
    }

    @Test fun compositionCommitUndoAndToggleReuseUnframedScience()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val(vm,s)=setup();runCurrent();seed(s);vm.detailPreviewEdge={96}
            var calls=0
            vm.renderPreview={_,_,_,_->calls++;Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)}
            val asset=object:dev.filmroom.border.BorderRaster {
                override val stock="kodak_portra_400";override val version="test-only"
                override val railRatio=.2;override val endRatio=.03;override val featherTotalAt3072=4.0;override val residentBytes=12L
                override fun sample(snapshot:dev.filmroom.border.BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb.fill(.2f)}
            }
            vm.borderProvider=object:dev.filmroom.border.BorderProvider {
                override fun snapshot(stock:String,width:Int,height:Int)=dev.filmroom.border.BorderSnapshot.create(stock,asset.version,width,height,.2,.03,4.0)
                override fun resolve(snapshot:dev.filmroom.border.BorderSnapshot)=asset
            }
            fun awaitPresentation(){repeat(500){advanceUntilIdle();runCurrent();if(!s.value.updating && !s.value.previewRefining && s.value.renderedParameters==s.value.parameters)return;Thread.sleep(5)};error("preview timeout")}
            vm.parameters(EditorState());awaitPresentation();assertEquals(1,calls)
            vm.parameters(s.value.parameters.copy(filmBorder=true));awaitPresentation();assertEquals(1,calls)
            val unframed=s.value.compositionSource
            vm.setCompositionEditing(true)
            val crop=dev.filmroom.model.CompositionTransform(.7,.4,2.0)
            vm.composition(crop,false);runCurrent()
            assertEquals(1,calls);assertFalse(s.value.canExport)
            vm.composition(crop,true);awaitPresentation()
            assertEquals(1,calls);assertFalse("Pointer-up renders a draft, not an accepted edit",s.value.canExport)
            vm.confirmComposition();awaitPresentation()
            assertEquals(1,calls);assertTrue(s.value.canExport);assertSame(unframed,s.value.compositionSource)
            assertEquals(crop,s.value.presentation!!.composition)
            vm.undo();awaitPresentation();assertEquals(dev.filmroom.model.CompositionTransform(),s.value.parameters.composition)
            vm.redo();awaitPresentation();assertEquals(crop,s.value.presentation!!.composition)
            vm.parameters(s.value.parameters.copy(filmBorder=false));awaitPresentation()
            assertFalse(s.value.compositionEditing);assertNull(s.value.presentation);assertSame(unframed,s.value.rendered)
            assertEquals(1,calls);vm.cancel()
        }finally{Dispatchers.resetMain()}
    }

}
