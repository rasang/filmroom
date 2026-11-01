package dev.filmroom.ui

import dev.filmroom.model.EditorState

/** UI-only compound choice; persisted/native parameters remain independent and compatible. */
internal object AutoExposureChoice {
    val options get() = listOf("off" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_3fd47edce45b), "average" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_a49ffd73bc85), "median" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1961784db559),
        "center_weighted" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1028167558b7), "partial" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6d13a74715fd), "matrix" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_d8b27dee3e23),
        "multi_zone" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_513adb42d450), "highlight_weighted" to dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2064d42c9288))
    fun value(state: EditorState): String = if (state.autoExposure) state.metering else "off"
    fun choose(state: EditorState, value: String): EditorState = when {
        value == "off" -> state.copy(autoExposure = false)
        options.drop(1).any { it.first == value } -> state.copy(autoExposure = true, metering = value)
        else -> state
    }
}
