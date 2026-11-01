package dev.filmroom

import dev.filmroom.border.BorderMemoryAdmission
import dev.filmroom.engine.*
import dev.filmroom.io.PhotoException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class BorderMemoryAdmissionHostTest {
    private val budget=BorderMemoryAdmission.DECODE_ESTIMATE_BYTES
    private val ample=NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)
    private val exact=NativeMemorySnapshot(64L*1024*1024+2*budget,0,false)
    @Test fun osLowMemoryFailsBeforeWorkAndWithoutLeakingReservation(){
        var called=false
        val error=assertThrows(PhotoException::class.java){
            BorderMemoryAdmission.withBudget(budget,{ample.copy(lowMemory=true)},{Long.MAX_VALUE}){called=true}
        }
        assertFalse(called);assertEquals(PhotoException.Reason.TOO_LARGE,error.reason)
        NativeWorkReservations.retain(budget,{exact}).close()
    }
    @Test fun heapHeadroomIncludesSafetyMarginAndFailsBeforeWork(){
        var called=false
        assertThrows(PhotoException::class.java){
            BorderMemoryAdmission.withBudget(budget,{ample},{budget+BorderMemoryAdmission.HEAP_MARGIN_BYTES-1}){called=true}
        }
        assertFalse(called)
        assertEquals(42,BorderMemoryAdmission.withBudget(budget,{ample},{budget+BorderMemoryAdmission.HEAP_MARGIN_BYTES}){42})
    }
    @Test fun cancellationAndFailureAlwaysReleaseLeaseForFutureWork(){
        assertThrows(CancellationException::class.java){
            BorderMemoryAdmission.withBudget(budget,{exact},{Long.MAX_VALUE}){
                assertThrows(NativeWorkUnavailable::class.java){NativeWorkReservations.retain(1,{exact}).close()}
                throw CancellationException("authored cancel")
            }
        }
        assertEquals("next",BorderMemoryAdmission.withBudget(budget,{exact},{Long.MAX_VALUE}){"next"})
        assertThrows(IllegalStateException::class.java){
            BorderMemoryAdmission.withBudget(budget,{exact},{Long.MAX_VALUE}){error("authored failure")}
        }
        NativeWorkReservations.retain(budget,{exact}).close()
    }
    @Test fun freshOsSnapshotAndSharedLedgerAreUsedForEveryAttempt(){
        var calls=0;val memory={calls++;exact}
        val held=NativeWorkReservations.retain(budget,memory)
        try {
            assertThrows(PhotoException::class.java){BorderMemoryAdmission.withBudget(budget,memory,{Long.MAX_VALUE}){error("must not run")}}
        }finally{held.close()}
        BorderMemoryAdmission.withBudget(budget,memory,{Long.MAX_VALUE}){}
        assertEquals(3,calls)
    }
}
