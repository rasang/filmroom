package dev.filmroom.engine

import dev.filmroom.diagnostics.Stage
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
    val polling=launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
        val gate=NativeProgressLogGate()
        while(isActive){
            delay(pollIntervalMs)
            try {
                val value=readProgress() ?: continue
                if(gate.shouldLog(value,now()))recordProgress(value)
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){ /* Diagnostic polling must not change rendering. */ }
        }
    }
    try {
        mark(Stage.NATIVE_ENTER)
        polling.start()
        val result=native()
        // A synchronous JNI return must remain visible even when its parent was
        // cancelled during the call. Do not infer success from this milestone.
        polling.cancel()
        withContext(NonCancellable){runCatching{mark(Stage.NATIVE_RETURN)}}
        result
    } finally {
        // Never poll a released/reused handle, including cancellation and throws.
        withContext(NonCancellable){polling.cancelAndJoin()}
    }
}
