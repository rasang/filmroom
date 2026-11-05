package dev.filmroom.engine

import android.content.Context
import android.os.SystemClock
import dev.filmroom.diagnostics.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Only fixed enums, local numeric IDs and timings cross the diagnostic boundary. */
internal class RenderBoundaryTrace private constructor(
    val token:Long, val scope:RenderLifecycleScope, private val now:()->Long,
    private val emit:(GpuLifecyclePhase,GpuRenderCode?,Long,Boolean,RenderMemoryInfo?)->Unit,
):AbstractCoroutineContextElement(Key) {
    @Volatile private var variant:RenderLifecycleVariant?=null
    private val started=now()
    private val lastRepeated=mutableMapOf<GpuLifecyclePhase,Long>()
    @Volatile private var cleanupRetained=false
    fun event(phase:GpuLifecyclePhase,code:GpuRenderCode?=null) {
        if(phase==GpuLifecyclePhase.CLEANUP_RETAINED)cleanupRetained=true
        if(phase in setOf(GpuLifecyclePhase.HEARTBEAT,GpuLifecyclePhase.PROGRESS_WAIT,GpuLifecyclePhase.MEMORY_WAIT)) {
            val time=now()
            val send=synchronized(lastRepeated){val old=lastRepeated[phase];if(old!=null && time-old<1000)false else {lastRepeated[phase]=time;true}}
            if(!send)return
        }
        runCatching { emit(phase,code,(now()-started).coerceAtLeast(0),false,null) }
    }
    private var lastMemoryWait:Long?=null
    fun memory(phase:GpuLifecyclePhase,info:RenderMemoryInfo) {
        if(phase==GpuLifecyclePhase.MEMORY_WAIT){val time=now();val send=synchronized(lastRepeated){val old=lastMemoryWait;if(old!=null && time-old<1000)false else{lastMemoryWait=time;true}};if(!send)return}
        runCatching{emit(phase,null,(now()-started).coerceAtLeast(0),false,info)}
    }
    /** Worker-only durable acknowledgment. The recorder refuses filesystem waits on Main. */
    fun beforeBlocking(phase:GpuLifecyclePhase) {
        runCatching { emit(phase,null,(now()-started).coerceAtLeast(0),true,null) }
    }
    suspend fun before(phase:GpuLifecyclePhase) {withContext(Dispatchers.IO){beforeBlocking(phase)}}
    suspend fun <T> withVariant(value:RenderLifecycleVariant,work:suspend ()->T):T {
        val previous=variant;variant=value
        try{return work()}finally{variant=previous}
    }
    companion object Key:CoroutineContext.Key<RenderBoundaryTrace> {
        private val ids=AtomicLong(System.currentTimeMillis().coerceAtLeast(1))
        internal fun forTesting(now:()->Long={0L},memorySink:(RenderMemoryInfo)->Unit={},emit:(GpuLifecyclePhase,GpuRenderCode?,Long,Boolean)->Unit)=
            RenderBoundaryTrace(1,RenderLifecycleScope.PREVIEW,now){phase,code,elapsed,durable,memory->memory?.let(memorySink);emit(phase,code,elapsed,durable)}
        suspend fun <T> run(context:Context,scope:RenderLifecycleScope,operationId:Long?=null,variant:RenderLifecycleVariant?=null,work:suspend (RenderBoundaryTrace)->T):T {
            val token=ids.incrementAndGet();val app=context.applicationContext
            lateinit var trace:RenderBoundaryTrace
            trace=RenderBoundaryTrace(token,scope,{SystemClock.elapsedRealtime()}) {phase,code,elapsed,durable,memory->
                if(durable)GpuLifecycleRecorder.recordBeforeBlocking(app,token,null,phase,code,elapsed,scope,memory=memory,operationId=operationId,variant=trace.variant)
                else GpuLifecycleRecorder.record(app,token,null,phase,code,elapsed,scope,memory=memory,operationId=operationId,variant=trace.variant)
                Unit
            }
            trace.variant=variant
            return withContext(trace) {
                try { work(trace).also{trace.event(GpuLifecyclePhase.OPERATION_END)} }
                catch(t:Throwable){trace.event(if(t is CancellationException && !trace.cleanupRetained)GpuLifecyclePhase.OPERATION_CANCELLED else GpuLifecyclePhase.OPERATION_FAILED,if(t is CancellationException)GpuRenderCode.CANCELLED else null);throw t}
            }
        }
    }
}
