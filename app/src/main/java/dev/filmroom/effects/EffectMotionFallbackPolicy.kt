package dev.filmroom.effects

/** A blur failure must not replace the current motion frame with the original still. */
internal class EffectMotionBlurPolicy {
    var gpuEnabled=true
        private set
    fun rejectGpu(close:()->Unit) { gpuEnabled=false;runCatching(close) }
    fun render(gpu:()->Unit,close:()->Unit,cpu:()->Unit) {
        if(gpuEnabled) {
            try { gpu();return }
            catch(_:Exception) { rejectGpu(close) }
        }
        cpu()
    }
}

/** One bounded second chance through the existing bitmap decoder before static fallback. */
internal object EffectMotionWarmupPolicy {
    fun retryBitmapDecoder(waitedMs:Long,frameReady:Boolean,hasSurfaceDecoder:Boolean)=
        waitedMs>=1500 && !frameReady && hasSurfaceDecoder
}
