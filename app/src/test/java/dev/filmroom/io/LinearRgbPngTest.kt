package dev.filmroom.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import org.junit.Assert.*
import org.junit.Test

class LinearRgbPngTest {
    @Test fun everySrgbCodeRoundTripsThroughFloatTransfer() {
        for (code in 0..255) assertEquals(code, LinearRgb.toSrgb8(LinearRgb.srgb8ToLinear[code]))
        assertEquals(0, LinearRgb.toSrgb8(-10f))
        assertEquals(255, LinearRgb.toSrgb8(10f))
    }

    @Test fun pngHasCorrectChunksChecksumsDimensionsAndUnfilteredPixels() {
        val codes = intArrayOf(255, 0, 0, 0, 255, 0, 0, 0, 255, 32, 127, 230)
        val source = ByteBuffer.allocate(codes.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        codes.forEach { source.putFloat(LinearRgb.srgb8ToLinear[it]) }
        val png = ByteArrayOutputStream()
        LinearRgbPng.encode(ByteArrayInputStream(source.array()), 2, 2, png)
        val input = DataInputStream(ByteArrayInputStream(png.toByteArray()))
        val signature = ByteArray(8).also { input.readFully(it) }
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), signature)
        val chunks = mutableListOf<String>()
        val idat = ByteArrayOutputStream()
        while (input.available() > 0) {
            val size = input.readInt()
            val type = ByteArray(4).also { input.readFully(it) }
            val data = ByteArray(size).also { input.readFully(it) }
            val actualCrc = input.readInt()
            val crc = CRC32().apply { update(type); update(data) }
            assertEquals(crc.value.toInt(), actualCrc)
            val name = type.toString(Charsets.US_ASCII)
            chunks += name
            when (name) {
                "IHDR" -> {
                    val header = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                    assertEquals(2, header.int); assertEquals(2, header.int)
                    assertEquals(8, header.get().toInt()); assertEquals(2, header.get().toInt())
                }
                "sRGB" -> assertArrayEquals(byteArrayOf(0), data)
                "IDAT" -> idat.write(data)
            }
        }
        assertEquals(listOf("IHDR", "sRGB", "IDAT", "IEND"), chunks)
        val rows = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).readBytes()
        assertArrayEquals(byteArrayOf(0, -1, 0, 0, 0, -1, 0, 0, 0, 0, -1, 32, 127, -26), rows)
    }

    @Test fun truncatedTrailingAndNonFiniteRawDataFailRatherThanExportingFakePixels() {
        for (bytes in listOf(ByteArray(11), ByteArray(13), ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array())) {
            try {
                LinearRgbPng.encode(ByteArrayInputStream(bytes), 1, 1, ByteArrayOutputStream())
                fail("Invalid native output must fail")
            } catch (_: IOException) { }
        }
    }

    @Test fun cancellationIsCheckedDuringRowsAndChunks() {
        var checks = 0
        try {
            LinearRgbPng.encode(ByteArrayInputStream(ByteArray(10 * 10 * 12)), 10, 10, ByteArrayOutputStream(), checkActive = {
                if (++checks >= 4) throw InterruptedException("cancel")
            })
            fail("Expected cancellation")
        } catch (_: InterruptedException) { assertTrue(checks >= 4) }
    }

    @Test fun allExifMappingsHaveExpectedAsymmetricLayout() {
        val raw = arrayOf(charArrayOf('A', 'B', 'C'), charArrayOf('D', 'E', 'F'))
        val expected = listOf("ABC/DEF", "CBA/FED", "FED/CBA", "DEF/ABC", "AD/BE/CF", "DA/EB/FC", "FC/EB/DA", "CF/BE/AD")
        for (orientation in 1..8) {
            val mapping = ExifMapping(3, 2, orientation)
            val result = (0 until mapping.height).joinToString("/") { y ->
                (0 until mapping.width).map { x -> raw[mapping.rawY(x, y)][mapping.rawX(x, y)] }.joinToString("")
            }
            assertEquals("EXIF $orientation", expected[orientation - 1], result)
        }
    }

    @Test fun streamsThirteenMegapixelsUnderASmallHeapWithoutAllocatingTheImage() {
        var remaining = 5000L * 2600 * 12
        val zero = object : InputStream() {
            override fun read(): Int = if (remaining > 0) { remaining--; 0 } else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
                remaining -= count
                return count
            }
        }
        val encoded = ByteArrayOutputStream()
        LinearRgbPng.encode(zero, 5000, 2600, encoded)
        assertEquals(0L, remaining)
        val header = ByteBuffer.wrap(encoded.toByteArray()).order(ByteOrder.BIG_ENDIAN)
        header.position(16)
        assertEquals(5000, header.int)
        assertEquals(2600, header.int)
        assertTrue(encoded.size() > 0)
    }

    @Test fun nominal50MegapixelOutputFailureStopsReadingWithoutInventingSuccess() {
        val width = 8192
        val height = 6144
        var consumed = 0L
        var state = 0x12345678
        val row = ByteBuffer.allocate(width * 12).order(ByteOrder.LITTLE_ENDIAN)
        var rowOffset = row.capacity()
        val source = object : InputStream() {
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (consumed >= width.toLong() * height * 12) return -1
                if (rowOffset == row.capacity()) {
                    row.clear()
                    repeat(width * 3) {
                        state = state xor (state shl 13); state = state xor (state ushr 17); state = state xor (state shl 5)
                        row.putFloat(LinearRgb.srgb8ToLinear[state and 255])
                    }
                    rowOffset = 0
                }
                val count = minOf(length, row.capacity() - rowOffset)
                row.array().copyInto(bytes, offset, rowOffset, rowOffset + count)
                rowOffset += count; consumed += count
                return count
            }
        }
        var written = 0
        val failure = object : OutputStream() {
            override fun write(value: Int) { if (++written > 32_768) throw IOException("synthetic output failure") }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (written + length > 32_768) throw IOException("synthetic output failure")
                written += length
            }
        }
        try { LinearRgbPng.encode(source, width, height, failure); fail("Output failure must propagate") }
        catch (e: IOException) { assertEquals("synthetic output failure", e.message) }
        assertTrue("Streaming failure must stop well before consuming the original", consumed < 2L * 1024 * 1024)
    }
}
