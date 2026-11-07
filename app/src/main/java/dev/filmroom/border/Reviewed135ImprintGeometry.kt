package dev.filmroom.border

/** Exact approved atlases have their own perforation phase, not new imprint semantics.
 * Parameters retain the existing top-gap layout and common-font fitting. The middle
 * real gap is nearest the photo center; even field counts leave that gap unused.
 * Dates stay inside the photograph. No atlas marks or perforations are moved.
 */
internal object Reviewed135ImprintGeometry {
    data class Layout(val centralGapMm:Double) {
        val pitchMm=4.75
        // Hole width is 1.981mm (2.794mm is its height). Actual feathered
        // alpha leaves a 2.675mm opaque gap, enough for the original 2.35mm
        // full-mask fit plus one final output-pixel rounding allowance.
    }
    private val layouts=mapOf(
        "kodak_tri_x_400" to Layout(18.6375),
        "kodak_tmax_100" to Layout(18.2375),
        "ilford_hp5_plus_400" to Layout(17.5875),
        "fujifilm_neopan_acros_100_ii" to Layout(20.5375),
        "cinestill_800t" to Layout(19.0375),
    )
    fun layout(snapshot:BorderSnapshot):Layout? =
        if(snapshot.geometryVersion in 2..3 && Reviewed135Definitions.matches(snapshot.stock,snapshot.assetVersion))layouts[snapshot.stock] else null
}
