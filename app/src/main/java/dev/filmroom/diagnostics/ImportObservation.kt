package dev.filmroom.diagnostics

import dev.filmroom.engine.NativeMemorySnapshot
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Per-import observation only. No file reads, OS queries, callbacks or admission changes. */
internal class ImportObservation : ThreadContextElement<ImportObservation?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ImportObservation> {
        private val local=ThreadLocal<ImportObservation?>()
        fun current():ImportObservation?=local.get()
    }
    override fun updateThreadContext(context:CoroutineContext):ImportObservation?=local.get().also{local.set(this)}
    override fun restoreThreadContext(context:CoroutineContext,oldState:ImportObservation?){if(oldState==null)local.remove()else local.set(oldState)}
    var rawFormat:dev.filmroom.codec.RawFormat?=null
    var width:Int?=null
    var height:Int?=null
    var memoryStage=ImportMemoryStage.UNKNOWN
    var memory:ImportMemoryInfo?=null
    var budgetSnapshot:NativeMemorySnapshot?=null
    fun dimensions(w:Int,h:Int){try{width=w.takeIf{it in 1..1_000_000};height=h.takeIf{it in 1..1_000_000}}catch(_:Throwable){}}
    fun budget(requested:Int,selected:Int){try{
        memoryStage=ImportMemoryStage.CODEC_BUDGET_SELECTION
        val snapshot=budgetSnapshot
        memory=ImportMemoryInfo(snapshotStage=ImportMemorySnapshotStage.BUDGET_SELECTION,admitted=requested>=128,requestedBudgetMiB=requested,selectedBudgetMiB=selected,
            availableBytes=snapshot?.availableBytes,thresholdBytes=snapshot?.thresholdBytes,lowMemory=snapshot?.lowMemory)
        if(requested<128)failure(ImportMemoryFailure.CODEC_BUDGET_SELECTION)
    }catch(_:Throwable){} }
    fun admission(estimate:Long,snapshot:NativeMemorySnapshot,capacity:Long,reserved:Long,active:Int,rejected:Boolean){try{
        val previous=memory?:ImportMemoryInfo()
        memory=previous.copy(snapshotStage=ImportMemorySnapshotStage.SHARED_ADMISSION,admitted=!rejected,estimatedBytes=estimate,chargeBytes=estimate+16L*1024*1024,
            availableBytes=snapshot.availableBytes,thresholdBytes=snapshot.thresholdBytes,lowMemory=snapshot.lowMemory,
            capacityBytes=capacity,reservedBytes=reserved,activeCount=active,
            failure=if(rejected)if(memoryStage==ImportMemoryStage.HEIC_ADMISSION)ImportMemoryFailure.HEIC_ADMISSION else ImportMemoryFailure.SHARED_ADMISSION else previous.failure)
    }catch(_:Throwable){} }
    fun failure(value:ImportMemoryFailure){try{memory=(memory?:ImportMemoryInfo()).copy(failure=value)}catch(_:Throwable){}}
}

enum class ImportMemoryStage { UNKNOWN, SOURCE_COPY, INPUT_METADATA, RASTER_PLATFORM, CODEC_BUDGET_SELECTION,
    CODEC_ADMISSION, CODEC_NATIVE, RAW_PREVIEW, HEIC_ESTIMATE, HEIC_ADMISSION, HEIC_PLATFORM, HEIC_SPOOL, HEIC_PREVIEW, COMPLETE }
enum class ImportMemorySnapshotStage { BUDGET_SELECTION, SHARED_ADMISSION }
enum class ImportMemoryFailure { JVM_ALLOCATION, CODEC_BUDGET_SELECTION, SHARED_ADMISSION,
    HEIC_ESTIMATE_LIMIT, HEIC_ADMISSION, HEIC_PLATFORM_ALLOCATION, NATIVE_CODEC_BUDGET }

/** Safe decision-time facts. Unknown is absent; zero is an observed zero, never an invented value. */
data class ImportMemoryInfo(
    val failure:ImportMemoryFailure?=null,
    val requestedBudgetMiB:Int?=null,val selectedBudgetMiB:Int?=null,
    val estimatedBytes:Long?=null,val chargeBytes:Long?=null,
    val availableBytes:Long?=null,val thresholdBytes:Long?=null,val lowMemory:Boolean?=null,
    val capacityBytes:Long?=null,val reservedBytes:Long?=null,val activeCount:Int?=null,
    val snapshotStage:ImportMemorySnapshotStage?=null,val admitted:Boolean?=null,
    val failureAvailableBytes:Long?=null,val failureThresholdBytes:Long?=null,val failureLowMemory:Boolean?=null,
){
    internal fun fields():Map<String,Any> = buildMap {
        failure?.let{put("importMemoryFailure",it.name)}
        snapshotStage?.let{put("importMemorySnapshotStage",it.name)}
        admitted?.let{put("importMemoryAdmitted",it)}
        fun number(k:String,v:Long?,max:Long=1L shl 40){v?.takeIf{it in 0..max}?.let{put(k,it)}}
        number("importRequestedBudgetMiB",requestedBudgetMiB?.toLong(),512)
        number("importSelectedBudgetMiB",selectedBudgetMiB?.toLong(),512)
        number("importEstimatedBytes",estimatedBytes);number("importChargeBytes",chargeBytes)
        number("importAvailableBytes",availableBytes);number("importThresholdBytes",thresholdBytes)
        lowMemory?.let{put("importLowMemory",it)}
        number("importCapacityBytes",capacityBytes);number("importReservedBytes",reservedBytes)
        number("importActiveCount",activeCount?.toLong(),2)
        number("importFailureAvailableBytes",failureAvailableBytes);number("importFailureThresholdBytes",failureThresholdBytes)
        failureLowMemory?.let{put("importFailureLowMemory",it)}
    }
    internal companion object {
        fun fromFields(f:Map<String,String>):ImportMemoryInfo? {
            fun n(k:String,max:Long=1L shl 40):Long?=f[k]?.takeIf{it.length<=13 && it.isNotEmpty() && it.all{c->c in '0'..'9'}}?.toLongOrNull()?.takeIf{it in 0..max}
            return ImportMemoryInfo(ImportMemoryFailure.entries.firstOrNull{it.name==f["importMemoryFailure"]},
                n("importRequestedBudgetMiB",512)?.toInt(),n("importSelectedBudgetMiB",512)?.toInt(),
                n("importEstimatedBytes"),n("importChargeBytes"),n("importAvailableBytes"),n("importThresholdBytes"),
                when(f["importLowMemory"]){"true"->true;"false"->false;else->null},n("importCapacityBytes"),n("importReservedBytes"),n("importActiveCount",2)?.toInt(),
                ImportMemorySnapshotStage.entries.firstOrNull{it.name==f["importMemorySnapshotStage"]},when(f["importMemoryAdmitted"]){"true"->true;"false"->false;else->null},n("importFailureAvailableBytes"),n("importFailureThresholdBytes"),
                when(f["importFailureLowMemory"]){"true"->true;"false"->false;else->null}).takeIf{it.fields().isNotEmpty()}
        }
    }
}
