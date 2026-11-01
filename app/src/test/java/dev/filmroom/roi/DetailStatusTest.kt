package dev.filmroom.roi

import dev.filmroom.engine.DetailUnavailable
import dev.filmroom.engine.NativeWorkUnavailable
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DetailStatusTest {
    @Test fun fallbackUsesOnlyAllowlistedReasonsNeverRawPathsOrMessages(){
        val secret="/data/user/0/private-user/cache/private-photo.heic"
        val cases=listOf(
            IllegalArgumentException(secret) to DetailFailure.SourceValidation,
            IOException(secret) to DetailFailure.SourceRead,
            UnsatisfiedLinkError(secret) to DetailFailure.EngineUnavailable,
            NativeWorkUnavailable() to DetailFailure.MemoryBudget,
            OutOfMemoryError(secret) to DetailFailure.MemoryBudget,
            DetailUnavailable("UNSUPPORTED") to DetailFailure.Unsupported,
            DetailUnavailable(secret) to DetailFailure.RenderFailed,
            DetailUnavailable("BUDGET_EXCEEDED") to DetailFailure.MemoryBudget,
            DetailUnavailable("INVALID_RESULT") to DetailFailure.InvalidResult
        )
        for((error,expected) in cases){
            val failure=detailFailure(error);assertEquals(expected,failure)
            val label=DetailStatus(DetailPhase.Fallback,failure=failure).label()
            assertTrue(label.startsWith("普通预览"));assertFalse(label.contains(secret));assertFalse(label.contains("private-user"));assertFalse(label.contains(".heic"))
        }
    }
    @Test fun readyLabelIsTruthfulAboutActualGridCpuAndNotOriginalResolution(){
        val label=DetailStatus(DetailPhase.Ready,1152,1536,4).label()
        assertTrue(label.contains("1536"));assertTrue(label.contains("非原尺寸"));assertTrue(label.contains("CPU"))
    }
}
