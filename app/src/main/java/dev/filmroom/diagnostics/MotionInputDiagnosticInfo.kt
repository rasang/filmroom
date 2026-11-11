package dev.filmroom.diagnostics

/** Input-stage outcomes only. Never accepts URI, path, filename or exception text. */
enum class MotionInputStatus {
    SOURCE_MISSING, READ_FAILED, NON_JPEG, INPUT_TOO_LARGE, NOT_RECOGNIZED,
    PAIR_REQUIRED, EXTRACTION_FAILED, SUCCESS, CANCELLED, MEMORY_LIMIT
}
enum class PairLookupStatus { SAVED, NO_PERMISSION, IMAGE_METADATA_UNAVAILABLE, TOO_MANY_CANDIDATES, QUERY_FAILED, ACCESS_DENIED, NO_VISIBLE_SAME_NAME, CANDIDATES }
enum class PairMatchStatus { NONE, UNREADABLE, ACCESS_DENIED, INVALID_PAIR, AMBIGUOUS, MATCHED }
enum class PairPermission { NONE, PARTIAL, FULL }
data class MotionInputDiagnosticInfo(
    val status: MotionInputStatus,
    val copiedBytes: Long = 0,
    val videoBytes: Long? = null,
    val correlationId:Long?=null,
    val pairLookup: PairLookupStatus? = null,
    val pairMatch: PairMatchStatus? = null,
    val pairPermission: PairPermission? = null,
    val pairCandidates: Int = 0,
    val pairReadFailures: Int = 0,
    val pairRejected: Int = 0,
    val pairSavedFallback: Boolean = false,
) {
    internal fun fields(): Map<String, Any> = linkedMapOf<String, Any>("motionInputStatus" to status.name).apply {
        pairLookup?.let { put("pairLookup", it.name) }
        pairMatch?.let { put("pairMatch", it.name) }
        pairPermission?.let { put("pairPermission", it.name) }
        if (pairLookup != null) {
            put("pairCandidates", pairCandidates.coerceIn(0, 9))
            put("pairReadFailures", pairReadFailures.coerceIn(0, 9))
            put("pairRejected", pairRejected.coerceIn(0, 9))
            put("pairSavedFallback", pairSavedFallback)
        }
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
                fields["motionInputCorrelationId"]?.toLongOrNull()?.takeIf{it>0},
                PairLookupStatus.entries.firstOrNull { it.name == fields["pairLookup"] },
                PairMatchStatus.entries.firstOrNull { it.name == fields["pairMatch"] },
                PairPermission.entries.firstOrNull { it.name == fields["pairPermission"] },
                fields["pairCandidates"]?.toIntOrNull()?.coerceIn(0,9) ?: 0,
                fields["pairReadFailures"]?.toIntOrNull()?.coerceIn(0,9) ?: 0,
                fields["pairRejected"]?.toIntOrNull()?.coerceIn(0,9) ?: 0,
                fields["pairSavedFallback"] == "true")
        }
    }
}
