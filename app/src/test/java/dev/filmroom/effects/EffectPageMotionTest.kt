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
    @Test fun shortFastFlickCommitsBothDirections(){
        assertTrue(EffectPageMotion.shouldCommit(-30f,400f,64f,-900f,40f))
        assertTrue(EffectPageMotion.shouldCommit(30f,400f,64f,900f,-40f))
    }
    @Test fun slowShortDragStillReturns(){
        assertFalse(EffectPageMotion.shouldCommit(-30f,400f,64f,-250f,0f))
        assertFalse(EffectPageMotion.shouldCommit(30f,400f,64f,250f,0f))
    }
    @Test fun slowLongDragStillCommits(){
        assertTrue(EffectPageMotion.shouldCommit(-100f,400f,64f,-30f,0f))
        assertTrue(EffectPageMotion.shouldCommit(100f,400f,64f,0f,0f))
    }
    @Test fun oppositeVelocityDoesNotCommitShortDrag(){
        assertFalse(EffectPageMotion.shouldCommit(-30f,400f,64f,900f,0f))
        assertFalse(EffectPageMotion.shouldCommit(30f,400f,64f,-900f,0f))
    }
    @Test fun tinyJitterAndVerticalFlickDoNotCommit(){
        assertFalse(EffectPageMotion.shouldCommit(10f,400f,64f,2000f,0f))
        assertFalse(EffectPageMotion.shouldCommit(30f,400f,64f,900f,1000f))
        assertFalse(EffectPageMotion.shouldCommit(0f,400f,64f,2000f,0f))
    }
    @Test fun flingThresholdBoundariesAreExplicit(){
        assertTrue(EffectPageMotion.shouldCommit(16f,400f,64f,600f,0f))
        assertFalse(EffectPageMotion.shouldCommit(15.99f,400f,64f,600f,0f))
        assertFalse(EffectPageMotion.shouldCommit(16f,400f,64f,599.99f,0f))
        assertFalse(EffectPageMotion.shouldCommit(16f,400f,64f,600f,400f))
        assertTrue(EffectPageMotion.shouldCommit(88f,400f,64f))
        assertFalse(EffectPageMotion.shouldCommit(87.99f,400f,64f))
    }
    @Test fun flingThresholdsScaleWithDensity(){
        assertTrue(EffectPageMotion.shouldCommit(48f,1200f,192f,1800f,0f,1800f,48f))
        assertFalse(EffectPageMotion.shouldCommit(47f,1200f,192f,1800f,0f,1800f,48f))
        assertFalse(EffectPageMotion.shouldCommit(48f,1200f,192f,1799f,0f,1800f,48f))
    }
    @Test fun invalidMotionCannotCommit(){
        assertFalse(EffectPageMotion.shouldCommit(Float.NaN,400f,64f))
        assertFalse(EffectPageMotion.shouldCommit(30f,400f,64f,Float.POSITIVE_INFINITY,0f))
    }
}
