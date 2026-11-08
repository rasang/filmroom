package dev.filmroom.livephoto

/** Restricted input contract for the native, silent three-second effect exporter. */
internal object NormalizedEffectContract {
    fun validate(durationUs: Long, width: Int, height: Int, rotation: Int, hasAudio: Boolean, maxLongEdge: Int) {
        require(width > 0 && height > 0 && maxOf(width, height) <= maxLongEdge + 16) { "Unexpected video dimensions" }
        require(rotation == 0) { "Effect video must contain upright pixels" }
        require(!hasAudio) { "Effect video must be silent" }
        require(durationUs in 2_900_000..3_100_000) { "Effect video must be three seconds" }
    }
}
