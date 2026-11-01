package dev.filmroom.ui

/** Decorative scenes follow worker state; elapsed animation time never advances a stage. */
enum class ProcessingVisualStage {
    PREPARING, READING, EXPOSING, HALATION, DEVELOPING, GRAIN, PRINTING, SAVING, CLEANING, PROCESSING;

    /** Legacy animation description. Export text comes from structured ExportDisplayProgress. */
    val label: String get() = when (this) {
        PREPARING -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c73702747ef1)
        READING -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1663134e88db)
        SAVING -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1cb9d4521713)
        CLEANING -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_11f1bec7ceb0)
        else -> dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2fb03c5fc0b5)
    }

    companion object {
        fun fromExportStep(step:Int):ProcessingVisualStage=when(step){
            1->PREPARING;2->EXPOSING;3->HALATION;4->DEVELOPING;5->GRAIN;6->PRINTING;7,8->SAVING
            else->PROCESSING
        }

        fun fromMessage(message: String): ProcessingVisualStage {
            // Match complete translated phase labels, never translated fragments whose grammar differs.
            dev.filmroom.diagnostics.NativePhase.entries.firstOrNull {
                message == it.chinese || message.split(" · ").any { part -> part == it.chinese }
            }?.let { return fromNativePhase(it) }
            return legacyMessage(message)
        }

        fun fromNativePhase(phase:dev.filmroom.diagnostics.NativePhase):ProcessingVisualStage = when(phase.id) {
            "setup" -> PREPARING
            "auto_exposure" -> READING
            "highlight_boost", "film_exposure" -> EXPOSING
            "camera_blur", "scan_glare", "print_diffusion", "camera_diffusion", "film_scatter", "film_halation" -> HALATION
            "film_log", "film_density", "dir_diffusion", "dir_develop" -> DEVELOPING
            "grain_microstructure", "grain_sampling", "grain_cloud", "grain_blur" -> GRAIN
            "scan_blur", "printing_scanning", "scan_sharpen" -> PRINTING
            else -> PROCESSING
        }

        private fun legacyMessage(message: String): ProcessingVisualStage = when {
            message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_2cd0f3be8738)) && message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_9d2998cca517)) -> CLEANING
            message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e66883ffd17e)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1cb9d4521713)) -> SAVING
            message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_7b5d2a09babd)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_534cb3fa8fbf)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b3622bcacaaf)) -> READING
            message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_129547c98a3a)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_652bd8e2b42a)) || message == dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6dfdd1fe6561) -> PREPARING
            message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b82bd426d236)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_b65851ed8941)) -> GRAIN
            message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_c08900693d28)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6a36379e12c4)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_99ccd913c2c3)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_88d9568f838c)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e29e511b19c8)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_bab826d7cea1)) -> HALATION
            message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6534faa7428f)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1a8fd554611b)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6fb121b28fdd)) -> PRINTING
            message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_1b5d3d55ef48)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_4bd29b339038)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_133496718bdc)) -> DEVELOPING
            message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_6ff3e6e82562)) || message.contains(dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_ee5e1f321d0c)) -> EXPOSING
            else -> PROCESSING
        }
    }
}
