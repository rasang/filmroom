package dev.filmroom.livephoto.importer

import dev.filmroom.livephoto.core.Jpeg
import dev.filmroom.livephoto.core.Mp4
import dev.filmroom.livephoto.core.VideoRange
import dev.filmroom.livephoto.core.Vivo
import java.io.File
import java.io.RandomAccessFile

// Reconstructed from the beta27 DEX; original comments and tests were not retained.
data class MotionPhotoSource(val file: File, val offset: Long, val length: Long, val kind: String)

object MotionPhotoExtractor {
    const val MAX_INPUT_BYTES = 134217728L
    private const val MAX_JPEG_BYTES = 33554432
    private val motionFlag = Regex("(?:[A-Za-z0-9_]+:)?(?:MotionPhoto|MicroVideo)\\s*=\\s*[\"']1[\"']")
    private val extent = Regex("(?:[A-Za-z0-9_]+:)?(?:MicroVideoOffset|Length)\\s*=\\s*[\"']([0-9]{1,12})[\"']")

    fun inspect(image: File, companion: File? = null, checkActive: () -> Unit = {}): MotionPhotoSource? {
        checkActive()
        if (!image.isFile || image.length() !in 4..MAX_INPUT_BYTES) return null
        val bytes = RandomAccessFile(image, "r").use { f ->
            ByteArray(minOf(f.length(), MAX_JPEG_BYTES.toLong()).toInt()).also(f::readFully)
        }
        return try {
            val layout = Jpeg.inspect(bytes)
            if (companion != null) return inspectPair(image, layout.end.toLong(), companion)
            val xmp = layout.segments.filter { it.marker == 0xe1 }.joinToString("\n") {
                bytes.copyOfRange(it.payload, it.end).toString(Charsets.ISO_8859_1)
            }
            if (!motionFlag.containsMatchIn(xmp)) return null
            val found = mutableListOf<MotionPhotoSource>()
            RandomAccessFile(image, "r").use { f ->
                val buffer = ByteArray(65543)
                var position = layout.end.toLong()
                var candidates = 0
                while (position < f.length()) {
                    checkActive()
                    f.seek(position)
                    val count = f.read(buffer)
                    if (count < 8) break
                    for (i in 4..count - 4) {
                        if (buffer[i] == 102.toByte() && buffer[i + 1] == 116.toByte() &&
                            buffer[i + 2] == 121.toByte() && buffer[i + 3] == 112.toByte()) {
                            if (++candidates > 32) return null
                            val start = position + i - 4
                            val length = movieLength(f, start) ?: continue
                            try {
                                Mp4.validate(image, VideoRange(start, length))
                                found += MotionPhotoSource(image, start, length, "jpeg-motion")
                            } catch (_: Exception) { }
                        }
                    }
                    position += count - 7
                }
            }
            val unique = found.distinctBy { it.offset }
            if (unique.size <= 1) return unique.singleOrNull()
            val offsets = extent.findAll(xmp).mapNotNull {
                it.groupValues[1].toLongOrNull()?.takeIf { n -> n > 0 && n <= image.length() }?.let { n -> image.length() - n }
            }.toSet()
            unique.filter { it.offset in offsets }.singleOrNull()
        } catch (_: Exception) { null }
    }

    private fun movieLength(f: RandomAccessFile, start: Long): Long? {
        val allowed = setOf("ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "sidx", "styp", "moof", "mfra")
        var p = start
        var moov = false
        var mdat = false
        var count = 0
        while (p <= f.length() - 8 && count++ < 10000) {
            f.seek(p)
            var size = f.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also(f::readFully).toString(Charsets.US_ASCII)
            if (type !in allowed || (p != start && type == "ftyp")) break
            if (p == start && type != "ftyp") return null
            val header = if (size == 1L) {
                if (p > f.length() - 16) return null
                size = f.readLong()
                16
            } else 8
            if (size < header || size > f.length() - p) return null
            if (type == "moov") {
                if (moov) break
                moov = true
            }
            if (type == "mdat" && size > header) mdat = true
            p += size
        }
        return if (moov && mdat && count < 10000) p - start else null
    }

    private fun inspectPair(image: File, jpegEnd: Long, video: File): MotionPhotoSource? = try {
        if (!video.isFile || video.length() !in 16..MAX_INPUT_BYTES || image.length() - jpegEnd < 40) null
        else {
            val binding = RandomAccessFile(image, "r").use { f ->
                val n = minOf(f.length() - jpegEnd, 16384).toInt()
                f.seek(f.length() - n)
                nativeBinding(ByteArray(n).also(f::readFully))
            }
            val box = Mp4.validate(video).last()
            if (box.type != "uuid" || box.header != 8 || box.size !in 64..16408) null
            else RandomAccessFile(video, "r").use { f ->
                f.seek(box.start + 8)
                if (!ByteArray(16).also(f::readFully).contentEquals(Vivo.uuidType)) null
                else {
                    val videoBinding = nativeBinding(ByteArray((box.size - 24).toInt()).also(f::readFully))
                    if (binding == videoBinding) MotionPhotoSource(video, 0, box.start, "vivo-paired") else null
                }
            }
        }
    } catch (_: Exception) { null }

    private fun nativeBinding(tail: ByteArray): String {
        require(tail.size in 64..16384)
        require(tail.takeLast(11).toByteArray().contentEquals(byteArrayOf(27, 42, 57, 72, 87, 102, 117, -124, -109, -94, -77)))
        require(tail.sliceArray(tail.size - 15 until tail.size - 11).all { it == (-1).toByte() })
        val id = tail.copyOfRange(tail.size - 43, tail.size - 15).toString(Charsets.US_ASCII)
        require(Regex("[0-9a-f]{28}").matches(id))
        fun uint(p: Int): Long {
            require(p >= 0 && p <= tail.size - 4)
            var value = 0L
            for (i in 0..3) value = (value shl 8) or (tail[p + i].toLong() and 255)
            return value
        }
        require(uint(tail.size - 47) == 47L)
        require(tail.copyOfRange(tail.size - 58, tail.size - 47).toString(Charsets.US_ASCII) == "cameralbum!")
        val end = tail.size - 62
        val length = uint(end)
        require(length in 2..16000)
        val start = end - length.toInt()
        require(start >= 4 && tail.copyOfRange(start - 4, start).toString(Charsets.US_ASCII) == "vivo")
        val json = tail.copyOfRange(start, end).toString(Charsets.UTF_8)
        require(json.startsWith("{") && json.endsWith("}"))
        val ids = Regex("\"com\\.android\\.camera\\.livephoto\"\\s*:\\s*\"([0-9a-f]{28})\"").findAll(json).toList()
        require(ids.size == 1 && ids.single().groupValues[1] == id)
        val versions = Regex("\"version\"\\s*:\\s*([0-9]+)").findAll(json).toList()
        require(versions.size == 1 && versions.single().groupValues[1] in setOf("2104", "2200", "2016"))
        return id
    }

    fun extract(source: MotionPhotoSource, output: File, checkActive: () -> Unit = {}): File {
        require(source.offset >= 0 && source.length in 16..MAX_INPUT_BYTES && source.offset <= source.file.length() - source.length)
        require(source.file.canonicalFile != output.canonicalFile)
        check(!output.exists()) { "Output already exists" }
        try {
            RandomAccessFile(source.file, "r").use { input ->
                input.seek(source.offset)
                output.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    var remaining = source.length
                    while (remaining > 0) {
                        checkActive()
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        check(n > 0) { "Source was truncated" }
                        out.write(buffer, 0, n)
                        remaining -= n
                    }
                }
            }
            return output
        } catch (t: Throwable) {
            output.delete()
            throw t
        }
    }
}
