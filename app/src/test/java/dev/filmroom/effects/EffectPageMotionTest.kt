package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectPageMotionTest {
    @Test fun readyPageTracksFingerBothWays(){assertEquals(-150f,EffectPageMotion.dragOffset(-150f,400f,true),0f);assertEquals(150f,EffectPageMotion.dragOffset(150f,400f,true),0f)}
    @Test fun travelNeverExceedsOnePage(){assertEquals(-400f,EffectPageMotion.dragOffset(-900f,400f,true),0f);assertEquals(400f,EffectPageMotion.dragOffset(900f,400f,true),0f)}
    @Test fun onlyMissingNeighbourUsesBoundaryResistance(){assertEquals(-32f,EffectPageMotion.dragOffset(-500f,400f,false),0f);assertEquals(16f,EffectPageMotion.dragOffset(100f,400f,false),0f)}
    @Test fun loadingAnExistingNeighbourNeverChangesTracking(){for(x in listOf(-250f,-100f,100f,250f))assertEquals(x,EffectPageMotion.dragOffset(x,400f,true),0f)}
    @Test fun prefetchIsBoundedAndDisabledAtLowMemory(){assertEquals(0,EffectPageMotion.prefetchEdge(8L*1024*1024));assertEquals(384,EffectPageMotion.prefetchEdge(30L*1024*1024));assertEquals(640,EffectPageMotion.prefetchEdge(60L*1024*1024));assertEquals(960,EffectPageMotion.prefetchEdge(Long.MAX_VALUE))}
    @Test fun shortDragReturnsRatherThanChangesPhoto(){assertFalse(EffectPageMotion.shouldCommit(50f,400f,64f));assertFalse(EffectPageMotion.shouldCommit(-50f,400f,64f))}
    @Test fun commitThresholdAdaptsToWidthAndDensity(){assertFalse(EffectPageMotion.shouldCommit(80f,400f,64f));assertTrue(EffectPageMotion.shouldCommit(90f,400f,64f));assertFalse(EffectPageMotion.shouldCommit(90f,200f,128f))}
    @Test fun lowMemoryLimitsAdjacentDecode(){assertEquals(640,EffectPageMotion.decodeEdge(8L*1024*1024));assertEquals(960,EffectPageMotion.decodeEdge(48L*1024*1024));assertEquals(1200,EffectPageMotion.decodeEdge(100L*1024*1024))}
    @Test fun noOriginalResolutionPreloadEvenWithLargeHeap(){assertEquals(1200,EffectPageMotion.decodeEdge(Long.MAX_VALUE));assertEquals(640,EffectPageMotion.decodeEdge(-1))}
}
