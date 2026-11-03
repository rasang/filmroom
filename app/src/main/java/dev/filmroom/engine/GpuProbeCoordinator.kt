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
import dev.filmroom.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit, private-Perf-only diagnostic owner. Never called automatically by an export.
 * Accepted snapshots must already contain the completed CPU reference and export must
 * have succeeded. A true return transfers only that token's snapshot ownership here.
 */
class GpuProbeCoordinator private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val preferences by lazy { context.getSharedPreferences("gpu-probe-summary-v1", Context.MODE_PRIVATE) }
    private val mutable = MutableStateFlow(GpuProbeState(lastSummary = readLastSummary()))
    val state = mutable.asStateFlow()
    val isAvailable: Boolean get() = BuildConfig.PERF_BUILD && !GpuProbeProcess.isWorker(context.packageName, Application.getProcessName())
    private var active: Session? = null

    @Synchronized
    fun submitReadySnapshot(token: Long): Boolean {
        if (!isAvailable || token <= 0 || active != null) return false
        val session = Session(token)
        active = session
        mutable.value = GpuProbeState(token, GpuProbePhase.PLANNING, mutable.value.lastSummary)
        session.job = scope.launch { execute(session) }
        return true
    }
    fun cancel(token: Long? = null) {
        main.post {
            val session = synchronized(this) { active } ?: return@post
            if (token == null || session.token == token) stop(session, GpuProbeStatus.CANCELLED)
        }
    }
    fun readLastSummary(): GpuProbeSummary? = runCatching {
        preferences.getString("summary", null)?.let { GpuProbeProtocol.parseSummary(it) }
    }.getOrNull()
    private fun current(session: Session): Boolean = synchronized(this) { active === session }
    private fun phase(session: Session, phase: GpuProbePhase) {
        if (current(session)) mutable.value = GpuProbeState(session.token, phase, mutable.value.lastSummary)
    }
    private inner class Session(val token: Long) {
        val lifetime = GpuProbeLifetime(token)
        val terminated = CompletableDeferred<Unit>()
        var job: Job? = null
        var binder: IBinder? = null
        var remote: Messenger? = null
        var replies: Messenger? = null
        var connection: ServiceConnection? = null
        var bindingAttempted = false
        val runDispatched: Boolean get() = lifetime.runAttempted
        var deathObserved = false
        val binders = mutableMapOf<IBinder, IBinder.DeathRecipient>()
        val knownBinders = mutableSetOf<IBinder>()
        var summary: GpuProbeSummary? = null
        val deadline = Runnable { if (current(this)) stop(this, GpuProbeStatus.TIMED_OUT) }
    }
    private suspend fun execute(session: Session) {
        try {
            phase(session, GpuProbePhase.WAITING_FOR_MEMORY)
            // Serialize with CPU exports before acquiring any passive memory lease.
            ExportWorkAdmissions.run {
                phase(session, GpuProbePhase.PLANNING)
                executeAdmitted(session)
            }
        } catch (_: CancellationException) {
            if (current(session)) {
                session.summary = GpuProbeSummary(GpuProbeStatus.CANCELLED)
                finishSession(session, null)
            }
        }
    }
    private suspend fun executeAdmitted(session: Session) {
        var reservation: RetainedNativeWork? = null
        try {
            val plan = withContext(Dispatchers.IO) {
                val dir = GpuProbePaths.snapshot(context, session.token)
                GpuProbeProtocol.parsePlan(NativeGpuProbeBridge.nativePlanGpuDiffusionProbe(dir.path, session.token), session.token)
            }
            if (session.lifetime.stopping) return
            val budget = GpuProbeProtocol.availableBudget(plan, NativeWorkReservations.snapshot(context))
                ?: throw NativeWorkUnavailable()
            phase(session, GpuProbePhase.WAITING_FOR_MEMORY)
            // Includes host + device + helper 64 MiB + driver 64 MiB. The existing
            // retained-work API adds its own 16 MiB safety and enforces unchanged caps.
            reservation = NativeWorkReservations.retainForDetail(plan.combinedPeakBytes,
                { NativeWorkReservations.snapshot(context) },
                { main.post { if (current(session)) stop(session, GpuProbeStatus.MEMORY_UNAVAILABLE) } })
            reservation.run({ NativeWorkReservations.snapshot(context) }) {
                withContext(Dispatchers.Main.immediate) { bindAndAwaitDeath(session, plan, budget) }
            }
        } catch (_: CancellationException) {
            if (session.summary == null) session.summary = GpuProbeSummary(GpuProbeStatus.CANCELLED)
        } catch (_: NativeWorkUnavailable) {
            if (session.summary == null) session.summary = GpuProbeSummary(GpuProbeStatus.MEMORY_UNAVAILABLE)
        } catch (_: LinkageError) {
            if (session.summary == null) session.summary = GpuProbeSummary(GpuProbeStatus.NATIVE_UNAVAILABLE)
        } catch (_: Exception) {
            if (session.summary == null) session.summary = GpuProbeSummary(GpuProbeStatus.SNAPSHOT_INVALID)
        } finally {
            finishSession(session, reservation)
        }
    }
    private suspend fun finishSession(session: Session, reservation: RetainedNativeWork?) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (!session.lifetime.canRelease) {
                stop(session, session.summary?.status ?: GpuProbeStatus.NATIVE_FAILED)
                // After any RUN attempt, no coroutine deadline may release live GPU memory.
                // Before RUN, permanent work revocation is a separate no-work proof.
                session.terminated.await()
            }
            check(session.lifetime.canRelease)
            main.removeCallbacks(session.deadline)
            unbindAfterDeath(session)
            reservation?.close()
            val summary = session.summary ?: GpuProbeSummary(GpuProbeStatus.HELPER_DIED)
            withContext(Dispatchers.IO) {
                GpuProbePaths.deleteOwned(context, session.token)
                runCatching { preferences.edit().putString("summary", summary.toSafeJson()).commit() }
                // Only the main process writes diagnostics after confirmed death
                // OR verified no-RUN revocation, through the typed privacy boundary.
                runCatching {
                    val id = dev.filmroom.diagnostics.Diagnostics.begin(dev.filmroom.diagnostics.Operation.GPU_PROBE)
                    val info = dev.filmroom.diagnostics.SafeInfo(gpuProbe = dev.filmroom.diagnostics.GpuProbeTelemetry.fromSummary(summary))
                    dev.filmroom.diagnostics.Diagnostics.event(id, dev.filmroom.diagnostics.Stage.NATIVE_EXIT, info)
                    val outcome = when (summary.status) {
                        GpuProbeStatus.COMPLETE -> dev.filmroom.diagnostics.Outcome.SUCCESS
                        GpuProbeStatus.CANCELLED -> dev.filmroom.diagnostics.Outcome.CANCELLED
                        else -> dev.filmroom.diagnostics.Outcome.FAILED
                    }
                    dev.filmroom.diagnostics.Diagnostics.end(id, outcome, info)
                }
            }
            check(session.lifetime.finish())
            synchronized(this@GpuProbeCoordinator) {
                if (active === session) {
                    active = null
                    mutable.value = GpuProbeState(session.token, GpuProbePhase.FINISHED, summary)
                }
            }
        }
    }
    private suspend fun bindAndAwaitDeath(session: Session, plan: GpuProbePlan, budget: Long) {
        if (!current(session) || session.lifetime.stopping) return
        if (GpuProbeProtocol.availableBudget(plan, NativeWorkReservations.snapshot(context)) == null) throw NativeWorkUnavailable()
        if (!session.lifetime.requestHelper()) return
        phase(session, GpuProbePhase.BINDING)
        val intent = Intent(context, GpuProbeService::class.java).putExtra(GpuProbeIpc.TOKEN, session.token)
        val started = try { context.startService(intent) }
        catch (_: IllegalStateException) { null } // Framework rejects a background start before creation.
        catch (_: SecurityException) { null } // Framework rejected access; no service was created.
        if (started == null) {
            session.lifetime.bindingNotCreated()
            session.summary = GpuProbeSummary(GpuProbeStatus.BIND_FAILED)
            return
        }
        val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (current(session) && message.sendingUid == Process.myUid() && message.replyTo?.binder in session.knownBinders) {
                val data = message.data
                val tokenMatches = runCatching { data.get(GpuProbeIpc.TOKEN) == session.token }.getOrDefault(false)
                if (tokenMatches && message.what == GpuProbeIpc.READY && data.keySet() == setOf(GpuProbeIpc.TOKEN)) {
                    if (session.lifetime.acknowledgeStartup(session.token)) {
                        if (session.deathObserved && session.binders.isEmpty()) completeConfirmedDeath(session)
                        else if (session.lifetime.stopping) sendCancel(session)
                        else if (!session.runDispatched) {
                            if (GpuProbeProtocol.availableBudget(plan, NativeWorkReservations.snapshot(context)) == null) {
                                stop(session, GpuProbeStatus.MEMORY_UNAVAILABLE)
                            } else {
                                val run = Message.obtain(null, GpuProbeIpc.RUN)
                                run.data = Bundle().apply {
                                    putLong(GpuProbeIpc.TOKEN, session.token)
                                    putLong(GpuProbeIpc.BUDGET, budget)
                                    putString(GpuProbeIpc.DIGEST, plan.manifestSha256)
                                }
                                if (session.lifetime.attemptRun(session.token)) {
                                    run.replyTo = session.replies
                                    try { session.remote?.send(run); phase(session, GpuProbePhase.RUNNING) }
                                    catch (_: Exception) { stop(session, GpuProbeStatus.BIND_FAILED); checkBinderDeath(session) }
                                }
                            }
                        }
                    }
                } else if (tokenMatches && message.what == GpuProbeIpc.RESULT) {
                    val parsed = runCatching {
                        require(data.keySet() == setOf(GpuProbeIpc.TOKEN, GpuProbeIpc.SUMMARY))
                        require(session.runDispatched)
                        GpuProbeProtocol.parseSummary(data.get(GpuProbeIpc.SUMMARY) as String)
                    }.getOrNull()
                    if (parsed == null) stop(session, GpuProbeStatus.PROTOCOL_REJECTED)
                    else if (session.lifetime.result(session.token)) {
                        session.summary = parsed
                        phase(session, GpuProbePhase.STOPPING)
                        sendCancel(session)
                    }
                }
            }
            true
        })
        session.replies = replies
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!current(session)) { cancelBinder(service, session.token); return }
                val replacement = session.binder != null && session.binder !== service
                observeBinder(session, service)
                if (replacement) {
                    stop(session, GpuProbeStatus.PROTOCOL_REJECTED)
                    cancelBinder(service, session.token)
                    return
                }
                session.binder = service
                session.remote = Messenger(service)
                if (!service.isBinderAlive) { confirmDeath(session, service); return }
                // READY is posted after onStartCommand returns START_NOT_STICKY.
                // Neither GPU RUN nor cancellation can race a pending sticky start.
                val hello = Message.obtain(null, GpuProbeIpc.HELLO)
                hello.replyTo = replies
                hello.data = Bundle().apply { putLong(GpuProbeIpc.TOKEN, session.token) }
                try { session.remote!!.send(hello) }
                catch (_: Exception) { stop(session, GpuProbeStatus.BIND_FAILED); checkBinderDeath(session) }
            }
            override fun onServiceDisconnected(name: ComponentName) { checkBinderDeath(session) }
            override fun onBindingDied(name: ComponentName) { stop(session, GpuProbeStatus.BIND_FAILED); checkBinderDeath(session) }
            override fun onNullBinding(name: ComponentName) {
                stop(session, GpuProbeStatus.BIND_FAILED)
                stopStartedService(session)
                unregisterBinding(session)
            }
        }
        session.connection = connection
        main.postDelayed(session.deadline, GpuProbeIpc.MAIN_DEADLINE_MS)
        session.bindingAttempted = true
        // No AUTO_CREATE: a non-sticky helper cannot be automatically recreated by
        // this binding after its Binder dies and before the main callback runs.
        val bound = context.bindService(Intent(context, GpuProbeService::class.java), connection, 0)
        if (!bound) {
            stop(session, GpuProbeStatus.BIND_FAILED)
            stopStartedService(session)
            unregisterBinding(session) // Android requires this even after false bind.
        }
        session.terminated.await()
    }
    private fun stop(session: Session, status: GpuProbeStatus) {
        if (!current(session) || !session.lifetime.stop(session.token)) return
        if (session.summary == null) session.summary = GpuProbeSummary(status)
        phase(session, GpuProbePhase.STOPPING)
        if (!session.lifetime.helperRequested) session.job?.cancel()
        else if (!session.runDispatched) {
            if (session.summary?.status != GpuProbeStatus.CANCELLED) session.summary = GpuProbeSummary(GpuProbeStatus.START_REVOKED)
            // No RUN was ever sent. Permanently prevent dispatch BEFORE revoking
            // pending service/binding ownership. The helper cannot load JNI, read
            // samples, or allocate GPU buffers without RUN. This proves no native
            // work, not process death; transient idle RSS stays in OS snapshots.
            stopStartedService(session)
            unregisterBinding(session)
            check(session.lifetime.revokeBeforeRun(session.token))
            session.terminated.complete(Unit)
        } else sendCancel(session)
    }
    private fun cancelBinder(binder: IBinder, token: Long) {
        val message = Message.obtain(null, GpuProbeIpc.CANCEL)
        message.data = Bundle().apply { putLong(GpuProbeIpc.TOKEN, token) }
        runCatching { Messenger(binder).send(message) }
    }
    private fun sendCancel(session: Session) {
        if (!session.lifetime.startupAcknowledged) return
        session.binders.keys.toList().forEach { cancelBinder(it, session.token) }
        checkBinderDeath(session)
    }
    private fun observeBinder(session: Session, binder: IBinder) {
        if (binder in session.binders) return
        val death = IBinder.DeathRecipient { main.post { confirmDeath(session, binder) } }
        session.binders[binder] = death
        session.knownBinders.add(binder)
        try { binder.linkToDeath(death, 0) }
        catch (_: android.os.RemoteException) { confirmDeath(session, binder) }
    }
    private fun checkBinderDeath(session: Session) {
        session.binders.keys.toList().filter { !it.isBinderAlive }.forEach { confirmDeath(session, it) }
    }
    private fun confirmDeath(session: Session, binder: IBinder) {
        if (!current(session)) return
        val death = session.binders.remove(binder) ?: return
        runCatching { binder.unlinkToDeath(death, 0) }
        session.deathObserved = true
        if (session.binders.isNotEmpty()) return
        if (session.lifetime.startupAcknowledged) completeConfirmedDeath(session)
        else {
            // Before READY no RUN can have been sent. Revoke pending work without
            // asserting that this callback proves an undelivered start has died.
            stop(session, GpuProbeStatus.HELPER_DIED)
            stopStartedService(session)
            unregisterBinding(session)
        }
    }
    private fun completeConfirmedDeath(session: Session) {
        if (!current(session) || session.binders.isNotEmpty() || !session.deathObserved || !session.lifetime.died(session.token)) return
        unregisterBinding(session)
        session.terminated.complete(Unit)
    }
    private fun stopStartedService(session: Session) {
        if (session.lifetime.helperRequested) runCatching { context.stopService(Intent(context, GpuProbeService::class.java)) }
    }
    private fun unregisterBinding(session: Session) {
        if (session.bindingAttempted) {
            session.bindingAttempted = false
            session.connection?.let { runCatching { context.unbindService(it) } }
        }
    }
    private fun unbindAfterDeath(session: Session) {
        if (session.lifetime.canRelease) unregisterBinding(session)
    }
    companion object {
        @Volatile private var instance: GpuProbeCoordinator? = null
        fun get(context: Context): GpuProbeCoordinator = instance ?: synchronized(this) {
            instance ?: GpuProbeCoordinator(context.applicationContext).also { instance = it }
        }
    }
}
