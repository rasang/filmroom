package dev.filmroom.diagnostics

import org.json.JSONObject

/** Fixed stage names and adopted backends only, never helper-provided free text. */
enum class ScienceStage(val objectKey: String, val fieldKey: String, val gpuBackend: String, val label: String) {
    AUTO_EXPOSURE("autoExposure", "gpuScienceAe", "gpu_meter_f32_v1", "自动测光 / Auto exposure"),
    HIGHLIGHT("highlightBoost", "gpuScienceHi", "gpu_highlight_f32_v1", "高光增强 / Highlight boost"),
    EXPOSURE_LOG("exposureLog", "gpuScienceLog", "gpu_log_f32_v1", "曝光对数 / Exposure log"),
    GRAIN("grain", "gpuScienceGrain", "gpu_grain_guarded64_spatial_f32_v2", "颗粒 / Grain"),
    PRINT_EXPOSURE("printExposure", "gpuSciencePrint", "gpu_print_exposure_f32_v1", "相纸曝光 / Print exposure"),
    PRINT_DIFFUSION("printDiffusion", "gpuScienceFft", "gpu_print_fft_f32_v1", "相纸柔光 / Print diffusion"),
    SCAN_TAIL("scanTail", "gpuScienceTail", "gpu_scan_tail_f32_v1", "扫描收尾 / Scanner tail"),
    FINISH_TAIL("finishTail", "gpuScienceFinish", "gpu_finish_tail_resident_f32_v1", "驻留打印扫描收尾 / Resident print scanner tail")
}
enum class ScienceNumber(val nativeKey: String, val max: Double, val integer: Boolean = false, val min: Double = 0.0) {
    HELPER_MS("helperTotalMs",604800000.0), STAGE_MS("gpuStageMs",604800000.0), SETUP_MS("setupMs",604800000.0),
    PEAK_BYTES("combinedPeakBytes",805306368.0,true), UPLOAD("uploadBytes",68719476736.0,true), READBACK("readbackBytes",68719476736.0,true),
    DISPATCHES("dispatches",10000000.0,true), CHUNKS("chunks",150000000.0,true), PASSES("passes",30.0,true),
    SAMPLES("sampleCount",450000000.0,true), MAX_ABS("sampleMaxAbs",1e300), RMS("sampleRms",1e300), EV("autoExposureEv",1024.0,false,-1024.0);
    fun accepts(value: Double) = value.isFinite() && value in min..max && (!integer || value % 1.0 == 0.0)
}
/** Compact, versioned, typed wire record keeps thirteen stage diagnostics below the unchanged 8 KiB event limit. */
data class SciencePerformanceInfo(
    val gpu: Boolean?, val fallback: FullGpuFallback?, val requested: Boolean?, val attempted: Boolean?, val resumed: Boolean?,
    val prepare: GpuPrepareCode?, val numbers: Map<ScienceNumber, Double> = emptyMap(),
) {
    fun backend(stage: ScienceStage): String = when (gpu) { true -> stage.gpuBackend; false -> "cpu_f64"; null -> "unknown" }
    internal fun encode(stage: ScienceStage): String = (listOf("2", when(gpu){true->"g";false->"c";null->"-"}, fallback?.id ?: "-",
        requested?.let { if (it) "1" else "0" } ?: "-", attempted?.let { if (it) "1" else "0" } ?: "-", resumed?.let { if (it) "1" else "0" } ?: "-", prepare?.id ?: "-") +
        ScienceNumber.entries.map { key -> numbers[key]?.takeIf { key.accepts(it) && (key != ScienceNumber.EV || stage == ScienceStage.AUTO_EXPOSURE) }?.let{if(it % 1.0==0.0 && kotlin.math.abs(it)<=9e15)it.toLong().toString() else it.toString()} ?: "-" }).joinToString("|")
    companion object {
        fun fromNative(stage: ScienceStage, value: JSONObject): SciencePerformanceInfo {
            val backend = value.opt("backend")
            return SciencePerformanceInfo(when (backend) { stage.gpuBackend -> true; "cpu_f64" -> false; else -> null },
                FullGpuFallback.entries.firstOrNull { it.id == value.opt("fallbackReason") }, value.opt("requested") as? Boolean,
                value.opt("attempted") as? Boolean, value.opt("resumed") as? Boolean, GpuPrepareCode.parse(value.opt("prepareErrorCode")),
                (if(value.opt("fallbackReason")==FullGpuFallback.INCLUDED_IN_RESIDENT.id)emptyList() else ScienceNumber.entries).mapNotNull { key -> (value.opt(key.nativeKey) as? Number)?.toDouble()?.takeIf { key.accepts(it) && (key != ScienceNumber.EV || stage == ScienceStage.AUTO_EXPOSURE) }?.let { key to it } }.toMap())
        }
        internal fun decode(stage: ScienceStage, text: String): SciencePerformanceInfo? = runCatching {
            require(text.length <= 768)
            val values = text.split('|'); require(values.size == 7 + ScienceNumber.entries.size && values[0] in setOf("1","2"))
            val backend = if(values[0]=="2")when(values[1]){"-"->null;"c"->false;"g"->true;else->error("Invalid backend")}
                else when(values[1]){"-"->null;"cpu_f64"->false;stage.gpuBackend->true;else->error("Invalid backend")}
            val fallback = if (values[2] == "-") null else FullGpuFallback.entries.single { it.id == values[2] }
            fun flag(i: Int): Boolean? = when (values[i]) { "-" -> null; "1" -> true; "0" -> false; else -> error("Invalid flag") }
            val prepare = if (values[6] == "-") null else requireNotNull(GpuPrepareCode.parse(values[6]))
            val numbers = ScienceNumber.entries.mapIndexedNotNull { i, key ->
                val raw = values[i + 7]
                if (raw == "-") null else raw.toDouble().also { require(key.accepts(it) && (key != ScienceNumber.EV || stage == ScienceStage.AUTO_EXPOSURE)) }.let { key to it }
            }.toMap()
            SciencePerformanceInfo(backend, fallback, flag(3), flag(4), flag(5), prepare, numbers)
        }.getOrNull()
    }
}
