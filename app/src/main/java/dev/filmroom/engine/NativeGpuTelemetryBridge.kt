package dev.filmroom.engine

import androidx.annotation.Keep
import dev.filmroom.diagnostics.GpuLifecycleRecorder

/** Called only by native helper workers, never by the hard-kill watchdog.
 * The recorder strictly parses the closed numeric schema; no driver text is accepted.
 * Persistence failure reports false without throwing into the render lifecycle.
 */
@Keep
object NativeGpuTelemetryBridge {
    @Keep @JvmStatic fun onNativePhase(raw: String): Boolean =
        runCatching { GpuLifecycleRecorder.recordNativeBeforeBlocking(raw) }.getOrDefault(false)
}
