package dev.filmroom.presets

import dev.filmroom.diagnostics.*
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

    fun list(): List<Preset> = RecipeLog.trace(RecipeAction.LIST) { log -> synchronized(lock) {
        log.at(RecipeStep.STORE_READ); read().also { log.end(Outcome.SUCCESS, count = it.size) }
    } }
    fun save(name: String, parameters: EditorState, correlation: Long = RecipeCorrelation.next()): Preset = RecipeLog.trace(RecipeAction.SAVE, correlation) { log -> synchronized(lock) {
        log.at(RecipeStep.VALIDATE)
        val clean = cleanName(name)
        log.at(RecipeStep.STORE_READ)
        val existing = read()
        if (existing.any { nameKey(it.name) == nameKey(clean) }) throw Failure(Problem.DUPLICATE_NAME)
        if (existing.size >= MAX_PRESETS) throw Failure(Problem.FULL)
        val item = Preset(UUID.randomUUID().toString(), clean, System.currentTimeMillis(), snapshot(parameters))
        // Catch serialization omissions instead of silently promising an incomplete preset.
        if (item.parameters() != parameters) throw Failure(Problem.INCOMPATIBLE)
        log.at(RecipeStep.STORE_WRITE)
        write(listOf(item) + existing)
        RecipeCorrelation.saved(item.id, correlation)
        log.end(Outcome.SUCCESS, count = existing.size + 1)
        item
    } }
    fun delete(id: String): Boolean = RecipeLog.trace(RecipeAction.DELETE) { log -> synchronized(lock) {
        log.at(RecipeStep.STORE_READ)
        val existing = read()
        if (existing.none { it.id == id }) { log.end(Outcome.FAILED, RecipeReason.MISSING); return@synchronized false }
        log.at(RecipeStep.STORE_WRITE)
        write(existing.filterNot { it.id == id })
        RecipeCorrelation.deleted(id)
        log.end(Outcome.SUCCESS, count = existing.size - 1)
        true
    } }

    /** Merge only: existing recipes are never replaced. One atomic write, retry-safe IDs. */
    fun mergeBackup(items: List<Preset>): Int = RecipeLog.trace(RecipeAction.MERGE) { log -> synchronized(lock) {
        log.at(RecipeStep.STORE_READ)
        val merged = read().toMutableList()
        var added = 0
        items.forEach { incoming ->
            val parameters = PresetQrParameters.read(JSONObject(incoming.snapshot))
            val normalized = snapshot(parameters)
            val id = UUID.nameUUIDFromBytes(("backup:" + incoming.id + ":" + normalized).toByteArray(Charsets.UTF_8)).toString()
            if (merged.any { it.id == id || (it.id == incoming.id && it.parameters() == parameters) }) return@forEach
            val base = cleanName(incoming.name)
            var name = base
            var suffix = 2
            while (merged.any { nameKey(it.name) == nameKey(name) }) {
                val cut = base.offsetByCodePoints(0, minOf(48, base.codePointCount(0, base.length)))
                name = base.substring(0, cut) + " (" + suffix++ + ")"
            }
            merged.add(Preset(id, name, incoming.createdAt, normalized))
            added++
        }
        if (merged.size > MAX_PRESETS) throw Failure(Problem.FULL)
        if (added > 0) { log.at(RecipeStep.STORE_WRITE); write(merged) }
        log.end(Outcome.SUCCESS, count = added)
        added
    } }

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
