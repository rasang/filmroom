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
}
