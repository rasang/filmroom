package dev.filmroom.export
import dev.filmroom.diagnostics.*
import org.junit.Assert.*
import org.junit.Test
class NegativePreviewDiagnosticsHostTest {
    @Test fun omittedFilmIsAnExplicitNonFailureTerminal() {
        assertNull(NegativePreviewTerminalGate.resolve(false,listOf("not_applicable","not_applicable"),false))
        assertEquals(NegativePreviewOutcome.NOT_APPLICABLE,NegativePreviewTerminalGate.resolve(false,listOf("not_applicable","not_applicable"),true))
        assertEquals(NegativePreviewOutcome.FAILURE,NegativePreviewTerminalGate.resolve(false,listOf("not_applicable","failed"),true))
    }
    @Test fun earlyFailureCannotPreemptExactSuccess() {
        assertNull(NegativePreviewTerminalGate.resolve(false,listOf("failed",null),false))
        assertEquals(NegativePreviewOutcome.SUCCESS,NegativePreviewTerminalGate.resolve(true,listOf("failed","ready"),false))
    }
    @Test fun terminalClassificationAndPendingUnknown() {
        assertEquals(NegativePreviewOutcome.BUDGET_SKIP,NegativePreviewTerminalGate.resolve(false,listOf("skipped_budget",null),true))
        assertEquals(NegativePreviewOutcome.FAILURE,NegativePreviewTerminalGate.resolve(false,listOf("failed","skipped_budget"),true))
        assertNull(NegativePreviewTerminalGate.resolve(false,listOf("pending","failed"),true))
        assertNull(NegativePreviewTerminalGate.resolve(false,listOf(null,null),true))
        assertNull(NegativePreviewTerminalGate.resolve(false,listOf("unavailable"),true))
        assertEquals(NegativePreviewOutcome.CANCELLED,NegativePreviewTerminalGate.resolve(false,listOf("pending"),false,true))
    }
    @Test fun oneTerminalAcrossPollAndCancel() {
        val gate=NegativePreviewTerminalGate()
        assertEquals(NegativePreviewOutcome.SUCCESS,gate.claim(NegativePreviewOutcome.SUCCESS))
        assertNull(gate.claim(NegativePreviewOutcome.CANCELLED))
        assertNull(gate.claim(NegativePreviewOutcome.SUCCESS))
    }
    @Test fun safeEnumRoundtripAndRejectFreeText() {
        for(value in NegativePreviewOutcome.entries)assertEquals(value,SafeInfo.parse(SafeInfo(negativePreviewOutcome=value).fields()).negativePreviewOutcome)
        assertNull(SafeInfo.parse(mapOf("negativePreviewOutcome" to "/private/photo")).negativePreviewOutcome)
    }
}
