package dev.filmroom.livephoto.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LivePhotoDatesRestoreTest {
    private fun dates()=LivePhotoDates("2020:02:29 12:34:56","+08:00","123456","2026:11:09 12:00:00","+00:00","007")
    @Test fun dateValidation() {
        dates()
        listOf("2021:02:29 12:34:56","0000:01:01 00:00:00","2020:2:29 12:34:56").forEach {
            assertTrue(runCatching{dates().copy(original=it)}.isFailure)
        }
        assertTrue(runCatching{dates().copy(originalOffset="+25:00")}.isFailure)
        assertTrue(runCatching{dates().copy(originalSubsec="12x")}.isFailure)
        assertTrue(runCatching{dates().copy(original=null)}.isFailure)
    }
    private fun tags(segment:ByteArray):Map<Int,ByteArray> {
        assertEquals(0xff,segment[0].toInt() and 255);assertEquals(0xe1,segment[1].toInt() and 255)
        assertEquals("Exif\u0000\u0000",String(segment.copyOfRange(4,10),Charsets.US_ASCII))
        val tiff=segment.copyOfRange(10,segment.size)
        val b=ByteBuffer.wrap(tiff).order(ByteOrder.LITTLE_ENDIAN)
        val result=mutableMapOf<Int,ByteArray>()
        fun directory(offset:Int):Int? {
            val n=b.getShort(offset).toInt() and 65535
            var exif:Int?=null
            for(i in 0 until n) {
                val p=offset+2+i*12;val tag=b.getShort(p).toInt() and 65535
                val type=b.getShort(p+2).toInt() and 65535
                val size=b.getInt(p+4)*when(type){3->2;4->4;else->1}
                val start=if(size<=4)p+8 else b.getInt(p+8)
                result[tag]=tiff.copyOfRange(start,start+size)
                if(tag==0x8769)exif=b.getInt(p+8)
            }
            return exif
        }
        directory(b.getInt(4))?.let{directory(it)}
        return result
    }
    @Test fun writesOriginalModifiedOffsetSubsecondsAndComment() {
        val comment="ASCII\u0000\u0000\u0000binding".toByteArray()
        val tags=tags(Jpeg.exif(comment,dates()))
        fun text(tag:Int)=String(tags.getValue(tag),Charsets.US_ASCII).trimEnd('\u0000')
        assertEquals("2020:02:29 12:34:56",text(0x9003))
        assertEquals("+08:00",text(0x9011));assertEquals("123456",text(0x9291))
        assertEquals("2026:11:09 12:00:00",text(0x132))
        assertEquals("+00:00",text(0x9010));assertEquals("007",text(0x9290))
        assertArrayEquals(comment,tags[0x9286]);assertArrayEquals(byteArrayOf(1,0),tags[0x112])
    }
    @Test fun missingCaptureDateIsNotInvented() {
        val tags=tags(Jpeg.exif(null,LivePhotoDates(modified="2026:11:09 12:00:00")))
        assertFalse(tags.containsKey(0x9003));assertFalse(tags.containsKey(0x9011));assertFalse(tags.containsKey(0x9291))
        assertTrue(tags.containsKey(0x132));assertFalse(tags.containsKey(0x9286))
    }
    @Test fun oldUndatedApiRetainsOrientation() {
        val tags=tags(Jpeg.exif(null))
        assertArrayEquals(byteArrayOf(1,0),tags[0x112]);assertFalse(tags.containsKey(0x132))
    }
}
