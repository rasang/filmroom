package dev.filmroom.io

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic container/metadata contracts, not a claim about a device decoder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class CanonCr3CaptureMetadataTest {
    @get:Rule val temp=TemporaryFolder()
    private val le=ByteOrder.LITTLE_ENDIAN
    private fun short(n:Int)=ByteBuffer.allocate(2).order(le).putShort(n.toShort()).array()
    private fun long(n:Int)=ByteBuffer.allocate(4).order(le).putInt(n).array()
    private fun ascii(s:String)=s.toByteArray(Charsets.US_ASCII)+byteArrayOf(0)
    private data class Entry(val tag:Int,val type:Int,val bytes:ByteArray)
    private fun tiff(vararg entries:Entry):ByteArray {
        val start=8+2+entries.size*12+4
        val b=ByteBuffer.allocate(start+entries.sumOf{if(it.bytes.size>4)it.bytes.size else 0}).order(le)
        b.put(byteArrayOf(73,73,42,0));b.putInt(8);b.putShort(entries.size.toShort());var at=start
        for(e in entries) {
            b.putShort(e.tag.toShort());b.putShort(e.type.toShort())
            val unit=when(e.type){3->2;4->4;5,10->8;else->1};b.putInt(e.bytes.size/unit)
            if(e.bytes.size<=4){b.put(e.bytes);repeat(4-e.bytes.size){b.put(0)}}else{b.putInt(at);e.bytes.copyInto(b.array(),at);at+=e.bytes.size}
        }
        return b.array()
    }
    private fun box(type:String,bytes:ByteArray)=ByteBuffer.allocate(8+bytes.size).putInt(8+bytes.size).put(type.toByteArray(Charsets.US_ASCII)).put(bytes).array()
    private fun file(cmt2:ByteArray=tiff(Entry(0x9003,2,ascii("2024:05:06 07:08:09")),Entry(0x9011,2,ascii("+02:00")),Entry(0x829a,5,long(1)+long(125)),Entry(0x8827,3,short(200)))):File {
        val desc=byteArrayOf(0x85.toByte(),0xc0.toByte(),0xb6.toByte(),0x87.toByte(),0x82.toByte(),0x0f,0x11,0xe0.toByte(),0x81.toByte(),0x11,0xf4.toByte(),0xce.toByte(),0x46,0x2b,0x6a,0x48)
        val cmt1=tiff(Entry(271,2,ascii("Canon")),Entry(272,2,ascii("Synthetic Camera")),Entry(274,3,short(6)))
        val cmt4=tiff(Entry(1,2,ascii("N")),Entry(2,5,long(35)+long(1)+long(30)+long(1)))
        val bytes=box("ftyp","crx ".toByteArray()+ByteArray(4))+box("moov",box("uuid",desc+box("CMT1",cmt1)+box("CMT2",cmt2)+box("CMT4",cmt4)))+box("mdat",byteArrayOf(0))
        return temp.newFile().apply{writeBytes(bytes)}
    }
    @Test fun captureFieldsSurviveAndSourceGeometryIsNeverCopied() {
        val metadata=PhotoMetadata.read(file());val fields=JSONObject(metadata.toJson()).getJSONObject("attributes")
        assertEquals("Canon",fields.getString("Make"));assertEquals("200",fields.getString("PhotographicSensitivity"))
        assertEquals("1/125",fields.getString("ExposureTime"));assertEquals("35/1,30/1,0/1",fields.getString("GPSLatitude"))
        assertFalse(fields.has("Orientation"));assertNotNull(metadata.captureTimeMillis())
        val output=temp.newFile().apply{writeBytes(metadata.exifBytes(320,240,"sRGB",0))}
        val exif=ExifInterface(output.absolutePath)
        assertEquals("Canon",exif.getAttribute(ExifInterface.TAG_MAKE))
        assertEquals(1,exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,0))
        assertEquals(320,exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH,0))
        assertEquals(240,exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH,0))
    }
    @Test fun duplicateCaptureTagsAndEscapingPointersFailClosed() {
        val duplicate=file(tiff(Entry(0x8827,3,short(200)),Entry(0x8827,3,short(800))))
        assertTrue(PhotoMetadata.read(duplicate).issues.isNotEmpty())
        val cmt=tiff(Entry(0x9003,2,ascii("2024:05:06 07:08:09")))
        ByteBuffer.wrap(cmt).order(le).putInt(18,Int.MAX_VALUE)
        assertTrue(PhotoMetadata.read(file(cmt)).issues.isNotEmpty())
    }
    @Test fun unselectedMakerNotesAreNotReadOrRetained() {
        val cmt=tiff(Entry(0x8827,3,short(400)),Entry(0x927c,7,ByteArray(8)))
        ByteBuffer.wrap(cmt).order(le).putInt(26,-1).putInt(30,-1)
        val fields=JSONObject(PhotoMetadata.read(file(cmt)).toJson()).getJSONObject("attributes")
        assertEquals("400",fields.getString("PhotographicSensitivity"));assertFalse(fields.has("MakerNote"))
    }
    @Test fun nonCr3AndOversizedBoxesDoNotTriggerCaptureParsing() {
        val jpeg=temp.newFile().apply{writeBytes(byteArrayOf(-1,-40,-1)+ByteArray(20))}
        assertNull(CanonCr3CaptureMetadata.read(jpeg,emptySet()))
        val input=file();val b=input.readBytes();ByteBuffer.wrap(b).putInt(16,Int.MAX_VALUE);input.writeBytes(b)
        assertTrue(PhotoMetadata.read(input).issues.isNotEmpty())
    }
}
