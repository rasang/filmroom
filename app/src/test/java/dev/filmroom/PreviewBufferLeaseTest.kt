package dev.filmroom

import dev.filmroom.engine.PreviewBufferPool
import org.junit.Assert.*
import org.junit.Test

class PreviewBufferLeaseTest {
    @Test fun warmReuseIsBoundedAndNeverReusesOutstandingLease(){
        val pool=PreviewBufferPool(128)
        val first=pool.acquire(PreviewBufferPool.Kind.RGBA8_OUTPUT,64)!!
        val second=pool.acquire(PreviewBufferPool.Kind.RGBA8_OUTPUT,64)!!
        assertNotSame(first.buffer,second.buffer)
        assertNull(pool.acquire(PreviewBufferPool.Kind.RGBA8_OUTPUT,1))
        val firstBuffer=first.buffer;first.close();first.close()
        repeat(20){pool.acquire(PreviewBufferPool.Kind.RGBA8_OUTPUT,64)!!.use{assertSame(firstBuffer,it.buffer)}}
        assertEquals(PreviewBufferPool.Usage(128,64,1),pool.usage())
        second.close();pool.close();assertEquals(PreviewBufferPool.Usage(0,0,0),pool.usage())
    }
    @Test fun trimAndCloseNeverReturnAnInFlightBuffer(){
        val pool=PreviewBufferPool(128)
        val first=pool.acquire(PreviewBufferPool.Kind.RGBA8_OUTPUT,64)!!
        pool.acquire(PreviewBufferPool.Kind.LINEAR_INPUT,64)!!.close()
        pool.trim();assertEquals(PreviewBufferPool.Usage(64,64,0),pool.usage())
        pool.close();assertEquals(PreviewBufferPool.Usage(64,64,0),pool.usage())
        first.close();assertEquals(PreviewBufferPool.Usage(0,0,0),pool.usage())
    }
    @Test fun shapeKindFailureAndOversizedRequestDoNotLeak(){
        val pool=PreviewBufferPool(128)
        assertNull(pool.acquire(PreviewBufferPool.Kind.LINEAR_INPUT,129))
        val first=pool.acquire(PreviewBufferPool.Kind.LINEAR_INPUT,64)!!;val firstBuffer=first.buffer;first.close()
        val other=pool.acquire(PreviewBufferPool.Kind.RGBA8_OUTPUT,64)!!
        assertNotSame(firstBuffer,other.buffer);other.discard();other.close()
        val reshaped=pool.acquire(PreviewBufferPool.Kind.LINEAR_INPUT,128)!!
        assertEquals(PreviewBufferPool.Usage(128,128,0),pool.usage())
        reshaped.discard();assertEquals(PreviewBufferPool.Usage(0,0,0),pool.usage())
    }
}
