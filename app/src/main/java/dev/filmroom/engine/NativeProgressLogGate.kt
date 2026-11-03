package dev.filmroom.engine

/** Record stage transitions immediately and only genuine native updates as bounded heartbeats. */
internal class NativeProgressLogGate(private val intervalMs: Long = 10_000L) {
    private var lastPass = -1L
    private var lastPhase: dev.filmroom.diagnostics.NativePhase? = null
    private var lastSerial = 0L
    private var lastTime = 0L
    fun shouldLog(value: EngineProgress, now: Long): Boolean {
        val changed = value.passId != lastPass || value.phase != lastPhase
        val heartbeat = value.updateSerial > 0 && value.updateSerial != lastSerial && now >= lastTime && now - lastTime >= intervalMs
        if (!changed && !heartbeat) return false
        lastPass = value.passId
        lastPhase = value.phase
        lastSerial = value.updateSerial
        lastTime = now
        return true
    }
}
