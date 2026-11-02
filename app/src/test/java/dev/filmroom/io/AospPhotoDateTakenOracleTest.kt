package dev.filmroom.io

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class AospPhotoDateTakenOracleTest {
    private fun withExif(fields:Map<String,String>,body:(ExifInterface)->Unit) {
        val metadata=PhotoMetadata.fromAttributes(fields)
        val tiff=metadata.exifBytes(2,3,"sRGB",Instant.parse("2026-11-02T03:00:00Z").toEpochMilli())
        if(!fields.containsKey("OffsetTimeOriginal")) {
            // Construct the original no-offset counterexample, not the newly compatible output.
            val b=java.nio.ByteBuffer.wrap(tiff).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val n=b.getShort(8).toInt() and 65535
            val pointer=(0 until n).map{10+it*12}.first{(b.getShort(it).toInt() and 65535)==0x8769}
            val exif=b.getInt(pointer+8);val count=b.getShort(exif).toInt() and 65535
            (0 until count).map{exif+2+it*12}.firstOrNull{(b.getShort(it).toInt() and 65535)==0x9011}?.let{b.putShort(it,0xffff.toShort())}
        }
        val payload="Exif\u0000\u0000".toByteArray()+tiff
        val n=payload.size+2;val file=File.createTempFile("scanner-date-", ".jpg")
        try {
            file.writeBytes(byteArrayOf(-1,-40,-1,-31,(n ushr 8).toByte(),n.toByte())+payload+byteArrayOf(-1,-39))
            body(ExifInterface(file.absolutePath))
        } finally {file.delete()}
    }
    @Test fun oldWallClockWithoutOffsetCannotSurviveAospPublishRescanWithNewMtime() {
        withExif(mapOf("DateTimeOriginal" to "2016:01:08 15:08:01")) { exif ->
            assertNull(AospPhotoDateTakenOracle.scan(exif,Instant.parse("2026-11-02T03:00:00Z").toEpochMilli()))
        }
    }
    @Test fun realSourceOffsetMakesHistoricalCaptureDateStableAcrossRescans() {
        withExif(mapOf("DateTimeOriginal" to "2023:10:18 17:19:29", "OffsetTimeOriginal" to "-04:00", "SubSecTimeOriginal" to "68")) { exif ->
            assertEquals(Instant.parse("2023-10-18T21:19:29.680Z").toEpochMilli(),AospPhotoDateTakenOracle.scan(exif,Instant.parse("2026-11-02T03:00:00Z").toEpochMilli()))
        }
    }
}
