package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectMotionAudioEnvelopeTest {
    @Test fun silenceOutsideSourceWindow() {
        assertEquals(0f,EffectMotionAudioEnvelope.gain(-1,1_000_000),0f)
        assertEquals(0f,EffectMotionAudioEnvelope.gain(1_000_000,1_000_000),0f)
        assertEquals(0f,EffectMotionAudioEnvelope.gain(1,0),0f)
    }
    @Test fun fadesInAndOutWithOriginalGain() {
        assertEquals(0f,EffectMotionAudioEnvelope.gain(0,1_000_000),0f)
        assertEquals(0.15f,EffectMotionAudioEnvelope.gain(40_000,1_000_000),0.00001f)
        assertEquals(0.3f,EffectMotionAudioEnvelope.gain(500_000,1_000_000),0.00001f)
        assertEquals(0.15f,EffectMotionAudioEnvelope.gain(940_000,1_000_000),0.00001f)
    }
    @Test fun honorsDelayedAudioStart() {
        assertEquals(0f,EffectMotionAudioEnvelope.gain(99_999,1_000_000,100_000),0f)
        assertEquals(0.15f,EffectMotionAudioEnvelope.gain(140_000,1_000_000,100_000),0.00001f)
    }
    @Test fun shortAudioRemainsBounded() {
        for(time in 0..1000)assertTrue(EffectMotionAudioEnvelope.gain(time.toLong(),1000) in 0f..0.3f)
    }
    @Test fun clippingAndBasePreservation() {
        assertEquals(1f,EffectMotionAudioEnvelope.mix(0.9f,1f,500_000,1_000_000,0),0f)
        assertEquals(-1f,EffectMotionAudioEnvelope.mix(-0.9f,-1f,500_000,1_000_000,0),0f)
        assertEquals(0.2f,EffectMotionAudioEnvelope.mix(0.2f,1f,1_000_000,1_000_000,0),0f)
    }
    @Test fun sourceStopsWhenMirrorCoversOrClipEnds() {
        assertEquals(0L,EffectMotionAudioEnvelope.endUs(false,-1))
        assertEquals(100_000L,EffectMotionAudioEnvelope.endUs(false,100_000))
        for(slow in listOf(false,true)) assertEquals(
            (EffectTimeline.opticalHoldMs(slow)+EffectTimeline.opticalTransitionMs(slow)*135/225)*1000L,
            EffectMotionAudioEnvelope.endUs(slow,10_000_000))
    }
    @Test fun omittedSourceStartMatchesOriginalZeroDefault() {
        assertEquals(EffectMotionAudioEnvelope.mix(.2f,.4f,500_000,1_000_000,0),
            EffectMotionAudioEnvelope.mix(.2f,.4f,500_000,1_000_000),0f)
    }

}
