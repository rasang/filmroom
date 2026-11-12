package dev.filmroom.engine

import dev.filmroom.diagnostics.GpuWorkPhase
import dev.filmroom.diagnostics.GpuTilePhase
import dev.filmroom.i18n.L10n
import dev.filmroom.R
import org.json.JSONObject

/** Actual completed batch metadata, never a percentage for the whole science chain. */
data class GpuWorkProgress(
    val phase:GpuWorkPhase?=null, val tileIndex:Long?=null, val tileTotal:Long?=null,
    val batchIndex:Long?=null, val batchElapsedMs:Double?=null, val chainWaitMs:Double?=null,
    val completedSubmits:Long?=null, val failedPhase:GpuWorkPhase?=null, val failedElapsedMs:Double?=null,
    val prepass:Boolean=false,
    val failureKind:dev.filmroom.diagnostics.GpuFailureKind?=null, val submitMs:Double?=null, val pollMs:Double?=null, val mapWaitMs:Double?=null,
    val tilePhase:GpuTilePhase?=null, val coreWidth:Long?=null, val coreHeight:Long?=null, val expandedWidth:Long?=null, val expandedHeight:Long?=null, val halo:Long?=null, val expandedRatio:Double?=null, val planBytes:Long?=null, val reservedBytes:Long?=null,
    // Wall-clock snapshot captured before panic/unwind, not GPU execution time.
    val mapTimeoutElapsedMs:Double?=null, val mapPollCount:Long?=null, val mapMaxPollMs:Double?=null, val mapMaxSleepMs:Double?=null, val mapCompletedMaps:Long?=null, val mapExpectedMaps:Long?=null, val halationComponent:Long?=null, val halationChannel:Long?=null, val halationRadius:Long?=null, val halationTileWidth:Long?=null, val halationTileHeight:Long?=null
) {
    val compactText:String get()=buildString {
        append(L10n.text(if(prepass)R.string.gpu_phase_prepass else R.string.gpu_progress_processing))
        if(tileIndex!=null&&tileTotal!=null)append(" · ").append(L10n.text(R.string.gpu_progress_tile,tileIndex,tileTotal))
        if(phase!=null&&batchElapsedMs!=null)append('\n').append(L10n.text(R.string.gpu_progress_last_completed,phase.label,batchElapsedMs.toLong()))
    }
    fun toJson()=JSONObject().apply {
        put("gpuPhase",phase?.id);put("gpuTileIndex",tileIndex);put("gpuTileTotal",tileTotal)
        put("gpuBatchIndex",batchIndex);put("gpuBatchElapsedMs",batchElapsedMs);put("gpuChainWaitMs",chainWaitMs)
        put("gpuCompletedSubmits",completedSubmits);put("gpuFailedPhase",failedPhase?.id);put("gpuFailedElapsedMs",failedElapsedMs)
        put("gpuFailureKind",failureKind?.id);put("gpuSubmitMs",submitMs);put("gpuPollMs",pollMs);put("gpuMapWaitMs",mapWaitMs)
        put("gpuTilePhase",tilePhase?.id)
        put("gpuCoreWidth",coreWidth)
        put("gpuCoreHeight",coreHeight)
        put("gpuExpandedWidth",expandedWidth)
        put("gpuExpandedHeight",expandedHeight)
        put("gpuHalo",halo)
        put("gpuExpandedRatio",expandedRatio)
        put("gpuPlanBytes",planBytes)
        put("gpuReservedBytes",reservedBytes)
        put("gpuMapTimeoutElapsedMs",mapTimeoutElapsedMs)
        put("gpuMapPollCount",mapPollCount)
        put("gpuMapMaxPollMs",mapMaxPollMs)
        put("gpuMapMaxSleepMs",mapMaxSleepMs)
        put("gpuMapCompletedMaps",mapCompletedMaps)
        put("gpuMapExpectedMaps",mapExpectedMaps)
        put("gpuHalationComponent",halationComponent)
        put("gpuHalationChannel",halationChannel)
        put("gpuHalationRadius",halationRadius)
        put("gpuHalationTileWidth",halationTileWidth)
        put("gpuHalationTileHeight",halationTileHeight)
        put("prepass",prepass);put("gpuScience",true)
    }
    companion object {
        fun fromJson(data:JSONObject?):GpuWorkProgress? {
            if(data==null)return null
            fun count(key:String,min:Long=0):Long?=(when(val x=data.opt(key)){is Int->x.toLong();is Long->x;else->null})?.takeIf{it in min..1_800_000_000L}
            fun time(key:String):Double?=(data.opt(key) as? Number)?.toDouble()?.takeIf{it.isFinite()&&it in 0.0..604_800_000.0}
            fun bytes(key:String):Long?=(when(val x=data.opt(key)){is Int->x.toLong();is Long->x;else->null})?.takeIf{it in 0..68_719_476_736L}
            val total=count("gpuTileTotal",1)
            val index=count("gpuTileIndex",1)?.takeIf{total!=null&&it<=total}
            val batch=count("gpuBatchIndex",1);val elapsed=time("gpuBatchElapsedMs")
            val phase=GpuWorkPhase.fromId(data.opt("gpuPhase") as? String)?.takeIf{batch!=null&&elapsed!=null}
            val failedElapsed=time("gpuFailedElapsedMs")
            val failed=GpuWorkPhase.fromId(data.opt("gpuFailedPhase") as? String)?.takeIf{failedElapsed!=null}
            val submits=count("gpuCompletedSubmits")
            if(phase==null&&index==null&&failed==null&&data.opt("gpuScience")!=true&&data.opt("prepass")!=true)return null
            return GpuWorkProgress(phase,index,total.takeIf{index!=null},batch,elapsed,time("gpuChainWaitMs"),submits,failed,failedElapsed,data.opt("prepass")==true, dev.filmroom.diagnostics.GpuFailureKind.fromId(data.opt("gpuFailureKind") as? String),time("gpuSubmitMs"),time("gpuPollMs"),time("gpuMapWaitMs"),
                tilePhase=GpuTilePhase.fromId(data.opt("gpuTilePhase") as? String), coreWidth=count("gpuCoreWidth",1)?.takeIf{it<=40000}, coreHeight=count("gpuCoreHeight",1)?.takeIf{it<=40000}, expandedWidth=count("gpuExpandedWidth",1)?.takeIf{it<=40000}, expandedHeight=count("gpuExpandedHeight",1)?.takeIf{it<=40000}, halo=count("gpuHalo")?.takeIf{it<=40000}, expandedRatio=(data.opt("gpuExpandedRatio") as? Number)?.toDouble()?.takeIf{it.isFinite()&&it in 1.0..1_600_000_000.0}, planBytes=bytes("gpuPlanBytes"), reservedBytes=bytes("gpuReservedBytes"),
                mapTimeoutElapsedMs=time("gpuMapTimeoutElapsedMs"), mapPollCount=count("gpuMapPollCount")?.takeIf{it<=1800000000}, mapMaxPollMs=time("gpuMapMaxPollMs"), mapMaxSleepMs=time("gpuMapMaxSleepMs"), mapCompletedMaps=count("gpuMapCompletedMaps")?.takeIf{it<=1000}, mapExpectedMaps=count("gpuMapExpectedMaps")?.takeIf{it<=1000}, halationComponent=count("gpuHalationComponent")?.takeIf{it<=3}, halationChannel=count("gpuHalationChannel")?.takeIf{it<=2}, halationRadius=count("gpuHalationRadius")?.takeIf{it<=40000}, halationTileWidth=count("gpuHalationTileWidth")?.takeIf{it<=40000}, halationTileHeight=count("gpuHalationTileHeight")?.takeIf{it<=40000})
        }
    }
}
