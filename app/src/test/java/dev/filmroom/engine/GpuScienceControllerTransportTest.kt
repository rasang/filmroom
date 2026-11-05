package dev.filmroom.engine

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

/** Exercises the real controller, lease, lifetime and parsers, not a duplicate state model.
 * Callbacks here are downstream of Binder authentication; these tests do not prove UID/Binder security. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GpuScienceControllerTransportTest {
    private val operation = GpuRenderOperation.AUTO_EXPOSURE

    private class FakeTransport(
        val message: (Int, String) -> Unit,
        val death: () -> Unit,
        val failure: (Boolean) -> Unit,
    ) : GpuScienceTransport {
        val sent = mutableListOf<Pair<Int, JSONObject>>()
        var starts = 0
        var closes = 0
        var detaches = 0
        var rejectRun = false
        var throwAfterRun = false
        var onStart: () -> Unit = {}
        var onSend: (Int, JSONObject) -> Unit = { _, _ -> }
        override fun start() { starts++; onStart() }
        override fun send(what: Int, json: String): Boolean {
            val packet = JSONObject(json)
            sent += what to packet
            onSend(what, packet)
            if (throwAfterRun && what == GpuScienceIpc.RUN_STAGE) error("Lost reply after RUN publication")
            return !(rejectRun && what == GpuScienceIpc.RUN_STAGE)
        }
        override fun requestClose() { closes++ }
        override fun checkDeath() = Unit
        override fun detach() { detaches++ }
        fun emit(what: Int, packet: JSONObject) = message(what, packet.toString())
        fun count(what: Int) = sent.count { it.first == what }
    }

    private inner class Harness(val epoch: Long = 701L, val stageOperation: GpuRenderOperation = operation, fixedBytes: Long = 128L * 1024 * 1024, prepaidBytes: Long = 768L * 1024 * 1024) {
        val context: Context = RuntimeEnvironment.getApplication()
        val budget = GpuScienceSessionBudget(prepaidBytes, fixedBytes, 64)
        val releases = AtomicInteger()
        val refunds = AtomicInteger()
        val quarantines = AtomicInteger()
        val memoryChecks = AtomicInteger()
        lateinit var transport: FakeTransport
        val controller: GpuScienceSessionController
        init {
            ShadowStatFs.registerStats(context.cacheDir.path, 4096, 1_000_000, 1_000_000)
            controller = GpuScienceSessionController(context, epoch, budget,
                leaseFactory = { _, b, life, _ ->
                    GpuScienceSessionLease.forTesting(b, life, object : RetainedNativeWork {
                        override fun close() { refunds.incrementAndGet() }
                        override fun quarantine() { quarantines.incrementAndGet() }
                        override suspend fun <T> run(memory: () -> NativeMemorySnapshot, work: suspend () -> T): T =
                            error("No native work in controller transport tests")
                    })
                },
                released = { releases.incrementAndGet(); GpuScienceSessionPool.releaseOwner(it) },
                transportFactory = { _, _, receive, died, failed ->
                    FakeTransport(receive, died, failed).also { transport = it }
                },
                nativePlan = { if (it.operation == GpuRenderOperation.CAMERA_DIFFUSION) GpuRenderFixtures.envelope(GpuStreamingFixtures.nearCapCamera(it.token)) else if (it.operation in GpuStreamingProtocol.operations) GpuStreamingFixtures.envelope(GpuStreamingFixtures.plan(it.operation, it.token)) else GpuScienceFixtures.envelope(it.operation, it.token) },
                recheckMemory = { memoryChecks.incrementAndGet(); Unit },
                // An expired drain is deterministic. Production retains its 5000 ms deadline.
                drainTimeoutMillis = 0L,
            )
            transport.onSend = { what, packet ->
                when (what) {
                    GpuScienceIpc.OPEN -> transport.emit(GpuScienceIpc.OPENED, control())
                    GpuScienceIpc.BEGIN_FRAME -> transport.emit(GpuScienceIpc.FRAME_OPENED,
                        control().put("frameGeneration", packet.getLong("frameGeneration")))
                    GpuScienceIpc.FINISH_FRAME -> transport.emit(GpuScienceIpc.FRAME_SEALED,
                        control().put("frameGeneration", packet.getLong("frameGeneration")).put("session", stats()))
                }
            }
        }
        fun control() = GpuScienceSessionProtocol.control(epoch)
        fun stats(charged: Long = budget.fixedBytes) = JSONObject().put("epoch", epoch)
            .put("prepaidBytes", budget.prepaidBytes).put("fixedBytes", budget.fixedBytes)
            .put("chargedBytes", charged).put("pipelineSlots", 0L).put("poolBytes", 0L)
            .put("pipelineHits", 0L).put("bufferHits", 0L).put("clearSubmissions", 0L)
        fun frame(generation: Long = 1L) = GpuScienceFrameDelegate(context, setOf(stageOperation), generation, null, true)
            .also { it.controller = controller }
        fun open(frame: GpuScienceFrameDelegate, published: Boolean = false) = runBlocking {
            if (!published) assertTrue(controller.claimStartupOwner(frame))
            controller.start()
            transport.emit(GpuScienceIpc.READY, control())
            assertTrue(controller.awaitReady())
            assertTrue(controller.beginFrame(frame))
        }
        fun begin(frame: GpuScienceFrameDelegate, token: Long = 901L): JSONObject =
            JSONObject(controller.begin(frame, request(token)))
        fun request(token: Long = 901L): String {
            if (stageOperation == GpuRenderOperation.CAMERA_DIFFUSION) {
                val p = GpuRenderProtocol.parsePlan(GpuRenderFixtures.envelope(GpuStreamingFixtures.nearCapCamera(token)), token)
                val dir = java.io.File(context.cacheDir, "gpu-render/gpu-stage-$token").apply { mkdirs() }
                return JSONObject().put("schemaVersion", 1).put("token", token).put("parentHandle", 11L)
                    .put("jobDir", dir.canonicalPath).put("manifestSha256", p.digest).put("budgetBytes", p.combinedPeakBytes)
                    .put("width", p.width).put("height", p.height).put("totalPixels", 3L * p.width * p.height).toString()
            }
            return if (stageOperation in GpuStreamingProtocol.operations) GpuStreamingFixtures.request(context, stageOperation, token) else GpuScienceFixtures.request(context, stageOperation, token)
        }
        fun seal(frame: GpuScienceFrameDelegate, token: Long = 901L, charged: Long = budget.fixedBytes): JSONObject {
            val record = frame.records.getValue(token)
            val (code, summary) = GpuRenderProtocol.parseStageNativeResult((if (record.plan is GpuStreamingPlan) GpuStreamingFixtures.result(record.plan) else GpuScienceFixtures.result(record.plan)).toString(), record.plan)
            assertEquals(GpuRenderCode.OK, code)
            return control().put("frameGeneration", frame.generation).put("sequence", record.ticket.sequence)
                .put("token", token).put("manifestSha256", record.ticket.manifestSha256).put("operation", stageOperation.wire)
                .put("summary", GpuRenderProtocol.encodeResult(code, summary)).put("session", stats(charged))
        }
        fun assertHeld() { assertFalse(controller.hasStopped()); assertEquals(0, refunds.get()); assertEquals(0, releases.get()); assertEquals(0, transport.detaches) }
        fun assertReleased() { assertTrue(controller.hasStopped()); assertEquals(1, refunds.get()); assertEquals(1, releases.get()); assertEquals(1, transport.detaches) }
    }

    @Test fun definiteStartRejectionRefundsButAmbiguousBindFailureDoesNot() = runBlocking {
        val rejected = Harness()
        rejected.controller.start()
        rejected.transport.failure(true)
        rejected.assertReleased()
        rejected.transport.failure(true)
        rejected.assertReleased()

        val uncertain = Harness(702)
        uncertain.controller.start()
        uncertain.transport.failure(false)
        assertFalse(uncertain.controller.awaitStopped())
        uncertain.assertHeld()
        assertTrue(uncertain.quarantines.get() > 0)
        // Authenticated READY after cancellation does not authorize OPEN, but permits death proof.
        uncertain.transport.emit(GpuScienceIpc.READY, uncertain.control())
        assertEquals(0, uncertain.transport.count(GpuScienceIpc.OPEN))
        uncertain.transport.death()
        uncertain.assertReleased()
    }

    @Test fun deathBeforeReadyIsNotProofButDeathAfterReadyReleases() = runBlocking {
        val early = Harness()
        early.controller.start()
        early.transport.death()
        early.assertHeld()
        early.controller.retire(GpuRenderCode.CANCELLED)
        assertFalse(early.controller.awaitStopped())
        early.assertHeld()
        assertTrue(early.quarantines.get() > 0)

        val ready = Harness(702)
        val frame = ready.frame()
        ready.open(frame)
        assertEquals(1, ready.transport.count(GpuScienceIpc.OPEN))
        ready.transport.death()
        ready.assertReleased()
        ready.transport.emit(GpuScienceIpc.READY, ready.control())
        assertEquals(1, ready.transport.count(GpuScienceIpc.OPEN))
        ready.assertReleased()
    }

    @Test fun acceptedRunWithLostReplyOrFailedSendRetainsParentUntilDeath() = runBlocking {
        for (failedSend in listOf(false, true)) {
            val h = Harness(if (failedSend) 703 else 704)
            val frame = h.frame()
            h.open(frame)
            h.transport.rejectRun = failedSend
            // Caller may lose this response entirely; ticket ownership must already be installed.
            val accepted = h.begin(frame)
            assertTrue(accepted.getBoolean("accepted"))
            assertEquals(1, h.transport.count(GpuScienceIpc.RUN_STAGE))
            assertEquals(1, frame.records.size)
            assertEquals(2, h.memoryChecks.get())
            val cleanup = AtomicInteger()
            frame.whenSafeToRelease { cleanup.incrementAndGet() }
            frame.cancelUncertainSessionStage(901)
            assertEquals("stopping", JSONObject(frame.pollUncertainSessionStage(901)).getString("status"))
            assertFalse(h.controller.awaitStopped())
            assertEquals(0, cleanup.get()); h.assertHeld()
            h.transport.death()
            h.assertReleased(); assertEquals(1, cleanup.get())
            assertEquals(if (failedSend) "failed_stopped" else "cancelled_stopped",
                JSONObject(frame.pollUncertainSessionStage(901)).getString("status"))
            h.transport.death(); frame.retireOwnedSession()
            h.assertReleased(); assertEquals(1, cleanup.get())
        }
    }

    @Test fun delegateExceptionAfterRunReturnsUncertainEnvelopeWithoutLosingOwnership() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val h = Harness()
        val frame = h.frame()
        h.open(frame)
        h.transport.throwAfterRun = true
        val response = frame.beginSessionStage(GpuScienceFixtures.request(h.context, operation, 901))
        assertEquals("{}", response)
        assertEquals(1, h.transport.count(GpuScienceIpc.RUN_STAGE))
        assertEquals(1, frame.records.size)
        val cleanup = AtomicInteger()
        frame.whenSafeToRelease { cleanup.incrementAndGet() }
        assertEquals("stopping", JSONObject(frame.pollUncertainSessionStage(901)).getString("status"))
        assertFalse(h.controller.awaitStopped()); h.assertHeld(); assertEquals(0, cleanup.get())
        h.transport.death(); h.assertReleased(); assertEquals(1, cleanup.get())
        assertEquals("failed_stopped", JSONObject(frame.pollUncertainSessionStage(901)).getString("status"))
    }

    @Test fun cancelRejectsLateValidSealAndQuarantineRefundsOnlyOnceOnDelayedDeath() = runBlocking {
        val h = Harness()
        val frame = h.frame()
        h.open(frame); h.begin(frame)
        val validSeal = h.seal(frame)
        val cleanup = AtomicInteger()
        frame.whenSafeToRelease { cleanup.incrementAndGet() }
        frame.cancelSessionStage(h.epoch, frame.generation, 1, 901)
        h.transport.emit(GpuScienceIpc.STAGE_SEALED, validSeal)
        assertEquals("running", frame.records.getValue(901).status)
        assertNull(frame.records.getValue(901).summary)
        assertFalse(h.controller.awaitStopped())
        assertTrue(h.quarantines.get() > 0); h.assertHeld(); assertEquals(0, cleanup.get())
        h.transport.death(); h.transport.death()
        h.assertReleased(); assertEquals(1, cleanup.get())
        assertEquals("cancelled_dead", frame.records.getValue(901).status)
    }

    @Test fun wrongEpochOrGenerationSealRetiresWithoutPublishingSuccess() {
        for (key in listOf("epoch", "frameGeneration")) {
            val h = Harness()
            val frame = h.frame()
            h.open(frame); h.begin(frame)
            h.transport.emit(GpuScienceIpc.STAGE_SEALED, h.seal(frame).put(key, 9999L))
            assertTrue(h.transport.closes > 0)
            assertEquals("running", frame.records.getValue(901).status)
            assertNull(frame.records.getValue(901).summary)
            h.assertHeld()
            h.transport.death(); h.assertReleased()
            assertEquals(GpuRenderCode.INVALID_RESULT, frame.records.getValue(901).code)
        }
    }

    @Test fun closingOldFrameCannotRetireNewerFrameInSameHelper() = runBlocking {
        val h = Harness()
        val old = h.frame(1)
        h.open(old)
        old.finishFrame()
        assertTrue(old.sealed); assertTrue(h.controller.idle())
        val current = h.frame(2)
        assertTrue(h.controller.beginFrame(current))
        val closeCount = h.transport.closes
        old.retireOwnedSession()
        assertEquals(closeCount, h.transport.closes)
        assertTrue(h.begin(current).getBoolean("accepted"))
        assertEquals(2L, current.records.getValue(901).ticket.frameGeneration)
        current.retireOwnedSession(); h.transport.death(); h.assertReleased()
    }

    @Test fun capacityRolloverIsBeforeRunAndPreservesEarlierSealedRecord() {
        val h = Harness()
        val frame = h.frame()
        h.open(frame); h.begin(frame)
        h.transport.emit(GpuScienceIpc.STAGE_SEALED, h.seal(frame, charged = h.budget.prepaidBytes))
        val first = frame.records.getValue(901)
        assertEquals("success_sealed", first.status)
        val before = h.transport.count(GpuScienceIpc.RUN_STAGE)
        val rollover = runCatching { h.begin(frame, 902) }.exceptionOrNull()
        assertTrue(rollover is GpuScienceCapacityRollover)
        assertEquals(before, h.transport.count(GpuScienceIpc.RUN_STAGE))
        assertFalse(frame.records.containsKey(902)); assertSame(first, frame.records.getValue(901))
        assertEquals("success_sealed", first.status); assertNotNull(first.summary)
        h.assertHeld()
        h.transport.death(); h.assertReleased()
        assertEquals("success_sealed", first.status); assertNotNull(first.summary)
    }

    @Test fun actualBinderTransportIgnoresWrongUidAndRejectsWrongReplyBinder() {
        val context: Context = RuntimeEnvironment.getApplication()
        val delivered = AtomicInteger()
        val failed = AtomicInteger()
        val transport = GpuScienceClientTransport(context, 701,
            { _, _ -> delivered.incrementAndGet() }, { fail("No death expected") },
            { definite -> assertFalse(definite); failed.incrementAndGet() })
        val endpoint = android.os.Messenger(android.os.Handler(android.os.Looper.getMainLooper()))
        val otherEndpoint = android.os.Messenger(android.os.Handler(android.os.Looper.getMainLooper()))
        // Install the endpoint that onServiceConnected would authenticate, without starting a helper.
        GpuScienceClientTransport::class.java.getDeclaredField("remote").apply { isAccessible = true }.set(transport, endpoint)
        val receive = GpuScienceClientTransport::class.java.getDeclaredMethod("receive", android.os.Message::class.java)
            .apply { isAccessible = true }
        fun ready(uid: Int, sender: android.os.Messenger) = android.os.Message.obtain().apply {
            what = GpuScienceIpc.READY
            sendingUid = uid
            replyTo = sender
            data = android.os.Bundle().apply { putString(GpuScienceIpc.JSON, GpuScienceSessionProtocol.control(701).toString()) }
        }
        try {
            receive.invoke(transport, ready(android.os.Process.myUid() + 1, endpoint))
            assertEquals(0, delivered.get()); assertEquals(0, failed.get())
            receive.invoke(transport, ready(android.os.Process.myUid(), otherEndpoint))
            assertEquals(0, delivered.get()); assertEquals(1, failed.get())
            receive.invoke(transport, ready(android.os.Process.myUid(), endpoint))
            assertEquals(1, delivered.get()); assertEquals(1, failed.get())
        } finally { transport.detach() }
    }

    @Test fun streamedFixedReserveRolloverIsPreRunAndHeldUntilConfirmedDeath() {
        for (op in listOf(GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1, GpuRenderOperation.FILM_GRAIN_STREAM_V1, GpuRenderOperation.FINISH_TAIL_STREAM_V1)) {
            val h = Harness(stageOperation = op)
            val frame = h.frame(); h.open(frame)
            val failure = runCatching { h.begin(frame) }.exceptionOrNull()
            assertTrue(failure is GpuScienceCapacityRollover)
            val next = (failure as GpuScienceCapacityRollover).replacementBudget
            assertTrue(next.fixedBytes > h.budget.fixedBytes)
            assertEquals(h.budget.prepaidBytes, next.prepaidBytes)
            assertEquals(0, h.transport.count(GpuScienceIpc.RUN_STAGE)); assertTrue(frame.records.isEmpty())
            h.assertHeld(); h.transport.death(); h.assertReleased()
            val replacement = Harness(epoch = 702, stageOperation = op, fixedBytes = next.fixedBytes)
            val fresh = replacement.frame(); replacement.open(fresh)
            assertTrue(replacement.begin(fresh).getBoolean("accepted"))
            assertEquals(1, replacement.transport.count(GpuScienceIpc.RUN_STAGE))
            fresh.retireOwnedSession(); replacement.transport.death(); replacement.assertReleased()
        }
    }

    @Test fun streamedFreshOverflowRejectsWithoutRunOrRollover() {
        val h = Harness(stageOperation = GpuRenderOperation.FINISH_TAIL_STREAM_V1, prepaidBytes = 512L * 1024 * 1024)
        val frame = h.frame(); h.open(frame)
        val rejected = h.begin(frame)
        assertFalse(rejected.getBoolean("accepted")); assertEquals("MEMORY_UNAVAILABLE", rejected.getString("code"))
        assertEquals(0, h.transport.count(GpuScienceIpc.RUN_STAGE)); assertTrue(frame.records.isEmpty())
        h.assertHeld(); frame.retireOwnedSession(); h.transport.death(); h.assertReleased()
    }

    @Test fun allStreamingStagesRequireRealOuterSealAndRejectLateSuccessAfterCancel() {
        for (op in GpuStreamingProtocol.operations) {
            val p = GpuStreamingFixtures.parsed(op)
            val b = requireNotNull(GpuScienceSessionBudget(768L * 1024 * 1024, 128L * 1024 * 1024, 64).forValidatedStage(p))
            val h = Harness(stageOperation = op, fixedBytes = b.fixedBytes)
            val frame = h.frame(); h.open(frame)
            assertTrue(h.begin(frame).getBoolean("accepted"))
            val seal = h.seal(frame)
            assertEquals("running", frame.records.getValue(901).status)
            assertNull(frame.records.getValue(901).summary)
            frame.cancelSessionStage(h.epoch, frame.generation, 1, 901)
            h.transport.emit(GpuScienceIpc.STAGE_SEALED, seal)
            assertEquals("running", frame.records.getValue(901).status)
            assertNull(frame.records.getValue(901).summary)
            h.assertHeld(); h.transport.death(); h.assertReleased()
            assertEquals("cancelled_dead", frame.records.getValue(901).status)
        }
    }

    @Test fun realPoolRolloverWaitsForDeathBeforeCreatingNextEpochAndIgnoresOldCallbacks() = runBlocking {
        val op = GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1
        val old = Harness(stageOperation = op)
        val frame = old.frame()
        assertTrue(GpuScienceSessionPool.publishNewOwner(frame, old.controller))
        old.open(frame, published = true)
        val pending = runCatching { old.begin(frame) }.exceptionOrNull() as GpuScienceCapacityRollover
        val created = AtomicInteger()
        var replacement: Harness? = null
        val factory: (Long, GpuScienceSessionBudget) -> GpuScienceSessionController = { epoch, admitted ->
            created.incrementAndGet()
            assertTrue(old.controller.hasStopped()); old.assertReleased()
            Harness(epoch, op, admitted.fixedBytes, admitted.prepaidBytes).also { fresh ->
                replacement = fresh
                fresh.transport.onStart = { fresh.transport.emit(GpuScienceIpc.READY, fresh.control()) }
            }.controller
        }
        try {
            assertNull(GpuScienceSessionPool.rollover(frame, old.controller, pending.replacementBudget, factory))
            assertEquals(0, created.get()); assertSame(old.controller, frame.controller); old.assertHeld()
            old.transport.death(); old.assertReleased()
            val controller = requireNotNull(GpuScienceSessionPool.rollover(frame, old.controller, pending.replacementBudget, factory))
            val fresh = requireNotNull(replacement)
            assertEquals(1, created.get()); assertSame(fresh.controller, controller)
            assertNotEquals(old.epoch, fresh.epoch); assertSame(controller, frame.controller)
            assertTrue(fresh.begin(frame).getBoolean("accepted"))
            old.transport.emit(GpuScienceIpc.ERROR, old.control().put("code", "NATIVE_FAILED"))
            old.transport.death()
            assertEquals(0, fresh.transport.closes); fresh.assertHeld()
        } finally {
            frame.retireOwnedSession()
            replacement?.transport?.death() ?: old.transport.death()
        }
    }

    @Test fun realPoolNeverReplacesCancelledFrameEvenAfterDeath() = runBlocking {
        val op = GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1
        val h = Harness(stageOperation = op)
        val frame = h.frame()
        assertTrue(GpuScienceSessionPool.publishNewOwner(frame, h.controller))
        h.open(frame, published = true)
        val pending = runCatching { h.begin(frame) }.exceptionOrNull() as GpuScienceCapacityRollover
        frame.retireOwnedSession(); h.transport.death(); h.assertReleased()
        assertNull(GpuScienceSessionPool.rollover(frame, h.controller, pending.replacementBudget) { _, _ -> error("Cancelled frame created a helper") })
        assertSame(h.controller, frame.controller)
    }

    @Test fun realPoolLowerFixedReserveWaitsForDeathAndKeepsOldEpochImmutable() = runBlocking {
        val op = GpuRenderOperation.CAMERA_DIFFUSION
        val old = Harness(stageOperation = op, fixedBytes = 137L * 1024 * 1024)
        val frame = old.frame()
        assertTrue(GpuScienceSessionPool.publishNewOwner(frame, old.controller))
        old.open(frame, published = true)
        val pending = runCatching { old.begin(frame) }.exceptionOrNull() as GpuScienceCapacityRollover
        assertEquals(128L * 1024 * 1024, pending.replacementBudget.fixedBytes)
        assertEquals(137L * 1024 * 1024, old.controller.budget.fixedBytes)
        val created = AtomicInteger()
        var replacement: Harness? = null
        val factory: (Long, GpuScienceSessionBudget) -> GpuScienceSessionController = { epoch, admitted ->
            created.incrementAndGet(); assertTrue(old.controller.hasStopped())
            Harness(epoch, op, admitted.fixedBytes, admitted.prepaidBytes).also { fresh ->
                replacement = fresh
                fresh.transport.onStart = { fresh.transport.emit(GpuScienceIpc.READY, fresh.control()) }
            }.controller
        }
        try {
            assertNull(GpuScienceSessionPool.rollover(frame, old.controller, pending.replacementBudget, factory))
            assertEquals(0, created.get()); old.assertHeld()
            old.transport.death(); old.assertReleased()
            requireNotNull(GpuScienceSessionPool.rollover(frame, old.controller, pending.replacementBudget, factory))
            assertEquals(1, created.get())
            assertTrue(requireNotNull(replacement).begin(frame).getBoolean("accepted"))
            assertEquals(137L * 1024 * 1024, old.controller.budget.fixedBytes)
        } finally {
            frame.retireOwnedSession(); replacement?.transport?.death() ?: old.transport.death()
        }
    }

    @Test fun cancellationDuringReplacementConstructionPreventsHelperStart() = runBlocking {
        val op = GpuRenderOperation.EXPOSURE_LOG_DIR_STREAM_V1
        val old = Harness(stageOperation = op)
        val frame = old.frame()
        assertTrue(GpuScienceSessionPool.publishNewOwner(frame, old.controller))
        old.open(frame, published = true)
        val pending = runCatching { old.begin(frame) }.exceptionOrNull() as GpuScienceCapacityRollover
        old.transport.death(); old.assertReleased()
        val opened = frame.opened; val finished = frame.finished
        var replacement: Harness? = null
        val next = GpuScienceSessionPool.rollover(frame, old.controller, pending.replacementBudget) { epoch, admitted ->
            Harness(epoch, op, admitted.fixedBytes, admitted.prepaidBytes).also {
                replacement = it
                frame.cancelled = true
            }.controller
        }
        assertNull(next)
        assertNotSame(opened, frame.opened); assertNotSame(finished, frame.finished)
        val fresh = requireNotNull(replacement)
        assertEquals(0, fresh.transport.starts); fresh.assertReleased()
    }

    @Test fun zeroDrainDeadlineCannotEraseConfirmedStopProof() = runBlocking {
        val h = Harness()
        val frame = h.frame(); h.open(frame)
        frame.retireOwnedSession()
        assertFalse(h.controller.awaitStopped())
        h.assertHeld(); assertTrue(h.quarantines.get() > 0)
        h.transport.death(); h.assertReleased()
        val quarantines = h.quarantines.get()
        assertTrue(h.controller.awaitStopped())
        assertTrue(h.controller.awaitStopped())
        assertEquals(quarantines, h.quarantines.get())
        h.assertReleased()
    }

    @Test fun zeroDrainDeadlineAcceptsDefiniteNoStartProof() = runBlocking {
        val h = Harness()
        h.controller.start(); h.transport.failure(true)
        h.assertReleased()
        assertTrue(h.controller.awaitStopped())
        h.assertReleased()
    }

    @Test fun cancelledOrExceptionalDeferredCompletionIsNotAStopProof() = runBlocking {
        for (exceptional in listOf(false, true)) {
            val h = Harness()
            val frame = h.frame(); h.open(frame)
            frame.retireOwnedSession()
            // Fault injection only: production has no cancellation/exceptional writer for this private deferred.
            val field = GpuScienceSessionController::class.java.getDeclaredField("stopped").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val stopped = field.get(h.controller) as kotlinx.coroutines.CompletableDeferred<Unit>
            if (exceptional) assertTrue(stopped.completeExceptionally(IllegalStateException("Synthetic failed completion")))
            else stopped.cancel()
            assertTrue(stopped.isCompleted); assertTrue(stopped.isCancelled)
            assertFalse(h.controller.awaitStopped())
            assertEquals(0, h.refunds.get()); assertEquals(0, h.releases.get()); assertEquals(0, h.transport.detaches)
            assertTrue(h.quarantines.get() > 0)
            h.transport.death(); h.assertReleased()
        }
    }

}
