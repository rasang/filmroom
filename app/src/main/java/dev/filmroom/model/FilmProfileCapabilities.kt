package dev.filmroom.model

/** Scientific identity is independent of availability of presentation artwork. */
internal object FilmProfileCapabilities {
    val spektraLabFilms = setOf(
        "kodak_tri_x_400", "kodak_tmax_100", "fujifilm_neopan_acros_100_ii",
        "ilford_hp5_plus_400", "cinestill_800t",
    )
    val monochromeFilms = setOf("kodak_doublex", "kodak_trix") +
        (spektraLabFilms - "cinestill_800t")
    /** Silver print media remain monochrome even when the shooting film is colour. */
    val monochromePrints = setOf("kodak_2302", "ilford_multigrade_iv_rc")
    // No 135 edge artwork has been supplied. A separately supported 120 frame may still be used.
    // Never substitute another stock's edge.
    val withoutFrameArtwork = spektraLabFilms
}
