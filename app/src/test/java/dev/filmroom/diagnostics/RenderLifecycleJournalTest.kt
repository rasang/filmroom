package dev.filmroom.diagnostics

import dev.filmroom.engine.GpuRenderCode
import dev.filmroom.engine.GpuRenderOperation
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RenderLifecycleJournalTest {
    private val folders = mutableListOf<File>()
    private val now = 1_800_000_000_000L
    private fun folder() = Files.createTempDirectory("render-lifecycle-test-").toFile().also { folders += it }
    private fun event(sequence: Long = 1, token: Long = 10, run: String = "a".repeat(32),
        phase: GpuLifecyclePhase = GpuLifecyclePhase.JNI_ENTER, scope: RenderLifecycleScope = RenderLifecycleScope.PREVIEW) =
        RenderLifecycleEvent(now, 500, run, 123, sequence, token, scope, null, phase)
    @After fun cleanup() { folders.forEach { it.deleteRecursively() } }

    @Test fun trustedFilesRootAliasSupportsNewJournalAndSnapshot() {
        val root = folder()
        val files = File(root, "real-files").apply { mkdirs() }
        val alias = File(root, "platform-files-alias")
        Files.createSymbolicLink(alias.toPath(), files.toPath())
        val directory = RenderLifecycleJournal.directoryUnder(alias)
        assertEquals(File(files.canonicalFile, RenderLifecycleJournal.DIRECTORY), directory)
        assertFalse(directory.exists())
        RenderLifecycleJournal(directory).use { journal ->
            assertTrue(journal.append(event()))
            val snapshot = RenderLifecycleJournal.snapshot(RenderLifecycleJournal.directoryUnder(alias), 5, now)
            assertTrue(snapshot.storageHealthy)
            assertEquals(0, snapshot.unavailableSlots)
            assertEquals(listOf(event()), snapshot.events)
            assertTrue(snapshot.inactiveRuns.isEmpty())
        }
    }

    @Test fun trustedParentAliasSharesExistingJournalAndWriterLeaseIdentity() {
        val root = folder()
        val realParent = File(root, "real-app").apply { mkdirs() }
        val files = File(realParent, "files").apply { mkdirs() }
        val aliasParent = File(root, "platform-app-alias")
        Files.createSymbolicLink(aliasParent.toPath(), realParent.toPath())
        val aliasFiles = File(aliasParent, "files")
        val canonicalDirectory = RenderLifecycleJournal.directoryUnder(files)
        val aliasDirectory = RenderLifecycleJournal.directoryUnder(aliasFiles)
        assertEquals(canonicalDirectory, aliasDirectory)
        RenderLifecycleJournal(canonicalDirectory).use { parent ->
            assertTrue(parent.append(event()))
            RenderLifecycleJournal(aliasDirectory).use { second ->
                assertNotEquals(parent.slot, second.slot)
                assertTrue(second.append(event(run = "b".repeat(32))))
            }
            repeat(25) {
                val directory = if (it % 2 == 0) aliasDirectory else canonicalDirectory
                val snapshot = RenderLifecycleJournal.snapshot(directory, 5, now)
                assertTrue(snapshot.storageHealthy)
                assertEquals(2, snapshot.events.size)
                assertFalse(snapshot.inactiveRuns.contains("a".repeat(32)))
            }
            // A second fd for an alias of the same POSIX lock can release the
            // original lease on close. Verify ownership from another process.
            val classpath = listOf(RenderLifecycleCrashWriter::class.java, RenderLifecycleJournal::class.java, kotlin.Unit::class.java)
                .map { File(checkNotNull(it.protectionDomain?.codeSource).location.toURI()).path }.distinct().joinToString(File.pathSeparator)
            val java = File(System.getProperty("java.home"), "bin/java").path
            val child = ProcessBuilder(java, "-cp", classpath, RenderLifecycleCrashWriter::class.java.name,
                aliasDirectory.path, parent.slot.toString()).redirectErrorStream(true).start()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(child.inputStream.bufferedReader().readText(), 29, child.exitValue())
            assertTrue(parent.append(event(2)))
        }
    }

    @Test fun canonicalizingTrustedRootDoesNotFollowJournalLeafSymlink() {
        val root = folder()
        val files = File(root, "real-files").apply { mkdirs() }
        val alias = File(root, "platform-files-alias")
        Files.createSymbolicLink(alias.toPath(), files.toPath())
        val target = File(root, "outside-journal").apply { mkdirs() }
        Files.createSymbolicLink(File(files, RenderLifecycleJournal.DIRECTORY).toPath(), target.toPath())
        val directory = RenderLifecycleJournal.directoryUnder(alias)
        assertTrue(Files.isSymbolicLink(directory.toPath()))
        assertTrue(runCatching { RenderLifecycleJournal(directory).use { } }.isFailure)
        val snapshot = RenderLifecycleJournal.snapshot(directory, 5, now)
        assertFalse(snapshot.storageHealthy)
        assertEquals(RenderLifecycleJournal.SLOT_COUNT, snapshot.unavailableSlots)
        assertTrue(snapshot.events.isEmpty())
        assertTrue(target.listFiles().orEmpty().isEmpty())
    }

    @Test fun trustedRootResolutionKeepsSlotFileSymlinkProtection() {
        for (name in listOf("slot-0.0.log", "slot-0.1.log", "slot-0.crumb", "slot-0.owner")) {
            val files = folder()
            val directory = RenderLifecycleJournal.directoryUnder(files).apply { mkdirs() }
            val target = File(files, "outside-sentinel").apply { writeText("private destination must not change") }
            Files.createSymbolicLink(File(directory, name).toPath(), target.toPath())
            val allPersisted = runCatching {
                RenderLifecycleJournal(directory, segmentBytes = 1024).use { journal ->
                    // Reach rotation as well, to exercise the previous-segment guard.
                    (1L..32L).all { journal.append(event(it)) }
                }
            }.getOrDefault(false)
            assertFalse(name, allPersisted)
            assertEquals(name, "private destination must not change", target.readText())
            val snapshot = RenderLifecycleJournal.snapshot(directory, 5, now)
            assertFalse(name, snapshot.text().contains("private destination"))
        }
    }

    @Test fun trustedRootResolutionSkipsSymlinkedLockSlot() {
        val files = folder()
        val directory = RenderLifecycleJournal.directoryUnder(files).apply { mkdirs() }
        val target = File(files, "outside-sentinel").apply { writeText("private lock target") }
        Files.createSymbolicLink(File(directory, "slot-0.lock").toPath(), target.toPath())
        RenderLifecycleJournal(directory).use { journal ->
            assertNotEquals(0, journal.slot)
            assertTrue(journal.append(event()))
        }
        assertEquals("private lock target", target.readText())
    }

    @Test fun strictEnumsNumbersCrcAndNativeSamplesRoundTripWithoutFreeText() {
        val base = event().copy(operation = GpuRenderOperation.FILM_GRAIN, code = GpuRenderCode.TIMED_OUT, parentToken = 99)
        assertEquals(base, RenderLifecycleEvent.parse(base.line()))
        val native = base.copy(phase = GpuLifecyclePhase.NATIVE_PHASE, native = NativeGpuLifecycle(24,
            NativeGpuPhase.PIPELINE, NativeGpuPhaseState.STARTED, 15, 24, NativeGpuTerminal.ACTIVE,
            NativeGpuDiagnosticCode.AVAILABLE, NativeGpuActivity.SYNTHETIC_ADMISSION, NativeGpuStep.SHADER_MODULE))
        assertEquals(native, RenderLifecycleEvent.parse(native.line()))
        assertNull(RenderLifecycleEvent.parse(native.line().replace("PIPELINE", "content://private-photo")))
        assertNull(RenderLifecycleEvent.parse(base.line().replace("TIMED_OUT", "NATIVE_FAILED")))
        val poisoned = base.line().substringBeforeLast('\t').split('\t').toMutableList()
        poisoned[9] = "private/path token=secret"
        val body = poisoned.joinToString("\t")
        assertNull(RenderLifecycleEvent.parse("$body\t${CRC32().apply { update(body.toByteArray()) }.value.toString(16)}\n"))
        assertFalse(RenderLifecycleSnapshot(listOf(native)).text().contains("private"))
        assertTrue(native.json().contains("\"parentToken\":99"))
    }

    @Test fun processSlotAndTokenScopeKeepDistinctOperationsSeparate() {
        val directory = folder()
        RenderLifecycleJournal(directory).use { first ->
            RenderLifecycleJournal(directory).use { second ->
                assertNotEquals(first.slot, second.slot)
                assertTrue(first.append(event(scope = RenderLifecycleScope.PREVIEW)))
                assertTrue(first.append(event(2, scope = RenderLifecycleScope.DETAIL)))
                assertTrue(second.append(event(run = "b".repeat(32), scope = RenderLifecycleScope.GPU_STAGE)))
                val live = RenderLifecycleJournal.snapshot(directory, 5, now)
                assertEquals(3, live.events.size); assertEquals(0, live.unfinishedInactiveTokens)
            }
            val oneExited = RenderLifecycleJournal.snapshot(directory, 5, now)
            assertEquals(1, oneExited.unfinishedInactiveTokens)
            assertFalse(oneExited.inactiveRuns.contains("a".repeat(32)))
        }
        assertEquals(3, RenderLifecycleJournal.snapshot(directory, 5, now).unfinishedInactiveTokens)
    }

    @Test fun tornOversizedAndMalformedSuffixCannotLeakOrPoisonNextProcess() {
        val directory = folder()
        RenderLifecycleJournal(directory).use { assertTrue(it.append(event())) }
        val log = File(directory, "slot-0.0.log")
        log.appendText("content://private/photo token=secret\n" + "x".repeat(MAX_LIFECYCLE_LINE_BYTES + 20) + "\npartial_secret")
        RenderLifecycleJournal(directory).use { assertTrue(it.append(event(1, 20, "b".repeat(32)))) }
        val snapshot = RenderLifecycleJournal.snapshot(directory, 5, now)
        assertEquals(2, snapshot.events.size)
        assertTrue(snapshot.malformedDiscarded >= 2)
        assertFalse(snapshot.text().contains("secret")); assertFalse(snapshot.json().contains("private"))
    }

    @Test fun openTokenBreadcrumbSurvivesUnrelatedHistoryRotationAndSampledWaits() {
        val directory = folder()
        RenderLifecycleJournal(directory, segmentBytes = 1024).use { journal ->
            assertTrue(journal.append(event(1, 10, phase = GpuLifecyclePhase.JNI_ENTER)))
            repeat(100) { index -> assertTrue(journal.append(event(index + 2L, 20, phase = GpuLifecyclePhase.STAGE_END))) }
            assertTrue(journal.append(event(102, 10, phase = GpuLifecyclePhase.PROGRESS_WAIT)))
        }
        val snapshot = RenderLifecycleJournal.snapshot(directory, 5, now)
        assertTrue(snapshot.events.any { it.token == 10L && it.phase == GpuLifecyclePhase.JNI_ENTER })
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 2 * 1024 + RenderLifecycleJournal.BREADCRUMB_BYTES + 33)
        assertEquals(1, snapshot.unfinishedInactiveTokens)
    }

    @Test fun tornNewestBreadcrumbBankFallsBackToPriorValidPhase() {
        val directory = folder()
        RenderLifecycleJournal(directory, segmentBytes = 1024).use { journal ->
            assertTrue(journal.append(event(1, phase = GpuLifecyclePhase.NATIVE_ENTER)))
            assertTrue(journal.append(event(2, phase = GpuLifecyclePhase.NATIVE_RETURN)))
            repeat(30) { assertTrue(journal.append(event(it + 3L, 20, phase = GpuLifecyclePhase.STAGE_END))) }
        }
        // First token uses slot 0, second update uses bank 0. Corrupt that bank.
        RandomAccessFile(File(directory, "slot-0.crumb"), "rw").use { it.seek(0); it.write("partial_secret".toByteArray()) }
        val snapshot = RenderLifecycleJournal.snapshot(directory, 5, now)
        assertTrue(snapshot.events.any { it.token == 10L && it.phase == GpuLifecyclePhase.NATIVE_ENTER })
        assertFalse(snapshot.json().contains("secret"))
    }

    @Test fun allSlotsAreBoundedAndNinthWriterCannotCorruptOwners() {
        val directory = folder()
        val writers = (0 until RenderLifecycleJournal.SLOT_COUNT).map { RenderLifecycleJournal(directory, 1024) }
        try {
            assertEquals(RenderLifecycleJournal.SLOT_COUNT, writers.map { it.slot }.toSet().size)
            assertTrue(runCatching { RenderLifecycleJournal(directory) }.isFailure)
            writers.forEachIndexed { index, writer -> assertTrue(writer.append(event(run = index.toString(16).repeat(32)))) }
            val snapshot = RenderLifecycleJournal.snapshot(directory, 5, now)
            assertEquals(RenderLifecycleJournal.SLOT_COUNT, snapshot.events.size)
            assertTrue(snapshot.inactiveRuns.isEmpty())
            assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= RenderLifecycleJournal.MAX_TOTAL_BYTES)
        } finally { writers.forEach { it.close() } }
    }

    @Test fun concurrentWritersAndSnapshotsKeepChecksummedCompleteRecords() {
        val directory = folder()
        val writers = (0..3).map { RenderLifecycleJournal(directory) }
        val pool = Executors.newFixedThreadPool(5)
        try {
            val tasks = writers.mapIndexed { index, writer -> pool.submit {
                repeat(40) { count -> assertTrue(writer.append(event(count + 1L, count % 8L + 1, index.toString(16).repeat(32)))) }
            } } + pool.submit { repeat(50) { RenderLifecycleJournal.snapshot(directory, 5, now) } }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            val snapshot = RenderLifecycleJournal.snapshot(directory, 5, now)
            assertEquals(160, snapshot.events.size); assertEquals(160, snapshot.events.map { it.run to it.sequence }.toSet().size)
            assertTrue(snapshot.events.all { RenderLifecycleEvent.parse(it.line()) == it })
        } finally { pool.shutdownNow(); writers.forEach { it.close() } }
    }

    @Test fun failedSyncNeverAcknowledgesDurabilityAndAsyncQueueFailsFastWhenFull() {
        val directory = folder()
        RenderLifecycleJournal(directory, sync = { throw IOException("private/path secret") }).use { assertFalse(it.append(event())) }
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        RenderLifecycleDispatcher(1).use { dispatcher ->
            val first = dispatcher.submit { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val second = dispatcher.submit { true }
            val before = System.nanoTime()
            assertFalse(dispatcher.submit { true }.get(100, TimeUnit.MILLISECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 100)
            assertFalse(first.isDone); assertFalse(second.isDone)
            release.countDown(); assertTrue(first.get(1, TimeUnit.SECONDS)); assertTrue(second.get(1, TimeUnit.SECONDS))
        }
    }

    @Test fun oneDurabilityTimeoutCannotDelayEveryLaterRenderPhase() {
        val gate = RenderLifecycleDurabilityGate(20)
        val blocked = java.util.concurrent.CompletableFuture<Boolean>()
        assertFalse(gate.await(blocked)); assertTrue(gate.suspended)
        val before = System.nanoTime()
        repeat(100) { assertFalse(gate.await(blocked)) }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 100)
        blocked.complete(true)
        assertFalse(gate.await(blocked)) // No implicit return to repeated blocking.
    }

    @Test fun nativeTerminalAndFailureChangesSurviveSameSequenceDeduplication() {
        val active = NativeGpuLifecycle(1, NativeGpuPhase.READBACK, NativeGpuPhaseState.ENDED,
            10, 20, NativeGpuTerminal.ACTIVE, NativeGpuDiagnosticCode.AVAILABLE)
        assertFalse(shouldRecordNativeSample(active, active.copy(elapsedMs = 100, totalElapsedMs = 200)))
        assertTrue(shouldRecordNativeSample(active, active.copy(terminal = NativeGpuTerminal.SUCCESS)))
        assertTrue(shouldRecordNativeSample(active, active.copy(diagnosticCode = NativeGpuDiagnosticCode.CALLBACK_UNAVAILABLE)))
        assertTrue(shouldRecordNativeSample(active, active.copy(failureCode = 229)))
        assertFalse(shouldRecordNativeSample(active, active.copy(sequence = 0)))
    }

    @Test fun completeMemoryAndCorrelationFieldsRemainBoundedTypedAndRoundTrip() {
        val memory = RenderMemoryInfo(1L shl 40, 1L shl 40, 1L shl 40, 1L shl 40, 4096, 65536,
            RenderMemoryWaitReason.WORKERS_AND_BYTES)
        val expected = event().copy(operationId = Long.MAX_VALUE, variant = RenderLifecycleVariant.REGION,
            parentToken = Long.MAX_VALUE, memory = memory)
        assertTrue(expected.line().toByteArray().size <= MAX_LIFECYCLE_LINE_BYTES)
        assertEquals(expected, RenderLifecycleEvent.parse(expected.line()))
        assertTrue(expected.json().contains("\"requestedBytes\":1099511627776"))
        assertTrue(expected.json().contains("\"operationId\":${Long.MAX_VALUE}"))
        assertFalse(memory.copy(requestedBytes = -1).valid())
        assertFalse(memory.copy(reservedBytes = Long.MAX_VALUE).valid())
        assertFalse(memory.copy(activeWorkers = 4097).valid())
    }

    @Test fun liveSnapshotCannotReleaseWriterLeaseForAnotherProcess() {
        val directory = folder()
        RenderLifecycleJournal(directory).use { parent ->
            assertTrue(parent.append(event()))
            repeat(25) { assertTrue(RenderLifecycleJournal.snapshot(directory, 5, now).inactiveRuns.isEmpty()) }
            val classpath = listOf(RenderLifecycleCrashWriter::class.java, RenderLifecycleJournal::class.java, kotlin.Unit::class.java)
                .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
            val java = File(System.getProperty("java.home"), "bin/java").path
            val child = ProcessBuilder(java, "-cp", classpath, RenderLifecycleCrashWriter::class.java.name,
                directory.path, parent.slot.toString()).redirectErrorStream(true).start()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS))
            assertEquals(child.inputStream.bufferedReader().readText(), 29, child.exitValue())
            assertTrue(parent.append(event(2)))
        }
    }

    @Test fun closedCancellationAndCleanupDoNotBecomeAnUnfinishedOperation() {
        val run = "a".repeat(32)
        val records = listOf(event(1), event(2, phase = GpuLifecyclePhase.CANCEL_REQUEST),
            event(3, phase = GpuLifecyclePhase.OPERATION_CANCELLED), event(4, phase = GpuLifecyclePhase.CLEANUP_END))
        assertEquals(0, RenderLifecycleSnapshot(records, setOf(run)).unfinishedInactiveTokens)
        assertEquals(1, RenderLifecycleSnapshot(records.take(2), setOf(run)).unfinishedInactiveTokens)
    }

    @Test fun hostAppendTimingIsMeasuredWithoutMakingADeviceLatencyClaim() {
        val directory = folder()
        val samples = ArrayList<Long>()
        RenderLifecycleJournal(directory).use { journal ->
            repeat(110) { index ->
                val start = System.nanoTime()
                assertTrue(journal.append(event(index + 1L)))
                if (index >= 10) samples += TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start)
            }
        }
        samples.sort()
        println("render_lifecycle_host_append_fsync_us: samples=${samples.size} p50=${samples[50]} p95=${samples[95]} max=${samples.last()}; host_only_not_phone_latency")
    }

    @Test fun acknowledgedPhaseSurvivesAbruptProcessHaltWithoutNormalClose() {
        val directory = folder()
        val classpath = listOf(RenderLifecycleCrashWriter::class.java, RenderLifecycleJournal::class.java, kotlin.Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").path
        val child = ProcessBuilder(java, "-cp", classpath, RenderLifecycleCrashWriter::class.java.name, directory.path).redirectErrorStream(true).start()
        assertTrue(child.waitFor(10, TimeUnit.SECONDS))
        assertEquals(child.inputStream.bufferedReader().readText(), 29, child.exitValue())
        val recovered = RenderLifecycleJournal.snapshot(directory, 5, now)
        assertEquals(GpuLifecyclePhase.NATIVE_ENTER, recovered.events.single().phase)
        assertEquals(1, recovered.unfinishedInactiveTokens)
        RenderLifecycleJournal(directory).use { assertTrue(it.append(event(1, 20, "b".repeat(32)))) }
        assertEquals(2, RenderLifecycleJournal.snapshot(directory, 5, now).events.size)
    }
}

/** Separate JVM deliberately omits close/finally/shutdown hooks. */
object RenderLifecycleCrashWriter {
    @JvmStatic fun main(args: Array<String>) {
        val journal = RenderLifecycleJournal(File(args.first()))
        args.getOrNull(1)?.toInt()?.let { check(journal.slot != it) }
        check(journal.append(RenderLifecycleEvent(1_800_000_000_000, 500, "a".repeat(32), 123, 1, 10,
            RenderLifecycleScope.GPU_STAGE, GpuRenderOperation.FILM_GRAIN, GpuLifecyclePhase.NATIVE_ENTER)))
        Runtime.getRuntime().halt(29)
    }
}
