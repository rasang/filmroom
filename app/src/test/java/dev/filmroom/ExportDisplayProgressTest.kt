package dev.filmroom

import dev.filmroom.engine.ExportDisplayProgress
import org.junit.Assert.*
import org.junit.Test

class ExportDisplayProgressTest {
    @Test fun stableSevenStepsAndUnknownCountersNeverInventPercent(){
        val labels=listOf("准备照片","计算曝光","处理光效","胶片显影","生成颗粒","印相和扫描","保存照片")
        labels.forEachIndexed{index,label->
            val progress=ExportDisplayProgress(index+1)
            assertEquals(label,progress.label)
            assertNull(progress.percent)
            assertEquals("$label\n第 ${index+1}/7 步",progress.compactText)
        }
    }
    @Test fun legacyEncodingAndCleanupMapToOneMonotonicPublicStepWithoutFalseCompletion(){
        val progress=listOf(ExportDisplayProgress(7),ExportDisplayProgress(7,100,100),ExportDisplayProgress(8),ExportDisplayProgress(8,0,1),ExportDisplayProgress(8,1,1))
        assertEquals(listOf(7,7,7,7,7),progress.map{it.displayStage})
        assertTrue(progress.all{it.label=="保存照片"})
        assertEquals(listOf(null,null,null,null,100),progress.map{it.displayPercent})
        assertEquals("保存照片\n第 7/7 步 · 100%",progress.last().compactText)
        assertEquals(dev.filmroom.ui.ProcessingVisualStage.SAVING,dev.filmroom.ui.ProcessingVisualStage.fromExportStep(8))
    }
    @Test fun measuredTailCountsRemainBelowCompletion() {
        assertEquals(49,ExportDisplayProgress(7,49,100,measured=true).displayPercent)
        assertNull(ExportDisplayProgress(7,49,100).displayPercent)
    }
    @Test fun percentageFloorsRealWorkAndUsesLongArithmetic(){
        assertEquals(65,ExportDisplayProgress(5,6500,10000).percent)
        assertEquals(99,ExportDisplayProgress(5,9999,10000).percent)
        assertEquals(100,ExportDisplayProgress(5,10000,10000).percent)
        assertEquals(50,ExportDisplayProgress(5,50_000_000_000L,100_000_000_000L).percent)
    }
    @Test fun internalGrainStatesStayInOneUserStepAndNativeCleanupDoesNotSkipSaving(){
        listOf("grain_sampling","grain_cloud","grain_microstructure","grain_blur").forEach{
            assertEquals(5,ExportDisplayProgress.forNativePhase(it).stage)
            assertNull(ExportDisplayProgress.forNativePhase(it).percent)
        }
        assertEquals(6,ExportDisplayProgress.forNativePhase("cleanup").stage)
        assertEquals(6,ExportDisplayProgress.forNativePhase("complete").stage)
    }
    @Test fun invalidCountersAreRejected(){
        listOf({ExportDisplayProgress(0)},{ExportDisplayProgress(9)},{ExportDisplayProgress(5,-1,100)},
            {ExportDisplayProgress(5,101,100)},{ExportDisplayProgress(5,Long.MAX_VALUE,Long.MAX_VALUE)}).forEach{
            try{it();fail("accepted invalid progress")}catch(_:IllegalArgumentException){}
        }
    }
}
