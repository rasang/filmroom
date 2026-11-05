package dev.filmroom.engine

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import androidx.annotation.Keep
import dev.filmroom.BuildConfig
import dev.filmroom.diagnostics.GpuLifecycleRecorder
import dev.filmroom.diagnostics.GpuLifecyclePhase
import dev.filmroom.diagnostics.RenderMemoryInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Per-export JNI callback. Native owns all packet files; this class never deletes them. */
@Keep
class GpuRenderDelegate private constructor(context: Context, private val testHooks: GpuRenderTestHooks?, private val operation: GpuRenderOperation, private val previewBudget: PreviewGpuBudget? = null) {
    constructor(context: Context) : this(context, null, GpuRenderOperation.CAMERA_DIFFUSION)
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val attempted = AtomicBoolean(false)
    private var session: Session? = null
    @Volatile private var rejected: GpuRenderPoll? = null
    private val enabled get() = (testHooks?.enabled ?: BuildConfig.PERF_BUILD) && !GpuProbeProcess.isWorker(this.context.packageName, Application.getProcessName())

    private fun trace(token: Long, phase: GpuLifecyclePhase, code: GpuRenderCode? = null, durable: Boolean = false, memory: RenderMemoryInfo? = null) {
        runCatching {
            if (testHooks != null) { testHooks.lifecycle(token, phase, code, durable); return }
            if (durable) GpuLifecycleRecorder.recordBeforeBlocking(context, token, operation, phase, code, memory = memory)
            else GpuLifecycleRecorder.record(context, token, operation, phase, code, memory = memory)
        }
    }
    init { if (enabled) prepareRoot() }
    private fun prepareRoot(): Boolean = runCatching {
        val root = File(this.context.cacheDir.canonicalFile, "gpu-render")
        if (!root.exists() && !root.mkdir()) return false
        Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && root.canonicalFile == root
    }.getOrDefault(false)

    /** Fresh private-disk admission only; it cannot start a helper. */
    fun availableDiskBytes(): Long = runCatching {
        if (!enabled || Looper.myLooper() == Looper.getMainLooper()) return 0L
        val cache = context.cacheDir.canonicalFile
        val root = File(cache, "gpu-render")
        if (!root.exists() && !root.mkdir()) return 0L
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) || root.canonicalFile != root) return 0L
        (StatFs(cache.path).availableBytes - 64L * GpuRenderProtocol.MIB).coerceAtLeast(0)
    }.getOrDefault(0)

    /** 0 is definitive no-launch; once accepted, only poll's proof-backed terminal is safe. */
    @JvmOverloads
    fun begin(requestJson: String, isCancelled: () -> Boolean = { false }): Int {
        if (!attempted.compareAndSet(false, true) || !enabled || Looper.myLooper() == Looper.getMainLooper()) return 0
        var reservation: RetainedNativeWork? = null
        var diagnosticToken = 0L
        try {
            val request = GpuRenderProtocol.parseRequest(requestJson, context)
            require(request.operation == operation)
            diagnosticToken = request.token
            trace(request.token, GpuLifecyclePhase.STAGE_BEGIN, durable = true)
            rejected = GpuRenderPoll(request.token, GpuRenderStatus.NOT_STARTED, GpuRenderCode.INVALID_REQUEST, request.totalPixels)
            if (isCancelled()) { rejected = rejected?.copy(code = GpuRenderCode.CANCELLED); return 0 }
            if (previewBudget?.expired() == true) { rejected = rejected?.copy(code = GpuRenderCode.PREVIEW_BUDGET_EXHAUSTED); return 0 }
            trace(request.token, GpuLifecyclePhase.PLAN_ENTER, durable = true)
            val plan = GpuRenderProtocol.parseStagePlan(testHooks?.plan?.invoke(request.jobDir, request.token) ?: GpuRenderProtocol.nativePlan(operation, request.jobDir, request.token), request.token, operation)
            require(GpuRenderProtocol.matches(request, plan))
            trace(request.token, GpuLifecyclePhase.PLAN_EXIT)
            if (isCancelled()) { rejected = rejected?.copy(code = GpuRenderCode.CANCELLED); return 0 }
            if (previewBudget?.expired() == true) { rejected = rejected?.copy(code = GpuRenderCode.PREVIEW_BUDGET_EXHAUSTED); return 0 }
            if (!active.compareAndSet(null, this)) { rejected = rejected?.copy(code = GpuRenderCode.GPU_BUSY); return 0 }
            val pressure = AtomicBoolean(false)
            // The CPU export already owns admission and a native worker. Reserve
            // this entire helper peak additionally; never borrow a second worker.
            val onPressure = {
                pressure.set(true)
                synchronized(lock) { session }?.let { stop(it, GpuRenderCode.MEMORY_UNAVAILABLE) }
                Unit
            }
            trace(request.token, GpuLifecyclePhase.RESERVE_ENTER, durable = true, memory = RenderMemoryInfo(requestedBytes = plan.combinedPeakBytes))
            reservation = testHooks?.reserve?.invoke(plan.combinedPeakBytes, onPressure)
                ?: NativeWorkReservations.retainForDetail(plan.combinedPeakBytes, { NativeWorkReservations.snapshot(context) }, onPressure)
            trace(request.token, GpuLifecyclePhase.RESERVED, memory = RenderMemoryInfo(requestedBytes = plan.combinedPeakBytes, chargedBytes = Math.addExact(plan.combinedPeakBytes, 16L * NativeMemoryPolicy.MIB)))
            testHooks?.beforeSession?.invoke()
            val owned = Session(request, plan, reservation)
            synchronized(lock) { session = owned; rejected = null }
            reservation = null
            testHooks?.afterSession?.invoke()
            if (isCancelled()) stop(owned, GpuRenderCode.CANCELLED)
            if (pressure.get()) stop(owned, GpuRenderCode.MEMORY_UNAVAILABLE)
            if (!main.post { start(owned) }) stop(owned, GpuRenderCode.START_REVOKED)
            return 1
        } catch (_: NativeWorkUnavailable) {
            val accepted = synchronized(lock) { session }
            if (accepted != null) { stop(accepted, GpuRenderCode.MEMORY_UNAVAILABLE); return 1 }
            rejected = rejected?.copy(code = GpuRenderCode.MEMORY_UNAVAILABLE)
            reservation?.close(); reservation = null; active.compareAndSet(this, null)
            return 0
        } catch (_: Exception) {
            val accepted = synchronized(lock) { session }
            if (accepted != null) { stop(accepted, GpuRenderCode.START_REVOKED); return 1 }
            reservation?.close(); reservation = null; active.compareAndSet(this, null)
            return 0
        } catch (_: LinkageError) {
            rejected = rejected?.copy(code = GpuRenderCode.NATIVE_UNAVAILABLE)
            val accepted = synchronized(lock) { session }
            if (accepted != null) { stop(accepted, GpuRenderCode.NATIVE_UNAVAILABLE); return 1 }
            reservation?.close(); reservation = null; active.compareAndSet(this, null)
            return 0
        } finally {
            // Also runs for Error/OOM before Session ownership is published.
            // A published session keeps its lease for the independent outer drain.
            if (synchronized(lock) { session } == null) {
                reservation?.close()
                active.compareAndSet(this, null)
                if (diagnosticToken > 0) trace(diagnosticToken, GpuLifecyclePhase.NOT_STARTED, rejected?.code)
            }
        }
    }
    fun poll(token: Long): String {
        val owned = synchronized(lock) { session }
            ?: return rejected?.takeIf { it.token == token }?.json() ?: GpuRenderPoll(token, GpuRenderStatus.NOT_STARTED, GpuRenderCode.NO_JOB, 0).json()
        if (owned.request.token != token) return GpuRenderPoll(token, GpuRenderStatus.STOPPING, GpuRenderCode.INVALID_REQUEST, 0).json()
        val waiting = synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (!owned.done.isCompleted && owned.waitEvents < 60 && now - owned.lastWaitAt >= 2_000) {
                owned.lastWaitAt = now; owned.waitEvents++; true
            } else false
        }
        if (waiting) trace(token, GpuLifecyclePhase.WAIT)
        checkDeaths(owned)
        if (nowMillis() >= owned.deadline) stop(owned, deadlineCode())
        // Unknown death is NOT a terminal status. JNI converts this transport failure
        // into its bounded error exit while retaining may_run packet/scratch roots.
        if (synchronized(lock) { !owned.done.isCompleted && owned.stopAt != null &&
                SystemClock.elapsedRealtime() - owned.stopAt!! >= drainMillis }) {
            quarantine(owned)
            throw IllegalStateException("GPU helper death unconfirmed; ownership quarantined")
        }
        return synchronized(lock) { owned.poll }.json()
    }
    fun cancel(token: Long) {
        synchronized(lock) { session }?.takeIf { it.request.token == token }?.let { stop(it, GpuRenderCode.CANCELLED) }
    }
    private fun nowMillis(): Long = previewBudget?.nowMillis() ?: SystemClock.elapsedRealtime()
    private fun deadlineCode(): GpuRenderCode = if (previewBudget?.expired() == true) GpuRenderCode.PREVIEW_BUDGET_EXHAUSTED else GpuRenderCode.TIMED_OUT
    private val drainMillis get() = testHooks?.drainMillis ?: GpuRenderIpc.DRAIN_GRACE_MS
    /** False transfers cleanup to whenSafeToRelease, never permission to destroy. */
    suspend fun awaitSafeTermination(): Boolean = withContext(NonCancellable) {
        val owned = synchronized(lock) { session } ?: return@withContext true
        if (owned.drainRecorded.compareAndSet(false, true)) trace(owned.request.token, GpuLifecyclePhase.DRAIN_START)
        val safe = runCatching {
            if (!owned.done.isCompleted) stop(owned, GpuRenderCode.CANCELLED)
            withTimeoutOrNull(drainMillis) {
                while (!owned.done.isCompleted) { checkDeaths(owned); delay(25) }
                true
            } == true
        }.getOrDefault(false)
        if (!safe) quarantine(owned)
        if (owned.done.isCompleted && owned.drainEndRecorded.compareAndSet(false, true)) trace(owned.request.token, GpuLifecyclePhase.DRAIN_END)
        owned.done.isCompleted
    }
    internal fun whenSafeToRelease(cleanup: () -> Unit) {
        val now = synchronized(lock) {
            val owned = session
            if (owned == null || owned.done.isCompleted) true
            else { owned.safeCallbacks.add(cleanup); false }
        }
        if (now) cleanup()
    }
    private fun quarantine(s: Session) {
        if (s.quarantineRecorded.compareAndSet(false, true)) trace(s.request.token, GpuLifecyclePhase.QUARANTINED)
        synchronized(lock) {
            if (s.done.isCompleted) return
            // Preserve the global GPU owner and Binder death subscription. No further
            // GPU helper may start; unrelated CPU workers are not held by this owner.
            runCatching { s.reservation.quarantine() }
        }
    }
    private inner class Session(val request: GpuRenderRequest, val plan: GpuStagePlan, val reservation: RetainedNativeWork) {
        val life = GpuProbeLifetime(request.token)
        val done = CompletableDeferred<Unit>()
        var lastWaitAt = SystemClock.elapsedRealtime()
        var waitEvents = 0
        val stopReasons = java.util.concurrent.ConcurrentHashMap.newKeySet<GpuRenderCode>()
        val drainRecorded = AtomicBoolean(false)
        val drainEndRecorded = AtomicBoolean(false)
        val quarantineRecorded = AtomicBoolean(false)
        val deadline = (nowMillis() + GpuRenderIpc.MAIN_DEADLINE_MS).let { previewBudget?.limitDeadline(it) ?: it }
        var poll = GpuRenderPoll(request.token, GpuRenderStatus.PENDING, GpuRenderCode.OK, request.totalPixels)
        var stopCode: GpuRenderCode? = null
        var stopAt: Long? = null
        val safeCallbacks = mutableListOf<() -> Unit>()
        var resultCode: GpuRenderCode? = null
        var summary: GpuStageSummary? = null
        var binder: IBinder? = null
        var remote: Messenger? = null
        var connection: ServiceConnection? = null
        var bindingAttempted = false
        val known = mutableSetOf<IBinder>()
        val live = mutableMapOf<IBinder, IBinder.DeathRecipient>()
        var deathObserved = false
        val deadlineAction = Runnable { stop(this, deadlineCode()) }
    }
    private fun current(s: Session) = synchronized(lock) { session === s && !s.done.isCompleted }
    private fun readmit(): Boolean = testHooks?.readmit?.invoke() ?: try {
        // A transient one-byte passive lease rechecks the entire existing ledger
        // against fresh OS headroom, without changing caps or taking a worker slot.
        NativeWorkReservations.retain(1, { NativeWorkReservations.snapshot(context) }).close(); true
    } catch (_: Exception) { false }
    private fun start(s: Session) {
        if (!current(s)) return
        if (nowMillis() >= s.deadline) { stop(s, deadlineCode()); return }
        if (!readmit()) { stop(s, GpuRenderCode.MEMORY_UNAVAILABLE); return }
        trace(s.request.token, GpuLifecyclePhase.START_REQUEST)
        val started = synchronized(lock) {
            if (nowMillis() >= s.deadline) { stop(s, deadlineCode()); return }
            if (!current(s) || !s.life.requestHelper()) return
            try { context.startService(Intent(context, GpuRenderService::class.java).putExtra(GpuRenderIpc.TOKEN, s.request.token)) }
            catch (_: Exception) { null }
        }
        if (started == null) { stop(s, GpuRenderCode.BIND_FAILED); return }
        val replies = Messenger(Handler(Looper.getMainLooper()) { message -> receive(s, message); true })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (name != ComponentName(context, GpuRenderService::class.java)) {
                    stop(s, GpuRenderCode.INVALID_RESULT); return
                }
                if (!current(s)) { cancelBinder(binder, s.request.token); return }
                trace(s.request.token, GpuLifecyclePhase.CONNECTED)
                val replacement = synchronized(lock) { s.binder != null && s.binder !== binder }
                observe(s, binder)
                if (replacement) { stop(s, GpuRenderCode.INVALID_RESULT); cancelBinder(binder, s.request.token); return }
                synchronized(lock) { s.binder = binder; s.remote = Messenger(binder) }
                if (!binder.isBinderAlive) { died(s, binder); return }
                val hello = Message.obtain(null, GpuRenderIpc.HELLO)
                hello.replyTo = replies
                hello.data = Bundle().apply { putLong(GpuRenderIpc.TOKEN, s.request.token) }
                runCatching { Messenger(binder).send(hello); trace(s.request.token, GpuLifecyclePhase.HELLO_SENT) }.onFailure { stop(s, GpuRenderCode.BIND_FAILED); checkDeaths(s) }
            }
            override fun onServiceDisconnected(name: ComponentName) { checkDeaths(s) }
            override fun onBindingDied(name: ComponentName) { stop(s, GpuRenderCode.BIND_FAILED); checkDeaths(s) }
            override fun onNullBinding(name: ComponentName) { stop(s, GpuRenderCode.BIND_FAILED) }
        }
        trace(s.request.token, GpuLifecyclePhase.BIND_REQUEST)
        val bound = synchronized(lock) {
            if (!current(s) || s.life.stopping) return
            s.connection = connection; s.bindingAttempted = true
            replyMessengers[s] = replies
            main.postDelayed(s.deadlineAction, (s.deadline - nowMillis()).coerceAtLeast(0))
            try { context.bindService(Intent(context, GpuRenderService::class.java), connection, 0) }
            catch (_: Exception) { false }
        }
        if (!bound) stop(s, GpuRenderCode.BIND_FAILED)
    }
    private val replyMessengers = mutableMapOf<Session, Messenger>()
    private fun receive(s: Session, message: Message) {
        if (!current(s) || message.sendingUid != Process.myUid()) return
        if (!synchronized(lock) { message.replyTo?.binder in s.known }) return
        val data = message.data
        if (runCatching { data.get(GpuRenderIpc.TOKEN) != s.request.token }.getOrDefault(true)) return
        when (message.what) {
            GpuRenderIpc.READY -> {
                if (data.keySet() != setOf(GpuRenderIpc.TOKEN)) { stop(s, GpuRenderCode.INVALID_RESULT); return }
                val stopped = synchronized(lock) {
                    if (!s.life.acknowledgeStartup(s.request.token)) return
                    s.life.stopping
                }
                trace(s.request.token, GpuLifecyclePhase.READY_RECEIVED)
                if (stopped) { sendCancel(s); return }
                if (nowMillis() >= s.deadline) { stop(s, deadlineCode()); return }
                if (!readmit()) { stop(s, GpuRenderCode.MEMORY_UNAVAILABLE); return }
                val run = Message.obtain(null, GpuRenderIpc.RUN)
                run.data = Bundle().apply {
                    putLong(GpuRenderIpc.TOKEN, s.request.token)
                    putLong(GpuRenderIpc.BUDGET, s.plan.combinedPeakBytes)
                    putString(GpuRenderIpc.DIGEST, s.plan.digest)
                    putString(GpuRenderIpc.OPERATION, operation.wire)
                }
                val remote = synchronized(lock) {
                    if (nowMillis() >= s.deadline) { stop(s, deadlineCode()); return }
                    if (!s.life.attemptRun(s.request.token)) return
                    run.replyTo = replyMessengers[s]
                    s.poll = GpuRenderPoll(s.request.token, GpuRenderStatus.RUNNING, GpuRenderCode.OK, s.request.totalPixels)
                    s.remote
                }
                trace(s.request.token, GpuLifecyclePhase.RUN_SEND_REQUEST)
                try { checkNotNull(remote).send(run); trace(s.request.token, GpuLifecyclePhase.RUN_SENT) }
                catch (_: Exception) { stop(s, GpuRenderCode.BIND_FAILED); checkDeaths(s) }
            }
            GpuRenderIpc.RESULT -> {
                val result = runCatching {
                    require(data.keySet() == setOf(GpuRenderIpc.TOKEN, GpuRenderIpc.SUMMARY))
                    GpuRenderProtocol.parseStageResult(data.get(GpuRenderIpc.SUMMARY) as String, s.plan)
                }.getOrNull()
                if (result == null) { stop(s, GpuRenderCode.INVALID_RESULT); return }
                synchronized(lock) {
                    if (!s.life.result(s.request.token)) return
                    s.resultCode = result.first; s.summary = result.second
                    if (s.stopAt == null) s.stopAt = SystemClock.elapsedRealtime()
                    s.poll = GpuRenderPoll(s.request.token, GpuRenderStatus.STOPPING, result.first, s.request.totalPixels)
                }
                trace(s.request.token, GpuLifecyclePhase.RESULT_RECEIVED, result.first)
                sendCancel(s)
            }
        }
    }
    private fun stop(s: Session, code: GpuRenderCode) {
        val neverRun = synchronized(lock) {
            if (!current(s) || !s.life.stop(s.request.token)) return
            if (s.stopAt == null) s.stopAt = SystemClock.elapsedRealtime()
            if (s.stopCode == null || code == GpuRenderCode.CANCELLED) s.stopCode = code
            s.poll = GpuRenderPoll(s.request.token, GpuRenderStatus.STOPPING, s.stopCode!!, s.request.totalPixels)
            !s.life.runAttempted
        }
        if (s.stopReasons.add(code)) trace(s.request.token,
            when (code) {
                GpuRenderCode.CANCELLED -> GpuLifecyclePhase.CANCEL_REQUEST
                GpuRenderCode.TIMED_OUT, GpuRenderCode.PREVIEW_BUDGET_EXHAUSTED -> GpuLifecyclePhase.TIMEOUT
                else -> GpuLifecyclePhase.STOP_REQUEST
            }, code)
        if (neverRun) {
            // Stop is permanent before revoking pending lifecycle. No callback can
            // ever authorize RUN afterward; no native reads/writes were permitted.
            runCatching { context.stopService(Intent(context, GpuRenderService::class.java)) }
            unregister(s)
            synchronized(lock) { s.life.revokeBeforeRun(s.request.token) }
            finish(s)
        } else sendCancel(s)
    }
    private fun sendCancel(s: Session) {
        synchronized(lock) { s.live.keys.toList() }.forEach { cancelBinder(it, s.request.token) }
        checkDeaths(s)
    }
    private fun cancelBinder(binder: IBinder, token: Long) {
        val message = Message.obtain(null, GpuRenderIpc.CANCEL)
        message.data = Bundle().apply { putLong(GpuRenderIpc.TOKEN, token) }
        runCatching { Messenger(binder).send(message) }
    }
    private fun observe(s: Session, binder: IBinder) {
        val recipient = IBinder.DeathRecipient { died(s, binder) }
        synchronized(lock) {
            if (!current(s) || binder in s.live) return
            s.live[binder] = recipient; s.known.add(binder)
        }
        try { binder.linkToDeath(recipient, 0) }
        catch (_: android.os.RemoteException) { died(s, binder) }
    }
    private fun checkDeaths(s: Session) {
        synchronized(lock) { s.live.keys.toList() }.filter { !it.isBinderAlive }.forEach { died(s, it) }
    }
    private fun died(s: Session, binder: IBinder) {
        val terminal = synchronized(lock) {
            val recipient = s.live.remove(binder) ?: return
            runCatching { binder.unlinkToDeath(recipient, 0) }
            s.deathObserved = true
            if (!current(s) || s.live.isNotEmpty()) return
            if (s.life.startupAcknowledged) s.life.died(s.request.token) else false
        }
        trace(s.request.token, GpuLifecyclePhase.DEATH_OBSERVED)
        if (terminal) finish(s) else stop(s, GpuRenderCode.HELPER_DIED)
    }
    private fun unregister(s: Session) {
        val connection = synchronized(lock) {
            if (!s.bindingAttempted) return
            s.bindingAttempted = false; s.connection
        }
        connection?.let { runCatching { context.unbindService(it) } }
    }
    private fun finish(s: Session) {
        val callbacks = synchronized(lock) {
            if (!current(s) || !s.life.canRelease || !s.life.finish()) return
            val code = s.stopCode ?: s.resultCode ?: GpuRenderCode.HELPER_DIED
            val status = when {
                code == GpuRenderCode.CANCELLED -> GpuRenderStatus.CANCELLED
                s.life.workRevokedBeforeRun -> GpuRenderStatus.NOT_STARTED
                code == GpuRenderCode.OK && s.summary != null -> GpuRenderStatus.SUCCESS
                else -> GpuRenderStatus.FAILED
            }
            // Close before publishing terminal: native may immediately resume CPU.
            // Death/revocation is already proven. A faulty ledger close may leak
            // conservatively, but cannot strand the cleanup notification forever.
            runCatching { s.reservation.close() }
            s.poll = GpuRenderPoll(s.request.token, status, code, s.request.totalPixels, s.summary.takeIf { status == GpuRenderStatus.SUCCESS })
            active.compareAndSet(this, null)
            s.done.complete(Unit)
            s.safeCallbacks.toList().also { s.safeCallbacks.clear() }
        }
        trace(s.request.token, GpuLifecyclePhase.STAGE_END, synchronized(lock) { s.poll.code })
        main.removeCallbacks(s.deadlineAction)
        unregister(s)
        synchronized(lock) { replyMessengers.remove(s) }
        // A cleanup failure must not prevent independent owners from releasing.
        callbacks.forEach { runCatching { it() } }
    }
    companion object {
        private val active = AtomicReference<GpuRenderDelegate?>(null)
        internal fun forOperation(context: Context, operation: GpuRenderOperation, previewBudget: PreviewGpuBudget? = null) = GpuRenderDelegate(context, null, operation, previewBudget)
        internal fun forTesting(context: Context, hooks: GpuRenderTestHooks, operation: GpuRenderOperation = GpuRenderOperation.CAMERA_DIFFUSION, previewBudget: PreviewGpuBudget? = null) = GpuRenderDelegate(context, hooks, operation, previewBudget)
    }
}

/** Tests replace JNI/admission only; service controls and lifetime proofs stay production code. */
internal data class GpuRenderTestHooks(
    val plan: (String, Long) -> String,
    val reserve: (Long, () -> Unit) -> RetainedNativeWork,
    val readmit: () -> Boolean = { true },
    val enabled: Boolean = true,
    val drainMillis: Long = GpuRenderIpc.DRAIN_GRACE_MS,
    val beforeSession: (() -> Unit)? = null,
    val afterSession: (() -> Unit)? = null,
    val lifecycle: (Long, GpuLifecyclePhase, GpuRenderCode?, Boolean) -> Unit = { _, _, _, _ -> },
)
