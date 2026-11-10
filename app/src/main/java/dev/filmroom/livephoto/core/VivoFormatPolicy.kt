package dev.filmroom.livephoto.core

/** A format hint, NOT a gallery compatibility result. No private API or permission is required here. */
data class VivoVersionObservation(val value: Int?, val state: State) {
    enum class State { VALUE, MISSING, INVALID, UNREADABLE }
    // SDK 2.4.2 defaults an absent/unreadable property to zero, then uses V1 when < 1.
    val effectiveVersion: Int get() = value ?: 0
    val preferredProfile: LivePhotoProfile get() = if (effectiveVersion >= 1)
        LivePhotoProfile.VIVO_SINGLE else LivePhotoProfile.VIVO_PAIR_2014
    val diagnostic: String get() = "ro.vivo.camera.livephoto.version: $state" +
        (value?.let { "=$it" } ?: "; default=0 (not an observed system value)")

    companion object {
        const val PROPERTY = "ro.vivo.camera.livephoto.version"
        fun read(reader: (String) -> String?): VivoVersionObservation = try {
            val raw = reader(PROPERTY)
            if (raw.isNullOrEmpty()) VivoVersionObservation(null, State.MISSING)
            else raw.toIntOrNull()?.let { VivoVersionObservation(it, State.VALUE) }
                ?: VivoVersionObservation(null, State.INVALID)
        } catch (_: Exception) { VivoVersionObservation(null, State.UNREADABLE) }
          catch (_: LinkageError) { VivoVersionObservation(null, State.UNREADABLE) }
    }
}
