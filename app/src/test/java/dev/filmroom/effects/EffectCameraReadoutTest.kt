package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectCameraReadoutTest {
    @Test fun reciprocalAndApertureUseCaptureFacts() {
        assertEquals(EffectCameraReadout("125","5.6"),EffectCameraReadout.fromAttributes(mapOf("ExposureTime" to "1/125","FNumber" to "56/10")))
    }
    @Test fun slowShutterHasSecondsMark() {
        assertEquals("2\"",EffectCameraReadout.fromAttributes(mapOf("ExposureTime" to "2/1")).shutter)
        assertEquals("1.3\"",EffectCameraReadout.fromAttributes(mapOf("ExposureTime" to "1.3")).shutter)
    }
    @Test fun apexFallbackIsConvertedNotShownRaw() {
        assertEquals(EffectCameraReadout("128","8"),EffectCameraReadout.fromAttributes(mapOf("ShutterSpeedValue" to "7","ApertureValue" to "6")))
    }
    @Test fun invalidAndMissingValuesStayBlank() {
        assertEquals(EffectCameraReadout(),EffectCameraReadout.fromAttributes(emptyMap()))
        for(bad in listOf("NaN","Infinity","-1","0","1/0","1/2/3","1e-320","unknown")) {
            assertEquals(EffectCameraReadout(),EffectCameraReadout.fromAttributes(mapOf("ExposureTime" to bad,"FNumber" to bad)))
        }
    }
    @Test fun nonStandardValuesNotSnappedToCameraDial() {
        assertEquals(EffectCameraReadout("123.4","2.23"),EffectCameraReadout.fromAttributes(mapOf("ExposureTime" to "1/123.4","FNumber" to "2.23")))
    }
    @Test fun explicitValuesWinOverApex() {
        assertEquals(EffectCameraReadout("250","4"),EffectCameraReadout.fromAttributes(mapOf("ExposureTime" to "1/250","FNumber" to "4","ShutterSpeedValue" to "7","ApertureValue" to "6")))
    }
}
