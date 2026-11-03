package dev.filmroom.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeWorkQuarantineTest {
    private val mib=1024L*1024
    private fun ample()=NativeMemorySnapshot(4L*1024*mib,256*mib,false)
    @Test fun unknownOwnerReleasesWorkerButKeepsChargeUntilCleanupThenDeathRelease()=runBlocking {
        val limiter=NativeWorkLimiter();val retention=NativeWorkRetention();lateinit var death:()->Unit
        var cleaned=false
        limiter.runQuarantinable(120*mib,::ample,retention) {
            retention.retainUntil { release -> death={cleaned=true;release()} }
        }
        assertEquals(0,limiter.activeCount());assertEquals(136*mib,limiter.reservedBytes())
        assertEquals(7,withTimeout(1000){limiter.run(120*mib,::ample){7}})
        assertFalse(cleaned);assertEquals(136*mib,limiter.reservedBytes())
        death();death();assertTrue(cleaned);assertEquals(0L,limiter.reservedBytes())
    }
    @Test fun quarantineRemovesUnfinishableEvictionAndRejectsRatherThanStarves()=runBlocking {
        val limiter=NativeWorkLimiter();var evictions=0
        val retained=limiter.retainForWork(120*mib,ample()){evictions++}
        retained.quarantine()
        val constrained={NativeMemorySnapshot(400*mib,128*mib,false)}
        try { withTimeout(1000){limiter.run(120*mib,constrained){fail("not admitted")}};fail("should reject") }
        catch(_:NativeWorkUnavailable){}
        assertEquals(0,evictions);assertEquals(0,limiter.activeCount());assertEquals(136*mib,limiter.reservedBytes())
        retained.close();assertEquals(0L,limiter.reservedBytes())
    }
    @Test fun cancellationRetainsBytesButNotWorkerAndEarlyDeathIsSafe()=runBlocking {
        val limiter=NativeWorkLimiter();val retention=NativeWorkRetention();lateinit var death:()->Unit
        val entered=CompletableDeferred<Unit>()
        val job=launch { limiter.runQuarantinable(120*mib,::ample,retention) {
            retention.retainUntil { death=it };entered.complete(Unit);awaitCancellation()
        } }
        entered.await();job.cancelAndJoin()
        assertEquals(0,limiter.activeCount());assertEquals(136*mib,limiter.reservedBytes())
        death();assertEquals(0L,limiter.reservedBytes())
        limiter.runQuarantinable(120*mib,::ample,NativeWorkRetention().apply {retainUntil{it()}}){Unit}
        assertEquals(0L,limiter.reservedBytes())
    }
}
