package dev.filmroom.effects

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import dev.filmroom.diagnostics.*
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Reconstructed exp6 home player. GL work stays on its own thread; editor copies are owned here. */
internal class HomeMotionPreview(context: Context, private val kind: EffectKind,
    private var snapshot: HomeMotionSnapshot, private val autoplay: Boolean = true,
    private val finished: (Boolean) -> Unit,
) : GLSurfaceView(context), GLSurfaceView.Renderer, AutoCloseable {
    private val audio = EffectPreviewAudio(context)
    private val closed = AtomicBoolean()
    private val notified = AtomicBoolean()
    private val failureNotified = AtomicBoolean()
    private val generation = AtomicLong()
    private val replacements = HomeMotionReplacementGate()
    private val pending = Collections.synchronizedSet(LinkedHashSet<HomeMotionSnapshot>())
    private var renderer: EffectGlRenderer? = null
    private var began = 0L
    @Volatile private var playing = autoplay
    val isPlaying get() = playing
    private var motion: EffectMotionPreview? = null
    private var surfaceMotion: EffectSurfaceMotionPreview? = null
    private var motionLog: EffectMotionPreviewLog? = null
    private val motionLock = Any()
    private var motionPrimed = false
    private var motionFrameReady = false
    private var warmupBegan = 0L
    private var audioRequested = false
    private val originalAudioEnabled = EffectOriginalAudioPreference.read(context)
    private var widthPx = 1
    private var heightPx = 1

    init {
        setEGLContextClientVersion(2); setRenderer(this); renderMode = RENDERMODE_CONTINUOUSLY
        isClickable = true
        if (autoplay) audio.prepare(kind,false,audioFile())
    }
    private fun audioFile(): File? = EffectOriginalAudioPreference.source(kind,originalAudioEnabled,snapshot.motionInput?.file)
    private fun disposeMotion(outcome: Outcome = Outcome.CANCELLED, releaseGl: Boolean = true) {
        synchronized(motionLock) {
            surfaceMotion?.let { motionLog?.decoder(it.snapshot()); it.close(releaseGl) }
            surfaceMotion = null
            motion?.close(); motion = null
            motionLog?.end(outcome); motionLog = null
        }
    }
    private fun preparePlayback() {
        disposeMotion(); renderer?.resetMotionFrame()
        motionPrimed = false; motionFrameReady = false; warmupBegan = 0; audioRequested = false
        val lease = if (kind == EffectKind.OPTICAL_LEAK) snapshot.motionInput?.retain() else null
        val file = lease?.file
        if (file == null) { lease?.close(); return }
        synchronized(motionLock) {
            if (closed.get()) { lease.close(); return }
            motionLog = EffectMotionPreviewLog()
            try {
                val created = EffectSurfaceMotionPreview(file,snapshot.motionComposition) { lease.close() }
                if (closed.get()) created.close() else {
                    surfaceMotion = created; motionLog?.path(MotionPreviewPath.SURFACE)
                }
            } catch (_: Exception) { lease.close(); fallbackMotion(MotionPreviewFallback.SURFACE) }
              catch (_: OutOfMemoryError) { lease.close(); fallbackMotion(MotionPreviewFallback.OUT_OF_MEMORY) }
        }
    }
    private fun fallbackMotion(reason: MotionPreviewFallback) {
        synchronized(motionLock) {
            surfaceMotion?.let { motionLog?.decoder(it.snapshot()); it.close() }; surfaceMotion = null
            // A replay or close may have invalidated the owner while a decoder was draining.
            if (closed.get()) return
            renderer?.resetMotionFrame(true); motionFrameReady = false
            motionLog?.path(MotionPreviewPath.BETA25_FALLBACK,reason)
            val lease = snapshot.motionInput?.retain() ?: return
            val file = lease.file
            if (file == null) { lease.close(); return }
            try {
                motion = EffectMotionPreview(file,snapshot.motionComposition,onClosed = { lease.close() })
            } catch (_: Exception) { lease.close(); motionLog?.path(MotionPreviewPath.STATIC,MotionPreviewFallback.CODEC) }
              catch (_: OutOfMemoryError) { lease.close(); motionLog?.path(MotionPreviewPath.STATIC,MotionPreviewFallback.OUT_OF_MEMORY) }
        }
    }
    /** Called only on GL. No decode/retrieval blocks this thread. */
    private fun tickMotion(timeMs: Long): Boolean {
        val surface = synchronized(motionLock) { surfaceMotion }
        if (surface != null) {
            if (surface.unavailable) {
                val report = surface.snapshot(); motionLog?.decoder(report)
                fallbackMotion(report.failureReason.takeUnless { it == MotionPreviewFallback.NONE } ?: MotionPreviewFallback.CODEC)
            } else {
                surface.request(timeMs,false)
                val started = System.nanoTime()
                try {
                    surface.render(checkNotNull(renderer),timeMs,false)?.let { frame ->
                        motionLog?.path(MotionPreviewPath.SURFACE,width=surface.sourceWidth,height=surface.sourceHeight,hardware=surface.hardware)
                        if (!motionFrameReady) motionLog?.decoder(surface.snapshot())
                        motionLog?.frame(frame.timestampNs/1000,timeMs,System.nanoTime()-started)
                        motionFrameReady = true
                    }
                    return motionFrameReady
                } catch (_: Exception) { fallbackMotion(MotionPreviewFallback.SURFACE) }
                  catch (_: OutOfMemoryError) { fallbackMotion(MotionPreviewFallback.OUT_OF_MEMORY) }
            }
        }
        val bitmapMotion = synchronized(motionLock) { motion } ?: return true
        bitmapMotion.request(timeMs,false)
        bitmapMotion.poll()?.use { frame ->
            val started = System.nanoTime()
            if (renderer?.updateMotionFrame(frame) != true) {
                motionLog?.path(MotionPreviewPath.STATIC,MotionPreviewFallback.SURFACE)
                disposeMotion(); renderer?.resetMotionFrame(true); return true
            }
            motionLog?.frame(null,timeMs,System.nanoTime()-started); motionFrameReady = true
        }
        if (bitmapMotion.isUnavailable()) {
            motionLog?.path(MotionPreviewPath.STATIC,MotionPreviewFallback.CODEC)
            disposeMotion(); renderer?.resetMotionFrame(true); return true
        }
        return motionFrameReady
    }
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        if (closed.get()) return
        try {
            // The prior context is gone: never delete its texture names in the new context.
            disposeMotion(releaseGl=false)
            renderer = EffectGlRenderer(kind,snapshot.source,snapshot.result,snapshot.readout)
            renderer!!.initialize()
            began = SystemClock.elapsedRealtime()
            HomeMotionLog.record(if (autoplay) HomeMotionEvent.START else HomeMotionEvent.COVER_READY,kind)
            if (playing) preparePlayback()
        } catch (_: Exception) { complete(false) } catch (_: OutOfMemoryError) { complete(false) }
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { widthPx=width; heightPx=height }
    override fun onDrawFrame(gl: GL10?) {
        if (closed.get()) return
        try {
            var time = (SystemClock.elapsedRealtime()-began).coerceAtLeast(0)
            var waiting = false
            if (playing) {
                val ready = tickMotion(if (motionPrimed) time else 0)
                val audioReady = audio.isPrepared(kind,false,audioFile())
                if (!motionPrimed) {
                    val now = SystemClock.elapsedRealtime()
                    if (warmupBegan == 0L) warmupBegan = now
                    waiting = !(ready && audioReady) && now-warmupBegan < EffectTimeline.OPTICAL_HOLD_MS
                    if (EffectMotionWarmupPolicy.retryBitmapDecoder(now-warmupBegan,ready,synchronized(motionLock) { surfaceMotion != null })) {
                        fallbackMotion(MotionPreviewFallback.TIMEOUT); warmupBegan=now; waiting=true
                    }
                    if (!waiting) {
                        if (!ready) {
                            motionLog?.path(MotionPreviewPath.STATIC,MotionPreviewFallback.TIMEOUT)
                            disposeMotion(); renderer?.resetMotionFrame(true)
                        }
                        motionPrimed=true; began=now; time=0; motionLog?.started()
                    }
                }
                if (!waiting && !audioRequested) {
                    audioRequested=true
                    val token=generation.get(); val start=began; val file=audioFile()
                    post { if (!closed.get() && playing && generation.get()==token) audio.play(kind,false,start,file,audioReady) }
                }
            }
            val done = !playing || (!waiting && time >= EffectTimeline.durationMs(kind))
            GLES20.glClearColor(24f/255,24f/255,24f/255,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val size=HomeMotionLayout.fit(widthPx,heightPx,kind,snapshot.result.width,snapshot.result.height)
            renderer?.draw(size.width,size.height,if (done || waiting) HomeMotionLayout.COVER_TIME_MS else time,
                viewportX=(widthPx-size.width)/2,viewportY=(heightPx-size.height)/2)
            if (done) {
                playing=false; disposeMotion(Outcome.SUCCESS); renderMode=RENDERMODE_WHEN_DIRTY
                val token=generation.get(); post { if (generation.get()==token) audio.stop() }
                complete(true)
            }
        } catch (_: Exception) { complete(false) } catch (_: OutOfMemoryError) { complete(false) }
    }
    fun cancelReplacement() { replacements.cancel() }
    fun replaceSnapshot(next: HomeMotionSnapshot, onApplied: (Boolean) -> Unit) {
        if (closed.get()) { next.close(); return }
        val token=replacements.next()
        pending.add(next)
        queueEvent {
            var candidate: EffectGlRenderer?=null
            var adopted=false
            try {
                if (!closed.get() && replacements.current(token)) {
                    if (renderer != null) {
                        candidate=EffectGlRenderer(kind,next.source,next.result,next.readout)
                        candidate!!.initialize()
                    }
                    adopted=replacements.commit(token) {
                        if (closed.get()) false else {
                            disposeMotion()
                            val oldRenderer=renderer; val oldSnapshot=snapshot
                            renderer=candidate; candidate=null; snapshot=next
                            playing=false; notified.set(false); generation.incrementAndGet()
                            renderMode=RENDERMODE_WHEN_DIRTY
                            runCatching { oldRenderer?.release() }; oldSnapshot.close()
                            true
                        }
                    }
                    if (adopted) {
                        requestRender()
                        post { if (!closed.get() && replacements.current(token)) { audio.stop(); onApplied(true) } }
                    }
                }
            } catch (_: Exception) {
                post { if (!closed.get() && replacements.current(token)) onApplied(false) }
            } catch (_: OutOfMemoryError) {
                post { if (!closed.get() && replacements.current(token)) onApplied(false) }
            } finally {
                runCatching { candidate?.release() }; pending.remove(next)
                if (!adopted) next.close()
            }
        }
    }
    fun replay() {
        if (closed.get()) return
        val token=generation.incrementAndGet(); audio.stop(); audio.prepare(kind,false,audioFile())
        queueEvent {
            if (!closed.get() && generation.get()==token) {
                preparePlayback(); began=SystemClock.elapsedRealtime(); playing=true; notified.set(false)
                renderMode=RENDERMODE_CONTINUOUSLY; requestRender(); HomeMotionLog.record(HomeMotionEvent.START,kind)
            }
        }
    }
    fun showCover() {
        if (closed.get()) return
        val token=generation.incrementAndGet()
        HomeMotionLog.record(HomeMotionEvent.COVER_READY,kind,HomeMotionReason.RELEASED)
        audio.stop(); playing=false
        queueEvent { if (!closed.get() && generation.get()==token) { playing=false; disposeMotion(); requestRender() } }
    }
    private fun complete(ok: Boolean) {
        if (!ok) { playing=false; renderMode=RENDERMODE_WHEN_DIRTY }
        if (if (ok) notified.compareAndSet(false,true) else failureNotified.compareAndSet(false,true)) {
            HomeMotionLog.record(if (ok) HomeMotionEvent.FINISH else HomeMotionEvent.FAIL,kind,
                if (ok) HomeMotionReason.NONE else HomeMotionReason.GL_ERROR)
            val token=generation.get()
            post { if (!closed.get() && (!ok || generation.get()==token)) { if (!ok) audio.stop(); finished(ok) } }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        generation.incrementAndGet(); cancelReplacement(); audio.stop(); playing=false
        disposeMotion(releaseGl=false)
        queueEvent { runCatching { renderer?.release() }; renderer=null }
        // GLSurfaceView's pause handshake drains any running upload before bitmap ownership ends.
        try { onPause() } finally {
            snapshot.close()
            synchronized(pending) { pending.forEach(HomeMotionSnapshot::close); pending.clear() }
        }
    }
}

/** An upload may be slow. Invalidation and adoption must share one lock, not two token checks. */
internal class HomeMotionReplacementGate {
    private var generation=0L
    @Synchronized fun next(): Long = ++generation
    @Synchronized fun cancel() { generation++ }
    @Synchronized fun current(token: Long) = token == generation
    @Synchronized fun commit(token: Long, adopt: () -> Boolean): Boolean = token == generation && adopt()
}
