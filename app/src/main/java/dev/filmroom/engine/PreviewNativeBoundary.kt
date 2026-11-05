package dev.filmroom.engine

import dev.filmroom.diagnostics.Stage
import dev.filmroom.diagnostics.GpuLifecyclePhase
import kotlinx.coroutines.*

/** Caller owns admission and the handle until this function and GPU cleanup finish. */
internal suspend fun <T> previewNativeBoundary(
    mark:suspend (Stage)->Unit,
    readProgress:()->EngineProgress?,
    recordProgress:suspend (EngineProgress)->Unit,
    now:()->Long,
    pollIntervalMs:Long=400,
    native:()->T,
):T=coroutineScope {
    val boundary=currentCoroutineContext()[RenderBoundaryTrace]
    // A cancelled UI request does not preempt a synchronous JNI call. Keep its
    // observer alive until the call actually returns, otherwise the last sampled
    // phase can be mistaken for the later blocking phase. This explicit lifetime
    // is always cancelled and joined before the caller may destroy the handle.
    val pollingLifetime=Job()
    val polling=launch(Dispatchers.IO+pollingLifetime,start=CoroutineStart.LAZY) {
        val gate=NativeProgressLogGate()
        var previousSerial:Long?=null
        while(isActive){
            delay(pollIntervalMs)
            try {
                val value=readProgress()
                if(value==null){boundary?.event(GpuLifecyclePhase.HEARTBEAT);continue}
                if(gate.shouldLog(value,now()))recordProgress(value)
                boundary?.event(if(previousSerial==value.updateSerial)GpuLifecyclePhase.PROGRESS_WAIT else GpuLifecyclePhase.HEARTBEAT)
                previousSerial=value.updateSerial
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Throwable){ /* Diagnostic polling must not change rendering, including linkage failures. */ }
        }
    }
    var entered=false
    try {
        boundary?.before(GpuLifecyclePhase.JNI_PREPARE)
        mark(Stage.NATIVE_PREPARE)
        polling.start()
        currentCoroutineContext().ensureActive()
        // No cancellable dispatcher boundary between the entry checkpoint and JNI.
        boundary?.event(GpuLifecyclePhase.JNI_ENTER)
        entered=true
        val result=try{native()}catch(t:Throwable){boundary?.event(GpuLifecyclePhase.JNI_FAILED);throw t}
        boundary?.event(GpuLifecyclePhase.JNI_RETURN)
        // A synchronous JNI return must remain visible even when its parent was
        // cancelled during the call. Do not infer success from this milestone.
        polling.cancel()
        withContext(NonCancellable){runCatching{mark(Stage.NATIVE_RETURN)}}
        result
    } finally {
        if(!entered)boundary?.event(GpuLifecyclePhase.NOT_ENTERED)
        // Never poll a released/reused handle, including cancellation and throws.
        withContext(NonCancellable){pollingLifetime.cancel();polling.cancelAndJoin()}
    }
}
