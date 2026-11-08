package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectExportImagesBudgetTest {
    @Test fun lowHeapPreservesHalfForOtherWork() {
        val available=64L*1024*1024
        val edge=EffectExportImages.decodeBudgetEdge(128L*1024*1024,available)
        assertTrue(edge.toLong()*edge*16<=available/2)
        assertTrue(edge<2560)
    }
    @Test fun highHeapStillHasAbsoluteCap() {
        assertEquals(6144,EffectExportImages.decodeBudgetEdge(4L*1024*1024*1024,4L*1024*1024*1024))
    }
    @Test fun usedMemoryLowersBudget() {
        assertTrue(EffectExportImages.decodeBudgetEdge(512L*1024*1024,32L*1024*1024)<
            EffectExportImages.decodeBudgetEdge(512L*1024*1024,400L*1024*1024))
    }
    @Test fun liveReservesCoverExtractionHeadroom() {
        val available=256L*1024*1024
        val live=EffectExportImages.decodeBudgetEdge(512L*1024*1024,available,true)
        val video=EffectExportImages.decodeBudgetEdge(512L*1024*1024,available,false)
        assertTrue(live<video)
        assertTrue(live.toLong()*live*48<=available)
    }
    @Test fun exhaustedHeapDoesNotInventCapacity() {
        assertEquals(1,EffectExportImages.decodeBudgetEdge(256L*1024*1024,0))
    }
}
