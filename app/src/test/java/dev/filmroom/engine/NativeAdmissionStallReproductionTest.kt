package dev.filmroom.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fault injection, not a reproduction of the user's device failure.
 * The 539af286 admission arithmetic and acquire branches are unchanged in the
 * instrumented implementation. No RenderBoundaryTrace is installed here.
 * Repeated acquire calls simulate 100 ms retries; they do NOT measure wall time.
 */
class NativeAdmissionStallReproductionTest {
    private val mib = NativeMemoryPolicy.MIB
    private fun ample() = NativeMemorySnapshot(4096 * mib, 256 * mib, false)
    private fun estimate(height: Int) = NativeWorkReservations.previewEstimate(512, height)
    private fun acquire(limiter: NativeWorkLimiter, bytes: Long, memory: NativeMemorySnapshot): Any =
        NativeWorkLimiter::class.java.getDeclaredMethod("acquire", java.lang.Long.TYPE, NativeMemorySnapshot::class.java)
            .apply { isAccessible = true }.invoke(limiter, bytes, memory)
    private fun kind(attempt: Any) = attempt.javaClass.simpleName
    @Suppress("UNCHECKED_CAST")
    private fun evict(attempt: Any) = (attempt.javaClass.getDeclaredMethod("getCallbacks")
        .apply { isAccessible = true }.invoke(attempt) as List<() -> Unit>).forEach { it() }

    @Test fun loggedPreviewDimensionsHaveSmallParentChargesDespite512MiBNativeBudget() {
        assertEquals(210890752L, estimate(338) + 16 * mib)
        assertEquals(223608832L, estimate(384) + 16 * mib)
        val smallestHeadroomFor512Budget = 2048 * mib
        assertEquals(512, NativeMemoryPolicy.chooseBudgetMiB(smallestHeadroomFor512Budget + 128 * mib, 128 * mib, false))
        val capacity = (smallestHeadroomFor512Budget - 64 * mib) / 2
        assertTrue(estimate(338) + 16 * mib < capacity)
        assertTrue(estimate(384) + 16 * mib < capacity)
    }

    @Test fun idleLowHeadroomRejectsOnFirstSnapshotRatherThanWaiting() = runBlocking {
        for (height in listOf(338, 384)) {
            val limiter = NativeWorkLimiter()
            val snapshots = AtomicInteger()
            var nativeEntered = false
            val failure = runCatching {
                withTimeout(2000) {
                    limiter.run(estimate(height), {
                        snapshots.incrementAndGet()
                        NativeMemorySnapshot(256 * mib, 128 * mib, false)
                    }) { nativeEntered = true }
                }
            }.exceptionOrNull()
            assertTrue(failure is NativeWorkUnavailable)
            assertEquals(1, snapshots.get())
            assertFalse(nativeEntered)
            assertEquals(0, limiter.activeCount())
            assertEquals(0L, limiter.reservedBytes())
        }
    }

    @Test fun occupiedWorkersPermitArbitrarilyManyRetriesButWaiterCancellationDoesNotLeak() = runBlocking {
        val limiter = NativeWorkLimiter()
        val entered = List(2) { CompletableDeferred<Unit>() }
        val holders = entered.map { signal -> launch {
            limiter.run(64 * mib, ::ample) { signal.complete(Unit); awaitCancellation() }
        } }
        try {
            withTimeout(2000) { entered.forEach { it.await() } }
            val reserved = limiter.reservedBytes()
            for ((height, retries) in listOf(338 to 249, 384 to 570)) {
                repeat(retries) { assertEquals("Wait", kind(acquire(limiter, estimate(height), ample()))) }
                val attempted = CompletableDeferred<Unit>()
                val nativeEntered = AtomicBoolean()
                val waiter = launch {
                    limiter.run(estimate(height), { attempted.complete(Unit); ample() }) { nativeEntered.set(true) }
                }
                withTimeout(2000) { attempted.await(); waiter.cancelAndJoin() }
                assertFalse(nativeEntered.get())
                assertEquals(2, limiter.activeCount())
                assertEquals(reserved, limiter.reservedBytes())
            }
        } finally { holders.forEach { it.cancelAndJoin() } }
        assertEquals(0, limiter.activeCount())
        assertEquals(0L, limiter.reservedBytes())
    }

    @Test fun pendingEvictionCanWaitWithoutAnActiveWorkerUntilOwnerActuallyReleases() = runBlocking {
        for (height in listOf(338, 384)) {
            val limiter = NativeWorkLimiter()
            val pressureCalls = AtomicInteger()
            val passiveBytes = 100 * mib
            val passive = limiter.retain(passiveBytes, ample()) { pressureCalls.incrementAndGet() }
            val charge = estimate(height) + 16 * mib
            val capacity = charge + passiveBytes - 1
            val pressured = NativeMemorySnapshot(128 * mib + 64 * mib + 2 * capacity, 128 * mib, false)
            try {
                val first = acquire(limiter, estimate(height), pressured)
                assertEquals("Evict", kind(first)); evict(first)
                repeat(570) { assertEquals("Wait", kind(acquire(limiter, estimate(height), pressured))) }
                assertEquals(1, pressureCalls.get())
                assertEquals(0, limiter.activeCount())
                val attempted = CompletableDeferred<Unit>()
                val nativeEntered = AtomicBoolean()
                val waiter = launch {
                    limiter.run(estimate(height), { attempted.complete(Unit); pressured }) { nativeEntered.set(true) }
                }
                withTimeout(2000) { attempted.await(); waiter.cancelAndJoin() }
                assertFalse(nativeEntered.get())
                assertEquals(passiveBytes, limiter.reservedBytes())
            } finally { passive.close() }
            assertEquals(7, withTimeout(2000) { limiter.run(estimate(height), { pressured }) { 7 } })
            assertEquals(0L, limiter.reservedBytes())
        }
    }

    @Test fun helperRetentionNeverWaitsForWorkerSlotAndInsufficientBytesRejectSynchronously() = runBlocking {
        val limiter = NativeWorkLimiter()
        val entered = List(2) { CompletableDeferred<Unit>() }
        val holders = entered.map { signal -> launch {
            limiter.run(64 * mib, ::ample) { signal.complete(Unit); awaitCancellation() }
        } }
        try {
            withTimeout(2000) { entered.forEach { it.await() } }
            val bytes = limiter.reservedBytes()
            val helper = limiter.retainForWork(150 * mib, ample())
            assertEquals(2, limiter.activeCount())
            assertEquals(bytes + 166 * mib, limiter.reservedBytes())
            helper.close()
            assertEquals(bytes, limiter.reservedBytes())
            assertTrue(runCatching { limiter.retainForWork(1900 * mib, ample()) }.exceptionOrNull() is NativeWorkUnavailable)
            assertEquals(bytes, limiter.reservedBytes())
        } finally { holders.forEach { it.cancelAndJoin() } }
        assertEquals(0L, limiter.reservedBytes())
    }
}
