package dev.filmroom.engine

import android.app.ActivityManager
import android.content.Context
import dev.filmroom.diagnostics.GpuLifecyclePhase
import dev.filmroom.diagnostics.RenderMemoryInfo
import dev.filmroom.diagnostics.RenderMemoryWaitReason
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
    /** Keep prepaid bytes, but remove eviction callbacks that can never complete yet. */
    fun quarantine() {}
    suspend fun <T> run(memory:()->NativeMemorySnapshot,work:suspend ()->T):T
}

/** Transfers an existing charge to passive ownership; never acquires additional capacity. */
class NativeWorkRetention {
    private val lock = Any()
    private var pending = 0
    private var completed = false
    private var release: (() -> Unit)? = null
    fun retainUntil(register: ((() -> Unit) -> Unit)) {
        synchronized(lock) { check(!completed); pending++ }
        val once = java.util.concurrent.atomic.AtomicBoolean()
        register {
            if (once.compareAndSet(false, true)) {
                val action = synchronized(lock) { pending--; if (pending == 0) release.also { release = null } else null }
                action?.invoke()
            }
        }
    }
    internal fun finish(releaseBytes: () -> Unit) {
        val now = synchronized(lock) {
            check(!completed); completed = true
            if (pending == 0) true else { release = releaseBytes; false }
        }
        if (now) releaseBytes()
    }
}

/** Shared by engine and codec callers. Only heavyweight JNI work holds a lease; planning never does. */
object NativeWorkReservations {
    private val limiter=NativeWorkLimiter()
    /** Read-only retry gate; does not raise capacity or alter reservation accounting. */
    fun detailRetryIdle():Boolean=limiter.activeCount()==0
    internal fun diagnosticLedger(reason:RenderMemoryWaitReason)=limiter.diagnosticLedger(reason)
    val unavailableMemory=NativeMemorySnapshot(0,0,true)
    fun snapshot(context:Context):NativeMemorySnapshot {
        val manager=context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info=ActivityManager.MemoryInfo();manager.getMemoryInfo(info)
        return NativeMemorySnapshot(info.availMem,info.threshold,info.lowMemory)
    }
    suspend fun <T> run(estimatedBytes:Long,memory:()->NativeMemorySnapshot,work:suspend ()->T):T = limiter.run(estimatedBytes,memory,work)
    suspend fun <T> runQuarantinable(estimatedBytes:Long,memory:()->NativeMemorySnapshot,retention:NativeWorkRetention,work:suspend ()->T):T = limiter.runQuarantinable(estimatedBytes,memory,retention,work)
    /** Passive contexts consume bytes, never worker slots. Admission fails instead of waiting on itself. */
    fun retainForDetail(estimatedBytes:Long,memory:()->NativeMemorySnapshot,onPressure:(()->Unit)?=null):RetainedNativeWork = limiter.retainForWork(estimatedBytes,memory(),onPressure)
    fun retain(estimatedBytes:Long,memory:()->NativeMemorySnapshot,onPressure:(()->Unit)?=null):AutoCloseable = limiter.retain(estimatedBytes,memory(),onPressure)
    fun previewEstimate(width:Int,height:Int):Long {
        require(width in 1..NativeMemoryPolicy.DETAIL_PREVIEW_EDGE && height in 1..NativeMemoryPolicy.DETAIL_PREVIEW_EDGE)
        // Matches native MEMORY_FIXED_ESTIMATE_BYTES + MEMORY_ESTIMATE_BYTES_PER_PIXEL.
        // Conservatively retain the legacy 24 B/pixel direct input/output allowance:
        // RGBA8 presentation now uses 12 B input + 4 B output, with no native
        // full-image packing scratch. Keep the spare 8 B/pixel until device peak
        // measurements; the displayed result remains 4 B/pixel. The limiter adds
        // another per-operation safety allowance. Buffers are allocated before JNI.
        return 96L*NativeMemoryPolicy.MIB+width.toLong()*height*(512L+24L+4L)
    }
}

/** Tiny process-local ledger. A fresh OS snapshot is checked on every cancellable retry. */
internal class NativeWorkLimiter {
    private val lock=Any()
    private var count=0
    private var reserved=0L
    private val passive=mutableMapOf<Long,()->Unit>()
    private var passiveSerial=0L
    private val evicting=mutableSetOf<Long>()
    internal fun activeCount():Int=synchronized(lock){count}
    internal fun reservedBytes():Long=synchronized(lock){reserved}
    internal fun diagnosticLedger(reason:RenderMemoryWaitReason)=synchronized(lock){RenderMemoryInfo(reservedBytes=reserved,activeWorkers=count,waitReason=reason)}
    private sealed class Attempt {
        class Ready(val lease:Lease):Attempt()
        class Wait(val reason:RenderMemoryWaitReason):Attempt()
        class Reject(val reason:RenderMemoryWaitReason):Attempt()
        class Evict(val callbacks:List<()->Unit>):Attempt()
    }
    private fun memoryInfo(estimate:Long,snapshot:NativeMemorySnapshot?,reason:RenderMemoryWaitReason):RenderMemoryInfo=synchronized(lock){
        RenderMemoryInfo(requestedBytes=estimate,chargedBytes=Math.addExact(estimate,16L*NativeMemoryPolicy.MIB),availableBytes=snapshot?.availableBytes?.takeIf{it in 0..(1L shl 40)},
            reservedBytes=reserved,activeWorkers=count,waitReason=reason)
    }
    private class Lease(private val release:()->Unit, val quarantine:()->Unit = {}, val releaseWorker:()->Unit = {}):AutoCloseable {
        private var closed=false
        override fun close() {synchronized(this){if(!closed){closed=true;release()}}}
    }
    fun retain(estimate:Long,memory:NativeMemorySnapshot,onPressure:(()->Unit)?=null):AutoCloseable=synchronized(lock) {
        require(estimate in 1..(2L*1024*NativeMemoryPolicy.MIB))
        val headroom=if(memory.thresholdBytes>=0 && memory.availableBytes>memory.thresholdBytes)
            memory.availableBytes-memory.thresholdBytes else 0L
        val capacity=(headroom-64L*NativeMemoryPolicy.MIB).coerceAtLeast(0)/2
        if(memory.lowMemory || estimate>capacity || reserved>capacity-estimate)throw NativeWorkUnavailable()
        reserved+=estimate
        val id=++passiveSerial;if(onPressure!=null)passive[id]=onPressure
        Lease(release = { synchronized(lock){reserved-=estimate;passive.remove(id);evicting.remove(id);check(reserved>=0)} }, quarantine = { synchronized(lock){passive.remove(id);evicting.remove(id)} })
    }
    /** Detail-only ownership. Normal export/codec admission and eviction stay unchanged. */
    fun retainForWork(estimate:Long,memory:NativeMemorySnapshot,onPressure:(()->Unit)?=null):RetainedNativeWork {
        val reservation=retain(Math.addExact(estimate,16L*NativeMemoryPolicy.MIB),memory,onPressure)
        return object:RetainedNativeWork {
            private var closed=false
            private var workers=0
            override fun quarantine() { (reservation as Lease).quarantine() }
            override fun close(){synchronized(lock){if(!closed){closed=true;if(workers==0)reservation.close()}}}
            override suspend fun <T> run(memory:()->NativeMemorySnapshot,work:suspend ()->T):T=withContext(Dispatchers.IO) {
                val trace=currentCoroutineContext()[RenderBoundaryTrace]
                trace?.before(GpuLifecyclePhase.MEMORY_WAIT)
                var waits=0
                var acquired=false
                while(!acquired){
                    currentCoroutineContext().ensureActive()
                    val snapshot=memory()
                    acquired=synchronized(lock){
                        if(closed)throw kotlinx.coroutines.CancellationException("Retained owner closed")
                        val headroom=if(snapshot.thresholdBytes>=0 && snapshot.availableBytes>snapshot.thresholdBytes) snapshot.availableBytes-snapshot.thresholdBytes else 0L
                        val capacity=(headroom-64L*NativeMemoryPolicy.MIB).coerceAtLeast(0)/2
                        // Do not add a second 16 MiB or evict our own passive owner.
                        // OS available memory is still conservative; never add RSS back.
                        if(snapshot.lowMemory || reserved>capacity){trace?.memory(GpuLifecyclePhase.MEMORY_REJECTED,memoryInfo(estimate,snapshot,if(snapshot.lowMemory)RenderMemoryWaitReason.LOW_MEMORY else RenderMemoryWaitReason.CAPACITY));throw NativeWorkUnavailable()}
                        if(count>=2 || workers!=0)false else {count++;workers++;true}
                    }
                    if(!acquired){if(waits++%10==0)trace?.memory(GpuLifecyclePhase.MEMORY_WAIT,memoryInfo(estimate,snapshot,RenderMemoryWaitReason.WORKER_LIMIT));delay(100)}
                    else trace?.memory(GpuLifecyclePhase.ADMITTED,memoryInfo(estimate,snapshot,RenderMemoryWaitReason.ADMITTED))
                }
                trace?.event(GpuLifecyclePhase.ADMITTED)
                try {currentCoroutineContext().ensureActive();work()}
                finally {synchronized(lock){count--;workers--;check(count>=0 && workers==0);if(closed)reservation.close()};trace?.memory(GpuLifecyclePhase.WORKER_RELEASED,memoryInfo(estimate,null,RenderMemoryWaitReason.WORKER_RELEASED))}
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
            val reason=when{memory.lowMemory->RenderMemoryWaitReason.LOW_MEMORY;count>=2 && (charge>capacity || reserved>capacity-charge)->RenderMemoryWaitReason.WORKERS_AND_BYTES;count>=2->RenderMemoryWaitReason.WORKER_LIMIT;else->RenderMemoryWaitReason.CAPACITY}
            return@synchronized if(count>0 || evicting.isNotEmpty())Attempt.Wait(reason) else Attempt.Reject(reason)
        }
        count++;reserved+=charge
        var workerReleased=false
        val releaseWorker = { synchronized(lock){if(!workerReleased){workerReleased=true;count--;check(count>=0)}}; Unit }
        Attempt.Ready(Lease(release={synchronized(lock){releaseWorker();reserved-=charge;check(reserved>=0)}},releaseWorker=releaseWorker))
    }
    suspend fun <T> run(estimate:Long,memory:()->NativeMemorySnapshot,work:suspend ()->T):T = runQuarantinable(estimate,memory,NativeWorkRetention(),work)
    suspend fun <T> runQuarantinable(estimate:Long,memory:()->NativeMemorySnapshot,retention:NativeWorkRetention,work:suspend ()->T):T=withContext(Dispatchers.IO) {
        val trace=currentCoroutineContext()[RenderBoundaryTrace]
        trace?.before(GpuLifecyclePhase.MEMORY_WAIT)
        var waits=0
        var lease:Lease?=null
        while(lease==null) {
            currentCoroutineContext().ensureActive()
            val snapshot=memory()
            when(val result=acquire(estimate,snapshot)) {
                is Attempt.Ready->{lease=result.lease;trace?.memory(GpuLifecyclePhase.ADMITTED,memoryInfo(estimate,snapshot,RenderMemoryWaitReason.ADMITTED))}
                is Attempt.Wait->{if(waits++%10==0)trace?.memory(GpuLifecyclePhase.MEMORY_WAIT,memoryInfo(estimate,snapshot,result.reason));delay(100)}
                is Attempt.Reject->{trace?.memory(GpuLifecyclePhase.MEMORY_REJECTED,memoryInfo(estimate,snapshot,result.reason));throw NativeWorkUnavailable()}
                is Attempt.Evict->{trace?.beforeBlocking(GpuLifecyclePhase.MEMORY_EVICTION);trace?.memory(GpuLifecyclePhase.MEMORY_EVICTION,memoryInfo(estimate,snapshot,RenderMemoryWaitReason.EVICTION));result.callbacks.forEach{it()};delay(25)}
            }
        }
        trace?.event(GpuLifecyclePhase.ADMITTED)
        val owned=checkNotNull(lease)
        try {currentCoroutineContext().ensureActive();work()} finally {owned.releaseWorker();trace?.memory(GpuLifecyclePhase.WORKER_RELEASED,memoryInfo(estimate,null,RenderMemoryWaitReason.WORKER_RELEASED));retention.finish { owned.close();trace?.memory(GpuLifecyclePhase.MEMORY_RELEASED,memoryInfo(estimate,null,RenderMemoryWaitReason.BYTES_RELEASED)) }}
    }
}
