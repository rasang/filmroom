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
        assertEquals(listOf("NATIVE_ENTER","CALL","NATIVE_RETURN","CALLER_CLEANUP"),events)
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
        assertEquals(listOf("NATIVE_ENTER","NATIVE_RETURN","CALLER_CLEANUP"),events)
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
        } catch(actual:IllegalStateException){assertSame(failure,actual)}
        val after=reads.get();assertTrue(after>0)
        delay(30);assertEquals(after,reads.get())
        assertEquals(listOf(Stage.NATIVE_ENTER),events)
    }
}
