package dev.filmroom.livephoto.importer

import dev.filmroom.livephoto.core.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Native camera imports have a richer schema than the deliberately strict export writer. */
internal object NativeVivoMetadata {
    const val MAX_TAIL = 16384
    private val signature = byteArrayOf(27,42,57,72,87,102,117,-124,-109,-94,-77)
    // Fixed trailer: JSON length(4), album(11), record length(4), ID(28), separator(4), signature(11).
    fun tailSize(suffix: ByteArray): Int {
        require(suffix.size >= 62)
        val p = suffix.size - 62
        require(suffix.copyOfRange(p + 4, p + 15).contentEquals("cameralbum!".toByteArray()))
        require(u32(suffix,p + 15) == 47L)
        require(suffix.copyOfRange(suffix.size - 11,suffix.size).contentEquals(signature))
        val length = u32(suffix,p)
        require(length in 2..(MAX_TAIL - 66).toLong())
        return length.toInt() + 66
    }
    fun parse(tail: ByteArray): MotionPhotoExtractor.PairBinding {
        require(tail.size <= MAX_TAIL && tailSize(tail) == tail.size)
        require(tail.copyOfRange(0,4).contentEquals("vivo".toByteArray()))
        val end = tail.size - 62
        val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(tail,4,end-4)).toString()
        val fields = NativeJson.parse(json)
        val id = tail.copyOfRange(tail.size - 43,tail.size - 15).toString(Charsets.US_ASCII)
        require(id.matches(Regex("[0-9a-f]{28}")) && fields[Vivo.ID_KEY] == id)
        require(tail.copyOfRange(tail.size - 15,tail.size - 11).all { it == (-1).toByte() })
        val version = fields["version"] as? Long ?: error("Missing integer version")
        require(version in setOf(2014L,2100L,2104L,2200L,2016L))
        return MotionPhotoExtractor.PairBinding(id,version)
    }
}

private object NativeJson {
    private object NullValue
    private data class DecimalValue(val raw: String)
    private val numberPattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    fun parse(s: String): Map<String, Any> {
        demand(s.length <= 16384, ErrorCode.RESOURCE_LIMIT, "JSON too large")
        var p = 0
        fun ws() { while (p < s.length && s[p] in " \r\n\t") p++ }
        fun token(c: Char) { ws(); demand(p < s.length && s[p++] == c, ErrorCode.INVALID_METADATA, "Invalid JSON delimiter") }
        fun string(): String {
            token('"'); val out = StringBuilder()
            while (p < s.length) {
                val c = s[p++]; if (c == '"') return out.toString()
                demand(c >= ' ', ErrorCode.INVALID_METADATA, "Unescaped JSON control character")
                if (c != '\\') out.append(c) else {
                    demand(p < s.length, ErrorCode.INVALID_METADATA, "Short JSON escape")
                    when(val e = s[p++]) {
                        '"', '\\', '/' -> out.append(e)
                        'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t'); 'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                        'u' -> { demand(p + 4 <= s.length, ErrorCode.INVALID_METADATA, "Short unicode escape"); val digits = s.substring(p,p+4); demand(digits.all { it in "0123456789abcdefABCDEF" }, ErrorCode.INVALID_METADATA, "Bad unicode escape"); val n = digits.toIntOrNull(16); demand(n != null, ErrorCode.INVALID_METADATA, "Bad unicode escape"); out.append(n!!.toChar()); p += 4 }
                        else -> throw LivePhotoException(ErrorCode.INVALID_METADATA, "Unknown JSON escape")
                    }
                }
            }
            throw LivePhotoException(ErrorCode.INVALID_METADATA, "Unterminated JSON string")
        }
        var values = 0
        fun value(depth: Int): Any {
            demand(depth <= 16 && ++values <= 1024, ErrorCode.RESOURCE_LIMIT, "JSON nesting or value limit")
            ws(); demand(p < s.length, ErrorCode.INVALID_METADATA, "Missing JSON value")
            return when (s[p]) {
                '"' -> string()
                '{' -> {
                    val out = linkedMapOf<String, Any>(); token('{'); ws()
                    if (p < s.length && s[p] == '}') p++ else while (true) {
                        val key = string(); token(':')
                        demand(!out.containsKey(key), ErrorCode.INVALID_METADATA, "Duplicate JSON key")
                        out[key] = value(depth + 1); ws()
                        demand(p < s.length, ErrorCode.INVALID_METADATA, "Unterminated object")
                        if (s[p] == '}') { p++; break }; token(',')
                    }
                    out
                }
                '[' -> {
                    val out = mutableListOf<Any>(); token('['); ws()
                    if (p < s.length && s[p] == ']') p++ else while (true) {
                        out += value(depth + 1); ws()
                        demand(p < s.length, ErrorCode.INVALID_METADATA, "Unterminated array")
                        if (s[p] == ']') { p++; break }; token(',')
                    }
                    out
                }
                't', 'f', 'n' -> {
                    val literal = when (s[p]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    demand(s.startsWith(literal, p), ErrorCode.INVALID_METADATA, "Invalid literal")
                    p += literal.length
                    when (literal) { "true" -> true; "false" -> false; else -> NullValue }
                }
                else -> {
                    val match = numberPattern.find(s, p)
                    demand(match != null && match.range.first == p, ErrorCode.INVALID_METADATA, "Invalid number")
                    val number = match!!.value; p += number.length
                    // Only exact JSON integers can be interpreted as the version.
                    if (number.none { it in ".eE" }) number.toLongOrNull()
                        ?: throw LivePhotoException(ErrorCode.INVALID_METADATA, "Integer overflow")
                    else DecimalValue(number)
                }
            }
        }
        val parsed = value(0)
        demand(parsed is Map<*, *>, ErrorCode.INVALID_METADATA, "Expected object")
        @Suppress("UNCHECKED_CAST") val out = parsed as Map<String, Any>
        ws(); demand(p == s.length, ErrorCode.INVALID_METADATA, "Trailing JSON data"); return out
    }
}
