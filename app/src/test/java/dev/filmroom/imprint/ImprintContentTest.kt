package dev.filmroom.imprint

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** Pure JVM tests: no Android, graphics, EXIF reader, filesystem, clock or device zone. */
class ImprintContentTest {
    private fun content(vararg pairs: Pair<String, String>, style: String = "contax_t2") =
        ImprintContent.fromAttributes(mapOf(*pairs), style)
    private fun field(kind: ImprintFieldKind, vararg pairs: Pair<String, String>) =
        content(*pairs).fields.singleOrNull { it.kind == kind }?.text

    @Test fun allFieldsAreTypedAndPackedInApprovedOrder() {
        val c = content("PhotographicSensitivity" to "400", "MeteringMode" to "5", "ExposureTime" to "1/250",
            "FNumber" to "56/10", "ExposureBiasValue" to "1/2", "FocalLength" to "800/1",
            "ExposureProgram" to "3", "ExposureMode" to "2")
        assertEquals(ImprintFieldKind.values().toList(), c.fields.map { it.kind })
        assertEquals(listOf("ISO400", "#", "250", "F5.6", "+0.5", "800mm"), c.fields.map { it.text })
        assertEquals(ImprintMeteringMode.PATTERN, c.fields[1].meteringMode)
        assertTrue(content("ExposureProgram" to "3", "ExposureMode" to "2").fields.isEmpty())
        assertEquals(listOf(ImprintFieldKind.APERTURE, ImprintFieldKind.FOCAL_LENGTH),
            content("FNumber" to "2/1", "FocalLength" to "400").fields.map { it.kind })
        assertTrue(content().fields.isEmpty())
    }

    @Test fun captureDateIsStrictLocalWallDateOnly() {
        val date = "2024:02:29 23:59:59"
        assertEquals("'24 02 29", content("DateTimeOriginal" to date, "OffsetTimeOriginal" to "-14:00").date)
        assertEquals("24 02.29", content("DateTimeOriginal" to date, style = "yashica_t4").date)
        assertEquals("29 2 24", content("DateTimeOriginal" to date, style = "canon_data_back").date)
        assertEquals("24年2月29日", content("DateTimeOriginal" to date, style = "canon_luna").date)
        assertNull(content("DateTimeOriginal" to date, style = "off").date)
        for (raw in listOf("2025:02:29 12:00:00", "2024:02:29 24:00:00", "0000:01:01 00:00:00",
            "2024:02:29", "2024:02:29 23:59:59garbage", "2024:2:29 23:59:59")) {
            assertNull(raw, content("DateTimeOriginal" to raw).date)
        }
        assertNull(content("DateTimeDigitized" to date, "DateTime" to date, "manualDate" to "1999-01-01").date)
    }

    @Test fun isoShortNeverLosesIntegerZeros() {
        for (iso in listOf("100", "400", "800", "6400", "51200", "65534"))
            assertEquals("ISO$iso", field(ImprintFieldKind.ISO, "PhotographicSensitivity" to iso))
        for (raw in listOf("0", "65535", "65536", "-100", "400.0", "100/1", "NaN"))
            assertNull(raw, field(ImprintFieldKind.ISO, "PhotographicSensitivity" to raw))
    }

    @Test fun isoOverflowUsesOnlySensitivityTypeDeclaredFields() {
        val tags = listOf("StandardOutputSensitivity", "RecommendedExposureIndex", "ISOSpeed")
        val enabled = listOf(setOf(1, 4, 5, 7), setOf(2, 4, 6, 7), setOf(3, 5, 6, 7))
        tags.forEachIndexed { index, tag ->
            for (type in 0..8) {
                val result = field(ImprintFieldKind.ISO, "PhotographicSensitivity" to "65535",
                    "SensitivityType" to "$type", tag to "102400")
                assertEquals("$tag type=$type", if (type in enabled[index]) "ISO102400" else null, result)
            }
            assertNull(field(ImprintFieldKind.ISO, tag to "102400"))
        }
        assertEquals("ISO4294967295", field(ImprintFieldKind.ISO, "SensitivityType" to "3", "ISOSpeed" to "4294967295"))
        assertNull(field(ImprintFieldKind.ISO, "SensitivityType" to "3", "ISOSpeed" to "4294967296"))
        assertEquals("ISO200000", field(ImprintFieldKind.ISO, "SensitivityType" to "7", "ISOSpeed" to "200000",
            "RecommendedExposureIndex" to "100000", "StandardOutputSensitivity" to "80000"))
        assertEquals("ISO400", field(ImprintFieldKind.ISO, "PhotographicSensitivity" to "400",
            "SensitivityType" to "3", "ISOSpeed" to "200000"))
    }

    @Test fun shutterHandlesFastFractionalAndLongWithoutInventingAStandardSpeed() {
        for ((raw, text) in listOf("1/8000" to "8000", "1/32000" to "32000", "1/4294967295" to "4294967295",
            "10" to "10s", "100" to "100s", "1000" to "1000s", "3600" to "3600s", "25/10" to "2.5s",
            "4/10" to "0.4s", "4294967295" to "4294967295s"))
            assertEquals(raw, text, field(ImprintFieldKind.SHUTTER, "ExposureTime" to raw))
        assertEquals("256", field(ImprintFieldKind.SHUTTER, "ShutterSpeedValue" to "8"))
        assertEquals("16s", field(ImprintFieldKind.SHUTTER, "ShutterSpeedValue" to "-4"))
        assertEquals("250", field(ImprintFieldKind.SHUTTER, "ExposureTime" to "1/250", "ShutterSpeedValue" to "8"))
    }

    @Test fun apertureAndShutterRejectInvalidNumbersAndUseOnlyIndependentValidFallbacks() {
        for (raw in listOf("0", "-1", "NaN", "Infinity", "1/0", "-1/-1", "1/-2", "1/2/3", "1e999", "1e-999", "")) {
            assertNull(raw, field(ImprintFieldKind.SHUTTER, "ExposureTime" to raw))
            assertNull(raw, field(ImprintFieldKind.APERTURE, "FNumber" to raw))
        }
        assertEquals("F4294967295", field(ImprintFieldKind.APERTURE, "FNumber" to "4294967295"))
        assertEquals("F1", field(ImprintFieldKind.APERTURE, "ApertureValue" to "0"))
        assertEquals("F2", field(ImprintFieldKind.APERTURE, "ApertureValue" to "2"))
        assertNull(field(ImprintFieldKind.APERTURE, "ApertureValue" to "-2"))
        assertNull(field(ImprintFieldKind.APERTURE, "ApertureValue" to "9999"))
        assertNull(field(ImprintFieldKind.SHUTTER, "ShutterSpeedValue" to "9999"))
        assertEquals("F4", field(ImprintFieldKind.APERTURE, "FNumber" to "1/0", "ApertureValue" to "4"))
        assertEquals("256", field(ImprintFieldKind.SHUTTER, "ExposureTime" to "0", "ShutterSpeedValue" to "8"))
    }

    @Test fun focalIsRealNotEquivalentAndNumbersAreBoundedWithoutStrippingIntegerZeros() {
        assertNull(field(ImprintFieldKind.FOCAL_LENGTH, "FocalLengthIn35mmFilm" to "800"))
        for ((raw, text) in listOf("400" to "400mm", "800" to "800mm", "1200" to "1200mm", "2000" to "2000mm", "4294967295" to "4294967295mm",
            "123456789/1000000" to "123.5mm", "1/4294967295" to "2.328e-10mm"))
            assertEquals(raw, text, field(ImprintFieldKind.FOCAL_LENGTH, "FocalLength" to raw))
        assertEquals("50mm", field(ImprintFieldKind.FOCAL_LENGTH, "FocalLength" to "50", "FocalLengthIn35mmFilm" to "75"))
        assertEquals("+0", field(ImprintFieldKind.EXPOSURE_BIAS, "ExposureBiasValue" to "-0.0"))
        assertEquals("+10", field(ImprintFieldKind.EXPOSURE_BIAS, "ExposureBiasValue" to "10"))
    }

    @Test fun formattingDoesNotDependOnLocaleAndUnknownMeteringIsOmitted() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("F5.6", field(ImprintFieldKind.APERTURE, "FNumber" to "5.6"))
            assertEquals("'24 02 29", content("DateTimeOriginal" to "2024:02:29 12:00:00").date)
        } finally { Locale.setDefault(original) }
        for (raw in listOf("0", "7", "255", "-1", "bad"))
            assertNull(field(ImprintFieldKind.METERING, "MeteringMode" to raw))
        for (mode in 1..6) assertNotNull(field(ImprintFieldKind.METERING, "MeteringMode" to "$mode"))
    }

    @Test fun explicitManualDateOverridesOnlyPresentationAndDateSwitchPreservesChoice() {
        val facts = mapOf("DateTimeOriginal" to "2024:02:29 23:59:59", "ExposureTime" to "1/32000",
            "FNumber" to "8", "PhotographicSensitivity" to "400", "FocalLength" to "2000")
        val original = ImprintSettings(dateStyle = "contax_t2", exposure = true)
        val manual = original.copy(manualDateTime = "1999-12-31 01:02:03")
        val source = ImprintContent.fromAttributes(facts, original)
        val changed = ImprintContent.fromAttributes(facts, manual)
        assertEquals("'24 02 29", source.date)
        assertEquals("'99 12 31", changed.date)
        assertEquals(source.fields, changed.fields)
        assertEquals("'99 12 31", ImprintContent.fromAttributes(emptyMap(), manual).date)
        assertNull(ImprintContent.fromAttributes(facts, manual.copy(dateEnabled = false)).date)
        assertEquals("contax_t2", manual.copy(dateEnabled = false).selectedDateStyle)
        assertEquals(changed, ImprintContent.fromAttributes(facts, manual.copy(dateEnabled = false).copy(dateEnabled = true)))
        assertEquals(source, ImprintContent.fromAttributes(facts, manual.forNewPhoto()))
        assertNull(manual.forNewPhoto().manualDateTime)
    }

    @Test fun manualDateTimeMustBeStrictAndCannotNormalizeInvalidValues() {
        for (raw in listOf("2025-02-29 12:00:00", "2024-02-29 24:00:00", "2024-02-29 12:60:00",
            "2024-02-29 12:00:60", "0000-01-01 00:00:00", "2024-2-29 12:00:00", "2024-02-29",
            "2024-02-29T12:00:00", "2024-02-29 12:00:00Z", "")) {
            assertNull(raw, ImprintSettings.parseDateTime(raw))
            assertThrows(IllegalArgumentException::class.java) { ImprintSettings(manualDateTime = raw) }
        }
        val parsed = ImprintSettings.parseDateTime("2024-02-29 23:59:59")
        assertNotNull(parsed)
        assertEquals("2024-02-29 23:59:59", ImprintSettings.formatDateTime(checkNotNull(parsed)))
        assertFalse(ImprintSettings().dateEnabled)
        assertEquals("contax_t2", ImprintSettings(dateEnabled = true).effectiveDateStyle)
    }

}
