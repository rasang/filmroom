package dev.filmroom.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreviewMeteringCacheTest {
    @Test fun concurrentFitAndDetailShareOneImmutableResult()=runBlocking {
        val cache=PreviewMeteringCache<Any>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val first=async {cache.get("source/data/algorithm","partial"){calls++;entered.complete(Unit);release.await();Any()}}
        entered.await()
        val second=async {cache.get("source/data/algorithm","partial"){calls++;Any()}}
        yield();release.complete(Unit)
        assertSame(first.await(),second.await());assertEquals(1,calls)
    }
    @Test fun modeSourceMappingDataAndAlgorithmInvalidateButViewportDoesNot()=runBlocking {
        val cache=PreviewMeteringCache<Int>();var calls=0
        suspend fun get(key:String="source/map/data/algorithm",method:String="average")=cache.get(key,method){++calls}
        assertEquals(1,get());repeat(10){assertEquals(1,get())}
        assertEquals(2,get(method="partial"));assertEquals(1,get())
        assertEquals(3,get("new-source/map/data/algorithm"));assertEquals(4,get("new-source/new-map/data/algorithm"))
        assertEquals(5,get("new-source/new-map/new-data/algorithm"));assertEquals(6,get("new-source/new-map/new-data/new-algorithm"))
    }
    @Test fun failedOrCancelledBuildNeverPublishes()=runBlocking {
        val cache=PreviewMeteringCache<Int>()
        try {cache.get("k","average"){error("failure")};fail()}catch(_:IllegalStateException){}
        val entered=CompletableDeferred<Unit>()
        val cancelled=launch {cache.get("k","average"){entered.complete(Unit);awaitCancellation()}}
        entered.await();cancelled.cancelAndJoin()
        assertEquals(3,cache.get("k","average"){3})
    }
    @Test fun closeRejectsLateSuccessAndFurtherReaders()=runBlocking {
        val cache=PreviewMeteringCache<Int>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        supervisorScope {
            val late=async {runCatching {cache.get("k","average"){entered.complete(Unit);release.await();7}}}
            entered.await();cache.close();release.complete(Unit)
            assertTrue(late.await().exceptionOrNull() is IllegalStateException)
        }
        try{cache.get("k","average"){fail();0};fail()}catch(_:IllegalStateException){}
    }
    @Test fun cancellationAfterUncancellableNativeReturnCannotPublish()=runBlocking {
        val cache=PreviewMeteringCache<Int>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val late=launch {cache.get("k","average"){withContext(NonCancellable){entered.complete(Unit);release.await()};7}}
        entered.await();late.cancel();release.complete(Unit);late.join()
        assertEquals(9,cache.get("k","average"){9})
    }
    @Test fun separateSourceOwnersNeverShare()=runBlocking {
        val a=PreviewMeteringCache<String>();val b=PreviewMeteringCache<String>()
        assertNotEquals(a.get("k","average"){it},b.get("k","average"){it})
    }
}
