package dev.filmroom

import dev.filmroom.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ExportWorkAdmissionsTest {
    private val mib=1024L*1024
    private fun ample()=NativeMemorySnapshot(4L*1024*mib,256*mib,false)
    @Test fun queuedExportDoesNotReservePreviewSlotAndWaitingCancellationIsClean()=runBlocking {
        val admission=ExportWorkAdmission();val limiter=NativeWorkLimiter()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val first=launch { admission.run { limiter.run(128*mib,::ample){entered.complete(Unit);release.await()} } }
        entered.await();assertEquals(1,limiter.activeCount())
        val secondRequested=CompletableDeferred<Unit>();val secondEntered=AtomicBoolean(false)
        val second=launch(start=CoroutineStart.UNDISPATCHED){secondRequested.complete(Unit);admission.run {limiter.run(128*mib,::ample){secondEntered.set(true)}}}
        secondRequested.await();assertFalse(secondEntered.get());assertEquals(1,limiter.activeCount())
        val previewEntered=CompletableDeferred<Unit>();val previewRelease=CompletableDeferred<Unit>()
        val preview=launch {limiter.run(NativeWorkReservations.previewEstimate(512,512),::ample){previewEntered.complete(Unit);previewRelease.await()}}
        withTimeout(2000){previewEntered.await()};assertEquals(2,limiter.activeCount())
        second.cancelAndJoin();assertFalse(secondEntered.get());assertEquals(2,limiter.activeCount())
        previewRelease.complete(Unit);preview.join();release.complete(Unit);first.join()
        assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
        assertEquals(7,admission.run {limiter.run(128*mib,::ample){7}})
    }
    @Test fun ownerExceptionAndCancellationReleaseBothAdmissions()=runBlocking {
        val admission=ExportWorkAdmission();val limiter=NativeWorkLimiter()
        try {admission.run {limiter.run(128*mib,::ample){throw IllegalStateException("test")}};fail()}catch(_:IllegalStateException){}
        assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
        val entered=CompletableDeferred<Unit>();val owner=launch {admission.run {limiter.run(128*mib,::ample){entered.complete(Unit);awaitCancellation()}}}
        entered.await();owner.cancelAndJoin();assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
        assertEquals(9,admission.run {limiter.run(128*mib,::ample){9}})
    }
}
