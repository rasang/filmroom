package dev.filmroom

import dev.filmroom.diagnostics.NativePhase
import dev.filmroom.diagnostics.GpuWorkPhase
import dev.filmroom.engine.*
import org.junit.Assert.*
import org.junit.Test

class GpuExportPresentationTest {
    private fun progress(done:Long=0,total:Long=100,phase:NativePhase=NativePhase.GPU_TILED_CHAIN,jobId:Long=1,
        stageDone:Long=done*10_000,stageTotal:Long=1_000_000,passId:Long=1)=
        EngineProgress(jobId,passId,phase,done,total,ExportDisplayProgress(6,stageDone,stageTotal,GpuWorkProgress(
            phase=GpuWorkPhase.GRAIN,tileIndex=19,tileTotal=20,batchIndex=7,batchElapsedMs=42.0)))
    @Test fun stageWideWorkDistributesAcrossFourVisualSteps() {
        val mapper=GpuExportPresentation()
        val expected=listOf(3 to 0,4 to 0,5 to 0,6 to 0,6 to 96,6 to 99)
        listOf(0L,25,50,75,99,100).zip(expected).forEach{(done,want)->
            val raw=progress(done);val projected=mapper.project(raw)
            assertEquals(ExportVisualProgress(want.first,want.second),projected.display.visual)
            assertEquals(raw,projected.copy(display=projected.display.copy(visual=null)))
        }
    }
    @Test fun twoPassFilmLogReplayDoesNotFreezeAtFirstPassCompletion() {
        val mapper=GpuExportPresentation()
        mapper.project(progress(phase=NativePhase.GPU_DIFFUSION_PREPASS,stageDone=1_000_000,stageTotal=5_000_000))
        val first=mapper.project(progress(100,stageDone=2_000_000,stageTotal=5_000_000,passId=4))
        assertEquals(ExportVisualProgress(4,60),first.display.visual)
        val reset=mapper.project(progress(0,total=0,stageDone=2_000_000,stageTotal=5_000_000,passId=5))
        assertEquals(first.display.visual,reset.display.visual)
        assertEquals(ExportVisualProgress(5,0),mapper.project(progress(50,stageDone=2_500_000,stageTotal=5_000_000,passId=5)).display.visual)
        assertEquals(ExportVisualProgress(5,40),mapper.project(progress(100,stageDone=3_000_000,stageTotal=5_000_000,passId=5)).display.visual)
    }
    @Test fun cachedPrefixStillHasNativeCompletedWorkAndSuffixAdvances() {
        val mapper=GpuExportPresentation()
        mapper.project(progress(phase=NativePhase.GPU_DIFFUSION_PREPASS,stageDone=1_000_000,stageTotal=5_000_000))
        assertEquals(ExportVisualProgress(4,60),mapper.project(progress(total=0,stageDone=2_000_000,stageTotal=5_000_000)).display.visual)
        assertEquals(ExportVisualProgress(5,0),mapper.project(progress(50,stageDone=2_500_000,stageTotal=5_000_000)).display.visual)
    }
    @Test fun missingRepeatedOrOlderSamplesNeverInventOrRegress() {
        val mapper=GpuExportPresentation()
        assertEquals(ExportVisualProgress(3),mapper.project(progress(total=0,stageTotal=0)).display.visual)
        val measured=mapper.project(progress(61)).display.visual
        for(sample in listOf(progress(61),progress(total=0,stageTotal=0)))
            assertEquals(measured,mapper.project(sample).display.visual)
        assertEquals(ExportVisualProgress(3),mapper.project(progress(total=0,jobId=2,stageTotal=0)).display.visual)
        // Legacy per-pass counters alone cannot certify chain-wide progress.
        assertEquals(ExportVisualProgress(3),GpuExportPresentation().project(progress(100,stageDone=0,stageTotal=0)).display.visual)
    }
    @Test fun measuredPrepassAdvancesWithoutResetAtChainAndCountersStayIntact() {
        val mapper=GpuExportPresentation()
        val exposure=progress(100,phase=NativePhase.AUTO_EXPOSURE).copy(display=ExportDisplayProgress(2,100,100))
        assertEquals(exposure,mapper.project(exposure))
        val prepass=progress(90,phase=NativePhase.GPU_DIFFUSION_PREPASS,stageDone=900_000,stageTotal=4_000_000)
        val projected=mapper.project(prepass)
        assertEquals(ExportVisualProgress(3,90),projected.display.visual)
        assertEquals(prepass,projected.copy(display=projected.display.copy(visual=null)))
        mapper.project(prepass.copy(display=prepass.display.copy(done=1_000_000)))
        assertEquals(ExportVisualProgress(4,0),mapper.project(progress(0,stageDone=1_000_000,stageTotal=4_000_000)).display.visual)
    }
    @Test fun prepassUsesStageWorkNotItsResettingPassCounter() {
        val mapper=GpuExportPresentation()
        assertEquals(ExportVisualProgress(3,40),mapper.project(progress(99,phase=NativePhase.GPU_DIFFUSION_PREPASS,stageDone=500_000,stageTotal=5_000_000)).display.visual)
        assertEquals(ExportVisualProgress(3,40),mapper.project(progress(0,phase=NativePhase.GPU_DIFFUSION_PREPASS,stageDone=500_000,stageTotal=5_000_000,passId=2)).display.visual)
        assertEquals(ExportVisualProgress(3,80),mapper.project(progress(100,phase=NativePhase.GPU_DIFFUSION_PREPASS,stageDone=1_000_000,stageTotal=5_000_000,passId=2)).display.visual)
        assertEquals(ExportVisualProgress(3,80),mapper.project(progress(0,stageDone=1_000_000,stageTotal=5_000_000,passId=3)).display.visual)
    }
    @Test fun prepassWithoutMeasuredStageCounterDoesNotInventPercentage() {
        val mapper=GpuExportPresentation()
        assertEquals(ExportVisualProgress(3),mapper.project(progress(100,phase=NativePhase.GPU_DIFFUSION_PREPASS,stageDone=0,stageTotal=0)).display.visual)
        assertEquals(ExportVisualProgress(3,0),mapper.project(progress(0)).display.visual)
    }
    @Test fun prepassReplayHidesPercentageAndNewAttemptResets() {
        val mapper=GpuExportPresentation()
        mapper.project(progress(80,phase=NativePhase.GPU_DIFFUSION_PREPASS,stageDone=800_000,stageTotal=4_000_000))
        assertEquals(ExportVisualProgress(3),mapper.project(progress(0,phase=NativePhase.GPU_DIFFUSION_PREPASS,stageDone=0,stageTotal=4_000_000)).display.visual)
        assertEquals(ExportVisualProgress(4),mapper.project(progress(0,stageDone=1_000_000,stageTotal=4_000_000)).display.visual)
        assertEquals(ExportVisualProgress(3,0),mapper.project(progress(0,phase=NativePhase.GPU_DIFFUSION_PREPASS,jobId=2,stageDone=0,stageTotal=4_000_000)).display.visual)
    }
    @Test fun outputSavingAndCpuFallbackRemainOwnedByActualNativeEvents() {
        val mapper=GpuExportPresentation();mapper.project(progress(100))
        for(phase in listOf(NativePhase.PRINTING_SCANNING,NativePhase.DIR_DEVELOP)) {
            val raw=progress(1,1,phase).copy(display=ExportDisplayProgress.forNativePhase(phase.id))
            assertEquals(raw,mapper.project(raw))
        }
        assertNull(ExportDisplayProgress(7).displayPercent)
        assertNull(ExportDisplayProgress(8).displayPercent)
        assertEquals(100,ExportDisplayProgress(8,1,1).displayPercent)
    }
    @Test fun corruptCacheReplayDoesNotPinAFabricatedPercentage() {
        val mapper=GpuExportPresentation()
        mapper.project(progress(100,stageDone=3_000_000,stageTotal=5_000_000))
        assertEquals(ExportVisualProgress(5),mapper.project(progress(0,stageDone=2_000_000,stageTotal=5_000_000)).display.visual)
        assertEquals(ExportVisualProgress(5),mapper.project(progress(90,stageDone=2_900_000,stageTotal=5_000_000)).display.visual)
        assertEquals(ExportVisualProgress(3,0),mapper.project(progress(0,jobId=2)).display.visual)
    }
    @Test fun fullAllowedDisplayRangeDoesNotOverflow() {
        val max=Long.MAX_VALUE/100
        val result=GpuExportPresentation().project(progress(1,stageDone=max,stageTotal=max))
        assertEquals(ExportVisualProgress(6,99),result.display.visual)
    }
    @Test fun nativeSinglePassKeepsOutputAndFinalizationReserved() {
        val mapper=GpuExportPresentation()
        assertEquals(ExportVisualProgress(4,33),mapper.project(progress(100,stageDone=1_000_000,stageTotal=3_000_000)).display.visual)
        assertEquals(ExportVisualProgress(5,0),GpuExportPresentation().project(progress(100,stageDone=2_000_000,stageTotal=4_000_000)).display.visual)
    }
    @Test fun pollingMayMissAllOrPartOfPrepassWithoutChangingLaterProgress() {
        val observations=listOf(emptyList<Long>(),listOf(0L),listOf(0L,900_000L,1_000_000L))
        for(seen in observations) {
            val mapper=GpuExportPresentation()
            seen.forEach { mapper.project(progress(phase=NativePhase.GPU_DIFFUSION_PREPASS,stageDone=it,stageTotal=5_000_000)) }
            assertEquals(ExportVisualProgress(4,60),mapper.project(progress(100,stageDone=2_000_000,stageTotal=5_000_000)).display.visual)
        }
    }
    @Test fun replannedDenominatorHidesUnreliablePercentage() {
        val mapper=GpuExportPresentation()
        mapper.project(progress(50,stageDone=2_500_000,stageTotal=5_000_000))
        assertEquals(ExportVisualProgress(5),mapper.project(progress(50,stageDone=2_500_000,stageTotal=6_000_000)).display.visual)
    }
    @Test fun allGpuChainKindsAndFreshJobsUseCumulativeWork() {
        for(phase in listOf(NativePhase.GPU_FULL_CHAIN,NativePhase.GPU_BANDED_CHAIN,NativePhase.GPU_TILED_CHAIN)) {
            val mapper=GpuExportPresentation()
            assertEquals(ExportVisualProgress(5,0),mapper.project(progress(900_000_000,1_800_000_000,phase,stageDone=500_000)).display.visual)
            assertEquals(ExportVisualProgress(3,0),mapper.project(progress(0,phase=phase,jobId=2)).display.visual)
        }
    }
}
