package dev.filmroom.engine

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GpuScienceInitializationRaceTest {
    private data class Owner(val controller: GpuScienceSessionController, val closes: AtomicInteger, val pressure: () -> Unit)
    private fun owner(epoch: Long, pressureBeforeLeasePublication: Boolean): Owner {
        val closes = AtomicInteger()
        var pressure: () -> Unit = {}
        val controller = GpuScienceSessionController(
            RuntimeEnvironment.getApplication(), epoch, GpuScienceSessionBudget(1000, 100, 8),
            leaseFactory = { _, budget, life, onPressure ->
                pressure = onPressure
                val lease = GpuScienceSessionLease.forTesting(budget, life, object : RetainedNativeWork {
                    override fun close() { closes.incrementAndGet() }
                    override suspend fun <T> run(memory: () -> NativeMemorySnapshot, work: suspend () -> T): T = error("No worker permitted")
                })
                if (pressureBeforeLeasePublication) {
                    // Deterministically reproduce the limiter publishing its callback
                    // before the controller receives the retained lease handle.
                    onPressure(); onPressure()
                    assertEquals(0, closes.get())
                }
                lease
            },
            released = GpuScienceSessionPool::releaseOwner,
        )
        return Owner(controller, closes, pressure)
    }
    private fun frame() = GpuScienceSessionPool.openFrame(RuntimeEnvironment.getApplication(), setOf(GpuRenderOperation.FILM_EXPOSURE), null, true)

    @Test fun pressureBeforeLeasePublicationClosesExactlyOnceAndCannotPoisonPool() {
        val first = owner(301, true)
        assertTrue(first.controller.hasStopped()); assertEquals(1, first.closes.get())
        val failedFrame = frame()
        assertFalse(GpuScienceSessionPool.publishNewOwner(failedFrame, first.controller))
        assertNull(failedFrame.controller)
        assertFalse(first.controller.claimStartupOwner(failedFrame))
        first.controller.start() // Permanently retired; must not bind or start a helper.
        first.pressure()
        assertEquals(1, first.closes.get())
        val next = owner(302, false)
        try {
            assertTrue(GpuScienceSessionPool.publishNewOwner(frame(), next.controller))
        } finally { next.controller.retire(GpuRenderCode.CANCELLED) }
        assertTrue(next.controller.hasStopped()); assertEquals(1, next.closes.get())
    }
    @Test fun pressureAfterConstructorBeforeStartupClaimIsDefiniteNoRun() {
        val first = owner(303, false)
        assertFalse(first.controller.hasStopped())
        first.pressure()
        assertTrue(first.controller.hasStopped()); assertEquals(1, first.closes.get())
        assertFalse(GpuScienceSessionPool.publishNewOwner(frame(), first.controller))
        val next = owner(304, false)
        try {
            val nextFrame = frame()
            assertTrue(GpuScienceSessionPool.publishNewOwner(nextFrame, next.controller))
            next.pressure() // Published, but no requestHelper: still a no-start proof.
            assertTrue(next.controller.hasStopped())
            assertEquals(1, next.closes.get())
        } finally { next.controller.retire(GpuRenderCode.CANCELLED) }
    }
}
