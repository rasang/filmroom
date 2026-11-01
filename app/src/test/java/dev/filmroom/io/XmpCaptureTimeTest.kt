package dev.filmroom.io

import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class XmpCaptureTimeTest {
    private val date="2026:10:24 15:15:59"
    private val iso="2026-10-24T15:15:59+00:00"
    private fun packet(value:String)= """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:q="http://ns.adobe.com/xap/1.0/" q:CreateDate="$value"/></rdf:RDF></x:xmpmeta>"""
    private fun metadata(value:String=iso,fields:Map<String,String> = mapOf("DateTimeOriginal" to date))=PhotoMetadata.fromJson(JSONObject(PhotoMetadata.fromAttributes(fields).toJson()).put("sourceCreateDate",value).toString())
    private fun segment(bytes:ByteArray):ByteArray { val size=bytes.size+2;return byteArrayOf(-1,-31,(size ushr 8).toByte(),size.toByte())+bytes }
    @Test fun readsNamespacedAttributeOrElementAndRejectsConflictsAndUnsafeXml() {
        assertEquals(iso,XmpCaptureTime.read(packet(iso).toByteArray()))
        assertEquals(iso,XmpCaptureTime.read("""<a xmlns:q="http://ns.adobe.com/xap/1.0/"><q:CreateDate>$iso</q:CreateDate></a>""".toByteArray()))
        assertNull(XmpCaptureTime.read("""<a xmlns:q="wrong" q:CreateDate="$iso"/>""".toByteArray()))
        assertNull(XmpCaptureTime.read("""<a xmlns:q="http://ns.adobe.com/xap/1.0/" q:CreateDate="$iso"><q:CreateDate>2001-01-01T00:00:00Z</q:CreateDate></a>""".toByteArray()))
        assertNull(XmpCaptureTime.read("""<!DOCTYPE a [<!ENTITY e SYSTEM "file:///not-readable">]><a>&e;</a>""".toByteArray()))
        assertNull(XmpCaptureTime.read(ByteArray(128*1024+1)))
    }
    @Test fun sourceHintIsValidatedAndNeverOverridesExplicitExifOffset() {
        assertEquals(Instant.parse("2026-10-24T15:15:59Z").toEpochMilli(),metadata().captureTimeMillis())
        assertNull(metadata("2026-10-24T15:15:59").captureTimeMillis())
        assertNull(metadata("2026-10-25T15:15:59Z").captureTimeMillis())
        assertNull(metadata(fields=emptyMap()).captureTimeMillis())
        assertNull(PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to date)).captureTimeMillis())
        assertEquals(Instant.parse("2026-10-24T07:15:59Z").toEpochMilli(),metadata(fields=mapOf("DateTimeOriginal" to date,"OffsetTimeOriginal" to "+08:00")).captureTimeMillis())
        assertNull(metadata(fields=mapOf("DateTimeOriginal" to date,"SubSecTimeOriginal" to "123")).captureTimeMillis())
    }
    @Test fun readSnapshotPersistsExplicitSourceOffsetAndRetryKeepsCapture() {
        val source=File.createTempFile("xmp-capture-", ".jpg")
        try {
            val exif=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to date)).exifBytes(24,16,"sRGB",0)
            source.writeBytes(byteArrayOf(-1,-40)+segment("Exif\u0000\u0000".toByteArray()+exif)+segment("http://ns.adobe.com/xap/1.0/\u0000".toByteArray()+packet(iso).toByteArray())+byteArrayOf(-1,-39))
            val before=source.readBytes()
            val snapshot=PhotoMetadata.fromJson(PhotoMetadata.read(source).toJson())
            assertEquals(Instant.parse("2026-10-24T15:15:59Z").toEpochMilli(),snapshot.captureTimeMillis())
            for((width,height) in listOf(24 to 16,30 to 28,16 to 24))for(exportTime in listOf(1000L,2000L)) {
                val output=File.createTempFile("capture-export-", ".jpg")
                try {
                    output.writeBytes(byteArrayOf(-1,-40)+segment("Exif\u0000\u0000".toByteArray()+snapshot.exifBytes(width,height,"sRGB",exportTime))+byteArrayOf(-1,-39))
                    assertEquals(snapshot.captureTimeMillis(),PhotoMetadata.read(output).captureTimeMillis())
                    val result=ExifInterface(output.absolutePath)
                    assertEquals(date,result.getAttribute("DateTimeOriginal"));assertEquals("+00:00",result.getAttribute("OffsetTimeOriginal"))
                    assertEquals(width,result.getAttributeInt("PixelXDimension",0));assertEquals(height,result.getAttributeInt("PixelYDimension",0))
                    assertEquals(if(exportTime==1000L)"1970:01:01 00:00:01" else "1970:01:01 00:00:02",result.getAttribute("DateTime"))
                } finally {output.delete()}
            }
            assertArrayEquals(before,source.readBytes())
        } finally { source.delete() }
    }
    @Test fun sourceSubsecondsFillIndependentlyOfExistingOffsetAndRoundtripNonUtc() {
        for(offset in listOf("+05:30","-03:30"))for(existingOffset in listOf(false,true)) {
            val fields=mapOf("DateTimeOriginal" to date)+(if(existingOffset)mapOf("OffsetTimeOriginal" to offset)else emptyMap())
            val isoFraction="2026-10-24T15:15:59.123456789$offset"
            val snapshot=metadata(isoFraction,fields)
            val expected=java.time.OffsetDateTime.parse(isoFraction).toInstant().toEpochMilli()
            assertEquals(expected,snapshot.captureTimeMillis())
            val out=File.createTempFile("xmp-fraction-", ".jpg")
            try {
                out.writeBytes(byteArrayOf(-1,-40)+segment("Exif\u0000\u0000".toByteArray()+snapshot.exifBytes(3,2,"sRGB",1000))+byteArrayOf(-1,-39))
                val exif=ExifInterface(out.absolutePath)
                assertEquals(offset,exif.getAttribute("OffsetTimeOriginal"));assertEquals("123456789",exif.getAttribute("SubSecTimeOriginal"))
                assertEquals(expected,PhotoMetadata.read(out).captureTimeMillis())
            }finally{out.delete()}
        }
    }
    @Test fun rejectsInvalidCalendarOffsetsAndNestedEntityContent() {
        for(iso in listOf("2026-10-24T15:15:59+19:00","2026-10-24T15:15:59+01:00:30","2026-02-30T15:15:59Z","2025-02-29T15:15:59Z"))assertNull(metadata(iso).captureTimeMillis())
        assertNull(metadata("2026-10-24T15:15:59.123456788Z",mapOf("DateTimeOriginal" to date,"SubSecTimeOriginal" to "123456789")).captureTimeMillis())
        assertNull(XmpCaptureTime.read("""<a xmlns:q="http://ns.adobe.com/xap/1.0/"><q:CreateDate><b>$iso</b></q:CreateDate></a>""".toByteArray()))
        assertNull(XmpCaptureTime.read("""<!DOCTYPE a [<!ENTITY e "2026-10-24T15:15:59Z">]><a xmlns:q="http://ns.adobe.com/xap/1.0/" q:CreateDate="&e;"/>""".toByteArray()))
    }
    @Test fun optionalPrivateDjiSourceRemainsUntouchedAndCaptureTimeIsAvailable() {
        val path=System.getenv("FILMROOM_CAPTURE_DATE_FIXTURE")
        assumeTrue("Private input is an explicit local QA opt-in only",path!=null)
        val expectedSha=checkNotNull(System.getenv("FILMROOM_CAPTURE_DATE_EXPECTED_SHA256")){"Fixture provided without expected SHA256"}
        check(expectedSha.matches(Regex("[0-9a-f]{64}"))){"Expected SHA256 must be 64 lowercase hex characters"}
        val expectedIso=checkNotNull(System.getenv("FILMROOM_CAPTURE_DATE_EXPECTED_ISO")){"Fixture provided without expected offset timestamp"}
        val expected=java.time.OffsetDateTime.parse(expectedIso)
        val source=File(path!!)
        fun hash()=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString(""){"%02x".format(it)}
        val before=hash()
        assertEquals(expectedSha,before)
        val snapshot=PhotoMetadata.read(source)
        assertEquals(expected.format(java.time.format.DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")),JSONObject(snapshot.toJson()).getJSONObject("attributes").getString("DateTimeOriginal"))
        assertEquals(expected.toInstant().toEpochMilli(),snapshot.captureTimeMillis())
        assertEquals(snapshot.captureTimeMillis(),PhotoMetadata.fromJson(snapshot.toJson()).captureTimeMillis())
        assertEquals(before,hash())
    }
}
