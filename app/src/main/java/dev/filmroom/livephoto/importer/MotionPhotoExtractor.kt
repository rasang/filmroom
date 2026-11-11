package dev.filmroom.livephoto.importer

import dev.filmroom.livephoto.core.Jpeg
import dev.filmroom.livephoto.core.Mp4
import dev.filmroom.livephoto.core.VideoRange
import dev.filmroom.livephoto.core.Vivo
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

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
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { null }
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

    /** A structurally validated vendor pairing hint, never a filename-based association. */
    data class PairBinding(val id: String, val version: Long)

    /** Returns a binding only for a JPEG with a complete, terminal supported pair record. */
    fun inspectPairImage(image: File, checkActive: () -> Unit = {}): PairBinding? {
        checkActive()
        return try {
            if (!image.isFile || image.length() !in 4..MAX_INPUT_BYTES) return null
            RandomAccessFile(image, "r").use { f ->
                val bytes = ByteArray(minOf(f.length(), MAX_JPEG_BYTES.toLong()).toInt()).also(f::readFully)
                checkActive()
                pairImageBinding(f, Jpeg.inspect(bytes).end.toLong())
            }
        } catch (e: CancellationException) { throw e }
          catch (_: Exception) { null }
    }

    private fun pairImageBinding(file: RandomAccessFile, jpegEnd: Long): PairBinding? {
        val length = file.length() - jpegEnd
        if (length !in 64..16384) return null
        file.seek(jpegEnd)
        return nativeBinding(ByteArray(length.toInt()).also(file::readFully))
            .takeIf { it.version in setOf(2014L, 2104L, 2200L) }
    }

    private fun inspectPair(image: File, jpegEnd: Long, video: File): MotionPhotoSource? = try {
        if (!video.isFile || video.length() !in 16..MAX_INPUT_BYTES) null
        else {
            val binding = RandomAccessFile(image, "r").use { pairImageBinding(it, jpegEnd) }
            if (binding == null) null else {
                val boxes = Mp4.validate(video)
                val box = boxes.last()
                if (box.type != "uuid" || box.header != 8 || box.size !in 64..16408) null
                else RandomAccessFile(video, "r").use { f ->
                    // A previous vendor extension would make the returned media ambiguous.
                    val hasEarlierBinding = boxes.dropLast(1).any { previous ->
                        if (previous.type != "uuid" || previous.size - previous.header < 16) false
                        else {
                            f.seek(previous.start + previous.header)
                            ByteArray(16).also(f::readFully).contentEquals(Vivo.uuidType)
                        }
                    }
                    if (hasEarlierBinding) return@use null
                    f.seek(box.start + 8)
                    if (!ByteArray(16).also(f::readFully).contentEquals(Vivo.uuidType)) null
                    else {
                        val videoBinding = nativeBinding(ByteArray((box.size - 24).toInt()).also(f::readFully))
                        val matchingVersions = when (binding.version) {
                            2014L -> videoBinding.version == 2014L
                            2104L -> videoBinding.version == 2104L
                            2200L -> videoBinding.version == 2016L
                            else -> false
                        }
                        if (binding.id != videoBinding.id || !matchingVersions) null
                        else {
                            // The extension is not media; validate the returned range independently.
                            Mp4.validate(video, VideoRange(0, box.start))
                            MotionPhotoSource(video, 0, box.start, "vivo-paired")
                        }
                    }
                }
            }
        }
    } catch (_: Exception) { null }

    private fun nativeBinding(tail: ByteArray): PairBinding {
        val parsed = Vivo.parse(tail)
        val version = parsed.fields["version"] as? Long ?: error("Missing vivo version")
        require(version in setOf(2014L, 2104L, 2200L, 2016L))
        return PairBinding(parsed.id, version)
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
