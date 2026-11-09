package dev.filmroom.presets

import dev.filmroom.diagnostics.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException

/** One bounded trace per user action. Exception text and user data never enter diagnostics. */
internal class RecipeLog(private val action: RecipeAction, private val input: RecipeInput? = null, val correlation: Long = RecipeCorrelation.next()) {
    private val started = System.nanoTime()
    var step = RecipeStep.START
        private set
    private val id = QueuedDiagnosticTrace(Operation.RECIPE, SafeInfo(recipe = info()))
    private var ended = false
    private fun info(reason: RecipeReason = RecipeReason.NONE, count: Int? = null) =
        RecipeDiagnosticInfo(action, step, reason, input, count, elapsedMs = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0), correlation = correlation)
    fun at(value: RecipeStep) { if (ended) return; step = value; id.event(Stage.ENCODE_PROGRESS, SafeInfo(recipe = info())) }
    fun dimensions(width: Int, height: Int) {
        if (!ended) id.event(Stage.ENCODE_PROGRESS, SafeInfo(recipe = info().copy(width = width, height = height)))
    }
    fun note(reason: RecipeReason) { if (!ended) id.event(Stage.ERROR, SafeInfo(recipe = info(reason))) }
    fun end(outcome: Outcome, reason: RecipeReason = RecipeReason.NONE, count: Int? = null) {
        if (ended) return
        ended = true
        // Keep the last attempted step in the terminal event for failures/cancellation.
        id.end(outcome, SafeInfo(recipe = info(reason, count)))
    }
    fun failed(error: Throwable) = end(if (error is CancellationException) Outcome.CANCELLED else Outcome.FAILED, reason(error))
    companion object {
        fun reason(error: Throwable): RecipeReason = when (error) {
            is SavedPresetStore.Failure -> RecipeReason.valueOf(error.problem.name)
            is PresetQrCodec.Failure -> when (error.problem) {
                PresetQrCodec.Problem.INVALID -> RecipeReason.INVALID_QR
                PresetQrCodec.Problem.INCOMPATIBLE -> RecipeReason.INCOMPATIBLE
                PresetQrCodec.Problem.TOO_LARGE -> RecipeReason.TOO_LARGE
            }
            is SecurityException -> RecipeReason.PERMISSION_DENIED
            is FileNotFoundException -> RecipeReason.FILE_NOT_FOUND
            is IOException -> RecipeReason.IO_ERROR
            is OutOfMemoryError -> RecipeReason.OUT_OF_MEMORY
            is IllegalArgumentException -> RecipeReason.INVALID_ARGUMENT
            is IllegalStateException -> RecipeReason.INVALID_STATE
            else -> RecipeReason.UNKNOWN
        }
        inline fun <T> trace(action: RecipeAction, correlation: Long = RecipeCorrelation.next(), block: (RecipeLog) -> T): T {
            val log = RecipeLog(action, correlation = correlation)
            try { return block(log).also { log.end(Outcome.SUCCESS) } }
            catch (error: Throwable) { log.failed(error); throw error }
        }
    }
}
