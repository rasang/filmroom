package dev.filmroom

import android.app.Application
import dev.filmroom.engine.FinishingContract
import dev.filmroom.model.EditorState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class FinishingStateHostTest {
    @Test fun oldRecipeHasIdentityGrade() {
        val old=JSONObject(EditorState().json()).apply {
            remove("finishExposureEv");remove("finishContrast");remove("finishSaturation")
        }
        val restored=EditorState.fromJson(old.toString())
        assertFalse(restored.hasFinishing)
        assertEquals(EditorState(),restored)
    }
    @Test fun gradeRoundTripDoesNotChangeDarkroomControls() {
        val original=EditorState(contrast=1.2f,warmth=-.4f,saturation=1.3f,
            finishExposureEv=.5f,finishContrast=1.1f,finishSaturation=.6f)
        assertEquals(original,EditorState.fromJson(original.json()))
        val input=original.withoutLateFinishing()
        assertEquals(original.contrast,input.contrast,0f)
        assertEquals(original.warmth,input.warmth,0f)
        assertEquals(original.saturation,input.saturation,0f)
        assertFalse(input.hasFinishing)
    }
    @Test fun gradeDoesNotInvalidatePreImagingInputIdentity() {
        val original=EditorState(filmBorder=true,filmBorderStage=1)
        val changed=original.copy(finishExposureEv=1f,finishContrast=1.2f,finishSaturation=.3f)
        assertEquals(original.preImagingInputKey("frame"),changed.preImagingInputKey("frame"))
        assertEquals(original.preImagingInputKey("frame"),original.copy(printExposureEv=1f).preImagingInputKey("frame"))
        assertNotEquals(original.preImagingInputKey("frame"),original.preImagingInputKey("other-frame"))
        assertNotEquals(original.withoutLateFinishing().json(),original.copy(filmExposure=1f).withoutLateFinishing().json())
    }
    @Test fun olderNativeLibraryCannotSilentlyDropActiveGrade() {
        assertTrue(FinishingContract.supported(EditorState(),JSONObject()))
        val grade=EditorState(finishExposureEv=.5f)
        assertFalse(FinishingContract.supported(grade,JSONObject()))
        assertTrue(FinishingContract.supported(grade,JSONObject().put("finishingAbi",1)))
    }
}
