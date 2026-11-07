package dev.filmroom.io

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DetailGridCacheTest {
    private fun built(id:String,deleted:AtomicInteger,valid:()->Boolean={true},width:Int=2)=DetailGridCache.Built(
        DetailSource(width,1,"source-$id","mapping-$id","sources/$id/grid.f32le"){deleted.incrementAndGet()},valid)
    @Test fun sameKeySharesOneGridAndIndependentIdempotentReaders()=runBlocking {
        val cache=DetailGridCache{it()};val deletes=AtomicInteger();var builds=0
        val first=cache.acquire("key"){builds++;built("first",deletes)}
        val second=cache.acquire("key"){error("Unexpected second build")}
        assertEquals(first.source.sourceRevision,second.source.sourceRevision)
        assertEquals(first.source.mappingRevision,second.source.mappingRevision)
        assertEquals(first.source.relativePath,second.source.relativePath);assertEquals(1,builds)
        first.source.close();first.source.close();assertEquals(0,deletes.get())
        cache.close();cache.close();assertEquals(0,deletes.get())
        second.source.close();second.source.close();assertEquals(1,deletes.get())
    }
    @Test fun replacingSlotKeepsOldReaderAliveWithoutRetainingOldOwner()=runBlocking {
        val cache=DetailGridCache{it()};val oldDeletes=AtomicInteger();val newDeletes=AtomicInteger()
        val old=cache.acquire("old"){built("old",oldDeletes)}
        val fresh=cache.acquire("new"){built("new",newDeletes)}
        assertEquals(0,oldDeletes.get());old.source.close();assertEquals(1,oldDeletes.get())
        fresh.source.close();assertEquals(0,newDeletes.get());cache.close();assertEquals(1,newDeletes.get())
    }
    @Test fun invalidOrThrowingValidatorDoesNotPublishStaleEntry()=runBlocking {
        val cache=DetailGridCache{it()};val deletes=AtomicInteger();var valid=true
        cache.acquire("key"){built("first",deletes,{valid})}.source.close()
        valid=false
        cache.acquire("key"){built("second",deletes)}.source.close();assertEquals(1,deletes.get())
        cache.close();assertEquals(2,deletes.get())
        val unsafe=DetailGridCache{it()};var throwOnCheck=false
        unsafe.acquire("key"){built("unsafe",deletes,{if(throwOnCheck)throw IllegalArgumentException("link");true})}.source.close()
        throwOnCheck=true
        try{unsafe.acquire("key"){error("Unsafe hit must be rejected")};fail()}catch(_:IllegalArgumentException){}
        assertEquals(3,deletes.get());unsafe.close();assertEquals(3,deletes.get())
    }
    @Test fun buildFailureAndInvalidBuiltGridAreNotCached()=runBlocking {
        val cache=DetailGridCache{it()};val deletes=AtomicInteger()
        try{cache.acquire("key"){throw java.io.IOException("failed build")};fail()}catch(_:java.io.IOException){}
        try{cache.acquire("key"){built("invalid",deletes,{false})};fail()}catch(_:IllegalStateException){}
        assertEquals(1,deletes.get())
        cache.acquire("key"){built("valid",deletes)}.source.close();cache.close();assertEquals(2,deletes.get())
    }
    @Test fun closedCacheRejectsNewBuildAndLatePublication()=runBlocking {
        supervisorScope {
            val cache=DetailGridCache{it()};val entered=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>();val deletes=AtomicInteger()
            val pending=async {cache.acquire("key"){entered.complete(Unit);resume.await();built("late",deletes)}}
            entered.await();cache.close();resume.complete(Unit)
            try{pending.await();fail()}catch(_:IllegalStateException){}
            assertEquals(1,deletes.get())
            try{cache.acquire("key"){error("Closed cache must not build")};fail()}catch(_:IllegalStateException){}
        }
    }
    @Test fun simultaneousAcquisitionsUseSingleBuild()=runBlocking {
        val cache=DetailGridCache{it()};val entered=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>();val deletes=AtomicInteger();var builds=0
        val first=async {cache.acquire("key"){builds++;entered.complete(Unit);resume.await();built("shared",deletes)}}
        entered.await()
        val second=async {cache.acquire("key"){error("Duplicate build")}}
        yield();resume.complete(Unit)
        first.await().source.close();second.await().source.close();assertEquals(1,builds)
        cache.close();assertEquals(1,deletes.get())
    }
    @Test fun cancelledWaiterDoesNotCancelBuilderOrStealReference()=runBlocking {
        val cache=DetailGridCache{it()};val entered=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>();val deletes=AtomicInteger()
        val first=async {cache.acquire("key"){entered.complete(Unit);resume.await();built("shared",deletes)}}
        entered.await();val waiter=launch {cache.acquire("key"){error("Duplicate build")}}
        yield();waiter.cancelAndJoin();resume.complete(Unit)
        first.await().source.close();cache.close();assertEquals(1,deletes.get())
    }
    @Test fun cancellationAfterBuildDisposesUnpublishedGrid()=runBlocking {
        val cache=DetailGridCache{it()};val deletes=AtomicInteger()
        val worker=launch {cache.acquire("key"){built("cancelled",deletes).also{currentCoroutineContext().cancel()}}}
        worker.join();assertEquals(1,deletes.get())
        cache.acquire("key"){built("retry",deletes)}.source.close();cache.close();assertEquals(2,deletes.get())
    }
    @Test fun cancelledHitReleasesReaderWithoutEvictingValidOwner()=runBlocking {
        val cache=DetailGridCache{it()};val deletes=AtomicInteger();var cancelHit:Job?=null
        cache.acquire("key"){built("first",deletes,{cancelHit?.cancel();true})}.source.close()
        val worker=launch(start=CoroutineStart.LAZY){cache.acquire("key"){error("Unexpected rebuild")}}
        cancelHit=worker;worker.start();worker.join()
        assertEquals(0,deletes.get());cancelHit=null
        cache.acquire("key"){error("Cancelled hit must retain owner")}.source.close();cache.close();assertEquals(1,deletes.get())
    }
    @Test fun abandonedNewPublicationReleasesOwnerButAbandonedHitKeepsOwner()=runBlocking {
        val cache=DetailGridCache{it()};val deletes=AtomicInteger()
        val first=cache.acquire("key"){built("first",deletes)}
        first.abandon();first.abandon();assertEquals(1,deletes.get())
        val next=cache.acquire("key"){built("next",deletes)};next.source.close()
        val hit=cache.acquire("key"){error("Unexpected rebuild")};hit.abandon();hit.abandon()
        assertEquals(1,deletes.get());cache.close();assertEquals(2,deletes.get())
    }
    @Test fun invalidatingAcquisitionPreservesOtherReaders()=runBlocking {
        val cache=DetailGridCache{it()};val deletes=AtomicInteger()
        val first=cache.acquire("key"){built("first",deletes)}
        val other=cache.acquire("key"){error("Unexpected rebuild")}
        first.invalidate();assertEquals(0,deletes.get());other.source.close();assertEquals(1,deletes.get());cache.close()
    }
    @Test fun retirementIsQueuedExactlyOnceAndFailureCannotKeepCacheOpen()=runBlocking {
        val tasks=java.util.ArrayDeque<()->Unit>();val deletes=AtomicInteger()
        val cache=DetailGridCache{tasks.add(it)}
        val first=cache.acquire("key"){DetailGridCache.Built(DetailSource(1,1,"s","m","grid"){deletes.incrementAndGet();throw java.io.IOException("cleanup failed")},{true})}
        cache.close();assertTrue(tasks.isEmpty())
        try{cache.acquire("key"){error("Closed cache must not publish")};fail()}catch(_:IllegalStateException){}
        first.source.close();first.source.close();cache.close()
        assertEquals(1,tasks.size);assertEquals(0,deletes.get())
        val waiter=async {cache.awaitRetirements()};yield();assertFalse(waiter.isCompleted)
        tasks.removeFirst().invoke();waiter.await();assertEquals(1,deletes.get());assertTrue(tasks.isEmpty())
        cache.close();first.source.close();assertTrue(tasks.isEmpty())
    }
    @Test fun oversizeGridCannotBecomeCacheOwner()=runBlocking {
        val cache=DetailGridCache{it()};val deletes=AtomicInteger()
        try{cache.acquire("key"){built("oversize",deletes,width=1537)};fail()}catch(_:IllegalArgumentException){}
        assertEquals(1,deletes.get());cache.close()
    }
}
