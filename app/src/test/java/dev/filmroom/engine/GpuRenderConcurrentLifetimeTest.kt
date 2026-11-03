package dev.filmroom.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class GpuRenderConcurrentLifetimeTest {
    @Test fun readyVersusCrossThreadCancelNeverReleasesAttemptedRun() {
        repeat(100) {
            val lock = Any(); val life = GpuProbeLifetime(1)
            life.requestHelper(); life.acknowledgeStartup(1)
            val start = CountDownLatch(1)
            val dispatch = Thread { start.await(); synchronized(lock) { life.attemptRun(1) } }
            val cancel = Thread { start.await(); synchronized(lock) { life.stop(1); if (!life.runAttempted) life.revokeBeforeRun(1) } }
            dispatch.start(); cancel.start(); start.countDown(); dispatch.join(); cancel.join()
            if (life.runAttempted) {
                assertFalse(life.canRelease)
                assertTrue(life.died(1)); assertTrue(life.canRelease)
            } else {
                assertTrue(life.canRelease); assertFalse(life.deathConfirmed)
            }
            assertTrue(life.finish()); assertFalse(life.attemptRun(1))
        }
    }
}
