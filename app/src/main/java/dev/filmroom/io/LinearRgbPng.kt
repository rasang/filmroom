package dev.filmroom.io

import java.io.DataInputStream
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
        pixelPayload: OutputStream? = null,
    ) {
        LinearRgb.byteCount(width, height)
        checkActive()
        val target = DataOutputStream(output)
        // The optional sink sees only a complete metadata-free PNG; main output is unchanged.
        val pixels=DataOutputStream(object:OutputStream() {
            private var side=pixelPayload
            override fun write(value:Int)=write(byteArrayOf(value.toByte()),0,1)
            override fun write(bytes:ByteArray,offset:Int,length:Int) {
                target.write(bytes,offset,length)
                try {side?.write(bytes,offset,length)}
                catch(c:java.util.concurrent.CancellationException){throw c}
                catch(e:InterruptedException){throw e}
                catch(_:Exception){runCatching{side?.close()};side=null}
                catch(_:OutOfMemoryError){runCatching{side?.close()};side=null}
            }
        })
        pixels.write(SIGNATURE)
        val header = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        writeChunk(pixels, "IHDR", header, header.size)
        if(colorSpace==OutputColorSpace.SRGB) {
            // Standard PNG sRGB chunk identifies both primaries and transfer.
            require(iccProfile==null) { dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_86c64f79957e) }
            writeChunk(pixels, "sRGB", byteArrayOf(0), 1)
        } else {
            val profile=iccProfile ?: throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7e611ddedc63))
            colorSpace.validateProfile(profile)
            if(profile.size !in 128..65536 || String(profile,36,4,Charsets.US_ASCII)!="acsp" || String(profile,16,4,Charsets.US_ASCII)!="RGB ")throw IOException(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7f988b809315))
            val packed=ByteArrayOutputStream()
            // ASCII profile keyword, NUL, compression method0, zlib profile.
            packed.write(colorSpace.id.toByteArray(Charsets.US_ASCII));packed.write(0);packed.write(0)
            DeflaterOutputStream(packed).use{it.write(profile)}
            val chunk=packed.toByteArray();writeChunk(pixels,"iCCP",chunk,chunk.size)
        }
        writeMetadata(target,exifMetadata,exportMillis)
        val chunks = IdatChunks(pixels, checkActive)
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
            writeChunk(pixels, "IEND", ByteArray(0), 0)
            target.flush()
        } finally {
            deflater.end()
        }
    }

    private val SIGNATURE=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)

    private fun writeMetadata(target:DataOutputStream,exif:ByteArray?,millis:Long?) {
        exif?.let {require(it.size in 8..60000);writeChunk(target,"eXIf",it,it.size)}
        millis?.let {
            val time=java.time.Instant.ofEpochMilli(it).atOffset(java.time.ZoneOffset.UTC)
            val bytes=ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN).putShort(time.year.toShort())
                .put(time.monthValue.toByte()).put(time.dayOfMonth.toByte()).put(time.hour.toByte())
                .put(time.minute.toByte()).put(time.second.toByte()).array()
            writeChunk(target,"tIME",bytes,bytes.size)
        }
    }

    /** Accept ONLY our bounded metadata-free container. No previous EXIF/GPS/time/unknown
     * ancillary chunk can pass through. IDAT bytes and their original boundaries stay identical.
     * Exact content hashing is checked by the owner before this stream reaches MediaStore. */
    fun replayPixelPayload(input:InputStream,output:OutputStream,width:Int,height:Int,
        colorSpace:OutputColorSpace=OutputColorSpace.SRGB,iccProfile:ByteArray?=null,
        exifMetadata:ByteArray?=null,exportMillis:Long?=null,checkActive:()->Unit={}) {
        val source=DataInputStream(input);val target=DataOutputStream(output)
        val signature=ByteArray(8).also{source.readFully(it)}
        if(!signature.contentEquals(SIGNATURE))throw IOException("Invalid cached PNG signature")
        target.write(signature)
        var stage=0;var idats=0;var total=8L
        while(true) {
            checkActive()
            val size=source.readInt();val type=ByteArray(4).also{source.readFully(it)}
            if(size !in 0..(128*1024) || total+size+12>32L*1024*1024)throw IOException("Invalid cached PNG chunk size")
            val bytes=ByteArray(size).also{source.readFully(it)};val expectedCrc=source.readInt()
            val crc=CRC32().apply{update(type);update(bytes)}
            if(crc.value.toInt()!=expectedCrc)throw IOException("Invalid cached PNG checksum")
            val name=type.toString(Charsets.US_ASCII)
            when {
                stage==0 && name=="IHDR" -> {
                    val expected=ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
                    if(!bytes.contentEquals(expected))throw IOException("Incompatible cached PNG dimensions")
                    stage=1
                }
                stage==1 && name=="sRGB" && colorSpace==OutputColorSpace.SRGB -> {
                    if(iccProfile!=null || !bytes.contentEquals(byteArrayOf(0)))throw IOException("Incompatible cached PNG color")
                    stage=2
                }
                stage==1 && name=="iCCP" && colorSpace!=OutputColorSpace.SRGB -> {
                    val profile=iccProfile ?: throw IOException("Missing cached PNG profile")
                    colorSpace.validateProfile(profile)
                    val packed=ByteArrayOutputStream();packed.write(colorSpace.id.toByteArray(Charsets.US_ASCII));packed.write(0);packed.write(0)
                    DeflaterOutputStream(packed).use{it.write(profile)}
                    if(!bytes.contentEquals(packed.toByteArray()))throw IOException("Incompatible cached PNG profile")
                    stage=2
                }
                stage>=2 && name=="IDAT" -> {
                    if(size==0 || size>64*1024)throw IOException("Invalid cached PNG IDAT")
                    if(idats++==0)writeMetadata(target,exifMetadata,exportMillis)
                    stage=3
                }
                stage==3 && name=="IEND" && size==0 -> {
                    if(source.read()!=-1)throw IOException("Trailing cached PNG bytes")
                    writeChunk(target,name,bytes,size);target.flush();checkActive();return
                }
                else -> throw IOException("Unexpected cached PNG chunk")
            }
            writeChunk(target,name,bytes,size);total+=size+12
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
