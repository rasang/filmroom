package dev.filmroom.diagnostics

import org.junit.Assert.*
import org.junit.Test

class GpsDiagnosticInfoTest {
    @Test fun presenceDoesNotClaimValueValidity() {
        assertEquals(GpsPresence.NONE,GpsDiagnosticInfo.presence(setOf("Make")))
        assertEquals(GpsPresence.OTHER_TAGS,GpsDiagnosticInfo.presence(setOf("GPSLatitude")))
        assertEquals(GpsPresence.COORDINATE_TAGS,GpsDiagnosticInfo.presence(setOf("GPSLatitude","GPSLongitude","GPSLatitudeRef","GPSLongitudeRef")))
    }
    @Test fun safeFieldsRoundTripAndRejectArbitraryStrings() {
        val gps=GpsDiagnosticInfo(GpsDiagnosticPhase.OUTPUT_READBACK,true,GpsAccessRoute.SELECTED_FALLBACK,
            GpsOriginalFallback.ORIGINAL_DENIED,GpsPresence.COORDINATE_TAGS,GpsPresence.COORDINATE_TAGS,GpsPresence.NONE,false)
        val info=SafeInfo(gps=gps)
        assertEquals(gps,SafeInfo.parse(info.fields()).gps)
        assertNull(SafeInfo.parse(mapOf("gpsInputPresence" to "31.2,121.4","gpsAccessRoute" to "content://private/item","gpsSameTagSet" to "/storage/secret")).gps)
        assertFalse(info.fields().toString().contains("31.2"))
    }
    @Test fun importAndOutputNamespacesStaySeparate() {
        val gps=GpsDiagnosticInfo(phase=GpsDiagnosticPhase.IMPORT_SNAPSHOT,input=GpsPresence.NONE,snapshot=GpsPresence.NONE)
        val info=SafeInfo(importDiagnostic=ImportDiagnosticInfo(gps=gps))
        val restored=SafeInfo.parse(info.fields())
        assertEquals(gps,restored.importDiagnostic?.gps)
        assertNull(restored.gps)
    }

    @Test fun persistedEventRoundTripKeepsOnlyPresence() {
        val gps=GpsDiagnosticInfo(phase=GpsDiagnosticPhase.OUTPUT_READBACK,snapshot=GpsPresence.COORDINATE_TAGS,output=GpsPresence.NONE,sameTagSet=false)
        val event=DiagnosticEvent(1800000000000,10,"a".repeat(32),1,1,Operation.EXPORT_ENCODE,Stage.ENCODE_PROGRESS,info=SafeInfo(gps=gps))
        val line=event.line()
        assertEquals(gps,DiagnosticEvent.parse(line)?.info?.gps)
        assertTrue(line.contains("gpsOutputPresence=NONE"))
        assertFalse(line.contains("GPSLatitude="))
        assertFalse(line.contains("content://"))
    }
}
