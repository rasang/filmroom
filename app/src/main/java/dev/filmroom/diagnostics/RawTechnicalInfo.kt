package dev.filmroom.diagnostics

import org.json.JSONObject

/** Coarse numeric file-layout facts only; no TIFF strings, EXIF, location, paths or pixel data. */
class RawTechnicalInfo private constructor(private val values: Map<RawMetric, Long>) {
    internal fun fields(): Map<String, Long> = values.mapKeys { it.key.key }

    companion object {
        /** JSON numbers must be integers in the protocol, not coercible strings, booleans or floats. */
        fun fromNative(json: JSONObject?): RawTechnicalInfo? = collect { metric ->
            when (val value = json?.opt(metric.key)) {
                is Int -> value.toLong()
                is Long -> value
                else -> null
            }
        }

        /** Revalidate persisted records too; unrelated keys and malformed numeric text are omitted. */
        internal fun fromFields(fields: Map<String, String>): RawTechnicalInfo? = collect { metric ->
            fields[metric.key]?.takeIf { it.length <= 10 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
        }

        private inline fun collect(read: (RawMetric) -> Long?): RawTechnicalInfo? {
            val safe = RawMetric.entries.mapNotNull { metric ->
                read(metric)?.takeIf { it in 0..metric.max }?.let { metric to it }
            }.toMap()
            return safe.takeIf { it.isNotEmpty() }?.let(::RawTechnicalInfo)
        }
    }

    override fun equals(other: Any?): Boolean = other is RawTechnicalInfo && values == other.values
    override fun hashCode(): Int = values.hashCode()
}

/** Explicit numeric-only schema shared with the bounded native metadata probe. */
private enum class RawMetric(val key: String, val max: Long = 0xffffffffL) {
    FILE_BYTES("rawFileBytes", 512L * 1024 * 1024), WIDTH("rawWidth"), HEIGHT("rawHeight"),
    COMPRESSION("rawCompression"), BITS_MIN("rawBitsMin"), BITS_MAX("rawBitsMax"),
    SAMPLES_PER_PIXEL("rawSamplesPerPixel"), PHOTOMETRIC("rawPhotometric"),
    CFA_ROWS("rawCfaRows"), CFA_COLS("rawCfaCols"), TILE_WIDTH("rawTileWidth"), TILE_HEIGHT("rawTileHeight"),
    ROWS_PER_STRIP("rawRowsPerStrip"), MAX_BLOCK_BYTES("rawMaxBlockBytes"), BLOCK_COUNT("rawBlockCount"),
    DNG_VERSION("rawDngVersion"), PROBE_STATUS("rawProbeStatus", 2),
    OPCODE_BYTES("rawOpcodeBytes", 3L * 512 * 1024 * 1024), OPCODE_COUNT("rawOpcodeCount", 192),
    REQUIRED_OPCODE_ID("rawRequiredOpcodeId"), REQUIRED_OPCODE_VERSION("rawRequiredOpcodeVersion"),
    REQUIRED_OPCODE_FLAGS("rawRequiredOpcodeFlags"), CALIBRATION_MASK("rawCalibrationMask", 3),
    IFD_ENTRIES("rawIfdEntries", 256),
}
