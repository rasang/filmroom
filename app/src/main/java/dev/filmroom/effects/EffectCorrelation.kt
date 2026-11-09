package dev.filmroom.effects

/** Random, ephemeral navigation-flow token; never derived from a photo, URI or recipe. */
internal object EffectCorrelation {
    fun next(): Long = java.security.SecureRandom().nextLong().and(Long.MAX_VALUE).coerceAtLeast(1)
}
