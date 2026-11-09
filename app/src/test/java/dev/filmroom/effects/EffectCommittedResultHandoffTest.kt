package dev.filmroom.effects

import dev.filmroom.livephoto.CommittedResultHandoff
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Exercises the production SDK handoff with a counting publisher, not Android codecs/MediaStore. */
class EffectCommittedResultHandoffTest {
    private class QueueDispatcher:CoroutineDispatcher() {
        val queue=java.util.ArrayDeque<Runnable>()
        override fun dispatch(context:CoroutineContext,block:Runnable){queue.add(block)}
        fun next(){requireNotNull(queue.poll()).run()}
    }
    @Test fun normalPublicationReturnsItsExactResultOnce() {
        val caller=QueueDispatcher();val io=QueueDispatcher();val saved=Any()
        var received:Any?=null;var publications=0
        CoroutineScope(caller).launch {
            received=CommittedResultHandoff.run(io){committed -> publications++;committed(saved);saved}
        }
        caller.next();io.next();caller.next()
        assertSame(saved,received);assertEquals(1,publications)
    }
    @Test fun cancellationAfterPublicationReturnsExactResultWithoutPublishingTwice() {
        val caller=QueueDispatcher();val io=QueueDispatcher()
        val saved=Any();var publications=0;var received:Any?=null
        val job=CoroutineScope(caller).launch {
            received=CommittedResultHandoff.run(io){ committed ->
                publications++
                committed(saved)
                saved
            }
        }
        caller.next();io.next()
        assertEquals(1,publications);assertNull(received)
        job.cancel();caller.next()
        assertSame(saved,received);assertEquals(1,publications)
    }
    @Test fun cancellationBeforeWorkerStartsNeverPublishesOrReportsSuccess() {
        val caller=QueueDispatcher();val io=QueueDispatcher()
        var publications=0;var received=false
        val job=CoroutineScope(caller).launch {
            CommittedResultHandoff.run(io){committed -> publications++;Any().also(committed)}
            received=true
        }
        caller.next();job.cancel();io.next();caller.next()
        assertEquals(0,publications);assertFalse(received);assertTrue(job.isCancelled)
    }
    @Test fun publicationFailureIsNotConvertedToSavedResult() {
        val caller=QueueDispatcher();val io=QueueDispatcher()
        val failure=java.io.IOException("publication failed")
        var observed:Throwable?=null;var publications=0;var received=false
        CoroutineScope(caller).launch {
            try { CommittedResultHandoff.run<Any>(io){ _ -> publications++;throw failure };received=true }
            catch(error:Throwable){observed=error}
        }
        caller.next();io.next();caller.next()
        // Coroutine stacktrace recovery may copy an IOException, preserving the original
        // as its cause. Identity across dispatcher boundaries is not the failure contract.
        assertTrue(observed is java.io.IOException)
        assertTrue(generateSequence(observed){it.cause}.take(8).any{it===failure})
        assertEquals(failure.message,observed?.message)
        assertFalse(received);assertEquals(1,publications)
    }
    @Test fun cancellationBeforeCommitIsNotSuccess() {
        val caller=QueueDispatcher();val io=QueueDispatcher()
        var received=false;var rolledBack=false
        val job=CoroutineScope(caller).launch {
            CommittedResultHandoff.run<Any>(io){ _ ->
                // Represents a cancelled/rolled-back publisher that never returns success.
                rolledBack=true;throw CancellationException("before commit")
            }
            received=true
        }
        caller.next();io.next();caller.next()
        assertTrue(rolledBack);assertFalse(received);assertTrue(job.isCancelled)
    }
    @Test fun cancellationAfterCommitDoesNotSkipNonCancellableCleanup() {
        val caller=QueueDispatcher();val io=QueueDispatcher()
        var cleaned=false;var released=false;var received=false
        val job=CoroutineScope(caller).launch {
            try {
                CommittedResultHandoff.run(io){ committed -> Any().also(committed) }
                received=true
            } finally {
                try { withContext(NonCancellable){withContext(io){cleaned=true}} }
                finally {released=true}
            }
        }
        caller.next();io.next();job.cancel();caller.next();io.next();caller.next()
        assertTrue(received);assertTrue(cleaned);assertTrue(released)
    }
}
