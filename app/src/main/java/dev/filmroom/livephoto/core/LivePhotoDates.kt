package dev.filmroom.livephoto.core

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Capture wall time remains distinct from the export modification time. */
data class LivePhotoDates(
    val original: String? = null,
    val originalOffset: String? = null,
    val originalSubsec: String? = null,
    val modified: String,
    val modifiedOffset: String = "+00:00",
    val modifiedSubsec: String? = null
) {
    init {
        fun date(value: String) {
            require(value.matches(Regex("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")))
            require(LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)).year in 1..9999)
        }
        fun offset(value: String) { require(value.matches(Regex("[+-][0-9]{2}:[0-9]{2}"))); ZoneOffset.of(value) }
        fun subsec(value: String) { require(value.matches(Regex("[0-9]{1,12}"))) }
        date(modified); offset(modifiedOffset); modifiedSubsec?.let(::subsec)
        original?.let(::date); originalOffset?.let(::offset); originalSubsec?.let(::subsec)
        require(original != null || (originalOffset == null && originalSubsec == null))
    }
}
