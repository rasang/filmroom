package dev.filmroom.diagnostics

import org.json.JSONObject

/** Closed vocabulary only: no native prose, paths, images, arbitrary keys or strings. */
enum class PerformanceNumber(val id:String,val max:Double=604_800_000.0) {
    GPU_FULL_UPLOAD("gpuDiffusionUploadBytes",68719476736.0), GPU_FULL_READBACK("gpuDiffusionReadbackBytes",68719476736.0), GPU_FULL_DISPATCHES("gpuDiffusionDispatches",10000000.0),
    GPU_SCAN_UPLOAD("gpuScannerUploadBytes",68719476736.0), GPU_SCAN_READBACK("gpuScannerReadbackBytes",68719476736.0), GPU_SCAN_DISPATCHES("gpuScannerDispatches",10000000.0),
    GPU_OPT_UPLOAD("gpuOpticalUploadBytes",68719476736.0), GPU_OPT_READBACK("gpuOpticalReadbackBytes",68719476736.0), GPU_OPT_DISPATCHES("gpuOpticalDispatches",10000000.0),
    GPU_EXP_UPLOAD("gpuExposureUploadBytes",68719476736.0), GPU_EXP_READBACK("gpuExposureReadbackBytes",68719476736.0), GPU_EXP_DISPATCHES("gpuExposureDispatches",10000000.0),
    GPU_DIR_UPLOAD("gpuDirUploadBytes",68719476736.0), GPU_DIR_READBACK("gpuDirReadbackBytes",68719476736.0), GPU_DIR_DISPATCHES("gpuDirDispatches",10000000.0),
    GPU_EXP_HELPER("gpuExposureHelperTotalMs"), GPU_EXP_STAGE("gpuExposureStageMs"), GPU_EXP_SETUP("gpuExposureSetupMs"),
    GPU_EXP_CHUNKS("gpuExposureChunks",150000000.0), GPU_EXP_PASSES("gpuExposurePasses",30.0), GPU_EXP_MEMORY("gpuExposureCombinedPeakBytes",805306368.0),
    GPU_EXP_SAMPLES("gpuExposureSampleCount",450000000.0), GPU_EXP_ABS("gpuExposureSampleMaxAbs",1e300), GPU_EXP_RMS("gpuExposureSampleRms",1e300),
    GPU_DIR_HELPER("gpuDirHelperTotalMs"), GPU_DIR_STAGE("gpuDirStageMs"), GPU_DIR_SETUP("gpuDirSetupMs"),
    GPU_DIR_CHUNKS("gpuDirChunks",150000000.0), GPU_DIR_PASSES("gpuDirPasses",30.0), GPU_DIR_MEMORY("gpuDirCombinedPeakBytes",805306368.0),
    GPU_DIR_SAMPLES("gpuDirSampleCount",450000000.0), GPU_DIR_ABS("gpuDirSampleMaxAbs",1e300), GPU_DIR_RMS("gpuDirSampleRms",1e300),
    GPU_OPT_HELPER("gpuOpticalHelperTotalMs"), GPU_OPT_STAGE("gpuOpticalStageMs"), GPU_OPT_SETUP("gpuOpticalSetupMs"),
    GPU_OPT_PASSES("gpuOpticalPasses",30.0), GPU_OPT_MEMORY("gpuOpticalCombinedPeakBytes",805306368.0),
    GPU_OPT_SAMPLES("gpuOpticalSampleCount",2400000.0), GPU_OPT_ABS("gpuOpticalSampleMaxAbs",1e300), GPU_OPT_RMS("gpuOpticalSampleRms",1e300),
    GPU_SCAN_HELPER("gpuScannerHelperTotalMs"), GPU_SCAN_STAGE("gpuScannerStageMs"), GPU_SCAN_SETUP("gpuScannerSetupMs"),
    GPU_SCAN_CHUNKS("gpuScannerChunks",150000000.0), GPU_SCAN_MEMORY("gpuScannerCombinedPeakBytes",402653184.0),
    GPU_SCAN_SAMPLES("gpuScannerSampleCount",450000000.0), GPU_SCAN_ABS("gpuScannerSampleMaxAbs",1e300), GPU_SCAN_RMS("gpuScannerSampleRms",1e300),
    GPU_FULL_SAMPLES("gpuDiffusionSampleCount",64.0), GPU_FULL_SAMPLE_ABS("gpuDiffusionSampleMaxAbs",1e300), GPU_FULL_SAMPLE_RMS("gpuDiffusionSampleRms",1e300),
    GPU_FULL_TOL_REL("gpuDiffusionSampleToleranceRelative",1.0), GPU_FULL_TOL_ABS("gpuDiffusionSampleToleranceAbsolute",1e300),
    GPU_FULL_HELPER("gpuDiffusionHelperTotalMs"), GPU_FULL_STAGE("gpuDiffusionStageMs"), GPU_FULL_SETUP("gpuDiffusionSetupMs"),
    GPU_FULL_TILES("gpuDiffusionTiles",64.0), GPU_FULL_FFT("gpuDiffusionFftSide",4096.0), GPU_FULL_MEMORY("gpuDiffusionCombinedPeakBytes",805306368.0),
    MASK("optimizationMask",127.0), CACHE_BYTES("previewCacheBytes",33554432.0), RESUME("checkpointResumeStage",100.0),
    PROBE_CAPTURE("gpuProbeCaptureMs"), NATIVE_WALL("nativeWallMs"), IDENTITY("checkpointIdentityMs"), COMMIT("checkpointCommitMs"), SETUP("setupMs"),
    FILM_EXPOSURE("timing_film_exposure"), HIGHLIGHT("timing_highlight_boost"), CAMERA_DIFFUSION("timing_camera_diffusion"),
    CAMERA_BLUR("timing_camera_blur"), OPTICS("timing_film_scatter_halation"), DENSITY_DIR("timing_film_density_dir"),
    GRAIN("timing_grain"), PRINT_DIFFUSION("timing_print_diffusion"), GLARE("timing_scan_glare"),
    PRINTING("timing_printing_scanning"), SHARPEN("timing_scan_sharpen"), SCAN_BLUR("timing_scan_blur"),
    REUSE_BATCHES("gpuReuseBatches",1_800_000_000.0), REUSE_CHECKS("gpuReuseExactChecks",1_800_000_000.0),
    CAL_PIXELS("gpuCalPixels",1_000_000.0), CAL_KNOTS("gpuCalKnots",65536.0), CAL_SAMPLES("gpuCalSamples",100.0),
    CAL_CANDIDATE_SAMPLES("gpuCalCandidateSamples",3.0), CAL_VALIDATION("gpuCalCandidateValidationMs"),
    CAL_RATIO("gpuCalRequiredTimeRatio",1.0), CAL_CPU("gpuCalCpuMedianMs"), CAL_ORIGINAL("gpuCalOriginalMedianMs"), CAL_CANDIDATE("gpuCalCandidateMedianMs"),
    CAL_MAX_ERROR("gpuCalCandidateCpuMaxAbs",1.0e12), CAL_RMS("gpuCalCandidateCpuRms",1.0e12);
    fun accepts(value:Double):Boolean = value.isFinite() && value in 0.0..max && (this !in setOf(GPU_FULL_UPLOAD,GPU_FULL_READBACK,GPU_FULL_DISPATCHES,GPU_SCAN_UPLOAD,GPU_SCAN_READBACK,GPU_SCAN_DISPATCHES,GPU_OPT_UPLOAD,GPU_OPT_READBACK,GPU_OPT_DISPATCHES,GPU_EXP_UPLOAD,GPU_EXP_READBACK,GPU_EXP_DISPATCHES,GPU_DIR_UPLOAD,GPU_DIR_READBACK,GPU_DIR_DISPATCHES,GPU_DIR_CHUNKS,GPU_DIR_PASSES,GPU_DIR_MEMORY,GPU_DIR_SAMPLES,GPU_EXP_CHUNKS,GPU_EXP_PASSES,GPU_EXP_MEMORY,GPU_EXP_SAMPLES,GPU_OPT_PASSES,GPU_OPT_MEMORY,GPU_OPT_SAMPLES,GPU_SCAN_CHUNKS,GPU_SCAN_MEMORY,GPU_SCAN_SAMPLES,GPU_FULL_SAMPLES,GPU_FULL_TILES,GPU_FULL_FFT,GPU_FULL_MEMORY,MASK,CACHE_BYTES,RESUME,REUSE_BATCHES,REUSE_CHECKS,CAL_PIXELS,CAL_KNOTS,CAL_SAMPLES,CAL_CANDIDATE_SAMPLES) || value % 1.0 == 0.0)
}
enum class PerformanceFlag(val id:String) {
    GPU_EXP_REQUEST("gpuExposureRequested"), GPU_EXP_ATTEMPT("gpuExposureAttempted"), GPU_EXP_RESUMED("gpuExposureResumed"),
    GPU_DIR_REQUEST("gpuDirRequested"), GPU_DIR_ATTEMPT("gpuDirAttempted"), GPU_DIR_RESUMED("gpuDirResumed"),
    GPU_OPT_REQUEST("gpuOpticalRequested"), GPU_OPT_ATTEMPT("gpuOpticalAttempted"), GPU_OPT_RESUMED("gpuOpticalResumed"),
    GPU_SCAN_REQUEST("gpuScannerRequested"), GPU_SCAN_ATTEMPT("gpuScannerAttempted"), GPU_SCAN_RESUMED("gpuScannerResumed"),
    GPU_FULL_REQUEST("gpuDiffusionRequested"), GPU_FULL_ATTEMPT("gpuDiffusionAttempted"), GPU_FULL_RESUMED("gpuDiffusionResumed"),
    CPU("cpuComparison"), PREVIEW_REQUEST("previewReuseRequested"), GPU_REUSE_REQUEST("gpuReuseRequested"), CACHE_HIT("previewReuseHit"),
    CAL_ORIGINAL_PASS("gpuCalOriginalPassed"), CAL_CANDIDATE_PASS("gpuCalCandidatePassed"), CAL_GPU_EXACT("gpuCalCandidateExactGpu"),
    CAL_CPU_EXACT("gpuCalCandidateExactCpu"), CAL_NUMERIC("gpuCalNumericPassed"), CAL_F64("gpuShaderF64Advertised")
}
enum class CalibrationStatus(val id:String) { NOT_REQUESTED("not_requested_at_initialization"), NOT_MEASURED("requested_not_measured"), BUDGET("budget_exhausted"), FAILURE("device_or_validation_failure"), NOT_EXACT("exact_comparison_failed"), MEASURED("measured_diagnostic_only") }
enum class ProbeCaptureStatus(val id:String) { DISABLED("disabled"), CAPTURED("captured"), INVALID("skipped_invalid_request"), UNSUPPORTED("skipped_unsupported"), RESUMED("skipped_resumed"), INACTIVE("skipped_inactive"), NOOP("skipped_noop"), LIMIT("skipped_limit"), FAILED("skipped_failed"), CANCELLED("skipped_cancelled") }
enum class FullGpuBackend(val id:String) { CPU("cpu_f64"), GPU("gpu_f32_v1") }
enum class FullGpuFallback(val id:String) { NONE("none"), INACTIVE("inactive"), INCLUDED_IN_RESIDENT("included_in_resident"), RESIDENT_FALLBACK("resident_fallback"), UPSTREAM_CPU("upstream_cpu"), UNSUPPORTED_MODEL("unsupported_model"), UNSUPPORTED_CONFIGURATION("unsupported_configuration"), PREFLIGHT_MEMORY("preflight_memory"), DELEGATE_UNAVAILABLE("delegate_unavailable"), UNSUPPORTED_BUILD("unsupported_build"), GEOMETRY_LIMIT("geometry_limit"), PATH_UNAVAILABLE("path_unavailable"), DISK_UNAVAILABLE("disk_unavailable"), PREPARE_FAILED("prepare_failed"), NOT_STARTED("not_started"), HELPER_MEMORY("helper_memory"), HELPER_FAILED("helper_failed"), HELPER_TIMEOUT("helper_timeout"), PREVIEW_BUDGET("preview_budget"), DRIVER_ERROR("driver_error"), QUALITY_GUARD("quality_guard"), INVALID_OUTPUT("invalid_output"), COMMIT_FAILED("commit_failed") }
enum class ScannerGpuBackend(val id:String) { CPU("cpu_f64"), GPU("gpu_pchip_f32_v1") }
enum class OpticalGpuBackend(val id:String) { CPU("cpu_f64"), GPU("gpu_spatial_f32_v1") }
enum class GpuPrepareStage(val objectKey:String,val fieldKey:String) {
    CAMERA("gpuDiffusion","gpuDiffusionPrepareErrorCode"), SCANNER("gpuScanner","gpuScannerPrepareErrorCode"), OPTICAL("gpuOptical","gpuOpticalPrepareErrorCode"), EXPOSURE("gpuExposure","gpuExposurePrepareErrorCode"), DIR("gpuDir","gpuDirPrepareErrorCode")
}
data class GpuPrepareCode private constructor(val id:String) {
    companion object {
        private val simple=setOf("SOURCE_SHAPE","SOURCE_IDENTITY","SOURCE_NONFINITE","COPY_RESERVE","COPY_HASH","STAGE_PATH","SOURCE_LINK_DESTINATION")
        private val phases=setOf("SOURCE_STAT","STAGE_PARENT_STAT","STAGE_PARENT_CANONICAL","SOURCE_OPEN","SOURCE_READ","STAGE_MKDIR","SOURCE_LINK","COPY_CREATE","COPY_WRITE","COPY_SYNC","COPY_VERIFY","SOURCE_RESTAT","MANIFEST_CREATE","MANIFEST_WRITE","MANIFEST_SYNC","MANIFEST_RENAME","KERNEL_CREATE","KERNEL_WRITE","KERNEL_SYNC","TABLE_CREATE","TABLE_WRITE","TABLE_SYNC")
        private val errors=setOf("EPERM","ENOENT","EIO","EACCES","EEXIST","EXDEV","ENOTDIR","EISDIR","ENOSPC","EROFS","ELOOP","EOPNOTSUPP","EDQUOT","EOF","OTHER")
        fun parse(value:Any?):GpuPrepareCode? {
            val text=value as? String ?: return null
            val rest=text.removePrefix("GPU_PREPARE_")
            if(rest==text || text.length>96)return null
            return if(rest in simple || phases.any{p->errors.any{e->rest=="${p}_${e}"}})GpuPrepareCode(text) else null
        }
    }
}
enum class ExposureGpuBackend(val id:String) { CPU("cpu_f64"), GPU("gpu_exposure_f32_v1") }
enum class DirGpuBackend(val id:String) { CPU("cpu_f64"), GPU("gpu_dir_f32_v1") }
data class PerformanceInfo(val numbers:Map<PerformanceNumber,Double> = emptyMap(),val flags:Map<PerformanceFlag,Boolean> = emptyMap(),val calibrationStatus:CalibrationStatus?=null,val probeCaptureStatus:ProbeCaptureStatus?=null,val fullGpuBackend:FullGpuBackend?=null,val fullGpuFallback:FullGpuFallback?=null,val scannerGpuBackend:ScannerGpuBackend?=null,val scannerGpuFallback:FullGpuFallback?=null,val opticalGpuBackend:OpticalGpuBackend?=null,val opticalGpuFallback:FullGpuFallback?=null,val prepareErrors:Map<GpuPrepareStage,GpuPrepareCode> = emptyMap(),val exposureGpuBackend:ExposureGpuBackend?=null,val exposureGpuFallback:FullGpuFallback?=null,val dirGpuBackend:DirGpuBackend?=null,val dirGpuFallback:FullGpuFallback?=null,val science:Map<ScienceStage,SciencePerformanceInfo> = emptyMap()) {
    internal fun typedFields():Map<String,Any> = linkedMapOf<String,Any>().apply {
        numbers.forEach{(key,value)->if(key.accepts(value))put(key.id,value)}
        flags.forEach{(key,value)->put(key.id,value)}
        calibrationStatus?.let{put("gpuCalCandidateStatus",it.id)}
        probeCaptureStatus?.let{put("gpuProbeCaptureStatus",it.id)}
        fullGpuBackend?.let{put("gpuDiffusionBackend",it.id)}
        fullGpuFallback?.let{put("gpuDiffusionFallback",it.id)}
        scannerGpuBackend?.let{put("gpuScannerBackend",it.id)}
        scannerGpuFallback?.let{put("gpuScannerFallback",it.id)}
        opticalGpuBackend?.let{put("gpuOpticalBackend",it.id)}
        opticalGpuFallback?.let{put("gpuOpticalFallback",it.id)}
        exposureGpuBackend?.let{put("gpuExposureBackend",it.id)}
        exposureGpuFallback?.let{put("gpuExposureFallback",it.id)}
        dirGpuBackend?.let{put("gpuDirBackend",it.id)}
        dirGpuFallback?.let{put("gpuDirFallback",it.id)}
        prepareErrors.forEach{(stage,code)->put(stage.fieldKey,code.id)}
        science.forEach{(stage,value)->put(stage.fieldKey,value.encode(stage))}
    }
    companion object {
        fun selection(mask:Int,cpu:Boolean,preview:Boolean,gpu:Boolean)=PerformanceInfo(mapOf(PerformanceNumber.MASK to mask.toDouble()),mapOf(PerformanceFlag.CPU to cpu,PerformanceFlag.PREVIEW_REQUEST to preview,PerformanceFlag.GPU_REUSE_REQUEST to gpu))
        fun fromFields(fields:Map<String,String>)=PerformanceInfo(
            PerformanceNumber.entries.mapNotNull{k->fields[k.id]?.toDoubleOrNull()?.takeIf{k.accepts(it)}?.let{k to it}}.toMap(),
            PerformanceFlag.entries.mapNotNull{k->fields[k.id]?.toBooleanStrictOrNull()?.let{k to it}}.toMap(),CalibrationStatus.entries.firstOrNull{it.id==fields["gpuCalCandidateStatus"]},ProbeCaptureStatus.entries.firstOrNull{it.id==fields["gpuProbeCaptureStatus"]},FullGpuBackend.entries.firstOrNull{it.id==fields["gpuDiffusionBackend"]},FullGpuFallback.entries.firstOrNull{it.id==fields["gpuDiffusionFallback"]},ScannerGpuBackend.entries.firstOrNull{it.id==fields["gpuScannerBackend"]},FullGpuFallback.entries.firstOrNull{it.id==fields["gpuScannerFallback"]},OpticalGpuBackend.entries.firstOrNull{it.id==fields["gpuOpticalBackend"]},FullGpuFallback.entries.firstOrNull{it.id==fields["gpuOpticalFallback"]},GpuPrepareStage.entries.mapNotNull{stage->GpuPrepareCode.parse(fields[stage.fieldKey])?.let{stage to it}}.toMap(),ExposureGpuBackend.entries.firstOrNull{it.id==fields["gpuExposureBackend"]},FullGpuFallback.entries.firstOrNull{it.id==fields["gpuExposureFallback"]},DirGpuBackend.entries.firstOrNull{it.id==fields["gpuDirBackend"]},FullGpuFallback.entries.firstOrNull{it.id==fields["gpuDirFallback"]},ScienceStage.entries.mapNotNull{stage->fields[stage.fieldKey]?.let{SciencePerformanceInfo.decode(stage,it)}?.let{stage to it}}.toMap()).takeUnless{it.numbers.isEmpty() && it.flags.isEmpty() && it.calibrationStatus==null && it.probeCaptureStatus==null && it.fullGpuBackend==null && it.fullGpuFallback==null && it.scannerGpuBackend==null && it.scannerGpuFallback==null && it.opticalGpuBackend==null && it.opticalGpuFallback==null && it.prepareErrors.isEmpty() && it.exposureGpuBackend==null && it.exposureGpuFallback==null && it.dirGpuBackend==null && it.dirGpuFallback==null && it.science.isEmpty()}
        fun fromNative(result:JSONObject,selection:PerformanceInfo):PerformanceInfo {
            val numbers=selection.numbers.toMutableMap(); val flags=selection.flags.toMutableMap()
            fun number(from:JSONObject?,name:String,key:PerformanceNumber){if(from?.opt("fallbackReason")==FullGpuFallback.INCLUDED_IN_RESIDENT.id)return;(from?.opt(name) as? Number)?.toDouble()?.takeIf{key.accepts(it)}?.let{numbers[key]=it}}
            fun flag(from:JSONObject?,name:String,key:PerformanceFlag){(from?.opt(name) as? Boolean)?.let{flags[key]=it}}
            val metrics=result.optJSONObject("metrics") ?: result
            for(stage in GpuPrepareStage.entries){
                val value=metrics.optJSONObject(stage.objectKey)?.takeUnless{it.opt("fallbackReason")==FullGpuFallback.INCLUDED_IN_RESIDENT.id}
                for((source,suffix) in listOf("uploadBytes" to "UploadBytes","readbackBytes" to "ReadbackBytes","dispatches" to "Dispatches")){
                    PerformanceNumber.entries.firstOrNull{it.id==stage.objectKey+suffix}?.let{number(value,source,it)}
                }
            }
            val exposure=metrics.optJSONObject("gpuExposure")
            for((name,key) in listOf("helperTotalMs" to PerformanceNumber.GPU_EXP_HELPER,"gpuStageMs" to PerformanceNumber.GPU_EXP_STAGE,"setupMs" to PerformanceNumber.GPU_EXP_SETUP,"chunks" to PerformanceNumber.GPU_EXP_CHUNKS,"passes" to PerformanceNumber.GPU_EXP_PASSES,"combinedPeakBytes" to PerformanceNumber.GPU_EXP_MEMORY,"sampleCount" to PerformanceNumber.GPU_EXP_SAMPLES,"sampleMaxAbs" to PerformanceNumber.GPU_EXP_ABS,"sampleRms" to PerformanceNumber.GPU_EXP_RMS))number(exposure,name,key)
            for((name,key) in listOf("requested" to PerformanceFlag.GPU_EXP_REQUEST,"attempted" to PerformanceFlag.GPU_EXP_ATTEMPT,"resumed" to PerformanceFlag.GPU_EXP_RESUMED))flag(exposure,name,key)
            val dir=metrics.optJSONObject("gpuDir")
            for((name,key) in listOf("helperTotalMs" to PerformanceNumber.GPU_DIR_HELPER,"gpuStageMs" to PerformanceNumber.GPU_DIR_STAGE,"setupMs" to PerformanceNumber.GPU_DIR_SETUP,"chunks" to PerformanceNumber.GPU_DIR_CHUNKS,"passes" to PerformanceNumber.GPU_DIR_PASSES,"combinedPeakBytes" to PerformanceNumber.GPU_DIR_MEMORY,"sampleCount" to PerformanceNumber.GPU_DIR_SAMPLES,"sampleMaxAbs" to PerformanceNumber.GPU_DIR_ABS,"sampleRms" to PerformanceNumber.GPU_DIR_RMS))number(dir,name,key)
            for((name,key) in listOf("requested" to PerformanceFlag.GPU_DIR_REQUEST,"attempted" to PerformanceFlag.GPU_DIR_ATTEMPT,"resumed" to PerformanceFlag.GPU_DIR_RESUMED))flag(dir,name,key)
            val optical=metrics.optJSONObject("gpuOptical")
            for((name,key) in listOf("helperTotalMs" to PerformanceNumber.GPU_OPT_HELPER,"gpuStageMs" to PerformanceNumber.GPU_OPT_STAGE,"setupMs" to PerformanceNumber.GPU_OPT_SETUP,"passes" to PerformanceNumber.GPU_OPT_PASSES,"combinedPeakBytes" to PerformanceNumber.GPU_OPT_MEMORY,"sampleCount" to PerformanceNumber.GPU_OPT_SAMPLES,"sampleMaxAbs" to PerformanceNumber.GPU_OPT_ABS,"sampleRms" to PerformanceNumber.GPU_OPT_RMS))number(optical,name,key)
            for((name,key) in listOf("requested" to PerformanceFlag.GPU_OPT_REQUEST,"attempted" to PerformanceFlag.GPU_OPT_ATTEMPT,"resumed" to PerformanceFlag.GPU_OPT_RESUMED))flag(optical,name,key)
            val scanner=metrics.optJSONObject("gpuScanner")
            for((name,key) in listOf("helperTotalMs" to PerformanceNumber.GPU_SCAN_HELPER,"gpuStageMs" to PerformanceNumber.GPU_SCAN_STAGE,"setupMs" to PerformanceNumber.GPU_SCAN_SETUP,"chunks" to PerformanceNumber.GPU_SCAN_CHUNKS,"combinedPeakBytes" to PerformanceNumber.GPU_SCAN_MEMORY,"sampleCount" to PerformanceNumber.GPU_SCAN_SAMPLES,"sampleMaxAbs" to PerformanceNumber.GPU_SCAN_ABS,"sampleRms" to PerformanceNumber.GPU_SCAN_RMS))number(scanner,name,key)
            for((name,key) in listOf("requested" to PerformanceFlag.GPU_SCAN_REQUEST,"attempted" to PerformanceFlag.GPU_SCAN_ATTEMPT,"resumed" to PerformanceFlag.GPU_SCAN_RESUMED))flag(scanner,name,key)
            val full=metrics.optJSONObject("gpuDiffusion")
            for((name,key) in listOf("sampleCount" to PerformanceNumber.GPU_FULL_SAMPLES,"sampleMaxAbs" to PerformanceNumber.GPU_FULL_SAMPLE_ABS,"sampleRms" to PerformanceNumber.GPU_FULL_SAMPLE_RMS,"sampleToleranceRelative" to PerformanceNumber.GPU_FULL_TOL_REL,"sampleToleranceAbsolute" to PerformanceNumber.GPU_FULL_TOL_ABS,"helperTotalMs" to PerformanceNumber.GPU_FULL_HELPER,"gpuStageMs" to PerformanceNumber.GPU_FULL_STAGE,"setupMs" to PerformanceNumber.GPU_FULL_SETUP,"tiles" to PerformanceNumber.GPU_FULL_TILES,"fftSide" to PerformanceNumber.GPU_FULL_FFT,"combinedPeakBytes" to PerformanceNumber.GPU_FULL_MEMORY))number(full,name,key)
            for((name,key) in listOf("requested" to PerformanceFlag.GPU_FULL_REQUEST,"attempted" to PerformanceFlag.GPU_FULL_ATTEMPT,"resumed" to PerformanceFlag.GPU_FULL_RESUMED))flag(full,name,key)
            val capture=metrics.optJSONObject("gpuProbeCapture")
            number(capture,"captureMs",PerformanceNumber.PROBE_CAPTURE);number(metrics,"wallElapsedMs",PerformanceNumber.NATIVE_WALL)
            number(result,"previewCacheBytes",PerformanceNumber.CACHE_BYTES);flag(result,"previewReuseHit",PerformanceFlag.CACHE_HIT)
            for((name,key) in listOf("checkpointResumeStage" to PerformanceNumber.RESUME,"checkpointIdentityMs" to PerformanceNumber.IDENTITY,"checkpointCommitMs" to PerformanceNumber.COMMIT,"setupMs" to PerformanceNumber.SETUP))number(metrics,name,key)
            metrics.optJSONArray("stages")?.let{stages->for(i in 0 until minOf(stages.length(),32)){
                val stage=stages.optJSONObject(i)?:continue
                PerformanceNumber.entries.firstOrNull{it.id=="timing_"+stage.optString("stage")}?.let{number(stage,"elapsedMs",it)}
            }}
            val gpu=metrics.optJSONObject("gpu")?:result.optJSONObject("gpu")
            number(gpu,"resourceReuseBatches",PerformanceNumber.REUSE_BATCHES);number(gpu,"resourceReuseExactChecks",PerformanceNumber.REUSE_CHECKS)
            val cal=gpu?.optJSONObject("densityCalibration")
            for((name,key) in listOf("candidateSamples" to PerformanceNumber.CAL_CANDIDATE_SAMPLES,"candidateValidationMs" to PerformanceNumber.CAL_VALIDATION,"fixturePixels" to PerformanceNumber.CAL_PIXELS,"curveKnots" to PerformanceNumber.CAL_KNOTS,"samples" to PerformanceNumber.CAL_SAMPLES,"requiredTimeRatio" to PerformanceNumber.CAL_RATIO,"cpuMedianMs" to PerformanceNumber.CAL_CPU,"originalGpuMedianMs" to PerformanceNumber.CAL_ORIGINAL,"candidateGpuMedianMs" to PerformanceNumber.CAL_CANDIDATE,"candidateCpuMaxAbs" to PerformanceNumber.CAL_MAX_ERROR,"candidateCpuRms" to PerformanceNumber.CAL_RMS))number(cal,name,key)
            for((name,key) in listOf("originalGatePassed" to PerformanceFlag.CAL_ORIGINAL_PASS,"candidateGatePassed" to PerformanceFlag.CAL_CANDIDATE_PASS,"candidateExactVsOriginalGpu" to PerformanceFlag.CAL_GPU_EXACT,"candidateCpuBitEqual" to PerformanceFlag.CAL_CPU_EXACT,"numericSelfCheckPassed" to PerformanceFlag.CAL_NUMERIC,"shaderF64Advertised" to PerformanceFlag.CAL_F64))flag(cal,name,key)
            return PerformanceInfo(numbers,flags,CalibrationStatus.entries.firstOrNull{it.id==cal?.opt("candidateStatus")},ProbeCaptureStatus.entries.firstOrNull{it.id==capture?.opt("status")},FullGpuBackend.entries.firstOrNull{it.id==full?.opt("backend")},FullGpuFallback.entries.firstOrNull{it.id==full?.opt("fallbackReason")},ScannerGpuBackend.entries.firstOrNull{it.id==scanner?.opt("backend")},FullGpuFallback.entries.firstOrNull{it.id==scanner?.opt("fallbackReason")},OpticalGpuBackend.entries.firstOrNull{it.id==optical?.opt("backend")},FullGpuFallback.entries.firstOrNull{it.id==optical?.opt("fallbackReason")},GpuPrepareStage.entries.mapNotNull{stage->GpuPrepareCode.parse(metrics.optJSONObject(stage.objectKey)?.opt("prepareErrorCode"))?.let{stage to it}}.toMap(),ExposureGpuBackend.entries.firstOrNull{it.id==exposure?.opt("backend")},FullGpuFallback.entries.firstOrNull{it.id==exposure?.opt("fallbackReason")},DirGpuBackend.entries.firstOrNull{it.id==dir?.opt("backend")},FullGpuFallback.entries.firstOrNull{it.id==dir?.opt("fallbackReason")},ScienceStage.entries.mapNotNull{stage->metrics.optJSONObject("gpuScience")?.optJSONObject(stage.objectKey)?.let{stage to SciencePerformanceInfo.fromNative(stage,it)}}.toMap())
        }
    }
}
