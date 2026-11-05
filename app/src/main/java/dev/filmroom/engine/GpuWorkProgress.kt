package dev.filmroom.engine

import dev.filmroom.diagnostics.GpuWorkPhase
import dev.filmroom.i18n.L10n
import dev.filmroom.R
import org.json.JSONObject

/** Actual completed batch metadata, never a percentage for the whole science chain. */
data class GpuWorkProgress(
    val phase:GpuWorkPhase?=null, val tileIndex:Long?=null, val tileTotal:Long?=null,
    val batchIndex:Long?=null, val batchElapsedMs:Double?=null, val chainWaitMs:Double?=null,
    val completedSubmits:Long?=null, val failedPhase:GpuWorkPhase?=null, val failedElapsedMs:Double?=null,
    val prepass:Boolean=false
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
        put("prepass",prepass);put("gpuScience",true)
    }
    companion object {
        fun fromJson(data:JSONObject?):GpuWorkProgress? {
            if(data==null)return null
            fun count(key:String,min:Long=0):Long?=(when(val x=data.opt(key)){is Int->x.toLong();is Long->x;else->null})?.takeIf{it in min..1_800_000_000L}
            fun time(key:String):Double?=(data.opt(key) as? Number)?.toDouble()?.takeIf{it.isFinite()&&it in 0.0..604_800_000.0}
            val total=count("gpuTileTotal",1)
            val index=count("gpuTileIndex",1)?.takeIf{total!=null&&it<=total}
            val batch=count("gpuBatchIndex",1);val elapsed=time("gpuBatchElapsedMs")
            val phase=GpuWorkPhase.fromId(data.opt("gpuPhase") as? String)?.takeIf{batch!=null&&elapsed!=null}
            val failedElapsed=time("gpuFailedElapsedMs")
            val failed=GpuWorkPhase.fromId(data.opt("gpuFailedPhase") as? String)?.takeIf{failedElapsed!=null}
            val submits=count("gpuCompletedSubmits")
            if(phase==null&&index==null&&failed==null&&data.opt("gpuScience")!=true&&data.opt("prepass")!=true)return null
            return GpuWorkProgress(phase,index,total.takeIf{index!=null},batch,elapsed,time("gpuChainWaitMs"),submits,failed,failedElapsed,data.opt("prepass")==true)
        }
    }
}
