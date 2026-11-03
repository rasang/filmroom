package dev.filmroom.engine

import android.app.Application
import android.content.*
import android.os.*
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GpuDrainQuarantineTest {
    private class ContextFake(base: Context): ContextWrapper(base) {
        lateinit var connection: ServiceConnection
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent): ComponentName? = service.component
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            this.connection = connection; return true
        }
        override fun stopService(service: Intent) = true
        override fun unbindService(connection: ServiceConnection) = Unit
    }
    private class Remote(private val base: IBinder): IBinder by base {
        var alive = true
        var failCheck = false
        private var death: IBinder.DeathRecipient? = null
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        override fun isBinderAlive(): Boolean { if (failCheck) error("broken binder observation"); return alive }
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { death = recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean { death = null; return true }
        fun die() { alive = false; death?.binderDied() }
    }
    private class Setup(budget: PreviewGpuBudget? = null) {
        val context = ContextFake(RuntimeEnvironment.getApplication())
        val closed = AtomicInteger()
        val quarantined = AtomicInteger()
        val remote = Remote(Messenger(Handler(Looper.getMainLooper()) { true }).binder)
        val delegate = GpuRenderDelegate.forTesting(context, GpuRenderTestHooks(
            plan={_,_->GpuRenderFixtures.envelope()},
            reserve={_,_->object:RetainedNativeWork {
                override fun close() { closed.incrementAndGet() }
                override fun quarantine() { quarantined.incrementAndGet() }
                override suspend fun <T> run(memory:()->NativeMemorySnapshot,work:suspend ()->T):T = error("nested worker")
            }},drainMillis=30), previewBudget=budget)
        suspend fun bind() {
            assertEquals(1, withContext(Dispatchers.IO){delegate.begin(GpuRenderFixtures.request(context))})
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            context.connection.onServiceConnected(ComponentName(context,GpuRenderService::class.java),remote)
        }
        // Inject a Messenger-delivered message after bind; all production UID, Binder,
        // token and lifetime checks still execute. No fake death/terminal state is set.
        fun ready(uid:Int=Process.myUid(),token:Long=91) {
            val session=delegate.javaClass.getDeclaredField("session").apply{isAccessible=true}.get(delegate)
            val message=Message.obtain(null,GpuRenderIpc.READY).apply {
                sendingUid=uid;replyTo=Messenger(remote)
                data=Bundle().apply{putLong(GpuRenderIpc.TOKEN,token)}
            }
            delegate.javaClass.declaredMethods.single{it.name=="receive"}.apply{isAccessible=true}.invoke(delegate,session,message)
        }
    }
    @Test fun noReadyRevokesRunWithoutClaimingDeathAndLateReadyCannotRestart()=runBlocking {
        val s=Setup();s.bind()
        assertTrue(s.delegate.awaitSafeTermination());assertEquals(1,s.closed.get())
        s.ready()
        assertEquals("cancelled_stopped",JSONObject(s.delegate.poll(91)).getString("status"))
        assertEquals(1,s.closed.get())
    }
    @Test fun readyHangReturnsUnknownRetainsOwnershipAndOnlyDeathReleases()=runBlocking {
        val s=Setup();s.bind();s.ready()
        assertEquals("running",JSONObject(s.delegate.poll(91)).getString("status"))
        var cleaned=0
        try {
            assertFalse(withTimeout(1000){s.delegate.awaitSafeTermination()})
            s.delegate.whenSafeToRelease { cleaned++ }
            assertEquals(0,s.closed.get());assertEquals(0,cleaned);assertTrue(s.quarantined.get()>0)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
            assertTrue(runCatching{s.delegate.poll(91)}.isFailure)
            assertEquals(0,s.closed.get())
        } finally { s.remote.die() }
        assertEquals(1,s.closed.get());assertEquals(1,cleaned)
        assertTrue(s.delegate.awaitSafeTermination())
    }
    @Test fun drainObservationExceptionDoesNotAuthorizeCleanup()=runBlocking {
        val s=Setup();s.bind();s.ready();s.remote.failCheck=true
        var cleaned=false
        try {
            assertFalse(s.delegate.awaitSafeTermination())
            s.delegate.whenSafeToRelease { cleaned=true }
            assertFalse(cleaned);assertEquals(0,s.closed.get())
        } finally { s.remote.failCheck=false;s.remote.die() }
        assertTrue(cleaned);assertEquals(1,s.closed.get())
    }
    @Test fun wrongUidAndStaleGenerationCannotAuthorizeRun()=runBlocking {
        val s=Setup();s.bind()
        try {
            s.ready(uid=Process.myUid()+1);s.ready(token=92)
            assertEquals("pending",JSONObject(s.delegate.poll(91)).getString("status"))
            assertTrue(s.delegate.awaitSafeTermination())
        } finally { s.remote.die() }
        assertEquals(1,s.closed.get())
    }
    @Test fun cancellationCannotSkipBoundedDrainAndDeathCallbacksRunOnce()=runBlocking {
        val s=Setup();s.bind();s.ready();val entered=CompletableDeferred<Unit>();var safe=true
        val job=launch { entered.complete(Unit);try{awaitCancellation()}finally{safe=s.delegate.awaitSafeTermination()} }
        entered.await();withTimeout(1000){job.cancelAndJoin()}
        assertFalse(safe);assertEquals(0,s.closed.get())
        var callbacks=0;s.delegate.whenSafeToRelease { callbacks++ }
        s.remote.die();s.remote.die()
        assertEquals(1,callbacks);assertEquals(1,s.closed.get())
    }
    @Test fun previewDeadlineAfterRunKeepsLeaseUntilObservedDeath() = runBlocking {
        var now = 1_000L
        val budget = PreviewGpuBudget(8_000) { now }.apply { start() }
        val s = Setup(budget); s.bind(); s.ready()
        try {
            assertEquals("running", JSONObject(s.delegate.poll(91)).getString("status"))
            now += 8_000
            val stopping = JSONObject(s.delegate.poll(91))
            assertEquals("stopping", stopping.getString("status"))
            assertEquals("PREVIEW_BUDGET_EXHAUSTED", stopping.getString("code"))
            assertEquals(0, s.closed.get())
            s.remote.die()
            val stopped = JSONObject(s.delegate.poll(91))
            assertEquals("failed_stopped", stopped.getString("status"))
            assertEquals("PREVIEW_BUDGET_EXHAUSTED", stopped.getString("code"))
            assertEquals(1, s.closed.get())
            assertTrue(s.delegate.awaitSafeTermination())
        } finally { s.remote.die(); s.delegate.awaitSafeTermination() }
    }
    @Test fun previewDeadlineBeforeReadyRevokesRunAndLateReadyCannotRestart() = runBlocking {
        var now = 1_000L
        val budget = PreviewGpuBudget(8_000) { now }.apply { start() }
        val s = Setup(budget); s.bind()
        try {
            now += 8_000
            s.ready()
            val poll = JSONObject(s.delegate.poll(91))
            assertEquals("not_started", poll.getString("status"))
            assertEquals("PREVIEW_BUDGET_EXHAUSTED", poll.getString("code"))
            assertEquals(1, s.closed.get())
            s.ready()
            assertEquals("not_started", JSONObject(s.delegate.poll(91)).getString("status"))
        } finally { s.remote.die(); s.delegate.awaitSafeTermination() }
    }

}
