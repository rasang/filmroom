package dev.filmroom.export

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** Durable, full export-resolution processed still, without Live scene decorations.
 * App private and owned by the job directory; never scanned or included in metadata backups. */
internal object CleanResultStore {
    private const val IMAGE = "clean-result.image"
    private const val META = "clean-result.meta"
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private fun binding(job: ExportJob): String = checksum(
        "${job.id}\n${job.createdAt}\n${job.outputWidth}\n${job.outputHeight}\n${job.liveEffect.orEmpty()}\n${job.parameters.json("export")}".toByteArray())

    /** The caller supplies the completed JPEG/PNG encoder output, not a preview bitmap.
     * Publication of the Live asset must not happen until this durable save succeeds. */
    fun write(dir: File, job: ExportJob, encoded: File, coroutine: CoroutineContext = kotlin.coroutines.EmptyCoroutineContext) {
        require(job.liveEffect != null)
        check(encoded.isFile && encoded.length() in 1..MAX_BYTES)
        val bounds = bounds(encoded)
        check(bounds.outWidth == job.outputWidth && bounds.outHeight == job.outputHeight)
        check(bounds.outMimeType in setOf("image/png", "image/jpeg"))
        check(dir.mkdirs() || dir.isDirectory)
        coroutine.ensureActive()
        clear(dir)
        try {
            val image = AtomicFile(File(dir, IMAGE))
            val out = image.startWrite()
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                encoded.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count); digest.update(buffer, 0, count)
                    }
                }
                out.fd.sync(); image.finishWrite(out)
            } catch (t: Throwable) { image.failWrite(out); throw t }
            val saved = File(dir, IMAGE)
            val expectedHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(saved.length() == encoded.length() && checksum(saved) == expectedHash)
            val bytes = JSONObject().put("version", 1).put("binding", binding(job))
                .put("width", bounds.outWidth).put("height", bounds.outHeight)
                .put("mime", bounds.outMimeType).put("sha256", expectedHash).toString().toByteArray()
            val meta = AtomicFile(File(dir, META)); val stream = meta.startWrite()
            try {
                stream.write(bytes); coroutine.ensureActive(); stream.fd.sync(); meta.finishWrite(stream)
            } catch (t: Throwable) { meta.failWrite(stream); throw t }
            check(meta.openRead().use { it.readBytes() }.contentEquals(bytes))
        } catch (t: Throwable) { clear(dir); throw t }
    }

    fun resolve(context: Context, job: ExportJob): File? {
        if (job.archivedHistory || runCatching { UUID.fromString(job.id).toString() != job.id }.getOrDefault(true)) return null
        return resolveDirectory(File(File(context.filesDir, "export-jobs"), job.id), job)
    }

    internal fun resolveDirectory(dir: File, job: ExportJob): File? = runCatching {
        if (job.liveEffect == null) return null
        val image = File(dir, IMAGE); val meta = File(dir, META)
        if (!image.isFile || image.length() !in 1..MAX_BYTES || !meta.isFile || meta.length() !in 1..4096) return null
        if (image.canonicalFile.parentFile != dir.canonicalFile || meta.canonicalFile.parentFile != dir.canonicalFile) return null
        val info = JSONObject(meta.readText())
        if (info.optInt("version") != 1 || info.optString("binding") != binding(job) || info.optString("sha256") != checksum(image)) return null
        val b = bounds(image)
        if (b.outWidth != job.outputWidth || b.outHeight != job.outputHeight || b.outWidth != info.optInt("width") || b.outHeight != info.optInt("height")) return null
        if (b.outMimeType !in setOf("image/png", "image/jpeg") || b.outMimeType != info.optString("mime")) return null
        image
    }.getOrNull()

    private fun bounds(file: File) = BitmapFactory.Options().apply { inJustDecodeBounds = true; BitmapFactory.decodeFile(file.path, this) }
    private fun checksum(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun retained(name: String) = name == IMAGE || name == META
    fun clear(dir: File) { AtomicFile(File(dir, IMAGE)).delete(); AtomicFile(File(dir, META)).delete() }
}
