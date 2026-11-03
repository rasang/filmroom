package dev.filmroom.engine

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GpuRenderDelegateTest {
    private class FakeContext(base: Context) : ContextWrapper(base) {
        var starts = 0; var binds = 0; var stops = 0; var unbinds = 0
        var lateConnection: ServiceConnection? = null
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent): ComponentName? { starts++; return service.component }
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(0, flags); binds++; lateConnection = connection; return false
        }
        override fun stopService(service: Intent): Boolean { stops++; return true }
        override fun unbindService(connection: ServiceConnection) { unbinds++ }
    }
    private fun context() = FakeContext(RuntimeEnvironment.getApplication())
    private fun hooks(closes: AtomicInteger) = GpuRenderTestHooks(
        plan = { _, _ -> GpuRenderFixtures.envelope() },
        reserve = { estimate, _ ->
            assertEquals(GpuRenderFixtures.parsedPlan().combinedPeakBytes, estimate)
            object : RetainedNativeWork {
                override fun close() { closes.incrementAndGet() }
                override suspend fun <T> run(memory: () -> NativeMemorySnapshot, work: suspend () -> T): T = error("No nested worker admission permitted")
            }
        })
    @Test fun constructorCreatesOnlyCanonicalPrivateRootBeforeNativeEntry() {
        val context = context()
        val root = File(context.cacheDir, "gpu-render")
        root.deleteRecursively(); assertFalse(root.exists())
        GpuRenderDelegate.forTesting(context, hooks(AtomicInteger()))
        assertTrue(root.isDirectory)
        assertEquals(File(context.cacheDir.canonicalFile, "gpu-render"), root.canonicalFile)
        assertEquals(0, context.starts)
    }
    @Test fun memoryDenialIsDefinitiveNoStartAndKeepsBoundedReason() = runBlocking {
        val context = context(); val calls = AtomicInteger()
        val delegate = GpuRenderDelegate.forTesting(context, hooks(calls).copy(reserve = { _, _ -> throw NativeWorkUnavailable() }))
        val request = GpuRenderFixtures.request(context)
        assertEquals(0, withContext(Dispatchers.IO) { delegate.begin(request) })
        val poll = JSONObject(delegate.poll(91))
        assertEquals("not_started", poll.getString("status")); assertEquals("MEMORY_UNAVAILABLE", poll.getString("code"))
        assertEquals(0, context.starts); assertEquals(0, calls.get())
    }
    @Test fun cancellationBeforeMainLaunchRevokesRunAndRetainsNativeOwnedFiles() = runBlocking {
        val context = context(); val closes = AtomicInteger()
        val delegate = GpuRenderDelegate.forTesting(context, hooks(closes))
        val request = GpuRenderFixtures.request(context)
        val marker = File(context.cacheDir, "gpu-render/gpu-stage-91/manifest.json").apply { writeText("native owns this") }
        try {
            assertEquals(1, withContext(Dispatchers.IO) { delegate.begin(request) })
            assertEquals("pending", JSONObject(delegate.poll(91)).getString("status"))
            delegate.cancel(91)
            delegate.awaitSafeTermination()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("cancelled_stopped", JSONObject(delegate.poll(91)).getString("status"))
            assertEquals(1, closes.get()); assertEquals(0, context.starts)
            assertEquals("native owns this", marker.readText())
            assertEquals(0, withContext(Dispatchers.IO) { delegate.begin(request) })
        } finally { delegate.awaitSafeTermination() }
    }
    @Test fun falseBindUnregistersAndCompletesWithoutDeathClaimOrSecondWorker() = runBlocking {
        val context = context(); val closes = AtomicInteger()
        val delegate = GpuRenderDelegate.forTesting(context, hooks(closes))
        try {
            assertEquals(1, withContext(Dispatchers.IO) { delegate.begin(GpuRenderFixtures.request(context)) })
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val poll = JSONObject(delegate.poll(91))
            assertEquals("not_started", poll.getString("status"))
            assertEquals(1, context.starts); assertEquals(1, context.binds); assertEquals(1, context.unbinds)
            assertTrue(context.stops >= 1); assertEquals(1, closes.get())
            assertEquals(0L, poll.getLong("completedPixels"))
            context.lateConnection!!.onServiceConnected(ComponentName(context, GpuRenderService::class.java), Binder())
            assertEquals("not_started", JSONObject(delegate.poll(91)).getString("status"))
            assertEquals(1, closes.get())
            val second = GpuRenderDelegate.forTesting(context, hooks(AtomicInteger()))
            try { assertEquals(1, withContext(Dispatchers.IO) { second.begin(GpuRenderFixtures.request(context)) }) }
            finally { second.awaitSafeTermination() }
        } finally { delegate.awaitSafeTermination() }
    }
    @Test fun parentFinallyIndependentlyDrainsWhenNativeReturnsUnexpectedly() = runBlocking {
        val context = context(); val closes = AtomicInteger()
        val delegate = GpuRenderDelegate.forTesting(context, hooks(closes))
        assertEquals(1, withContext(Dispatchers.IO) { delegate.begin(GpuRenderFixtures.request(context)) })
        delegate.awaitSafeTermination() // No native poll/cancel loop is still running.
        assertEquals("cancelled_stopped", JSONObject(delegate.poll(91)).getString("status"))
        assertEquals(1, closes.get())
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, context.starts)
    }
    @Test fun errorBeforeSessionTransferClosesAdmissionAndDoesNotLeakGlobalOwner() = runBlocking {
        val context = context(); val closes = AtomicInteger()
        val broken = GpuRenderDelegate.forTesting(context, hooks(closes).copy(beforeSession = { throw AssertionError("simulated allocation error") }))
        val failure = runCatching { withContext(Dispatchers.IO) { broken.begin(GpuRenderFixtures.request(context)) } }.exceptionOrNull()
        assertTrue(failure is AssertionError); assertEquals(1, closes.get()); assertEquals(0, context.starts)
        assertEquals("not_started", JSONObject(broken.poll(91)).getString("status"))
        val next = GpuRenderDelegate.forTesting(context, hooks(AtomicInteger()))
        try { assertEquals(1, withContext(Dispatchers.IO) { next.begin(GpuRenderFixtures.request(context)) }) }
        finally { next.awaitSafeTermination() }
    }
    @Test fun errorAfterSessionTransferPreservesLeaseUntilIndependentFinallyDrain() = runBlocking {
        val context = context(); val closes = AtomicInteger()
        val delegate = GpuRenderDelegate.forTesting(context, hooks(closes).copy(afterSession = { throw AssertionError("simulated post failure") }))
        try {
            val failure = runCatching { withContext(Dispatchers.IO) { delegate.begin(GpuRenderFixtures.request(context)) } }.exceptionOrNull()
            assertTrue(failure is AssertionError); assertEquals(0, closes.get())
            assertEquals("pending", JSONObject(delegate.poll(91)).getString("status"))
            delegate.awaitSafeTermination()
            assertEquals(1, closes.get())
            assertEquals("cancelled_stopped", JSONObject(delegate.poll(91)).getString("status"))
        } finally { delegate.awaitSafeTermination() }
    }
    @Test fun invalidDirectoryCannotEscapeOwnedPacketAndMainThreadBeginCannotLaunch() = runBlocking {
        val context = context(); val delegate = GpuRenderDelegate.forTesting(context, hooks(AtomicInteger()))
        val request = JSONObject(GpuRenderFixtures.request(context)).put("jobDir", context.cacheDir.canonicalPath).toString()
        assertEquals(0, withContext(Dispatchers.IO) { delegate.begin(request) })
        assertEquals(0, context.starts)
        val onMain = GpuRenderDelegate.forTesting(context, hooks(AtomicInteger()))
        assertEquals(0, onMain.begin(GpuRenderFixtures.request(context)))
        assertEquals(0, context.starts)
    }
    @Test fun previewBudgetSpentPlanningNeverReservesOrStarts() = runBlocking {
        val context = context(); val closes = AtomicInteger()
        var now = 0L
        val budget = PreviewGpuBudget(8_000) { now }.apply { start() }
        val configured = hooks(closes).copy(
            plan = { _, _ -> now = 8_000; GpuRenderFixtures.envelope() },
            reserve = { _, _ -> error("Expired plan must not reserve") })
        val delegate = GpuRenderDelegate.forTesting(context, configured, previewBudget = budget)
        assertEquals(0, withContext(Dispatchers.IO) { delegate.begin(GpuRenderFixtures.request(context)) })
        assertEquals("PREVIEW_BUDGET_EXHAUSTED", JSONObject(delegate.poll(91)).getString("code"))
        assertTrue(delegate.awaitSafeTermination())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, context.starts); assertEquals(0, closes.get())
    }

}
