package dev.filmroom.io

import dev.filmroom.codec.RawMetadata
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import org.json.JSONObject

/** A completed RAW spool is reusable only under the policy that produced its pixels.
 * Geometry/profile coefficient changes must bump the native developmentPolicy version.
 * The record contains bounded internal identity fields, never native warning text or EXIF.
 */
internal object RawDevelopmentCache {
    const val SUFFIX = ".development.json"
    private const val MAX_RECORD_BYTES = 2048L

    fun recordFile(raw: File): File = File(raw.path + SUFFIX)

    fun matches(raw: File, source: File, metadata: RawMetadata): Boolean {
        val policy = metadata.developmentPolicy ?: return false
        val record = recordFile(raw)
        if (!Files.isRegularFile(record.toPath(), NOFOLLOW_LINKS)
            || record.length() !in 1..MAX_RECORD_BYTES || raw.length() != metadata.bytes) return false
        return runCatching {
            val buffer = ByteBuffer.allocate((MAX_RECORD_BYTES + 1).toInt())
            FileChannel.open(record.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) { /* bounded regular-file read */ }
            }
            if (buffer.position() == 0 || buffer.position() > MAX_RECORD_BYTES) return@runCatching false
            val saved = JSONObject(String(buffer.array(), 0, buffer.position(), Charsets.UTF_8))
            saved.getInt("schemaVersion") == 1
                && saved.getString("developmentPolicy") == policy
                && saved.getString("sourceKey") == source.name
                && saved.getLong("sourceBytes") == source.length()
                && saved.getLong("sourceModified") == source.lastModified()
                && saved.getLong("rawModified") == raw.lastModified()
                && saved.getString("format") == metadata.format.name
                && saved.getInt("width") == metadata.width
                && saved.getInt("height") == metadata.height
                && saved.getLong("outputBytes") == metadata.bytes
        }.getOrDefault(false)
    }

    /** Called only for a new, fully decoded spool before handing it to a PhotoSource. */
    fun write(raw: File, source: File, metadata: RawMetadata) {
        val policy = metadata.developmentPolicy ?: return
        require(raw.length() == metadata.bytes)
        val bytes = JSONObject().put("schemaVersion", 1)
            .put("developmentPolicy", policy).put("sourceKey", source.name)
            .put("sourceBytes", source.length()).put("sourceModified", source.lastModified())
            .put("rawModified", raw.lastModified()).put("format", metadata.format.name)
            .put("width", metadata.width).put("height", metadata.height)
            .put("outputBytes", metadata.bytes).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_RECORD_BYTES)
        FileChannel.open(recordFile(raw).toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    /** Only the owner of the spool may delete it and its matching record. */
    fun delete(raw: File) {
        recordFile(raw).delete()
        raw.delete()
    }
}
