package dev.filmroom.codec

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Allocation hint only; native still validates the DNG and every codec parameter.
 * Classic TIFF only, with a valid root DNGVersion and Compression=52546 in its
 * root/next/SubIFD graph on a non-preview LinearRaw IFD. No suffix, maker string,
 * payload scan, or pixel decode.
 * All reads, table sizes and graph traversal are bounded; ambiguity fails closed.
 */
internal object JxlDngAdmission {
    private const val MAX_IFDS = 32
    private const val MAX_ENTRIES = 256
    private const val MAX_READ_BYTES = 64 * 1024

    fun matches(input: File): Boolean = try {
        RandomAccessFile(input, "r").use { file ->
            val length = file.length()
            var readBytes = 0
            fun read(at: Long, size: Int): ByteArray {
                if (at < 0 || size < 0 || at > length - size || size > MAX_READ_BYTES - readBytes)
                    throw IOException("Invalid TIFF admission bounds")
                readBytes += size
                return ByteArray(size).also { file.seek(at); file.readFully(it) }
            }
            val header = read(0, 8)
            val little = header[0] == 73.toByte() && header[1] == 73.toByte()
            if (!little && !(header[0] == 77.toByte() && header[1] == 77.toByte())) return@use false
            fun u16(b: ByteArray, at: Int): Int {
                val a = b[at].toInt() and 255; val c = b[at + 1].toInt() and 255
                return if (little) a or (c shl 8) else (a shl 8) or c
            }
            fun u32(b: ByteArray, at: Int): Long = if (little)
                u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)
            else (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
            if (u16(header, 2) != 42) return@use false
            val pending = java.util.ArrayDeque<Long>()
            val seen = mutableSetOf<Long>()
            pending.add(u32(header, 4))
            var root = true
            var jxl = false
            while (pending.isNotEmpty()) {
                val offset = pending.removeFirst()
                if (offset < 8 || !seen.add(offset) || seen.size > MAX_IFDS) return@use false
                val count = u16(read(offset, 2), 0)
                if (count > MAX_ENTRIES) return@use false
                val table = read(offset + 2, count * 12 + 4)
                var dngVersion = false
                var compression = 0
                var photometric = 0
                var subfileType = 0L
                val tags = mutableSetOf<Int>()
                for (i in 0 until count) {
                    val at = i * 12
                    val tag = u16(table, at)
                    if (!tags.add(tag)) return@use false
                    val type = u16(table, at + 2)
                    val n = u32(table, at + 4)
                    when (tag) {
                        50706 -> {
                            if (type != 1 || n != 4L || table[at + 8] != 1.toByte()) return@use false
                            dngVersion = true
                        }
                        259 -> {
                            if (type != 3 || n != 1L) return@use false
                            compression = u16(table, at + 8)
                        }
                        262 -> {
                            if (type != 3 || n != 1L) return@use false
                            photometric = u16(table, at + 8)
                        }
                        254 -> {
                            if (type != 4 || n != 1L) return@use false
                            subfileType = u32(table, at + 8)
                        }
                        330 -> {
                            if (type != 4 || n !in 1L..MAX_IFDS.toLong()) return@use false
                            val offsets = if (n == 1L) table.copyOfRange(at + 8, at + 12)
                                else read(u32(table, at + 8), n.toInt() * 4)
                            for (j in 0 until n.toInt()) pending.add(u32(offsets, j * 4))
                        }
                    }
                }
                if (compression == 52546 && photometric == 34892 && (subfileType and 1L) == 0L) jxl = true
                if (root && !dngVersion) return@use false
                root = false
                val next = u32(table, count * 12)
                if (next != 0L) pending.add(next)
                if (pending.size + seen.size > MAX_IFDS) return@use false
            }
            jxl
        }
    } catch (_: IOException) { false }
}
