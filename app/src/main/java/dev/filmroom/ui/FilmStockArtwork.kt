package dev.filmroom.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import dev.filmroom.R

/** Original packaging renders keyed by immutable engine profile IDs, never display order/name.
 * Depicted physical 135/120 products do not grant the app any frame-strip capability. */
object FilmStockArtwork {
    val physicalStockByProfile: Map<String, String> = mapOf(
        "kodak_doublex" to "kodak_doublex",
        "kodak_trix" to "kodak_trix",
        "kodak_portra_160" to "kodak_portra_160",
        "kodak_portra_400" to "kodak_portra_400",
        "kodak_portra_800" to "kodak_portra_800",
        "kodak_portra_800_push1" to "kodak_portra_800",
        "kodak_portra_800_push2" to "kodak_portra_800",
        "kodak_gold_200" to "kodak_gold_200",
        "kodak_ultramax_400" to "kodak_ultramax_400",
        "kodak_ektar_100" to "kodak_ektar_100",
        "fujifilm_c200" to "fujifilm_c200",
        "fujifilm_xtra_400" to "fujifilm_xtra_400",
        "fujifilm_pro_400h" to "fujifilm_pro_400h",
        "fujifilm_provia_100f" to "fujifilm_provia_100f",
        "fujifilm_velvia_100" to "fujifilm_velvia_100",
        "kodak_ektachrome_100" to "kodak_ektachrome_100",
        "kodak_kodachrome_64" to "kodak_kodachrome_64",
        "kodak_vision3_50d" to "kodak_vision3_50d",
        "kodak_vision3_200t" to "kodak_vision3_200t",
        "kodak_vision3_250d" to "kodak_vision3_250d",
        "kodak_vision3_500t" to "kodak_vision3_500t",
        "kodak_verita_200d" to "kodak_verita_200d",
        "kodak_tri_x_400" to "kodak_tri_x_400",
        "kodak_tmax_100" to "kodak_tmax_100",
        "fujifilm_neopan_acros_100_ii" to "fujifilm_neopan_acros_100_ii",
        "ilford_hp5_plus_400" to "ilford_hp5_plus_400",
        "cinestill_800t" to "cinestill_800t",
    )
    private val bitmaps = mutableMapOf<String, Bitmap>()
    fun assetPath(profileId: String): String? = physicalStockByProfile[profileId]?.let { "film-rolls/$it.webp" }

    fun drawable(context: Context, profileId: String): Drawable {
        if (profileId == "none") return NoFilmArtwork()
        val path = assetPath(profileId)
        val bitmap = path?.let {
            synchronized(bitmaps) {
                bitmaps[it] ?: runCatching {
                    context.assets.open(it).use { input -> BitmapFactory.decodeStream(input) }
                }.getOrNull()?.also { decoded -> bitmaps[it] = decoded }
            }
        }
        // An unknown or damaged asset must never silently show a different film stock.
        return if (bitmap != null) BitmapDrawable(context.resources, bitmap)
        else context.getDrawable(R.drawable.ic_film)!!.mutate()
    }

    fun cardLabel(profileId: String, displayName: String): String = when (profileId) {
        "kodak_portra_800_push1" -> "Kodak\nPortra 800 · +1 PUSH"
        "kodak_portra_800_push2" -> "Kodak\nPortra 800 · +2 PUSH"
        "fujifilm_neopan_acros_100_ii" -> "Fujifilm Neopan\n100 Acros II"
        else -> displayName.replaceFirst(" ", "\n")
    }
}
