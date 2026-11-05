package dev.filmroom.engine

import dev.filmroom.diagnostics.NativePhase

/** Visual distribution only, not a claim that an image-wide scientific phase has completed. */
data class ExportVisualProgress(val stage:Int,val percent:Int?=null) {
    init { require(stage in 1..6 && (percent==null || percent in 0..100)) }
}

/** One instance per native export attempt. Never modifies native counters or GPU diagnostics.
 * Actual setup/exposure keep steps 1/2. Completed core pixels distribute over the old visual
 * steps 3/4/5/6 in equal quarters. Batch phases and tile indices are deliberately ignored.
 * Output/save are not part of this distribution; only real output publication can finish 6.
 */
internal class GpuExportPresentation {
    private var jobId:Long?=null
    private var previous:ExportVisualProgress?=null
    private var chainStarted=false
    private var completedUnits=-1L

    fun project(value:EngineProgress):EngineProgress {
        if(jobId!=value.jobId) {
            jobId=value.jobId;previous=null;chainStarted=false;completedUnits=-1
        }
        val chain=value.phase in setOf(NativePhase.GPU_FULL_CHAIN,NativePhase.GPU_BANDED_CHAIN,NativePhase.GPU_TILED_CHAIN)
        val prepass=value.phase==NativePhase.GPU_DIFFUSION_PREPASS
        if(!chain && !prepass) {
            if(!chainStarted && value.display.stage<=2)
                previous=ExportVisualProgress(value.display.displayStage,value.display.displayPercent)
            return value
        }
        val visual=if(prepass) {
            // Prepass is real work but not completed image pixels. Keep the last certified
            // setup/exposure presentation, with no timer or invented prepass percentage.
            previous ?: ExportVisualProgress(2)
        } else {
            chainStarted=true
            if(value.total>0) {
                // EngineProgress bounds counters to 1.8B, so multiplication is safe.
                val units=(value.done*400/value.total).coerceAtMost(399)
                completedUnits=maxOf(completedUnits,units)
            }
            if(completedUnits<0) previous?.takeIf{it.stage>=3} ?: ExportVisualProgress(3)
            else ExportVisualProgress(3+(completedUnits/100).toInt(),(completedUnits%100).toInt())
        }
        previous=visual
        return value.copy(display=value.display.copy(visual=visual))
    }
}
