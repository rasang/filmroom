package dev.filmroom.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RetainedNativeWorkTest {
    private val mib=1024L*1024
    private fun memory(capacityMiB:Long)=NativeMemorySnapshot((capacityMiB*2+64)*mib,0,false)
    @Test fun prepaidSafetyAvoidsSelfEvictionAndNeverRaisesCapacity()=runBlocking {
        val limiter=NativeWorkLimiter();val evictions=AtomicInteger()
        val owner=limiter.retainForWork(100*mib,memory(116)){evictions.incrementAndGet()}
        assertEquals(116*mib,limiter.reservedBytes())
        owner.run({memory(116)}){assertEquals(1,limiter.activeCount());assertEquals(116*mib,limiter.reservedBytes())}
        assertEquals(0,evictions.get());assertEquals(0,limiter.activeCount());owner.close();assertEquals(0,limiter.reservedBytes())
        try{limiter.retainForWork(100*mib,memory(115));fail()}catch(_:NativeWorkUnavailable){}
    }
    @Test fun closeDuringActiveRunDefersBytesAndExceptionalExitReturnsSlot()=runBlocking {
        val limiter=NativeWorkLimiter();val owner=limiter.retainForWork(100*mib,memory(512))
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val task=async {runCatching{owner.run({memory(512)}){entered.complete(Unit);release.await();throw IllegalStateException("synthetic")}}}
        entered.await();owner.close();assertEquals(116*mib,limiter.reservedBytes());assertEquals(1,limiter.activeCount())
        release.complete(Unit);assertTrue(task.await().isFailure);assertEquals(0,limiter.activeCount());assertEquals(0,limiter.reservedBytes())
        try{owner.run({memory(512)}){};fail()}catch(_:CancellationException){}
    }
    @Test fun exportCanStillEvictIdleOwnerAndLowMemoryRejectsWithoutSelfEviction()=runBlocking {
        val limiter=NativeWorkLimiter();lateinit var owner:RetainedNativeWork
        owner=limiter.retainForWork(100*mib,memory(116)){owner.close()}
        limiter.run(100*mib,{memory(116)}){assertEquals(1,limiter.activeCount());assertEquals(116*mib,limiter.reservedBytes())}
        assertEquals(0,limiter.reservedBytes())
        val events=AtomicInteger();val other=limiter.retainForWork(100*mib,memory(512)){events.incrementAndGet()}
        try{other.run({memory(512).copy(lowMemory=true)}){fail()};fail()}catch(_:NativeWorkUnavailable){}
        assertEquals(0,events.get());assertEquals(0,limiter.activeCount());other.close();assertEquals(0,limiter.reservedBytes())
    }
    @Test fun twoWorkerLimitIsSharedWithExports()=runBlocking {
        val limiter=NativeWorkLimiter();val owner=limiter.retainForWork(10*mib,memory(1024))
        val release=CompletableDeferred<Unit>();val a=CompletableDeferred<Unit>();val b=CompletableDeferred<Unit>()
        val first=launch{limiter.run(10*mib,{memory(1024)}){a.complete(Unit);release.await()}}
        val second=launch{limiter.run(10*mib,{memory(1024)}){b.complete(Unit);release.await()}}
        a.await();b.await();val started=CompletableDeferred<Unit>()
        val detail=launch{owner.run({memory(1024)}){started.complete(Unit);assertTrue(limiter.activeCount()<=2)}}
        delay(150);assertFalse(started.isCompleted);assertEquals(2,limiter.activeCount());release.complete(Unit)
        joinAll(first,second,detail);assertTrue(started.isCompleted);owner.close();assertEquals(0,limiter.reservedBytes())
    }
}
