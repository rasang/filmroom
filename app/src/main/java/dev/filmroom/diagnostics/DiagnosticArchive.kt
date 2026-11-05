package dev.filmroom.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** One UTF-8 text report. It never traverses or packages any source directory. */
internal object DiagnosticArchive {
    private val currentRunCreated = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun write(directory: File, snapshot: DiagnosticSnapshot, metadata: DiagnosticMetadata, checkActive: () -> Unit = {}): DiagnosticShare {
        check(directory.isDirectory || directory.mkdirs())
        val summary = ChineseDiagnostics.summary(snapshot, metadata)
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(snapshot.snapshotWallMs))
        val target = File(directory, "暗房_诊断_${snapshot.minutes}分钟_${timestamp}_${UUID.randomUUID().toString().take(8)}.txt")
        val part = File.createTempFile(".diagnostic-", ".part", directory)
        try {
            checkActive()
            FileOutputStream(part).use { raw ->
                // Keep the same safe summary, detailed timeline and complete JSON payload.
                // Explicit markers allow tools to extract JSON without separate attachments.
                fun section(text: String) {
                    checkActive()
                    raw.write(text.toByteArray(Charsets.UTF_8))
                }
                section(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_71fcada1d005))
                section(summary + dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7af205e83135))
                section(snapshot.text(metadata.text()))
                section(snapshot.renderLifecycle.text() + "\n\n=== BEGIN DIAGNOSTICS JSON V1 ===\n")
                section(ChineseDiagnostics.json(snapshot, metadata))
                section("\n=== END DIAGNOSTICS JSON V1 ===\n")
                raw.flush()
                raw.fd.sync()
            }
            checkActive()
            Files.move(part.toPath(), target.toPath())
            // Wall-clock file age controls share retention, independently of event time filtering.
            target.setLastModified(snapshot.snapshotWallMs)
            currentRunCreated[target.absolutePath] = snapshot.snapshotElapsedMs
            return DiagnosticShare(target, summary, snapshot.events.size, snapshot.minutes)
        } finally { part.delete() }
    }

    fun cleanup(directory: File, now: Long, elapsedNow: Long? = null) {
        val finalName = Regex("暗房_诊断_[0-9]{1,4}分钟_[0-9]{8}_[0-9]{6}_[0-9a-f]{8}\\.(?:txt|zip)")
        val partName = Regex("\\.diagnostic-[0-9]+\\.part")
        directory.listFiles()?.forEach { file ->
            val createdElapsed = currentRunCreated[file.absolutePath]
            if (elapsedNow != null && createdElapsed != null && elapsedNow - createdElapsed <= DiagnosticStore.RETENTION_MS) return@forEach
            val age = now - file.lastModified()
            if (file.isFile && (file.name.matches(finalName) || file.name.matches(partName)) && age > DiagnosticStore.RETENTION_MS) {
                if (file.delete()) currentRunCreated.remove(file.absolutePath)
            }
        }
    }
}
