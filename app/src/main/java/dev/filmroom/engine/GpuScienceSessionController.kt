package dev.filmroom.engine

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal class GpuScienceCapacityRollover(val replacementBudget: GpuScienceSessionBudget) : Exception()

internal class GpuScienceStageRecord(val request: GpuRenderRequest, val plan: GpuStagePlan, val ticket: GpuScienceStageTicket) {
    var status = "running"
    var code = GpuRenderCode.OK
    var summary: GpuStageSummary? = null
    val deadlineMillis = SystemClock.elapsedRealtime() + 125_000L
}

internal class GpuScienceSessionController(
    private val context: Context,
    val epoch: Long,
    val budget: GpuScienceSessionBudget,
    leaseFactory: (Context, GpuScienceSessionBudget, GpuScienceSessionLifetime, () -> Unit) -> GpuScienceSessionLease =
        { context, budget, life, onPressure -> GpuScienceSessionLease.retain(context, budget, life, onPressure) },
    private val released: (GpuScienceSessionController) -> Unit,
    transportFactory: (Context, Long, (Int, String) -> Unit, () -> Unit, (Boolean) -> Unit) -> GpuScienceTransport =
        { context, epoch, receive, died, failed -> GpuScienceClientTransport(context, epoch, receive, died, failed) },
    private val nativePlan: (GpuRenderRequest) -> String =
        { GpuRenderProtocol.nativePlan(it.operation, it.jobDir, it.token) },
    private val recheckMemory: (Context) -> Unit =
        { NativeWorkReservations.retain(1L, { NativeWorkReservations.snapshot(it) }).close() },
    private val drainTimeoutMillis: Long = 5000L,
) {
    internal val lock = Any()
    private val life = GpuScienceSessionLifetime(epoch)
    private var lease: GpuScienceSessionLease? = null
    private var transport: GpuScienceTransport? = null
    private val ready = CompletableDeferred<Boolean>()
    private val stopped = CompletableDeferred<Unit>()
    private var frame: GpuScienceFrameDelegate? = null
    private var lastOwnerGeneration = 0L
    private var stats: GpuScienceSessionStats? = null
    private var stopCode = GpuRenderCode.CANCELLED
    private var didRelease = false
    private var stopAtMillis: Long? = null
    private val drainScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var drainStarted = false
    private val cleanups = mutableListOf<() -> Unit>()
    private var initializing = true
    private var pressurePending = false
    private fun onPressure() = synchronized(lock) {
        // The limiter may publish this callback before retain returns its handle.
        // Never attempt a release until that handle has been installed.
        if (initializing) pressurePending = true else retire(GpuRenderCode.MEMORY_UNAVAILABLE)
    }

    init {
        val admitted = leaseFactory(context, budget, life, ::onPressure)
        synchronized(lock) {
            lease = admitted
            try {
                if (!pressurePending) {
                    transport = transportFactory(context, epoch, ::receive, ::died, ::transportFailed)
                }
                initializing = false
                // Retry the no-start release now that its prepaid handle exists.
                if (pressurePending) retire(GpuRenderCode.MEMORY_UNAVAILABLE)
            } catch (failure: Throwable) {
                initializing = false
                life.requestStop(epoch)
                releaseIfProvenLocked()
                drainScope.cancel()
                throw failure
            }
        }
    }
    fun claimStartupOwner(owner: GpuScienceFrameDelegate): Boolean = synchronized(lock) {
        if (lastOwnerGeneration != 0L || transport == null || life.isRetiring() || didRelease) return@synchronized false
        lastOwnerGeneration = owner.generation
        true
    }
    fun owns(owner: GpuScienceFrameDelegate): Boolean = synchronized(lock) { lastOwnerGeneration == owner.generation }
    fun start() {
        synchronized(lock) {
            if (!life.requestHelper()) { releaseIfProvenLocked(); return }
            transport!!.start()
        }
    }
    suspend fun awaitReady(): Boolean {
        if (withTimeoutOrNull(5000L) { ready.await() } == true) return true
        retire(GpuRenderCode.BIND_FAILED)
        return false
    }
    fun idle(): Boolean = synchronized(lock) { life.isIdle() }
    fun hasStopped(): Boolean = stopped.isCompleted
    suspend fun beginFrame(owner: GpuScienceFrameDelegate): Boolean {
        synchronized(lock) {
            if (!life.attemptBeginFrame(epoch, owner.generation)) return false
            lastOwnerGeneration = owner.generation
            frame = owner
            if (!send(GpuScienceIpc.BEGIN_FRAME, GpuScienceSessionProtocol.control(epoch).put("frameGeneration", owner.generation)))
                retireOwned(owner, GpuRenderCode.BIND_FAILED)
        }
        if (withTimeoutOrNull(5000L) { owner.opened.await() } == true) return true
        retireOwned(owner, GpuRenderCode.BIND_FAILED)
        return false
    }
    fun begin(owner: GpuScienceFrameDelegate, raw: String, allowRollover: Boolean = true): String {
        val request: GpuRenderRequest
        val plan: GpuStagePlan
        val nextBudget: GpuScienceSessionBudget
        try {
            request = GpuRenderProtocol.parseRequest(raw, context)
            if (request.operation !in owner.allowedOperations) return GpuScienceSessionProtocol.rejected(GpuRenderCode.UNSUPPORTED.name)
            plan = GpuRenderProtocol.parseStagePlan(nativePlan(request), request.token, request.operation)
            require(GpuRenderProtocol.matches(request, plan))
            // This calculation uses only the parsed, strictly validated native plan.
            // Any reserve change belongs to a new epoch; never mutate a live lease.
            nextBudget = budget.forValidatedStage(plan)
                ?: return GpuScienceSessionProtocol.rejected(GpuRenderCode.MEMORY_UNAVAILABLE.name)
            if (plan.diskBytes > owner.availableDiskBytes()) return GpuScienceSessionProtocol.rejected(GpuRenderCode.MEMORY_UNAVAILABLE.name)
        } catch (_: Throwable) { return GpuScienceSessionProtocol.rejected(GpuRenderCode.INVALID_REQUEST.name) }
        synchronized(lock) {
            if (frame !== owner || owner.cancelled || life.isRetiring() || owner.records.size >= 64 || owner.records.containsKey(request.token))
                return GpuScienceSessionProtocol.rejected(GpuRenderCode.START_REVOKED.name)
            try { recheckMemory(context) }
            catch (_: Throwable) { retire(GpuRenderCode.MEMORY_UNAVAILABLE); return GpuScienceSessionProtocol.rejected(GpuRenderCode.MEMORY_UNAVAILABLE.name) }
            if (life.activeStage() != null) return GpuScienceSessionProtocol.rejected(GpuRenderCode.GPU_BUSY.name)
            if (nextBudget != budget) {
                if (!allowRollover) return GpuScienceSessionProtocol.rejected(GpuRenderCode.MEMORY_UNAVAILABLE.name)
                retireOwned(owner, GpuRenderCode.MEMORY_UNAVAILABLE)
                throw GpuScienceCapacityRollover(nextBudget)
            }
            // Conservative no-RUN preflight. Count the whole existing stage plan
            // in addition to cumulative session debt, without invented reuse credit.
            val freshUpper = runCatching { Math.addExact(budget.fixedBytes, plan.combinedPeakBytes) }.getOrNull()
            if (freshUpper == null || freshUpper > budget.prepaidBytes)
                return GpuScienceSessionProtocol.rejected(GpuRenderCode.UNSUPPORTED.name)
            val nextUpper = runCatching { Math.addExact(stats?.chargedBytes ?: budget.fixedBytes, plan.combinedPeakBytes) }.getOrNull()
            if (nextUpper == null || nextUpper > budget.prepaidBytes) {
                if (!allowRollover) return GpuScienceSessionProtocol.rejected(GpuRenderCode.MEMORY_UNAVAILABLE.name)
                retireOwned(owner, GpuRenderCode.MEMORY_UNAVAILABLE)
                throw GpuScienceCapacityRollover(budget)
            }
            val sequence = life.nextSequence() ?: return GpuScienceSessionProtocol.rejected(GpuRenderCode.START_REVOKED.name)
            val ticket = GpuScienceStageTicket(epoch, owner.generation, sequence, request.token, request.digest)
            val accepted = GpuScienceSessionProtocol.accepted(ticket)
            val packet = GpuScienceSessionProtocol.control(epoch).put("frameGeneration", owner.generation)
                .put("sequence", sequence).put("request", raw)
            if (packet.toString().toByteArray(Charsets.UTF_8).size > 16384) return GpuScienceSessionProtocol.rejected(GpuRenderCode.INVALID_REQUEST.name)
            val record = GpuScienceStageRecord(request, plan, ticket)
            if (!life.attemptStage(ticket)) return GpuScienceSessionProtocol.rejected(GpuRenderCode.GPU_BUSY.name)
            // Publish ownership before any uncertain Binder send or returned JNI value.
            owner.records[request.token] = record
            owner.parentToken?.takeIf { it > 0L }?.let { parent ->
                runCatching { dev.filmroom.diagnostics.GpuLifecycleRecorder.link(context, request.token, request.operation, parent) }
            }
            if (!send(GpuScienceIpc.RUN_STAGE, packet)) retire(GpuRenderCode.BIND_FAILED)
            return accepted
        }
    }
    fun poll(owner: GpuScienceFrameDelegate, epoch: Long, generation: Long, sequence: Long, token: Long): String = synchronized(lock) {
        val record = owner.records[token]
        if (record == null || record.ticket.epoch != epoch || record.ticket.frameGeneration != generation || record.ticket.sequence != sequence) {
            retireOwned(owner, GpuRenderCode.INVALID_REQUEST)
            return@synchronized "{}"
        }
        checkProgressLocked(record)
        GpuScienceSessionProtocol.poll(record.ticket, record.status,
            if (record.status == "success_sealed") record.request.totalPixels else 0L, record.request.totalPixels,
            record.code.name, record.summary?.json()?.toString())
    }
    fun uncertainPoll(owner: GpuScienceFrameDelegate, token: Long): String = synchronized(lock) {
        val record = owner.records[token]
        if (record != null) checkProgressLocked(record)
        val terminal = stopped.isCompleted
        val status = when { record == null -> "not_started"; !terminal -> "stopping"; stopCode == GpuRenderCode.CANCELLED -> "cancelled_stopped"; else -> "failed_stopped" }
        JSONObject().put("schemaVersion", 1).put("token", token).put("status", status).put("completedPixels", 0L)
            .put("totalPixels", record?.request?.totalPixels ?: 0L).put("code", if (record == null) GpuRenderCode.NO_JOB.name else stopCode.name).toString()
    }
    /** A timeout is never a stopped proof. Fail the callback after bounded drain
     * so native's finite transport-error path can return while ownership stays quarantined. */
    private fun checkProgressLocked(record: GpuScienceStageRecord) {
        if (record.status == "success_sealed" || stopped.isCompleted) return
        transport?.checkDeath()
        val now = SystemClock.elapsedRealtime()
        if (now >= record.deadlineMillis) retire(GpuRenderCode.TIMED_OUT)
        if (stopAtMillis?.let { now - it >= GpuRenderIpc.DRAIN_GRACE_MS } == true && !stopped.isCompleted) {
            lease?.quarantine()
            throw IllegalStateException("GPU session death unconfirmed; ownership quarantined")
        }
    }
    suspend fun finishFrame(owner: GpuScienceFrameDelegate) {
        synchronized(lock) {
            if (owner.sealed) return
            if (frame !== owner || !life.attemptFinishFrame(epoch, owner.generation)) { retireOwned(owner, GpuRenderCode.INVALID_RESULT); return }
            if (!send(GpuScienceIpc.FINISH_FRAME, GpuScienceSessionProtocol.control(epoch).put("frameGeneration", owner.generation))) retireOwned(owner, GpuRenderCode.BIND_FAILED)
        }
        if (withTimeoutOrNull(5000L) { owner.finished.await() } != true) retireOwned(owner, GpuRenderCode.TIMED_OUT)
    }
    fun retireOwned(owner: GpuScienceFrameDelegate, code: GpuRenderCode) = synchronized(lock) {
        if (lastOwnerGeneration == owner.generation) retire(code)
    }
    fun retire(code: GpuRenderCode) {
        synchronized(lock) {
            if (didRelease) return
            if (!life.isRetiring()) { stopCode = code; stopAtMillis = SystemClock.elapsedRealtime() }
            life.requestStop(epoch)
            ready.complete(false)
            frame?.opened?.complete(false)
            frame?.finished?.complete(false)
            transport?.requestClose()
            // Keep the limiter's pressure-eviction marker until actual death.
            // Only an unconfirmed bounded drain changes this to quarantine.
            if (!drainStarted) {
                drainStarted = true
                drainScope.launch { awaitStopped() }
            }
            releaseIfProvenLocked()
        }
    }
    suspend fun awaitStopped(): Boolean = withContext(NonCancellable) {
        // A zero/expired wait budget cannot invalidate an already published stop proof.
        // Completion alone is insufficient: await must also succeed, not be cancelled/exceptional.
        val completed = stopped.isCompleted || withTimeoutOrNull(drainTimeoutMillis) {
            while (!stopped.isCompleted) { transport?.checkDeath(); delay(25L) }
            true
        } == true
        val confirmed = (completed || stopped.isCompleted) && runCatching { stopped.await(); true }.getOrDefault(false)
        if (!confirmed) synchronized(lock) { lease?.quarantine() }
        confirmed
    }
    fun whenStopped(cleanup: () -> Unit) {
        val now = synchronized(lock) { if (stopped.isCompleted) true else { cleanups += cleanup; false } }
        if (now) cleanup()
    }
    private fun send(what: Int, value: JSONObject): Boolean = transport?.send(what, value.toString()) == true
    private fun receive(what: Int, raw: String) {
        synchronized(lock) {
            if (didRelease) return
            try {
                val value = GpuScienceSessionProtocol.objectOf(raw)
                GpuScienceSessionProtocol.version(value, epoch)
                when (what) {
                    GpuScienceIpc.READY -> {
                        GpuScienceSessionProtocol.exact(value, setOf("schemaVersion", "epoch"))
                        require(life.acknowledgeStartup(epoch))
                        if (life.isRetiring()) { transport?.requestClose(); transport?.checkDeath(); return }
                        recheckMemory(context)
                        require(life.attemptOpen(epoch))
                        if (transport?.send(GpuScienceIpc.OPEN, GpuScienceSessionProtocol.configuration(epoch, budget)) != true) retire(GpuRenderCode.BIND_FAILED)
                    }
                    GpuScienceIpc.OPENED -> {
                        GpuScienceSessionProtocol.exact(value, setOf("schemaVersion", "epoch"))
                        require(life.opened(epoch)); ready.complete(true)
                    }
                    GpuScienceIpc.FRAME_OPENED -> {
                        GpuScienceSessionProtocol.exact(value, setOf("schemaVersion", "epoch", "frameGeneration"))
                        val owner = requireNotNull(frame)
                        require(GpuScienceSessionProtocol.integer(value, "frameGeneration", 1L) == owner.generation)
                        require(life.frameOpened(epoch, owner.generation)); owner.opened.complete(true)
                    }
                    GpuScienceIpc.STAGE_SEALED -> {
                        GpuScienceSessionProtocol.exact(value, setOf("schemaVersion", "epoch", "frameGeneration", "sequence", "token", "manifestSha256", "operation", "summary", "session"))
                        val owner = requireNotNull(frame)
                        val record = requireNotNull(owner.records[GpuScienceSessionProtocol.integer(value, "token", 1L)])
                        require(GpuScienceSessionProtocol.integer(value, "frameGeneration", 1L) == record.ticket.frameGeneration)
                        require(GpuScienceSessionProtocol.integer(value, "sequence", 1L) == record.ticket.sequence)
                        require(value.getString("manifestSha256") == record.ticket.manifestSha256)
                        require(value.getString("operation") == record.plan.operation.wire)
                        val (code, summary) = GpuRenderProtocol.parseStageResult(value.getString("summary"), record.plan)
                        require(code == GpuRenderCode.OK && summary != null)
                        val next = GpuScienceSessionProtocol.stats(value.getJSONObject("session"), epoch, budget, stats)
                        require(life.stageSealed(record.ticket))
                        owner.recordSessionStats(stats, next)
                        stats = next; record.summary = summary; record.status = "success_sealed"; record.code = GpuRenderCode.OK
                    }
                    GpuScienceIpc.FRAME_SEALED -> {
                        GpuScienceSessionProtocol.exact(value, setOf("schemaVersion", "epoch", "frameGeneration", "session"))
                        val owner = requireNotNull(frame)
                        require(GpuScienceSessionProtocol.integer(value, "frameGeneration", 1L) == owner.generation)
                        val next = GpuScienceSessionProtocol.stats(value.getJSONObject("session"), epoch, budget, stats)
                        val lastSequence = life.nextSequence()?.minus(1L) ?: error("sequence exhausted")
                        require(life.frameSealed(epoch, owner.generation, lastSequence))
                        owner.recordSessionStats(stats, next)
                        stats = next; owner.sealed = true; frame = null; owner.finished.complete(true)
                    }
                    GpuScienceIpc.ERROR -> {
                        GpuScienceSessionProtocol.exact(value, setOf("schemaVersion", "epoch", "code"))
                        val code = GpuRenderCode.entries.firstOrNull { it.name == value.getString("code") } ?: GpuRenderCode.INVALID_RESULT
                        retire(if (code == GpuRenderCode.OK) GpuRenderCode.INVALID_RESULT else code)
                    }
                    else -> error("invalid message")
                }
            } catch (_: Throwable) { retire(GpuRenderCode.INVALID_RESULT) }
        }
    }
    private fun transportFailed(definiteNoStart: Boolean) {
        synchronized(lock) {
            retire(GpuRenderCode.BIND_FAILED)
            if (definiteNoStart && life.confirmStartRejected(epoch)) releaseIfProvenLocked()
        }
    }
    private fun died() {
        synchronized(lock) {
            val wasRetiring = life.isRetiring()
            if (!life.confirmDead(epoch)) { lease?.quarantine(); return }
            if (!wasRetiring) stopCode = GpuRenderCode.HELPER_DIED
            releaseIfProvenLocked()
        }
    }
    private fun releaseIfProvenLocked() {
        if (didRelease || !life.canReleaseSessionLease() || lease == null) return
        lease!!.releaseAfterStopProof()
        require(life.finish())
        didRelease = true
        frame?.records?.values?.forEach {
            if (it.status != "success_sealed") { it.status = if (stopCode == GpuRenderCode.CANCELLED) "cancelled_dead" else "failed_dead"; it.code = stopCode }
        }
        ready.complete(false); frame?.opened?.complete(false); frame?.finished?.complete(false)
        transport?.detach()
        // Publish pool release before waking epoch-rollover waiters.
        released(this)
        stopped.complete(Unit)
        drainScope.cancel()
        val callbacks = cleanups.toList(); cleanups.clear()
        callbacks.forEach { runCatching(it) }
    }
}
