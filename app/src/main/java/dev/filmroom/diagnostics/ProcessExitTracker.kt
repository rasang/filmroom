package dev.filmroom.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Only fixed classifications are ever included in a diagnostic report. */
enum class ProcessExitReason { UNKNOWN, LOW_MEMORY, JAVA_CRASH, NATIVE_CRASH, ANR, USER_REQUESTED, OTHER }

internal data class ExitMarker(val run: String, val pid: Int, val startedWallMs: Long)
internal data class ExitCandidate(val pid: Int, val processName: String, val timestamp: Long, val reason: ProcessExitReason, val runTokenMatches: Boolean)

internal interface ExitTracker {
    fun previous(run: String, sessionStart: Long, lastEvent: Long, now: Long): ProcessExitReason
    fun mark(run: String, sessionStart: Long)
}

/**
 * The marker remains in private app storage. It is never added to a share.
 * PID, process name, OS summary bytes, description, status, and traces are never exported.
 */
internal class ProcessExitTracker(private val context: Context, private val directory: File) : ExitTracker {
    override fun previous(run: String, sessionStart: Long, lastEvent: Long, now: Long): ProcessExitReason = runCatching {
        if (Build.VERSION.SDK_INT < 30) return ProcessExitReason.UNKNOWN
        val marker = readMarker(File(directory, "process.marker")) ?: return ProcessExitReason.UNKNOWN
        if (!validMarker(marker, run, sessionStart, lastEvent, now)) return ProcessExitReason.UNKNOWN
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return ProcessExitReason.UNKNOWN
        val expectedProcess = Application.getProcessName()
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, marker.pid, 32)
        // A full result might be truncated. Refuse to identify a unique record.
        if (exits.size >= 32) return ProcessExitReason.UNKNOWN
        val candidates = exits.map { ExitCandidate(it.pid, it.processName, it.timestamp, classify(it.reason), summaryMatches(marker.run, it.processStateSummary)) }
        matchExit(marker, expectedProcess, lastEvent, now, candidates)
    }.getOrDefault(ProcessExitReason.UNKNOWN)

    override fun mark(run: String, sessionStart: Long) {
        runCatching {
            val marker = ExitMarker(run, Process.myPid(), sessionStart)
            require(marker.run.matches(Regex("[0-9a-f]{32}")) && marker.pid > 0 && marker.startedWallMs > 0)
            val temp = File(directory, "process.marker.new")
            FileOutputStream(temp).use { stream ->
                stream.write("${marker.run}\t${marker.pid}\t${marker.startedWallMs}\n".toByteArray(Charsets.US_ASCII))
                stream.fd.sync()
            }
            Files.move(temp.toPath(), File(directory, "process.marker").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            // A PID and timestamp cannot prove lifetime identity after PID reuse.
            // Android preserves this random per-run token with this process's exit record.
            if (Build.VERSION.SDK_INT >= 30) {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                manager?.setProcessStateSummary(summaryForRun(run))
            }
        }
    }

    companion object {
        internal fun readMarker(file: File): ExitMarker? = runCatching {
            if (!file.isFile || file.length() !in 1L..100L) return null
            val parts = file.readText(Charsets.US_ASCII).trimEnd('\n').split('\t')
            if (parts.size != 3 || !parts[0].matches(Regex("[0-9a-f]{32}"))) return null
            val pid = parts[1].toIntOrNull() ?: return null
            val start = parts[2].toLongOrNull() ?: return null
            if (pid <= 0 || start <= 0) return null
            ExitMarker(parts[0], pid, start)
        }.getOrNull()

        internal fun validMarker(marker: ExitMarker, run: String, sessionStart: Long, lastEvent: Long, now: Long): Boolean =
            marker.run == run && marker.startedWallMs == sessionStart && sessionStart > 0 &&
                sessionStart <= lastEvent && lastEvent <= now && now - sessionStart <= DiagnosticStore.RETENTION_MS

        internal fun summaryForRun(run: String): ByteArray = "filmroom-diag-v1:$run".toByteArray(Charsets.US_ASCII)

        internal fun summaryMatches(run: String, summary: ByteArray?): Boolean =
            summary != null && summary.contentEquals(summaryForRun(run))

        internal fun matchExit(marker: ExitMarker, expectedProcess: String, lastEvent: Long, now: Long, candidates: List<ExitCandidate>): ProcessExitReason {
            if (expectedProcess.isEmpty() || lastEvent < marker.startedWallMs || now < lastEvent) return ProcessExitReason.UNKNOWN
            val matches = candidates.filter { it.pid == marker.pid && it.processName == expectedProcess && it.timestamp in lastEvent..now && it.runTokenMatches }
            return matches.singleOrNull()?.reason ?: ProcessExitReason.UNKNOWN
        }

        internal fun classify(reason: Int): ProcessExitReason = when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> ProcessExitReason.LOW_MEMORY
            ApplicationExitInfo.REASON_CRASH -> ProcessExitReason.JAVA_CRASH
            ApplicationExitInfo.REASON_CRASH_NATIVE -> ProcessExitReason.NATIVE_CRASH
            ApplicationExitInfo.REASON_ANR -> ProcessExitReason.ANR
            ApplicationExitInfo.REASON_USER_REQUESTED -> ProcessExitReason.USER_REQUESTED
            ApplicationExitInfo.REASON_UNKNOWN -> ProcessExitReason.UNKNOWN
            else -> ProcessExitReason.OTHER
        }
    }
}
