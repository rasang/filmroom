package dev.filmroom.engine

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import android.content.ComponentCallbacks2
import dev.filmroom.BuildConfig
import dev.filmroom.diagnostics.GpuLifecycleRecorder
import dev.filmroom.diagnostics.GpuLifecyclePhase
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Closed schema, separate from the death-only one-shot render protocol. */
internal object GpuScienceIpc {
    const val HELLO = 1; const val OPEN = 2; const val BEGIN_FRAME = 3
    const val RUN_STAGE = 4; const val FINISH_FRAME = 5; const val CLOSE = 6
    const val READY = 101; const val OPENED = 102; const val FRAME_OPENED = 103
    const val STAGE_SEALED = 104; const val FRAME_SEALED = 105; const val ERROR = 106
    const val JSON = "json"; const val EPOCH = "epoch"
    const val MAX_JSON_BYTES = 16 * 1024
    const val HELPER_DEADLINE_MS = 120_000L
    const val IDLE_TIMEOUT_MS = 15_000L
    const val CANCEL_GRACE_MS = 100L
}

/** One same-UID isolated process, one serial worker, one immutable lifetime epoch. */
class GpuScienceService : Service() {
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gpu-science-watchdog").apply { isDaemon = true } }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "gpu-science-native").apply { isDaemon = true } }
    private val telemetry = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "gpu-science-telemetry").apply { isDaemon = true } }
    @Volatile private var activeToken = 0L
    @Volatile private var activeOperation: GpuRenderOperation? = null
    private val telemetrySamples = java.util.concurrent.atomic.AtomicInteger()
    private fun trace(phase: GpuLifecyclePhase, durable: Boolean = false) {
        val token = activeToken
        runCatching {
            if (durable) GpuLifecycleRecorder.recordBeforeBlocking(this, token, activeOperation, phase)
            else GpuLifecycleRecorder.record(this, token, activeOperation, phase)
        }
    }
    private fun sampleNativeWait() {
        val token = activeToken
        if (token <= 0 || telemetrySamples.incrementAndGet() > 60) return
        trace(GpuLifecyclePhase.WAIT)
        runCatching { GpuLifecycleRecorder.recordNative(NativeGpuProbeBridge.nativeGpuPhaseSnapshot(token)) }
    }
    private val handler = Handler(Looper.getMainLooper()) { m -> handle(m); true }
    private val messenger = Messenger(handler)
    private val retiring = AtomicBoolean(false)
    private val parentWatch = GpuHelperParentWatch { retire() }
    @Volatile private var epoch = 0L
    @Volatile private var parent: Messenger? = null
    private var life: GpuScienceSessionLifetime? = null
    private var startupReady = false
    private var pendingHello = false
    private var busy = false
    private var budget: GpuScienceSessionBudget? = null
    private var timer: ScheduledFuture<*>? = null
    private fun identity() = GpuProbeProcess.isScienceHelper(packageName, Application.getProcessName())
    private fun killOwnProcess() { if (identity()) Process.killProcess(Process.myPid()) }
    @Synchronized private fun arm(delay: Long) {
        // A completion racing retirement must never replace the hard kill with an idle timer.
        if (retiring.get() && delay > GpuScienceIpc.CANCEL_GRACE_MS) return
        timer?.cancel(false)
        timer = watchdog.schedule({ killOwnProcess() }, delay, TimeUnit.MILLISECONDS)
    }
    /** Arm independent death before touching native locks, callbacks, or Binder. */
    private fun retire() {
        if (!retiring.compareAndSet(false, true)) return
        arm(GpuScienceIpc.CANCEL_GRACE_MS)
        val e = epoch
        life?.requestStop(e)
        if (e > 0) Thread({ runCatching { NativeGpuSessionBridge.nativeRetireGpuSession(e) } }, "gpu-science-retire").apply { isDaemon = true; start() }
    }
    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.PERF_BUILD || !identity()) { stopSelf(); return }
        GpuLifecycleRecorder.configure(this)
        arm(GpuScienceIpc.IDLE_TIMEOUT_MS)
        telemetry.scheduleWithFixedDelay({ sampleNativeWait() }, 2000, 2000, TimeUnit.MILLISECONDS)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = runCatching {
            val extras = intent?.extras ?: error("Missing epoch")
            require(extras.keySet() == setOf(GpuScienceIpc.EPOCH))
            (extras.get(GpuScienceIpc.EPOCH) as? Long)?.also { require(it > 0) } ?: error("Invalid epoch")
        }.getOrNull()
        if (!BuildConfig.PERF_BUILD || !identity() || requested == null || !startupGate.claim(requested)) {
            retire()
        } else {
            epoch = requested
            life = GpuScienceSessionLifetime(requested).also { check(it.requestHelper()) }
            // READY follows the START_NOT_STICKY return, not merely a successful bind.
            handler.post {
                if (!retiring.get()) {
                    if (pendingHello && pendingEpoch != epoch) { fail(GpuRenderCode.INVALID_REQUEST); return@post }
                    startupReady = true
                    check(life!!.acknowledgeStartup(epoch))
                    if (pendingHello) { pendingHello = false; send(GpuScienceIpc.READY, base()) }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = if (BuildConfig.PERF_BUILD && identity() && !retiring.get()) messenger.binder else null
    override fun onUnbind(intent: Intent?): Boolean { retire(); return false }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) retire() }
    override fun onLowMemory() { super.onLowMemory(); retire() }
    override fun onDestroy() {
        // Do not wait for a blocked driver or executor shutdown to release resources.
        killOwnProcess()
        activeToken = 0L
        telemetry.shutdownNow(); worker.shutdownNow(); watchdog.shutdownNow()
        super.onDestroy()
    }
    private fun base() = JSONObject().put("schemaVersion", 1).put("epoch", epoch)
    private fun number(v: JSONObject, key: String, min: Long = 1, max: Long = Long.MAX_VALUE): Long {
        val value = v.get(key)
        val n = when (value) { is Int -> value.toLong(); is Long -> value; else -> error("Invalid integer") }
        require(n in min..max); return n
    }
    private fun exact(v: JSONObject, keys: Set<String>) { require(v.keys().asSequence().toSet() == keys) }
    private fun parse(raw: String): JSONObject {
        require(raw.length <= GpuScienceIpc.MAX_JSON_BYTES && raw.toByteArray(Charsets.UTF_8).size <= GpuScienceIpc.MAX_JSON_BYTES)
        return JSONObject(raw)
    }
    private fun send(what: Int, value: JSONObject) {
        val raw = value.toString()
        if (raw.length > GpuScienceIpc.MAX_JSON_BYTES || raw.toByteArray(Charsets.UTF_8).size > GpuScienceIpc.MAX_JSON_BYTES) { retire(); return }
        val reply = parent ?: run { retire(); return }
        try { reply.send(Message.obtain(null, what).apply { replyTo = messenger; data = Bundle().apply { putString(GpuScienceIpc.JSON, raw) } }) }
        catch (_: Exception) { retire() }
    }
    private class Failure(val code: GpuRenderCode) : Exception()
    private fun fail(code: GpuRenderCode) { retire(); send(GpuScienceIpc.ERROR, base().put("code", code.name)) }
    private fun native(raw: String): JSONObject {
        val value = parse(raw)
        if (value.opt("ok") != true) throw Failure(GpuRenderCode.NATIVE_FAILED)
        require(number(value, "schemaVersion") == 1L && number(value, "epoch") == epoch)
        return value
    }
    private fun stats(value: JSONObject): JSONObject {
        val keys = setOf("epoch", "prepaidBytes", "fixedBytes", "chargedBytes", "pipelineSlots", "poolBytes", "pipelineHits", "bufferHits", "clearSubmissions")
        exact(value, keys)
        val b = budget ?: error("Missing admission")
        require(number(value, "epoch") == epoch)
        require(number(value, "prepaidBytes") == b.prepaidBytes && number(value, "fixedBytes") == b.fixedBytes)
        val charged = number(value, "chargedBytes", b.fixedBytes, b.prepaidBytes)
        number(value, "pipelineSlots", 0, b.pipelineLimit.toLong())
        number(value, "poolBytes", 0, charged)
        number(value, "pipelineHits", 0); number(value, "bufferHits", 0); number(value, "clearSubmissions", 0)
        return JSONObject().also { safe -> keys.forEach { safe.put(it, number(value, it, 0)) } }
    }
    /** No unbounded command queue: caller cannot submit until prior reply/seal. */
    private fun submit(what: Int, complete: () -> Boolean, work: () -> JSONObject) {
        check(!busy && !retiring.get())
        busy = true
        arm(GpuScienceIpc.HELPER_DEADLINE_MS)
        worker.execute {
            try {
                check(parentWatch.isAlive() && !retiring.get())
                val result = work()
                if (!handler.post {
                    if (!retiring.get()) {
                        try {
                            check(complete())
                            busy = false
                            // CPU preparation between stages is part of the active frame,
                            // not the short post-frame idle grace.
                            arm(if (what == GpuScienceIpc.FRAME_SEALED) GpuScienceIpc.IDLE_TIMEOUT_MS else GpuScienceIpc.HELPER_DEADLINE_MS)
                            send(what, result)
                        } catch (_: Exception) { fail(GpuRenderCode.INVALID_RESULT) }
                    }
                }) retire()
            } catch (e: Failure) { fail(e.code) }
            catch (_: LinkageError) { fail(GpuRenderCode.NATIVE_UNAVAILABLE) }
            catch (_: Exception) { fail(GpuRenderCode.INVALID_RESULT) }
        }
    }
    private fun handle(message: Message) {
        if (!BuildConfig.PERF_BUILD || !identity() || message.sendingUid != Process.myUid() || retiring.get()) return
        try {
            require(message.data.keySet() == setOf(GpuScienceIpc.JSON))
            val value = parse(message.data.get(GpuScienceIpc.JSON) as? String ?: error("Missing JSON"))
            require(number(value, "schemaVersion") == 1L)
            val e = number(value, "epoch")
            // HELLO may arrive before onStartCommand. Hold its epoch until startup.
            require(epoch == 0L || e == epoch)
            val reply = message.replyTo ?: error("Missing parent")
            if (message.what == GpuScienceIpc.HELLO) {
                exact(value, setOf("schemaVersion", "epoch"))
                require(parent == null || parent!!.binder == reply.binder)
                require(parentWatch.attach(reply.binder))
                parent = reply
                if (epoch == 0L) { pendingHello = true; pendingEpoch = e }
                else if (startupReady) send(GpuScienceIpc.READY, base())
                else { pendingHello = true; pendingEpoch = e }
                return
            }
            require(startupReady && e == epoch && parent?.binder == reply.binder && parentWatch.isAlive())
            val lifetime = life ?: error("Missing lifetime")
            if (message.what == GpuScienceIpc.CLOSE) {
                exact(value, setOf("schemaVersion", "epoch")); retire(); return
            }
            require(!busy)
            when (message.what) {
                GpuScienceIpc.OPEN -> {
                    exact(value, setOf("schemaVersion", "epoch", "prepaidBytes", "fixedBytes", "pipelineLimit"))
                    val admitted = GpuScienceSessionBudget(number(value, "prepaidBytes", 1, GpuRenderProtocol.HARD_CAP), number(value, "fixedBytes"), number(value, "pipelineLimit", 1, 64).toInt())
                    admitted.validate()
                    check(lifetime.attemptOpen(e)); budget = admitted
                    val config = value.toString()
                    submit(GpuScienceIpc.OPENED, { lifetime.opened(e) }) {
                        check(parentWatch.claimNative { GpuHelperProcessLock.acquire(this) })
                        val result = native(NativeGpuSessionBridge.nativeOpenGpuSession(config))
                        exact(result, setOf("ok", "schemaVersion", "epoch", "state")); require(result.get("state") == "ready")
                        base()
                    }
                }
                GpuScienceIpc.BEGIN_FRAME -> {
                    exact(value, setOf("schemaVersion", "epoch", "frameGeneration"))
                    val g = number(value, "frameGeneration"); check(lifetime.attemptBeginFrame(e, g))
                    submit(GpuScienceIpc.FRAME_OPENED, { lifetime.frameOpened(e, g) }) {
                        val result = native(NativeGpuSessionBridge.nativeBeginGpuSessionFrame(e, g))
                        exact(result, setOf("ok", "schemaVersion", "epoch", "generation", "state"))
                        require(number(result, "generation") == g && result.get("state") == "frame")
                        base().put("frameGeneration", g)
                    }
                }
                GpuScienceIpc.RUN_STAGE -> {
                    exact(value, setOf("schemaVersion", "epoch", "frameGeneration", "sequence", "request"))
                    val g = number(value, "frameGeneration"); val sequence = number(value, "sequence")
                    val raw = value.get("request") as? String ?: error("Missing request")
                    val packet = parse(raw)
                    val ticket = GpuScienceStageTicket(e, g, sequence, number(packet, "token"), packet.get("manifestSha256") as? String ?: error("Missing digest"))
                    check(lifetime.attemptStage(ticket))
                    activeToken = ticket.token; telemetrySamples.set(0)
                    submit(GpuScienceIpc.STAGE_SEALED, {
                        lifetime.stageSealed(ticket).also { sealed ->
                            if (sealed) { trace(GpuLifecyclePhase.STAGE_END); activeToken = 0L; activeOperation = null }
                        }
                    }) {
                        val request = GpuRenderProtocol.parseRequest(raw, this)
                        activeOperation = request.operation
                        trace(GpuLifecyclePhase.RUN_RECEIVED)
                        require(request.token == ticket.token && request.digest == ticket.manifestSha256)
                        val dir = GpuRenderProtocol.stageDirectory(this, request.token)
                        trace(GpuLifecyclePhase.PLAN_ENTER, durable = true)
                        val plan = GpuRenderProtocol.parseStagePlan(GpuRenderProtocol.nativePlan(request.operation, dir.path, request.token), request.token, request.operation)
                        require(GpuRenderProtocol.matches(request, plan) && plan.combinedPeakBytes <= budget!!.prepaidBytes)
                        trace(GpuLifecyclePhase.PLAN_EXIT)
                        trace(GpuLifecyclePhase.NATIVE_ENTER, durable = true)
                        val result = native(NativeGpuSessionBridge.nativeRunGpuSessionStage(e, g, sequence, request.operation.wire, dir.path, plan.combinedPeakBytes, request.token, request.digest))
                        trace(GpuLifecyclePhase.NATIVE_RETURN)
                        exact(result, setOf("ok", "schemaVersion", "epoch", "generation", "sequence", "token", "operation", "stageSealed", "summary", "session"))
                        require(number(result, "generation") == g && number(result, "sequence") == sequence && number(result, "token") == request.token)
                        require(result.get("operation") == request.operation.wire && result.get("stageSealed") == true)
                        val parsed = GpuRenderProtocol.parseStageNativeResult(result.getJSONObject("summary").toString(), plan)
                        if (parsed.first != GpuRenderCode.OK || parsed.second == null) throw Failure(parsed.first.takeIf { it != GpuRenderCode.OK } ?: GpuRenderCode.INVALID_RESULT)
                        base().put("frameGeneration", g).put("sequence", sequence).put("token", request.token)
                            .put("manifestSha256", request.digest).put("operation", request.operation.wire)
                            .put("summary", GpuRenderProtocol.encodeResult(GpuRenderCode.OK, parsed.second)).put("session", stats(result.getJSONObject("session")))
                    }
                }
                GpuScienceIpc.FINISH_FRAME -> {
                    exact(value, setOf("schemaVersion", "epoch", "frameGeneration"))
                    val g = number(value, "frameGeneration"); check(lifetime.attemptFinishFrame(e, g))
                    val sequence = (lifetime.nextSequence() ?: error("Sequence exhausted")) - 1
                    submit(GpuScienceIpc.FRAME_SEALED, { lifetime.frameSealed(e, g, sequence) }) {
                        val result = native(NativeGpuSessionBridge.nativeSealGpuSessionFrame(e, g))
                        exact(result, setOf("ok", "schemaVersion", "epoch", "generation", "frameSealed", "session"))
                        require(number(result, "generation") == g && result.get("frameSealed") == true)
                        base().put("frameGeneration", g).put("session", stats(result.getJSONObject("session")))
                    }
                }
                else -> error("Invalid message")
            }
        } catch (_: Exception) { fail(GpuRenderCode.INVALID_REQUEST) }
    }
    private var pendingEpoch = 0L
    companion object { private val startupGate = GpuProbeProcessGate() }
}
