package dev.filmroom.engine

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import dev.filmroom.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object GpuProbeIpc {
    const val RUN = 1
    const val CANCEL = 2
    const val RESULT = 3
    const val HELLO = 4
    const val READY = 5
    const val TOKEN = "token"
    const val BUDGET = "budget"
    const val DIGEST = "digest"
    const val SUMMARY = "summary"
    const val HELPER_DEADLINE_MS = 60_000L
    const val MAIN_DEADLINE_MS = 65_000L
}

/** Non-exported, same-UID, single-use helper. It never opens originals or normal app state. */
class GpuProbeService : Service() {
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "gpu-probe-watchdog").apply { isDaemon = true }
    }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "gpu-probe-native").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper()) { message -> handle(message); true }
    private val messenger = Messenger(handler)
    private var activeToken = 0L
    private var startupToken = 0L
    private var startupReady = false
    private val parentWatch = GpuHelperParentWatch { watchdog.execute { terminateOwnProcess() } }
    private var pendingHello: Pair<Long, Messenger>? = null
    private fun helperIdentity(): Boolean = GpuProbeProcess.isHelper(packageName, Application.getProcessName())

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.PERF_BUILD || !helperIdentity()) { stopSelf(); return }
        // This thread never calls native code or waits for a driver/worker/main looper.
        watchdog.schedule({ terminateOwnProcess() }, GpuProbeIpc.HELPER_DEADLINE_MS, TimeUnit.MILLISECONDS)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = runCatching {
            val data = intent?.extras ?: error("Missing token")
            require(data.keySet() == setOf(GpuProbeIpc.TOKEN))
            (data.get(GpuProbeIpc.TOKEN) as Long).also { require(it > 0) }
        }.getOrNull()
        if (!BuildConfig.PERF_BUILD || !helperIdentity() || token == null || !startupGate.claim(token)) {
            watchdog.schedule({ terminateOwnProcess() }, 0, TimeUnit.MILLISECONDS)
        } else {
            startupToken = token
            // Run after onStartCommand returns and Android processes START_NOT_STICKY.
            handler.post {
                startupReady = true
                pendingHello?.let { (request, reply) -> sendReady(request, reply) }
                pendingHello = null
            }
        }
        return START_NOT_STICKY
    }
    private fun sendReady(token: Long, reply: Messenger) {
        if (!startupReady || token != startupToken) return
        val response = Message.obtain(null, GpuProbeIpc.READY)
        response.replyTo = messenger
        response.data = Bundle().apply { putLong(GpuProbeIpc.TOKEN, token) }
        runCatching { reply.send(response) }
    }
    override fun onBind(intent: Intent?): IBinder? =
        if (BuildConfig.PERF_BUILD && helperIdentity()) messenger.binder else null
    override fun onUnbind(intent: Intent?): Boolean { terminateOwnProcess(); return false }
    override fun onDestroy() {
        if (helperIdentity()) terminateOwnProcess()
        worker.shutdownNow()
        watchdog.shutdownNow()
        super.onDestroy()
    }
    private fun terminateOwnProcess() {
        // Never accept a PID over IPC; killing the main process is impossible through this API.
        if (helperIdentity()) Process.killProcess(Process.myPid())
    }
    private fun handle(message: Message) {
        if (!BuildConfig.PERF_BUILD || !helperIdentity() || message.sendingUid != Process.myUid()) return
        val data = message.data
        val token = runCatching { data.get(GpuProbeIpc.TOKEN) as? Long }.getOrNull() ?: return
        if (token <= 0) return
        when (message.what) {
            GpuProbeIpc.HELLO -> {
                if (data.keySet() != setOf(GpuProbeIpc.TOKEN)) return
                val reply = message.replyTo ?: return
                if (!parentWatch.attach(reply.binder)) return
                if (startupReady) sendReady(token, reply)
                else if (pendingHello == null) pendingHello = token to reply
            }
            GpuProbeIpc.RUN -> {
                if (!startupReady || token != startupToken) return
                if (data.keySet() != setOf(GpuProbeIpc.TOKEN, GpuProbeIpc.BUDGET, GpuProbeIpc.DIGEST)) return
                val budget = data.get(GpuProbeIpc.BUDGET) as? Long ?: return
                val digest = data.get(GpuProbeIpc.DIGEST) as? String ?: return
                if (budget !in 1..GpuProbeProtocol.COMBINED_CAP || !digest.matches(Regex("[0-9a-f]{64}"))) return
                val reply = message.replyTo ?: return
                if (!processGate.claim(token)) return
                activeToken = token
                worker.execute {
                    val summary = try {
                        check(parentWatch.claimNative { GpuHelperProcessLock.acquire(this) })
                        val dir = GpuProbePaths.snapshot(this, token)
                        val plan = GpuProbeProtocol.parsePlan(NativeGpuProbeBridge.nativePlanGpuDiffusionProbe(dir.path, token), token)
                        require(plan.manifestSha256 == digest && plan.combinedPeakBytes == budget)
                        check(parentWatch.isAlive())
                        val raw = NativeGpuProbeBridge.nativeRunGpuDiffusionProbe(dir.path, budget, token, digest)
                        GpuProbeProtocol.parseNativeResult(raw, plan)
                    } catch (_: LinkageError) {
                        GpuProbeSummary(GpuProbeStatus.NATIVE_UNAVAILABLE)
                    } catch (_: Exception) {
                        GpuProbeSummary(GpuProbeStatus.PROTOCOL_REJECTED)
                    }
                    try {
                        val response = Message.obtain(null, GpuProbeIpc.RESULT)
                        response.replyTo = messenger
                        response.data = Bundle().apply {
                            putLong(GpuProbeIpc.TOKEN, token)
                            putString(GpuProbeIpc.SUMMARY, summary.toSafeJson())
                        }
                        reply.send(response)
                    } catch (_: Exception) {
                        // Main-process Binder death is not a reason to keep a GPU helper alive.
                    } finally {
                        watchdog.schedule({ terminateOwnProcess() }, 250, TimeUnit.MILLISECONDS)
                    }
                }
            }
            GpuProbeIpc.CANCEL -> {
                if (data.keySet() != setOf(GpuProbeIpc.TOKEN)) return
                if (startupReady && token == startupToken && (activeToken == 0L || processGate.owns(token))) {
                    // Arm the hard stop before cooperative native cancellation, which itself
                    // must never be able to hold up the watchdog.
                    watchdog.schedule({ terminateOwnProcess() }, 100, TimeUnit.MILLISECONDS)
                    if (activeToken != 0L) Thread({
                        runCatching { NativeGpuProbeBridge.nativeCancelGpuDiffusionProbe(token) }
                    }, "gpu-probe-cancel").apply { isDaemon = true; start() }
                }
            }
        }
    }
    companion object {
        private val startupGate = GpuProbeProcessGate()
        private val processGate = GpuProbeProcessGate()
    }
}
