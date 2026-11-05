package dev.filmroom.imprint

import androidx.exifinterface.media.ExifInterface
import dev.filmroom.io.ExportFormat
import dev.filmroom.io.PhotoMetadataContainer
import java.io.File
import java.time.Instant
import dev.filmroom.io.PhotoMetadata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Adapter regression checks using the actual validated capture snapshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImprintSourceSnapshotHostTest {
    @Test fun persistedManualDateNeverOverridesOriginalCaptureDate() {
        val source = PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2024:02:29 23:59:59", "OffsetTimeOriginal" to "-14:00"))
        val settings = ImprintSettings.fromJson(JSONObject("""{
            "version":1,"dateStyle":"contax_t2","dateSource":"manual","manualDate":"1999-12-31",
            "corner":"bottom_right","size":1.0,"glow":0.45,"exposure":false
        }"""))
        assertEquals("'24 02 29", ImprintContent.from(source, settings).date)
        assertNull(ImprintContent.from(PhotoMetadata.fromAttributes(emptyMap()), settings).date)
        assertEquals(ImprintContent.from(source, settings), ImprintContent.from(PhotoMetadata.fromJson(source.toJson()), settings))
    }

    @Test fun sourceSnapshotExposesOnlyRequiredFocalAndSensitivityFacts() {
        val source = PhotoMetadata.fromAttributes(mapOf("PhotographicSensitivity" to "65535", "SensitivityType" to "3",
            "ISOSpeed" to "102400", "FocalLength" to "800/1", "FocalLengthIn35mmFilm" to "1200"))
        val content = ImprintContent.from(source, ImprintSettings(exposure = true))
        assertEquals(listOf("ISO102400", "800mm"), content.fields.map { it.text })
        assertEquals(content, ImprintContent.from(PhotoMetadata.fromJson(source.toJson()), ImprintSettings(exposure = true)))
    }

    @Test fun actualJpegReaderKeepsCaptureDateHighIsoAndLongFocalAcrossSnapshotRoundtrip() {
        val file = File.createTempFile("imprint-source-", ".jpg")
        try {
            for ((exposure, expectedShutter) in listOf("3600/1" to "3600s", "1/32000" to "32000")) {
                // Same full JPEG helper as PhotoMetadataTest, not a metadata-only fake container.
                val imageClass = Class.forName("java.awt.image.BufferedImage")
                val image = imageClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).newInstance(3, 2, 1)
                assertEquals(true, Class.forName("javax.imageio.ImageIO").getMethod("write",
                    Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
                    .invoke(null, image, "jpeg", file))
                val sourceFacts = PhotoMetadata.fromAttributes(mapOf(
                    "DateTimeOriginal" to "2024:02:29 23:59:59", "OffsetTimeOriginal" to "-09:00",
                    "PhotographicSensitivity" to "65535", "SensitivityType" to "3", "ISOSpeed" to "204800",
                    "FocalLength" to "2000/1", "FocalLengthIn35mmFilm" to "3000", "FNumber" to "8/1",
                    "ExposureTime" to exposure,
                ))
                PhotoMetadataContainer.inject(file, ExportFormat.JPEG, sourceFacts.exifBytes(3, 2, "sRGB",
                    Instant.parse("2026-11-05T12:34:56Z").toEpochMilli()))
                val persisted = ExifInterface(file.absolutePath)
                assertEquals("2024:02:29 23:59:59", persisted.getAttribute("DateTimeOriginal"))
                assertEquals("2026:11:05 12:34:56", persisted.getAttribute("DateTime"))
                assertEquals("65535", persisted.getAttribute("PhotographicSensitivity"))
                assertEquals("204800", persisted.getAttribute("ISOSpeed"))

                val read = PhotoMetadata.read(file)
                assertNull(read.warning)
                val settings = ImprintSettings(dateStyle = "contax_t2", exposure = true)
                val imprint = ImprintContent.from(read, settings)
                assertEquals("'24 02 29", imprint.date)
                assertEquals(listOf("ISO204800", expectedShutter, "F8", "2000mm"), imprint.fields.map { it.text })
                assertEquals(imprint, ImprintContent.from(PhotoMetadata.fromJson(read.toJson()), settings))
            }
        } finally { file.delete() }
    }


    @Test fun manualDateTimePersistsOnlyForSamePhotoAndOriginalAccessorNeverUsesIt() {
        val source = PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2024:02:29 23:59:59",
            "OffsetTimeOriginal" to "-09:00", "DateTimeDigitized" to "2026:11:05 12:00:00"))
        val original = ImprintContent.originalCaptureDateTime(source)
        assertEquals("2024-02-29T23:59:59", original.toString())
        val setting = ImprintSettings(dateStyle = "canon_luna", dateEnabled = false,
            manualDateTime = "1999-12-31 01:02:03", datePlacement = "edge")
        assertEquals("1999-12-31T01:02:03", ImprintContent.displayDateTime(source, setting).toString())
        assertEquals(original, ImprintContent.originalCaptureDateTime(source))
        val restored = ImprintSettings.fromJson(setting.json())
        assertEquals(3, setting.json().getInt("version"))
        assertEquals(setting, restored)
        assertNull(ImprintContent.from(source, restored).date)
        assertEquals("99年12月31日", ImprintContent.from(source, restored.copy(dateEnabled = true)).date)
        assertEquals(original, ImprintContent.displayDateTime(source, restored.forNewPhoto()))
        assertNull(ImprintContent.originalCaptureDateTime(PhotoMetadata.fromAttributes(
            mapOf("DateTimeDigitized" to "2026:11:05 12:00:00"))))
        assertNull(ImprintContent.displayDateTime(PhotoMetadata.fromAttributes(emptyMap()), ImprintSettings()))
    }

    @Test fun settingsVersionTwoMigratesSwitchWithoutInventingOverride() {
        for (style in listOf("off", "canon_luna")) {
            val json = ImprintSettings(dateStyle = style).json().put("version", 2)
            json.remove("dateEnabled")
            json.remove("manualDateTime")
            val migrated = ImprintSettings.fromJson(json)
            assertEquals(style != "off", migrated.dateEnabled)
            assertNull(migrated.manualDateTime)
            assertEquals(style, migrated.dateStyle)
        }
        val invalid = ImprintSettings().json().put("manualDateTime", "2025-02-29 12:00:00")
        assertThrows(IllegalArgumentException::class.java) { ImprintSettings.fromJson(invalid) }
    }

}
