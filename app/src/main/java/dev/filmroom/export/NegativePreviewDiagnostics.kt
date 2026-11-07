package dev.filmroom.export

import dev.filmroom.diagnostics.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** No path, token, picture or exception text is serialized. One terminal at most per attempt. */
internal class NegativePreviewTerminalGate {
    private val emitted=AtomicBoolean(false)
    fun claim(value:NegativePreviewOutcome):NegativePreviewOutcome?=if(emitted.compareAndSet(false,true))value else null
    companion object {
        fun resolve(ready:Boolean,statuses:List<String?>,jobTerminal:Boolean,cancelled:Boolean=false):NegativePreviewOutcome? {
            if(ready)return NegativePreviewOutcome.SUCCESS
            if(cancelled)return NegativePreviewOutcome.CANCELLED
            if(!jobTerminal)return null // exact producer may not exist until final density harvest
            val observed=statuses.filterNotNull()
            if(observed.isEmpty() || observed.any{it=="pending" || it !in setOf("ready","skipped_budget","failed","cancelled","not_applicable")})return null
            if(observed.all{it=="not_applicable"})return NegativePreviewOutcome.NOT_APPLICABLE
            if(observed.any{it=="failed" || it=="ready"})return NegativePreviewOutcome.FAILURE // ready without valid image
            if(observed.any{it=="skipped_budget"})return NegativePreviewOutcome.BUDGET_SKIP
            return NegativePreviewOutcome.CANCELLED
        }
    }
}
internal class NegativePreviewDiagnostics(private val dir:File,private val token:String) {
    private val gate=NegativePreviewTerminalGate()
    private val terminal=AtomicBoolean(false)
    private val cancelled=AtomicBoolean(false)
    private val done=AtomicBoolean(false)
    private val watcher=scope.launch {
        // Reuse the optional producer's existing grace; never wait on export completion.
        val start=android.os.SystemClock.elapsedRealtime()
        while(isActive && !done.get() && android.os.SystemClock.elapsedRealtime()-start<=NegativePreviewStore.PENDING_GRACE_MS) {
            observe()
            if(ScanPreviewStore.token(dir)!=token)break
            delay(1000)
        }
        // A stalled/pending producer is unknown, not an invented failure.
    }
    private fun observe()=runCatching {
        val current=ScanPreviewStore.token(dir)==token
        val ready=current && NegativePreviewStore.select(dir)!=null
        val result=NegativePreviewTerminalGate.resolve(ready,if(current)NegativePreviewStore.producerStatuses(dir,token)else emptyList(),terminal.get(),cancelled.get()) ?: return@runCatching
        emit(result)
    }
    private fun emit(value:NegativePreviewOutcome) {
        val result=gate.claim(value) ?: return
        done.set(true)
        val id=Diagnostics.begin(Operation.PREVIEW_PRESENTATION,SafeInfo(negativePreviewOutcome=result))
        Diagnostics.end(id,when(result){NegativePreviewOutcome.CANCELLED->Outcome.CANCELLED;NegativePreviewOutcome.FAILURE->Outcome.FAILED;else->Outcome.SUCCESS})
    }
    fun jobTerminal(){
        terminal.set(true)
        val result=runCatching {
            if(ScanPreviewStore.token(dir)!=token)null else NegativePreviewTerminalGate.resolve(
                NegativePreviewStore.select(dir)!=null,NegativePreviewStore.producerStatuses(dir,token),true,cancelled.get())
        }.getOrNull()
        if(result!=null)scope.launch{emit(result)}
    }
    /** Capture success before caller clears files; no new filesystem writes on caller thread. */
    fun cancel() {
        val ready=runCatching{ScanPreviewStore.token(dir)==token && NegativePreviewStore.select(dir)!=null}.getOrDefault(false)
        cancelled.set(true);terminal.set(true)
        scope.launch{emit(if(ready)NegativePreviewOutcome.SUCCESS else NegativePreviewOutcome.CANCELLED)}
    }
    companion object {private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)}
}
