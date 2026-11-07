package dev.filmroom.presets

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Lossless fixed-slot delta. Unknown future fields are carried verbatim in a bounded extension. */
internal object PresetQrBinary {
    private val fields get() = PresetQrSchemaV2.fields
    private val known = PresetQrSchemaV2.fields.map { it.first }.toSet()
    private val strings get() = PresetQrSchemaV2.strings
    private fun baseline() = JSONObject(PresetQrSchemaV2.BASELINE).apply {
        // Explicit null sentinels prevent upgraded app defaults from filling these old slots.
        put("mediumFormat", JSONObject.NULL)
        getJSONObject("composition").put("targetAspect", JSONObject.NULL)
        getJSONObject("crop").getJSONObject("transform").put("targetAspect", JSONObject.NULL)
    }
    private fun value(root: JSONObject, path: String): Any {
        var node = root
        val parts = path.split('.')
        for (key in parts.dropLast(1)) node = node.optJSONObject(key) ?: return JSONObject.NULL
        return node.opt(parts.last()) ?: JSONObject.NULL
    }
    private fun put(root: JSONObject, path: String, value: Any) {
        val parts = path.split('.'); var node = root
        for (key in parts.dropLast(1)) node = node.getJSONObject(key)
        node.put(parts.last(), value)
    }
    private fun extras(input: JSONObject, prefix: String = ""): JSONObject = JSONObject().apply {
        for (key in input.keys()) {
            val path = prefix + key; val v = input.get(key)
            if (path in known) continue
            if (v is JSONObject) { val children = extras(v, "$path."); if (children.length() > 0) put(key, children) }
            else put(key, v)
        }
    }
    private fun addExtras(target: JSONObject, extra: JSONObject, prefix: String = "") {
        for (key in extra.keys()) {
            require('.' !in key)
            val path = prefix + key; require(path !in known)
            val v = extra.get(key)
            if (target.has(key)) {
                require(v is JSONObject && target.get(key) is JSONObject)
                addExtras(target.getJSONObject(key), v, "$path.")
            } else {
                // Recursively check even newly introduced containers against the frozen slots.
                if (v is JSONObject) {
                    val child = JSONObject(); addExtras(child, v, "$path."); target.put(key, child)
                } else target.put(key, v)
            }
        }
    }
    fun encode(name: String, snapshot: JSONObject): ByteArray {
        val base = baseline()
        val changes = fields.mapIndexedNotNull { index, (path, type) ->
            val bytes = Writer().apply { scalar(type, value(snapshot, path)) }.bytes()
            val old = Writer().apply { scalar(type, value(base, path)) }.bytes()
            if (bytes.contentEquals(old)) null else index to bytes
        }
        val extension = extras(snapshot).let { if (it.length() == 0) byteArrayOf() else it.toString().toByteArray(Charsets.UTF_8) }
        return Writer().apply {
            uint(1) // Fixed-slot schema. Do not silently reinterpret another schema.
            utf8(name); uint(changes.size)
            for ((index, bytes) in changes) { uint(index); raw(bytes) }
            uint(extension.size); raw(extension)
        }.bytes().also { require(it.size <= PresetQrCodec.MAX_JSON_BYTES) }
    }
    fun decode(bytes: ByteArray): Pair<String, JSONObject> {
        require(bytes.size <= PresetQrCodec.MAX_JSON_BYTES)
        val reader = Reader(bytes)
        if (reader.uint() != 1) throw PresetQrCodec.Failure(PresetQrCodec.Problem.INCOMPATIBLE)
        val name = reader.utf8(); val count = reader.uint(); require(count <= fields.size)
        val result = baseline(); var previous = -1
        repeat(count) {
            val id = reader.uint(); require(id > previous && id < fields.size); previous = id
            val (path, type) = fields[id]; put(result, path, reader.scalar(type))
        }
        val length = reader.uint()
        if (length > 0) addExtras(result, StrictPresetJson(reader.utf8(length)).read())
        require(reader.finished())
        return name to result
    }
    private class Writer {
        private val output = ByteArrayOutputStream()
        fun bytes() = output.toByteArray()
        fun raw(bytes: ByteArray) { output.write(bytes) }
        fun uint(value: Int) {
            require(value >= 0); var v = value
            while (v > 127) { output.write((v and 127) or 128); v = v ushr 7 }; output.write(v)
        }
        fun utf8(value: String) { require(Charsets.UTF_8.newEncoder().canEncode(value)); val b = value.toByteArray(Charsets.UTF_8); uint(b.size); raw(b) }
        fun scalar(type: Char, value: Any) { when (type) {
            'b' -> { require(value is Boolean); output.write(if (value) 1 else 0) }
            'f' -> { require(value is Number && value.toFloat().isFinite()); raw(ByteBuffer.allocate(4).putFloat(value.toFloat()).array()) }
            'D' -> { require(value is Number && value.toDouble().isFinite()); raw(ByteBuffer.allocate(8).putDouble(value.toDouble()).array()) }
            'd' -> { output.write(if (value === JSONObject.NULL) 0 else 1); if (value !== JSONObject.NULL) scalar('D', value) }
            'i' -> { require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() >= 0); uint(value.toInt()) }
            's' -> if (value === JSONObject.NULL) uint(0) else {
                require(value is String)
                val index = strings.indexOf(value)
                if (index >= 0) uint(index + 1) else {
                    require(Charsets.UTF_8.newEncoder().canEncode(value))
                    val data = value.toByteArray(Charsets.UTF_8); uint(strings.size + 1 + data.size); raw(data)
                }
            }
            else -> error("Frozen type")
        } }
    }
    private class Reader(private val data: ByteArray) {
        private var at = 0
        fun finished() = at == data.size
        private fun byte(): Int { require(at < data.size); return data[at++].toInt() and 255 }
        fun uint(): Int {
            var result = 0
            for (shift in 0..14 step 7) {
                val b = byte(); result = result or ((b and 127) shl shift)
                if (b < 128) { require(shift == 0 || b != 0); require(result <= PresetQrCodec.MAX_JSON_BYTES); return result }
            }
            error("Oversized integer")
        }
        private fun take(length: Int): ByteArray { require(length >= 0 && length <= data.size - at); return data.copyOfRange(at, at + length).also { at += length } }
        fun utf8(length: Int = uint()): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(take(length))).toString()
        fun scalar(type: Char): Any = when (type) {
            'b' -> byte().also { require(it in 0..1) } == 1
            'f' -> ByteBuffer.wrap(take(4)).float.also { require(it.isFinite()) }
            'D' -> ByteBuffer.wrap(take(8)).double.also { require(it.isFinite()) }
            'd' -> if (byte().also { require(it in 0..1) } == 0) JSONObject.NULL else scalar('D')
            'i' -> uint()
            's' -> uint().let { when { it == 0 -> JSONObject.NULL; it <= strings.size -> strings[it - 1]; else -> utf8(it - strings.size - 1) } }
            else -> error("Frozen type")
        }
    }
}
