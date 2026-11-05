package dev.filmroom.diagnostics

import android.content.Context
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import dev.filmroom.engine.GpuRenderCode
import dev.filmroom.engine.GpuRenderOperation
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local queue, independent of Diagnostics.initialize (which :gpu skips).
 * record() does no filesystem work or waiting on the caller, including main.
 * recordBeforeBlocking() is the explicit worker-thread durability boundary: true
 * means its record was written and synced before returning. Failure is advisory
 * and must never change CPU/science results or hold up the hard-kill watchdog.
 */
internal object GpuLifecycleRecorder {
    const val ACK_TIMEOUT_MS = 2_000L
    @Volatile private var context: Context? = null
    @Volatile private var healthy = true
    private val durability = RenderLifecycleDurabilityGate(ACK_TIMEOUT_MS)
    private val dropped = AtomicLong()
    private val run = UUID.randomUUID().toString().replace("-", "")
    internal val processRunId: String get() = run
    private val dispatcher = RenderLifecycleDispatcher()
    // Accessed only by the single diagnostic writer.
    private var journal: RenderLifecycleJournal? = null
    private var sequence = 0L
    private val nativeSequences = LinkedHashMap<Long, NativeGpuLifecycle>()
    private val parents = LinkedHashMap<Long, Long>()

    /** Capture a context only. Safe in Application or a helper service on main. */
    fun configure(context: Context) { this.context = context.applicationContext ?: context }

    fun record(
        context: Context, token: Long, operation: GpuRenderOperation?, phase: GpuLifecyclePhase,
        code: GpuRenderCode? = null, elapsedMs: Long? = null,
        scope: RenderLifecycleScope = RenderLifecycleScope.GPU_STAGE, parentToken: Long? = null, memory: RenderMemoryInfo? = null, operationId: Long? = null, variant: RenderLifecycleVariant? = null,
    ): CompletableFuture<Boolean> {
        configure(context)
        if (operationId != null && operationId < 0 || memory != null && !memory.valid() || token < 0 || parentToken != null && parentToken <= 0 || phase == GpuLifecyclePhase.NATIVE_PHASE || elapsedMs != null && elapsedMs !in 0..MAX_LIFECYCLE_DURATION_MS) return rejected()
        return enqueue(token, operation, phase, code, elapsedMs, scope, parentToken = parentToken, memory = memory, operationId = operationId, variant = variant)
    }

    fun link(context: Context, token: Long, operation: GpuRenderOperation, parentToken: Long): CompletableFuture<Boolean> =
        record(context, token, operation, GpuLifecyclePhase.LINK, parentToken = parentToken)

    fun recordBeforeBlocking(
        context: Context, token: Long, operation: GpuRenderOperation?, phase: GpuLifecyclePhase,
        code: GpuRenderCode? = null, elapsedMs: Long? = null,
        scope: RenderLifecycleScope = RenderLifecycleScope.GPU_STAGE, parentToken: Long? = null, memory: RenderMemoryInfo? = null, operationId: Long? = null, variant: RenderLifecycleVariant? = null,
    ): Boolean {
        configure(context)
        if (isMainThread() || durability.suspended) { rejected(); return false }
        return await(record(context, token, operation, phase, code, elapsedMs, scope, parentToken, memory, operationId, variant))
    }

    fun recordNative(raw: String): CompletableFuture<Boolean> {
        val sample = NativeGpuLifecycleParser.parse(raw) ?: return rejected()
        return enqueue(sample.token, sample.operation, GpuLifecyclePhase.NATIVE_PHASE, null, null,
            RenderLifecycleScope.GPU_STAGE, sample.native)
    }

    /** Called synchronously by JNI on its worker before a potentially blocking phase. */
    fun recordNativeBeforeBlocking(raw: String): Boolean {
        if (isMainThread() || durability.suspended) { rejected(); return false }
        return await(recordNative(raw))
    }

    private fun enqueue(
        token: Long, operation: GpuRenderOperation?, phase: GpuLifecyclePhase,
        code: GpuRenderCode?, elapsedMs: Long?, scope: RenderLifecycleScope,
        native: NativeGpuLifecycle? = null, parentToken: Long? = null, memory: RenderMemoryInfo? = null, operationId: Long? = null, variant: RenderLifecycleVariant? = null,
    ): CompletableFuture<Boolean> {
        val app = context ?: return rejected()
        val wallMs = System.currentTimeMillis(); val uptimeMs = SystemClock.elapsedRealtime()
        val future = dispatcher.submit {
            try {
                // Polling the same native sequence is not progress and must not
                // displace a useful phase breadcrumb. Lifecycle WAIT is separate.
                if (native != null) {
                    val previous = nativeSequences[token]
                    if (!shouldRecordNativeSample(previous, native)) return@submit true
                }
                val target = journal ?: RenderLifecycleJournal(RenderLifecycleJournal.directoryUnder(app.filesDir)).also { journal = it }
                if (parentToken != null) {
                    parents[token] = parentToken
                    while (parents.size > 256) parents.remove(parents.keys.first())
                }
                val linkedParent = parentToken ?: if (scope == RenderLifecycleScope.GPU_STAGE) parents[token] else null
                val event = RenderLifecycleEvent(wallMs, uptimeMs, run, Process.myPid(), ++sequence,
                    token, scope, operation, phase, code, elapsedMs, native, linkedParent, memory, operationId, variant)
                val persisted = target.append(event)
                if (persisted && native != null) {
                    nativeSequences[token] = native.copy(elapsedMs = 0, totalElapsedMs = 0)
                    while (nativeSequences.size > 256) nativeSequences.remove(nativeSequences.keys.first())
                }
                persisted
            } catch (_: Exception) { false }
        }
        future.whenComplete { persisted, failure -> if (persisted != true || failure != null) { healthy = false; dropped.incrementAndGet() } }
        return future
    }

    private fun await(future: CompletableFuture<Boolean>): Boolean = durability.await(future).also {
        if (!it) healthy = false
    }
    private fun rejected(): CompletableFuture<Boolean> {
        healthy = false; dropped.incrementAndGet(); return CompletableFuture.completedFuture(false)
    }
    private fun isMainThread(): Boolean = Looper.getMainLooper()?.let { Looper.myLooper() === it } == true

    /** Called on the share IO worker, never on the hard-kill watchdog or UI. */
    fun snapshot(minutes: Int, now: Long): RenderLifecycleSnapshot {
        val app = context ?: return RenderLifecycleSnapshot(storageHealthy = false, droppedThisProcess = dropped.get())
        // The queue barrier is bounded. Actual filesystem IO, as elsewhere in
        // diagnostic sharing, has no hard OS latency guarantee and runs off main.
        val drained = if (isMainThread()) false else runCatching {
            dispatcher.submit { true }.get(500, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        return runCatching { RenderLifecycleJournal.snapshot(RenderLifecycleJournal.directoryUnder(app.filesDir), minutes, now) }
            .getOrElse { RenderLifecycleSnapshot(storageHealthy = false) }
            .let { it.copy(storageHealthy = it.storageHealthy && healthy && drained, droppedThisProcess = dropped.get()) }
    }
}

/** Bounded queue: even a stuck fsync cannot accumulate unbounded closures. */
internal class RenderLifecycleDispatcher(capacity: Int = 128) : AutoCloseable {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity),
        { task -> Thread(task, "render-lifecycle-writer").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    fun submit(action: () -> Boolean): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        try {
            executor.execute {
                if (!result.isCancelled) {
                    try { result.complete(action()) } catch (_: Exception) { result.complete(false) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { result.complete(false) }
        return result
    }
    override fun close() { executor.shutdownNow() }
}

/** One timed-out storage write suspends later durability waits for this process. */
internal class RenderLifecycleDurabilityGate(private val timeoutMs: Long) {
    @Volatile var suspended: Boolean = false
        private set
    fun await(future: CompletableFuture<Boolean>): Boolean {
        if (suspended) return false
        return try { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: java.util.concurrent.TimeoutException) { suspended = true; false }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        catch (_: Exception) { false }
    }
}

/** Elapsed-only samples are waits; terminal/failure changes are always new evidence. */
internal fun shouldRecordNativeSample(previous: NativeGpuLifecycle?, sample: NativeGpuLifecycle): Boolean =
    previous == null || sample.sequence >= previous.sequence &&
        sample.copy(elapsedMs = 0, totalElapsedMs = 0) != previous.copy(elapsedMs = 0, totalElapsedMs = 0)
