package dev.filmroom.diagnostics

/** Exact protocol allowlists. Never retain the native message, unknown strings or paths. */
enum class CodecErrorCode {
    BUSY, CANCELLED, DECODE_FAILED, DISK_BUDGET, ENCODE_FAILED, INVALID_COLOR_METADATA,
    INVALID_HANDLE, INVALID_INPUT, INVALID_PATH, IO_ERROR, MEMORY_BUDGET, NO_SPACE,
    OUTPUT_EXISTS, PIXEL_LIMIT, UNSUPPORTED_COLOR_SPACE, UNSUPPORTED_COMPRESSION,
    UNSUPPORTED_DNG_CALIBRATION, UNSUPPORTED_DNG_OPCODE, UNSUPPORTED_DNG_VARIANT,
    UNSUPPORTED_CAMERA_RAW_VARIANT, UNSUPPORTED_CAMERA, UNSUPPORTED_FORMAT, UNKNOWN;
    companion object { fun fromId(id: String): CodecErrorCode = entries.firstOrNull { it.name == id } ?: UNKNOWN }
}
enum class CodecStage(val id: String) {
    ADMISSION("admission"), COMPLETE("complete"), DECODE("decode"), DEVELOP("develop"),
    ENCODE("encode"), PROBE("probe"), SPOOL("spool"), VALIDATE("validate"), UNKNOWN("unknown");
    companion object { fun fromId(id: String): CodecStage = entries.firstOrNull { it.id == id } ?: UNKNOWN }
}
