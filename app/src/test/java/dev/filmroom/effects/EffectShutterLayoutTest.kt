package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectShutterLayoutTest {
    @Test fun digitsHaveVisibleClearanceAfterAccountingForStroke() {
        val l = EffectShutterLayout.create(3, 9f, 72f)
        assertEquals(4.32f, l.glyphWidth, .0001f)
        assertTrue(l.gap - l.strokeWidth >= 1.7f)
        assertEquals(40.5f, l.windowWidth, .0001f)
    }
    @Test fun oneHundredHasThreeDistinctNonTouchingCells() {
        val l = EffectShutterLayout.create(3, 9f, 72f)
        for (i in 0..1) assertTrue(l.glyphX(i + 1) - l.glyphX(i) - l.glyphWidth - l.strokeWidth > 1.7f)
        assertTrue(l.glyphX(0) - l.strokeWidth / 2f > 0f)
        assertTrue(l.glyphX(2) + l.glyphWidth + l.strokeWidth / 2f < l.windowWidth)
    }
    @Test fun unusualLongExposureValuesFitWithoutCrossingIntoAperture() {
        for (n in 1..12) {
            val l = EffectShutterLayout.create(n, 9f, 45f)
            assertTrue(l.windowWidth <= 45f)
            assertTrue(l.glyphX(0) - l.strokeWidth / 2f > 0f)
            assertTrue(l.glyphX(n - 1) + l.glyphWidth + l.strokeWidth / 2f < l.windowWidth)
            assertTrue(l.gap > l.strokeWidth * 3f)
        }
    }
    @Test fun strokeAndSpacingShrinkTogetherOnlyWhenWindowIsConstrained() {
        val full = EffectShutterLayout.create(10, 9f, 150f)
        val small = EffectShutterLayout.create(10, 9f, 45f)
        assertTrue(small.glyphWidth < full.glyphWidth)
        assertEquals(full.gap / full.strokeWidth, small.gap / small.strokeWidth, .0001f)
    }
}
