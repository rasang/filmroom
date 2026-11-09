package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectMotionFallbackPolicyTest {
    @Test fun healthyGpuDoesNotUseCpu() {
        val policy=EffectMotionBlurPolicy();var gpu=0;var cpu=0;var close=0
        repeat(3){policy.render({gpu++},{close++},{cpu++})}
        assertEquals(3,gpu);assertEquals(0,cpu);assertEquals(0,close);assertTrue(policy.gpuEnabled)
    }
    @Test fun failedBlurUsesCurrentFrameAndDoesNotRetryBrokenGpu() {
        val policy=EffectMotionBlurPolicy();val visible=mutableListOf<Int>();var gpu=0;var close=0
        for(frame in 1..3)policy.render({gpu++;error("shader")},{close++},{visible+=frame})
        assertEquals(listOf(1,2,3),visible);assertEquals(1,gpu);assertEquals(1,close);assertFalse(policy.gpuEnabled)
    }
    @Test fun surfaceBlurFailureDisablesGpuForBitmapFallback() {
        val policy=EffectMotionBlurPolicy();policy.rejectGpu{error("release")};var cpu=false
        policy.render({fail("must not retry rejected GPU")},{fail("must not close twice")},{cpu=true})
        assertTrue(cpu)
    }
    @Test fun timeoutTriesBitmapDecoderOnlyForMissingSurfaceFrame() {
        assertFalse(EffectMotionWarmupPolicy.retryBitmapDecoder(1499,false,true))
        assertTrue(EffectMotionWarmupPolicy.retryBitmapDecoder(1500,false,true))
        assertFalse(EffectMotionWarmupPolicy.retryBitmapDecoder(3000,false,false))
        assertFalse(EffectMotionWarmupPolicy.retryBitmapDecoder(1500,true,true))
    }
    @Test fun cpuFailureIsNotFalselyReportedAsSuccessfulMotion() {
        val policy=EffectMotionBlurPolicy();policy.rejectGpu{}
        assertThrows(IllegalStateException::class.java){policy.render({},{},{error("cpu")})}
    }
}
