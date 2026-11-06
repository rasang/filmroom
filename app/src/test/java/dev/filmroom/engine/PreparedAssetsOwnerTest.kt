package dev.filmroom.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PreparedAssetsOwnerTest {
    @Test fun oneLeasePerHandleAcrossAllParameterAndSizeChanges() {
        val calls=mutableListOf<Pair<Long,Long>>();var reserved=0L;var closes=0
        val owner=PreparedAssetsOwner({h,b->calls+=h to b},{b,_->reserved+=b;AutoCloseable{reserved-=b;closes++}})
        repeat(20){assertTrue(owner.prepare(7))}
        assertEquals(PreparedAssetsOwner.BYTES,reserved);assertEquals(listOf(7L to PreparedAssetsOwner.BYTES),calls)
        owner.retire();assertEquals(0L,reserved);assertEquals(1,closes);owner.retire();owner.close();assertEquals(1,closes)
    }
    @Test fun pressureDisablesNativeBeforeReleasingPassiveCharge() {
        val order=mutableListOf<String>();var pressure:(()->Unit)?=null
        val owner=PreparedAssetsOwner({_,b->order+="native:$b"},{_,p->pressure=p;order+="reserve";AutoCloseable{order+="release"}})
        owner.prepare(1);pressure!!.invoke()
        assertEquals(listOf("reserve","native:${PreparedAssetsOwner.BYTES}","native:0","release"),order)
    }
    @Test fun lowMemorySkipsReuseWithoutNativeEnableOrLeakedLease() {
        var calls=0;val owner=PreparedAssetsOwner({_,_->calls++},{_,_->throw NativeWorkUnavailable()})
        assertFalse(owner.prepare(1));owner.close();assertEquals(0,calls)
    }
    @Test fun nativeRetirementFailureKeepsChargeUntilSuccessfulRetry() {
        var failRetirement=true;var closes=0
        val owner=PreparedAssetsOwner({_,b->if(b==0L && failRetirement)error("still leased")},{_,_->AutoCloseable{closes++}})
        owner.prepare(1)
        try{owner.retire();fail("must not release unacknowledged bytes")}catch(_:IllegalStateException){}
        assertEquals(0,closes);failRetirement=false;owner.retire();assertEquals(1,closes)
    }
    @Test fun stalePressureCallbackCannotEvictReplacementHandle() {
        val callbacks=mutableListOf<()->Unit>();val native=mutableListOf<Pair<Long,Long>>();var closes=0
        val owner=PreparedAssetsOwner({h,b->native+=h to b},{_,p->callbacks+=p;AutoCloseable{closes++}})
        owner.prepare(1);owner.prepare(2);callbacks[0]()
        assertEquals(listOf(1L to PreparedAssetsOwner.BYTES,1L to 0L,2L to PreparedAssetsOwner.BYTES),native);assertEquals(1,closes)
        owner.close();assertEquals(2,closes)
    }
    @Test fun pressureWaitKeepsChargeUntilNativePhysicalRetirement() {
        val entered=CountDownLatch(1);val allow=CountDownLatch(1);val released=AtomicInteger();var pressure:(()->Unit)?=null
        val owner=PreparedAssetsOwner({_,b->if(b==0L){entered.countDown();check(allow.await(5,TimeUnit.SECONDS))}},{_,p->pressure=p;AutoCloseable{released.incrementAndGet()}})
        owner.prepare(1);val thread=Thread{pressure!!.invoke()};thread.start();assertTrue(entered.await(5,TimeUnit.SECONDS));assertEquals(0,released.get())
        allow.countDown();thread.join(5000);assertFalse(thread.isAlive);assertEquals(1,released.get())
    }
    @Test fun enableFailureRollsBackNativeBeforeLeaseRelease() {
        val order=mutableListOf<String>()
        val owner=PreparedAssetsOwner({_,b->order+="native:$b";if(b!=0L)error("failed enable")},{_,_->AutoCloseable{order+="release"}})
        try{owner.prepare(1);fail("expected failure")}catch(_:IllegalStateException){}
        assertEquals(listOf("native:${PreparedAssetsOwner.BYTES}","native:0","release"),order)
    }
    @Test fun closeIsIdempotentAndPreventsReadmission() {
        var closes=0;val owner=PreparedAssetsOwner({_,_->},{_,_->AutoCloseable{closes++}})
        owner.prepare(1);owner.close();owner.close();assertEquals(1,closes)
        try{owner.prepare(1);fail("closed owner readmitted")}catch(_:IllegalStateException){}
    }
}
