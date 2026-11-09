package dev.filmroom.diagnostics

/** Input-stage outcomes only. Never accepts URI, path, filename or exception text. */
enum class MotionInputStatus {
    SOURCE_MISSING, READ_FAILED, NON_JPEG, INPUT_TOO_LARGE, NOT_RECOGNIZED,
    EXTRACTION_FAILED, SUCCESS, CANCELLED, MEMORY_LIMIT
}
data class MotionInputDiagnosticInfo(
    val status: MotionInputStatus,
    val copiedBytes: Long = 0,
    val videoBytes: Long? = null,
    val correlationId:Long?=null,
) {
    internal fun fields(): Map<String, Any> = linkedMapOf<String, Any>("motionInputStatus" to status.name).apply {
        if(correlationId!=null && correlationId>0)put("motionInputCorrelationId",correlationId)
        if (copiedBytes in 0..134217728L) put("motionInputCopiedBytes", copiedBytes)
        if (videoBytes != null && videoBytes in 16..134217728L) put("motionInputVideoBytes", videoBytes)
    }
    companion object {
        internal fun parse(fields: Map<String, String>): MotionInputDiagnosticInfo? {
            val status = MotionInputStatus.entries.firstOrNull { it.name == fields["motionInputStatus"] } ?: return null
            return MotionInputDiagnosticInfo(status,
                fields["motionInputCopiedBytes"]?.toLongOrNull()?.takeIf { it in 0..134217728L } ?: 0,
                fields["motionInputVideoBytes"]?.toLongOrNull()?.takeIf { it in 16..134217728L },
                fields["motionInputCorrelationId"]?.toLongOrNull()?.takeIf{it>0})
        }
    }
}
