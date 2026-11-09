package dev.filmroom.diagnostics

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

/** Bounded, ordered local logging. UI/audio callbacks never perform filesystem writes.
 * Overload drops diagnostics, not application work. Only SafeInfo crosses the queue.
 */
internal class QueuedDiagnosticTrace(
    private val operation: Operation,
    initial: SafeInfo = SafeInfo(),
    private val schedule: (QueuedDiagnosticTrace) -> Boolean = Queue::offer,
    private val write: (Operation, Long?, Stage, SafeInfo, Outcome?) -> Long = { op, token, stage, info, outcome ->
        if (token == null) Diagnostics.begin(op, info).value
        else { if (outcome == null) Diagnostics.event(OperationId(token), stage, info)
               else Diagnostics.end(OperationId(token), outcome, info); token }
    },
) {
    private data class Record(val stage: Stage, val info: SafeInfo, val outcome: Outcome? = null)
    private val pending = ArrayList<Record>()
    private var scheduled = false
    private var ended = false
    private var token: Long? = null // Only the single queue consumer accesses this.
    private var dropped = 0L
    init { enqueue(Record(Stage.START, initial)) }
    @Synchronized fun event(stage: Stage, info: SafeInfo) { if (!ended) enqueue(Record(stage, info)) }
    @Synchronized fun end(outcome: Outcome, info: SafeInfo) {
        if (ended) return
        ended = true
        enqueue(Record(Stage.END, info, outcome))
    }
    @Synchronized private fun enqueue(record: Record) {
        if (pending.size >= 64) {
            // Preserve START and terminal state; coalesce progress under pressure.
            pending.removeAt(if (pending.first().stage == Stage.START) 1 else 0)
            dropped = (dropped + 1).coerceAtMost(1_000_000)
        }
        pending.add(record)
        if (!scheduled) scheduled = runCatching { schedule(this) }.getOrDefault(false)
    }
    internal fun drain() {
        val batch = synchronized(this) {
            val copy = pending.toList(); pending.clear(); scheduled = false
            val losses = dropped; dropped = 0
            copy to losses
        }
        batch.first.forEachIndexed { index, record ->
            val info = if (index == 0 && batch.second > 0) record.info.copy(diagnosticSuppressedEvents=batch.second) else record.info
            runCatching { token = write(operation, token, record.stage, info, record.outcome) }
        }
    }
    companion object {
        /** Await before a diagnostic snapshot; never call synchronously from the UI. */
        suspend fun flush() = Queue.flush()
    }
    private object Queue {
        private sealed class Item {
            data class Trace(val trace: QueuedDiagnosticTrace): Item()
            data class Barrier(val done: CompletableDeferred<Unit>): Item()
        }
        private val channel = Channel<Item>(64)
        private val rejected = AtomicLong()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        init { scope.launch { for (item in channel) when(item) {
            is Item.Trace -> item.trace.drain()
            is Item.Barrier -> item.done.complete(Unit)
        } } }
        fun offer(trace: QueuedDiagnosticTrace): Boolean {
            val accepted = channel.trySend(Item.Trace(trace)).isSuccess
            if (!accepted) rejected.updateAndGet { (it + 1).coerceAtMost(1_000_000) }
            return accepted
        }
        suspend fun flush() {
            val done = CompletableDeferred<Unit>()
            channel.send(Item.Barrier(done)); done.await()
            val losses = rejected.getAndSet(0)
            if (losses > 0) {
                val info = SafeInfo(diagnosticQueueRejectedEnqueues=losses)
                Diagnostics.end(Diagnostics.begin(Operation.SHARE_DIAGNOSTICS, info), Outcome.SUCCESS, info)
            }
        }
    }
}
