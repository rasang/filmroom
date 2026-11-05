package dev.filmroom

import dev.filmroom.diagnostics.NativePhase
import dev.filmroom.diagnostics.GpuWorkPhase
import dev.filmroom.engine.*
import org.junit.Assert.*
import org.junit.Test

class GpuExportPresentationTest {
    private fun progress(done:Long=0,total:Long=100,phase:NativePhase=NativePhase.GPU_TILED_CHAIN,jobId:Long=1)=
        EngineProgress(jobId,1,phase,done,total,ExportDisplayProgress(6,1,4,GpuWorkProgress(
            phase=GpuWorkPhase.GRAIN,tileIndex=19,tileTotal=20,batchIndex=7,batchElapsedMs=42.0)))
    @Test fun completedPixelsDistributeAcrossFourEqualVisualQuarters() {
        val mapper=GpuExportPresentation()
        val expected=listOf(3 to 0,3 to 96,4 to 0,4 to 96,5 to 0,5 to 96,6 to 0,6 to 96,6 to 99)
        listOf(0L,24,25,49,50,74,75,99,100).zip(expected).forEach{(done,want)->
            val raw=progress(done);val projected=mapper.project(raw)
            assertEquals(ExportVisualProgress(want.first,want.second),projected.display.visual)
            assertEquals(raw,projected.copy(display=projected.display.copy(visual=null)))
        }
    }
    @Test fun missingRepeatedOrOlderSamplesNeverAdvanceOrRegress() {
        val mapper=GpuExportPresentation()
        assertEquals(ExportVisualProgress(3),mapper.project(progress(total=0)).display.visual)
        val measured=mapper.project(progress(61)).display.visual
        for(sample in listOf(progress(61),progress(10),progress(total=0),progress(total=0,phase=NativePhase.GPU_DIFFUSION_PREPASS)))
            assertEquals(measured,mapper.project(sample).display.visual)
        assertEquals(ExportVisualProgress(3),mapper.project(progress(total=0,jobId=2)).display.visual)
    }
    @Test fun prepassDoesNotConsumeImageProgressAndNativeCountersStayIntact() {
        val mapper=GpuExportPresentation()
        val exposure=progress(100,phase=NativePhase.AUTO_EXPOSURE).copy(display=ExportDisplayProgress(2,100,100))
        assertEquals(exposure,mapper.project(exposure))
        val prepass=progress(90,phase=NativePhase.GPU_DIFFUSION_PREPASS)
        assertEquals(ExportVisualProgress(2,100),mapper.project(prepass).display.visual)
        assertEquals(ExportVisualProgress(3,0),mapper.project(progress(0)).display.visual)
    }
    @Test fun sparseUpdatesJumpOnlyToMeasuredPositionAndNeverFinishOutputOrSaving() {
        val mapper=GpuExportPresentation()
        assertEquals(ExportVisualProgress(6,60),mapper.project(progress(90)).display.visual)
        assertEquals(ExportVisualProgress(6,99),mapper.project(progress(100)).display.visual)
        val published=progress(1,1,NativePhase.PRINTING_SCANNING).copy(display=ExportDisplayProgress(6,1,1))
        assertEquals(published,mapper.project(published))
        assertEquals(100,published.display.displayPercent)
        assertNull(ExportDisplayProgress(7).displayPercent)
        assertNull(ExportDisplayProgress(8).displayPercent)
        assertEquals(100,ExportDisplayProgress(8,1,1).displayPercent)
    }
    @Test fun largeCountsUseExactBoundedIntegerArithmetic() {
        assertEquals(ExportVisualProgress(5,0),GpuExportPresentation().project(progress(900_000_000,1_800_000_000)).display.visual)
    }
}
