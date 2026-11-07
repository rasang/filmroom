package dev.filmroom.ui

import dev.filmroom.R
import dev.filmroom.i18n.L10n
import dev.filmroom.model.FilmProfileCapabilities

/** Physical support (paper/print film) and colour model are independent properties. */
internal object PrintMediumLabels {
    fun suffixResource(stock: String, kind: String): Int {
        require(kind == "paper" || kind == "cine_print")
        val monochrome = stock in FilmProfileCapabilities.monochromePrints
        return when {
            kind == "cine_print" && monochrome -> R.string.print_medium_bw_film
            kind == "cine_print" -> R.string.text_b9afff7ca1b0
            monochrome -> R.string.print_medium_bw_paper
            else -> R.string.text_3b789c2037e2
        }
    }
    fun label(stock: String, name: String, kind: String): String =
        name + " " + L10n.text(suffixResource(stock, kind)).trim()
    /** Semantic lines, never split a product name at its first arbitrary space. */
    fun cardLabel(stock: String, name: String, kind: String): String {
        val lines = when (stock) {
            "fujifilm_crystal_archive_typeii" -> listOf("Fujifilm", "Crystal Archive", "Type II")
            "ilford_multigrade_iv_rc" -> listOf("Ilford", "Multigrade IV RC")
            "kodak_2383" -> listOf("Kodak", "Vision", "2383")
            "kodak_2393" -> listOf("Kodak", "Vision Premier", "2393")
            "kodak_2302" -> listOf("Kodak", "B&W Print Film", "2302")
            "kodak_portra_endura" -> listOf("Kodak", "Portra Endura")
            "kodak_supra_endura" -> listOf("Kodak", "Supra Endura")
            "kodak_ultra_endura" -> listOf("Kodak", "Ultra Endura")
            "kodak_endura_premier" -> listOf("Kodak", "Endura Premier")
            "kodak_ektacolor_edge" -> listOf("Kodak", "Ektacolor Edge")
            else -> listOf(name)
        }
        // Keep the full original profile name in accessibility text; the card omits only
        // repetitive "Professional", never the model's identifying words or numbers.
        val type=L10n.text(suffixResource(stock,kind)).trim().removePrefix("·").trim()
            .replace("Black-and-white motion-picture print film", "B&W print film")
            .replace("Color motion-picture print film", "Color print film")
            .replace("Black-and-white paper", "B&W paper")
            .replace("モノクロ映画用プリントフィルム", "モノクロ映画用\nプリントフィルム")
            .replace("カラー映画用プリントフィルム", "カラー映画用\nプリントフィルム")
            .replace("컬러 영화용 프린트 필름", "컬러 영화용\n프린트 필름")
            .replace("흑백 영화용 프린트 필름", "흑백 영화용\n프린트 필름")
        return (lines + type).joinToString("\n")
    }
}
