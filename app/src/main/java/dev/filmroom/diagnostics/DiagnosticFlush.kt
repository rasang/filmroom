package dev.filmroom.diagnostics

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small internal seams for real-file tests with observable write/flush/sync failures. */
internal interface DiagnosticOutput : Closeable {
    fun write(bytes: ByteArray)
    fun flush()
    fun sync()
}

internal class FileDiagnosticOutput(file: File, append: Boolean) : DiagnosticOutput {
    private val stream = FileOutputStream(file, append)
    override fun write(bytes: ByteArray) = stream.write(bytes)
    override fun flush() = stream.flush()
    override fun sync() = stream.fd.sync()
    override fun close() = stream.close()
}

internal fun interface DiagnosticFlushScheduler {
    /** Must enqueue asynchronously, never invoke the callback inline. */
    fun schedule(delayMs: Long, task: () -> Unit): Closeable
}

internal object SharedDiagnosticFlushScheduler : DiagnosticFlushScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "filmroom-diagnostic-flush").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun schedule(delayMs: Long, task: () -> Unit): Closeable {
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return Closeable { future.cancel(false) }
    }
}
