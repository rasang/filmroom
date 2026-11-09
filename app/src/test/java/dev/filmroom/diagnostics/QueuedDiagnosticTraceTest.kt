package dev.filmroom.diagnostics

import org.junit.Assert.*
import org.junit.Test

class QueuedDiagnosticTraceTest {
    @Test fun uiCallsOnlyQueueAndConsumerPreservesOrder() {
        val scheduled = mutableListOf<QueuedDiagnosticTrace>()
        val writes = mutableListOf<Stage>()
        val trace = QueuedDiagnosticTrace(Operation.RECIPE, schedule={ scheduled.add(it); true },
            write={_,_,stage,_,_->writes.add(stage);1L})
        trace.event(Stage.ENCODE_PROGRESS, SafeInfo(width=42))
        trace.end(Outcome.SUCCESS, SafeInfo())
        trace.end(Outcome.FAILED, SafeInfo())
        assertTrue(writes.isEmpty())
        assertEquals(1, scheduled.size)
        scheduled.removeAt(0).drain()
        assertEquals(listOf(Stage.START, Stage.ENCODE_PROGRESS, Stage.END), writes)
    }
    @Test fun traceCapacityIsBoundedAndRetainsStartAndEnd() {
        val writes = mutableListOf<Pair<Stage,SafeInfo>>()
        val trace = QueuedDiagnosticTrace(Operation.BACKUP, schedule={true},
            write={_,_,stage,info,_->writes.add(stage to info);1L})
        repeat(500){trace.event(Stage.ENCODE_PROGRESS, SafeInfo(width=it))}
        trace.end(Outcome.FAILED, SafeInfo())
        trace.drain()
        assertEquals(64, writes.size)
        assertEquals(Stage.START, writes.first().first)
        assertEquals(Stage.END, writes.last().first)
        assertEquals(438L, writes.first().second.diagnosticSuppressedEvents)
    }
    @Test fun queueFailureDoesNotThrowAndCanRetryOnNextEvent() {
        var accept=false
        var writes=0
        val trace=QueuedDiagnosticTrace(Operation.RECIPE,schedule={accept},write={_,_,_,_,_->writes++;1L})
        accept=true
        trace.end(Outcome.CANCELLED,SafeInfo())
        trace.drain()
        assertEquals(2,writes)
    }
    @Test fun writerFailureCannotEscapeConsumer() {
        val trace=QueuedDiagnosticTrace(Operation.RECIPE,schedule={true},write={_,_,_,_,_->error("IO unavailable")})
        trace.end(Outcome.FAILED,SafeInfo())
        trace.drain()
    }
}
