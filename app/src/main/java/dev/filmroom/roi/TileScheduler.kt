package dev.filmroom.roi

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UI-only prototype. One running worker, latest visible demand (no accumulated viewport queue).
 * Main-confined cache and publication. Viewport changes do not invalidate same-key results.
 * Never recycle a published bitmap: Canvas/display-list references can outlive cache membership.
 * Byte budget covers cache, not low-res underlay + one in-flight tile + transient GPU/GC ownership.
 */
class TileScheduler(
    private val provider: TileProvider,
    val byteBudget: Long = 8L * 1024 * 1024,
    private val allowApproximateTestOnly: Boolean = false,
    worker: Executor? = null,
    private val onChanged: () -> Unit = {}
) : AutoCloseable {
    init { require(byteBudget >= 258L * 258 * 4) }
    private val ownedWorker = if (worker == null) Executors.newSingleThreadExecutor { r -> Thread(r, "roi-ui-prototype").apply { isDaemon = true } } else null
    private val worker = worker ?: ownedWorker!!
    private val main = Handler(Looper.getMainLooper())
    private val cache = LinkedHashMap<TileId, TileResult>(16, .75f, true)
    private var demand = emptyList<TileId>()
    private var visibleDemand = emptyList<TileId>()
    private var paused = false
    private val failed = mutableSetOf<TileId>()
    private var running = false
    private var closed = false
    private var cancellation = AtomicBoolean(false)
    var key: RenderKey? = null; private set
    var generation = 0L; private set
    var viewportRevision = 0L; private set
    var cacheBytes = 0L; private set
    var lastError: String? = null; private set
    internal var lastFailure: Throwable? = null; private set
    val runningCount get() = if (running) 1 else 0
    private fun mainOnly() { check(Looper.myLooper() == Looper.getMainLooper()) }
    fun update(k: RenderKey, visible: List<TileId>) {
        mainOnly(); check(!closed)
        val changedKey = key != k
        if (changedKey) {
            generation++; key = k
            cancellation.set(true); cancellation = AtomicBoolean(false)
            cache.clear(); cacheBytes = 0; failed.clear()
        }
        viewportRevision++
        val validVisible = visible.distinct().filter { it.x >= 0 && it.y >= 0 && it.x.toLong()*256 < k.virtualFullWidth && it.y.toLong()*256 < k.virtualFullHeight }
        if (changedKey || validVisible != visibleDemand) {
            visibleDemand = validVisible
            var reserved = 0L
            // Admit in priority order BEFORE rendering. Never trade center tiles for far tiles.
            demand = validVisible.filter { id ->
                val sample = id.rect(k).sampleRect(k)
                val bytes = sample.width.toLong() * sample.height * 4
                if (reserved + bytes <= byteBudget) { reserved += bytes; true } else false
            }
            failed.clear(); lastError = null; lastFailure = null
        }
        demand.forEach { cache[it] }
        onChanged(); pump()
    }
    fun drawableTiles(): List<TileResult> {
        mainOnly()
        return demand.mapNotNull { cache[it] }.filter { !it.bitmap.isRecycled }
    }
    fun forEachDrawable(draw: (TileResult) -> Unit) {
        mainOnly()
        for (index in demand.indices) {
            val tile = cache[demand[index]] ?: continue
            if (!tile.bitmap.isRecycled) draw(tile)
        }
    }
    fun cached(id: TileId): TileResult? { mainOnly(); return cache[id] }
    fun setPaused(value: Boolean) { mainOnly(); if (closed) return; paused = value; if (!paused) pump() }
    fun admittedTiles(): List<TileId> { mainOnly(); return demand.toList() }
    private fun pump() {
        if (closed || paused || running) return
        val k = key ?: return
        val id = demand.firstOrNull { cache[it] == null && it !in failed } ?: return
        val rect = id.rect(k); val sample = rect.sampleRect(k); val g = generation; val token = cancellation
        running = true
        try {
            worker.execute {
                var result: TileResult? = null
                var error: Throwable? = null
                try {
                    check(Looper.myLooper() != Looper.getMainLooper()) { "Provider must not run on main" }
                    result = provider.render(k, rect) { token.get() }
                } catch (t: Exception) { error = t } catch (t: LinkageError) { error = t } catch (t: OutOfMemoryError) { error = t }
                val completed = result; val failure = error
                main.post {
                    running = false
                    val valid = !closed && g == generation && k == key && !token.get()
                    val accepted = completed != null && completed.key == k && completed.coreRect == rect && completed.sampleRect == sample &&
                        !completed.bitmap.isRecycled && completed.bitmap.width == sample.width && completed.bitmap.height == sample.height &&
                        completed.bitmap.config == Bitmap.Config.ARGB_8888 &&
                        completed.bitmap.allocationByteCount.toLong() == sample.width.toLong()*sample.height*4 &&
                        (completed.quality.accepts(k) || (allowApproximateTestOnly && completed.quality==TileQuality.ApproximateTestOnly))
                    if (valid && accepted) {
                        val tile = completed!!
                        val bytes = tile.bitmap.allocationByteCount.toLong()
                        // Protect all currently admitted visible tiles; evict only offscreen LRU.
                        while (cacheBytes + bytes > byteBudget) {
                            val victim = cache.entries.firstOrNull { it.key !in demand } ?: break
                            cacheBytes -= victim.value.bitmap.allocationByteCount
                            cache.remove(victim.key) // Never recycle published ownership.
                        }
                        if (cacheBytes + bytes <= byteBudget) {
                            cache.put(id, tile)?.let { cacheBytes -= it.bitmap.allocationByteCount }
                            cacheBytes += bytes
                        } else {
                            // Offscreen completion may not evict priority-resident visible data.
                            tile.bitmap.recycle() // Never published.
                        }
                    } else {
                        // This result has never been published, so exclusive ownership makes recycle safe.
                        completed?.bitmap?.let { if (!it.isRecycled) it.recycle() }
                        if (valid) { failed += id; lastFailure=failure;lastError = failure?.message ?: "Rejected tile contract/quality" }
                    }
                    onChanged()
                    // A failed request retries only after changed demand, never identical updates.
                    // Successfully admitted output is resident, not a render/evict loop.
                    pump()
                }
            }
        } catch (e: RuntimeException) {
            running = false; failed += id; lastFailure=e;lastError = e.message ?: "Worker unavailable"; onChanged()
        } catch (e: OutOfMemoryError) {
            running = false; failed += id; lastFailure=e;lastError = "Insufficient detail worker memory"; onChanged()
        }
    }
    override fun close() {
        mainOnly(); closed = true; cancellation.set(true)
        demand = emptyList(); visibleDemand = emptyList(); cache.clear(); cacheBytes = 0
        // Do not interrupt a potentially shared native operation, or recycle display-list-owned data.
        ownedWorker?.shutdown() // No interruption; queued/running sole task owns its cancellation token.
    }
}
