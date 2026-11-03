package dev.filmroom.engine

import org.junit.Assert.*
import org.junit.Test

class PreviewGpuBudgetTest {
    @Test fun lazyStartAndRepeatedStartNeverExtendDeadline() {
        var now = 100L
        val budget = PreviewGpuBudget(8_000) { now }
        now = 90_000
        assertFalse(budget.expired())
        budget.start()
        assertEquals(98_000L, budget.limitDeadline(200_000))
        now += 7_999
        budget.start()
        assertFalse(budget.expired())
        assertEquals(98_000L, budget.limitDeadline(200_000))
        now++
        assertTrue(budget.expired())
        assertEquals(97_000L, budget.limitDeadline(97_000))
    }
}
