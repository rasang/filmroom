package dev.filmroom.ui

import dev.filmroom.roi.*
import org.junit.Assert.*
import org.junit.Test

class DetailFeedbackTest {
    @Test fun onlyPreparingAndRefiningShowLoadingWhileForegroundAndEligible(){
        val feedback=DetailFeedback()
        for(phase in DetailPhase.values()){
            val status=DetailStatus(phase)
            assertEquals(phase in setOf(DetailPhase.Preparing,DetailPhase.Refining),feedback.showLoading(status,"photo",true,true))
            assertFalse(feedback.showLoading(status,"photo",true,false))
            assertFalse(feedback.showLoading(status,null,true,true))
            assertFalse(feedback.showLoading(status,"photo",false,true))
        }
    }
    @Test fun failuresDeduplicatePerPhotoAndReasonAcrossSavedStateWithoutLeakingKeys(){
        val feedback=DetailFeedback();val status=DetailStatus(DetailPhase.Fallback,failure=DetailFailure.MemoryBudget)
        val secret="private-photo-key"
        assertFalse(feedback.takeFailureToast(status,secret,true,true)!!.contains(secret))
        repeat(50){assertNull(feedback.takeFailureToast(status,secret,true,true))}
        val restored=DetailFeedback(feedback.savedKeys())
        assertNull(restored.takeFailureToast(status,secret,true,true))
        assertNotNull(restored.takeFailureToast(status,"other-photo",true,true))
        assertNotNull(restored.takeFailureToast(status.copy(failure=DetailFailure.Unsupported),secret,true,true))
    }
    @Test fun backgroundFailuresDoNotToastOrConsumeForegroundNotification(){
        val feedback=DetailFeedback();val status=DetailStatus(DetailPhase.Fallback,failure=DetailFailure.EngineUnavailable)
        assertNull(feedback.takeFailureToast(status,"photo",true,false))
        assertTrue(feedback.savedKeys().isEmpty())
        assertNotNull(feedback.takeFailureToast(status,"photo",true,true))
        assertNull(feedback.takeFailureToast(DetailStatus(DetailPhase.Ready),"new",true,true))
    }
}
