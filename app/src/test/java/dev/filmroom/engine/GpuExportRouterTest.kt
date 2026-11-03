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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GpuExportRouterTest {
    private class FakeContext(base: Context) : ContextWrapper(base) {
        var starts = 0
        val connections = mutableListOf<ServiceConnection>()
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent): ComponentName? { starts++; return service.component }
        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean { connections.add(connection); return false }
        override fun stopService(service: Intent): Boolean = true
        override fun unbindService(connection: ServiceConnection) = Unit
    }
    private fun hooks(operation: GpuRenderOperation, closes: AtomicInteger) = GpuRenderTestHooks(
        plan = { _, token -> when (operation) {
            GpuRenderOperation.CAMERA_DIFFUSION -> GpuRenderFixtures.envelope()
            GpuRenderOperation.PRINT_SCAN -> GpuPrintScanFixtures.envelope(GpuPrintScanFixtures.plan(token))
            GpuRenderOperation.FILM_OPTICS -> GpuFilmOpticsFixtures.envelope(GpuFilmOpticsFixtures.plan(token))
            GpuRenderOperation.FILM_EXPOSURE -> GpuFilmExposureFixtures.envelope(GpuFilmExposureFixtures.plan(token))
            GpuRenderOperation.FILM_DIR -> GpuFilmDirFixtures.envelope(GpuFilmDirFixtures.plan(token))
            else -> GpuScienceFixtures.envelope(operation,token)
        } },
        reserve = { _, _ -> object : RetainedNativeWork {
            override fun close() { closes.incrementAndGet() }
            override suspend fun <T> run(memory: () -> NativeMemorySnapshot, work: suspend () -> T): T = error("No nested worker")
        } },
    )
    private fun pipelineRequests(context: Context) = linkedMapOf(
        GpuRenderOperation.FILM_EXPOSURE to (95L to GpuFilmExposureFixtures.request(context)),
        GpuRenderOperation.CAMERA_DIFFUSION to (91L to GpuRenderFixtures.request(context)),
        GpuRenderOperation.FILM_OPTICS to (94L to GpuFilmOpticsFixtures.request(context)),
        GpuRenderOperation.FILM_DIR to (96L to GpuFilmDirFixtures.request(context)),
        GpuRenderOperation.PRINT_SCAN to (92L to GpuPrintScanFixtures.request(context)),
    ).apply {
        for(operation in GpuScienceFixtures.files.keys){val token=100+operation.ordinal.toLong();put(operation,token to GpuScienceFixtures.request(context,operation,token))}
    }
    @Test fun sequentialStagesUseIndependentOneShotOwnersAndStaleCameraCallbackCannotTouchScanner() = runBlocking {
        val context = FakeContext(RuntimeEnvironment.getApplication())
        val camera = AtomicInteger(); val scanner = AtomicInteger()
        val router = GpuExportRouter.forTesting(context, mapOf(
            GpuRenderOperation.CAMERA_DIFFUSION to hooks(GpuRenderOperation.CAMERA_DIFFUSION, camera),
            GpuRenderOperation.PRINT_SCAN to hooks(GpuRenderOperation.PRINT_SCAN, scanner)))
        try {
            assertEquals(1, withContext(Dispatchers.IO) { router.begin(GpuRenderFixtures.request(context)) })
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("not_started", JSONObject(router.poll(91)).getString("status"))
            assertEquals(1, camera.get())
            val old = context.connections.single()
            assertEquals(1, withContext(Dispatchers.IO) { router.begin(GpuPrintScanFixtures.request(context)) })
            old.onServiceConnected(ComponentName(context, GpuRenderService::class.java), Binder())
            router.cancel(91)
            assertEquals("pending", JSONObject(router.poll(92)).getString("status"))
            assertEquals(0, scanner.get())
            router.cancel(92)
            assertEquals("cancelled_stopped", JSONObject(router.poll(92)).getString("status"))
            assertEquals(1, scanner.get())
            assertEquals(0, withContext(Dispatchers.IO) { router.begin(GpuPrintScanFixtures.request(context, 93)) })
            assertEquals(0, withContext(Dispatchers.IO) { router.begin(GpuRenderFixtures.request(context)) })
        } finally { router.awaitSafeTermination() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, context.starts); assertEquals(1, camera.get()); assertEquals(1, scanner.get())
    }
    @Test fun allRegisteredStagesEachGetOneIndependentSequentialLease() = runBlocking {
        val context = FakeContext(RuntimeEnvironment.getApplication())
        val closes = GpuRenderOperation.entries.associateWith { AtomicInteger() }
        val router = GpuExportRouter.forTesting(context, closes.mapValues { (operation, count) -> hooks(operation, count) })
        try {
            for ((token, request) in pipelineRequests(context).values) {
                assertEquals(1, withContext(Dispatchers.IO) { router.begin(request) })
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals("not_started", JSONObject(router.poll(token)).getString("status"))
            }
        } finally { router.awaitSafeTermination() }
        assertEquals(GpuRenderOperation.entries.size, context.starts)
        closes.values.forEach { assertEquals(1, it.get()) }
    }
    @Test fun eachStageCanDeclineMemoryWithoutBorrowingOrBlockingLaterStageLeases() = runBlocking {
        for (denied in GpuRenderOperation.entries) {
            val context = FakeContext(RuntimeEnvironment.getApplication())
            val closes = GpuRenderOperation.entries.associateWith { AtomicInteger() }
            val configured = closes.mapValues { (operation, count) ->
                val normal = hooks(operation, count)
                if (operation == denied) normal.copy(reserve = { _, _ -> throw NativeWorkUnavailable() }) else normal
            }
            val router = GpuExportRouter.forTesting(context, configured)
            try {
                for ((operation, owner) in pipelineRequests(context)) {
                    val (token, request) = owner
                    assertEquals(if (operation == denied) 0 else 1, withContext(Dispatchers.IO) { router.begin(request) })
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    val poll = JSONObject(router.poll(token))
                    assertEquals("not_started", poll.getString("status"))
                    if (operation == denied) assertEquals("MEMORY_UNAVAILABLE", poll.getString("code"))
                }
            } finally { router.awaitSafeTermination() }
            closes.forEach { (operation, count) -> assertEquals(if (operation == denied) 0 else 1, count.get()) }
            assertEquals(GpuRenderOperation.entries.size-1, context.starts)
        }
    }
    @Test fun cancellationAtAnyStageDrainsCurrentOwnerAndClosesAllFutureRoutes() = runBlocking {
        for (cancelled in GpuRenderOperation.entries) {
            val context = FakeContext(RuntimeEnvironment.getApplication())
            val closes = GpuRenderOperation.entries.associateWith { AtomicInteger() }
            val router = GpuExportRouter.forTesting(context, closes.mapValues { (operation, count) -> hooks(operation, count) })
            val requests = pipelineRequests(context)
            val started = mutableSetOf<GpuRenderOperation>()
            try {
                for ((operation, owner) in requests) {
                    val (token, request) = owner
                    assertEquals(1, withContext(Dispatchers.IO) { router.begin(request) }); started.add(operation)
                    if (operation == cancelled) {
                        router.cancel(token)
                        assertEquals("cancelled_stopped", JSONObject(router.poll(token)).getString("status"))
                        break
                    }
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                }
            } finally { router.awaitSafeTermination() }
            requests.values.forEach { (_, request) -> assertEquals(0, withContext(Dispatchers.IO) { router.begin(request) }) }
            closes.forEach { (operation, count) -> assertEquals(if (operation in started) 1 else 0, count.get()) }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
    @Test fun noOverlapEvenWithDifferentOperationAndToken() = runBlocking {
        val context = FakeContext(RuntimeEnvironment.getApplication())
        val camera = AtomicInteger(); val scanner = AtomicInteger()
        val router = GpuExportRouter.forTesting(context, mapOf(
            GpuRenderOperation.CAMERA_DIFFUSION to hooks(GpuRenderOperation.CAMERA_DIFFUSION, camera),
            GpuRenderOperation.PRINT_SCAN to hooks(GpuRenderOperation.PRINT_SCAN, scanner)))
        try {
            assertEquals(1, withContext(Dispatchers.IO) { router.begin(GpuRenderFixtures.request(context)) })
            assertEquals(0, withContext(Dispatchers.IO) { router.begin(GpuPrintScanFixtures.request(context)) })
            assertEquals("GPU_BUSY", JSONObject(router.poll(92)).getString("code"))
            assertEquals(0, camera.get()); assertEquals(0, scanner.get()); assertEquals(0, context.starts)
        } finally { router.awaitSafeTermination() }
        assertEquals(1, camera.get()); assertEquals(0, scanner.get())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun disabledUnknownDuplicateTokensAndClosedRouterNeverStartAnotherOwner() = runBlocking {
        val context = FakeContext(RuntimeEnvironment.getApplication()); val closes = AtomicInteger()
        val router = GpuExportRouter.forTesting(context, mapOf(GpuRenderOperation.PRINT_SCAN to hooks(GpuRenderOperation.PRINT_SCAN, closes)))
        try {
            assertEquals(0, withContext(Dispatchers.IO) { router.begin(GpuRenderFixtures.request(context)) })
            val scanner = GpuPrintScanFixtures.request(context)
            assertEquals(0, withContext(Dispatchers.IO) { router.begin(JSONObject(scanner).put("operation", "driverMethod").toString()) })
            assertEquals(1, withContext(Dispatchers.IO) { router.begin(scanner) })
            assertEquals(0, withContext(Dispatchers.IO) { router.begin(scanner) })
            router.cancel(91)
            assertEquals("pending", JSONObject(router.poll(92)).getString("status"))
        } finally { router.awaitSafeTermination() }
        assertEquals(1, closes.get())
        assertEquals(0, withContext(Dispatchers.IO) { router.begin(GpuPrintScanFixtures.request(context, 93)) })
        assertEquals(0L, withContext(Dispatchers.IO) { router.availableDiskBytes() })
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, context.starts)
    }
    @Test fun thrownAfterSessionPublicationStillDrainsThroughExportRouterFinally() = runBlocking {
        val context = FakeContext(RuntimeEnvironment.getApplication()); val closes = AtomicInteger()
        val router = GpuExportRouter.forTesting(context, mapOf(GpuRenderOperation.PRINT_SCAN to
            hooks(GpuRenderOperation.PRINT_SCAN, closes).copy(afterSession = { throw AssertionError("simulated JNI failure") })))
        try {
            assertTrue(runCatching { withContext(Dispatchers.IO) { router.begin(GpuPrintScanFixtures.request(context)) } }.exceptionOrNull() is AssertionError)
            assertEquals(0, closes.get()); assertEquals("pending", JSONObject(router.poll(92)).getString("status"))
        } finally { router.awaitSafeTermination() }
        assertEquals(1, closes.get()); assertEquals("cancelled_stopped", JSONObject(router.poll(92)).getString("status"))
        Shadows.shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, context.starts)
    }
    @Test fun previewBudgetIsSharedAndExpiredStagesNeverPlanOrReserve() = runBlocking {
        val context = FakeContext(RuntimeEnvironment.getApplication())
        var now = 10L
        val budget = PreviewGpuBudget(8_000) { now }
        val camera = AtomicInteger(); val scanner = AtomicInteger()
        val router = GpuExportRouter.forTesting(context, mapOf(
            GpuRenderOperation.CAMERA_DIFFUSION to hooks(GpuRenderOperation.CAMERA_DIFFUSION, camera),
            GpuRenderOperation.PRINT_SCAN to hooks(GpuRenderOperation.PRINT_SCAN, scanner).copy(plan = { _, _ -> error("Expired stage must not plan") })), budget)
        now = 99_000 // Construction does not spend the opportunity window.
        try {
            assertEquals(1, withContext(Dispatchers.IO) { router.begin(GpuRenderFixtures.request(context)) })
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, camera.get())
            now += 8_000
            assertEquals(0, withContext(Dispatchers.IO) { router.begin(GpuPrintScanFixtures.request(context)) })
            val poll = JSONObject(router.poll(92))
            assertEquals("not_started", poll.getString("status"))
            assertEquals("PREVIEW_BUDGET_EXHAUSTED", poll.getString("code"))
            assertEquals(0, scanner.get()); assertEquals(1, context.starts)
        } finally { router.awaitSafeTermination() }
    }
    @Test fun laterPreviewStageReceivesOnlyRemainingBudget() = runBlocking {
        val context = FakeContext(RuntimeEnvironment.getApplication())
        var now = 100L
        val budget = PreviewGpuBudget(8_000) { now }
        val camera = AtomicInteger(); val scanner = AtomicInteger()
        val router = GpuExportRouter.forTesting(context, mapOf(
            GpuRenderOperation.CAMERA_DIFFUSION to hooks(GpuRenderOperation.CAMERA_DIFFUSION, camera),
            GpuRenderOperation.PRINT_SCAN to hooks(GpuRenderOperation.PRINT_SCAN, scanner)), budget)
        try {
            assertEquals(1, withContext(Dispatchers.IO) { router.begin(GpuRenderFixtures.request(context)) })
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            now += 7_900
            assertEquals(1, withContext(Dispatchers.IO) { router.begin(GpuPrintScanFixtures.request(context)) })
            assertEquals("pending", JSONObject(router.poll(92)).getString("status"))
            now += 100
            val poll = JSONObject(router.poll(92))
            assertEquals("not_started", poll.getString("status"))
            assertEquals("PREVIEW_BUDGET_EXHAUSTED", poll.getString("code"))
            assertEquals(1, scanner.get())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, context.starts) // Stage two expired before main could launch it.
        } finally { router.awaitSafeTermination() }
    }
    @Test fun defaultExportRouterHasNoPreviewBudget() = runBlocking {
        val context = FakeContext(RuntimeEnvironment.getApplication()); val closes = AtomicInteger()
        val router = GpuExportRouter.forTesting(context, mapOf(GpuRenderOperation.CAMERA_DIFFUSION to hooks(GpuRenderOperation.CAMERA_DIFFUSION, closes)))
        try {
            assertEquals(1, withContext(Dispatchers.IO) { router.begin(GpuRenderFixtures.request(context)) })
            android.os.SystemClock.sleep(8_001)
            assertEquals("pending", JSONObject(router.poll(91)).getString("status"))
            assertEquals(0, closes.get())
        } finally { router.awaitSafeTermination() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

}
