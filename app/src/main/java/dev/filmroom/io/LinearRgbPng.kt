package dev.filmroom.io

import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.pow
import kotlin.math.roundToInt

/** SDR transfer at the file boundary, not a film effect or approximate image filter. */
object LinearRgb {
    val srgb8ToLinear: FloatArray = FloatArray(256) { code ->
        val s = code / 255.0
        (if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    fun toSrgb8(value: Float): Int {
        if (!value.isFinite()) throw IOException("Non-finite native RGB output")
        val linear = value.toDouble().coerceIn(0.0, 1.0)
        val encoded = if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1.0 / 2.4) - 0.055
        return (encoded * 255.0).roundToInt().coerceIn(0, 255)
    }

    fun byteCount(width: Int, height: Int): Long {
        require(PhotoLimits.validSource(width, height)) { "Invalid original dimensions" }
        return width.toLong() * height * 12L
    }
}

/**
 * Standards-compliant non-interlaced RGB8 PNG: IHDR, sRGB or matching iCCP, one zlib stream split
 * over bounded IDAT chunks, IEND. Optional bounded EXIF is supplied by the export pipeline.
 * Working data: one float row (width*12), one RGB row (width*3+1), 64KiB IDAT.
 * Input is packed RGB float32 little-endian, already oriented, in the selected linear RGB primaries.
 */
object LinearRgbPng {
    fun encode(
        input: InputStream,
        width: Int,
        height: Int,
        output: OutputStream,
        checkActive: () -> Unit = {},
        onRowEncoded: (Int) -> Unit = {},
        colorSpace: OutputColorSpace = OutputColorSpace.SRGB,
        iccProfile: ByteArray? = null,
        exifMetadata: ByteArray? = null,
        exportMillis: Long? = null,
    ) {
        LinearRgb.byteCount(width, height)
        checkActive()
        val target = DataOutputStream(output)
        target.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        val header = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        writeChunk(target, "IHDR", header, header.size)
        if(colorSpace==OutputColorSpace.SRGB) {
            // Standard PNG sRGB chunk identifies both primaries and transfer.
            require(iccProfile==null) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_86c64f79957e) }
            writeChunk(target, "sRGB", byteArrayOf(0), 1)
        } else {
            val profile=iccProfile ?: throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7e611ddedc63))
            colorSpace.validateProfile(profile)
            if(profile.size !in 128..65536 || String(profile,36,4,Charsets.US_ASCII)!="acsp" || String(profile,16,4,Charsets.US_ASCII)!="RGB ")throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7f988b809315))
            val packed=ByteArrayOutputStream()
            // ASCII profile keyword, NUL, compression method0, zlib profile.
            packed.write(colorSpace.id.toByteArray(Charsets.US_ASCII));packed.write(0);packed.write(0)
            DeflaterOutputStream(packed).use{it.write(profile)}
            val chunk=packed.toByteArray();writeChunk(target,"iCCP",chunk,chunk.size)
        }
        exifMetadata?.let {
            require(it.size in 8..60000)
            writeChunk(target,"eXIf",it,it.size)
        }
        exportMillis?.let {
            val time=java.time.Instant.ofEpochMilli(it).atOffset(java.time.ZoneOffset.UTC)
            val bytes=ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN).putShort(time.year.toShort())
                .put(time.monthValue.toByte()).put(time.dayOfMonth.toByte()).put(time.hour.toByte())
                .put(time.minute.toByte()).put(time.second.toByte()).array()
            writeChunk(target,"tIME",bytes,bytes.size)
        }
        val chunks = IdatChunks(target, checkActive)
        val deflater = Deflater(6, false)
        try {
            val compressed = DeflaterOutputStream(chunks, deflater, 64 * 1024)
            val floats = ByteArray(width * 12)
            val values = ByteBuffer.wrap(floats).order(ByteOrder.LITTLE_ENDIAN)
            val row = ByteArray(width * 3 + 1)
            row[0] = 0 // PNG filter None; no previous/full image allocation.
            repeat(height) { rowIndex ->
                checkActive()
                readFully(input, floats)
                values.position(0)
                for (component in 1 until row.size) row[component] = colorSpace.encode8(values.float).toByte()
                compressed.write(row)
                onRowEncoded(rowIndex + 1)
            }
            checkActive()
            if (input.read() != -1) throw IOException("Native RGB file contains trailing bytes")
            compressed.finish()
            chunks.finish()
            checkActive()
            writeChunk(target, "IEND", ByteArray(0), 0)
            target.flush()
        } finally {
            deflater.end()
        }
    }

    private fun readFully(input: InputStream, destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val count = input.read(destination, offset, destination.size - offset)
            if (count < 0) throw EOFException("Native RGB file is incomplete")
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) throw EOFException("Native RGB file is incomplete")
                destination[offset++] = byte.toByte()
            } else offset += count
        }
    }

    private fun writeChunk(output: DataOutputStream, name: String, bytes: ByteArray, length: Int) {
        val type = name.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(type)
        crc.update(bytes, 0, length)
        output.writeInt(length)
        output.write(type)
        output.write(bytes, 0, length)
        output.writeInt(crc.value.toInt())
    }

    private class IdatChunks(private val output: DataOutputStream, private val checkActive: () -> Unit) : OutputStream() {
        private val buffer = ByteArray(64 * 1024)
        private var used = 0
        override fun write(value: Int) {
            if (used == buffer.size) flushChunk()
            buffer[used++] = value.toByte()
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var source = offset
            var left = length
            while (left > 0) {
                if (used == buffer.size) flushChunk()
                val count = minOf(left, buffer.size - used)
                bytes.copyInto(buffer, used, source, source + count)
                used += count; source += count; left -= count
            }
        }
        fun finish() { if (used > 0) flushChunk() }
        private fun flushChunk() {
            checkActive()
            writeChunk(output, "IDAT", buffer, used)
            used = 0
        }
    }
}
