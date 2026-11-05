package dev.filmroom.engine

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class GpuScienceControlIdentityTest {
    @Test fun seedIsPositiveAndLeavesRoomForFirstIdentity() {
        repeat(16) { assertTrue(GpuScienceControlIdentity.positiveSeed() in 1L until Long.MAX_VALUE) }
    }
    @Test fun identitiesAreCheckedMonotonicAndNeverWrap() {
        val counter = AtomicLong(Long.MAX_VALUE - 1L)
        assertEquals(Long.MAX_VALUE, GpuScienceControlIdentity.next(counter))
        assertEquals(0L, GpuScienceControlIdentity.next(counter))
        assertEquals(Long.MAX_VALUE, counter.get())
        assertEquals(0L, GpuScienceControlIdentity.next(AtomicLong(-1L)))
        val normal = AtomicLong(7L)
        assertEquals(8L, GpuScienceControlIdentity.next(normal))
        assertEquals(9L, GpuScienceControlIdentity.next(normal))
    }
}
