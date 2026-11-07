package dev.filmroom.codec

import java.io.File
import java.io.RandomAccessFile
import dev.filmroom.io.formats.PhotoHeaders

/** Resource hint only: native remains the sole format/variant authority.
 * Inspect at most a classic-TIFF root table (8 + 2 + 256*12 bytes), never payloads.
 * DNGVersion routes to the legacy bounded DNG parser even when malformed; no
 * RawLoader database can be initialized on that native path.
 */
internal object RawMetadataAdmission {
    const val CAMERA_BYTES = 32L * 1024 * 1024
    fun estimatedBytes(input: File): Long = try {
        RandomAccessFile(input, "r").use { file ->
            if (file.length() < 8) return@use 0L
            val header=ByteArray(minOf(file.length(), 16).toInt());file.readFully(header)
            // ORF/ORI, Canon CR3 and Fujifilm RAF can initialize the same camera metadata DB.
            // This is only conservative admission, not an assertion of decoder support.
            if(PhotoHeaders.isOlympusRaw(header) || PhotoHeaders.isCanonCr3(header) || PhotoHeaders.isFujifilmRaf(header)) return@use CAMERA_BYTES
            val little=header[0]==0x49.toByte() && header[1]==0x49.toByte()
            val big=header[0]==0x4d.toByte() && header[1]==0x4d.toByte()
            if(!little && !big) return@use 0L
            fun u16(bytes:ByteArray,at:Int):Int {
                val a=bytes[at].toInt() and 255;val b=bytes[at+1].toInt() and 255
                return if(little) a or (b shl 8) else (a shl 8) or b
            }
            fun u32(bytes:ByteArray,at:Int):Long = if(little)
                u16(bytes,at).toLong() or (u16(bytes,at+2).toLong() shl 16)
                else (u16(bytes,at).toLong() shl 16) or u16(bytes,at+2).toLong()
            // RW2 uses Panasonic TIFF magic85; the same root bounds and shared camera lease apply.
            if(u16(header,2)!=42 && !(little && u16(header,2)==85)) return@use 0L
            val offset=u32(header,4)
            if(offset<8 || offset>file.length()-2) return@use 0L
            file.seek(offset);val countBytes=ByteArray(2);file.readFully(countBytes)
            val count=u16(countBytes,0)
            if(count>256 || offset+2+count.toLong()*12>file.length()) return@use 0L
            val table=ByteArray(count*12);file.readFully(table)
            if((0 until count).any { u16(table,it*12)==50706 }) 0L else CAMERA_BYTES
        }
    } catch (_:java.io.IOException) {
        // A later native read may succeed; reserve conservatively rather than
        // letting an I/O race turn a possible camera probe into an uncharged one.
        CAMERA_BYTES
    }
}
