package dev.filmroom.engine

import android.content.Context
import android.os.Looper
import android.os.StatFs
import androidx.annotation.Keep
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Owns one native frame, not the longer-lived helper lease. Creation has no native or memory side effects. */
@Keep
internal class GpuScienceFrameDelegate internal constructor(
    internal val context: Context,
    internal val allowedOperations: Set<GpuRenderOperation>,
    internal val generation: Long,
    internal val parentToken: Long?,
    internal val preview: Boolean,
) {
    internal var opened = CompletableDeferred<Boolean>()
    internal var finished = CompletableDeferred<Boolean>()
    internal val records = linkedMapOf<Long, GpuScienceStageRecord>()
    @Volatile internal var cancelled = false
    @Volatile internal var sealed = false
    @Volatile internal var controller: GpuScienceSessionController? = null
    private val diagnosticsLock = Any()
    private var pipelineCacheHits = 0L
    private var bufferCacheHits = 0L
    private var clearSubmissions = 0L
    private var sessionRecycles = 0L
    /** Only validated, newly sealed observations contribute; no inferred hits. */
    internal fun recordSessionStats(previous: GpuScienceSessionStats?, next: GpuScienceSessionStats) = synchronized(diagnosticsLock) {
        require(previous == null || previous.epoch == next.epoch)
        val pipelineDelta = next.pipelineHits - (previous?.pipelineHits ?: 0L)
        val bufferDelta = next.bufferHits - (previous?.bufferHits ?: 0L)
        val clearDelta = next.clearSubmissions - (previous?.clearSubmissions ?: 0L)
        require(pipelineDelta >= 0L && bufferDelta >= 0L && clearDelta >= 0L)
        val pipelines = Math.addExact(pipelineCacheHits, pipelineDelta)
        val buffers = Math.addExact(bufferCacheHits, bufferDelta)
        val clears = Math.addExact(clearSubmissions, clearDelta)
        pipelineCacheHits = pipelines; bufferCacheHits = buffers; clearSubmissions = clears
    }
    internal fun recordSessionRecycle() = synchronized(diagnosticsLock) {
        sessionRecycles = Math.addExact(sessionRecycles, 1L)
    }
    @Keep fun sessionDiagnostics(): JSONObject = synchronized(diagnosticsLock) {
        JSONObject().put("pipelineCacheHits", pipelineCacheHits).put("bufferCacheHits", bufferCacheHits)
            .put("clearSubmissions", clearSubmissions).put("sessionRecycles", sessionRecycles)
    }

    @Keep fun beginSessionStage(requestJson: String): String {
        if (cancelled || sealed) return GpuScienceSessionProtocol.rejected(GpuRenderCode.START_REVOKED.name)
        // JNI work is background-only. Blocking the Binder/main looper would deadlock startup.
        if (Looper.myLooper() == Looper.getMainLooper()) return GpuScienceSessionProtocol.rejected(GpuRenderCode.INVALID_REQUEST.name)
        return try {
            val owner = controller ?: runBlocking(Dispatchers.IO) { GpuScienceSessionPool.acquire(this@GpuScienceFrameDelegate) }
            if (owner == null) GpuScienceSessionProtocol.rejected(GpuRenderCode.MEMORY_UNAVAILABLE.name)
            else try { owner.begin(this, requestJson) }
            catch (rollover: GpuScienceCapacityRollover) {
                // Only a pre-ticket/pre-RUN capacity rejection can reach this path.
                // Retry at most once and only after the previous process is dead.
                val replacement = runBlocking(Dispatchers.IO) { GpuScienceSessionPool.rollover(this@GpuScienceFrameDelegate, owner, rollover.replacementBudget) }
                if (replacement == null) GpuScienceSessionProtocol.rejected(GpuRenderCode.MEMORY_UNAVAILABLE.name)
                else replacement.begin(this, requestJson, allowRollover = false)
            }
        } catch (_: Throwable) {
            controller?.retireOwned(this, GpuRenderCode.INVALID_RESULT)
            // A failure after ticket publication is uncertain, never a definite rejection.
            "{}"
        }
    }
    @Keep fun pollSessionStage(epoch: Long, frameGeneration: Long, sequence: Long, token: Long): String =
        controller?.poll(this, epoch, frameGeneration, sequence, token) ?: "{}"
    @Keep fun cancelSessionStage(epoch: Long, frameGeneration: Long, sequence: Long, token: Long) {
        val owner = controller ?: return
        synchronized(owner.lock) {
            val ticket = records[token]?.ticket
            if (ticket == null || ticket.epoch != epoch || ticket.frameGeneration != frameGeneration || ticket.sequence != sequence) {
                owner.retireOwned(this, GpuRenderCode.INVALID_REQUEST)
            } else { cancelled = true; owner.retireOwned(this, GpuRenderCode.CANCELLED) }
        }
    }
    @Keep fun cancelUncertainSessionStage(token: Long) {
        cancelled = true
        controller?.retireOwned(this, GpuRenderCode.CANCELLED)
    }
    @Keep fun pollUncertainSessionStage(token: Long): String = controller?.uncertainPoll(this, token)
        ?: JSONObject().put("schemaVersion", 1).put("token", token).put("status", "not_started")
            .put("completedPixels", 0L).put("totalPixels", 0L).put("code", GpuRenderCode.NO_JOB.name).toString()
    @Keep fun availableDiskBytes(): Long = try {
        val cache = context.cacheDir.toPath()
        if (!Files.isDirectory(cache, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(cache)) 0L
        else (StatFs(cache.toString()).availableBytes - 64L * 1024L * 1024L).coerceAtLeast(0L)
    } catch (_: Throwable) { 0L }

    suspend fun finishFrame() {
        val owner = controller
        if (owner == null) { sealed = true; return }
        owner.finishFrame(this)
    }
    suspend fun awaitSafeTermination(): Boolean {
        val owner = controller ?: return true
        if (sealed) return true
        owner.retireOwned(this, if (cancelled) GpuRenderCode.CANCELLED else GpuRenderCode.START_REVOKED)
        return owner.awaitStopped()
    }
    fun whenSafeToRelease(cleanup: () -> Unit) {
        val owner = controller
        if (owner == null || sealed) cleanup() else owner.whenStopped(cleanup)
    }
    fun retireActive() = retireOwnedSession()
    fun retireOwnedSession() {
        cancelled = true
        controller?.retireOwned(this, GpuRenderCode.CANCELLED)
    }
}

internal object GpuScienceSessionPool {
    private val gate = Mutex()
    private val active = AtomicReference<GpuScienceSessionController?>(null)
    // Transport identity only, completely separate from film/grain randomness.
    // A fresh parent process must not reuse epoch 1 while an old helper may exist.
    private val epochs = AtomicLong(GpuScienceControlIdentity.positiveSeed())
    private val generations = AtomicLong(0L)
    // Fixed admitted capacity, not a claim that every graph fits. Native debt checks may recycle it.
    private val budget = GpuScienceSessionBudget(768L * 1024L * 1024L, 128L * 1024L * 1024L, 64)

    fun openFrame(context: Context, allowedOperations: Set<GpuRenderOperation>, parentToken: Long?, preview: Boolean): GpuScienceFrameDelegate {
        val generation = GpuScienceControlIdentity.next(generations)
        check(generation > 0L) { "GPU frame generation exhausted" }
        return GpuScienceFrameDelegate(context.applicationContext, allowedOperations.toSet(), generation, parentToken, preview)
    }
    internal suspend fun acquire(frame: GpuScienceFrameDelegate): GpuScienceSessionController? = gate.withLock {
        frame.controller?.let { return@withLock it }
        if (frame.cancelled || frame.sealed || frame.allowedOperations.isEmpty()) return@withLock null
        var owner = active.get()
        var created = false
        if (owner == null) {
            val epoch = GpuScienceControlIdentity.next(epochs)
            if (epoch <= 0L) return@withLock null
            owner = try { GpuScienceSessionController(frame.context, epoch, budget, released = ::releaseOwner) }
            catch (_: Throwable) { return@withLock null }
            if (!publishNewOwner(frame, owner)) return@withLock null
            created = true
        } else if (!owner.idle()) return@withLock null
        frame.controller = owner
        if (frame.cancelled) { owner.retire(GpuRenderCode.CANCELLED); return@withLock null }
        if (created) {
            owner.start()
            if (!owner.awaitReady()) return@withLock null
        }
        if (frame.cancelled) { owner.retire(GpuRenderCode.CANCELLED); return@withLock null }
        if (!owner.beginFrame(frame)) {
            if (!owner.owns(frame)) frame.controller = null
            return@withLock null
        }
        owner
    }
    internal suspend fun rollover(
        frame: GpuScienceFrameDelegate,
        previous: GpuScienceSessionController,
        nextBudget: GpuScienceSessionBudget,
        controllerFactory: (Long, GpuScienceSessionBudget) -> GpuScienceSessionController = { epoch, admitted ->
            GpuScienceSessionController(frame.context, epoch, admitted, released = ::releaseOwner)
        },
    ): GpuScienceSessionController? = gate.withLock {
        nextBudget.validate()
        require(nextBudget.prepaidBytes == previous.budget.prepaidBytes && nextBudget.pipelineLimit == previous.budget.pipelineLimit)
        require(nextBudget.fixedBytes >= 128L * GpuRenderProtocol.MIB)
        if (frame.controller !== previous || frame.cancelled || frame.sealed) return@withLock null
        previous.retireOwned(frame, GpuRenderCode.MEMORY_UNAVAILABLE)
        if (!previous.awaitStopped() || active.get() != null || frame.cancelled) return@withLock null
        // Old callbacks are detached and process death is confirmed. Preserve
        // all earlier sealed stage records, but use new per-epoch readiness.
        frame.controller = null
        frame.opened = CompletableDeferred()
        frame.finished = CompletableDeferred()
        val epoch = GpuScienceControlIdentity.next(epochs)
        if (epoch <= 0L) return@withLock null
        val replacement = try { controllerFactory(epoch, nextBudget) }
        catch (_: Throwable) { return@withLock null }
        if (!publishNewOwner(frame, replacement)) return@withLock null
        if (frame.cancelled) { replacement.retireOwned(frame, GpuRenderCode.CANCELLED); return@withLock null }
        replacement.start()
        if (!replacement.awaitReady() || frame.cancelled) {
            replacement.retireOwned(frame, GpuRenderCode.CANCELLED); return@withLock null
        }
        if (!replacement.beginFrame(frame)) return@withLock null
        frame.recordSessionRecycle()
        replacement
    }
    internal fun releaseOwner(owner: GpuScienceSessionController) { active.compareAndSet(owner, null) }
    /** Called under the pool gate. Tests can inject a never-started owner without JNI. */
    internal fun publishNewOwner(frame: GpuScienceFrameDelegate, owner: GpuScienceSessionController): Boolean {
        if (!active.compareAndSet(null, owner)) {
            owner.retire(GpuRenderCode.START_REVOKED)
            if (!owner.hasStopped()) frame.controller = owner
            return false
        }
        if (owner.hasStopped() || !owner.claimStartupOwner(frame)) {
            owner.retire(GpuRenderCode.MEMORY_UNAVAILABLE)
            if (owner.hasStopped()) active.compareAndSet(owner, null)
            else frame.controller = owner // Unknown ownership must remain reachable for drain.
            return false
        }
        frame.controller = owner
        return true
    }
    /** For actual application-wide trim only, never an arbitrary FilmEngine.close(). */
    fun retireAll() { active.get()?.retire(GpuRenderCode.CANCELLED) }
}

/** Control nonces never participate in film or grain random-number streams. */
internal object GpuScienceControlIdentity {
    fun positiveSeed(): Long = (SecureRandom().nextLong() ushr 1).coerceIn(1L, Long.MAX_VALUE - 1L)
    fun next(counter: AtomicLong): Long {
        while (true) {
            val previous = counter.get()
            if (previous < 0L || previous == Long.MAX_VALUE) return 0L
            val next = previous + 1L
            if (counter.compareAndSet(previous, next)) return next
        }
    }
}
