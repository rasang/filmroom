package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectMotionTimingRestoreTest {
    @Test fun normalStopsWhenMirrorCloses(){
        assertEquals(1_635_000L,EffectMotionTiming.sampleUs(3_000,false,4_000_000))
        assertEquals(0L,EffectMotionTiming.sampleUs(-20,false,4_000_000))
        assertEquals(33_333L,EffectMotionTiming.sampleUs(33,false,4_000_000))
    }
    @Test fun slowStopsWhenMirrorCloses(){assertEquals(1_610_000L,EffectMotionTiming.sampleUs(3_000,true,4_000_000))}
    @Test fun shortSourceNeverRequestsBeyondLastTimestamp(){assertEquals(99_999L,EffectMotionTiming.sampleUs(3_000,false,100_000));assertEquals(0L,EffectMotionTiming.sampleUs(3000,false,0))}
    @Test fun pausedLifecycleNeverPublishesResources(){val gate=EffectMotionLifecycleGate();var assigned=false;gate.pause();assertFalse(gate.publish{assigned=true});assertFalse(assigned);gate.resume();assertTrue(gate.publish{assigned=true});assertTrue(assigned);assertEquals("resource",gate.take{"resource"})}
}
