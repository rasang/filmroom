package dev.filmroom.livephoto.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

internal object Vivo {
    const val ID_KEY = "com.android.camera.livephoto"
    val uuidType = ascii("vivoMediaExtInfo")
    private val album = ascii("cameralbum!")
    private val signature = byteArrayOf(0x1b, 0x2a, 0x39, 0x48, 0x57, 0x66, 0x75, 0x84.toByte(), 0x93.toByte(), 0xa2.toByte(), 0xb3.toByte())
    fun metadata(id: String, profile: LivePhotoProfile, video: Boolean, frame: Int): LinkedHashMap<String, Any> {
        demand(id.matches(Regex("[0-9a-f]{28}")), ErrorCode.INVALID_REQUEST, "Generated vivo binding ID must be 28 lowercase hex characters")
        return linkedMapOf<String, Any>().apply {
            if (profile == LivePhotoProfile.VIVO_PAIR_2104) {
                if (video) put("com.android.camera.imageTime", frame.toLong())
                put(ID_KEY, id); put("version", 2104L)
            } else {
                if (!video) {
                    put("com.vivo.gallery.livephoto.source", 4L)
                    put("com.vivo.gallery.livePhoto.rotationOffset", 0L)
                    put("com.vivo.gallery.livePhoto.rotationCheck", 3L)
                }
                put(ID_KEY, id); put("version", if (video) 2016L else 2200L)
                if (video) put("com.vivo.gallery.livePhoto.newCoverTime", 0L)
            }
        }
    }
    fun tail(meta: Map<String, Any>): ByteArray {
        val id = meta[ID_KEY] as? String ?: throw LivePhotoException(ErrorCode.INVALID_METADATA, "Missing pair ID")
        val encodedId = ascii(id)
        val raw = FlatJson.encode(meta).toByteArray(Charsets.UTF_8)
        return ascii("vivo") + raw + be32(raw.size.toLong()) + album + be32(encodedId.size + 19L) + encodedId + ByteArray(4) { 0xff.toByte() } + signature
    }
    fun uuid(tail: ByteArray): ByteArray = be32(tail.size + 24L) + ascii("uuid") + uuidType + tail
    data class Binding(val id: String, val fields: Map<String, Any>)
    /** Delimiter location comes from parsing JSON, never searching for cameralbum! inside arbitrary text. */
    fun parse(tail: ByteArray): Binding {
        demand(tail.size in 40..16384 && tail.startsAt(ascii("vivo{")) && tail.startsAt(signature, tail.size - signature.size), ErrorCode.INVALID_METADATA, "Invalid vivo tail framing")
        var p = 4; var inString = false; var escaped = false; var jsonEnd = -1
        while (p < tail.size) {
            val c = tail[p].toInt() and 255
            if (inString) { if (escaped) escaped = false else if (c == 92) escaped = true else if (c == 34) inString = false }
            else { if (c == 34) inString = true else if (c == 125) { jsonEnd = p + 1; break } else if ((c == 123 && p != 4) || c == 91) throw LivePhotoException(ErrorCode.INVALID_METADATA, "Nested vivo JSON unsupported") }
            p++
        }
        demand(jsonEnd > 4, ErrorCode.INVALID_METADATA, "Missing JSON end")
        val raw = tail.copyOfRange(4, jsonEnd)
        val json = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() } catch (e: Exception) { throw LivePhotoException(ErrorCode.INVALID_METADATA, "Invalid UTF-8", e) }
        val fields = FlatJson.parse(json)
        p = jsonEnd
        demand(u32(tail, p) == raw.size.toLong(), ErrorCode.INVALID_METADATA, "Wrong JSON length"); p += 4
        demand(tail.startsAt(album, p), ErrorCode.INVALID_METADATA, "Missing album delimiter"); p += album.size
        val n = u32(tail, p)
        demand(n >= 20 && n == (tail.size - p).toLong(), ErrorCode.INVALID_METADATA, "Wrong binding-record length")
        val idEnd = tail.size - 15
        demand(idEnd > p + 4 && tail.sliceArray(idEnd until idEnd + 4).all { it == 0xff.toByte() }, ErrorCode.INVALID_METADATA, "Missing binding separator")
        val id = tail.copyOfRange(p + 4, idEnd).toString(Charsets.US_ASCII)
        demand(id.matches(Regex("[0-9a-f]{28}")) && fields[ID_KEY] == id, ErrorCode.INVALID_METADATA, "Pair ID malformed or conflicting")
        return Binding(id, fields)
    }
}

/** Intentionally small parser for this SDK's flat string/integer metadata schema; duplicate keys fail. */
internal object FlatJson {
    private fun quoted(s: String): String = "\"" + buildString { for (c in s) append(when(c) { '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; else -> if (c < ' ') "\\u%04x".format(c.code) else c.toString() }) } + "\""
    fun encode(map: Map<String, Any>): String = map.entries.joinToString(",", "{", "}") { (k,v) -> quoted(k) + ":" + when(v) { is String -> quoted(v); is Number -> v.toLong().toString(); else -> error("Unsupported JSON value") } }
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
                        'u' -> { demand(p + 4 <= s.length, ErrorCode.INVALID_METADATA, "Short unicode escape"); val n = s.substring(p,p+4).toIntOrNull(16); demand(n != null, ErrorCode.INVALID_METADATA, "Bad unicode escape"); out.append(n!!.toChar()); p += 4 }
                        else -> throw LivePhotoException(ErrorCode.INVALID_METADATA, "Unknown JSON escape")
                    }
                }
            }
            throw LivePhotoException(ErrorCode.INVALID_METADATA, "Unterminated JSON string")
        }
        val out = linkedMapOf<String, Any>(); token('{'); ws()
        if (p < s.length && s[p] == '}') p++ else while (true) {
            val key = string(); token(':'); ws()
            val value: Any = if (p < s.length && s[p] == '"') string() else {
                val start = p
                if (p < s.length && s[p] == '-') p++
                while (p < s.length && s[p] in '0'..'9') p++
                val number = s.substring(start,p)
                demand(number.matches(Regex("-?(0|[1-9][0-9]*)")), ErrorCode.INVALID_METADATA, "JSON value must be an integer")
                number.toLongOrNull() ?: throw LivePhotoException(ErrorCode.INVALID_METADATA, "JSON number overflow")
            }
            demand(!out.containsKey(key), ErrorCode.INVALID_METADATA, "Duplicate JSON key")
            out[key] = value; demand(out.size <= 32, ErrorCode.RESOURCE_LIMIT, "Too many JSON fields"); ws()
            demand(p < s.length, ErrorCode.INVALID_METADATA, "Unterminated JSON object")
            if (s[p] == '}') { p++; break }; token(',')
        }
        ws(); demand(p == s.length, ErrorCode.INVALID_METADATA, "Trailing JSON data"); return out
    }
}

internal object Samsung {
    data class Suffix(val prefix: ByteArray, val suffix: ByteArray)
    private fun recordHeader(id: Int, name: String) = byteArrayOf(0, 0, (id and 255).toByte(), (id ushr 8).toByte()) + le32(name.length.toLong()) + ascii(name)
    fun plan(videoLength: Long, modern: Boolean): Suffix {
        val prefix = recordHeader(0xa30, "MotionPhoto_Data")
        val version = if (modern) recordHeader(0xa31, "MotionPhoto_Version") + ascii("mpv3") else byteArrayOf()
        val count = if (modern) 2 else 1; val firstSize = prefix.size + videoLength
        val index = bytes {
            write(ascii("SEFH")); write(le32(if (modern) 107 else 106)); write(le32(count.toLong()))
            write(byteArrayOf(0,0,0x30,0x0a)); write(le32(firstSize + version.size)); write(le32(firstSize))
            if (modern) { write(byteArrayOf(0,0,0x31,0x0a)); write(le32(version.size.toLong())); write(le32(version.size.toLong())) }
            write(le32(12L + 12L * count)); write(ascii("SEFT"))
        }
        return Suffix(prefix, version + index)
    }
    data class Layout(val range: VideoRange, val firstRecord: Long, val extent: Long)
    fun inspect(file: File, modern: Boolean): Layout = RandomAccessFile(file, "r").use { f ->
        val n = f.length(); demand(n >= 56, ErrorCode.INVALID_METADATA, "Short SEF file")
        val end = f.readAt(n - 8, 8)
        demand(end.startsAt(ascii("SEFT"), 4), ErrorCode.INVALID_METADATA, "Missing SEFT")
        val l = u32(end, 0, true); demand(l in 24..4096 && l <= n - 8, ErrorCode.INVALID_METADATA, "Invalid SEF index length")
        val h = n - 8 - l; val index = f.readAt(h, l.toInt())
        demand(index.startsAt(ascii("SEFH")) && u32(index, 4, true) == (if(modern)107L else 106L), ErrorCode.INVALID_METADATA, "SEF version mismatch")
        val count = u32(index, 8, true).toInt()
        demand(count == (if(modern)2 else 1) && l == 12L + 12L * count, ErrorCode.INVALID_METADATA, "Unexpected SEF records")
        var range: VideoRange? = null; var firstRecord = -1L; val extents = mutableListOf<LongRange>()
        for (i in 0 until count) {
            val p = 12 + i * 12
            val id = u32(index, p, true) ushr 16
            val back = u32(index, p + 4, true); val size = u32(index, p + 8, true)
            demand(back >= size && back <= h && size >= 8, ErrorCode.INVALID_METADATA, "SEF record out of bounds")
            val start = h - back; val recHead = f.readAt(start, 8)
            demand(u32(recHead, 0, true) == u32(index, p, true), ErrorCode.INVALID_METADATA, "SEF index/record tag mismatch")
            val nameLen = u32(recHead,4,true)
            demand(nameLen in 1..64 && nameLen <= size - 8, ErrorCode.INVALID_METADATA, "Invalid SEF record name")
            val name = f.readAt(start + 8, nameLen.toInt()).toString(Charsets.US_ASCII)
            val data = start + 8 + nameLen; val dataLength = size - 8 - nameLen
            extents += start until start + size
            if (id == 0xa30L && name == "MotionPhoto_Data") {
                demand(range == null && dataLength > 0, ErrorCode.INVALID_METADATA, "Duplicate/empty main video")
                range = VideoRange(data, dataLength); firstRecord = start
            } else if (modern && id == 0xa31L && name == "MotionPhoto_Version") {
                demand(dataLength == 4L && f.readAt(data,4).contentEquals(ascii("mpv3")), ErrorCode.INVALID_METADATA, "Bad mpv3 marker")
            } else throw LivePhotoException(ErrorCode.INVALID_METADATA, "Unexpected SEF record for this writer profile")
        }
        val sorted = extents.sortedBy { it.first }
        demand(sorted.zipWithNext().all { (a,b) -> a.last + 1 == b.first } && sorted.last().last + 1 == h, ErrorCode.INVALID_METADATA, "SEF record gaps or overlaps")
        val v = range ?: throw LivePhotoException(ErrorCode.INVALID_METADATA, "No MotionPhoto_Data")
        demand(firstRecord == sorted.first().first, ErrorCode.INVALID_METADATA, "Wrong SEF record order")
        Layout(v, firstRecord, n - v.offset)
    }
}

internal object Honor {
    fun tail(size: Long, cover: CoverInfo): ByteArray {
        val b = ByteArray(60) { 32 }
        fun put(p: Int, width: Int, value: String) { val v = ascii(value); demand(v.size <= width, ErrorCode.UNSUPPORTED_PROFILE, "Honor candidate field too narrow; choose an earlier cover or a different profile"); v.copyInto(b,p) }
        put(0,6,"v6_f${cover.frameIndex}")
        put(20,8,"${cover.frameIndex}:${cover.frameCount}")
        put(40,20,"LIVE_${size + 20}")
        return b
    }
}

