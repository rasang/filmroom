package dev.filmroom.io

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PhotoMetadataLiveRestoreTest {
    @Test fun originalDateOffsetAndSubsecondsSurvive() {
        val metadata=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2020:02:29 12:34:56", "OffsetTimeOriginal" to "+08:00", "SubSecTimeOriginal" to "123456"))
        val dates=metadata.livePhotoDates(Instant.parse("2026-11-09T01:02:03.004Z").toEpochMilli())
        assertEquals("2020:02:29 12:34:56",dates.original)
        assertEquals("+08:00",dates.originalOffset);assertEquals("123456",dates.originalSubsec)
        assertEquals("2026:11:09 01:02:03",dates.modified);assertEquals("004",dates.modifiedSubsec)
    }
    @Test fun missingZoneRemainsUnknownInExif() {
        val metadata=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2020:02:29 12:34:56"))
        assertNull(metadata.livePhotoDates(0).originalOffset)
        assertNotNull(metadata.mediaStoreCaptureTimeMillis())
    }
    @Test fun fallbackIsNotCaptureDate() {
        val metadata=PhotoMetadata.fromAttributes(mapOf("DateTime" to "2020:02:29 12:34:56"))
        assertNull(metadata.livePhotoDates(0).original)
    }
    @Test fun captureSnapshotRoundTripPreservesDates() {
        val metadata=PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2020:02:29 12:34:56", "OffsetTimeOriginal" to "+08:00"))
        assertEquals(metadata.livePhotoDates(0),PhotoMetadata.fromJson(metadata.toJson()).livePhotoDates(0))
    }
}
