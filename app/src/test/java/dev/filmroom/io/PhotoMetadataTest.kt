package dev.filmroom.io

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PhotoMetadataTest {
    private fun values(bytes:ByteArray,offset:Int=8):Map<Int,ByteArray> {
        val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until (b.getShort(offset).toInt() and 65535)).associate { n->
            val p=offset+2+n*12;val tag=b.getShort(p).toInt() and 65535;val type=b.getShort(p+2).toInt();val count=b.getInt(p+4)
            val size=count*when(type){3->2;4->4;5,10->8;else->1};val start=if(size>4)b.getInt(p+8)else p+8
            tag to bytes.copyOfRange(start,start+size)
        }
    }
    private fun long(v:ByteArray)=ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN).int
    private fun string(v:ByteArray)=v.toString(Charsets.US_ASCII).trimEnd('\u0000')
    @Test fun unicodeCommentUsesRawBytesNotAndroidxLossyStringConversion() {
        val original=PhotoMetadata.fromAttributes(mapOf("UserComment" to "测试照片 — café"))
        fun jpeg(tiff:ByteArray):ByteArray {val payload="Exif\u0000\u0000".toByteArray()+tiff;val n=payload.size+2;return byteArrayOf(-1,-40,-1,-31,(n ushr 8).toByte(),n.toByte())+payload+byteArrayOf(-1,-39)}
        val file=java.io.File.createTempFile("unicode-metadata-", ".jpg")
        try {
            file.writeBytes(jpeg(original.exifBytes(2,3,"sRGB",0)))
            val read=PhotoMetadata.read(file);assertNull(read.warning);assertTrue(read.toJson(),read.toJson().contains("测试照片 — café"))
            file.writeBytes(jpeg(read.exifBytes(3,2,"sRGB",1)))
            assertTrue(PhotoMetadata.read(file).toJson().contains("测试照片 — café"))
        }finally{file.delete()}
    }
    @Test fun readerWriterRoundtripNormalizesOrientationAndPreservesCapture() {
        val source=java.io.File.createTempFile("metadata-source-", ".jpg")
        val output=java.io.File.createTempFile("metadata-output-", ".jpg")
        fun jpeg(file:java.io.File,width:Int,height:Int) {
            val cls=Class.forName("java.awt.image.BufferedImage")
            val image=cls.getConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).newInstance(width,height,1)
            assertEquals(true,Class.forName("javax.imageio.ImageIO").getMethod("write",Class.forName("java.awt.image.RenderedImage"),String::class.java,java.io.File::class.java).invoke(null,image,"jpeg",file))
        }
        try {
            jpeg(source,2,3)
            val original=androidx.exifinterface.media.ExifInterface(source.absolutePath)
            mapOf("Make" to "Test Camera", "Model" to "Body", "LensModel" to "35mm Prime", "ExposureTime" to "1/125", "FNumber" to "2.8", "PhotographicSensitivity" to "400", "GPSLatitudeRef" to "N", "GPSLatitude" to "35/1,1/1,2/1", "GPSLongitudeRef" to "E", "GPSLongitude" to "139/1,2/1,3/1", "DateTimeOriginal" to "2019:01:02 03:04:05", "OffsetTimeOriginal" to "+09:00", "SubSecTimeOriginal" to "123456", "Orientation" to "8").forEach{(k,v)->original.setAttribute(k,v)}
            original.saveAttributes()
            val snapshot=PhotoMetadata.read(source)
            assertNull(snapshot.warning)
            jpeg(output,3,2)
            PhotoMetadataContainer.inject(output,ExportFormat.JPEG,snapshot.exifBytes(3,2,"sRGB",Instant.parse("2026-10-31T09:01:02.987Z").toEpochMilli()))
            val result=androidx.exifinterface.media.ExifInterface(output.absolutePath)
            assertEquals("Test Camera",result.getAttribute("Make"));assertEquals("35mm Prime",result.getAttribute("LensModel"))
            assertEquals(0.008,result.getAttributeDouble("ExposureTime",0.0),1e-12);assertEquals(400,result.getAttributeInt("PhotographicSensitivity",0))
            assertEquals(1,result.getAttributeInt("Orientation",0));assertEquals(3,result.getAttributeInt("PixelXDimension",0));assertEquals(2,result.getAttributeInt("PixelYDimension",0))
            assertEquals("2019:01:02 03:04:05",result.getAttribute("DateTimeOriginal"));assertEquals("+09:00",result.getAttribute("OffsetTimeOriginal"));assertEquals("123456",result.getAttribute("SubSecTimeOriginal"))
            assertEquals("2026:10:31 09:01:02",result.getAttribute("DateTime"));assertEquals("+00:00",result.getAttribute("OffsetTime"));assertEquals("987",result.getAttribute("SubSecTime"))
            assertEquals(original.getAttribute("GPSLatitude"),result.getAttribute("GPSLatitude"))
        } finally {source.delete();output.delete()}
    }
    @Test fun originalJpegSnapshotReadsLensCaptureGpsAndIsoBeforePixels() {
        val original=PhotoMetadata.fromAttributes(mapOf("Make" to "Camera", "LensModel" to "Prime 35", "PhotographicSensitivity" to "800", "DateTimeOriginal" to "2019:01:02 03:04:05", "OffsetTimeOriginal" to "+09:00", "GPSLatitudeRef" to "N", "GPSLatitude" to "35/1,1/1,2/1"))
        val tiff=original.exifBytes(2,3,"sRGB",0)
        val payload="Exif\u0000\u0000".toByteArray()+tiff;val length=payload.size+2
        val file=java.io.File.createTempFile("metadata-authored-", ".jpg")
        try {
            file.writeBytes(byteArrayOf(-1,-40,-1,-31,(length ushr 8).toByte(),length.toByte())+payload+byteArrayOf(-1,-39))
            val read=PhotoMetadata.read(file);val saved=read.toJson()
            assertTrue(saved,saved.contains("Prime 35"));assertTrue(saved,saved.contains("800"));assertTrue(saved,saved.contains("GPSLatitude"))
            assertEquals(original.captureTimeMillis(),read.captureTimeMillis())
        }finally { file.delete() }
    }
    @Test fun captureInstantRequiresOriginalOffsetAndPreservesSubseconds() {
        val a=mapOf("DateTimeOriginal" to "2020:01:02 03:04:05","SubSecTimeOriginal" to "123456")
        assertNull(PhotoMetadata.fromAttributes(a).captureTimeMillis())
        val m=PhotoMetadata.fromAttributes(a+("OffsetTimeOriginal" to "+05:30"))
        assertEquals(Instant.parse("2020-01-01T21:34:05.123Z").toEpochMilli(),m.captureTimeMillis())
        assertEquals(m,PhotoMetadata.fromJson(m.toJson()))
    }
    @Test fun exportFieldsAreFreshCaptureFieldsRemainOriginal() {
        val m=PhotoMetadata.fromAttributes(mapOf("Make" to "Camera","DateTimeOriginal" to "2001:02:03 04:05:06","OffsetTimeOriginal" to "-08:00","UserComment" to "测试"))
        val bytes=m.exifBytes(31,17,"Display P3",Instant.parse("2026-10-31T09:01:02.987Z").toEpochMilli())
        val main=values(bytes);val exif=values(bytes,long(main.getValue(0x8769)))
        assertEquals(31,long(main.getValue(0x100)));assertEquals(17,long(main.getValue(0x101)))
        assertArrayEquals(byteArrayOf(1,0),main[0x112]);assertEquals("2026:10:31 09:01:02",string(main.getValue(0x132)))
        assertEquals("2001:02:03 04:05:06",string(exif.getValue(0x9003)));assertEquals("-08:00",string(exif.getValue(0x9011)))
        assertEquals("+00:00",string(exif.getValue(0x9010)));assertEquals("987",string(exif.getValue(0x9290)))
        assertArrayEquals(byteArrayOf(-1,-1),exif[0xA001]);assertTrue(exif.getValue(0x9286).take(8).toByteArray().contentEquals("UNICODE\u0000".toByteArray()))
    }
    @Test fun invalidAndOpaqueFieldsAreDroppedWithoutLosingValidMetadata() {
        val m=PhotoMetadata.fromAttributes(mapOf("Make" to "Safe","MakerNote" to "offsets","Orientation" to "8","ExposureTime" to "1/0","DateTimeOriginal" to "2020:02:31 00:00:00"))
        assertNotNull(m.warning);assertNull(m.captureTimeMillis());val main=values(m.exifBytes(3,2,"sRGB",0))
        assertEquals("Safe",string(main.getValue(0x10f)));assertArrayEquals(byteArrayOf(1,0),main[0x112])
    }
    @Test fun decimalRationalsGpsAndBudgetAreBounded() {
        val m=PhotoMetadata.fromAttributes(mapOf("FNumber" to "2.8","ExposureTime" to "0.0003333333333333333","GPSLatitude" to "30/1,20/1,1/2","GPSTimeStamp" to "10:20:30","GPSVersionID" to "2.3.0.0","UserComment" to "x".repeat(50000)))
        assertNotNull(m.warning);val bytes=m.exifBytes(3,2,"sRGB",0);assertTrue(bytes.size<PhotoMetadata.BUDGET)
        val main=values(bytes);val exif=values(bytes,long(main.getValue(0x8769)));val gps=values(bytes,long(main.getValue(0x8825)))
        assertEquals(1,long(exif.getValue(0x829a)));assertEquals(3000,long(exif.getValue(0x829a).copyOfRange(4,8)))
        assertEquals(14,long(exif.getValue(0x829d)));assertEquals(5,long(exif.getValue(0x829d).copyOfRange(4,8)))
        assertEquals(24,gps.getValue(7).size);assertArrayEquals(byteArrayOf(2,3,0,0),gps[0])
        assertNotNull(PhotoMetadata.fromJson("x".repeat(PhotoMetadata.BUDGET+1)).warning)
    }
}
