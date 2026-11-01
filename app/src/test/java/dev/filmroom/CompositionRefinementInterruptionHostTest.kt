package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class CompositionRefinementInterruptionHostTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun borderCommitBetweenQuickAndDetailDoesNotPermanentlyFreezeQuickCache()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var vm:EditorViewModel?=null
        try {
            val app=org.robolectric.RuntimeEnvironment.getApplication()
            app.getSharedPreferences("local-session",0).edit().clear().commit()
            val model=EditorViewModel(app).apply{borderMemorySnapshot={dev.filmroom.engine.NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}};vm=model
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            var started=false
            for(i in 0..500){runCurrent();if((operation.get(model) as? Job)?.isCompleted==true){started=true;break};Thread.sleep(5)}
            assertTrue("startup completed",started)
            val state=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(model) as MutableStateFlow<EditorUiState>
            state.value=EditorUiState(photo=PhotoSource(File("unused"),"fixture","image/jpeg",6000,4000,0),original=Bitmap.createBitmap(192,128,Bitmap.Config.ARGB_8888))
            val calls=mutableListOf<Boolean>();model.detailPreviewEdge={192}
            model.renderPreview={_,_,_,detail->calls+=detail;Bitmap.createBitmap(if(detail)192 else 96,if(detail)128 else 64,Bitmap.Config.ARGB_8888)}
            val raster=object:BorderRaster {
                override val stock="kodak_portra_400";override val version="authored-fixture"
                override val railRatio=.2;override val endRatio=.03;override val residentBytes=12L;override val featherTotalAt3072=4.0
                override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb.fill(.25f)}
            }
            model.borderProvider=object:BorderProvider {
                override fun snapshot(stock:String,width:Int,height:Int)=BorderSnapshot.create(stock,raster.version,width,height,.2,.03,4.0)
                override fun resolve(snapshot:BorderSnapshot)=raster
            }
            model.parameters(EditorState(brightness=.2f));advanceTimeBy(121);runCurrent()
            assertEquals(listOf(false),calls);assertEquals(96,state.value.compositionSource!!.width)
            // Cancel the pending 350 ms refinement with a presentation-only update.
            model.parameters(state.value.parameters.copy(filmBorder=true,composition=CompositionTransform(.6,.4,2.0)))
            var done=false
            for(i in 0..500){advanceUntilIdle();runCurrent();if(state.value.compositionSource?.width==192 && state.value.renderedParameters==state.value.parameters && !state.value.previewRefining){done=true;break};Thread.sleep(5)}
            assertTrue("presentation and refinement completed",done)
            assertEquals("reuse quick cache, but still finish requested scientific detail",listOf(false,true),calls)
            assertEquals(192,state.value.compositionSource!!.width)
            assertEquals(CompositionTransform(.6,.4,2.0),state.value.presentation!!.composition)
            assertEquals(state.value.parameters,state.value.renderedParameters)
            assertTrue(state.value.canExport)
        }finally{vm?.cancel();Dispatchers.resetMain()}
    }
}
