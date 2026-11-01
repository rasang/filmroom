package dev.filmroom

import dev.filmroom.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class NativeWorkReservationsTest {
    private val mib=1024L*1024
    private fun ample()=NativeMemorySnapshot(4L*1024*mib,256*mib,false)
    @Test fun exportAndPreviewMayOverlapButThirdHeavyCallWaits()=runBlocking {
        val limiter=NativeWorkLimiter();val release=CompletableDeferred<Unit>();val first=CompletableDeferred<Unit>();val second=CompletableDeferred<Unit>();val third=AtomicBoolean(false)
        val export=launch{limiter.run(120*mib,::ample){first.complete(Unit);release.await()}}
        first.await()
        val preview=launch{limiter.run(NativeWorkReservations.previewEstimate(512,512),::ample){second.complete(Unit);release.await()}}
        second.await();assertEquals(2,limiter.activeCount())
        val decode=launch{limiter.run(128*mib,::ample){third.set(true)}}
        delay(150);assertFalse(third.get());decode.cancelAndJoin();assertEquals(2,limiter.activeCount())
        release.complete(Unit);export.join();preview.join();assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
    }
    @Test fun exceptionAndHolderCancellationReleaseExactlyOnce()=runBlocking {
        val limiter=NativeWorkLimiter()
        try{limiter.run(120*mib,::ample){throw IllegalStateException("synthetic")};fail()}catch(_:IllegalStateException){}
        assertEquals(0,limiter.activeCount())
        val entered=CompletableDeferred<Unit>();val job=launch{limiter.run(120*mib,::ample){entered.complete(Unit);awaitCancellation()}}
        entered.await();job.cancelAndJoin();assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
        assertEquals(7,limiter.run(120*mib,::ample){7})
    }
    @Test fun weightedAdmissionDefersWithoutHoldingASecondPermit()=runBlocking {
        val limiter=NativeWorkLimiter();val memory={NativeMemorySnapshot(700*mib,128*mib,false)}
        val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val second=AtomicBoolean(false)
        val first=launch{limiter.run(120*mib,memory){entered.complete(Unit);gate.await()}}
        entered.await();val next=launch{limiter.run(160*mib,memory){second.set(true)}}
        delay(150);assertFalse(second.get());assertEquals(1,limiter.activeCount())
        gate.complete(Unit);first.join();withTimeout(2000){next.join()};assertTrue(second.get());assertEquals(0,limiter.activeCount())
    }
    @Test fun freshLowMemorySnapshotRejectsAndNeverStartsNative()=runBlocking {
        val limiter=NativeWorkLimiter();var started=false
        for(memory in listOf(NativeMemorySnapshot(8L*1024*mib,128*mib,true),NativeMemorySnapshot(160*mib,128*mib,false))) {
            try{limiter.run(120*mib,{memory}){started=true};fail()}catch(_:NativeWorkUnavailable){}
        }
        assertFalse(started);assertEquals(0,limiter.activeCount())
        assertTrue(NativeWorkReservations.previewEstimate(256,256)<NativeWorkReservations.previewEstimate(512,512))
    }
    @Test fun cancellationAtGrantEdgeCannotOrphanLease()=runBlocking {
        val limiter=NativeWorkLimiter();var entered=false
        val owner=Job();val task=launch(owner) {
            limiter.run(120*mib,{owner.cancel();ample()}){entered=true}
        }
        task.join();assertFalse(entered);assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
        assertEquals(9,limiter.run(120*mib,::ample){9})
    }

}
