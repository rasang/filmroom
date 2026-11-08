package dev.filmroom.backup

import dev.filmroom.presets.PresetQrParameters
import dev.filmroom.presets.SavedPresetStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** Bounded metadata ZIP. Media bytes and private app paths are never archived. */
internal object AppBackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    data class Document(val settings: JSONObject, val recipes: List<SavedPresetStore.Preset>, val history: JSONArray)
    fun read(input: InputStream): Document {
        val buffered = java.io.PushbackInputStream(input, 4)
        val prefix = ByteArray(4); var count = 0
        while(count < prefix.size) { val n=buffered.read(prefix,count,prefix.size-count); if(n < 0)break; if(n == 0)continue; count+=n }
        if (count > 0) buffered.unread(prefix, 0, count)
        if (count == 4 && prefix.contentEquals(byteArrayOf(80,75,3,4))) {
            return java.util.zip.ZipInputStream(buffered).use { zip ->
                val entry = zip.nextEntry ?: error("EMPTY_ARCHIVE")
                require(entry.name == "backup.json" && !entry.isDirectory) { "INVALID_ARCHIVE_ENTRY" }
                val document = readJson(zip)
                zip.closeEntry()
                require(zip.nextEntry == null) { "UNEXPECTED_ARCHIVE_ENTRY" }
                document
            }
        }
        return readJson(buffered)
    }
    private fun readJson(input: InputStream): Document {
        val bytes = input.readBytesBounded(MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return decode(strictJson(text))
    }
    fun decode(root: JSONObject): Document {
        require(root.opt("type") == "filmroom-app-backup" && root.opt("version") in setOf(1,2)) { "INCOMPATIBLE" }
        require(root.keys().asSequence().toSet() == setOf("type","version","settings","recipes","history")) { "INCOMPATIBLE" }
        val settings = root.getJSONObject("settings").also(BackupSettings::validate)
        val rows = root.getJSONArray("recipes")
        require(rows.length() <= 200)
        val recipes = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            require(row.keys().asSequence().toSet() == setOf("id","name","createdAt","parameters")) { "INCOMPATIBLE" }
            require(row.get("id") is String && row.get("name") is String)
            val id = row.getString("id").also { UUID.fromString(it) }
            val time = row.get("createdAt"); require(time is Number && time.toLong() >= 0 && time.toDouble() == time.toLong().toDouble())
            val state = PresetQrParameters.read(row.getJSONObject("parameters"))
            SavedPresetStore.Preset(id, SavedPresetStore.cleanName(row.getString("name")), time.toLong(), SavedPresetStore.snapshot(state))
        }
        require(recipes.map { it.id }.toSet().size == recipes.size)
        val history = root.getJSONArray("history")
        require(history.length() <= 10000)
        return Document(settings, recipes, history)
    }
    fun validateStocks(document: Document, profiles: JSONArray) {
        document.recipes.forEach { recipe ->
            require(dev.filmroom.model.StockSelectionValidation.isSupported(recipe.parameters(), profiles)) { "INCOMPATIBLE" }
        }
    }
    fun encode(document: Document): ByteArray {
        BackupSettings.validate(document.settings)
        val recipes = JSONArray()
        document.recipes.forEach { item ->
            // Strict parameter parser rejects unknown/path-shaped fields; reserialize only the model.
            val state = PresetQrParameters.read(JSONObject(item.snapshot))
            recipes.put(JSONObject().put("id",item.id).put("name",item.name).put("createdAt",item.createdAt)
                .put("parameters",JSONObject(SavedPresetStore.snapshot(state))))
        }
        val bytes = JSONObject().put("type","filmroom-app-backup").put("version",2)
            .put("settings",document.settings).put("recipes",recipes).put("history",document.history)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        return bytes
    }
    fun writeZip(document: Document, output: java.io.OutputStream) {
        java.util.zip.ZipOutputStream(output).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("backup.json"))
            zip.write(encode(document)); zip.closeEntry(); zip.finish()
        }
    }
    private fun strictJson(text: String): JSONObject {
        val reader = android.util.JsonReader(java.io.StringReader(text)).apply { isLenient = false }
        fun value(depth: Int): Any {
            require(depth < 12)
            return when(reader.peek()) {
                android.util.JsonToken.BEGIN_OBJECT -> JSONObject().apply {
                    reader.beginObject()
                    while(reader.hasNext()) { val key = reader.nextName(); require(key.length <= 80 && !has(key)); put(key,value(depth+1)) }
                    reader.endObject()
                }
                android.util.JsonToken.BEGIN_ARRAY -> JSONArray().apply {
                    reader.beginArray()
                    while(reader.hasNext()) { require(length() < 10000); put(value(depth+1)) }
                    reader.endArray()
                }
                android.util.JsonToken.STRING -> reader.nextString().also { require(it.length <= 4096 && Charsets.UTF_8.newEncoder().canEncode(it)) }
                android.util.JsonToken.BOOLEAN -> reader.nextBoolean()
                android.util.JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                android.util.JsonToken.NUMBER -> reader.nextString().let { token ->
                    require(token.length <= 40)
                    token.toIntOrNull() ?: token.toLongOrNull() ?: token.toDouble().also { require(it.isFinite()) }
                }
                else -> throw IllegalArgumentException("INVALID")
            }
        }
        return reader.use { val root = value(0) as JSONObject; require(it.peek() == android.util.JsonToken.END_DOCUMENT); root }
    }
    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while(true) { val n = read(buffer); if(n < 0) break; require(out.size()+n <= limit); out.write(buffer,0,n) }
        return out.toByteArray()
    }
}
