package dev.filmroom.effects

import dev.filmroom.io.PhotoMetadata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class EffectCaptureMetadataTest {
    private val gps=mapOf("Make" to "Fixture", "GPSLatitudeRef" to "N", "GPSLatitude" to "24/1,30/1,0/1", "GPSLongitudeRef" to "E", "GPSLongitude" to "118/1,5/1,0/1")
    private val dates=mapOf("DateTimeOriginal" to "2020:02:29 12:34:56", "OffsetTimeOriginal" to "+08:00")
    private fun fields(m:PhotoMetadata)=JSONObject(m.toJson()).getJSONObject("attributes")
    private fun jpeg(m:PhotoMetadata):ByteArray {
        val payload="Exif\u0000\u0000".toByteArray()+m.exifBytes(2,3,"sRGB",0)
        val n=payload.size+2
        return byteArrayOf(-1,-40,-1,-31,(n ushr 8).toByte(),n.toByte())+payload+byteArrayOf(-1,-39)
    }
    @Test fun dateLookupDoesNotReplaceGpsSnapshot() {
        val cached=PhotoMetadata.fromAttributes(gps)
        val result=EffectCaptureMetadata.resolve(cached.toJson(),listOf("source")){PhotoMetadata.fromAttributes(dates)}
        assertEquals("24/1,30/1,0/1",fields(result).getString("GPSLatitude"))
        assertEquals("118/1,5/1,0/1",fields(result).getString("GPSLongitude"))
        assertEquals("2020:02:29 12:34:56",result.livePhotoDates(0).original)
        assertTrue(result.livePhotoCaptureExif().any{it.group==2 && it.tag==2})
    }
    @Test fun existingDateReturnsFrozenSnapshotWithoutReadingUri() {
        val cached=PhotoMetadata.fromAttributes(gps+dates)
        val result=EffectCaptureMetadata.resolve(cached.toJson(),listOf("source")){error("Should not read source")}
        assertEquals(cached.toJson(),result.toJson())
    }
    @Test fun restoredSnapshotDoesNotRehydrateIntentionallyOmittedGps() {
        val cached=PhotoMetadata.fromAttributes(mapOf("Make" to "Fixture"))
        val result=EffectCaptureMetadata.resolve(cached.toJson(),listOf("source")){PhotoMetadata.fromAttributes(gps+dates)}
        assertFalse(fields(result).has("GPSLatitude"))
        assertEquals("2020:02:29 12:34:56",result.livePhotoDates(0).original)
    }
    @Test fun legacyWithoutSnapshotReadsCompleteExifAndKeepsGpsWithoutDate() {
        val bytes=jpeg(PhotoMetadata.fromAttributes(gps))
        val result=EffectCaptureMetadata.resolve(null,listOf("source")){PhotoMetadata.readCaptureMetadata(bytes.inputStream())}
        assertEquals("24/1,30/1,0/1",fields(result).getString("GPSLatitude"))
        assertEquals("118/1,5/1,0/1",fields(result).getString("GPSLongitude"))
        assertNull(result.livePhotoDates(0).original)
    }
    @Test fun fullExifStreamReadsBothDateAndLocation() {
        val result=PhotoMetadata.readCaptureMetadata(jpeg(PhotoMetadata.fromAttributes(gps+dates)).inputStream())
        assertEquals("24/1,30/1,0/1",fields(result).getString("GPSLatitude"))
        assertEquals("2020:02:29 12:34:56",result.livePhotoDates(0).original)
    }
    @Test fun fallbackDateStillKeepsFirstLegacySourcesGps() {
        val result=EffectCaptureMetadata.resolve(null,listOf("source","result")){uri->
            PhotoMetadata.fromAttributes(if(uri=="source")gps else dates)
        }
        assertEquals("24/1,30/1,0/1",fields(result).getString("GPSLatitude"))
        assertEquals("2020:02:29 12:34:56",result.livePhotoDates(0).original)
    }
    @Test fun unreadableUrisLeaveSnapshotUnchanged() {
        val cached=PhotoMetadata.fromAttributes(gps)
        val result=EffectCaptureMetadata.resolve(cached.toJson(),listOf("source","result")){null}
        assertEquals(cached.toJson(),result.toJson())
    }
    @Test fun fallbackDateDoesNotInheritOrphanedOffsetFromAnotherSource() {
        val cached=PhotoMetadata.fromAttributes(gps+mapOf("OffsetTimeOriginal" to "-05:00","SubSecTimeOriginal" to "123"))
        val result=EffectCaptureMetadata.resolve(cached.toJson(),listOf("source")){PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2020:02:29 12:34:56"))}
        assertFalse(fields(result).has("OffsetTimeOriginal"))
        assertFalse(fields(result).has("SubSecTimeOriginal"))
        assertTrue(fields(result).has("GPSLatitude"))
    }
}
