package dev.filmroom.diagnostics

import android.os.Build

/** Public product/OS labels only. Never collect serial, Android ID, fingerprint or user device name. */
internal data class DiagnosticDeviceInfo private constructor(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val androidRelease: String,
) {
    fun text(): String = "deviceManufacturer=$manufacturer; deviceBrand=$brand; deviceModel=$model; androidRelease=$androidRelease"

    companion object {
        fun capture(): DiagnosticDeviceInfo = from(Build.MANUFACTURER, Build.BRAND, Build.MODEL, Build.VERSION.RELEASE)
        fun from(manufacturer: String?, brand: String?, model: String?, androidRelease: String?) =
            DiagnosticDeviceInfo(label(manufacturer), label(brand), label(model), label(androidRelease))

        // Bound output and prevent forged report lines/control sequences from unusual OEM labels.
        private fun label(value: String?): String = value.orEmpty().take(256)
            .filter { !it.isISOControl() && !it.isSurrogate() && Character.getType(it) != Character.FORMAT.toInt() }
            .trim().take(128).ifEmpty { "unknown" }
    }
}
