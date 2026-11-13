package dev.filmroom.engine

import dev.filmroom.diagnostics.NativePhase

/** Visual distribution only, not a claim that an image-wide scientific phase has completed. */
data class ExportVisualProgress(val stage:Int,val percent:Int?=null) {
    init { require(stage in 1..6 && (percent==null || percent in 0..100)) }
}

/** One instance per native export attempt. Never modifies native counters or GPU diagnostics.
 * Actual setup/exposure keep steps 1/2. The native stage-wide work counter distributes GPU chain work over visual steps
 * 3/4/5/6, including measured diffusion prepass work already in that counter.
 * Per-pass pixels reset between FilmLog passes and must never be used here.
 * Reserved output/finalization work remains in the denominator. Batch phases are ignored.
 * Output/save are not part of this distribution; only real output publication can finish 6.
 */
internal class GpuExportPresentation {
    private var jobId:Long?=null
    private var previous:ExportVisualProgress?=null
    private var chainStarted=false
    private var completedUnits=-1L
    private var lastWork=-1L
    private var lastTotal=-1L
    private var replayed=false

    fun project(value:EngineProgress):EngineProgress {
        if(jobId!=value.jobId) {
            jobId=value.jobId;previous=null;chainStarted=false;completedUnits=-1;lastWork=-1;lastTotal=-1;replayed=false
        }
        val chain=value.phase in setOf(NativePhase.GPU_FULL_CHAIN,NativePhase.GPU_BANDED_CHAIN,NativePhase.GPU_TILED_CHAIN)
        val prepass=value.phase==NativePhase.GPU_DIFFUSION_PREPASS
        if(!chain && !prepass) {
            if(!chainStarted && value.display.stage<=2)
                previous=ExportVisualProgress(value.display.displayStage,value.display.displayPercent)
            return value
        }
        // Prepass and chain share the same native stage-work denominator. Using
        // it for both avoids a reset when the chain starts, and does not depend
        // on polling having observed the prepass. This is completed work, not
        // image-wide phase completion or a remaining-time estimate.
        val visual=run {
            chainStarted=true
            val raw=value.display
            if(raw.stage==6 && raw.total>0) {
                // Native can replay corrupt cached work inside a work unit. Once
                // its measured counter regresses, no percentage is trustworthy
                // until output or a fresh attempt. Never pin it at a false 99%.
                if(lastTotal>0 && (raw.total!=lastTotal || raw.done<lastWork))replayed=true
                lastWork=maxOf(lastWork,raw.done);lastTotal=raw.total
                val units=java.math.BigInteger.valueOf(raw.done).multiply(java.math.BigInteger.valueOf(400))
                    .divide(java.math.BigInteger.valueOf(raw.total)).toLong().coerceAtMost(399)
                completedUnits=maxOf(completedUnits,units)
            }
            val stage=if(completedUnits<0)previous?.stage?.coerceAtLeast(3) ?: 3 else 3+(completedUnits/100).toInt()
            ExportVisualProgress(stage,if(completedUnits<0 || replayed)null else (completedUnits%100).toInt())
        }
        previous=visual
        return value.copy(display=value.display.copy(visual=visual))
    }
}
