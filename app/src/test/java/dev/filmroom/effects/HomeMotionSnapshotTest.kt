package dev.filmroom.effects

import android.graphics.Bitmap
import dev.filmroom.EditorUiState
import dev.filmroom.io.PhotoSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
class HomeMotionSnapshotTest {
    private fun bitmap(w:Int=40,h:Int=30)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    private fun state(original:Bitmap=bitmap(),result:Bitmap=bitmap()):EditorUiState {
        val state=EditorUiState(photo=PhotoSource(File("unused"),"test","image/jpeg",40,30,1),original=original,rendered=result)
        return state.copy(renderedParameters=state.parameters)
    }
    @Test fun opticalSnapshotOwnsIndependentCopiesAndDoesNotRecycleEditor() {
        val state=state()
        val snapshot=HomeMotionSnapshot.capture(state,EffectKind.OPTICAL_LEAK)
        assertNotSame(state.original,snapshot.source); assertNotSame(state.rendered,snapshot.result)
        snapshot.close(); snapshot.close()
        assertTrue(snapshot.source.isRecycled); assertTrue(snapshot.result.isRecycled)
        assertFalse(state.original!!.isRecycled); assertFalse(state.rendered!!.isRecycled)
    }
    @Test fun lightTableSharesItsOwnedResultNotEditorBitmap() {
        val state=state()
        val snapshot=HomeMotionSnapshot.capture(state,EffectKind.LIGHT_TABLE)
        assertSame(snapshot.source,snapshot.result); assertNotSame(state.rendered,snapshot.result)
        snapshot.close(); assertFalse(state.rendered!!.isRecycled)
    }
    @Test fun sourceAndResultAreBoundedSeparately() {
        val state=state(bitmap(2400,1200),bitmap(900,1800))
        HomeMotionSnapshot.capture(state,EffectKind.OPTICAL_LEAK).use {
            assertEquals(1600,it.source.width); assertEquals(800,it.source.height)
            assertEquals(800,it.result.width); assertEquals(1600,it.result.height)
        }
    }
    @Test fun detailSnapshotOwnsCopyAndKeepsHighResolutionInBothModes() {
        for(kind in listOf(EffectKind.LIGHT_TABLE,EffectKind.OPTICAL_LEAK)) {
            val state=state(); val detail=bitmap(2048,1536)
            val snapshot=HomeMotionSnapshot.capture(state,kind,detailResult=detail)
            assertEquals(2048,snapshot.result.width); assertEquals(1536,snapshot.result.height)
            assertNotSame(detail,snapshot.result)
            detail.recycle()
            assertFalse(snapshot.result.isRecycled)
            snapshot.close(); assertFalse(state.rendered!!.isRecycled)
        }
    }
    @Test fun oversizedDetailIsBoundedWithoutMutatingProviderBitmap() {
        val state=state(); val detail=bitmap(3200,1600)
        HomeMotionSnapshot.capture(state,EffectKind.LIGHT_TABLE,detailResult=detail).use {
            assertEquals(3072,it.result.width); assertEquals(1536,it.result.height)
            assertNotSame(detail,it.result)
        }
        assertFalse(detail.isRecycled); detail.recycle()
    }
    @Test fun readinessRejectsTransientEditorStates() {
        val state=state(); assertTrue(HomeMotionSnapshot.ready(state))
        listOf(state.copy(previewRefining=true),state.copy(compare=true),state.copy(exporting=true),
            state.copy(compositionEditing=true),state.copy(updating=true),state.copy(original=null)).forEach {
            assertFalse(HomeMotionSnapshot.ready(it))
        }
    }
    @Test fun retentionAllowsParameterUpdateButNotPhotoOrCompositionChange() {
        val state=state()
        assertTrue(HomeMotionSnapshot.canRetain(state,state.copy(previewRefining=true,updating=true)))
        assertFalse(HomeMotionSnapshot.canRetain(state,state()))
        assertFalse(HomeMotionSnapshot.canRetain(state,state.copy(compositionEditing=true)))
        assertFalse(HomeMotionSnapshot.matches(state,state.copy(rendered=bitmap())))
        assertTrue(HomeMotionSnapshot.matches(state,state))
    }
    @Test fun comparisonRetainsExistingCoverButCannotCaptureOrMatchUntilReleased() {
        val frozen=state()
        val comparing=frozen.copy(compare=true)
        assertTrue(HomeMotionSnapshot.canRetain(frozen,comparing))
        assertFalse(HomeMotionSnapshot.ready(comparing))
        assertFalse(HomeMotionSnapshot.matches(frozen,comparing))
        val updating=comparing.copy(parameters=comparing.parameters.copy(brightness=.4f),updating=true,previewRefining=true)
        assertTrue(HomeMotionSnapshot.canRetain(frozen,updating))
        assertTrue(HomeMotionSnapshot.canRetain(frozen,updating.copy(compare=false)))
        assertTrue(HomeMotionSnapshot.matches(frozen,comparing.copy(compare=false)))
        assertFalse(HomeMotionSnapshot.canRetain(frozen,comparing.copy(exporting=true)))
        assertFalse(HomeMotionSnapshot.canRetain(frozen,comparing.copy(compositionEditing=true)))
        assertFalse(HomeMotionSnapshot.canRetain(frozen,state().copy(compare=true)))
    }
    @Test fun cancelledPreparationLeavesCopiesClosable() {
        val state=state(); val snapshot=HomeMotionSnapshot.capture(state,EffectKind.OPTICAL_LEAK,prepare=false)
        try { snapshot.prepare { throw java.util.concurrent.CancellationException() }; fail() }
        catch (_:java.util.concurrent.CancellationException) { snapshot.close() }
        assertTrue(snapshot.source.isRecycled); assertFalse(state.original!!.isRecycled)
    }
}
