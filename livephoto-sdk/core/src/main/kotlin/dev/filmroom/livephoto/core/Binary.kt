package dev.filmroom.livephoto.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
internal fun be32(n: Long): ByteArray { demand(n in 0..0xffffffffL, ErrorCode.RESOURCE_LIMIT, "uint32 overflow"); return ByteBuffer.allocate(4).putInt(n.toInt()).array() }
internal fun le32(n: Long): ByteArray = be32(n).reversedArray()
internal fun u16(b: ByteArray, p: Int): Int { demand(p >= 0 && p <= b.size - 2, ErrorCode.INVALID_METADATA, "Short uint16"); return ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255) }
internal fun u32(b: ByteArray, p: Int, little: Boolean = false): Long {
    demand(p >= 0 && p <= b.size - 4, ErrorCode.INVALID_METADATA, "Short uint32")
    return ByteBuffer.wrap(b, p, 4).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
}
internal fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { it.block() } }.toByteArray()
internal fun ByteArray.startsAt(prefix: ByteArray, at: Int = 0): Boolean = at >= 0 && at <= size - prefix.size && prefix.indices.all { this[at + it] == prefix[it] }
internal fun RandomAccessFile.readAt(offset: Long, count: Int): ByteArray {
    demand(offset >= 0 && count >= 0 && offset <= length() - count, ErrorCode.INVALID_METADATA, "Read out of bounds")
    seek(offset); return ByteArray(count).also { readFully(it) }
}
internal fun readBounded(file: File, max: Long): ByteArray {
    val n = file.length()
    demand(file.isFile && n in 1..max && n <= Int.MAX_VALUE, ErrorCode.RESOURCE_LIMIT, "File missing, empty or too large: ${file.name}")
    return FileInputStream(file).use { input ->
        val out = ByteArrayOutputStream(n.toInt()); val buf = ByteArray(SdkLimits.BUFFER_BYTES)
        while (true) { val count = input.read(buf); if (count < 0) break; demand(out.size().toLong() + count <= max, ErrorCode.RESOURCE_LIMIT, "File grew beyond limit"); out.write(buf, 0, count) }
        out.toByteArray()
    }
}
internal fun streamCopy(input: InputStream, out: OutputStream, expected: Long, checkActive: () -> Unit = {}) {
    var total = 0L; val buffer = ByteArray(SdkLimits.BUFFER_BYTES)
    while (true) {
        checkActive(); val count = input.read(buffer); if (count < 0) break
        demand(count.toLong() <= expected - total, ErrorCode.VERIFICATION_FAILED, "Input grew while copying")
        out.write(buffer, 0, count); total += count
    }
    demand(total == expected, ErrorCode.VERIFICATION_FAILED, "Input shrank while copying")
}
object FileDigests {
    @JvmStatic fun sha256(file: File, checkActive: () -> Unit = {}): String = FileInputStream(file).use { sha256(it, checkActive) }
    @JvmStatic fun sha256(input: InputStream, checkActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(SdkLimits.BUFFER_BYTES)
        while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @JvmStatic fun range(file: File, range: VideoRange, checkActive: () -> Unit = {}): String = RandomAccessFile(file, "r").use { input ->
        demand(range.offset >= 0 && range.length > 0 && range.offset <= input.length() - range.length, ErrorCode.INVALID_METADATA, "Invalid digest range")
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(SdkLimits.BUFFER_BYTES); var remaining = range.length
        input.seek(range.offset)
        while (remaining > 0) { checkActive(); val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (n < 0) throw EOFException(); digest.update(buffer, 0, n); remaining -= n }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
