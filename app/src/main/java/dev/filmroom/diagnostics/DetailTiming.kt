package dev.filmroom.diagnostics

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Local attempt counters, dimensions and fixed enums only. No photo IDs, hashes, positions or raw strings. */
enum class DetailAttemptKind { OPEN, REGION, TILE, FIRST_PRESENTATION }
enum class DetailTimingPoint { REQUESTED, SETTLE_END, OPEN_STARTED, DATA_HASH_START, DATA_HASH_END, METERING_START, METERING_END, SOURCE_START, SOURCE_RESERVATION_REQUESTED, SOURCE_RESERVATION_ACQUIRED, DECODE_START, DECODE_END, RESIZE_START, RESIZE_END, SOURCE_HASH_START, SOURCE_HASH_END, SOURCE_END, PLAN_START, PLAN_END, CONTEXT_CREATE_START, CONTEXT_CREATE_END, RENDER_QUEUED, WORKER_STARTED, RESERVATION_REQUESTED, RESERVATION_ACQUIRED, OUTPUT_ALLOCATION_START, OUTPUT_ALLOCATION_END, JNI_ENTER, JNI_EXIT, VALIDATION_START, VALIDATION_END, CONVERSION_START, CONVERSION_END, RETURNED, ADOPTED, FIRST_DRAW, FIRST_REGION_QUEUED, FIRST_REGION_JNI_ENTER, FIRST_REGION_JNI_EXIT, FIRST_REGION_ADOPTED }
enum class DetailTimingTerminal { OPEN_READY, DRAW_CALLBACK, DRAW_NOT_OBSERVED, CANCELLED, FAILED, STALE, RETIRED }
enum class DetailActualBackend { CPU, WGPU }
enum class DetailActualQuality { EXACT_SAME_WORKING_GRID, APPROXIMATE_SAME_WORKING_GRID }
enum class DetailSourceRoute { EXISTING_LINEAR, DECODE_ORIGINAL }
enum class DetailBackendReason(val id:String) {
    DISABLED("disabled_by_user"), NOT_BUILT("gpu_not_built"), ADMISSION_CPU("gpu_admission_cpu_attempt"), SHARED_METERING("shared_preview_metering_required"), MEMORY("gpu_detail_memory_budget"), UNAVAILABLE("gpu_unavailable"), PITCH("gpu_full_grid_pitch_required"), FIELD_BUDGET("gpu_global_field_budget"), HALO_BINDING("gpu_detail_halo_or_binding_budget"), FIELD_EXECUTION("gpu_global_field_execution_failed"), FIELD_CAPACITY("gpu_global_field_capacity_exceeded"), CHAIN_EXECUTION("gpu_detail_chain_execution_failed"), SETUP_PANICKED("gpu_setup_panicked"), NO_SUBMITS("gpu_no_confirmed_scientific_submits"), RESIDENT("approximate_resident_detail"), UNKNOWN("unknown");
    companion object { fun fromId(value:String)=entries.firstOrNull{it.id==value} ?: UNKNOWN }
}
enum class DetailRetirementReason { SOURCE_CHANGED, SCIENCE_CHANGED, ELIGIBILITY_CHANGED, BACKGROUND, ZOOM_OUT, MOTION, COMPARING, EXTERNAL_INVALIDATION, RESOURCE_PRESSURE, PROVIDER_ERROR, CLOSED }
/** Fixed native fields only; counts are per successful operation, times are milliseconds. */
enum class DetailNativeMetric(val id:String,val count:Boolean=false) {
    TILE_CACHE_HITS("tileCacheHits",true), TILE_CACHE_MISSES("tileCacheMisses",true), GPU_SUBMIT_DELTA("gpuSubmitDelta",true),
    SOURCE_READ_MS("sourceReadMs"), CHAIN_MS("chainMs"), RESULT_COPY_MS("resultCopyMs"),
    PROFILE_PREPARE_MS("profilePrepareMs"), PIPELINE_PREPARE_MS("pipelinePrepareMs"), GLOBAL_FIELDS_MS("globalFieldsMs"), PROOF_TILE_MS("proofTileMs");
    fun accepts(value:Double)=value.isFinite() && value>=0 && value<=(if(count)1_800_000_000.0 else 604_800_000.0) && (!count || value==kotlin.math.floor(value))
}
enum class DetailDiagnosticFailure { CANCELLED, MEMORY_ADMISSION, MEMORY_BUDGET, MEMORY_PRESSURE, STORAGE, SOURCE_IO, INVALID_RESULT, UNSUPPORTED, UNAVAILABLE, GPU_UNCERTAIN, GPU_FAILED, ALLOCATION, OTHER }
data class DetailTimingSnapshot(
    val attempt:Long,val context:Long,val kind:DetailAttemptKind,val offsets:Map<DetailTimingPoint,Long>,val terminal:DetailTimingTerminal,
    val backend:DetailActualBackend?=null,val quality:DetailActualQuality?=null,val reason:DetailBackendReason?=null,
    val requestedGpu:Boolean?=null,val cpuFallback:Boolean?=null,val confirmedSubmits:Long?=null,
    val sourceRoute:DetailSourceRoute?=null,val width:Int?=null,val height:Int?=null,val failure:DetailDiagnosticFailure?=null,val suppressed:Long=0,
    val sourceCacheHit:Boolean?=null,val retirementReason:DetailRetirementReason?=null,val nativeMetrics:Map<DetailNativeMetric,Double> = emptyMap(),
) {
    fun fields():Map<String,Any> = buildMap {
        if(attempt>0)put("detailAttempt",attempt);if(context>0)put("detailContext",context)
        put("detailKind",kind.name);put("detailTerminal",terminal.name)
        offsets.forEach{(k,v)->if(v in 0..604_800_000L)put("detailTiming"+k.name,v)}
        backend?.let{put("detailActualBackend",it.name)};quality?.let{put("detailActualQuality",it.name)};reason?.let{put("detailBackendReason",it.id)}
        requestedGpu?.let{put("detailRequestedGpu",it)};cpuFallback?.let{put("detailCpuFallback",it)}
        confirmedSubmits?.takeIf{it in 0..1_800_000_000L}?.let{put("detailConfirmedSubmits",it)}
        sourceRoute?.let{put("detailSourceRoute",it.name)}
        width?.takeIf{it in 1..1536}?.let{put("detailGridWidth",it)};height?.takeIf{it in 1..1536}?.let{put("detailGridHeight",it)}
        sourceCacheHit?.let{put("detailSourceCacheHit",it)};retirementReason?.let{put("detailRetirementReason",it.name)}
        nativeMetrics.forEach{(k,v)->if(k.accepts(v))put("detailNative"+k.name,if(k.count)v.toLong() else v)}
        failure?.let{put("detailFailure",it.name)};if(suppressed in 1..1_000_000)put("detailSuppressed",suppressed)
    }
    companion object {
        fun parse(f:Map<String,String>):DetailTimingSnapshot? {
            val kind=DetailAttemptKind.entries.firstOrNull{it.name==f["detailKind"]} ?: return null
            val terminal=DetailTimingTerminal.entries.firstOrNull{it.name==f["detailTerminal"]} ?: return null
            val attempt=f["detailAttempt"]?.toLongOrNull()?.takeIf{it>0} ?: return null
            val context=f["detailContext"]?.toLongOrNull()?.takeIf{it>0} ?: return null
            return DetailTimingSnapshot(attempt,context,kind,DetailTimingPoint.entries.mapNotNull{k->f["detailTiming"+k.name]?.toLongOrNull()?.takeIf{it in 0..604_800_000L}?.let{k to it}}.toMap(),terminal,
                DetailActualBackend.entries.firstOrNull{it.name==f["detailActualBackend"]},DetailActualQuality.entries.firstOrNull{it.name==f["detailActualQuality"]},f["detailBackendReason"]?.let(DetailBackendReason::fromId),
                f["detailRequestedGpu"]?.toBooleanStrictOrNull(),f["detailCpuFallback"]?.toBooleanStrictOrNull(),f["detailConfirmedSubmits"]?.toLongOrNull()?.takeIf{it in 0..1_800_000_000L},
                DetailSourceRoute.entries.firstOrNull{it.name==f["detailSourceRoute"]},f["detailGridWidth"]?.toIntOrNull()?.takeIf{it in 1..1536},f["detailGridHeight"]?.toIntOrNull()?.takeIf{it in 1..1536},
                DetailDiagnosticFailure.entries.firstOrNull{it.name==f["detailFailure"]},f["detailSuppressed"]?.toLongOrNull()?.takeIf{it in 0..1_000_000} ?: 0,
                f["detailSourceCacheHit"]?.toBooleanStrictOrNull(),DetailRetirementReason.entries.firstOrNull{it.name==f["detailRetirementReason"]},
                DetailNativeMetric.entries.mapNotNull{k->f["detailNative"+k.name]?.toDoubleOrNull()?.takeIf(k::accepts)?.let{k to it}}.toMap())
        }
    }
}
/** One small accumulator per build/output, no per-pixel work. All IO is bounded and asynchronous.
 * FIRST_DRAW means this View called Canvas.drawBitmap, not GPU fence, compositor or panel presentation.
 * confirmedSubmits is the native context's cumulative confirmed scientific count, not new submits per ROI.
 */
class DetailTiming(
    val kind:DetailAttemptKind,val contextToken:Long?=null,
    private val now:()->Long={SystemClock.elapsedRealtime()},private val started:Long=now(),
    private val record:(DetailTimingSnapshot)->Unit=DetailDiagnostics::record,
):AbstractCoroutineContextElement(Key) {
    val attempt=tokens.incrementAndGet()
    val context= contextToken ?: attempt
    private val points=java.util.EnumMap<DetailTimingPoint,Long>(DetailTimingPoint::class.java)
    private var finished=false
    private var drawDeadlineReported=false
    private var backend:DetailActualBackend?=null;private var quality:DetailActualQuality?=null;private var reason:DetailBackendReason?=null
    private var requested:Boolean?=null;private var fallback:Boolean?=null;private var submits:Long?=null
    private var sourceRoute:DetailSourceRoute?=null;private var width:Int?=null;private var height:Int?=null
    private var sourceCacheHit:Boolean?=null;private var retirementReason:DetailRetirementReason?=null
    private var nativeMetrics:Map<DetailNativeMetric,Double> = emptyMap()
    init { mark(DetailTimingPoint.REQUESTED) }
    @Synchronized fun mark(point:DetailTimingPoint){if(!finished)points.putIfAbsent(point,(now()-started).coerceAtLeast(0))}
    @Synchronized fun source(linear:Boolean,w:Int?=null,h:Int?=null){if(!finished){sourceRoute=if(linear)DetailSourceRoute.EXISTING_LINEAR else DetailSourceRoute.DECODE_ORIGINAL;width=w;height=h}}
    @Synchronized fun sourceCache(hit:Boolean){if(!finished)sourceCacheHit=hit}
    @Synchronized fun retiring(reason:DetailRetirementReason){if(!finished)retirementReason=reason}
    /** Call only AFTER DetailBackendContract validation. Unknown reasons become one fixed enum. */
    @Synchronized fun validatedBackend(result:JSONObject){if(!finished)runCatching{
        val b=result.getJSONObject("backend")
        backend=when(b.getString("actualBackend")){"cpu"->DetailActualBackend.CPU;"wgpu"->DetailActualBackend.WGPU;else->null}
        quality=when(result.getString("quality")){"ExactSameWorkingGrid"->DetailActualQuality.EXACT_SAME_WORKING_GRID;"ApproximateSameWorkingGrid"->DetailActualQuality.APPROXIMATE_SAME_WORKING_GRID;else->null}
        requested=b.getBoolean("requestedGpu");fallback=b.getBoolean("cpuFallback");reason=DetailBackendReason.fromId(b.getString("reason"))
        val count=b.opt("gpuBatches");submits=if(count is Int || count is Long)(count as Number).toLong().takeIf{it in 0..1_800_000_000L}else null
        val identity=result.getJSONObject("identity");width=identity.optInt("width").takeIf{it in 1..1536};height=identity.optInt("height").takeIf{it in 1..1536}
        val performance=result.optJSONObject("detailPerformance")
        nativeMetrics=DetailNativeMetric.entries.mapNotNull { metric ->
            (performance?.opt(metric.id) as? Number)?.toDouble()?.takeIf(metric::accepts)?.let{metric to it}
        }.toMap()
    }}
    @Synchronized fun presentation():DetailTiming=DetailTiming(DetailAttemptKind.FIRST_PRESENTATION,context,now,started,record).also{
        it.points.putAll(points);it.backend=backend;it.quality=quality;it.reason=reason;it.requested=requested;it.fallback=fallback;it.submits=submits;it.sourceRoute=sourceRoute;it.width=width;it.height=height;it.sourceCacheHit=sourceCacheHit;it.nativeMetrics=nativeMetrics
    }
    private data class OutputObservation(val context:Long,val started:Long,val points:Map<DetailTimingPoint,Long>,val backend:DetailActualBackend?,val quality:DetailActualQuality?,val reason:DetailBackendReason?,val requested:Boolean?,val fallback:Boolean?,val submits:Long?,val metrics:Map<DetailNativeMetric,Double>)
    @Synchronized private fun outputObservation()=OutputObservation(context,started,points.toMap(),backend,quality,reason,requested,fallback,submits,nativeMetrics)
    /** The first actually drawn output, not the old OPEN_READY cumulative snapshot.
     * Snapshot the other accumulator before locking this one, avoiding lock-order cycles. */
    fun drawnOutput(output:DetailTiming?) {
        if(output==null || output.kind !in setOf(DetailAttemptKind.REGION,DetailAttemptKind.TILE))return
        val observed=output.outputObservation()
        synchronized(this) {
            if(finished || kind!=DetailAttemptKind.FIRST_PRESENTATION || observed.context!=context)return
            if(observed.backend!=null){backend=observed.backend;quality=observed.quality;reason=observed.reason;requested=observed.requested;fallback=observed.fallback;submits=observed.submits}
            nativeMetrics=nativeMetrics+observed.metrics
            for((from,to) in listOf(DetailTimingPoint.RENDER_QUEUED to DetailTimingPoint.FIRST_REGION_QUEUED,DetailTimingPoint.JNI_ENTER to DetailTimingPoint.FIRST_REGION_JNI_ENTER,DetailTimingPoint.JNI_EXIT to DetailTimingPoint.FIRST_REGION_JNI_EXIT,DetailTimingPoint.ADOPTED to DetailTimingPoint.FIRST_REGION_ADOPTED)) {
                observed.points[from]?.let{points.putIfAbsent(to,(observed.started-started+it).coerceAtLeast(0))}
            }
        }
    }
    @Synchronized fun finish(terminal:DetailTimingTerminal,failure:DetailDiagnosticFailure?=null){
        if(finished)return
        // A slow main thread is evidence, not a reason to erase the later adoption/draw.
        // At most one deadline record plus one final record for this local attempt.
        if(terminal==DetailTimingTerminal.DRAW_NOT_OBSERVED){
            if(drawDeadlineReported)return;drawDeadlineReported=true
        }else finished=true
        record(DetailTimingSnapshot(attempt,context,kind,points.toMap(),terminal,backend,quality,reason,requested,fallback,submits,sourceRoute,width,height,failure,sourceCacheHit=sourceCacheHit,retirementReason=retirementReason,nativeMetrics=nativeMetrics))
    }
    fun monitor(job:Job){job.invokeOnCompletion{cause->if(cause!=null)failed(cause)}}
    fun failed(error:Throwable){finish(if(error is CancellationException)DetailTimingTerminal.CANCELLED else DetailTimingTerminal.FAILED,detailDiagnosticFailure(error))}
    fun returned(){mark(DetailTimingPoint.RETURNED);scope.launch{delay(1000);finish(DetailTimingTerminal.DRAW_NOT_OBSERVED)}}
    @Synchronized fun drawn(){mark(DetailTimingPoint.FIRST_DRAW);finish(DetailTimingTerminal.DRAW_CALLBACK)}
    companion object Key:CoroutineContext.Key<DetailTiming> {
        private val tokens=AtomicLong();private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    }
}
internal fun detailDiagnosticFailure(error:Throwable):DetailDiagnosticFailure=when(error){
    is CancellationException->DetailDiagnosticFailure.CANCELLED
    is dev.filmroom.engine.NativeWorkUnavailable->DetailDiagnosticFailure.MEMORY_ADMISSION
    is OutOfMemoryError->DetailDiagnosticFailure.ALLOCATION
    is LinkageError->DetailDiagnosticFailure.UNAVAILABLE
    is java.io.IOException->DetailDiagnosticFailure.SOURCE_IO
    is dev.filmroom.engine.DetailUnavailable->when(error.code){
        "BUDGET_EXCEEDED","WORKING_MEMORY_BUDGET"->DetailDiagnosticFailure.MEMORY_BUDGET
        "RESOURCE_PRESSURE"->DetailDiagnosticFailure.MEMORY_PRESSURE
        "STORAGE_BUDGET"->DetailDiagnosticFailure.STORAGE
        "INVALID_RESULT"->DetailDiagnosticFailure.INVALID_RESULT
        "UNSUPPORTED"->DetailDiagnosticFailure.UNSUPPORTED
        "UNAVAILABLE","DATA_NOT_READY"->DetailDiagnosticFailure.UNAVAILABLE
        "GPU_COMPLETION_UNCERTAIN"->DetailDiagnosticFailure.GPU_UNCERTAIN
        "GPU_EXECUTION_FAILED"->DetailDiagnosticFailure.GPU_FAILED
        else->DetailDiagnosticFailure.OTHER
    }
    else->DetailDiagnosticFailure.OTHER
}
/** At most 8 records/sec, queue 16. No caller waits for disk or per-gesture telemetry. */
internal object DetailDiagnostics {
    private val rate=PreviewDiagnosticRateGate();private val dropped=AtomicLong()
    private val queue=Channel<DetailTimingSnapshot>(16)
    init{CoroutineScope(SupervisorJob()+Dispatchers.IO).launch{for(record in queue)runCatching{
        val suppressed=rate.accept(SystemClock.elapsedRealtime()) ?: return@runCatching
        val info=SafeInfo(detailTiming=record.copy(suppressed=(suppressed+dropped.getAndSet(0)).coerceAtMost(1_000_000)))
        val id=Diagnostics.begin(Operation.PREVIEW_PRESENTATION,info)
        Diagnostics.end(id,when(record.terminal){DetailTimingTerminal.FAILED->Outcome.FAILED;DetailTimingTerminal.CANCELLED,DetailTimingTerminal.RETIRED,DetailTimingTerminal.STALE->Outcome.CANCELLED;else->Outcome.SUCCESS})
    }}}
    fun record(record:DetailTimingSnapshot){if(!queue.trySend(record).isSuccess)dropped.updateAndGet{(it+1).coerceAtMost(1_000_000)}}
}
