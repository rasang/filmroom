package dev.filmroom.io

import kotlin.math.max
import kotlin.math.roundToInt

/** Small pure functions shared by Android IO and host unit tests. */
object PhotoLimits {
    // Nominal 50MP RGBA16 PNG can exceed 400MB. Copying remains streaming.
    const val MAX_COMPRESSED_BYTES: Long = 512L * 1024 * 1024
    const val IMPORT_RESERVE_BYTES: Long = 16L * 1024 * 1024
    const val IMPORT_SPACE_CHECK_BYTES: Long = 8L * 1024 * 1024
    const val MAX_SOURCE_EDGE: Int = 30_000
    const val MAX_SOURCE_PIXELS: Long = 150_000_000L
    const val PREVIEW_EDGE: Int = 1280
    const val EXPORT_EDGE: Int = 2560
    const val MAX_DECODE_EDGE: Int = 4096

    data class Dimensions(val width: Int, val height: Int)

    fun supportedMime(header: ByteArray, count: Int = header.size): String? {
        if (count >= 3 && header[0] == 0xff.toByte() &&
            header[1] == 0xd8.toByte() && header[2] == 0xff.toByte()) return "image/jpeg"
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (count >= png.size && png.indices.all { header[it] == png[it] }) return "image/png"
        return null
    }

    fun validSource(width: Int, height: Int): Boolean =
        width in 1..MAX_SOURCE_EDGE && height in 1..MAX_SOURCE_EDGE &&
            width.toLong() * height <= MAX_SOURCE_PIXELS

    /** Provider length is a preflight hint only; the live byte counter is authoritative. */
    fun importRequiredSpace(sizeHint: Long?): Long = IMPORT_RESERVE_BYTES +
        (sizeHint?.takeIf { it >= 0 }?.coerceAtMost(MAX_COMPRESSED_BYTES) ?: IMPORT_SPACE_CHECK_BYTES)

    fun hasImportSpace(availableBytes: Long, sizeHint: Long?): Boolean =
        availableBytes >= 0 && availableBytes >= importRequiredSpace(sizeHint)

    /** Recheck available space at least every8MiB, sooner if the reserve is near. */
    fun copyWindowBytes(availableBytes: Long): Long =
        if (availableBytes <= IMPORT_RESERVE_BYTES) 0 else minOf(availableBytes - IMPORT_RESERVE_BYTES, IMPORT_SPACE_CHECK_BYTES)

    fun acceptsCopiedBytes(copied: Long, nextBytes: Int): Boolean =
        copied in 0..MAX_COMPRESSED_BYTES && nextBytes >= 0 && nextBytes.toLong() <= MAX_COMPRESSED_BYTES - copied

    /** Never upsamples, never produces zero, and bounds the longest edge. */
    fun fit(width: Int, height: Int, maxEdge: Int): Dimensions {
        require(width > 0 && height > 0)
        require(maxEdge in 1..MAX_DECODE_EDGE)
        val largest = max(width, height)
        if (largest <= maxEdge) return Dimensions(width, height)
        val scale = maxEdge.toDouble() / largest
        return Dimensions(
            (width * scale).roundToInt().coerceIn(1, maxEdge),
            (height * scale).roundToInt().coerceIn(1, maxEdge),
        )
    }

    /** A provider display name is presentation text, never a filesystem path. */
    fun safeDisplayName(raw: String?, fallback: String = dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7b50017ae47e)): String =
        raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() }.trim().take(160).ifEmpty { fallback }
}
