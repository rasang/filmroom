package dev.filmroom.model

import org.json.JSONArray

/** One stock-ID contract for editor recipes, QR import and application backup.
 * None is an explicit selection, never a fallback for unknown future profiles. */
object StockSelectionValidation {
    const val NONE = "none"
    fun isSupported(state: EditorState, profiles: JSONArray): Boolean {
        var film = state.filmStock == NONE
        var paper = state.paperStock == NONE
        for (index in 0 until profiles.length()) {
            val row = profiles.getJSONObject(index)
            val id = row.getString("id")
            val kind = row.getString("kind")
            if (id == state.filmStock && kind in setOf("negative", "reversal")) film = true
            if (id == state.paperStock && kind in setOf("paper", "cine_print")) paper = true
        }
        return film && paper
    }
}
