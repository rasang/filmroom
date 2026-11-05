package dev.filmroom.engine

import dev.filmroom.diagnostics.NativePhase
import dev.filmroom.diagnostics.Stage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Boundary/ownership tests with an injected blocking call, not device JNI tests. */
class PreviewNativeBoundaryTest {
    @Test fun milestonesBracketTheSynchronousCall()=runBlocking {
        val events=mutableListOf<String>()
        val result=previewNativeBoundary(
            mark={events.add(it.name)},readProgress={null},recordProgress={},now={0},
        ){events.add("CALL");42}
        events.add("CALLER_CLEANUP")
        assertEquals(42,result)
        assertEquals(listOf("NATIVE_PREPARE","CALL","NATIVE_RETURN","CALLER_CLEANUP"),events)
    }

    @Test fun cancellationDuringCallStillRecordsReturnBeforeCleanup()=runBlocking {
        val events=Collections.synchronizedList(mutableListOf<String>())
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val job=launch(Dispatchers.Default){
            try {
                previewNativeBoundary(
                    mark={events.add(it.name)},readProgress={null},recordProgress={},now={0},
                ) { entered.countDown();check(release.await(5,TimeUnit.SECONDS));"returned" }
            } finally {events.add("CALLER_CLEANUP")}
        }
        try {
            assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
            job.cancel()
        } finally {release.countDown()}
        withTimeout(5000){job.join()}
        assertTrue(job.isCancelled)
        assertEquals(listOf("NATIVE_PREPARE","NATIVE_RETURN","CALLER_CLEANUP"),events)
    }

    @Test fun thrownCallDoesNotClaimReturnAndPollingIsJoined()=runBlocking {
        val events=Collections.synchronizedList(mutableListOf<Stage>())
        val reads=AtomicInteger();val observed=CountDownLatch(1)
        val failure=IllegalStateException("synthetic boundary failure")
        try {
            withContext(Dispatchers.Default){
                previewNativeBoundary(
                    mark={events.add(it)},
                    readProgress={reads.incrementAndGet();EngineProgress(1,1,NativePhase.SETUP,0,1,updateSerial=1)},
                    recordProgress={observed.countDown()},now={1000},pollIntervalMs=5,
                ){check(observed.await(5,TimeUnit.SECONDS));throw failure}
            }
            fail("Expected synchronous failure")
        } catch(actual:IllegalStateException){
            // Coroutine stacktrace recovery may copy the exception across withContext.
            assertEquals(failure.javaClass,actual.javaClass)
            assertEquals(failure.message,actual.message)
            assertTrue("Original synchronous failure remains in the cause chain",
                generateSequence<Throwable>(actual){it.cause}.take(8).any{it===failure})
        }
        val after=reads.get();assertTrue(after>0)
        delay(30);assertEquals(after,reads.get())
        assertEquals(listOf(Stage.NATIVE_PREPARE),events)
    }
    @Test fun cancelledRequestKeepsObservingUntilActualNativeReturnThenStops()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val firstRead=CountDownLatch(1);val observedAfterCancellation=CountDownLatch(1)
        val cancellationIssued=java.util.concurrent.atomic.AtomicBoolean(false)
        val reads=AtomicInteger()
        val job=launch(Dispatchers.Default) {
            previewNativeBoundary(
                mark={},
                readProgress={
                    val count=reads.incrementAndGet();firstRead.countDown()
                    if(cancellationIssued.get())observedAfterCancellation.countDown()
                    EngineProgress(1,1,NativePhase.SETUP,0,1,updateSerial=count.toLong())
                },recordProgress={},now={System.nanoTime()/1_000_000},pollIntervalMs=5,
            ) {entered.countDown();check(release.await(5,TimeUnit.SECONDS));"returned"}
        }
        try {
            assertTrue(withContext(Dispatchers.IO){entered.await(5,TimeUnit.SECONDS)})
            assertTrue(withContext(Dispatchers.IO){firstRead.await(5,TimeUnit.SECONDS)})
            job.cancel();cancellationIssued.set(true)
            assertTrue("Observation must outlive UI cancellation while JNI owns the handle",
                withContext(Dispatchers.IO){observedAfterCancellation.await(2,TimeUnit.SECONDS)})
        } finally {release.countDown();withContext(NonCancellable){withTimeout(5000){job.join()}}}
        val after=reads.get();delay(30)
        assertEquals("No read after caller can destroy the handle",after,reads.get())
        assertTrue(job.isCancelled)
    }

    @Test fun diagnosticFailureDoesNotEscapeIndependentObserver()=runBlocking {
        val reads=AtomicInteger();val recovered=CountDownLatch(1)
        val result=withContext(Dispatchers.Default) {
            previewNativeBoundary(
                mark={},readProgress={
                    if(reads.incrementAndGet()==1)throw AssertionError("synthetic diagnostic failure")
                    recovered.countDown();null
                },recordProgress={},now={0},pollIntervalMs=5,
            ){check(recovered.await(5,TimeUnit.SECONDS));42}
        }
        assertEquals(42,result)
        val after=reads.get();delay(30);assertEquals(after,reads.get())
    }

}
