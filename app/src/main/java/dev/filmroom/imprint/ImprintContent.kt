package dev.filmroom.imprint

import dev.filmroom.io.PhotoMetadata
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

enum class ImprintFieldKind { ISO, METERING, SHUTTER, APERTURE, EXPOSURE_BIAS, FOCAL_LENGTH }
enum class ImprintMeteringMode { AVERAGE, CENTER_WEIGHTED, SPOT, MULTI_SPOT, PATTERN, PARTIAL }

/** [text] is bounded display text, not a substitute for the original capture metadata. */
data class ImprintField(
    val kind: ImprintFieldKind,
    val text: String,
    val meteringMode: ImprintMeteringMode? = null,
)

/** Packed source exposure facts plus source date or explicit presentation-only date override. */
data class ImprintContent(val date: String?, val fields: List<ImprintField>) {
    companion object {
        private val captureDateFormat = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
        private val decimalSyntax = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        private const val UINT_MAX = 4294967295L

        fun from(metadata: PhotoMetadata, settings: ImprintSettings): ImprintContent =
            ImprintContent(displayDateTime(metadata, settings)?.let { formatDate(it.toLocalDate(), settings.effectiveDateStyle) }, exposure(metadata.imprintAttributes()))

        /** Original local wall time only; never edit/export time or a timezone conversion. */
        fun originalCaptureDateTime(metadata: PhotoMetadata): LocalDateTime? =
            originalCaptureDateTime(metadata.imprintAttributes())

        /** Editable date source. A null result means neither capture nor fallback date is available. */
        fun displayDateTime(metadata: PhotoMetadata, settings: ImprintSettings): LocalDateTime? =
            settings.manualDateTime?.let(ImprintSettings::parseDateTime) ?: metadata.imprintDateTime()

        private fun originalCaptureDateTime(attributes: Map<String, String>): LocalDateTime? =
            attributes["DateTimeOriginal"]?.let { raw ->
                runCatching {
                    require(raw.matches(Regex("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")))
                    LocalDateTime.parse(raw, captureDateFormat).also { require(it.year in 1..9999) }
                }.getOrNull()
            }

        /** Pure source formatter retained for focused metadata tests. */
        internal fun fromAttributes(attributes: Map<String, String>, dateStyle: String): ImprintContent =
            fromAttributes(attributes, ImprintSettings(dateStyle = dateStyle))

        internal fun fromAttributes(attributes: Map<String, String>, settings: ImprintSettings): ImprintContent {
            val date = settings.manualDateTime?.let(ImprintSettings::parseDateTime)
                ?: originalCaptureDateTime(attributes)
                ?: attributes["DateTime"]?.let { originalCaptureDateTime(mapOf("DateTimeOriginal" to it)) }
                ?: attributes["DateTimeDigitized"]?.let { originalCaptureDateTime(mapOf("DateTimeOriginal" to it)) }
            return ImprintContent(date?.let { formatDate(it.toLocalDate(), settings.effectiveDateStyle) }, exposure(attributes))
        }

        internal fun formatDate(date: LocalDate, style: String): String? {
            val yy = String.format(Locale.ROOT, "%02d", date.year % 100)
            return when (style) {
                "contax_t2" -> "'$yy ${String.format(Locale.ROOT, "%02d %02d", date.monthValue, date.dayOfMonth)}"
                "yashica_t4" -> "$yy ${String.format(Locale.ROOT, "%02d.%02d", date.monthValue, date.dayOfMonth)}"
                "canon_data_back" -> "${date.dayOfMonth} ${date.monthValue} $yy"
                "canon_luna" -> "${yy}年${date.monthValue}月${date.dayOfMonth}日"
                else -> null
            }
        }

        /** Reject non-finite numbers, zero/negative denominators and malformed rational syntax. */
        private fun number(raw: String?, signed: Boolean = false): Double? = raw?.let { value ->
            runCatching {
                require(value.length in 1..128)
                val parts = value.split('/')
                require(parts.size in 1..2 && parts.all { decimalSyntax.matches(it) })
                val n = parts[0].toDouble()
                val d = if (parts.size == 2) parts[1].toDouble() else 1.0
                require(n.isFinite() && d.isFinite() && d > 0.0 && (signed || n >= 0.0))
                (n / d).also { require(it.isFinite() && (n == 0.0 || it != 0.0)) }
            }.getOrNull()
        }

        private fun unsigned(raw: String?, max: Long = UINT_MAX): Long? = raw
            ?.takeIf { it.length in 1..10 && it.all { c -> c in '0'..'9' } }
            ?.toLongOrNull()?.takeIf { it in 1..max }

        /** SHORT 65535 is an overflow sentinel, never ISO 65535 by itself. */
        private fun iso(f: Map<String, String>): Long? {
            unsigned(f["PhotographicSensitivity"], 65534)?.let { return it }
            // Extended values have meaning only if SensitivityType declares their presence.
            val type = unsigned(f["SensitivityType"], 7)?.toInt() ?: return null
            if (type in setOf(3, 5, 6, 7)) unsigned(f["ISOSpeed"])?.let { return it }
            if (type in setOf(2, 4, 6, 7)) unsigned(f["RecommendedExposureIndex"])?.let { return it }
            if (type in setOf(1, 4, 5, 7)) unsigned(f["StandardOutputSensitivity"])?.let { return it }
            return null
        }

        /** Integral source values stay exact. Fractions use four significant digits and bounded notation. */
        private fun compact(value: Double): String {
            if (value == 0.0) return "0"
            if (value == kotlin.math.floor(value) && abs(value) <= UINT_MAX) return value.toLong().toString()
            val decimal = BigDecimal.valueOf(value).round(MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros()
            val plain = decimal.toPlainString()
            return if (plain.length <= 10) plain else decimal.toString().replace("E+", "e").replace("E", "e")
        }

        /** Display-only MZ-S-style precision; modern third stops are rounded, not quantized to half stops. */
        private fun exposureBias(value: Double): String {
            val rounded = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP)
            return when (rounded.signum()) {
                0 -> "±0.0"
                1 -> "+" + rounded.toPlainString()
                else -> rounded.toPlainString()
            }
        }

        private fun shutter(seconds: Double): String {
            if (seconds < 1.0) {
                val inverse = 1.0 / seconds
                val integer = round(inverse)
                // Preserve reciprocal notation when supported by the source precision, not a preferred camera series.
                if (inverse.isFinite() && integer in 1.0..UINT_MAX.toDouble() && abs(inverse - integer) / inverse <= 0.00001)
                    return integer.toLong().toString()
            }
            return compact(seconds) + "s"
        }

        internal fun exposure(f: Map<String, String>): List<ImprintField> = buildList {
            iso(f)?.let { add(ImprintField(ImprintFieldKind.ISO, "ISO$it")) }
            // Metering icons are intentionally not printed: they are ambiguous at film-edge size.
            // A valid independently recorded APEX tag may recover a missing or unusable primary tag.
            val seconds = number(f["ExposureTime"])?.takeIf { it > 0.0 && it <= UINT_MAX }
                ?: number(f["ShutterSpeedValue"], signed = true)?.takeIf { it in -32.0..32.0 }
                    ?.let { 2.0.pow(-it) }?.takeIf { it.isFinite() && it > 0.0 && it <= UINT_MAX }
            seconds?.let { add(ImprintField(ImprintFieldKind.SHUTTER, shutter(it))) }
            val aperture = number(f["FNumber"])?.takeIf { it > 0.0 && it <= UINT_MAX }
                ?: number(f["ApertureValue"])?.takeIf { it in 0.0..63.0 }
                    ?.let { 2.0.pow(it / 2.0) }
            aperture?.let { add(ImprintField(ImprintFieldKind.APERTURE, "F" + compact(it))) }
            number(f["ExposureBiasValue"], signed = true)?.takeIf { it in -99.0..99.0 }?.let {
                add(ImprintField(ImprintFieldKind.EXPOSURE_BIAS, exposureBias(it)))
            }
            // Prefer recorded 35mm-equivalent focal length, never infer a sensor crop factor.
            // EXIF defines the equivalent tag as SHORT; zero means unknown.
            val focal = number(f["FocalLengthIn35mmFilm"])?.takeIf { it >= 0.5 && it <= 65535.0 }
                ?: number(f["FocalLength"])?.takeIf { it > 0.0 && it <= UINT_MAX }
            focal?.let {
                val rounded = BigDecimal.valueOf(it).setScale(0, RoundingMode.HALF_UP)
                // Do not invent a zero-millimetre lens when a positive sub-mm value rounds to zero.
                if (rounded.signum() > 0) add(ImprintField(ImprintFieldKind.FOCAL_LENGTH, rounded.toPlainString() + "mm"))
            }
        }
    }
}
