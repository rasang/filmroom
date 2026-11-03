package dev.filmroom.engine

/** Numbers returned by the native planning API; no paths, images or free-form text. */
data class NativeExportPlan(
    val width:Int, val height:Int,
    val inputBytes:Long, val outputBytes:Long,
    val scratchBytes:Long, val totalLocalBytes:Long, val workingBytes:Long=0,
    val checkpointReserveBytes:Long=width.toLong()*height*48L+1024*1024,
) {
    val pixels:Long get()=width.toLong()*height
    init {
        require(width in 1..30_000 && height in 1..30_000 && pixels<=150_000_000L)
        require(inputBytes==pixels*12L && outputBytes==pixels*12L)
        require(checkpointReserveBytes==pixels*48L+1024*1024)
        require(scratchBytes in 0..(1L shl 40) && workingBytes in 0..(1L shl 40))
        require(totalLocalBytes==Math.addExact(Math.addExact(inputBytes,outputBytes),scratchBytes))
    }
}

/** Existing source copies already reduce availableBytes: never charge them twice. */
object ExportStoragePolicy {
    private const val APP_RESERVE=64L*1024*1024
    fun beforeInput(plan:NativeExportPlan):Long = Math.addExact(plan.totalLocalBytes, Math.addExact(plan.pixels*3L,APP_RESERVE))
    fun afterInput(plan:NativeExportPlan):Long = beforeInput(plan)-plan.inputBytes
    fun hasSpace(availableBytes:Long,requiredBytes:Long):Boolean = availableBytes>=0 && requiredBytes>=0 && availableBytes>=requiredBytes
    fun gibibytes(bytes:Long):String = String.format(java.util.Locale.ROOT,"%.2f",bytes.coerceAtLeast(0)/(1024.0*1024*1024))
}

/** Per-pass progress only: never presented as a percentage of the whole export. */
data class EngineProgress(val jobId:Long,val passId:Long,val phase:dev.filmroom.diagnostics.NativePhase,val done:Long,val total:Long,
    val display:ExportDisplayProgress=ExportDisplayProgress.forNativePhase(phase.id),
    val scanPreview:java.io.File?=null, val updateSerial:Long=0) {
    init {require(jobId>0 && passId>0 && done in 0..1_800_000_000L && total in 0..1_800_000_000L && done<=total)}
    val chinese:String get() = phase.chinese + if(total>0)dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_30fe814c4574, done*100/total) else ""
}

/** Trusted Kotlin-owned output identity; native JSON never chooses a preview path. */
internal fun scanPreviewForOutput(output:java.io.File,workspace:java.io.File?):java.io.File? {
    if(workspace==null || output.canonicalFile.parentFile!=workspace.canonicalFile)return null
    val match=Regex("film-result-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.rgb").matchEntire(output.name)?:return null
    return java.io.File(workspace,"scan-source-${match.groupValues[1]}.png")
}
