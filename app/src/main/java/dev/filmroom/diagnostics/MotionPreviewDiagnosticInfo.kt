package dev.filmroom.diagnostics

// Reconstructed from beta27 DEX; public field order and validation bounds retained.
data class MotionPreviewDiagnosticInfo(
    val path: MotionPreviewPath,
    val fallback: MotionPreviewFallback = MotionPreviewFallback.NONE,
    val hardware: MotionPreviewHardware = MotionPreviewHardware.UNKNOWN,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val submittedFrames: Long = 0,
    val prepareMs: Long? = null,
    val activeMs: Long? = null,
    val sampleSpanMs: Long? = null,
    val ptsLagMeanUs: Long? = null,
    val ptsLagMaxUs: Long? = null,
    val glSubmitCpuMeanUs: Long? = null,
    val glSubmitCpuMaxUs: Long? = null,
    val suppressedReports: Long = 0,
    val decoder: MotionPreviewDecoderSnapshot? = null,
    val correlationId:Long?=null
) {
    internal fun fields(): Map<String, Any> = linkedMapOf<String, Any>().apply {
        fun n(k:String,v:Long?,min:Long,max:Long) { if(v != null && v in min..max) put("motionPreview"+k,v) }
        n("CorrelationId",correlationId,1,Long.MAX_VALUE)
        n("SourceWidth", sourceWidth?.toLong(), 1, 30000)
        n("SourceHeight", sourceHeight?.toLong(), 1, 30000)
        n("SubmittedFrames", submittedFrames, 0, 10000)
        n("PrepareMs", prepareMs, 0, 60000)
        n("ActiveMs", activeMs, 0, 60000)
        n("SampleSpanMs", sampleSpanMs, 0, 60000)
        n("PtsLagMeanUs", ptsLagMeanUs, 0, 60000000)
        n("PtsLagMaxUs", ptsLagMaxUs, 0, 60000000)
        n("GlSubmitCpuMeanUs", glSubmitCpuMeanUs, 0, 60000000)
        n("GlSubmitCpuMaxUs", glSubmitCpuMaxUs, 0, 60000000)
        n("SuppressedReports", suppressedReports, 0, 1000000)
        put("motionPreviewPath",path.name); put("motionPreviewFallback",fallback.name); put("motionPreviewHardware",hardware.name)
        decoder?.let { putAll(it.fields()) }
    }
    companion object {
        internal fun parse(fields:Map<String,String>): MotionPreviewDiagnosticInfo? {
            fun n(k:String,min:Long,max:Long):Long? = fields["motionPreview"+k]?.toLongOrNull()?.takeIf { it in min..max }
            val path=MotionPreviewPath.entries.firstOrNull { it.name==fields["motionPreviewPath"] } ?: return null
            return MotionPreviewDiagnosticInfo(
                path = path,
                fallback = MotionPreviewFallback.entries.firstOrNull { it.name==fields["motionPreviewFallback"] } ?: MotionPreviewFallback.UNKNOWN,
                hardware = MotionPreviewHardware.entries.firstOrNull { it.name==fields["motionPreviewHardware"] } ?: MotionPreviewHardware.UNKNOWN,
                sourceWidth = n("SourceWidth", 1, 30000)?.toInt(),
                sourceHeight = n("SourceHeight", 1, 30000)?.toInt(),
                submittedFrames = n("SubmittedFrames", 0, 10000) ?: 0,
                prepareMs = n("PrepareMs", 0, 60000),
                activeMs = n("ActiveMs", 0, 60000),
                sampleSpanMs = n("SampleSpanMs", 0, 60000),
                ptsLagMeanUs = n("PtsLagMeanUs", 0, 60000000),
                ptsLagMaxUs = n("PtsLagMaxUs", 0, 60000000),
                glSubmitCpuMeanUs = n("GlSubmitCpuMeanUs", 0, 60000000),
                glSubmitCpuMaxUs = n("GlSubmitCpuMaxUs", 0, 60000000),
                suppressedReports = n("SuppressedReports", 0, 1000000) ?: 0,
                decoder = MotionPreviewDecoderSnapshot.parse(fields),
                correlationId=n("CorrelationId",1,Long.MAX_VALUE)
            )
        }
    }
}
