package dev.filmroom.engine

import java.util.concurrent.atomic.AtomicLong

/** Exact identity check, shared by Application startup and the helper's self-kill guard. */
object GpuProbeProcess {
    const val SUFFIX = ":gpu_probe"
    const val RENDER_SUFFIX = ":gpu_render"
    const val SCIENCE_SUFFIX = ":gpu_science"
    fun isHelper(packageName: String, processName: String?): Boolean =
        processName == packageName + SUFFIX
    fun isRenderHelper(packageName: String, processName: String?): Boolean = processName == packageName + RENDER_SUFFIX
    fun isScienceHelper(packageName: String, processName: String?): Boolean = processName == packageName + SCIENCE_SUFFIX
    fun isWorker(packageName: String, processName: String?): Boolean = isHelper(packageName, processName) || isRenderHelper(packageName, processName) || isScienceHelper(packageName, processName)
}

/** Deliberately never reset: a new GPU invocation requires a fresh helper process. */
internal class GpuProbeProcessGate {
    private val claimed = AtomicLong(0)
    fun claim(token: Long): Boolean = token > 0 && claimed.compareAndSet(0, token)
    fun owns(token: Long): Boolean = token > 0 && claimed.get() == token
}

/** Timeout/cancel/result never prove death. Only a permanently revoked, never-sent RUN proves no native work. */
internal class GpuProbeLifetime(val token: Long) {
    init { require(token > 0) }
    var helperRequested = false; private set
    var startupAcknowledged = false; private set
    var deathConfirmed = false; private set
    var stopping = false; private set
    var runAttempted = false; private set
    var workRevokedBeforeRun = false; private set
    var resultReceived = false; private set
    var finished = false; private set
    fun requestHelper(): Boolean {
        if (helperRequested || stopping || finished) return false
        helperRequested = true
        return true
    }
    fun stop(callbackToken: Long): Boolean {
        if (callbackToken != token || finished) return false
        stopping = true
        return true
    }
    fun attemptRun(callbackToken: Long): Boolean {
        if (callbackToken != token || finished || stopping || !startupAcknowledged || runAttempted || workRevokedBeforeRun) return false
        runAttempted = true // Must precede Messenger.send, including throwing/uncertain sends.
        return true
    }
    fun revokeBeforeRun(callbackToken: Long): Boolean {
        if (callbackToken != token || finished || runAttempted || !stopping) return false
        workRevokedBeforeRun = true
        return true
    }
    fun result(callbackToken: Long): Boolean {
        if (callbackToken != token || finished || deathConfirmed || stopping || resultReceived || !runAttempted) return false
        resultReceived = true
        stopping = true
        return true
    }
    fun acknowledgeStartup(callbackToken: Long): Boolean {
        if (callbackToken != token || finished || !helperRequested || deathConfirmed) return false
        startupAcknowledged = true
        return true
    }
    fun died(callbackToken: Long): Boolean {
        if (callbackToken != token || finished || !helperRequested || !startupAcknowledged || deathConfirmed) return false
        deathConfirmed = true
        return true
    }
    fun bindingNotCreated() {
        check(helperRequested && !deathConfirmed)
        helperRequested = false
    }
    val canRelease: Boolean get() = !helperRequested || deathConfirmed || workRevokedBeforeRun
    fun finish(): Boolean {
        if (finished || !canRelease) return false
        finished = true
        return true
    }
}
