package dev.filmroom.engine

import android.content.Context
import androidx.annotation.Keep
import kotlinx.coroutines.NonCancellable
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
) {
    constructor(context: Context, cameraEnabled: Boolean, scannerEnabled: Boolean, opticalEnabled: Boolean = false, exposureEnabled: Boolean = false, dirEnabled: Boolean = false, scienceEnabled: Boolean = false) : this(
        context,
        operations(cameraEnabled, scannerEnabled, opticalEnabled, exposureEnabled, dirEnabled, scienceEnabled),
        null,
    )
    private val context = context.applicationContext
    private val lock = Any()
    private var closing = false
    private val delegates = operations.associateWith { operation ->
        testHooks?.get(operation)?.let { GpuRenderDelegate.forTesting(this.context, it, operation, previewBudget) }
            ?: GpuRenderDelegate.forOperation(this.context, operation, previewBudget)
    }
    private val usedOperations = mutableSetOf<GpuRenderOperation>()
    private val routes = mutableMapOf<Long, GpuRenderDelegate>()

    fun availableDiskBytes(): Long = synchronized(lock) {
        if (closing) 0L else delegates.values.firstOrNull()?.availableDiskBytes() ?: 0L
    }
    /** A route is retained even when begin throws: the outer finally still owns its drain. */
    fun begin(requestJson: String): Int {
        val request = runCatching { GpuRenderProtocol.parseRequest(requestJson, context) }.getOrNull() ?: return 0
        return synchronized(lock) {
            if (closing || request.token in routes || request.operation in usedOperations) return@synchronized 0
            val delegate = delegates[request.operation] ?: return@synchronized 0
            previewBudget?.start()
            usedOperations.add(request.operation)
            routes[request.token] = delegate
            // The delegates' shared active owner additionally forbids overlap.
            delegate.begin(requestJson)
        }
    }
    fun poll(token: Long): String = synchronized(lock) { routes[token] }?.poll(token)
        ?: GpuRenderPoll(token, GpuRenderStatus.NOT_STARTED, GpuRenderCode.NO_JOB, 0).json()
    fun cancel(token: Long) { synchronized(lock) { routes[token] }?.cancel(token) }

    /** Close routing before taking the snapshot; false means passive quarantine. */
    suspend fun awaitSafeTermination(): Boolean = withContext(NonCancellable) {
        val owned = synchronized(lock) { closing = true; delegates.values.toList() }
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
        fun forPreview(context: Context, cameraEnabled: Boolean, scannerEnabled: Boolean, opticalEnabled: Boolean = false, exposureEnabled: Boolean = false, dirEnabled: Boolean = false, scienceEnabled: Boolean = false): GpuExportRouter =
            GpuExportRouter(context, operations(cameraEnabled, scannerEnabled, opticalEnabled, exposureEnabled, dirEnabled, scienceEnabled), null, PreviewGpuBudget())
        private fun operations(cameraEnabled: Boolean, scannerEnabled: Boolean, opticalEnabled: Boolean, exposureEnabled: Boolean, dirEnabled: Boolean, scienceEnabled: Boolean): Set<GpuRenderOperation> = buildSet {
            if (cameraEnabled) add(GpuRenderOperation.CAMERA_DIFFUSION)
            if (scannerEnabled) add(GpuRenderOperation.PRINT_SCAN)
            if (opticalEnabled) add(GpuRenderOperation.FILM_OPTICS)
            if (exposureEnabled) add(GpuRenderOperation.FILM_EXPOSURE)
            if (dirEnabled) add(GpuRenderOperation.FILM_DIR)
            if (scienceEnabled && scannerEnabled) add(GpuRenderOperation.FINISH_TAIL)
            if (scienceEnabled) addAll(setOf(GpuRenderOperation.AUTO_EXPOSURE, GpuRenderOperation.HIGHLIGHT_BOOST, GpuRenderOperation.EXPOSURE_LOG, GpuRenderOperation.FILM_GRAIN, GpuRenderOperation.PRINT_EXPOSURE, GpuRenderOperation.PRINT_DIFFUSION, GpuRenderOperation.SCAN_TAIL))
        }
        fun forTesting(context: Context, hooks: Map<GpuRenderOperation, GpuRenderTestHooks>, previewBudget: PreviewGpuBudget? = null) = GpuExportRouter(context, hooks.keys, hooks, previewBudget)
    }
}
