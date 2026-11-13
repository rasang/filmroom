package dev.filmroom.engine

/** Seven public steps; raw stages 7/8 remain distinct for durable old queues and diagnostics. */
data class ExportDisplayProgress(val stage:Int, val done:Long=0, val total:Long=0, val gpu:GpuWorkProgress?=null, val visual:ExportVisualProgress?=null, val measured:Boolean=false) {
    init { require(stage in 1..8 && done>=0 && total>=done && total<=MAX_WORK) }
    val displayStage:Int get()=visual?.stage ?: stage.coerceAtMost(7)
    val label:String get()=LABELS[displayStage-1]
    val percent:Int? get()=if(total>0)(done*100/total).toInt() else null
    // Encoding and cleanup have no common measured denominator. Do not reset or invent
    // a percentage when moving between them; only completed cleanup certifies 100%.
    val displayPercent:Int? get()=if(visual!=null)visual.percent else if(stage<7 || (stage==7 && measured))percent else if(stage==8 && total>0 && done==total)100 else null
    // Two reserved lines keep updates from changing the compact dialog's height.
    val compactText:String get()=dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c6bddcd61e6b, label, displayStage)+(displayPercent?.let{" · $it%"} ?: "")
    companion object {
        private const val MAX_WORK=Long.MAX_VALUE/100
        private val LABELS get()=listOf(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d23207c07a82),dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ef1ae2c65edd),dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6159fa0e2bb0),dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_63dc46ef8527),dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6817024599e0),dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3b490fba1ffc),dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1cb9d4521713))
        fun forNativePhase(id:String):ExportDisplayProgress=ExportDisplayProgress(when(id){
            "setup"->1
            "auto_exposure","film_exposure"->2
            "highlight_boost","camera_diffusion","camera_blur","film_scatter","film_halation"->3
            "film_log","film_density","dir_diffusion","dir_develop"->4
            "grain_sampling","grain_cloud","grain_microstructure","grain_blur"->5
            else->6 // Includes native scratch cleanup, not the final app-owned file cleanup.
        })
    }
}
