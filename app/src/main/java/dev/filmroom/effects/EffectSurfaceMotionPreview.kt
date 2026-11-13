package dev.filmroom.effects

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal class EffectSurfaceMotionPreview(file:File,private val composition:EffectSourceComposition?,onClosed:()->Unit={}) {
    private val texture=EffectMotionSurfaceTexture()
    private val closed=AtomicBoolean(false)
    private val surfaceRelease=EffectSurfaceRelease({texture.releaseSurface()},onClosed)
    @Volatile var failure:String?=null
        private set
    private val decoder=try{EffectSurfaceMotionDecoder(file,texture.surface,{failure=it},{surfaceRelease.release()})}catch(t:Throwable){try{texture.releaseGl()}finally{surfaceRelease.release()};throw t}
    fun snapshot()=decoder.snapshot()
    val unavailable get()=failure!=null||decoder.isUnavailable
    val sourceWidth get()=decoder.width
    val sourceHeight get()=decoder.height
    val hardware:Boolean? get()=if(decoder.codecName.isEmpty())null else decoder.hardwareAccelerated
    fun request(timeMs:Long,slow:Boolean){if(!closed.get())decoder.request(timeMs,slow)}
    fun render(renderer:EffectGlRenderer,timeMs:Long,slow:Boolean):EffectMotionSurfaceTexture.Frame? {
        if(closed.get()||!texture.hasPendingFrame)return null
        try{return renderer.updateMotionSurface(texture,decoder.width,decoder.height,decoder.rotationDegrees,composition,timeMs<EffectTimeline.focusDurationMs(slow))}finally{decoder.notifyFrameConsumed()}
    }
    fun close(releaseGl:Boolean=true){if(closed.compareAndSet(false,true)){decoder.close();if(releaseGl)texture.releaseGl()}}
}

/** Reconstructed exp6 cleanup gate: surface and input lease release exactly once. */
internal class EffectSurfaceRelease(private val releaseSurface:()->Unit,private val completed:()->Unit) {
    private val released=AtomicBoolean(false)
    fun release(){if(released.compareAndSet(false,true))try{releaseSurface()}finally{completed()}}
}
