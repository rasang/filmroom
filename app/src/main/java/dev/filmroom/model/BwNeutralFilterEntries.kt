package dev.filmroom.model

/** Exact added upstream lookup availability, not a promise that every density is neutral.
 * Generated from the bundled filter DB; host tests verify every pair and illuminant. */
internal object BwNeutralFilterEntries {
    private val pairs=setOf(
        "kodak_trix|kodak_ultra_endura|TH-KG3",
        "kodak_doublex|kodak_ultra_endura|TH-KG3",
        "kodak_trix|kodak_endura_premier|TH-KG3",
        "kodak_doublex|kodak_endura_premier|TH-KG3",
        "kodak_trix|kodak_ektacolor_edge|TH-KG3",
        "kodak_doublex|kodak_ektacolor_edge|TH-KG3",
        "kodak_trix|kodak_supra_endura|TH-KG3",
        "kodak_doublex|kodak_supra_endura|TH-KG3",
        "kodak_trix|kodak_portra_endura|TH-KG3",
        "kodak_doublex|kodak_portra_endura|TH-KG3",
        "kodak_trix|fujifilm_crystal_archive_typeii|TH-KG3",
        "kodak_doublex|fujifilm_crystal_archive_typeii|TH-KG3",
        "kodak_trix|kodak_2383|TH-KG3",
        "kodak_doublex|kodak_2383|TH-KG3",
        "kodak_trix|kodak_2393|TH-KG3",
        "kodak_doublex|kodak_2393|TH-KG3",
        "kodak_ektar_100|kodak_2302|TH-KG3",
        "kodak_portra_160|kodak_2302|TH-KG3",
        "kodak_portra_400|kodak_2302|TH-KG3",
        "kodak_portra_800|kodak_2302|TH-KG3",
        "kodak_portra_800_push1|kodak_2302|TH-KG3",
        "kodak_portra_800_push2|kodak_2302|TH-KG3",
        "kodak_gold_200|kodak_2302|TH-KG3",
        "kodak_ultramax_400|kodak_2302|TH-KG3",
        "kodak_vision3_50d|kodak_2302|TH-KG3",
        "kodak_vision3_250d|kodak_2302|TH-KG3",
        "kodak_verita_200d|kodak_2302|TH-KG3",
        "kodak_vision3_200t|kodak_2302|TH-KG3",
        "kodak_vision3_500t|kodak_2302|TH-KG3",
        "fujifilm_pro_400h|kodak_2302|TH-KG3",
        "fujifilm_c200|kodak_2302|TH-KG3",
        "fujifilm_xtra_400|kodak_2302|TH-KG3",
        "kodak_ektachrome_100|kodak_2302|TH-KG3",
        "kodak_kodachrome_64|kodak_2302|TH-KG3",
        "fujifilm_velvia_100|kodak_2302|TH-KG3",
        "fujifilm_provia_100f|kodak_2302|TH-KG3",
        "kodak_trix|kodak_2302|TH-KG3",
        "kodak_doublex|kodak_2302|TH-KG3",
    )
    fun contains(film:String,paper:String,illuminant:String)="$film|$paper|$illuminant" in pairs
}
