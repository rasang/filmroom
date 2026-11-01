package dev.filmroom.io.formats

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PhotoHeadersTest {
    @Test fun jpegPngAndOtherSignaturesAreHintsNeverValidation() {
        assertEquals(PhotoFormat.JPEG, PhotoHeaders.sniff(byteArrayOf(-1, -40, -1)).format)
        assertEquals(PhotoFormat.PNG, PhotoHeaders.sniff(png()).format)
        assertEquals(PhotoFormat.GIF, PhotoHeaders.sniff("GIF89a".toByteArray()).format)
        assertEquals(AnimationHint.UNKNOWN, PhotoHeaders.sniff("GIF89a".toByteArray()).animation)
        assertEquals(PhotoFormat.BMP, PhotoHeaders.sniff("BM".toByteArray() + ByteArray(12)).format)
        assertTrue(PhotoHeaders.sniff(png()).requiresFullDecoderValidation)
    }

    @Test fun pngDepthAndAnimationRemainUnknownUntilRelevantChunksExist() {
        val sixteen = png(16) + chunk("IDAT", ByteArray(0))
        assertEquals(16, PhotoHeaders.sniff(sixteen).bitDepthHint)
        assertEquals(AnimationHint.NOT_INDICATED, PhotoHeaders.sniff(sixteen).animation)
        assertEquals(AnimationHint.ANIMATION_OR_SEQUENCE,
            PhotoHeaders.sniff(png() + chunk("acTL", ByteArray(8))).animation)
        assertEquals(AnimationHint.UNKNOWN, PhotoHeaders.sniff(png()).animation)
        val giant = png() + byteArrayOf(-1, -1, -1, -1) + "tEXt".toByteArray() + "acTL".toByteArray()
        assertEquals(AnimationHint.UNKNOWN, PhotoHeaders.sniff(giant).animation)
        assertEquals(AnimationHint.UNKNOWN, PhotoHeaders.sniff(png() + chunk("acTL", ByteArray(8)).copyOf(12)).animation)
    }

    @Test fun webpRequiresRiffWebpAndReadsOnlyStructuralVp8xAnimationBit() {
        fun webp(flags: Int): ByteArray {
            val out = ByteArray(30)
            "RIFF".toByteArray().copyInto(out); out[4] = 22
            "WEBPVP8X".toByteArray().copyInto(out, 8); out[16] = 10; out[20] = flags.toByte()
            return out
        }
        assertEquals(PhotoFormat.WEBP, PhotoHeaders.sniff(webp(0)).format)
        assertEquals(AnimationHint.NOT_INDICATED, PhotoHeaders.sniff(webp(16)).animation)
        assertEquals(AnimationHint.ANIMATION_OR_SEQUENCE, PhotoHeaders.sniff(webp(2)).animation)
        assertEquals(AnimationHint.UNKNOWN, PhotoHeaders.sniff(webp(2).copyOf(20)).animation)
        assertEquals(PhotoFormat.UNKNOWN, PhotoHeaders.sniff("RIFF1234WAVEVP8X".toByteArray()).format)
    }

    @Test fun recognizesAvifAndHeifFromCompleteMajorOrCompatibleBrands() {
        assertEquals(PhotoFormat.AVIF, PhotoHeaders.sniff(ftyp("avif", "mif1")).format)
        assertEquals(PhotoFormat.AVIF, PhotoHeaders.sniff(ftyp("mif1", "avif")).format)
        assertEquals(PhotoFormat.HEIF, PhotoHeaders.sniff(ftyp("heic", "mif1")).format)
        assertEquals(Identification.CONTAINER_ONLY, PhotoHeaders.sniff(ftyp("mif1")).identification)
        assertEquals(AnimationHint.ANIMATION_OR_SEQUENCE, PhotoHeaders.sniff(ftyp("avif", "avis")).animation)
        assertEquals(AnimationHint.ANIMATION_OR_SEQUENCE, PhotoHeaders.sniff(ftyp("heic", "msf1")).animation)
        assertNull(PhotoHeaders.sniff(ftyp("heix")).bitDepthHint)
    }

    @Test fun bmffMinorVersionOrArbitraryTextCannotMasqueradeAsImageBrand() {
        val movie = ftyp("isom", "mp42")
        "avif".toByteArray().copyInto(movie, 12) // minor_version field, never a compatible brand.
        assertEquals(PhotoFormat.UNKNOWN, PhotoHeaders.sniff(movie).format)
        assertEquals(PhotoFormat.UNKNOWN, PhotoHeaders.sniff("hello ftyp avif heic image/jpeg".toByteArray()).format)
        assertEquals(PhotoFormat.UNKNOWN, PhotoHeaders.sniff(ftyp("isom") + ftyp("avif")).format)
    }

    @Test fun bmffSizeParsingHandlesExtendedLengthPaddingTruncationAndOverflow() {
        val ordinary = ftyp("avif", "avis")
        val extended = bytes { writeInt(1); writeBytes("ftyp"); writeLong(ordinary.size + 8L); write(ordinary, 8, ordinary.size - 8) }
        assertEquals(PhotoFormat.AVIF, PhotoHeaders.sniff(extended).format)
        assertEquals(PhotoFormat.AVIF, PhotoHeaders.sniff(bytes { writeInt(8); writeBytes("free") } + ordinary).format)
        assertEquals(PhotoFormat.UNKNOWN, PhotoHeaders.sniff(ordinary.copyOf(ordinary.size - 1)).format)
        assertEquals(PhotoFormat.UNKNOWN, PhotoHeaders.sniff(bytes { writeInt(1); writeBytes("ftyp"); writeLong(Long.MAX_VALUE) }).format)
        assertEquals(PhotoFormat.UNKNOWN, PhotoHeaders.sniff(bytes { writeInt(-1); writeBytes("ftyp"); writeBytes("avif") }).format)
        assertEquals(PhotoFormat.UNKNOWN, PhotoHeaders.sniff(bytes { writeInt(0); writeBytes("ftyp"); writeBytes("avif") }).format)
    }

    @Test fun tiffIsNotAssumedDngAndExrIsNotAssumedDecodable() {
        for (header in listOf(byteArrayOf(73,73,42,0), byteArrayOf(77,77,0,42), byteArrayOf(73,73,43,0))) {
            val result = PhotoHeaders.sniff(header)
            assertEquals(PhotoFormat.TIFF, result.format)
            assertEquals(Identification.CONTAINER_ONLY, result.identification)
            assertNotEquals(PhotoFormat.DNG, result.format)
        }
        val exr = PhotoHeaders.sniff(byteArrayOf(0x76, 0x2f, 0x31, 1, 2, 0, 0, 0))
        assertEquals(PhotoFormat.EXR, exr.format)
        assertEquals(Identification.CONTAINER_ONLY, exr.identification)
    }

    @Test fun scanIsBoundedAndAdversarialCountsOrRandomPrefixesNeverThrow() {
        val random = Random(718)
        repeat(5_000) {
            val b = random.nextBytes(random.nextInt(257))
            PhotoHeaders.sniff(b, listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE, b.size, 1).random(random))
        }
        assertEquals(PhotoFormat.UNKNOWN, PhotoHeaders.sniff(png(), -1).format)
        val padded = ByteArray(PhotoHeaders.MAX_PREFIX_BYTES + 100)
        assertEquals(PhotoHeaders.MAX_PREFIX_BYTES, PhotoHeaders.sniff(padded, Int.MAX_VALUE).inspectedBytes)
        assertEquals(PhotoFormat.UNKNOWN, PhotoHeaders.sniff(padded).format)
    }

    private fun png(depth: Int = 8): ByteArray = byteArrayOf(-119,80,78,71,13,10,26,10) +
        chunk("IHDR", bytes { writeInt(4_000); writeInt(3_000); writeByte(depth); writeByte(2); write(byteArrayOf(0,0,0)) })
    private fun chunk(type: String, data: ByteArray) = bytes { writeInt(data.size); writeBytes(type); write(data); writeInt(0) }
    private fun ftyp(major: String, vararg compatible: String) = bytes {
        writeInt(16 + compatible.size * 4); writeBytes("ftyp"); writeBytes(major); writeInt(0)
        compatible.forEach(::writeBytes)
    }
    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use { it.block() }
    }.toByteArray()
}
