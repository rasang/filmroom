package dev.filmroom.diagnostics

import android.app.ApplicationExitInfo
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProcessExitTrackerTest {
    private val run = "a".repeat(32)
    private val marker = ExitMarker(run, 123, 1_000)
    private fun candidate(pid: Int = 123, process: String = "dev.filmroom", time: Long = 1_500,
                          reason: ProcessExitReason = ProcessExitReason.LOW_MEMORY, tokenMatches: Boolean = true) = ExitCandidate(pid, process, time, reason, tokenMatches)
    private fun match(vararg events: ExitCandidate) = ProcessExitTracker.matchExit(marker, "dev.filmroom", 1_200, 2_000, events.toList())

    @Test fun onlyOnePidProcessAndTimeMatchCanBeAttributed() {
        assertEquals(ProcessExitReason.LOW_MEMORY, match(candidate()))
        assertEquals(ProcessExitReason.UNKNOWN, match())
        assertEquals(ProcessExitReason.UNKNOWN, match(candidate(pid = 124)))
        // The prior exit may be absent and this one PID reused by a later process.
        assertEquals(ProcessExitReason.UNKNOWN, match(candidate(tokenMatches = false)))
        assertEquals(ProcessExitReason.UNKNOWN, match(candidate(process = "dev.filmroom:other")))
        assertEquals(ProcessExitReason.UNKNOWN, match(candidate(time = 1_199)))
        assertEquals(ProcessExitReason.UNKNOWN, match(candidate(time = 2_001)))
        assertEquals(ProcessExitReason.UNKNOWN, match(candidate(), candidate(time = 1_600, reason = ProcessExitReason.JAVA_CRASH)))
        assertEquals(ProcessExitReason.JAVA_CRASH, match(candidate(reason = ProcessExitReason.JAVA_CRASH)))
        assertEquals(ProcessExitReason.UNKNOWN, match(candidate(reason = ProcessExitReason.UNKNOWN)))
    }

    @Test fun osRunTokenMustMatchExactlyAndMissingSummaryIsUnknown() {
        val summary = ProcessExitTracker.summaryForRun(run)
        assertTrue(ProcessExitTracker.summaryMatches(run, summary))
        assertFalse(ProcessExitTracker.summaryMatches(run, null))
        assertFalse(ProcessExitTracker.summaryMatches(run, ProcessExitTracker.summaryForRun("b".repeat(32))))
        assertFalse(ProcessExitTracker.summaryMatches(run, summary.copyOf(summary.size - 1)))
    }

    @Test fun platformReasonsAreNotInferredFromMissingOrUnrecognizedValues() {
        assertEquals(ProcessExitReason.LOW_MEMORY, ProcessExitTracker.classify(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals(ProcessExitReason.JAVA_CRASH, ProcessExitTracker.classify(ApplicationExitInfo.REASON_CRASH))
        assertEquals(ProcessExitReason.NATIVE_CRASH, ProcessExitTracker.classify(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals(ProcessExitReason.UNKNOWN, ProcessExitTracker.classify(ApplicationExitInfo.REASON_UNKNOWN))
        assertEquals(ProcessExitReason.OTHER, ProcessExitTracker.classify(Int.MAX_VALUE))
    }

    @Test fun markerMustMatchRunStartAndSaneTimeWindow() {
        assertTrue(ProcessExitTracker.validMarker(marker, run, 1_000, 1_200, 2_000))
        assertFalse(ProcessExitTracker.validMarker(marker, "b".repeat(32), 1_000, 1_200, 2_000))
        assertFalse(ProcessExitTracker.validMarker(marker, run, 1_001, 1_200, 2_000))
        assertFalse(ProcessExitTracker.validMarker(marker, run, 1_000, 999, 2_000))
        assertFalse(ProcessExitTracker.validMarker(marker, run, 1_000, 1_200, 1_199))
        assertFalse(ProcessExitTracker.validMarker(marker, run, 1_000, 1_200, 1_000 + DiagnosticStore.RETENTION_MS + 1))
    }

    @Test fun privateMarkerParserIsBoundedAndRejectsUntrustedData() {
        val dir = Files.createTempDirectory("exit-marker").toFile()
        try {
            val file = File(dir, "process.marker")
            file.writeText("$run\t123\t1000\n")
            assertEquals(marker, ProcessExitTracker.readMarker(file))
            file.writeText("secret-path\t123\t1000\n")
            assertNull(ProcessExitTracker.readMarker(file))
            file.writeText("x".repeat(101))
            assertNull(ProcessExitTracker.readMarker(file))
            file.writeText("$run\t0\t1000\n")
            assertNull(ProcessExitTracker.readMarker(file))
        } finally { dir.deleteRecursively() }
    }

    private class Clock(var wall: Long = 1_000, var elapsed: Long = 100) : DiagnosticClock {
        override fun wallMs() = wall
        override fun elapsedMs() = elapsed
    }
    private class Tracker(var result: ProcessExitReason = ProcessExitReason.LOW_MEMORY) : ExitTracker {
        val marks = mutableListOf<Pair<String, Long>>()
        var calls = 0
        override fun previous(run: String, sessionStart: Long, lastEvent: Long, now: Long): ProcessExitReason { calls++; return result }
        override fun mark(run: String, sessionStart: Long) { marks += run to sessionStart }
    }

    @Test fun recoveryPersistsOnlyTypedExitAndDoesNotConvertItToOom() {
        val dir = Files.createTempDirectory("exit-store").toFile()
        try {
            val clock = Clock(); val tracker = Tracker()
            val first = DiagnosticStore(dir, clock, run, exitTracker = tracker)
            clock.wall += 100; clock.elapsed += 100
            first.begin(Operation.IMPORT, SafeInfo())
            clock.wall += 100; clock.elapsed += 100
            val restarted = DiagnosticStore(dir, clock, "b".repeat(32), exitTracker = tracker)
            val snapshot = restarted.snapshot(1)
            val event = snapshot.events.single { it.stage == Stage.PREVIOUS_UNFINISHED }
            assertEquals(ProcessExitReason.LOW_MEMORY, event.info.previousExitReason)
            assertEquals(ErrorCode.PREVIOUS_TERMINATION_UNCERTAIN, event.error)
            assertEquals(event, DiagnosticEvent.parse(event.line()))
            val json = ChineseDiagnostics.json(snapshot, DiagnosticMetadata())
            assertTrue(json.contains("\"previousExitReason\":\"LOW_MEMORY\""))
            assertFalse(json.contains("processName")); assertFalse(json.contains("123")); assertFalse(json.contains("OOM"))
            assertEquals(1, tracker.calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun trackerFailureCannotBreakRecovery() {
        val dir = Files.createTempDirectory("exit-failure").toFile()
        try {
            val clock = Clock()
            val broken = object : ExitTracker {
                override fun previous(run: String, sessionStart: Long, lastEvent: Long, now: Long): ProcessExitReason = error("secret")
                override fun mark(run: String, sessionStart: Long) { error("secret") }
            }
            val first = DiagnosticStore(dir, clock, run, exitTracker = broken)
            first.begin(Operation.IMPORT, SafeInfo())
            val second = DiagnosticStore(dir, clock, "b".repeat(32), exitTracker = broken)
            val event = second.snapshot(1).events.single { it.stage == Stage.PREVIOUS_UNFINISHED }
            assertEquals(ProcessExitReason.UNKNOWN, event.info.previousExitReason)
            assertFalse(event.line().contains("secret"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun clockChangePreventsAttributionAndStillReportsUnknown() {
        val dir = Files.createTempDirectory("exit-clock").toFile()
        try {
            val clock = Clock(); val tracker = Tracker()
            val first = DiagnosticStore(dir, clock, run, exitTracker = tracker)
            clock.wall += 10_000; clock.elapsed += 10
            first.begin(Operation.IMPORT, SafeInfo())
            val second = DiagnosticStore(dir, clock, "b".repeat(32), exitTracker = tracker)
            assertEquals(ProcessExitReason.UNKNOWN, second.snapshot(1).events.single { it.stage == Stage.PREVIOUS_UNFINISHED }.info.previousExitReason)
            assertEquals(0, tracker.calls)
        } finally { dir.deleteRecursively() }
    }
}
