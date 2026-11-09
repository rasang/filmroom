package dev.filmroom.diagnostics

// Reconstructed from beta27 DEX; public field order and validation bounds retained.
data class MotionPreviewDecoderSnapshot(
    val codedWidth: Int = 0,
    val codedHeight: Int = 0,
    val hardware: Boolean? = null,
    val decoderCandidates: Int = 0,
    val hardwareCandidates: Int = 0,
    val capabilityMatches: Int = 0,
    val attemptedCandidates: Int = 0,
    val failedCandidates: Int = 0,
    val selectionReason: MotionPreviewDecoderSelection = MotionPreviewDecoderSelection.NOT_STARTED,
    val failureReason: MotionPreviewFallback = MotionPreviewFallback.NONE,
    val decoded: Long = 0,
    val discarded: Long = 0,
    val queued: Long = 0,
    val consumed: Long = 0,
    val sourceIntervalCount: Long = 0,
    val sourceIntervalTotalUs: Long = 0,
    val sourceIntervalMaxUs: Long = 0,
    val workerCount: Long = 0,
    val workerTotalUs: Long = 0,
    val workerMaxUs: Long = 0,
    val surfaceWaitCount: Long = 0,
    val surfaceWaitMaxUs: Long = 0,
    val sourceFrameRateMilli: Int = 0,
    val sourceProfile: Int? = null,
    val sourceLevel: Int? = null,
    val sourceBitDepth: Int? = null,
    val capabilityRateChecked: Boolean = false,
    val selectedRateSupported: Boolean? = null
) {
    internal fun fields(): Map<String, Any> = linkedMapOf<String, Any>().apply {
        fun n(k:String,v:Long?,min:Long,max:Long) { if(v != null && v in min..max) put("motionDecoder"+k,v) }
        n("CodedWidth", codedWidth.toLong(), 1, 30000)
        n("CodedHeight", codedHeight.toLong(), 1, 30000)
        n("Candidates", decoderCandidates.toLong(), 0, 10000)
        n("HardwareCandidates", hardwareCandidates.toLong(), 0, 10000)
        n("CapabilityMatches", capabilityMatches.toLong(), 0, 10000)
        n("AttemptedCandidates", attemptedCandidates.toLong(), 0, 10000)
        n("FailedCandidates", failedCandidates.toLong(), 0, 10000)
        n("Decoded", decoded, 0, 1000000)
        n("Discarded", discarded, 0, 1000000)
        n("Queued", queued, 0, 1000000)
        n("Consumed", consumed, 0, 1000000)
        n("SourceIntervalCount", sourceIntervalCount, 0, 1000000)
        n("SourceIntervalTotalUs", sourceIntervalTotalUs, 0, 60000000)
        n("SourceIntervalMaxUs", sourceIntervalMaxUs, 0, 60000000)
        n("WorkerCount", workerCount, 0, 1000000)
        n("WorkerTotalUs", workerTotalUs, 0, 60000000)
        n("WorkerMaxUs", workerMaxUs, 0, 60000000)
        n("SurfaceWaitCount", surfaceWaitCount, 0, 1000000)
        n("SurfaceWaitMaxUs", surfaceWaitMaxUs, 0, 60000000)
        n("SourceFrameRateMilli", sourceFrameRateMilli.toLong(), 1, 1000000)
        n("SourceProfile", sourceProfile?.toLong(), 0, 2147483647)
        n("SourceLevel", sourceLevel?.toLong(), 0, 2147483647)
        n("SourceBitDepth", sourceBitDepth?.toLong(), 1, 64)
        put("motionDecoderSelection",selectionReason.name); put("motionDecoderFailure",failureReason.name)
        put("motionDecoderHardware",when(hardware){true->"HARDWARE";false->"SOFTWARE";null->"UNKNOWN"})
        put("motionDecoderCapabilityRateChecked",capabilityRateChecked)
        selectedRateSupported?.let { put("motionDecoderSelectedRateSupported",it) }
        if(sourceIntervalCount in 1L..1000000L && sourceIntervalTotalUs in 0L..60000000L) n("SourceIntervalMeanUs", sourceIntervalTotalUs/sourceIntervalCount, 0, 60000000)
        if(workerCount in 1L..1000000L && workerTotalUs in 0L..60000000L) n("WorkerMeanUs", workerTotalUs/workerCount, 0, 60000000)
    }
    companion object {
        internal fun parse(fields:Map<String,String>): MotionPreviewDecoderSnapshot? {
            fun n(k:String,min:Long,max:Long):Long? = fields["motionDecoder"+k]?.toLongOrNull()?.takeIf { it in min..max }
            val selectionReason=MotionPreviewDecoderSelection.entries.firstOrNull { it.name==fields["motionDecoderSelection"] } ?: return null
            return MotionPreviewDecoderSnapshot(
                codedWidth = n("CodedWidth", 1, 30000)?.toInt() ?: 0,
                codedHeight = n("CodedHeight", 1, 30000)?.toInt() ?: 0,
                hardware = when(fields["motionDecoderHardware"]){"HARDWARE"->true;"SOFTWARE"->false;else->null},
                decoderCandidates = n("Candidates", 0, 10000)?.toInt() ?: 0,
                hardwareCandidates = n("HardwareCandidates", 0, 10000)?.toInt() ?: 0,
                capabilityMatches = n("CapabilityMatches", 0, 10000)?.toInt() ?: 0,
                attemptedCandidates = n("AttemptedCandidates", 0, 10000)?.toInt() ?: 0,
                failedCandidates = n("FailedCandidates", 0, 10000)?.toInt() ?: 0,
                selectionReason = selectionReason,
                failureReason = MotionPreviewFallback.entries.firstOrNull { it.name==fields["motionDecoderFailure"] } ?: MotionPreviewFallback.UNKNOWN,
                decoded = n("Decoded", 0, 1000000) ?: 0,
                discarded = n("Discarded", 0, 1000000) ?: 0,
                queued = n("Queued", 0, 1000000) ?: 0,
                consumed = n("Consumed", 0, 1000000) ?: 0,
                sourceIntervalCount = n("SourceIntervalCount", 0, 1000000) ?: 0,
                sourceIntervalTotalUs = n("SourceIntervalTotalUs", 0, 60000000) ?: 0,
                sourceIntervalMaxUs = n("SourceIntervalMaxUs", 0, 60000000) ?: 0,
                workerCount = n("WorkerCount", 0, 1000000) ?: 0,
                workerTotalUs = n("WorkerTotalUs", 0, 60000000) ?: 0,
                workerMaxUs = n("WorkerMaxUs", 0, 60000000) ?: 0,
                surfaceWaitCount = n("SurfaceWaitCount", 0, 1000000) ?: 0,
                surfaceWaitMaxUs = n("SurfaceWaitMaxUs", 0, 60000000) ?: 0,
                sourceFrameRateMilli = n("SourceFrameRateMilli", 1, 1000000)?.toInt() ?: 0,
                sourceProfile = n("SourceProfile", 0, 2147483647)?.toInt(),
                sourceLevel = n("SourceLevel", 0, 2147483647)?.toInt(),
                sourceBitDepth = n("SourceBitDepth", 1, 64)?.toInt(),
                capabilityRateChecked = fields["motionDecoderCapabilityRateChecked"]?.toBooleanStrictOrNull() ?: false,
                selectedRateSupported = fields["motionDecoderSelectedRateSupported"]?.toBooleanStrictOrNull()
            )
        }
    }
}
