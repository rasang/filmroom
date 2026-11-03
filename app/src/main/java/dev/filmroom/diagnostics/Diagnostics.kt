package dev.filmroom.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Local-only typed diagnostic facade. No Throwable/raw JSON/free-text API exists.
 * initialize once from Application.onCreate, before native engine entry.
 * event(..., NATIVE_ENTER) completes an fsync breadcrumb before it returns.
 */
object Diagnostics {
    @Volatile private var store: DiagnosticStore? = null
    private var sharesDirectory: File? = null
    private var metadata: DiagnosticMetadata? = null
    private val clock = object : DiagnosticClock {
        override fun wallMs() = System.currentTimeMillis()
        override fun elapsedMs() = SystemClock.elapsedRealtime()
    }

    @Synchronized fun initialize(context: Context) {
        if (store != null) return
        val app = context.applicationContext
        sharesDirectory = File(app.cacheDir, "diagnostic-shares")
        metadata = captureMetadata(app)
        val directory = File(app.filesDir, "diagnostics")
        store = runCatching { DiagnosticStore(directory, clock, exitTracker = ProcessExitTracker(app, directory)) }.getOrNull()
        // Only old exact diagnostic-share files; never package or sweep app cache.
        runCatching { DiagnosticArchive.cleanup(checkNotNull(sharesDirectory), clock.wallMs()) }
    }

    fun begin(operation: Operation, info: SafeInfo = SafeInfo()): OperationId =
        runCatching { store?.begin(operation, info) }.getOrNull() ?: OperationId(0)

    fun event(id: OperationId, stage: Stage, info: SafeInfo = SafeInfo(), error: ErrorCode? = null) {
        runCatching { store?.event(id, stage, info, error) }
    }

    fun end(id: OperationId, outcome: Outcome, info: SafeInfo = SafeInfo()) { runCatching { store?.end(id, outcome, info) } }

    /** Numeric measurements only; no process names, OS logs, identifiers, or dumps. */
    fun memoryInfo(): SafeInfo = runCatching {
        val runtime = Runtime.getRuntime()
        SafeInfo(javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(), javaMaxBytes = runtime.maxMemory(),
            nativeAllocatedBytes = Debug.getNativeHeapAllocatedSize())
    }.getOrDefault(SafeInfo())

    suspend fun prepareShare(minutes: Int): DiagnosticShare {
        require(minutes in 1..1440) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_94f31f970642) }
        var produced: DiagnosticShare? = null
        var delivered = false
        try {
            val result = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val source = store ?: throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_235ff46182e0))
                val directory = sharesDirectory ?: throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2bec26a5c8e2))
                check(directory.isDirectory || directory.mkdirs()) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_78d664a50890) }
                DiagnosticArchive.cleanup(directory, clock.wallMs(), clock.elapsedMs())
                val snapshot = source.snapshot(minutes)
                val meta = metadata ?: DiagnosticMetadata()
                val job = currentCoroutineContext()
                DiagnosticArchive.write(directory, snapshot, meta) { job.ensureActive() }.also { produced = it }
            }
            delivered = true
            return result
        } finally {
            // A cancelled dispatcher handoff has not opened Sharesheet, so it
            // owns no recipient-visible URI and can remove this orphan snapshot.
            if (!delivered) produced?.file?.delete()
        }
    }

    private fun captureMetadata(context: Context): DiagnosticMetadata {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0)
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: 0
        return DiagnosticMetadata(version.coerceIn(0, 1_000_000_000), Build.VERSION.SDK_INT.coerceIn(0, 1000),
            Build.SUPPORTED_ABIS.firstNotNullOfOrNull { value -> DiagnosticAbi.entries.firstOrNull { it.id == value } } ?: DiagnosticAbi.UNKNOWN,
            Runtime.getRuntime().availableProcessors().coerceIn(0, 1024), Runtime.getRuntime().maxMemory().coerceIn(0, 1L shl 40),
            memoryClass.coerceIn(0, 1_000_000))
    }

}
