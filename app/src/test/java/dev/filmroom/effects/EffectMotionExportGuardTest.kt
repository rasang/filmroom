package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectMotionExportGuardTest {
    @Test fun firstFrameCannotSilentlyBeStatic(){
        assertThrows(EffectMotionExportException::class.java){EffectMotionExportGuard.requireFrame(0,false,false)}
    }
    @Test fun midExportDecoderFailureAborts(){
        assertThrows(EffectMotionExportException::class.java){EffectMotionExportGuard.requireFrame(20,false,true)}
    }
    @Test fun successfulFrameAccepted(){EffectMotionExportGuard.requireFrame(0,true,false)}
    @Test fun repeatedSampleMayReusePreviousFrame(){EffectMotionExportGuard.requireFrame(20,false,false)}
}
