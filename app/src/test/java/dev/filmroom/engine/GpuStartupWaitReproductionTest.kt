package dev.filmroom.engine

import android.app.Application
import android.content.*
import android.os.*
import dev.filmroom.diagnostics.GpuLifecyclePhase
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Same 512x338/384 transport geometry as the supplied log, with missing callbacks
 * injected deliberately. This proves compatible wait mechanisms, not their cause
 * on the phone. No optional new preview deadline is enabled. The original 539af286
 * 125 s deadline, READY gate, and no-RUN cancellation paths remain exercised.
 * Film/paper/seed do not enter this transport contract; native science is stubbed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GpuStartupWaitReproductionTest {
    private class ContextFake(base: Context) : ContextWrapper(base) {
        var starts = 0
        lateinit var connection: ServiceConnection
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent): ComponentName? { starts++; return service.component }
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            this.connection = connection; return true
        }
        override fun stopService(service: Intent) = true
        override fun unbindService(connection: ServiceConnection) = Unit
    }
    private class Remote(private val base: IBinder) : IBinder by base {
        var alive = true
        private var death: IBinder.DeathRecipient? = null
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun isBinderAlive() = alive
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { death = recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean { death = null; return true }
        fun die() { alive = false; death?.binderDied() }
    }
    private class Setup(val height: Int) {
        val context = ContextFake(RuntimeEnvironment.getApplication())
        val closes = AtomicInteger()
        val events = mutableListOf<GpuLifecyclePhase>()
        val remote = Remote(Messenger(Handler(Looper.getMainLooper()) { true }).binder)
        private val envelope = JSONObject(checkNotNull(javaClass.classLoader.getResource("gpu-contracts/exposure.json")).readText())
        private val plan = envelope.getJSONObject("plan").apply {
            val pixels = 512L * height
            val chunk = 65536L
            val table = getLong("tableScalars")
            val device = 4 * table + 36 * chunk + 4096 + 8
            val host = 16 * table + 128 * chunk + 2 * GpuRenderProtocol.MIB
            put("token", 91L); put("width", 512); put("height", height)
            put("chunkPixels", chunk); put("chunkCount", (pixels + chunk - 1) / chunk)
            put("deviceBytes", device); put("hostBytes", host)
            put("combinedPeakBytes", device + host + 128 * GpuRenderProtocol.MIB)
            put("diskBytes", 36 * pixels + 8 * table + 65536)
        }
        val delegate = GpuRenderDelegate.forTesting(context, GpuRenderTestHooks(
            plan = { _, _ -> envelope.toString() },
            reserve = { _, _ -> object : RetainedNativeWork {
                override fun close() { closes.incrementAndGet() }
                override suspend fun <T> run(memory: () -> NativeMemorySnapshot, work: suspend () -> T): T = error("Nested worker forbidden")
            } }, lifecycle = { _, phase, _, _ -> events.add(phase) }), GpuRenderOperation.FILM_EXPOSURE)
        suspend fun begin() {
            val directory = File(context.cacheDir, "gpu-render/gpu-stage-91").apply { mkdirs() }
            val request = JSONObject().put("schemaVersion", 1).put("operation", GpuRenderOperation.FILM_EXPOSURE.wire)
                .put("token", 91L).put("parentHandle", 11L).put("jobDir", directory.canonicalPath)
                .put("manifestSha256", plan.getString("manifestSha256")).put("budgetBytes", plan.getLong("combinedPeakBytes"))
                .put("width", 512).put("height", height).put("totalPixels", 512L * height * 3)
            assertEquals(1, withContext(Dispatchers.IO) { delegate.begin(request.toString()) })
        }
        fun connect() {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            context.connection.onServiceConnected(ComponentName(context, GpuRenderService::class.java), remote)
        }
        fun ready() {
            val session = delegate.javaClass.getDeclaredField("session").apply { isAccessible = true }.get(delegate)
            val message = Message.obtain(null, GpuRenderIpc.READY).apply {
                sendingUid = Process.myUid(); replyTo = Messenger(remote)
                data = Bundle().apply { putLong(GpuRenderIpc.TOKEN, 91L) }
            }
            delegate.javaClass.declaredMethods.single { it.name == "receive" }.apply { isAccessible = true }.invoke(delegate, session, message)
        }
        fun poll() = JSONObject(delegate.poll(91))
    }

    @Test fun absentMainLaunchCanRemainPendingForLoggedIntervalAndCancelWithoutAService() = runBlocking {
        val s = Setup(384)
        try {
            s.begin()
            ShadowSystemClock.advanceBy(Duration.ofMillis(56_856))
            assertEquals("pending", s.poll().getString("status")); assertEquals(0, s.context.starts)
            s.delegate.cancel(91)
            assertTrue(s.delegate.awaitSafeTermination())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, s.context.starts); assertEquals(1, s.closes.get())
        } finally { s.delegate.awaitSafeTermination() }
    }

    @Test fun missingReadySurvivesBothLoggedIntervalsAndLateReadyCannotAuthorizeRun() = runBlocking {
        for ((height, elapsed) in listOf(338 to 24_660L, 384 to 56_856L)) {
            val s = Setup(height)
            try {
                s.begin(); s.connect()
                ShadowSystemClock.advanceBy(Duration.ofMillis(elapsed))
                assertEquals("pending", s.poll().getString("status"))
                assertEquals(0, s.closes.get())
                s.delegate.cancel(91); s.ready()
                assertEquals("cancelled_stopped", s.poll().getString("status"))
                assertFalse(s.events.contains(GpuLifecyclePhase.RUN_SENT))
                assertEquals(1, s.closes.get())
            } finally { s.remote.die(); s.delegate.awaitSafeTermination() }
        }
    }

    @Test fun missingReadyHas125SecondDeadlineRatherThan25Or57SecondDeadline() = runBlocking {
        val s = Setup(384)
        try {
            s.begin(); s.connect()
            ShadowSystemClock.advanceBy(Duration.ofMillis(GpuRenderIpc.MAIN_DEADLINE_MS))
            assertEquals("not_started", s.poll().getString("status"))
            assertEquals("TIMED_OUT", s.poll().getString("code"))
            s.ready()
            assertFalse(s.events.contains(GpuLifecyclePhase.RUN_SENT))
            assertEquals(1, s.closes.get())
        } finally { s.remote.die(); s.delegate.awaitSafeTermination() }
    }

    @Test fun readyWithoutNativeResultCanRemainRunningButCancellationNeedsDeathProof() = runBlocking {
        val s = Setup(384)
        try {
            s.begin(); s.connect(); s.ready()
            ShadowSystemClock.advanceBy(Duration.ofMillis(56_856))
            assertEquals("running", s.poll().getString("status"))
            s.delegate.cancel(91)
            assertEquals("stopping", s.poll().getString("status"))
            assertEquals(0, s.closes.get())
            s.remote.die()
            assertEquals("cancelled_stopped", s.poll().getString("status"))
            assertEquals(1, s.closes.get())
            assertTrue(s.delegate.awaitSafeTermination())
        } finally { s.remote.die(); s.delegate.awaitSafeTermination() }
    }
}
