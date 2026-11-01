package dev.filmroom

import org.junit.Assert.*
import org.junit.Test

class PendingExportCancellationTest {
    @Test fun explicitCancelBeforeAcceptanceCancelsFutureAcceptedTaskExactlyOnce(){
        val capture=PendingExportCancellation();var calls=0
        capture.cancel();capture.cancel();capture.accepted{calls++};capture.cancel()
        assertEquals(1,calls);assertTrue(capture.requested);assertTrue(capture.wasAccepted)
    }
    @Test fun merelyAcceptingTaskDoesNotCancelIt(){
        val capture=PendingExportCancellation();var calls=0
        capture.accepted{calls++};assertEquals(0,calls)
        capture.cancel();capture.cancel();assertEquals(1,calls)
    }
}
