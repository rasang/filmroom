package dev.filmroom.engine

import android.os.SystemClock

/** One preview's shared GPU opportunity window, not a deadline for CPU rendering.
 * Starts at the first eligible begin; construction and parent admission do not spend it.
 * Expiry never proves helper death or authorizes releasing memory/files.
 */
internal class PreviewGpuBudget(
    private val durationMillis: Long = DEFAULT_MILLIS,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    init { require(durationMillis in 1..GpuRenderIpc.MAIN_DEADLINE_MS) }
    private var deadline: Long? = null
    fun nowMillis(): Long = clock()
    @Synchronized fun start() { if (deadline == null) deadline = Math.addExact(nowMillis(), durationMillis) }
    @Synchronized fun expired(): Boolean = deadline?.let { nowMillis() >= it } ?: false
    @Synchronized fun limitDeadline(ordinaryDeadline: Long): Long = deadline?.let { minOf(it, ordinaryDeadline) } ?: ordinaryDeadline
    companion object { const val DEFAULT_MILLIS = 8_000L }
}
