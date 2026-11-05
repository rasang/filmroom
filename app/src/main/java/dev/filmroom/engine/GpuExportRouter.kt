package dev.filmroom.engine

import android.content.Context
import androidx.annotation.Keep
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext

/**
 * Export-scoped, closed typed routing. Each registered stage owns a different
 * one-shot delegate; no helper process gate or delegate attempt is ever reset.
 */
@Keep
class GpuExportRouter private constructor(
    context: Context,
    operations: Set<GpuRenderOperation>,
    testHooks: Map<GpuRenderOperation, GpuRenderTestHooks>?,
    private val previewBudget: PreviewGpuBudget? = null,
    private val parentToken: Long? = null,
) {
    constructor(context: Context, cameraEnabled: Boolean, scannerEnabled: Boolean, opticalEnabled: Boolean = false, exposureEnabled: Boolean = false, dirEnabled: Boolean = false, scienceEnabled: Boolean = false, parentToken: Long? = null) : this(
        context,
        operations(cameraEnabled, scannerEnabled, opticalEnabled, exposureEnabled, dirEnabled, scienceEnabled),
        null,
        null,
        parentToken,
    )
    internal val hasPreviewOpportunityBudget: Boolean get() = previewBudget != null
    private val context = context.applicationContext
    private val lock = Any()
    private var closing = false
    private var beginning = 0
    private var beginsFinished = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val delegates = operations.associateWith { operation ->
        testHooks?.get(operation)?.let { GpuRenderDelegate.forTesting(this.context, it, operation, previewBudget) }
            ?: GpuRenderDelegate.forOperation(this.context, operation, previewBudget)
    }
    private val usedOperations = mutableSetOf<GpuRenderOperation>()
    private val cancelledTokens = mutableSetOf<Long>()
    private val routes = mutableMapOf<Long, GpuRenderDelegate>()

    fun availableDiskBytes(): Long = synchronized(lock) {
        if (closing) 0L else delegates.values.firstOrNull()?.availableDiskBytes() ?: 0L
    }
    /** A route is retained even when begin throws: the outer finally still owns its drain. */
    fun begin(requestJson: String): Int {
        val request = runCatching { GpuRenderProtocol.parseRequest(requestJson, context) }.getOrNull() ?: return 0
        val delegate = synchronized(lock) {
            if (closing || request.token in routes || request.operation in usedOperations) return 0
            val selected = delegates[request.operation] ?: return 0
            previewBudget?.start()
            usedOperations.add(request.operation)
            routes[request.token] = selected
            if (beginning++ == 0) beginsFinished = CompletableDeferred()
            selected
        }
        // Planning and durable breadcrumbs may block; never hold the routing lock.
        // Closing waits for every published begin before deciding release is safe.
        return try {
            parentToken?.takeIf { it > 0 }?.let { parent -> runCatching { dev.filmroom.diagnostics.GpuLifecycleRecorder.link(context, request.token, request.operation, parent) } }
            delegate.begin(requestJson) { synchronized(lock) { closing || request.token in cancelledTokens } } }
        finally { synchronized(lock) { if (--beginning == 0) beginsFinished.complete(Unit) } }
    }

    fun poll(token: Long): String = synchronized(lock) { routes[token] }?.poll(token)
        ?: GpuRenderPoll(token, GpuRenderStatus.NOT_STARTED, GpuRenderCode.NO_JOB, 0).json()
    fun cancel(token: Long) {
        val delegate = synchronized(lock) { routes[token]?.also { cancelledTokens.add(token) } }
        delegate?.cancel(token)
    }

    /** Close routing before taking the snapshot; false means passive quarantine. */
    suspend fun awaitSafeTermination(): Boolean = withContext(NonCancellable) {
        val pending = synchronized(lock) { closing = true; beginsFinished }
        pending.await()
        val owned = synchronized(lock) { delegates.values.toList() }
        var safe = true
        for (delegate in owned) {
            if (!runCatching { delegate.awaitSafeTermination() }.getOrDefault(false)) safe = false
        }
        safe
    }
    /** Register only after closing routing, so no late begin can escape ownership. */
    fun whenSafeToRelease(cleanup: () -> Unit) {
        val owned = synchronized(lock) { check(closing); delegates.values.toList() }
        if (owned.isEmpty()) { cleanup(); return }
        val remaining = java.util.concurrent.atomic.AtomicInteger(owned.size)
        owned.forEach { delegate -> delegate.whenSafeToRelease {
            if (remaining.decrementAndGet() == 0) cleanup()
        } }
    }
    internal companion object {
        // No unmeasured short total budget: cold pipeline setup must not silently
        // turn the GPU switch into CPU fallback. Existing hard watchdog remains.
        fun forPreview(context: Context, cameraEnabled: Boolean, scannerEnabled: Boolean, opticalEnabled: Boolean = false, exposureEnabled: Boolean = false, dirEnabled: Boolean = false, scienceEnabled: Boolean = false, parentToken: Long? = null): GpuExportRouter =
            GpuExportRouter(context, operations(cameraEnabled, scannerEnabled, opticalEnabled, exposureEnabled, dirEnabled, scienceEnabled), null, null, parentToken)
        internal fun operations(cameraEnabled: Boolean, scannerEnabled: Boolean, opticalEnabled: Boolean, exposureEnabled: Boolean, dirEnabled: Boolean, scienceEnabled: Boolean): Set<GpuRenderOperation> = buildSet {
            if (cameraEnabled) add(GpuRenderOperation.CAMERA_DIFFUSION)
            if (scannerEnabled) add(GpuRenderOperation.PRINT_SCAN)
            if (opticalEnabled) add(GpuRenderOperation.FILM_OPTICS)
            if (exposureEnabled) add(GpuRenderOperation.FILM_EXPOSURE)
            if (dirEnabled) add(GpuRenderOperation.FILM_DIR)
            if (dirEnabled && scienceEnabled) add(GpuRenderOperation.EXPOSURE_LOG_DIR)
            if (scienceEnabled && scannerEnabled) add(GpuRenderOperation.FINISH_TAIL)
            if (scienceEnabled) addAll(setOf(GpuRenderOperation.AUTO_EXPOSURE, GpuRenderOperation.HIGHLIGHT_BOOST, GpuRenderOperation.EXPOSURE_LOG, GpuRenderOperation.FILM_GRAIN, GpuRenderOperation.PRINT_EXPOSURE, GpuRenderOperation.PRINT_DIFFUSION, GpuRenderOperation.SCAN_TAIL))
            // The original switches admit their versioned file-backed counterpart only.
            if (opticalEnabled) add(GpuRenderOperation.FILM_OPTICS_STREAM_V1)
            if (dirEnabled) add(GpuRenderOperation.FILM_DIR_STREAM_V1)
            if (dirEnabled && scienceEnabled) add(GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1)
            if (scienceEnabled && scannerEnabled) add(GpuRenderOperation.FINISH_TAIL_STREAM_V1)
            if (scienceEnabled) addAll(setOf(GpuRenderOperation.FILM_GRAIN_STREAM_V1, GpuRenderOperation.SCAN_TAIL_STREAM_V1, GpuRenderOperation.HIGHLIGHT_STREAM_V1))
        }
        fun forTesting(context: Context, hooks: Map<GpuRenderOperation, GpuRenderTestHooks>, previewBudget: PreviewGpuBudget? = null) = GpuExportRouter(context, hooks.keys, hooks, previewBudget)
    }
}
