package dev.filmroom.effects

import java.io.IOException

/** An identified Live input must never silently become a successful still export. */
internal class EffectMotionExportException(cause:Throwable?=null):IOException("Original Live video decoding failed",cause)
internal object EffectMotionExportGuard {
    fun requireFrame(frameIndex:Int,hasFrame:Boolean,unavailable:Boolean) {
        if(unavailable || (frameIndex==0 && !hasFrame))throw EffectMotionExportException()
    }
}
