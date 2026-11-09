package dev.filmroom.effects

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Reproduces coroutine handoff semantics, NOT a device/MediaStore publication test. */
class EffectPublishedHandoffRiskTest {
    private class QueueDispatcher:CoroutineDispatcher() {
        val queue=java.util.ArrayDeque<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable){queue.add(block)}
        fun next(){requireNotNull(queue.poll()).run()}
    }
    @Test fun committedSideEffectSurvivesCancellationBeforeResultDelivery() {
        val caller=QueueDispatcher();val worker=QueueDispatcher()
        var committed=false;var completionBreadcrumb=false;var received=false
        val job=CoroutineScope(caller).launch {
            withContext(worker){
                committed=true
                completionBreadcrumb=true // Equivalent to COMPLETE callback inside convert().
                "saved"
            }
            received=true
        }
        caller.next();worker.next()
        assertTrue(committed);assertTrue(completionBreadcrumb);assertFalse(received)
        job.cancel()
        caller.next()
        assertTrue(job.isCancelled);assertTrue(committed);assertFalse(received)
    }
    @Test fun normalHandoffDeliversResultAfterCommit() {
        val caller=QueueDispatcher();val worker=QueueDispatcher()
        var committed=false;var received=false
        val job=CoroutineScope(caller).launch {
            withContext(worker){committed=true}
            received=true
        }
        caller.next();worker.next();caller.next()
        assertTrue(committed);assertTrue(received);assertTrue(job.isCompleted);assertFalse(job.isCancelled)
    }
}
