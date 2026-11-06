package dev.filmroom.engine

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Admission estimates, not allocator caps or a guarantee against Android killing the process. */
data class NativeMemorySnapshot(val availableBytes:Long,val thresholdBytes:Long,val lowMemory:Boolean)
class NativeWorkUnavailable:Exception("Native working-memory admission unavailable")
/** Full peak + safety are prepaid; a run borrows only one worker slot. */
interface RetainedNativeWork:AutoCloseable {
    suspend fun <T> run(memory:()->NativeMemorySnapshot,work:suspend ()->T):T
}

/** Shared by engine and codec callers. Only heavyweight JNI work holds a lease; planning never does. */
object NativeWorkReservations {
    private val limiter=NativeWorkLimiter { !ExportWorkAdmissions.prewarmBlocked }
    /** Read-only retry gate; does not raise capacity or alter reservation accounting. */
    fun detailRetryIdle():Boolean=limiter.activeCount()==0
    fun preemptIdle()=limiter.preemptIdle()
    suspend fun <T:Any> tryRunIdle(estimatedBytes:Long,memory:()->NativeMemorySnapshot,onPreempt:()->Unit,work:suspend ()->T):T? = limiter.tryRunIdle(estimatedBytes,memory(),onPreempt,work)
    val unavailableMemory=NativeMemorySnapshot(0,0,true)
    fun snapshot(context:Context):NativeMemorySnapshot {
        val manager=context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info=ActivityManager.MemoryInfo();manager.getMemoryInfo(info)
        return NativeMemorySnapshot(info.availMem,info.threshold,info.lowMemory)
    }
    suspend fun <T> run(estimatedBytes:Long,memory:()->NativeMemorySnapshot,work:suspend ()->T):T = limiter.run(estimatedBytes,memory,work)
    /** Passive contexts consume bytes, never worker slots. Admission fails instead of waiting on itself. */
    fun retainForDetail(estimatedBytes:Long,memory:()->NativeMemorySnapshot,onPressure:(()->Unit)?=null):RetainedNativeWork = limiter.retainForWork(estimatedBytes,memory(),onPressure)
    fun retain(estimatedBytes:Long,memory:()->NativeMemorySnapshot,onPressure:(()->Unit)?=null):AutoCloseable = limiter.retain(estimatedBytes,memory(),onPressure)
    fun previewEstimate(width:Int,height:Int):Long {
        require(width in 1..NativeMemoryPolicy.DETAIL_PREVIEW_EDGE && height in 1..NativeMemoryPolicy.DETAIL_PREVIEW_EDGE)
        // Matches native MEMORY_FIXED_ESTIMATE_BYTES + MEMORY_ESTIMATE_BYTES_PER_PIXEL.
        // Include direct float input/output and displayed result, although allocated
        // before JNI. The limiter adds another per-operation safety allowance.
        return 96L*NativeMemoryPolicy.MIB+width.toLong()*height*(512L+24L+4L)
    }
}

/** Tiny process-local ledger. A fresh OS snapshot is checked on every cancellable retry. */
internal class NativeWorkLimiter(private val idleAllowed:()->Boolean = {true}) {
    private val lock=Any()
    private var foregroundWaiters=0
    private var idlePreempt:(()->Unit)?=null
    private var count=0
    private var reserved=0L
    private val passive=mutableMapOf<Long,()->Unit>()
    private var passiveSerial=0L
    private val evicting=mutableSetOf<Long>()
    internal fun activeCount():Int=synchronized(lock){count}
    internal fun reservedBytes():Long=synchronized(lock){reserved}
    private sealed class Attempt {
        class Ready(val lease:Lease):Attempt()
        object Wait:Attempt()
        object Reject:Attempt()
        class Evict(val callbacks:List<()->Unit>):Attempt()
    }
    private class Lease(private val release:()->Unit):AutoCloseable {
        private var closed=false
        override fun close() {synchronized(this){if(!closed){closed=true;release()}}}
    }
    /** Never queues, evicts a passive cache, or borrows capacity from another
     * owner. A real admission first invalidates this work outside the ledger lock.
     * Bytes and slot remain charged until the synchronous native call returns. */
    fun preemptIdle(){val callback=synchronized(lock){idlePreempt};runCatching{callback?.invoke()}}
    suspend fun <T:Any> tryRunIdle(estimate:Long,memory:NativeMemorySnapshot,onPreempt:()->Unit,work:suspend ()->T):T? {
        val lease=synchronized(lock) {
            require(estimate in 1..(2L*1024*NativeMemoryPolicy.MIB))
            // Read only atomics/StateFlow here. Queue activity is published before
            // preemptIdle takes this same lock, closing check/register races.
            if(!idleAllowed())return null
            val charge=Math.addExact(estimate,16L*NativeMemoryPolicy.MIB)
            val headroom=if(memory.thresholdBytes>=0 && memory.availableBytes>memory.thresholdBytes)memory.availableBytes-memory.thresholdBytes else 0L
            val capacity=(headroom-64L*NativeMemoryPolicy.MIB).coerceAtLeast(0)/2
            if(memory.lowMemory || count!=0 || foregroundWaiters!=0 || idlePreempt!=null || evicting.isNotEmpty() || charge>capacity || reserved>capacity-charge)return null
            count++;reserved+=charge;idlePreempt=onPreempt
            Lease {synchronized(lock){idlePreempt=null;count--;reserved-=charge;check(count>=0&&reserved>=0)}}
        }
        try {currentCoroutineContext().ensureActive();return work()} finally {lease.close()}
    }
    private fun foregroundWaiting(){synchronized(lock){foregroundWaiters++};preemptIdle()}
    private fun foregroundReady(){synchronized(lock){foregroundWaiters--;check(foregroundWaiters>=0)}}
    fun retain(estimate:Long,memory:NativeMemorySnapshot,onPressure:(()->Unit)?=null):AutoCloseable=synchronized(lock) {
        require(estimate in 1..(2L*1024*NativeMemoryPolicy.MIB))
        val headroom=if(memory.thresholdBytes>=0 && memory.availableBytes>memory.thresholdBytes)
            memory.availableBytes-memory.thresholdBytes else 0L
        val capacity=(headroom-64L*NativeMemoryPolicy.MIB).coerceAtLeast(0)/2
        if(memory.lowMemory || estimate>capacity || reserved>capacity-estimate)throw NativeWorkUnavailable()
        reserved+=estimate
        val id=++passiveSerial;if(onPressure!=null)passive[id]=onPressure
        Lease { synchronized(lock){reserved-=estimate;passive.remove(id);evicting.remove(id);check(reserved>=0)} }
    }
    /** Detail-only ownership. Normal export/codec admission and eviction stay unchanged. */
    fun retainForWork(estimate:Long,memory:NativeMemorySnapshot,onPressure:(()->Unit)?=null):RetainedNativeWork {
        val reservation=retain(Math.addExact(estimate,16L*NativeMemoryPolicy.MIB),memory,onPressure)
        return object:RetainedNativeWork {
            private var closed=false
            private var workers=0
            override fun close(){synchronized(lock){if(!closed){closed=true;if(workers==0)reservation.close()}}}
            override suspend fun <T> run(memory:()->NativeMemorySnapshot,work:suspend ()->T):T=withContext(Dispatchers.IO) {
                var acquired=false
                foregroundWaiting()
                try { while(!acquired){
                    currentCoroutineContext().ensureActive()
                    val snapshot=memory()
                    acquired=synchronized(lock){
                        if(closed)throw kotlinx.coroutines.CancellationException("Retained owner closed")
                        val headroom=if(snapshot.thresholdBytes>=0 && snapshot.availableBytes>snapshot.thresholdBytes) snapshot.availableBytes-snapshot.thresholdBytes else 0L
                        val capacity=(headroom-64L*NativeMemoryPolicy.MIB).coerceAtLeast(0)/2
                        // Do not add a second 16 MiB or evict our own passive owner.
                        // OS available memory is still conservative; never add RSS back.
                        if(snapshot.lowMemory || reserved>capacity)throw NativeWorkUnavailable()
                        if(count>=2 || workers!=0)false else {count++;workers++;true}
                    }
                    if(!acquired)delay(100)
                }
                } finally {foregroundReady()}
                try {currentCoroutineContext().ensureActive();work()}
                finally {synchronized(lock){count--;workers--;check(count>=0 && workers==0);if(closed)reservation.close()}}
            }
        }
    }
    private fun acquire(estimate:Long,memory:NativeMemorySnapshot):Attempt=synchronized(lock) {
        require(estimate in 1..(2L*1024*NativeMemoryPolicy.MIB))
        val charge=Math.addExact(estimate,16L*NativeMemoryPolicy.MIB)
        val headroom=if(memory.thresholdBytes>=0 && memory.availableBytes>memory.thresholdBytes)
            memory.availableBytes-memory.thresholdBytes else 0L
        // Keep Android's low-memory threshold plus 64 MiB untouched, then use
        // at most half the remaining currently available memory. Existing native
        // RSS may already reduce availMem: counting reservations too is conservative.
        val capacity=(headroom-64L*NativeMemoryPolicy.MIB).coerceAtLeast(0)/2
        if(memory.lowMemory || count>=2 || charge>capacity || reserved>capacity-charge) {
            val callbacks=passive.filterKeys { it !in evicting }
            if(callbacks.isNotEmpty()){evicting.addAll(callbacks.keys);return@synchronized Attempt.Evict(callbacks.values.toList())}
            if(count==0 && evicting.isEmpty())dev.filmroom.diagnostics.ImportObservation.current()?.admission(estimate,memory,capacity,reserved,count,true)
            return@synchronized if(count>0 || evicting.isNotEmpty())Attempt.Wait else Attempt.Reject
        }
        dev.filmroom.diagnostics.ImportObservation.current()?.admission(estimate,memory,capacity,reserved,count,false)
        count++;reserved+=charge
        Attempt.Ready(Lease {synchronized(lock){count--;reserved-=charge;check(count>=0&&reserved>=0)}})
    }
    suspend fun <T> run(estimate:Long,memory:()->NativeMemorySnapshot,work:suspend ()->T):T=withContext(Dispatchers.IO) {
        var lease:Lease?=null
        foregroundWaiting()
        try { while(lease==null) {
            currentCoroutineContext().ensureActive()
            when(val result=acquire(estimate,memory())) {
                is Attempt.Ready->lease=result.lease
                Attempt.Wait->delay(100)
                Attempt.Reject->throw NativeWorkUnavailable()
                is Attempt.Evict->{result.callbacks.forEach{it()};delay(25)}
            }
        }
        } finally {foregroundReady()}
        try {currentCoroutineContext().ensureActive();work()} finally {lease!!.close()}
    }
}
