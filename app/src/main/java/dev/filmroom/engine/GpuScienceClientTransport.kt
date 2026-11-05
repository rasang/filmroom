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
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Authenticated callback boundary; implementations retain ownership until detach. */
internal interface GpuScienceTransport {
    fun start()
    fun send(what: Int, json: String): Boolean
    fun requestClose()
    fun checkDeath()
    fun detach()
}

/** Binder transport only. The controller owns admission, seals and release proofs. */
internal class GpuScienceClientTransport(
    context: Context,
    private val epoch: Long,
    private val onMessage: (Int, String) -> Unit,
    private val onDeath: () -> Unit,
    private val onFailure: (definiteNoStart: Boolean) -> Unit,
) : GpuScienceTransport {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val startedOnce = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val detached = AtomicBoolean(false)
    private var bindingAttempted = false
    private var remote: Messenger? = null
    private val live = mutableMapOf<IBinder, IBinder.DeathRecipient>()
    private val known = mutableSetOf<IBinder>()
    private var deathReported = false
    private var startupReady = false
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { message ->
        receive(message); true
    })
    init { require(epoch > 0) }
    private fun hello() = JSONObject().put("schemaVersion", 1).put("epoch", epoch).toString()
    private fun bounded(raw: String) = raw.length <= GpuScienceIpc.MAX_JSON_BYTES && raw.toByteArray(Charsets.UTF_8).size <= GpuScienceIpc.MAX_JSON_BYTES
    private fun failure(definite: Boolean) { if (!detached.get()) onFailure(definite) }
    private fun observe(binder: IBinder) {
        if (binder in known) return
        val recipient = IBinder.DeathRecipient { main.post { died(binder) } }
        known.add(binder); live[binder] = recipient
        try { binder.linkToDeath(recipient, 0) }
        catch (_: android.os.RemoteException) { died(binder) }
    }
    private fun died(binder: IBinder) {
        val recipient = live.remove(binder) ?: return
        runCatching { binder.unlinkToDeath(recipient, 0) }
        if (live.isEmpty() && known.isNotEmpty() && !deathReported && !detached.get()) {
            deathReported = true; closing.set(true); onDeath()
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (detached.get()) return
            if (name.packageName != context.packageName || name.className != GpuScienceService::class.java.name) {
                failure(false); return
            }
            val previous = remote?.binder
            observe(binder)
            if (deathReported || (previous != null && previous != binder)) {
                closing.set(true)
                sendNow(Messenger(binder), GpuScienceIpc.CLOSE, hello())
                failure(false)
                return
            }
            remote = Messenger(binder)
            // Even when cancelled before binding, obtain nonsticky startup proof
            // with HELLO, never OPEN. Close immediately after authenticated READY.
            sendNow(remote!!, GpuScienceIpc.HELLO, hello())
        }
        override fun onServiceDisconnected(name: ComponentName) { checkDeath(); if (!deathReported) failure(false) }
        override fun onBindingDied(name: ComponentName) { checkDeath(); if (!deathReported) failure(false) }
        override fun onNullBinding(name: ComponentName) { failure(false) }
    }
    /** START_NOT_STICKY readiness is a separate authenticated service message. */
    override fun start() {
        if (!startedOnce.compareAndSet(false, true)) { failure(false); return }
        if (!main.post {
            if (detached.get()) return@post
            if (closing.get() || !BuildConfig.PERF_BUILD || GpuProbeProcess.isWorker(context.packageName, Application.getProcessName())) {
                failure(true); return@post
            }
            val intent = Intent(context, GpuScienceService::class.java).putExtra(GpuScienceIpc.EPOCH, epoch)
            val started = try { context.startService(intent) }
            catch (_: SecurityException) { failure(true); return@post }
            catch (_: IllegalStateException) { failure(true); return@post }
            catch (_: IllegalArgumentException) { failure(true); return@post }
            catch (_: Exception) { failure(false); return@post }
            if (started == null) { failure(true); return@post }
            // A failed bind does not prove that the started helper never ran.
            bindingAttempted = true
            try { if (!context.bindService(intent, connection, 0)) failure(false) }
            catch (_: Exception) { failure(false) }
        }) failure(true)
    }
    /** Acceptance means queued on main, not delivered. Failures are reported separately. */
    override fun send(what: Int, json: String): Boolean {
        if (detached.get() || (closing.get() && what != GpuScienceIpc.CLOSE) || !bounded(json)) return false
        return main.post {
            if (detached.get() || (closing.get() && what != GpuScienceIpc.CLOSE)) return@post
            val target = remote
            if (target == null) failure(false) else sendNow(target, what, json)
        }
    }
    private fun sendNow(target: Messenger, what: Int, json: String) {
        if (detached.get() || !bounded(json)) return
        try { target.send(Message.obtain(null, what).apply {
            replyTo = receiver
            data = Bundle().apply { putString(GpuScienceIpc.JSON, json) }
        }) } catch (_: Exception) { failure(false) }
    }
    private fun receive(message: Message) {
        if (detached.get() || message.sendingUid != Process.myUid()) return
        try {
            require(message.replyTo?.binder == remote?.binder && remote != null)
            require(message.data.keySet() == setOf(GpuScienceIpc.JSON))
            val raw = message.data.get(GpuScienceIpc.JSON) as? String ?: error("Missing JSON")
            require(bounded(raw))
            if (message.what == GpuScienceIpc.READY) {
                val ready = JSONObject(raw)
                require(ready.keys().asSequence().toSet() == setOf("schemaVersion", "epoch"))
                require(GpuScienceSessionProtocol.integer(ready, "schemaVersion") == 1L)
                require(GpuScienceSessionProtocol.integer(ready, "epoch", 1) == epoch)
                startupReady = true
            }
            if (!closing.get() || message.what == GpuScienceIpc.ERROR || message.what == GpuScienceIpc.READY) onMessage(message.what, raw)
            if (closing.get() && startupReady) remote?.let { sendNow(it, GpuScienceIpc.CLOSE, hello()) }
        } catch (_: Exception) { failure(false) }
    }
    override fun requestClose() {
        closing.set(true)
        if (!main.post {
            if (!detached.get() && startupReady) {
                val targets = live.keys.toList()
                targets.forEach { sendNow(Messenger(it), GpuScienceIpc.CLOSE, hello()) }
            }
        }) failure(false)
    }
    override fun checkDeath() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { checkDeath() }; return }
        live.keys.toList().filter { !it.isBinderAlive }.forEach { died(it) }
    }
    /** Call only after controller proves no start or authenticated process death. */
    override fun detach() {
        if (!detached.compareAndSet(false, true)) return
        closing.set(true)
        main.post {
            live.forEach { (binder, recipient) -> runCatching { binder.unlinkToDeath(recipient, 0) } }
            live.clear()
            if (bindingAttempted) { bindingAttempted = false; runCatching { context.unbindService(connection) } }
            remote = null
        }
    }
}
