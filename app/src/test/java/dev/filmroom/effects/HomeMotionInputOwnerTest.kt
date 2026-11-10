package dev.filmroom.effects

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** New regression tests for reconstructed exp6 ownership semantics, not recovered test sources. */
class HomeMotionInputOwnerTest {
    private fun video()=File.createTempFile("home-motion-lease-",".mp4").apply{writeBytes(byteArrayOf(1,2,3))}
    @Test fun extractionIsSharedAndLastDecoderOwnsDeletion(){
        val file=video();var calls=0;var sourceClosed=0
        val owner=HomeMotionInputOwner.forTest(AutoCloseable{sourceClosed++}){calls++;file}
        val first=owner.acquire();val second=owner.acquire();val decoder=first.retain()
        assertEquals(1,calls);assertSame(file,second.file)
        owner.close();first.close();second.close()
        assertTrue(file.exists());assertEquals(0,sourceClosed)
        decoder.close();decoder.close();owner.close()
        assertFalse(file.exists());assertEquals(1,sourceClosed)
        assertThrows(IllegalStateException::class.java){decoder.retain()}
    }
    @Test fun stillImageNegativeResultIsCached(){
        var calls=0
        val owner=HomeMotionInputOwner.forTest{calls++;null}
        owner.acquire().use{assertNull(it.file)};owner.acquire().close()
        assertEquals(1,calls);owner.close()
    }
    @Test fun cancellationAfterExtractDeletesOutputAndAllowsRetry(){
        val first=video();val second=video();var calls=0;var cancel=false
        val owner=HomeMotionInputOwner.forTest{calls++;if(calls==1){cancel=true;first}else second}
        assertThrows(CancellationException::class.java){owner.acquire{if(cancel)throw CancellationException()}}
        assertFalse(first.exists());cancel=false
        owner.acquire().use{assertSame(second,it.file)}
        owner.close();assertFalse(second.exists());assertEquals(2,calls)
    }
    @Test fun closingOwnerDuringExtractionDoesNotCloseSourceOrVideoEarly(){
        val file=video();val entered=CountDownLatch(1);val finish=CountDownLatch(1);val closed=AtomicInteger()
        val pool=Executors.newSingleThreadExecutor()
        val owner=HomeMotionInputOwner.forTest(AutoCloseable{closed.incrementAndGet()}){entered.countDown();check(finish.await(5,TimeUnit.SECONDS));file}
        try {
            val result=pool.submit<HomeMotionInputOwner.Lease>{owner.acquire()}
            assertTrue(entered.await(5,TimeUnit.SECONDS));owner.close()
            assertEquals(0,closed.get());finish.countDown()
            val lease=result.get(5,TimeUnit.SECONDS);assertTrue(file.exists())
            lease.close();assertFalse(file.exists());assertEquals(1,closed.get())
        }finally{finish.countDown();pool.shutdownNow();owner.close();file.delete()}
    }
    @Test fun concurrentAcquiresOnlyExtractOnce(){
        val file=video();val calls=AtomicInteger();val pool=Executors.newFixedThreadPool(2)
        val owner=HomeMotionInputOwner.forTest{calls.incrementAndGet();Thread.sleep(30);file}
        try {
            val first=pool.submit<HomeMotionInputOwner.Lease>{owner.acquire()}
            val second=pool.submit<HomeMotionInputOwner.Lease>{owner.acquire()}
            first.get(5,TimeUnit.SECONDS).close();second.get(5,TimeUnit.SECONDS).close()
            assertEquals(1,calls.get())
        }finally{pool.shutdownNow();owner.close()}
        assertFalse(file.exists())
    }
    @Test fun closedCacheRejectsNewAcquiresButExistingLeaseMayRetain(){
        val file=video();val owner=HomeMotionInputOwner.forTest{file};val lease=owner.acquire()
        owner.close()
        assertThrows(IllegalStateException::class.java){owner.acquire()}
        val decoder=lease.retain();lease.close();assertTrue(file.exists());decoder.close();assertFalse(file.exists())
    }
}
