package dev.filmroom.backup

import android.os.SystemClock
import dev.filmroom.diagnostics.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException

/** One operation, bounded checkpoints, safe categories only. Never logs document contents. */
internal class BackupLog(private val action: BackupAction) {
    private val started = SystemClock.elapsedRealtime()
    private var step = BackupStep.PREPARE
    private var writes = false
    private var ended = false
    private var recipeCount: Int? = null
    private var historyCount: Int? = null
    private val id = QueuedDiagnosticTrace(Operation.BACKUP, info())
    private fun info(failure: BackupFailure = BackupFailure.NONE) = SafeInfo(
        backup = BackupDiagnosticInfo(action, step, failure, recipeCount, historyCount, writes),
        durationMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0),
    )
    fun step(value: BackupStep, writesStarted: Boolean = writes) {
        step = value; writes = writesStarted
        id.event(Stage.ENCODE_PROGRESS, info())
    }
    fun counts(recipes: Int, history: Int) { recipeCount = recipes; historyCount = history }
    fun success() { step = BackupStep.COMPLETE; end(Outcome.SUCCESS) }
    fun failure(error: Throwable) {
        val reason = when (error) {
            is CancellationException -> BackupFailure.CANCELLED
            is SecurityException -> BackupFailure.PERMISSION
            is FileNotFoundException -> BackupFailure.NOT_FOUND
            is IOException -> BackupFailure.IO
            is IllegalArgumentException -> BackupFailure.INVALID_DATA
            is IllegalStateException -> BackupFailure.INVALID_STATE
            else -> BackupFailure.UNKNOWN
        }
        id.event(if (reason == BackupFailure.CANCELLED) Stage.CANCEL_REQUESTED else Stage.ERROR, info(reason))
        end(if (reason == BackupFailure.CANCELLED) Outcome.CANCELLED else Outcome.FAILED, reason)
    }
    private fun end(outcome: Outcome, reason: BackupFailure = BackupFailure.NONE) {
        if (ended) return
        ended = true
        id.end(outcome, info(reason))
    }
}
