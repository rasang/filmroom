package dev.filmroom.io.formats

/** Typed catalogue, not an assertion that a decoder or app route is installed. */
enum class PhotoFormat(private val label: () -> String, val platformMime: String?) {
    JPEG({"JPEG"}, "image/jpeg"), PNG({"PNG"}, "image/png"), WEBP({"WebP"}, "image/webp"),
    HEIF({"HEIF / HEIC"}, "image/heif"), AVIF({"AVIF"}, "image/avif"),
    GIF({"GIF"}, "image/gif"), BMP({"BMP"}, "image/bmp"),
    TIFF({"TIFF"}, null), DNG({"DNG"}, null), EXR({"OpenEXR"}, null),
    CAMERA_RAW({dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_f63450b5d12b)}, null), UNKNOWN({dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c65b3b97b411)}, null);
    val displayName:String get()=label()
}

enum class Identification { SIGNATURE_HINT, CONTAINER_ONLY, UNKNOWN }
enum class AnimationHint { NOT_INDICATED, ANIMATION_OR_SEQUENCE, UNKNOWN }

/** Untrusted header hints only. Never authorize decoding or allocate from these values. */
data class PhotoHeader(
    val format: PhotoFormat,
    val identification: Identification,
    val animation: AnimationHint = AnimationHint.UNKNOWN,
    val bitDepthHint: Int? = null,
    val inspectedBytes: Int,
) {
    val requiresFullDecoderValidation: Boolean get() = true
}

/** No filenames, extension/MIME guesses, EXIF values or arbitrary text enter the result. */
object PhotoHeaders {
    const val MAX_PREFIX_BYTES = 64 * 1024
    /** Container hints only. Native validates the complete structure and actual RAW encoding. */
    fun isOlympusRaw(prefix: ByteArray, count: Int = prefix.size): Boolean {
        val n = count.coerceIn(0, prefix.size)
        return ascii(prefix, 0, "IIRO", n) || ascii(prefix, 0, "IIRS", n) || ascii(prefix, 0, "MMOR", n)
    }
    fun isCanonCr3(prefix: ByteArray, count: Int = prefix.size): Boolean {
        val n = count.coerceIn(0, prefix.size)
        return n >= 12 && ascii(prefix, 4, "ftyp", n) && ascii(prefix, 8, "crx ", n)
    }
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun sniff(prefix: ByteArray, count: Int = prefix.size): PhotoHeader {
        val n = count.coerceIn(0, minOf(prefix.size, MAX_PREFIX_BYTES))
        fun result(f: PhotoFormat, a: AnimationHint = AnimationHint.UNKNOWN, depth: Int? = null,
                   identification: Identification = Identification.SIGNATURE_HINT) =
            PhotoHeader(f, identification, a, depth, n)
        if (n >= 3 && u8(prefix, 0) == 255 && u8(prefix, 1) == 216 && u8(prefix, 2) == 255)
            return result(PhotoFormat.JPEG, AnimationHint.NOT_INDICATED)
        if (n >= 8 && pngSignature.indices.all { prefix[it] == pngSignature[it] }) {
            val depth = if (n >= 33 && u32be(prefix, 8) == 13L && ascii(prefix, 12, "IHDR", n))
                u8(prefix, 24).takeIf { it in setOf(1, 2, 4, 8, 16) } else null
            var offset = 8
            var animation = AnimationHint.UNKNOWN
            // Skip complete bounded chunks. A large/truncated chunk means unknown, not still.
            while (offset <= n - 8) {
                val length = u32be(prefix, offset)
                if (ascii(prefix, offset + 4, "acTL", n) && length == 8L && offset.toLong() + 20 <= n) {
                    animation = AnimationHint.ANIMATION_OR_SEQUENCE
                    break
                }
                if (ascii(prefix, offset + 4, "IDAT", n)) {
                    animation = AnimationHint.NOT_INDICATED
                    break
                }
                val next = offset.toLong() + length + 12
                if (next > n || next <= offset) break
                offset = next.toInt()
            }
            return result(PhotoFormat.PNG, animation, depth)
        }
        if (n >= 12 && ascii(prefix, 0, "RIFF", n) && ascii(prefix, 8, "WEBP", n)) {
            val total = u32le(prefix, 4) + 8
            var animation = AnimationHint.UNKNOWN
            if (total >= 30 && n >= 30 && ascii(prefix, 12, "VP8X", n) && u32le(prefix, 16) == 10L) {
                animation = if (u8(prefix, 20) and 2 != 0) AnimationHint.ANIMATION_OR_SEQUENCE else AnimationHint.NOT_INDICATED
            } else if (total >= 20 && n >= 20 &&
                (ascii(prefix, 12, "VP8 ", n) || ascii(prefix, 12, "VP8L", n))) {
                animation = AnimationHint.NOT_INDICATED
            }
            return result(PhotoFormat.WEBP, animation, 8)
        }
        if (ascii(prefix, 0, "GIF87a", n) || ascii(prefix, 0, "GIF89a", n))
            return result(PhotoFormat.GIF) // A signature cannot distinguish one frame from many.
        if (n >= 14 && ascii(prefix, 0, "BM", n)) return result(PhotoFormat.BMP)
        if (isOlympusRaw(prefix, n) || isCanonCr3(prefix, n))
            return result(PhotoFormat.CAMERA_RAW, identification = Identification.CONTAINER_ONLY)
        if (n >= 4 && ascii(prefix, 0, "II", n) && u8(prefix, 2) == 0x55 && u8(prefix, 3) == 0)
            return result(PhotoFormat.CAMERA_RAW, identification = Identification.CONTAINER_ONLY)
        if (n >= 4 && ((ascii(prefix, 0, "II", n) && u8(prefix, 3) == 0 && u8(prefix, 2) in setOf(42, 43)) ||
                (ascii(prefix, 0, "MM", n) && u8(prefix, 2) == 0 && u8(prefix, 3) in setOf(42, 43))))
            // TIFF-based DNG and camera RAW must be identified by the real native parser later.
            return result(PhotoFormat.TIFF, identification = Identification.CONTAINER_ONLY)
        if (n >= 8 && u8(prefix, 0) == 0x76 && u8(prefix, 1) == 0x2f && u8(prefix, 2) == 0x31 && u8(prefix, 3) == 1)
            return result(PhotoFormat.EXR, identification = Identification.CONTAINER_ONLY)

        var offset = 0
        while (offset <= n - 8) {
            val size32 = u32be(prefix, offset)
            var headerBytes = 8
            val size = if (size32 == 1L) {
                if (offset > n - 16 || u32be(prefix, offset + 8) != 0L) break
                headerBytes = 16
                u32be(prefix, offset + 12)
            } else size32
            // Unknown-to-EOF or incomplete boxes are deliberately not guessed from major_brand.
            if (size < headerBytes || size > n.toLong() - offset) break
            val end = offset + size.toInt()
            if (ascii(prefix, offset + 4, "ftyp", n)) {
                val body = offset + headerBytes
                if (size < headerBytes + 8 || (size - headerBytes - 8) % 4 != 0L) break
                fun hasBrand(brand: String): Boolean {
                    if (ascii(prefix, body, brand, end)) return true
                    var at = body + 8 // minor_version is NOT a brand.
                    while (at <= end - 4) {
                        if (ascii(prefix, at, brand, end)) return true
                        at += 4
                    }
                    return false
                }
                val sequence = listOf("avis", "msf1", "hevc", "hevx", "hevm", "hevs").any(::hasBrand)
                val animation = if (sequence) AnimationHint.ANIMATION_OR_SEQUENCE else AnimationHint.UNKNOWN
                if (hasBrand("avif") || hasBrand("avis")) return result(PhotoFormat.AVIF, animation)
                if (listOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs").any(::hasBrand))
                    return result(PhotoFormat.HEIF, animation)
                if (hasBrand("mif1") || hasBrand("msf1"))
                    return result(PhotoFormat.HEIF, animation, identification = Identification.CONTAINER_ONLY)
                break // An unrelated MP4 ftyp is not an image merely because later bytes say avif.
            }
            if (!ascii(prefix, offset + 4, "free", n) && !ascii(prefix, offset + 4, "skip", n) &&
                !ascii(prefix, offset + 4, "wide", n)) break
            offset = end
        }
        return result(PhotoFormat.UNKNOWN, identification = Identification.UNKNOWN)
    }

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 255
    private fun u32be(b: ByteArray, at: Int): Long =
        (u8(b, at).toLong() shl 24) or (u8(b, at + 1).toLong() shl 16) or
            (u8(b, at + 2).toLong() shl 8) or u8(b, at + 3).toLong()
    private fun u32le(b: ByteArray, at: Int): Long =
        u8(b, at).toLong() or (u8(b, at + 1).toLong() shl 8) or
            (u8(b, at + 2).toLong() shl 16) or (u8(b, at + 3).toLong() shl 24)
    private fun ascii(b: ByteArray, at: Int, expected: String, limit: Int): Boolean =
        at >= 0 && at <= limit - expected.length && expected.indices.all { u8(b, at + it) == expected[it].code }
}
