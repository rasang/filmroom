package dev.filmroom.engine

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class PreviewStageCacheOwnerTest {
    private class Harness {
        val events=mutableListOf<String>()
        val pressure=mutableListOf<()->Unit>()
        var reserved=0L
        var reject=false
        var failClear=false
        val owner=PreviewStageCacheOwner({handle,bytes->
            if(bytes==0L && failClear)throw IllegalStateException("clear failed")
            events+="native:$handle:$bytes"
        },{bytes,callback->
            if(reject)throw NativeWorkUnavailable()
            reserved+=bytes;pressure+=callback;events+="reserve:$bytes"
            AutoCloseable{reserved-=bytes;events+="release:$bytes"}
        })
    }
    @Test fun hitReservationIsReusedAndSourceOrSizeChangesRetireBeforeReconfigure() {
        val h=Harness();val bytes=PreviewStageCacheOwner.bytesFor(32,24)
        assertTrue(h.owner.prepare(1,"a",32,24));assertEquals(bytes,h.reserved)
        h.owner.prepare(1,"a",32,24);assertEquals(2,h.events.size)
        h.owner.prepare(1,"b",32,24)
        assertEquals(listOf("native:1:0","release:$bytes"),h.events.subList(2,4))
        h.owner.prepare(1,"b",24,32) // identity normally includes oriented dimensions, but bytes are equal.
        assertEquals(bytes,h.reserved)
        h.owner.prepare(1,"b-24x32",24,32);assertEquals(bytes,h.reserved)
        h.owner.prepare(1,"b-16x16",16,16);assertEquals(PreviewStageCacheOwner.bytesFor(16,16),h.reserved)
        h.owner.close();assertEquals(0,h.reserved);h.owner.close();assertEquals(0,h.reserved)
    }
    @Test fun pressureClearsNativeBeforeReleasingAndOldCallbackCannotClearNewOwner() {
        val h=Harness();h.owner.prepare(1,"a",32,24);val old=h.pressure.last()
        h.owner.prepare(1,"b",32,24);val live=h.reserved;val events=h.events.size
        old();assertEquals(live,h.reserved);assertEquals(events,h.events.size)
        h.pressure.last()();assertEquals(0,h.reserved)
        assertEquals("native:1:0",h.events[h.events.lastIndex-1]);assertTrue(h.events.last().startsWith("release:"))
        h.owner.prepare(1,"b",32,24);assertTrue(h.reserved>0);h.owner.close()
    }
    @Test fun failedAdmissionIsColdPathAndRetirementFailureKeepsCharge() {
        val h=Harness();h.reject=true;assertFalse(h.owner.prepare(1,"a",32,24));assertEquals(0,h.reserved);assertTrue(h.events.isEmpty())
        h.reject=false;h.owner.prepare(1,"a",32,24);val charge=h.reserved;h.failClear=true
        try{h.owner.retire();fail()}catch(_:IllegalStateException){}
        assertEquals(charge,h.reserved)
        h.failClear=false;h.owner.retire();assertEquals(0,h.reserved)
    }
    @Test fun coldCandidateRemainsActivelyChargedAfterConcurrentPassivePressure()=runBlocking {
        val limiter=NativeWorkLimiter();val mib=1024L*1024
        val memory={NativeMemorySnapshot(4096*mib,0,false)}
        var pressure:(()->Unit)?=null
        val owner=PreviewStageCacheOwner({_,_->},{bytes,callback->pressure=callback;limiter.retain(bytes,memory(),callback)})
        owner.prepare(1,"source-a",32,24)
        val passive=PreviewStageCacheOwner.bytesFor(32,24)
        val active=NativeWorkReservations.previewEstimate(32,24)+PreviewStageCacheOwner.candidateBytes(32,24)
        val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val worker=launch(Dispatchers.Default) {limiter.run(active,memory){
            assertEquals(passive+active+16*mib,limiter.reservedBytes());started.complete(Unit);finish.await()
            // Candidate may still be allocated/borrowed here, but its active48 B/pixel is prepaid.
            assertEquals(active+16*mib,limiter.reservedBytes())
        }}
        started.await();withContext(Dispatchers.IO){pressure!!.invoke()}
        assertEquals(active+16*mib,limiter.reservedBytes());finish.complete(Unit);worker.join()
        assertEquals(0,limiter.reservedBytes());owner.close()
        assertFalse(PreviewStageCacheOwner.candidateFits(512,512,224))
        assertTrue(PreviewStageCacheOwner.candidateFits(512,512,236))
        assertTrue(PreviewStageCacheOwner.candidateFits(768,768,420))
    }
    @Test fun closedOwnerAndBoundsNeverStartNewNativeWork() {
        val h=Harness();h.owner.close()
        try{h.owner.prepare(1,"a",32,24);fail()}catch(_:IllegalStateException){}
        for((w,ht) in listOf(0 to 1,1281 to 2000,Int.MAX_VALUE to Int.MAX_VALUE)) {
            try{PreviewStageCacheOwner.bytesFor(w,ht);fail()}catch(_:IllegalArgumentException){}catch(_:ArithmeticException){}
        }
        assertEquals(1024L*1024*60+65536,PreviewStageCacheOwner.bytesFor(1024,1024))
        assertEquals(1152L*1152*48+65536,PreviewStageCacheOwner.bytesFor(1152,1152))
        assertEquals(1280L*1280*36+65536,PreviewStageCacheOwner.bytesFor(1280,1280))
    }
}
