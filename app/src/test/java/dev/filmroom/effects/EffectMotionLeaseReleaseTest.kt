package dev.filmroom.effects

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** New regression coverage for the APK's asynchronous ownership callbacks. */
class EffectMotionLeaseReleaseTest {
    @Test fun surfaceReleaseCompletesOnceEvenWhenSurfaceThrows(){
        val surface=AtomicInteger();val completed=AtomicInteger()
        val release=EffectSurfaceRelease({surface.incrementAndGet();throw IllegalStateException("synthetic")},{completed.incrementAndGet()})
        assertThrows(IllegalStateException::class.java){release.release()}
        release.release()
        assertEquals(1,surface.get());assertEquals(1,completed.get())
    }
    @Test fun bitmapDecoderRetainsInputUntilActualWorkerClose(){
        val entered=CountDownLatch(1);val finish=CountDownLatch(1);val completed=CountDownLatch(1)
        val sourceClosed=AtomicInteger();val callback=AtomicInteger()
        val preview=EffectMotionPreview(File("unused"),sourceFactory={object:EffectMotionFrameSource {
            override fun reset()=Unit
            override fun frameAt(timeMs:Long,slow:Boolean):EffectMotionFrame?{entered.countDown();check(finish.await(5,TimeUnit.SECONDS));return null}
            override fun isUnavailable()=false
            override fun close(){sourceClosed.incrementAndGet()}
        }},onClosed={callback.incrementAndGet();completed.countDown()})
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS));preview.close();preview.close()
            assertEquals(0,callback.get());assertEquals(0,sourceClosed.get())
            finish.countDown();assertTrue(completed.await(5,TimeUnit.SECONDS))
            assertEquals(1,sourceClosed.get());assertEquals(1,callback.get())
        }finally{finish.countDown();preview.close()}
    }
}
