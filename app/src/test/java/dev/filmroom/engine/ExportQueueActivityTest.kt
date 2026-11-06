package dev.filmroom.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExportQueueActivityTest {
    private val mib=1024L*1024
    private val ample=NativeMemorySnapshot(4L*1024*mib,0,false)

    @Test fun busyIsPublishedBeforePreemptionAndNestedEarlyReturnReleasesOnlyItsScope()=runBlocking {
        lateinit var activity:ExportQueueActivity
        var preemptions=0
        activity=ExportQueueActivity {assertTrue(activity.active);preemptions++}
        assertEquals(42,activity.run {
            assertTrue(activity.active)
            assertEquals(7,activity.run nested@{return@nested 7})
            assertTrue(activity.active)
            42
        })
        assertEquals(2,preemptions);assertFalse(activity.active)
    }

    @Test fun workAndPreemptionExceptionsReleaseActivity()=runBlocking {
        val activity=ExportQueueActivity {}
        try {activity.run {throw IllegalStateException("work")};fail()}catch(_:IllegalStateException){}
        assertFalse(activity.active)
        val broken=ExportQueueActivity {throw IllegalArgumentException("preempt")}
        try {broken.run {fail("work must not start")};fail()}catch(_:IllegalArgumentException){}
        assertFalse(broken.active)
    }

    @Test fun cancellationReleasesActivity()=runBlocking {
        val activity=ExportQueueActivity {};val entered=CompletableDeferred<Unit>()
        val task=launch {activity.run {entered.complete(Unit);awaitCancellation()}}
        try {withTimeout(2000){entered.await()};assertTrue(activity.active)}
        finally {task.cancelAndJoin()}
        assertFalse(activity.active)
    }

    @Test fun overlappingScopesStayBusyUntilTheLastFinally()=runBlocking {
        val activity=ExportQueueActivity {};val first=CompletableDeferred<Unit>();val second=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val a=launch {activity.run {first.complete(Unit);release.await()}}
        val b=launch {activity.run {second.complete(Unit);awaitCancellation()}}
        try {
            withTimeout(2000){first.await();second.await()}
            b.cancelAndJoin();assertTrue(activity.active)
            release.complete(Unit);withTimeout(2000){a.join()};assertFalse(activity.active)
        } finally {release.complete(Unit);a.cancelAndJoin();b.cancelAndJoin()}
    }

    @Test fun coldExportAdmissionCanNestWithoutChangingWarmRefinementOrWorkerPolicy()=runBlocking {
        assertFalse(ExportWorkAdmissions.active.value);assertFalse(ExportWorkAdmissions.prewarmBlocked)
        assertTrue(NativeWorkReservations.detailRetryIdle())
        withTimeout(2000) {ExportWorkAdmissions.withQueueActivity {
            assertTrue(ExportWorkAdmissions.prewarmBlocked)
            assertFalse("Warm queue activity is not scientific export activity",ExportWorkAdmissions.active.value)
            assertTrue("Queue activity holds no worker",NativeWorkReservations.detailRetryIdle())
            ExportWorkAdmissions.run {assertTrue(ExportWorkAdmissions.active.value);assertTrue(ExportWorkAdmissions.prewarmBlocked)}
            assertFalse(ExportWorkAdmissions.active.value);assertTrue(ExportWorkAdmissions.prewarmBlocked)
        }}
        assertFalse(ExportWorkAdmissions.active.value);assertFalse(ExportWorkAdmissions.prewarmBlocked)
        assertTrue(NativeWorkReservations.detailRetryIdle())
    }

    @Test fun actualIdleAdmissionRechecksQueueAfterAnEarlierEligibleObservation()=runBlocking {
        val activity=ExportQueueActivity {}
        val limiter=NativeWorkLimiter { !activity.active }
        assertFalse(activity.active) // Earlier timer/policy observation is stale below.
        activity.run {
            assertNull(limiter.tryRunIdle(64*mib,ample,{}){error("late work entered")})
            assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
        }
        assertEquals("ready",limiter.tryRunIdle(64*mib,ample,{}){"ready"})
        assertEquals(0L,limiter.reservedBytes())
    }

    @Test fun queueEnteringBetweenIdleCheckAndRegistrationStillPreemptsWithoutEarlyRelease()=runBlocking {
        val checked=CountDownLatch(1);val allowRegistration=CountDownLatch(1);val queueBusy=CountDownLatch(1)
        val preempted=CompletableDeferred<Unit>();val releaseIdle=CompletableDeferred<Unit>();val releaseQueue=CompletableDeferred<Unit>()
        lateinit var activity:ExportQueueActivity
        val limiter=NativeWorkLimiter {
            val eligible=!activity.active
            checked.countDown();check(allowRegistration.await(5,TimeUnit.SECONDS));eligible
        }
        activity=ExportQueueActivity {queueBusy.countDown();limiter.preemptIdle()}
        val idle=launch(Dispatchers.Default) {limiter.tryRunIdle(64*mib,ample,{preempted.complete(Unit)}){releaseIdle.await();"done"}}
        var queue:Job?=null
        try {
            assertTrue(checked.await(5,TimeUnit.SECONDS))
            queue=launch(Dispatchers.Default) {activity.run {releaseQueue.await()}}
            assertTrue(queueBusy.await(5,TimeUnit.SECONDS));assertTrue(activity.active)
            allowRegistration.countDown()
            withTimeout(5000){preempted.await()}
            assertEquals(1,limiter.activeCount());assertEquals(80*mib,limiter.reservedBytes())
            assertNull(limiter.tryRunIdle(64*mib,ample,{}){error("queue must block new idle work")})
            releaseIdle.complete(Unit);withTimeout(5000){idle.join()}
            assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
        } finally {
            allowRegistration.countDown();releaseIdle.complete(Unit);releaseQueue.complete(Unit)
            idle.cancelAndJoin();queue?.cancelAndJoin()
        }
        assertFalse(activity.active)
    }
}
