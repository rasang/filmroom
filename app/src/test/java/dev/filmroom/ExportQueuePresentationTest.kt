package dev.filmroom

import dev.filmroom.engine.ExportDisplayProgress
import dev.filmroom.export.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ExportQueuePresentationTest {
    private fun job(state:ExportJobState)=ExportJob("test",0,EditorState(),40,30,ExportFormat.JPEG,95,state,ExportDisplayProgress(5,65,100))
    @Test fun stableSevenStageProgressRemainsVisibleWithoutDetails(){
        val p=ExportJobPresentation.from(job(ExportJobState.RUNNING))
        assertEquals("生成颗粒\n第 5/7 步 · 65%",p.progress)
        assertTrue(p.canCancel);assertFalse(p.canRetry)
    }
    @Test fun onlyPausedOrFailedJobsCanResumeAndCancelledJobsNeverRestart(){
        ExportJobState.entries.forEach{state->
            val p=ExportJobPresentation.from(job(state))
            assertEquals(state in setOf(ExportJobState.PAUSED,ExportJobState.FAILED),p.canRetry)
            assertEquals(state !in setOf(ExportJobState.COMPLETED,ExportJobState.CANCELLED),p.canCancel)
        }
    }
    @Test fun incompleteCaptureCannotBeRetriedAndFailedCaptureCanBeDiscarded(){
        val capturing=ExportJobPresentation.from(job(ExportJobState.PAUSED).copy(inputReady=false))
        assertEquals("正在添加照片",capturing.status);assertFalse(capturing.canRetry);assertTrue(capturing.canCancel)
        val failed=ExportJobPresentation.from(job(ExportJobState.FAILED).copy(inputReady=false))
        assertFalse(failed.canRetry);assertTrue(failed.canCancel)
    }
    @Test fun invalidRecoveryNeverOffersAnEndlessRetry(){
        val failed=ExportJobPresentation.from(job(ExportJobState.FAILED).copy(failure=ExportJobFailure.RECOVERY_INVALID))
        assertFalse(failed.canRetry);assertTrue(failed.canCancel)
    }
    @Test fun terminalStatesAndEncodingFailureAreNotPresentedAsStillRunning() {
        assertEquals("已保存",ExportJobPresentation.from(job(ExportJobState.COMPLETED)).progress)
        assertEquals("已取消",ExportJobPresentation.from(job(ExportJobState.CANCELLED)).progress)
        val failed=ExportJobPresentation.from(job(ExportJobState.FAILED).copy(progress=ExportDisplayProgress(7)))
        assertEquals("导出失败",failed.status)
        assertEquals("导出失败\n保存照片 · 第 7/7 步",failed.progress)
        assertTrue(failed.canRetry)
    }
    @Test fun legacyCleanupFailuresAndCancellationKeepTheirStateInMergedSavingStep(){
        val cleanup=job(ExportJobState.FAILED).copy(progress=ExportDisplayProgress(8,0,1))
        assertEquals("导出失败\n保存照片 · 第 7/7 步",ExportJobPresentation.from(cleanup).progress)
        assertTrue(ExportJobPresentation.from(cleanup).canRetry)
        val cancelled=cleanup.copy(state=ExportJobState.CANCELLED)
        assertEquals("已取消",ExportJobPresentation.from(cancelled).progress)
        assertFalse(ExportJobPresentation.from(cancelled).canRetry)
        val pendingCleanup=cleanup.copy(state=ExportJobState.COMPLETED)
        assertNull(pendingCleanup.progress.displayPercent)
        assertEquals("已保存",ExportJobPresentation.from(pendingCleanup).progress)
        assertEquals(100,pendingCleanup.copy(progress=ExportDisplayProgress(8,1,1)).progress.displayPercent)
    }
    @Test fun previewsNeverMislabelOriginalAsCurrentOutput(){
        val dir=Files.createTempDirectory("queue-preview").toFile()
        try {
            val original=java.io.File(dir,"original-preview.jpg").apply{writeBytes(byteArrayOf(1))}
            assertEquals("原图 · 非处理结果",ExportPreviewSelection.select(dir,ExportJobState.RUNNING).label)
            val negative=java.io.File(dir,"negative-preview.png").apply{writeBytes(byteArrayOf(1))}
            assertEquals(negative,ExportPreviewSelection.select(dir,ExportJobState.RUNNING).file)
            val final=java.io.File(dir,"final-preview.jpg").apply{writeBytes(byteArrayOf(1))}
            assertEquals(negative,ExportPreviewSelection.select(dir,ExportJobState.RUNNING).file)
            assertEquals(final,ExportPreviewSelection.select(dir,ExportJobState.COMPLETED).file)
            assertEquals(original,ExportPreviewSelection.select(dir,ExportJobState.CANCELLED).file)
        }finally{dir.deleteRecursively()}
    }
}
