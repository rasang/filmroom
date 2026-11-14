package dev.filmroom.diagnostics

import org.json.JSONObject

/** Fixed validation categories and source-code coordinates only, never photo metadata values.
 * Interpret coordinates against the codec source revision of the exact APK release.
 * The report's engineBuild is a separate component and cannot identify these coordinates. */
class CodecDiagnosticDetail private constructor(
    private val reason: Reason, private val source: Source,
    private val line: Long, private val column: Long, private val tag: Long?,
) {
    internal fun fields(): Map<String, Any> = buildMap {
        put("codecDiagnosticSchema", 1L)
        put("codecReason", reason.name); put("codecSource", source.name)
        put("codecLine", line); put("codecColumn", column)
        tag?.let { put("codecTag", it) }
    }
    companion object {
        private val tags = setOf(256L,257L,258L,259L,262L,266L,274L,277L,278L,284L,317L,322L,323L,339L,
            33421L,33422L,50710L,50711L,50713L,50719L,50720L,50829L,50714L,50717L,50721L,50722L,50723L,50724L,50727L,50728L,
            50730L,50778L,50779L,50931L,50932L,50937L,50938L,50939L,50940L,50964L,50965L,50715L,50716L,50718L,50729L,50941L,50942L,50966L,50967L,50968L,50969L,50970L,50971L,50972L,50973L,51089L,51110L,51111L,330L)
        fun fromNative(json: JSONObject?): CodecDiagnosticDetail? = if (number(json?.opt("diagnosticSchema")) != 1L) null else parse(
            json?.opt("reason") as? String, json?.opt("source") as? String,
            number(json?.opt("line")), number(json?.opt("column")), number(json?.opt("tag")))
        internal fun fromFields(fields: Map<String, String>): CodecDiagnosticDetail? = if (integer(fields["codecDiagnosticSchema"]) != 1L) null else parse(
            fields["codecReason"], fields["codecSource"], integer(fields["codecLine"]),
            integer(fields["codecColumn"]), integer(fields["codecTag"]))
        private fun number(value: Any?): Long? = when(value) { is Int -> value.toLong(); is Long -> value; else -> null }
        private fun integer(value: String?): Long? = value?.takeIf {
            it.length in 1..6 && it.all { c -> c in '0'..'9' }
        }?.toLongOrNull()
        private fun parse(reason: String?, source: String?, line: Long?, column: Long?, tag: Long?): CodecDiagnosticDetail? {
            val safeReason = Reason.entries.firstOrNull { it.name == reason } ?: return null
            val safeSource = Source.entries.firstOrNull { it.name == source } ?: return null
            if (line == null || line !in 1..100_000 || column == null || column !in 1..100_000) return null
            return CodecDiagnosticDetail(safeReason, safeSource, line, column, tag?.takeIf { it in tags })
        }
    }
    private enum class Reason {
        VALIDATION, MISSING_TAG, TAG_SHAPE, TAG_VALUE, BLACK_WHITE_LEVEL, WHITE_BALANCE_RANGE,
        D65_MATRIX_MISSING, MATRIX_SHAPE, MATRIX_RANGE, MATRIX_SINGULAR, COLOR_TRANSFORM,
        PROFILE_DIMENSIONS, PROFILE_CURVE, PROFILE_ENDPOINT, UNSUPPORTED_VALUE, RESOURCE_LIMIT,
    }
    private enum class Source {
        DNG, DNG_BLACK, TIFF, DNG_CFA_COLOR, DNG_COLOR_SPEC, PROFILE_DEVELOP, PROFILE_GAIN_TABLE,
        COMPAT_CALIBRATION, COMPAT_PROFILES, COMPAT_LENS, COMPAT, RAW, CAMERA_RAW,
        DEVELOP, DECODE, EXPORT, JNI, OTHER,
        CAMERA_RAW_ADDITIONAL_CR3_BOXES, CAMERA_RAW_ADDITIONAL, CAMERA_RAW_SONY_TILES,
        CAMERA_RAW_CANON_STREAM, CAMERA_RAW_INTEGER_SPOOL, CAMERA_RAW_SONY_BAYER,
        CAMERA_RAW_SONY_ARW6, CAMERA_RAW_FUJI_XTRANS, CAMERA_RAW_FUJI, CAMERA_RAW_PANASONIC,
        LOSSLESS, LINEAR_JPEG, JXL, NIKON_HE, NIKON_SNEF, DNG_CFA, DNG_BAD_PIXELS,
        DNG_GEOMETRY_DEVELOP, EMBEDDED_LENS, OUTPUT_COLOR, DNG_WARP, CAMERA_GEOMETRY,
        LIB, OPCODES, GAINMAP, OPCODE_STAGE1, CFA_GAINMAP, TECHNICAL,
    }
    override fun equals(other: Any?): Boolean = other is CodecDiagnosticDetail && fields() == other.fields()
    override fun hashCode(): Int = fields().hashCode()
}
