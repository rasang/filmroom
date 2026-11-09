package dev.filmroom.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueuedDiagnosticBarrierTest {
    @Test(timeout=10000) fun shareBarrierIncludesTheQueuedFailureTerminal() = runBlocking {
        val writes = java.util.Collections.synchronizedList(mutableListOf<Pair<Stage, Outcome?>>())
        val trace = QueuedDiagnosticTrace(Operation.RECIPE, write={_,_,stage,_,outcome ->
            writes.add(stage to outcome); 1L
        })
        trace.event(Stage.ENCODE_PROGRESS, SafeInfo())
        trace.end(Outcome.FAILED, SafeInfo())
        QueuedDiagnosticTrace.flush()
        assertEquals(listOf(Stage.START, Stage.ENCODE_PROGRESS, Stage.END), writes.map { it.first })
        assertEquals(Outcome.FAILED, writes.last().second)
    }
}
