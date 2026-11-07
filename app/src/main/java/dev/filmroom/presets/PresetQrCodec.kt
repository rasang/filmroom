package dev.filmroom.presets

import dev.filmroom.model.EditorState
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Offline, self-contained QR protocol. No URLs, IDs, photo metadata, or device identifiers. */
internal object PresetQrCodec {
    // V1 remains readable byte-for-byte. V2 changes transport compression, not parameter semantics.
    const val PREFIX = "FILMROOM:1:"
    const val COMPACT_PREFIX = "FILMROOM:2:"
    const val MAX_TEXT = 2200 // Legacy input limit is unchanged.
    const val MAX_SHARE_TEXT = 1800 // Alphanumeric H fits a single QR (version 40 capacity: 1852).
    const val MAX_JSON_BYTES = 32 * 1024
    enum class Problem { INVALID, INCOMPATIBLE, TOO_LARGE }
    class Failure(val problem: Problem) : Exception(problem.name)
    data class Draft(val name: String, val parameters: EditorState)

    fun encode(name: String, parameters: EditorState): String {
        val clean = SavedPresetStore.cleanName(name)
        require(Charsets.UTF_8.newEncoder().canEncode(clean))
        val snapshot = JSONObject(SavedPresetStore.snapshot(parameters))
        val checked = PresetQrParameters.read(snapshot)
        if (checked != parameters) throw Failure(Problem.INCOMPATIBLE)
        val bytes = PresetQrBinary.encode(clean, snapshot)
        // Verify the frozen schema cannot silently drop a field or round a parameter.
        if (PresetQrParameters.read(PresetQrBinary.decode(bytes).second, frozenNulls = true) != parameters) throw Failure(Problem.INCOMPATIBLE)
        val compressor = Deflater(9, true)
        val compressed = ByteArrayOutputStream()
        try {
            compressor.setInput(bytes); compressor.finish()
            val chunk = ByteArray(1024)
            while (!compressor.finished()) compressed.write(chunk, 0, compressor.deflate(chunk))
        } finally { compressor.end() }
        val packed = compressed.toByteArray()
        val data = MessageDigest.getInstance("SHA-256").digest(bytes) +
            if (packed.size < bytes.size) byteArrayOf(1) + packed else byteArrayOf(0) + bytes
        val text = COMPACT_PREFIX + PresetBase45.encode(data)
        if (text.length > MAX_SHARE_TEXT) throw Failure(Problem.TOO_LARGE)
        return text
    }

    fun decode(text: String): Draft = guarded {
        if (text.length > MAX_TEXT) throw Failure(Problem.TOO_LARGE)
        val compact = when {
            text.startsWith(COMPACT_PREFIX) -> true
            text.startsWith(PREFIX) -> false
            else -> throw Failure(if (text.startsWith("FILMROOM:")) Problem.INCOMPATIBLE else Problem.INVALID)
        }
        val prefix = if (compact) COMPACT_PREFIX else PREFIX
        // Base45 includes spaces. Do not trim or normalize valid encoded data.
        val body = text.substring(prefix.length)
        val data = if (compact) PresetBase45.decode(body) else {
            require(body.matches(Regex("[A-Za-z0-9_-]+")))
            Base64.getUrlDecoder().decode(body).also {
                require(Base64.getUrlEncoder().withoutPadding().encodeToString(it) == body)
            }
        }
        require(data.size > 32)
        if (compact) {
            require(data.size > 33)
            val bytes = when (data[32].toInt()) {
                0 -> data.copyOfRange(33, data.size)
                1 -> inflate(data, 33, raw = true)
                else -> throw Failure(Problem.INCOMPATIBLE)
            }
            require(MessageDigest.isEqual(data.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(bytes)))
            val (name, snapshot) = PresetQrBinary.decode(bytes)
            return@guarded Draft(SavedPresetStore.cleanName(name), PresetQrParameters.read(snapshot, frozenNulls = true))
        }
        val bytes = inflate(data, 32, raw = false)
        require(MessageDigest.isEqual(data.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(bytes)))
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val root = StrictPresetJson(json).read()
        if (root.opt("version") != 1) throw Failure(Problem.INCOMPATIBLE)
        require(root.keys().asSequence().toSet() == setOf("version", "name", "parameters"))
        require(root.get("name") is String && root.get("parameters") is JSONObject)
        Draft(SavedPresetStore.cleanName(root.getString("name")), PresetQrParameters.read(root.getJSONObject("parameters")))
    }

    private fun inflate(data: ByteArray, offset: Int, raw: Boolean): ByteArray {
        val inflater = Inflater(raw)
        val output = ByteArrayOutputStream()
        try {
            inflater.setInput(data, offset, data.size - offset)
            val chunk = ByteArray(1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(chunk)
                require(count > 0 || inflater.finished())
                if (output.size() + count > MAX_JSON_BYTES) throw Failure(Problem.TOO_LARGE)
                output.write(chunk, 0, count)
            }
            require(inflater.remaining == 0)
        } finally { inflater.end() }
        return output.toByteArray()
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (error: Failure) { throw error }
    catch (_: Exception) { throw Failure(Problem.INVALID) }
}

/** Strict bounded JSON subset: objects, primitive scalars, no arrays/duplicate keys/trailing data. */
internal class StrictPresetJson(private val text: String) {
    private var at = 0
    private fun space() { while (at < text.length && text[at] in " \r\n\t") at++ }
    private fun take(char: Char) { space(); require(at < text.length && text[at++] == char) }
    fun read(): JSONObject { val result = obj(0); space(); require(at == text.length); return result }
    private fun obj(depth: Int): JSONObject {
        require(depth < 8); take('{'); space()
        val result = JSONObject()
        if (at < text.length && text[at] == '}') { at++; return result }
        while (true) {
            val key = string(); require(key.length <= 80 && !result.has(key)); take(':'); space()
            val value: Any = when (text.getOrNull(at)) {
                '{' -> obj(depth + 1)
                '"' -> string()
                else -> {
                    val start = at
                    while (at < text.length && text[at] !in ",} \r\n\t") at++
                    when (val token = text.substring(start, at)) {
                        "true" -> true
                        "false" -> false
                        "null" -> JSONObject.NULL
                        else -> {
                            require(token.length <= 40 && token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                            token.toIntOrNull() ?: token.toDouble().also { require(it.isFinite()) }
                        }
                    }
                }
            }
            result.put(key, value); space()
            if (text.getOrNull(at) == '}') { at++; return result }
            take(',')
        }
    }
    private fun string(): String {
        take('"'); val result = StringBuilder()
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') {
                val value = result.toString()
                // Reject isolated UTF-16 surrogates (including escaped ones).
                var i = 0
                while (i < value.length) {
                    if (Character.isHighSurrogate(value[i])) { require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1])); i += 2 }
                    else { require(!Character.isLowSurrogate(value[i])); i++ }
                }
                return value
            }
            require(c >= ' ')
            if (c != '\\') result.append(c) else {
                require(at < text.length)
                result.append(when (val escape = text[at++]) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> { require(at + 4 <= text.length); val hex = text.substring(at, at + 4); require(hex.all { it in "0123456789abcdefABCDEF" }); hex.toInt(16).toChar().also { at += 4 } }
                    else -> throw IllegalArgumentException()
                })
            }
            require(result.length <= 512)
        }
        throw IllegalArgumentException()
    }
}
