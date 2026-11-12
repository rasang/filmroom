package dev.filmroom.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import dev.filmroom.io.LinearSpool
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** App-private, display-encoded copy of the canonical RAW input, BEFORE crop/frame/effects.
 * Lives with the export record, not in cache/MediaStore. Never used as scientific render input.
 * Backups deliberately do not carry this artifact; restored/legacy records use source fallback.
 */
internal object RawSourcePreviewStore {
    const val MAX_EDGE = 2560
    private const val IMAGE = "source-raw.preview"
    private const val META = "source-raw.meta"
    private const val MAX_BYTES = 16L * 1024 * 1024
    private fun binding(job: ExportJob): String = MessageDigest.getInstance("SHA-256")
        .digest("${job.id}\n${job.originalUri.orEmpty()}\n${job.sourceWidth}\n${job.sourceHeight}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun write(dir: File, job: ExportJob, linear: File, coroutine: CoroutineContext) {
        coroutine.ensureActive()
        clear(dir)
        val bitmap = LinearSpool.preview(linear, job.sourceWidth, job.sourceHeight, MAX_EDGE, coroutine)
        try {
            val image = AtomicFile(File(dir, IMAGE))
            val output = image.startWrite()
            try {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                coroutine.ensureActive()
                image.finishWrite(output)
            } catch (t: Throwable) { image.failWrite(output); throw t }
            check(File(dir, IMAGE).length() in 1..MAX_BYTES)
            val meta = AtomicFile(File(dir, META))
            val stream = meta.startWrite()
            try {
                stream.write(JSONObject().put("version", 1).put("binding", binding(job))
                    .put("width", bitmap.width).put("height", bitmap.height)
                    .put("sha256", checksum(File(dir, IMAGE))).toString().toByteArray())
                coroutine.ensureActive()
                meta.finishWrite(stream)
            } catch (t: Throwable) { meta.failWrite(stream); throw t }
        } catch (t: Throwable) { clear(dir); throw t }
        finally { bitmap.recycle() }
    }

    /** Missing, invalid, stale/relinked or backup-only records cannot supply source pixels. */
    fun read(context: Context, job: ExportJob, maxEdge: Int, onSize: (Int, Int) -> Unit = { _, _ -> }): Bitmap? {
        require(maxEdge in 1..6144)
        if(job.archivedHistory || runCatching { UUID.fromString(job.id).toString() != job.id }.getOrDefault(true))return null
        return readDirectory(File(File(context.filesDir, "export-jobs"), job.id), job, maxEdge, onSize)
    }

    internal fun readDirectory(dir: File, job: ExportJob, maxEdge: Int, onSize: (Int, Int) -> Unit = { _, _ -> }): Bitmap? {
        var owned: Bitmap? = null
        try {
            val meta = File(dir, META)
            val image = File(dir, IMAGE)
            if(!meta.isFile || meta.length() !in 1..4096 || !image.isFile || image.length() !in 1..MAX_BYTES)return null
            // No external paths are accepted from stored metadata.
            if(meta.canonicalFile.parentFile != dir.canonicalFile || image.canonicalFile.parentFile != dir.canonicalFile)return null
            val info = JSONObject(meta.readText())
            if(info.optInt("version") != 1 || info.optString("binding") != binding(job))return null
            if(info.optString("sha256") != checksum(image))return null
            val w = info.getInt("width"); val h = info.getInt("height")
            if(w !in 1..MAX_EDGE || h !in 1..MAX_EDGE)return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.path, bounds)
            if(bounds.outWidth != w || bounds.outHeight != h || bounds.outMimeType != "image/jpeg")return null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                var sample = 1
                while(maxOf(w, h) / (sample * 2) >= maxEdge)sample *= 2
                inSampleSize = sample
            }
            owned = BitmapFactory.decodeFile(image.path, options) ?: return null
            val source = owned
            val scale = minOf(1.0, maxEdge.toDouble() / maxOf(source.width, source.height))
            if(scale < 1.0) {
                val scaled = Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1), true)
                if(scaled !== source)source.recycle()
                owned = scaled
            }
            onSize(w, h)
            return owned.also { owned = null }
        } catch (_: Exception) { return null }
        catch (_: OutOfMemoryError) { return null }
        finally { owned?.takeUnless { it.isRecycled }?.recycle() }
    }

    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while(true) { val count = input.read(buffer); if(count < 0)break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun retained(name: String) = name == IMAGE || name == META
    fun clear(dir: File) {
        AtomicFile(File(dir, IMAGE)).delete()
        AtomicFile(File(dir, META)).delete()
    }
}
