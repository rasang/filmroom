package dev.filmroom.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DiagnosticStoreTest {
    private val folders = mutableListOf<File>()
    private fun folder(): File = Files.createTempDirectory("filmroom-diagnostics-test-").toFile().also { folders += it }
    private val runA = "a".repeat(32)
    private val runB = "b".repeat(32)
    private class Clock(var wall: Long = 1_800_000_000_000, var elapsed: Long = 1000) : DiagnosticClock {
        override fun wallMs() = wall
        override fun elapsedMs() = elapsed
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }
    @After fun cleanup() { folders.forEach { it.deleteRecursively() } }

    @Test fun minuteBoundaryIsInclusiveAndFutureUpperBoundIsEnforced() {
        val clock = Clock(); val store = DiagnosticStore(folder(), clock, runA)
        val id = store.begin(Operation.IMPORT, SafeInfo(width = 123))
        clock.advance(60_000)
        assertTrue(store.snapshot(1).events.any { it.operationId == id.value && it.operation == Operation.IMPORT })
        clock.advance(1)
        assertFalse(store.snapshot(1).events.any { it.operationId == id.value && it.operation == Operation.IMPORT })
        val snapshot = store.snapshot(1)
        assertTrue(snapshot.effectiveWallTimes.all { it in (snapshot.snapshotWallMs - 60_000)..snapshot.snapshotWallMs })
    }

    @Test fun currentRunUsesMonotonicClockAcrossWallClockRollback() {
        val clock = Clock(); val store = DiagnosticStore(folder(), clock, runA)
        store.begin(Operation.PREVIEW_RENDER, SafeInfo())
        clock.wall -= 3_600_000; clock.elapsed += 10_000
        val snapshot = store.snapshot(1)
        assertTrue(snapshot.events.any { it.operation == Operation.PREVIEW_RENDER })
        assertTrue(snapshot.events.any { it.wallMs > snapshot.snapshotWallMs })
        assertTrue(snapshot.effectiveWallTimes.all { it <= snapshot.snapshotWallMs })
        assertEquals(0, snapshot.crossRunCount)
    }

    @Test fun crossRunWallClockIsExplicitlyUncertainAndFutureRecordsAreExcluded() {
        val directory = folder(); val clock = Clock()
        val first = DiagnosticStore(directory, clock, runA)
        first.begin(Operation.EXPORT_RENDER, SafeInfo())
        clock.advance(10_000); clock.elapsed = 5
        val second = DiagnosticStore(directory, clock, runB)
        assertTrue(second.snapshot(1).crossRunCount > 0)
        assertTrue(second.snapshot(1).recoveredIncomplete)
        clock.wall -= 60_000; clock.elapsed = 1
        val third = DiagnosticStore(directory, clock, "c".repeat(32))
        assertEquals(0, third.snapshot(1).crossRunCount)
        assertTrue(third.snapshot(1).futureDiscarded > 0)
    }

    @Test fun idleSessionDoesNotInventAnUnfinishedRealOperation() {
        val directory = folder(); val clock = Clock()
        DiagnosticStore(directory, clock, runA)
        clock.advance(10)
        assertFalse(DiagnosticStore(directory, clock, runB).snapshot(1).recoveredIncomplete)
    }

    @Test fun oldUnfinishedRunIsNotMisreportedAfterInterveningCleanRun() {
        val directory = folder(); val clock = Clock()
        val first = DiagnosticStore(directory, clock, runA)
        first.begin(Operation.EXPORT_RENDER, SafeInfo())
        clock.advance(100)
        val second = DiagnosticStore(directory, clock, runB)
        assertTrue(second.snapshot(1).recoveredIncomplete)
        val clean = second.begin(Operation.IMPORT, SafeInfo()); second.end(clean, Outcome.SUCCESS)
        clock.advance(100)
        val third = DiagnosticStore(directory, clock, "c".repeat(32))
        assertFalse(third.snapshot(1).recoveredIncomplete)
        assertFalse(ChineseDiagnostics.summary(third.snapshot(1), DiagnosticMetadata()).contains("最近的失败/中断记录"))
    }

    @Test fun unfinishedRecoveryNoticeIsNotShownOutsideSelectedWindow() {
        val directory = folder(); val clock = Clock()
        DiagnosticStore(directory, clock, runA).begin(Operation.EXPORT_RENDER, SafeInfo())
        val second = DiagnosticStore(directory, clock, runB)
        assertTrue(second.snapshot(1).recoveredIncomplete)
        clock.advance(60_001)
        assertFalse(second.snapshot(1).recoveredIncomplete)
    }

    @Test fun currentRunShareCannotBeDeletedEarlyByForwardWallClockJump() {
        val clock = Clock(); val store = DiagnosticStore(folder(), clock, runA); val shares = folder()
        val archive = DiagnosticArchive.write(shares, store.snapshot(5), DiagnosticMetadata())
        DiagnosticArchive.cleanup(shares, clock.wall + 3 * DiagnosticStore.RETENTION_MS, clock.elapsed + 1000)
        assertTrue(archive.file.exists())
    }

    @Test fun endedOperationIsNotRecoveredAndNativeBreadcrumbIsRecovered() {
        val closedFolder = folder(); val clock = Clock()
        val first = DiagnosticStore(closedFolder, clock, runA)
        val id = first.begin(Operation.IMPORT, SafeInfo()); first.end(id, Outcome.SUCCESS)
        assertFalse(DiagnosticStore(closedFolder, clock, runB).snapshot(1).recoveredIncomplete)
        val openFolder = folder(); val active = DiagnosticStore(openFolder, clock, runA)
        val native = active.begin(Operation.EXPORT_RENDER, SafeInfo(memoryBudgetMiB = 256))
        active.event(native, Stage.NATIVE_ENTER, SafeInfo(), null)
        val restarted = DiagnosticStore(openFolder, clock, runB).snapshot(1)
        assertTrue(restarted.recoveredIncomplete)
        assertTrue(restarted.events.any { it.stage == Stage.PREVIOUS_UNFINISHED && it.info.previousOperation == Operation.EXPORT_RENDER })
    }

    @Test fun expiryRemovesOldRecordsFromDiskAtNextAccess() {
        val directory = folder(); val clock = Clock(); val store = DiagnosticStore(directory, clock, runA)
        store.begin(Operation.IMPORT, SafeInfo())
        clock.advance(DiagnosticStore.RETENTION_MS + 1)
        val snapshot = store.snapshot(1440)
        assertTrue(snapshot.events.isEmpty())
        assertTrue(snapshot.expiredDiscarded >= 2)
        assertTrue(directory.listFiles().orEmpty().filter { it.extension == "log" }.all { it.length() == 0L })
    }

    @Test fun rotationKeepsBoundedSegmentsAndReportsDiscardCount() {
        val directory = folder(); val clock = Clock(); val store = DiagnosticStore(directory, clock, runA, 8192)
        val id = store.begin(Operation.EXPORT_ENCODE, SafeInfo())
        repeat(100) { store.event(id, Stage.ENCODE_PROGRESS, SafeInfo(width = 5000, height = 2600, completedUnits = it.toLong(), totalUnits = 100), null) }
        val snapshot = store.snapshot(1)
        assertTrue(snapshot.capacityDiscarded > 0)
        assertEquals(99L, snapshot.events.last().info.completedUnits)
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 8192)
    }

    @Test fun tornMalformedAndOversizedLinesAreToleratedAndCounted() {
        val directory = folder(); val clock = Clock()
        val store = DiagnosticStore(directory, clock, runA)
        store.begin(Operation.IMPORT, SafeInfo())
        File(directory, "events.0.log").appendText("not_a_valid_record\n" + "x".repeat(5000) + "\nunfinished_secret")
        val reopened = DiagnosticStore(directory, clock, runB).snapshot(1)
        assertTrue(reopened.malformedDiscarded >= 3)
        assertFalse(reopened.text("fixed").contains("unfinished_secret"))
        assertTrue(reopened.events.any { it.operation == Operation.IMPORT })
    }

    @Test fun invalidMinutesAreRejected() {
        val store = DiagnosticStore(folder(), Clock(), runA)
        for (minutes in listOf(-1, 0, 1441, Int.MAX_VALUE)) {
            try { store.snapshot(minutes); fail("Invalid minutes accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(1440, store.snapshot(1440).minutes)
    }

    @Test fun numericWhitelistDropsInvalidValuesAndNoUserTextCanEnter() {
        val info = SafeInfo(width = -1, height = Int.MAX_VALUE, brightness = Double.NaN, grain = Double.POSITIVE_INFINITY,
            seed = -2, memoryBudgetMiB = 999, film = FilmProfile.fromId("secret.jpg\nTOKEN=abc"), paper = PaperProfile.fromId("content://photos/42"))
        assertTrue(info.fields().isEmpty())
        assertFalse(SafeInfo::class.java.constructors.any { constructor -> constructor.parameterTypes.any { it == String::class.java || Throwable::class.java.isAssignableFrom(it) } })
        val body = "1\t1800000000000\t1000\t$runA\t1\t1\tIMPORT\tSTART\t-\t-\twidth=64;uri=content://secret;message=TOKENabc;film=secret.jpg"
        val crc = CRC32().apply { update(body.toByteArray()) }.value.toString(16)
        val sanitized = checkNotNull(DiagnosticEvent.parse("$body\t$crc"))
        assertEquals(mapOf("width" to "64"), sanitized.info.fields())
        assertFalse(sanitized.line().contains("TOKEN")); assertFalse(sanitized.line().contains("secret"))
    }

    @Test fun endPersistsTypedDurationAndMemoryWithoutAllowingForgedEndEvents() {
        val store = DiagnosticStore(folder(), Clock(), runA)
        val id = store.begin(Operation.PREVIEW_RENDER, SafeInfo())
        store.event(id, Stage.END, SafeInfo(durationMs = 123), null)
        assertFalse(store.snapshot(1).events.any { it.stage == Stage.END })
        store.end(id, Outcome.SUCCESS, SafeInfo(durationMs = 456, javaUsedBytes = 12_345, nativeAllocatedBytes = 67_890))
        val end = store.snapshot(1).events.last()
        assertEquals(Stage.END, end.stage)
        assertEquals(456L, end.info.durationMs)
        assertEquals(12_345L, end.info.javaUsedBytes)
        assertEquals(67_890L, end.info.nativeAllocatedBytes)
    }

    @Test fun parallelWritersAndSnapshotsHaveUniqueOrderedSequences() {
        val store = DiagnosticStore(folder(), Clock(), runA)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 4).map {
                pool.submit {
                    repeat(15) {
                        val id = store.begin(Operation.PREVIEW_RENDER, SafeInfo(width = 512, height = 384))
                        store.event(id, Stage.NATIVE_ENTER, SafeInfo(), null)
                        store.snapshot(5)
                        store.end(id, Outcome.SUCCESS)
                    }
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            val events = store.snapshot(5).events
            assertEquals(events.size, events.map { it.sequence }.distinct().size)
            assertTrue(events.zipWithNext().all { it.first.sequence < it.second.sequence })
        } finally { pool.shutdownNow() }
    }

    @Test fun storageFailureIsReportedWithoutThrowingFromLogging() {
        val file = File(folder(), "not-a-directory").apply { writeText("x") }
        val store = DiagnosticStore(file, Clock(), runA)
        store.begin(Operation.IMPORT, SafeInfo())
        assertFalse(store.snapshot(1).storageHealthy)
    }

    @Test fun textReportContainsAllSectionsAndRetainsForFull24Hours() {
        val clock = Clock(); val store = DiagnosticStore(folder(), clock, runA)
        store.begin(Operation.IMPORT, SafeInfo(width = 42))
        val failed = store.begin(Operation.EXPORT_RENDER, SafeInfo(brightness = 1.25))
        store.event(failed, Stage.ERROR, SafeInfo(nativePhase = NativePhase.SETUP), ErrorCode.RENDER_FAILED)
        store.end(failed, Outcome.FAILED)
        val shares = folder()
        val unrelated = File(shares, "private-photo.jpg").apply { writeText("SHOULD_NOT_APPEAR") }
        val archive = DiagnosticArchive.write(shares, store.snapshot(15), DiagnosticMetadata(appVersionCode = 7))
        assertEquals("txt", archive.file.extension)
        val texts = archive.file.readText(Charsets.UTF_8)
        assertTrue(texts.startsWith("FILMROOM_DIAGNOSTICS_TEXT_V1\n"))
        assertFalse(archive.file.readBytes().take(2) == listOf(0x50.toByte(), 0x4b.toByte()))
        assertFalse(texts.contains("SHOULD_NOT_APPEAR")); assertFalse(texts.contains("private-photo.jpg"))
        assertTrue(texts.contains("过去 15 分钟")); assertTrue(texts.contains("\"appVersionCode\":7"))
        assertTrue(texts.contains("IMPORT/START")); assertTrue(texts.contains("width=42"))
        assertTrue(texts.contains("EXPORT_RENDER/ERROR")); assertTrue(texts.contains("错误代码=RENDER_FAILED"))
        assertTrue(texts.contains("brightness=1.25")); assertTrue(texts.contains("nativePhase=setup"))
        assertEquals(ChineseDiagnostics.json(store.snapshot(15), DiagnosticMetadata(appVersionCode = 7)),
            texts.substringAfter("=== BEGIN DIAGNOSTICS JSON V1 ===\n").substringBefore("\n=== END DIAGNOSTICS JSON V1 ==="))
        assertEquals(2, shares.listFiles()!!.size) // report plus unrelated file; no second attachment

        DiagnosticArchive.cleanup(shares, clock.wall + DiagnosticStore.RETENTION_MS)
        assertTrue(archive.file.exists())
        DiagnosticArchive.cleanup(shares, clock.wall + DiagnosticStore.RETENTION_MS + 1)
        assertFalse(archive.file.exists()); assertTrue(unrelated.exists())
    }

    @Test fun emptyAndMaximumWindowReportsRemainReadableAndBounded() {
        val clock = Clock(); val store = DiagnosticStore(folder(), clock, runA, 8192)
        val id = store.begin(Operation.EXPORT_ENCODE, SafeInfo())
        repeat(100) { store.event(id, Stage.ENCODE_PROGRESS, SafeInfo(completedUnits = it.toLong(), totalUnits = 100), null) }
        val snapshot = store.snapshot(1440)
        val report = DiagnosticArchive.write(folder(), snapshot, DiagnosticMetadata()).file.readText()
        assertTrue(snapshot.capacityDiscarded > 0)
        assertTrue(report.contains("completedUnits=99"))
        assertTrue(report.contains("容量轮替"))
        assertEquals(ChineseDiagnostics.json(snapshot, DiagnosticMetadata()),
            report.substringAfter("=== BEGIN DIAGNOSTICS JSON V1 ===\n").substringBefore("\n=== END DIAGNOSTICS JSON V1 ==="))
        assertTrue(report.toByteArray().size < 100_000)
        clock.advance(DiagnosticStore.RETENTION_MS + 1)
        val empty = DiagnosticArchive.write(folder(), store.snapshot(1440), DiagnosticMetadata())
        assertEquals(0, empty.eventCount)
        assertTrue(empty.file.readText().contains("所选时间内没有记录"))
        assertTrue(empty.file.readText().contains("\"events\":[]"))
    }

    @Test fun textReportDoesNotExposeUntrustedPersistedFields() {
        val directory = folder(); val clock = Clock(); val store = DiagnosticStore(directory, clock, runA)
        val body = "1\t${clock.wall}\t${clock.elapsed}\t$runA\t99\t99\tIMPORT\tSTART\t-\t-\twidth=64;uri=content://secret;message=TOKENabc;film=secret.jpg;gps=42;path=/private/photo"
        val crc = CRC32().apply { update(body.toByteArray()) }.value.toString(16)
        File(directory, "events.0.log").appendText("$body\t$crc\n")
        val reopened = DiagnosticStore(directory, clock, runB)
        val text = DiagnosticArchive.write(folder(), reopened.snapshot(5), DiagnosticMetadata()).file.readText()
        for (secret in listOf("content://secret", "TOKENabc", "secret.jpg", "/private/photo", "gps=42")) assertFalse(text.contains(secret))
        assertTrue(text.contains("width=64"))
    }

    @Test fun cleanupAlsoRemovesExpiredLegacyZipButNotOtherFiles() {
        val directory = folder(); val clock = Clock()
        val legacy = File(directory, "暗房_诊断_5分钟_20261031_050000_abcdef01.zip").apply { writeText("old"); setLastModified(clock.wall - DiagnosticStore.RETENTION_MS - 1) }
        val unrelated = File(directory, "keep.txt").apply { writeText("keep"); setLastModified(legacy.lastModified()) }
        DiagnosticArchive.cleanup(directory, clock.wall)
        assertFalse(legacy.exists()); assertTrue(unrelated.exists())
    }

    @Test fun cancelledArchiveCreationLeavesNoPartOrFinalFile() {
        val store = DiagnosticStore(folder(), Clock(), runA); val shares = folder()
        var checks = 0
        try {
            DiagnosticArchive.write(shares, store.snapshot(5), DiagnosticMetadata()) { if (++checks == 3) throw InterruptedException("cancel") }
            fail("Expected cancellation")
        } catch (_: InterruptedException) { }
        assertTrue(shares.listFiles().orEmpty().isEmpty())
    }
}
