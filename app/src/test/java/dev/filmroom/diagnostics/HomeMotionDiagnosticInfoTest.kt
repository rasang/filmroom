package dev.filmroom.diagnostics

import dev.filmroom.effects.EffectKind
import org.junit.Assert.*
import org.junit.Test

class HomeMotionDiagnosticInfoTest {
    @Test fun roundTripsEveryEventAndReason() {
        for (event in HomeMotionEvent.entries) for (reason in HomeMotionReason.entries) {
            val info = HomeMotionDiagnosticInfo(event, EffectKind.entries.first(), reason, 60_000, 30_000, 1)
            assertEquals(info, SafeInfo.parse(SafeInfo(homeMotion = info).fields()).homeMotion)
        }
    }
    @Test fun rejectsUnsafeOrOutOfRangeFields() {
        assertNull(HomeMotionDiagnosticInfo.parse(mapOf("homeMotionEvent" to "content://private/photo")))
        val parsed = HomeMotionDiagnosticInfo.parse(mapOf(
            "homeMotionEvent" to "START", "homeMotionKind" to "bad", "homeMotionReason" to "bad",
            "homeMotionElapsedMs" to "60001", "homeMotionWidth" to "0", "homeMotionHeight" to "30001",
        ))!!
        assertEquals(HomeMotionReason.UNKNOWN, parsed.reason)
        assertNull(parsed.kind); assertNull(parsed.elapsedMs); assertNull(parsed.width); assertNull(parsed.height)
        val fields = HomeMotionDiagnosticInfo(HomeMotionEvent.START, elapsedMs = -1, width = 0, height = 30001).fields()
        assertEquals(setOf("homeMotionEvent", "homeMotionReason"), fields.keys)
    }
}
