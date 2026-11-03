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

internal object GpuRenderIpc {
    const val RUN = 1
    const val CANCEL = 2
    const val RESULT = 3
    const val HELLO = 4
    const val READY = 5
    const val TOKEN = "token"
    const val BUDGET = "budget"
    const val DIGEST = "digest"
    const val SUMMARY = "summary"
    const val OPERATION = "operation"
    const val HELPER_DEADLINE_MS = 120_000L
    const val MAIN_DEADLINE_MS = 125_000L
    const val DRAIN_GRACE_MS = 2_000L
}

/** Non-exported, same-UID, single-use full-stage helper. It opens only its immutable token-owned packet, never main app state. */
class GpuRenderService : Service() {
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "gpu-render-watchdog").apply { isDaemon = true }
    }
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "gpu-render-native").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper()) { message -> handle(message); true }
    private val messenger = Messenger(handler)
    private var activeToken = 0L
    private var activeOperation: GpuRenderOperation? = null
    private var startupToken = 0L
    private var startupReady = false
    private val parentWatch = GpuHelperParentWatch { watchdog.execute { terminateOwnProcess() } }
    private var pendingHello: Pair<Long, Messenger>? = null
    private fun helperIdentity(): Boolean = GpuProbeProcess.isRenderHelper(packageName, Application.getProcessName())

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.PERF_BUILD || !helperIdentity()) { stopSelf(); return }
        // This thread never calls native code or waits for a driver/worker/main looper.
        watchdog.schedule({ terminateOwnProcess() }, GpuRenderIpc.HELPER_DEADLINE_MS, TimeUnit.MILLISECONDS)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = runCatching {
            val data = intent?.extras ?: error("Missing token")
            require(data.keySet() == setOf(GpuRenderIpc.TOKEN))
            (data.get(GpuRenderIpc.TOKEN) as Long).also { require(it > 0) }
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
        val response = Message.obtain(null, GpuRenderIpc.READY)
        response.replyTo = messenger
        response.data = Bundle().apply { putLong(GpuRenderIpc.TOKEN, token) }
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
        val token = runCatching { data.get(GpuRenderIpc.TOKEN) as? Long }.getOrNull() ?: return
        if (token <= 0) return
        when (message.what) {
            GpuRenderIpc.HELLO -> {
                if (data.keySet() != setOf(GpuRenderIpc.TOKEN)) return
                val reply = message.replyTo ?: return
                if (!parentWatch.attach(reply.binder)) return
                if (startupReady) sendReady(token, reply)
                else if (pendingHello == null) pendingHello = token to reply
            }
            GpuRenderIpc.RUN -> {
                if (!startupReady || token != startupToken) return
                if (data.keySet() != setOf(GpuRenderIpc.TOKEN, GpuRenderIpc.BUDGET, GpuRenderIpc.DIGEST, GpuRenderIpc.OPERATION)) return
                val operation = runCatching { GpuRenderOperation.fromWire(data.get(GpuRenderIpc.OPERATION)) }.getOrNull() ?: return
                val budget = data.get(GpuRenderIpc.BUDGET) as? Long ?: return
                val digest = data.get(GpuRenderIpc.DIGEST) as? String ?: return
                if (budget !in 1..GpuRenderProtocol.HARD_CAP || !digest.matches(Regex("[0-9a-f]{64}"))) return
                val reply = message.replyTo ?: return
                if (!processGate.claim(token)) return
                activeToken = token
                activeOperation = operation
                worker.execute {
                    val result = try {
                        check(parentWatch.claimNative { GpuHelperProcessLock.acquire(this) })
                        val dir = GpuRenderProtocol.stageDirectory(this, token)
                        val plan = GpuRenderProtocol.parseStagePlan(GpuRenderProtocol.nativePlan(operation, dir.path, token), token, operation)
                        require(plan.digest == digest && plan.combinedPeakBytes == budget)
                        check(parentWatch.isAlive())
                        val raw = GpuRenderProtocol.nativeRun(plan, dir.path)
                        GpuRenderProtocol.parseStageNativeResult(raw, plan)
                    } catch (_: LinkageError) {
                        GpuRenderCode.NATIVE_UNAVAILABLE to null
                    } catch (_: Exception) {
                        GpuRenderCode.INVALID_RESULT to null
                    }
                    try {
                        val response = Message.obtain(null, GpuRenderIpc.RESULT)
                        response.replyTo = messenger
                        response.data = Bundle().apply {
                            putLong(GpuRenderIpc.TOKEN, token)
                            putString(GpuRenderIpc.SUMMARY, GpuRenderProtocol.encodeResult(result.first, result.second))
                        }
                        reply.send(response)
                    } catch (_: Exception) {
                        // Main-process Binder death is not a reason to keep a GPU helper alive.
                    } finally {
                        watchdog.schedule({ terminateOwnProcess() }, 250, TimeUnit.MILLISECONDS)
                    }
                }
            }
            GpuRenderIpc.CANCEL -> {
                if (data.keySet() != setOf(GpuRenderIpc.TOKEN)) return
                if (startupReady && token == startupToken && (activeToken == 0L || processGate.owns(token))) {
                    // Arm the hard stop before cooperative native cancellation, which itself
                    // must never be able to hold up the watchdog.
                    watchdog.schedule({ terminateOwnProcess() }, 100, TimeUnit.MILLISECONDS)
                    if (activeToken != 0L) Thread({
                        runCatching { activeOperation?.let { GpuRenderProtocol.nativeCancel(it, token) } }
                    }, "gpu-render-cancel").apply { isDaemon = true; start() }
                }
            }
        }
    }
    companion object {
        private val startupGate = GpuProbeProcessGate()
        private val processGate = GpuProbeProcessGate()
    }
}
