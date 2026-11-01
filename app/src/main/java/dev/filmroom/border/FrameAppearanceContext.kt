package dev.filmroom.border

import org.json.JSONObject

/** Frozen presentation semantics, separate from geometry and scientific parameters.
 * A null context on BorderSnapshot is a legacy immutable frame: never infer its route. */
data class FrameAppearanceContext(
    val outputRoute:String,
    val filmKind:FilmKind,
    val negativeWhiteHoles:Boolean,
    val contractVersion:Int=1,
) {
    enum class FilmKind { NEGATIVE, REVERSAL }
    enum class Material { POSITIVE_DISPLAY, NEGATIVE_TRANSMISSION }
    init {
        require(contractVersion==1)
        require(outputRoute=="print" || outputRoute=="direct")
        require(filmKind!=FilmKind.REVERSAL || outputRoute=="direct")
    }
    val material get()=if(filmKind==FilmKind.NEGATIVE && outputRoute=="direct")Material.NEGATIVE_TRANSMISSION else Material.POSITIVE_DISPLAY
    val holeMatteSrgb get()=if(filmKind==FilmKind.REVERSAL || negativeWhiteHoles)0xffffff else 0
    fun json()=JSONObject().apply {
        put("version",contractVersion);put("outputRoute",outputRoute)
        put("filmKind",filmKind.name);put("negativeWhiteHoles",negativeWhiteHoles)
    }
    companion object {
        fun fromJson(j:JSONObject)=FrameAppearanceContext(j.getString("outputRoute"),FilmKind.valueOf(j.getString("filmKind")),j.getBoolean("negativeWhiteHoles"),j.getInt("version"))
        fun forProfile(profile:String,route:String,white:Boolean)=FrameAppearanceContext(route,FrameProfileCatalog.kind(profile),white)
    }
}

/** Generated from the bundled engine-data/profile_catalog.json kinds. A host parity test
 * guards every profile. No stock-name guessing, asset I/O, or atlas decode on Main. */
internal object FrameProfileCatalog {
    // BEGIN GENERATED CATALOG
    const val catalogSha256="7783788474ef730d170c3313a15964c4b3ec0075049d7a20d42b2bc80b67115d"
    val kinds:Map<String,FrameAppearanceContext.FilmKind> = mapOf(
        "fujifilm_c200" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "fujifilm_pro_400h" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "fujifilm_provia_100f" to FrameAppearanceContext.FilmKind.REVERSAL,
        "fujifilm_velvia_100" to FrameAppearanceContext.FilmKind.REVERSAL,
        "fujifilm_xtra_400" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_ektachrome_100" to FrameAppearanceContext.FilmKind.REVERSAL,
        "kodak_ektar_100" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_gold_200" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_kodachrome_64" to FrameAppearanceContext.FilmKind.REVERSAL,
        "kodak_portra_160" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_portra_400" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_portra_800" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_portra_800_push1" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_portra_800_push2" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_ultramax_400" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_verita_200d" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_vision3_200t" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_vision3_250d" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_vision3_500t" to FrameAppearanceContext.FilmKind.NEGATIVE,
        "kodak_vision3_50d" to FrameAppearanceContext.FilmKind.NEGATIVE,
    )
    // END GENERATED CATALOG
    fun kind(profile:String)=requireNotNull(kinds[profile]) { "Unknown frame film profile" }
}
