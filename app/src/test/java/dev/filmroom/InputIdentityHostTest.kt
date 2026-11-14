package dev.filmroom

import android.app.Application
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class InputIdentityHostTest {
    @Test fun printChangesDoNotInvalidateInputButFrameChangesDo() {
        val original=EditorState(filmBorder=true,filmBorderStage=1)
        assertEquals(original.preImagingInputKey("frame"),original.copy(printExposureEv=1f).preImagingInputKey("frame"))
        assertNotEquals(original.preImagingInputKey("frame"),original.preImagingInputKey("other-frame"))
        assertNotEquals(original.preImagingInputKey("frame"),original.copy(imprint=original.imprint.copy(exposure=true)).preImagingInputKey("frame"))
    }
    @Test fun retiredLocalFieldsAreNotReemitted() {
        val json=org.json.JSONObject(EditorState().json()).put("finishExposureEv",1).put("finishContrast",1.2).put("finishSaturation",.3)
        val restored=EditorState.fromJson(json.toString())
        assertEquals(EditorState(),restored)
        assertFalse(org.json.JSONObject(restored.json()).has("finishExposureEv"))
    }
}
