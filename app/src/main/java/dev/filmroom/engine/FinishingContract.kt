package dev.filmroom.engine

import dev.filmroom.model.EditorState
import org.json.JSONObject

/** Never let an older bundled native library silently ignore an active grade. */
internal object FinishingContract {
    fun supported(parameters:EditorState,capabilities:JSONObject):Boolean =
        !parameters.hasFinishing || capabilities.optInt("finishingAbi")==1
}
