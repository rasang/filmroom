package dev.filmroom.diagnostics

import org.junit.Assert.*
import org.junit.Test

class PreviewTimingHostTest {
    @Test fun monotonicOffsetsAndSingleTerminal() {
        var clock=100L
        val trace=PreviewTiming(100){clock}
        clock=164;trace.mark(PreviewTimingPoint.DEBOUNCE_END)
        clock=180;trace.mark(PreviewTimingPoint.JNI_ENTER)
        clock=190;trace.mark(PreviewTimingPoint.JNI_ENTER)
        trace.handle(false);trace.operation(42)
        val data=trace.snapshot(PreviewTimingTerminal.CANCELLED)!!
        assertEquals(64L,data.offsets[PreviewTimingPoint.DEBOUNCE_END])
        assertEquals(80L,data.offsets[PreviewTimingPoint.JNI_ENTER])
        assertEquals(42L,data.renderOperation)
        assertEquals(PreviewHandleState.WARM_HANDLE,data.handle)
        assertNull(trace.snapshot(PreviewTimingTerminal.DRAW_CALLBACK))
    }
    @Test fun adoptIsNotDisplayAndCancelledRefinementCannotEraseIt() {
        val trace=PreviewTiming(100){120}
        trace.mark(PreviewTimingPoint.ADOPTED)
        assertNull(trace.snapshot(PreviewTimingTerminal.CANCELLED))
        val data=trace.snapshot(PreviewTimingTerminal.DRAW_NOT_OBSERVED)!!
        assertFalse(data.offsets.containsKey(PreviewTimingPoint.NEXT_DRAW))
        assertEquals(PreviewTimingTerminal.DRAW_NOT_OBSERVED,data.terminal)
    }
    @Test fun schemaRejectsUnknownTextAndOutOfRangeValues() {
        val map=mapOf("previewTimingTerminal" to "DRAW_CALLBACK","previewTimingHandle" to "path-or-secret",
            "previewTimingJNI_ENTER" to "-1","previewTimingJNI_EXIT" to "604800001","previewTimingNEXT_DRAW" to "125",
            "uri" to "private-photo")
        val result=PreviewTimingSnapshot.parse(map)!!
        assertEquals(PreviewHandleState.UNKNOWN,result.handle)
        assertEquals(mapOf(PreviewTimingPoint.NEXT_DRAW to 125L),result.offsets)
        assertFalse(result.fields().containsKey("uri"))
        assertTrue(result.fields()["previewTimingNEXT_DRAW"] is Long)
        assertNull(PreviewTimingSnapshot.parse(mapOf("previewTimingTerminal" to "untrusted")))
    }
    @Test fun safeInfoRoundTripsTypedTiming() {
        val data=PreviewTimingSnapshot(mapOf(PreviewTimingPoint.JNI_EXIT to 123),PreviewTimingTerminal.DRAW_CALLBACK,PreviewHandleState.COLD_HANDLE,9)
        val info=SafeInfo(previewTiming=data)
        assertEquals(data,SafeInfo.parse(info.fields()).previewTiming)
    }
}
