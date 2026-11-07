package dev.filmroom.presets

import android.content.Context
import android.util.AtomicFile
import dev.filmroom.model.EditorState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Private, photo-independent snapshots. Never stored in the source-photo cache or export queue. */
internal class SavedPresetStore(private val file: File) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, "saved-presets.json"))

    data class Preset(val id: String, val name: String, val createdAt: Long, val snapshot: String) {
        // A newer parameter schema is kept verbatim, but is never partially applied by an old app.
        fun parameters(): EditorState {
            val json = JSONObject(snapshot)
            if (json.optInt("schemaVersion", 1) != 1) throw Failure(Problem.INCOMPATIBLE)
            validateNumbers(json)
            return try { EditorState.fromJson(snapshot) } catch (_: Exception) { throw Failure(Problem.INCOMPATIBLE) }
        }
    }
    enum class Problem { INVALID_NAME, DUPLICATE_NAME, FULL, UNREADABLE, INCOMPATIBLE, WRITE_FAILED, MISSING }
    class Failure(val problem: Problem) : Exception(problem.name)

    fun list(): List<Preset> = synchronized(lock) { read() }
    fun save(name: String, parameters: EditorState): Preset = synchronized(lock) {
        val clean = cleanName(name)
        val existing = read()
        if (existing.any { nameKey(it.name) == nameKey(clean) }) throw Failure(Problem.DUPLICATE_NAME)
        if (existing.size >= MAX_PRESETS) throw Failure(Problem.FULL)
        val item = Preset(UUID.randomUUID().toString(), clean, System.currentTimeMillis(), snapshot(parameters))
        // Catch serialization omissions instead of silently promising an incomplete preset.
        if (item.parameters() != parameters) throw Failure(Problem.INCOMPATIBLE)
        write(listOf(item) + existing)
        item
    }
    fun delete(id: String): Boolean = synchronized(lock) {
        val existing = read()
        if (existing.none { it.id == id }) return@synchronized false
        write(existing.filterNot { it.id == id })
        true
    }

    private fun read(): List<Preset> {
        val atomic = AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) return emptyList()
        try {
            val bytes = atomic.openRead().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (output.size() + count > MAX_BYTES) throw Failure(Problem.UNREADABLE)
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            if (root.getInt("version") != 1) throw Failure(Problem.INCOMPATIBLE)
            val rows = root.getJSONArray("presets")
            if (rows.length() > MAX_PRESETS) throw Failure(Problem.UNREADABLE)
            val result = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val snapshot = row.getString("parameters")
                if (row.getString("sha256") != digest(snapshot)) throw Failure(Problem.UNREADABLE)
                val id = row.getString("id")
                UUID.fromString(id)
                Preset(id, cleanName(row.getString("name")), row.getLong("createdAt"), snapshot)
            }
            if (result.map { it.id }.toSet().size != result.size || result.map { nameKey(it.name) }.toSet().size != result.size)
                throw Failure(Problem.UNREADABLE)
            return result
        } catch (error: Failure) { throw error }
        catch (_: Exception) { throw Failure(Problem.UNREADABLE) }
    }

    private fun write(presets: List<Preset>) {
        val rows = JSONArray()
        presets.forEach { item -> rows.put(JSONObject().put("id", item.id).put("name", item.name)
            .put("createdAt", item.createdAt).put("parameters", item.snapshot).put("sha256", digest(item.snapshot))) }
        val bytes = JSONObject().put("version", 1).put("presets", rows).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw Failure(Problem.FULL)
        val atomic = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        try {
            file.parentFile?.mkdirs()
            output = atomic.startWrite()
            output.write(bytes)
            atomic.finishWrite(output)
            output = null
            if (!atomic.readFully().contentEquals(bytes)) throw Failure(Problem.WRITE_FAILED)
        } catch (_: Exception) {
            output?.let { atomic.failWrite(it) }
            throw Failure(Problem.WRITE_FAILED)
        }
    }

    companion object {
        private val lock = Any()
        const val MAX_NAME_LENGTH = 60
        private const val MAX_PRESETS = 200
        private const val MAX_BYTES = 2 * 1024 * 1024
        // Keep latent orientation too, without changing engine/session/queue JSON identities.
        fun snapshot(parameters: EditorState): String = JSONObject(parameters.json())
            .put("mediumPortrait", parameters.mediumPortrait).toString()
        fun cleanName(name: String): String {
            val value = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
            if (value.isEmpty() || value.codePointCount(0, value.length) > MAX_NAME_LENGTH || value.any { Character.isISOControl(it) })
                throw Failure(Problem.INVALID_NAME)
            return value
        }
        private fun nameKey(name: String) = name.lowercase(Locale.ROOT)
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun validateNumbers(json: JSONObject) {
            for (key in json.keys()) when (val value = json.get(key)) {
                is Number -> if (!value.toDouble().isFinite() || !value.toFloat().isFinite()) throw Failure(Problem.INCOMPATIBLE)
                is JSONObject -> validateNumbers(value)
            }
        }
    }
}
