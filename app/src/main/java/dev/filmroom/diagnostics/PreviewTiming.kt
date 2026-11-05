package dev.filmroom.diagnostics

import android.os.SystemClock
import kotlinx.coroutines.*

/** Monotonic offsets from parameter callback (or scheduled request) entry, NOT hardware input or display timestamps. */
enum class PreviewTimingPoint { SCHEDULED, DEBOUNCE_END, RENDER_QUEUED, MUTEX_ACQUIRED, WORKER_STARTED, PREPARED, RESERVATION_REQUESTED, JNI_ENTER, JNI_EXIT, COMPOSITION_START, ADOPTED, NEXT_DRAW }
enum class PreviewTimingTerminal { DRAW_CALLBACK, DRAW_NOT_OBSERVED, CANCELLED, FAILED, STALE_SOURCE, STALE_GENERATION, CACHE_ONLY }
enum class PreviewHandleState { COLD_HANDLE, WARM_HANDLE, CACHED, UNKNOWN }
data class PreviewTimingSnapshot(val offsets:Map<PreviewTimingPoint,Long>,val terminal:PreviewTimingTerminal,val handle:PreviewHandleState,val renderOperation:Long?) {
    fun fields():Map<String,Any> = buildMap {
        offsets.forEach { (k,v)->if(v in 0..604_800_000L)put("previewTiming"+k.name,v) }
        put("previewTimingTerminal",terminal.name);put("previewTimingHandle",handle.name)
        renderOperation?.takeIf{it>0}?.let{put("previewTimingOperation",it)}
    }
    companion object {
        fun parse(fields:Map<String,String>):PreviewTimingSnapshot? {
            val terminal=PreviewTimingTerminal.entries.firstOrNull{it.name==fields["previewTimingTerminal"]} ?: return null
            val handle=PreviewHandleState.entries.firstOrNull{it.name==fields["previewTimingHandle"]} ?: PreviewHandleState.UNKNOWN
            val offsets=PreviewTimingPoint.entries.mapNotNull { k->fields["previewTiming"+k.name]?.toLongOrNull()?.takeIf{it in 0..604_800_000L}?.let{k to it} }.toMap()
            return PreviewTimingSnapshot(offsets,terminal,handle,fields["previewTimingOperation"]?.toLongOrNull()?.takeIf{it>0})
        }
    }
}
/** One small in-memory accumulator per attempt; one bounded asynchronous terminal record.
 * Warm means engine handle reuse, not a guarantee of warm driver/shader/source caches.
 * NEXT_DRAW is a View draw callback, NEVER proof of compositor or panel presentation. */
class PreviewTiming(private val start:Long=SystemClock.elapsedRealtime(),private val now:()->Long={SystemClock.elapsedRealtime()}) {
    private val points=java.util.EnumMap<PreviewTimingPoint,Long>(PreviewTimingPoint::class.java)
    private var finished=false
    private var handle=PreviewHandleState.UNKNOWN
    private var operation:Long?=null
    @Synchronized fun mark(point:PreviewTimingPoint){if(!finished)points.putIfAbsent(point,(now()-start).coerceAtLeast(0))}
    @Synchronized fun handle(cold:Boolean){if(!finished)handle=if(cold)PreviewHandleState.COLD_HANDLE else PreviewHandleState.WARM_HANDLE}
    @Synchronized fun cached(){if(!finished)handle=PreviewHandleState.CACHED}
    @Synchronized fun operation(id:Long){if(!finished)operation=id}
    @Synchronized fun snapshot(terminal:PreviewTimingTerminal):PreviewTimingSnapshot? {
        if(finished || (terminal==PreviewTimingTerminal.CANCELLED && points.containsKey(PreviewTimingPoint.ADOPTED)))return null
        finished=true
        return PreviewTimingSnapshot(points.toMap(),terminal,handle,operation)
    }
    fun finish(context:PreviewDiagnosticContext,terminal:PreviewTimingTerminal,disposition:PreviewDisposition) {
        val data=snapshot(terminal) ?: return
        PreviewAdoptionDiagnostics.record(PreviewAdoptionRecord(context,disposition,disposition==PreviewDisposition.ADOPTED && data.renderOperation!=null,timing=data))
    }
    fun adopted(context:PreviewDiagnosticContext) {
        mark(PreviewTimingPoint.ADOPTED)
        scope.launch { delay(1000);finish(context,PreviewTimingTerminal.DRAW_NOT_OBSERVED,PreviewDisposition.ADOPTED) }
    }
    fun nextDraw(context:PreviewDiagnosticContext) {
        mark(PreviewTimingPoint.NEXT_DRAW)
        finish(context,PreviewTimingTerminal.DRAW_CALLBACK,PreviewDisposition.ADOPTED)
    }
    companion object { private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default) }
}
