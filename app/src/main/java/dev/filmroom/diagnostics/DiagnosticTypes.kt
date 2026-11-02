package dev.filmroom.diagnostics

/** No arbitrary messages, exceptions, filenames, URIs, or string key/value maps. */
enum class Operation { SESSION, RESTORE, IMPORT, PREVIEW_RENDER, PREVIEW_PRESENTATION, ORIGINAL_SPOOL, EXPORT_PLAN, EXPORT_RENDER, EXPORT_ENCODE, SHARE_DIAGNOSTICS, CLEANUP }
enum class Stage { START, DECODE_HEADER, DECODE_STRIPE, NATIVE_ENTER, NATIVE_PROGRESS, NATIVE_EXIT, ENCODE_START, ENCODE_PROGRESS, MEDIA_INSERTED, MEDIA_COMMITTED, CANCEL_REQUESTED, CLEANUP, ERROR, PREVIOUS_UNFINISHED, END }
enum class Outcome { SUCCESS, CANCELLED, FAILED, ABANDONED }
enum class ErrorCode { ENGINE_UNAVAILABLE, INVALID_ARGUMENT, UNSUPPORTED_FORMAT, DECODE_FAILED, MEMORY_LIMIT, DISK_SPACE, RENDER_FAILED, ENCODE_FAILED, PERMISSION_LOST, CLEANUP_UNCERTAIN, CANCELLED, PREVIOUS_TERMINATION_UNCERTAIN, UNKNOWN }
enum class FilmProfile(val id: String) {
    KODAK_DOUBLEX_5222("kodak_doublex"),
    KODAK_TRIX_7266("kodak_trix"),
    C200("fujifilm_c200"),
    FUJIFILM_PRO_400H("fujifilm_pro_400h"),
    FUJIFILM_PROVIA_100F("fujifilm_provia_100f"),
    FUJIFILM_VELVIA_100("fujifilm_velvia_100"),
    FUJIFILM_XTRA_400("fujifilm_xtra_400"),
    KODAK_EKTACHROME_100("kodak_ektachrome_100"),
    KODAK_EKTAR_100("kodak_ektar_100"),
    GOLD_200("kodak_gold_200"),
    KODAK_KODACHROME_64("kodak_kodachrome_64"),
    KODAK_PORTRA_160("kodak_portra_160"),
    PORTRA_400("kodak_portra_400"),
    KODAK_PORTRA_800("kodak_portra_800"),
    KODAK_PORTRA_800_PUSH1("kodak_portra_800_push1"),
    KODAK_PORTRA_800_PUSH2("kodak_portra_800_push2"),
    KODAK_ULTRAMAX_400("kodak_ultramax_400"),
    KODAK_VERITA_200D("kodak_verita_200d"),
    KODAK_VISION3_200T("kodak_vision3_200t"),
    KODAK_VISION3_250D("kodak_vision3_250d"),
    KODAK_VISION3_500T("kodak_vision3_500t"),
    KODAK_VISION3_50D("kodak_vision3_50d");
    companion object { fun fromId(id: String): FilmProfile? = entries.firstOrNull { it.id == id } }
}
enum class PaperProfile(val id: String) {
    KODAK_2302("kodak_2302"),
    CRYSTAL_ARCHIVE_II("fujifilm_crystal_archive_typeii"),
    KODAK_2383("kodak_2383"),
    KODAK_2393("kodak_2393"),
    KODAK_EKTACOLOR_EDGE("kodak_ektacolor_edge"),
    KODAK_ENDURA_PREMIER("kodak_endura_premier"),
    PORTRA_ENDURA("kodak_portra_endura"),
    SUPRA_ENDURA("kodak_supra_endura"),
    KODAK_ULTRA_ENDURA("kodak_ultra_endura");
    companion object { fun fromId(id: String): PaperProfile? = entries.firstOrNull { it.id == id } }
}
enum class OutputColorProfile(val id:String) { SRGB("sRGB"), DISPLAY_P3("Display P3"), ADOBE_RGB("Adobe RGB (1998)"); companion object {fun fromId(id:String):OutputColorProfile?=entries.firstOrNull{it.id==id}} }
enum class PrintIlluminant(val id:String) { TH_KG3("TH-KG3"), D50("D50"), D55("D55"), D65("D65"); companion object {fun fromId(id:String):PrintIlluminant?=entries.firstOrNull{it.id==id}} }
enum class MeteringMode(val id:String) { AVERAGE("average"), MEDIAN("median"), CENTER_WEIGHTED("center_weighted"), PARTIAL("partial"), MATRIX("matrix"), MULTI_ZONE("multi_zone"), HIGHLIGHT_WEIGHTED("highlight_weighted"); companion object {fun fromId(id:String):MeteringMode?=entries.firstOrNull{it.id==id}} }
enum class OutputRoute(val id:String) { PRINT("print"), DIRECT("direct"); companion object {fun fromId(id:String):OutputRoute?=entries.firstOrNull{it.id==id}} }
enum class DiffusionProfile(val id:String) {
    OFF("off"), GLIMMERGLASS("glimmerglass"), BLACK_PRO_MIST("black_pro_mist"), PRO_MIST("pro_mist"), CINEBLOOM("cinebloom");
    companion object {fun fromId(id:String):DiffusionProfile?=entries.firstOrNull{it.id==id}}
}
enum class HalationColor(val id:String) { STOCK("stock"), RED("red"), AMBER("amber"), NEUTRAL("neutral"); companion object {fun fromId(id:String)=entries.firstOrNull{it.id==id}} }
enum class NativePhase(val id:String,private val labelId:Int) {
    SETUP("setup",dev.filmroom.R.string.text_652bd8e2b42a), AUTO_EXPOSURE("auto_exposure",dev.filmroom.R.string.text_7b5d2a09babd), HIGHLIGHT_BOOST("highlight_boost",dev.filmroom.R.string.text_6ff3e6e82562), CAMERA_BLUR("camera_blur",dev.filmroom.R.string.text_99ccd913c2c3), SCAN_BLUR("scan_blur",dev.filmroom.R.string.text_1a8fd554611b), SCAN_GLARE("scan_glare",dev.filmroom.R.string.text_c08900693d28), GRAIN_MICROSTRUCTURE("grain_microstructure",dev.filmroom.R.string.text_28dfadf63de0), PRINT_DIFFUSION("print_diffusion",dev.filmroom.R.string.text_6a36379e12c4), CAMERA_DIFFUSION("camera_diffusion",dev.filmroom.R.string.text_bab826d7cea1), FILM_EXPOSURE("film_exposure",dev.filmroom.R.string.text_ee5e1f321d0c),
    FILM_SCATTER("film_scatter",dev.filmroom.R.string.text_e29e511b19c8), FILM_HALATION("film_halation",dev.filmroom.R.string.text_0caa7cb6bf14),
    FILM_LOG("film_log",dev.filmroom.R.string.text_a3a8c53854e3), FILM_DENSITY("film_density",dev.filmroom.R.string.text_e010cca5a693),
    DIR_DIFFUSION("dir_diffusion",dev.filmroom.R.string.text_c3d62fb6dc52), DIR_DEVELOP("dir_develop",dev.filmroom.R.string.text_63dc46ef8527),
    GRAIN_SAMPLING("grain_sampling",dev.filmroom.R.string.text_6817024599e0), GRAIN_CLOUD("grain_cloud",dev.filmroom.R.string.text_b6ccbb59ff43),
    GRAIN_BLUR("grain_blur",dev.filmroom.R.string.text_465b9d003092), PRINTING_SCANNING("printing_scanning",dev.filmroom.R.string.text_6534faa7428f), SCAN_SHARPEN("scan_sharpen",dev.filmroom.R.string.text_6fb121b28fdd), COMPLETE("complete",dev.filmroom.R.string.text_93b5664a205f);
    val chinese:String get()=dev.filmroom.i18n.L10n.text(labelId)
    companion object {fun fromId(id:String):NativePhase?=entries.firstOrNull{it.id==id}}
}
class OperationId internal constructor(val value: Long)

/** Invalid numeric values are omitted, not logged or converted into arbitrary text. */
data class SafeInfo(
    val width: Int? = null, val height: Int? = null,
    val durationMs: Long? = null,
    val javaUsedBytes: Long? = null, val javaMaxBytes: Long? = null, val nativeAllocatedBytes: Long? = null,
    val completedUnits: Long? = null, val totalUnits: Long? = null,
    val cleanupRemoved: Long? = null, val cleanupUnresolved: Long? = null,
    val memoryBudgetMiB: Int? = null, val estimatedWorkingBytes: Long? = null,
    val film: FilmProfile? = null, val paper: PaperProfile? = null,
    val brightness: Double? = null, val filmExposure: Double? = null,
    val meteredEv: Double? = null,
    val contrast: Double? = null, val warmth: Double? = null, val saturation: Double? = null,
    val grain: Double? = null, val grainSize: Double? = null,
    val halation: Double? = null, val halationSize: Double? = null,
    val diffusionFilter: DiffusionProfile? = null,
    val diffusionStrength: Double? = null, val diffusionSize: Double? = null,
    val printYellow: Double? = null, val printMagenta: Double? = null, val preflash: Double? = null,
    val scanSharpenAmount: Double? = null, val scanSharpenRadius: Double? = null,
    val filmFormatMm: Double? = null,
    val lensBlurUm: Double? = null,
    val scanBlur: Double? = null,
    val scatterAmount: Double? = null,
    val scatterSize: Double? = null,
    val halationBounces: Long? = null,
    val halationDecay: Double? = null,
    val halationNormalize: Boolean? = null,
    val dirAmount: Double? = null,
    val dirDiffusionUm: Double? = null,
    val dirSameLayer: Double? = null,
    val dirInterLayer: Double? = null,
    val grainSublayers: Boolean? = null,
    val grainLayers: Long? = null,
    val grainBlur: Double? = null,
    val grainDyeCloud: Double? = null,
    val grainUniformity: Double? = null,
    val outputRoute: OutputRoute? = null,
    val useGpu: Boolean? = null,
    val outputColorSpace: OutputColorProfile? = null,
    val outputGamut: Boolean? = null,
    val printExposureEv: Double? = null,
    val printIlluminant: PrintIlluminant? = null,
    val printCompensation: Boolean? = null,
    val normalizePrintExposure: Boolean? = null,
    val preflashYellow: Double? = null,
    val preflashMagenta: Double? = null,
    val scanWhiteCorrection: Boolean? = null,
    val scanBlackCorrection: Boolean? = null,
    val scanWhiteLevel: Double? = null,
    val scanBlackLevel: Double? = null,
    val autoExposure: Boolean? = null,
    val metering: MeteringMode? = null,
    val highlightBoostEv: Double? = null,
    val highlightBoostRange: Double? = null,
    val highlightProtectEv: Double? = null,
    val scanGlarePercent: Double? = null,
    val scanGlareRoughness: Double? = null,
    val scanGlareBlur: Double? = null,
    val grainClumpSizeUm: Double? = null,
    val grainClumpStrengthNm: Double? = null,
    val printDiffusionFilter: DiffusionProfile? = null,
    val printDiffusionStrength: Double? = null,
    val printDiffusionSize: Double? = null,
    val neutralFiltersFromDatabase: Boolean? = null,
    val neutralCyan: Double? = null,
    val neutralMagenta: Double? = null,
    val neutralYellow: Double? = null,
    val filmGamma: Double? = null,
    val paperGamma: Double? = null,
    val paperGammaFast: Double? = null,
    val paperGammaSlow: Double? = null,
    val paperGammaRed: Double? = null,
    val paperGammaGreen: Double? = null,
    val paperGammaBlue: Double? = null,
    val paperDeveloperExhaustion: Double? = null,
    val filmDevelopmentTime: Double? = null,
    val paperDevelopmentTime: Double? = null,
    val grainRedScale: Double? = null,
    val grainGreenScale: Double? = null,
    val grainBlueScale: Double? = null,
    val halationColor: HalationColor? = null,
    val exportPoolStatus: ExportPoolStatus? = null,
    val exportWorkers: Long? = null,
    val exportWorkerCap: Long? = null,
    val exportAdmissionWaitMs: Double? = null,
    val exportInteractiveWaits: Long? = null,
    val exportInteractiveSerialChunks: Long? = null,
    val exportParallelChunks: Long? = null,
    val exportSerialChunks: Long? = null,
    val exportInteractiveWaitMs: Double? = null,
    val gpuRequested: Boolean? = null,
    val gpuActive: Boolean? = null,
    val gpuBackend: GpuBackend? = null,
    val gpuStage: GpuStage? = null,
    val gpuAdapterModel: Int? = null,
    val gpuBatches: Long? = null,
    val gpuFallbacks: Long? = null,
    val gpuReason: GpuReason? = null,
    val gpuSelfTest: Boolean? = null,
    val seed: Long? = null,
    val previousOperation: Operation? = null,
    val nativePhase: NativePhase? = null,
    val nativePass: Long? = null,
    val nativeEngineCode: NativeEngineCode? = null,
    val nativeEngineStage: NativeEngineStage? = null,
    val codecCode: CodecErrorCode? = null,
    val codecStage: CodecStage? = null,
    val rawTechnical: RawTechnicalInfo? = null,
    val metadataIssues: List<MetadataIssue> = emptyList(),
    val metadataPhase: MetadataIssuePhase? = null,
    val captureTimeBasis: MetadataDateBasis? = null,
    val previewSourceToken: Long? = null,
    val previewGeneration: Long? = null,
    val previewOriginGeneration: Long? = null,
    val previewScienceFingerprint: Long? = null,
    val previewKind: PreviewFrameKind? = null,
    val previewOriginKind: PreviewFrameKind? = null,
    val previewDisposition: PreviewDisposition? = null,
    val previewNativeCompleted: Boolean? = null,
    val previewSuppressed: Long? = null,
    val previewObservedElapsedMs: Long? = null,
) {
    internal fun fields(): Map<String, String> = typedFields().mapValues { it.value.toString() }

    /** Keep primitive types until JSON output; adding an enum cannot create bare JSON tokens. */
    internal fun typedFields(): Map<String, Any> = linkedMapOf<String, Any>().apply {
        fun integer(key: String, value: Long?, max: Long, min: Long = 0) { if (value != null && value in min..max) put(key, value) }
        fun decimal(key: String, value: Double?, min: Double, max: Double) {
            if (value != null && value.isFinite() && value in min..max) put(key, value)
        }
        integer("previewSourceToken",previewSourceToken,Long.MAX_VALUE,1)
        integer("previewGeneration",previewGeneration,Long.MAX_VALUE,1)
        integer("previewOriginGeneration",previewOriginGeneration,Long.MAX_VALUE,1)
        integer("previewScienceFingerprint",previewScienceFingerprint,Long.MAX_VALUE)
        integer("previewSuppressed",previewSuppressed,1_000_000)
        integer("previewObservedElapsedMs",previewObservedElapsedMs,Long.MAX_VALUE)
        previewKind?.let{put("previewKind",it.name)}
        previewOriginKind?.let{put("previewOriginKind",it.name)}
        previewDisposition?.let{put("previewDisposition",it.name)}
        previewNativeCompleted?.let{put("previewNativeCompleted",it)}
        integer("width", width?.toLong(), 30_000, 1); integer("height", height?.toLong(), 30_000, 1)
        integer("durationMs", durationMs, 7L * 24 * 60 * 60 * 1000)
        integer("javaUsedBytes", javaUsedBytes, 1L shl 40); integer("javaMaxBytes", javaMaxBytes, 1L shl 40)
        integer("nativeAllocatedBytes", nativeAllocatedBytes, 1L shl 40)
        integer("completedUnits", completedUnits, 1_800_000_000L); integer("totalUnits", totalUnits, 1_800_000_000L)
        integer("cleanupRemoved", cleanupRemoved, 1_000_000); integer("cleanupUnresolved", cleanupUnresolved, 1_000_000)
        integer("memoryBudgetMiB", memoryBudgetMiB?.toLong(), 512, 128)
        integer("estimatedWorkingBytes", estimatedWorkingBytes, 1L shl 40)
        film?.let { put("film", it.id) }; paper?.let { put("paper", it.id) }
        decimal("meteredEv", meteredEv, -64.0, 64.0)
        decimal("brightness", brightness, -2.0, 2.0); decimal("filmExposure", filmExposure, -4.0, 4.0)
        decimal("contrast", contrast, 0.5, 1.5); decimal("warmth", warmth, -1.0, 1.0); decimal("saturation", saturation, 0.0, 2.0)
        decimal("grain", grain, 0.0, 2.0); decimal("grainSize", grainSize, 0.25, 3.0)
        decimal("halation", halation, 0.0, 2.0); decimal("halationSize", halationSize, 0.25, 3.0)
        diffusionFilter?.let{put("diffusionFilter",it.id)}
        decimal("diffusionStrength",diffusionStrength,0.0,2.0);decimal("diffusionSize",diffusionSize,0.25,2.0)
        decimal("printYellow",printYellow,-30.0,30.0);decimal("printMagenta",printMagenta,-30.0,30.0)
        decimal("preflash",preflash,0.0,0.2)
        decimal("scanSharpenAmount",scanSharpenAmount,0.0,2.0);decimal("scanSharpenRadius",scanSharpenRadius,0.2,3.0)
        decimal("filmFormatMm",filmFormatMm,8.0,120.0)
        decimal("lensBlurUm",lensBlurUm,0.0,100.0)
        decimal("scanBlur",scanBlur,0.0,3.0)
        decimal("scatterAmount",scatterAmount,0.0,2.0)
        decimal("scatterSize",scatterSize,0.25,3.0)
        integer("halationBounces",halationBounces,5L,1L)
        decimal("halationDecay",halationDecay,0.0,1.0)
        halationNormalize?.let{put("halationNormalize",it)}
        decimal("dirAmount",dirAmount,0.0,2.0)
        decimal("dirDiffusionUm",dirDiffusionUm,0.0,100.0)
        decimal("dirSameLayer",dirSameLayer,0.0,2.0)
        decimal("dirInterLayer",dirInterLayer,0.0,2.0)
        grainSublayers?.let{put("grainSublayers",it)}
        integer("grainLayers",grainLayers,4L,1L)
        decimal("grainBlur",grainBlur,0.0,2.0)
        decimal("grainDyeCloud",grainDyeCloud,0.0,3.0)
        decimal("grainUniformity",grainUniformity,0.5,1.0)
        outputRoute?.let{put("outputRoute",it.id)}
        useGpu?.let{put("useGpu",it)}
        outputColorSpace?.let{put("outputColorSpace",it.id)}
        outputGamut?.let{put("outputGamut",it)}
        decimal("printExposureEv",printExposureEv,-4.0,4.0)
        printIlluminant?.let{put("printIlluminant",it.id)}
        printCompensation?.let{put("printCompensation",it)}
        normalizePrintExposure?.let{put("normalizePrintExposure",it)}
        decimal("preflashYellow",preflashYellow,-30.0,30.0)
        decimal("preflashMagenta",preflashMagenta,-30.0,30.0)
        scanWhiteCorrection?.let{put("scanWhiteCorrection",it)}
        scanBlackCorrection?.let{put("scanBlackCorrection",it)}
        decimal("scanWhiteLevel",scanWhiteLevel,0.5,1.0)
        decimal("scanBlackLevel",scanBlackLevel,0.0,0.2)
        autoExposure?.let{put("autoExposure",it)}
        metering?.let{put("metering",it.id)}
        decimal("highlightBoostEv",highlightBoostEv,0.0,3.0)
        decimal("highlightBoostRange",highlightBoostRange,0.0,1.0)
        decimal("highlightProtectEv",highlightProtectEv,0.0,4.0)
        decimal("scanGlarePercent",scanGlarePercent,0.0,5.0)
        decimal("scanGlareRoughness",scanGlareRoughness,0.0,1.0)
        decimal("scanGlareBlur",scanGlareBlur,0.0,20.0)
        decimal("grainClumpSizeUm",grainClumpSizeUm,0.0,100.0)
        decimal("grainClumpStrengthNm",grainClumpStrengthNm,0.0,1000.0)
        printDiffusionFilter?.let{put("printDiffusionFilter",it.id)}
        decimal("printDiffusionStrength",printDiffusionStrength,0.0,2.0)
        decimal("printDiffusionSize",printDiffusionSize,0.25,2.0)
        neutralFiltersFromDatabase?.let{put("neutralFiltersFromDatabase",it)}
        decimal("neutralCyan",neutralCyan,0.0,200.0)
        decimal("neutralMagenta",neutralMagenta,0.0,200.0)
        decimal("neutralYellow",neutralYellow,0.0,200.0)
        decimal("filmGamma",filmGamma,0.5,2.0)
        decimal("paperGamma",paperGamma,0.5,2.0)
        decimal("paperGammaFast",paperGammaFast,0.5,2.0)
        decimal("paperGammaSlow",paperGammaSlow,0.5,2.0)
        decimal("paperGammaRed",paperGammaRed,0.5,2.0)
        decimal("paperGammaGreen",paperGammaGreen,0.5,2.0)
        decimal("paperGammaBlue",paperGammaBlue,0.5,2.0)
        decimal("paperDeveloperExhaustion",paperDeveloperExhaustion,0.0,1.0)
        decimal("filmDevelopmentTime",filmDevelopmentTime,0.0,12.0)
        decimal("paperDevelopmentTime",paperDevelopmentTime,0.0,9.0)
        decimal("grainRedScale",grainRedScale,0.25,3.0)
        decimal("grainGreenScale",grainGreenScale,0.25,3.0)
        decimal("grainBlueScale",grainBlueScale,0.25,3.0)
        halationColor?.let{put("halationColor",it.id)}
        exportPoolStatus?.let { put("exportPoolStatus", it.id) }
        integer("exportWorkers", exportWorkers, 4)
        integer("exportWorkerCap", exportWorkerCap, 4)
        integer("exportInteractiveWaits", exportInteractiveWaits, 1_800_000_000)
        integer("exportInteractiveSerialChunks", exportInteractiveSerialChunks, 1_800_000_000)
        decimal("exportAdmissionWaitMs", exportAdmissionWaitMs, 0.0, 604_800_000.0)
        integer("exportParallelChunks", exportParallelChunks, 1_800_000_000)
        integer("exportSerialChunks", exportSerialChunks, 1_800_000_000)
        decimal("exportInteractiveWaitMs", exportInteractiveWaitMs, 0.0, 604_800_000.0)
        gpuRequested?.let{put("gpuRequested",it)}
        gpuActive?.let{put("gpuActive",it)}
        gpuBackend?.let{put("gpuBackend",it.id)}
        gpuStage?.let{put("gpuStage",it.id)}
        integer("gpuAdapterModel",gpuAdapterModel?.toLong(),9999, 100)
        integer("gpuBatches",gpuBatches,1_800_000_000)
        integer("gpuFallbacks",gpuFallbacks,1_800_000_000)
        gpuReason?.let{put("gpuReason",it.id)}
        gpuSelfTest?.let{put("gpuSelfTest",it)}
        integer("seed", seed, 0xffffffffL)
        previousOperation?.let { put("previousOperation", it.name) }
        nativePhase?.let { put("nativePhase", it.id) }
        integer("nativePass", nativePass, 1_000_000, 1)
        nativeEngineCode?.let { put("nativeEngineCode", it.name) }
        nativeEngineStage?.let { put("nativeEngineStage", it.id) }
        codecCode?.let { put("codecCode", it.name) }
        codecStage?.let { put("codecStage", it.id) }
        rawTechnical?.let { putAll(it.fields()) }
        captureTimeBasis?.let{put("captureTimeBasis",it.name)}
        MetadataIssues.encode(metadataIssues).takeIf{it.isNotEmpty()}?.let{put("metadataIssues",it);metadataPhase?.let{phase->put("metadataPhase",phase.name)}}
    }

    internal companion object {
        /** Disk input is untrusted too: revalidate every key/value against the same typed schema. */
        fun parse(fields: Map<String, String>): SafeInfo = SafeInfo(
            nativeEngineCode = fields["nativeEngineCode"]?.let(NativeEngineCode::fromId),
            nativeEngineStage = fields["nativeEngineStage"]?.let(NativeEngineStage::fromId),
            codecCode = fields["codecCode"]?.let(CodecErrorCode::fromId),
            codecStage = fields["codecStage"]?.let(CodecStage::fromId),
            rawTechnical = RawTechnicalInfo.fromFields(fields),
            metadataIssues = MetadataIssues.parse(fields["metadataIssues"].orEmpty()),
            metadataPhase = MetadataIssuePhase.entries.firstOrNull{it.name==fields["metadataPhase"]},
            captureTimeBasis = MetadataDateBasis.entries.firstOrNull{it.name==fields["captureTimeBasis"]},
            previewSourceToken=fields["previewSourceToken"]?.toLongOrNull(),
            previewGeneration=fields["previewGeneration"]?.toLongOrNull(),
            previewOriginGeneration=fields["previewOriginGeneration"]?.toLongOrNull(),
            previewScienceFingerprint=fields["previewScienceFingerprint"]?.toLongOrNull(),
            previewSuppressed=fields["previewSuppressed"]?.toLongOrNull(),
            previewObservedElapsedMs=fields["previewObservedElapsedMs"]?.toLongOrNull(),
            previewKind=fields["previewKind"]?.let{v->PreviewFrameKind.entries.firstOrNull{it.name==v}},
            previewOriginKind=fields["previewOriginKind"]?.let{v->PreviewFrameKind.entries.firstOrNull{it.name==v}},
            previewDisposition=fields["previewDisposition"]?.let{v->PreviewDisposition.entries.firstOrNull{it.name==v}},
            previewNativeCompleted=fields["previewNativeCompleted"]?.toBooleanStrictOrNull(),
            width = fields["width"]?.toIntOrNull(), height = fields["height"]?.toIntOrNull(),
            durationMs = fields["durationMs"]?.toLongOrNull(), javaUsedBytes = fields["javaUsedBytes"]?.toLongOrNull(),
            javaMaxBytes = fields["javaMaxBytes"]?.toLongOrNull(), nativeAllocatedBytes = fields["nativeAllocatedBytes"]?.toLongOrNull(),
            completedUnits = fields["completedUnits"]?.toLongOrNull(), totalUnits = fields["totalUnits"]?.toLongOrNull(),
            cleanupRemoved = fields["cleanupRemoved"]?.toLongOrNull(), cleanupUnresolved = fields["cleanupUnresolved"]?.toLongOrNull(),
            memoryBudgetMiB = fields["memoryBudgetMiB"]?.toIntOrNull(), estimatedWorkingBytes = fields["estimatedWorkingBytes"]?.toLongOrNull(),
            film = fields["film"]?.let(FilmProfile::fromId), paper = fields["paper"]?.let(PaperProfile::fromId),
            meteredEv = fields["meteredEv"]?.toDoubleOrNull(),
            brightness = fields["brightness"]?.toDoubleOrNull(), filmExposure = fields["filmExposure"]?.toDoubleOrNull(),
            contrast = fields["contrast"]?.toDoubleOrNull(), warmth = fields["warmth"]?.toDoubleOrNull(), saturation = fields["saturation"]?.toDoubleOrNull(),
            grain = fields["grain"]?.toDoubleOrNull(), grainSize = fields["grainSize"]?.toDoubleOrNull(),
            halation = fields["halation"]?.toDoubleOrNull(), halationSize = fields["halationSize"]?.toDoubleOrNull(),
            diffusionFilter=fields["diffusionFilter"]?.let(DiffusionProfile::fromId),
            diffusionStrength=fields["diffusionStrength"]?.toDoubleOrNull(), diffusionSize=fields["diffusionSize"]?.toDoubleOrNull(),
            printYellow=fields["printYellow"]?.toDoubleOrNull(), printMagenta=fields["printMagenta"]?.toDoubleOrNull(), preflash=fields["preflash"]?.toDoubleOrNull(),
            scanSharpenAmount=fields["scanSharpenAmount"]?.toDoubleOrNull(), scanSharpenRadius=fields["scanSharpenRadius"]?.toDoubleOrNull(),
            nativePhase = fields["nativePhase"]?.let(NativePhase::fromId), nativePass = fields["nativePass"]?.toLongOrNull(),
            filmFormatMm=fields["filmFormatMm"]?.toDoubleOrNull(),
            lensBlurUm=fields["lensBlurUm"]?.toDoubleOrNull(),
            scanBlur=fields["scanBlur"]?.toDoubleOrNull(),
            scatterAmount=fields["scatterAmount"]?.toDoubleOrNull(),
            scatterSize=fields["scatterSize"]?.toDoubleOrNull(),
            halationBounces=fields["halationBounces"]?.toLongOrNull(),
            halationDecay=fields["halationDecay"]?.toDoubleOrNull(),
            halationNormalize=fields["halationNormalize"]?.toBooleanStrictOrNull(),
            dirAmount=fields["dirAmount"]?.toDoubleOrNull(),
            dirDiffusionUm=fields["dirDiffusionUm"]?.toDoubleOrNull(),
            dirSameLayer=fields["dirSameLayer"]?.toDoubleOrNull(),
            dirInterLayer=fields["dirInterLayer"]?.toDoubleOrNull(),
            grainSublayers=fields["grainSublayers"]?.toBooleanStrictOrNull(),
            grainLayers=fields["grainLayers"]?.toLongOrNull(),
            grainBlur=fields["grainBlur"]?.toDoubleOrNull(),
            grainDyeCloud=fields["grainDyeCloud"]?.toDoubleOrNull(),
            grainUniformity=fields["grainUniformity"]?.toDoubleOrNull(),
            outputRoute=fields["outputRoute"]?.let(OutputRoute::fromId),
            useGpu=fields["useGpu"]?.toBooleanStrictOrNull(),
            outputColorSpace=fields["outputColorSpace"]?.let(OutputColorProfile::fromId),
            outputGamut=fields["outputGamut"]?.toBooleanStrictOrNull(),
            printExposureEv=fields["printExposureEv"]?.toDoubleOrNull(),
            printIlluminant=fields["printIlluminant"]?.let(PrintIlluminant::fromId),
            printCompensation=fields["printCompensation"]?.toBooleanStrictOrNull(),
            normalizePrintExposure=fields["normalizePrintExposure"]?.toBooleanStrictOrNull(),
            preflashYellow=fields["preflashYellow"]?.toDoubleOrNull(),
            preflashMagenta=fields["preflashMagenta"]?.toDoubleOrNull(),
            scanWhiteCorrection=fields["scanWhiteCorrection"]?.toBooleanStrictOrNull(),
            scanBlackCorrection=fields["scanBlackCorrection"]?.toBooleanStrictOrNull(),
            scanWhiteLevel=fields["scanWhiteLevel"]?.toDoubleOrNull(),
            scanBlackLevel=fields["scanBlackLevel"]?.toDoubleOrNull(),
            autoExposure=fields["autoExposure"]?.toBooleanStrictOrNull(),
            metering=fields["metering"]?.let(MeteringMode::fromId),
            highlightBoostEv=fields["highlightBoostEv"]?.toDoubleOrNull(),
            highlightBoostRange=fields["highlightBoostRange"]?.toDoubleOrNull(),
            highlightProtectEv=fields["highlightProtectEv"]?.toDoubleOrNull(),
            scanGlarePercent=fields["scanGlarePercent"]?.toDoubleOrNull(),
            scanGlareRoughness=fields["scanGlareRoughness"]?.toDoubleOrNull(),
            scanGlareBlur=fields["scanGlareBlur"]?.toDoubleOrNull(),
            grainClumpSizeUm=fields["grainClumpSizeUm"]?.toDoubleOrNull(),
            grainClumpStrengthNm=fields["grainClumpStrengthNm"]?.toDoubleOrNull(),
            printDiffusionFilter=fields["printDiffusionFilter"]?.let(DiffusionProfile::fromId),
            printDiffusionStrength=fields["printDiffusionStrength"]?.toDoubleOrNull(),
            printDiffusionSize=fields["printDiffusionSize"]?.toDoubleOrNull(),
            neutralFiltersFromDatabase=fields["neutralFiltersFromDatabase"]?.toBooleanStrictOrNull(),
            neutralCyan=fields["neutralCyan"]?.toDoubleOrNull(),
            neutralMagenta=fields["neutralMagenta"]?.toDoubleOrNull(),
            neutralYellow=fields["neutralYellow"]?.toDoubleOrNull(),
            filmGamma=fields["filmGamma"]?.toDoubleOrNull(),
            paperGamma=fields["paperGamma"]?.toDoubleOrNull(),
            paperGammaFast=fields["paperGammaFast"]?.toDoubleOrNull(),
            paperGammaSlow=fields["paperGammaSlow"]?.toDoubleOrNull(),
            paperGammaRed=fields["paperGammaRed"]?.toDoubleOrNull(),
            paperGammaGreen=fields["paperGammaGreen"]?.toDoubleOrNull(),
            paperGammaBlue=fields["paperGammaBlue"]?.toDoubleOrNull(),
            paperDeveloperExhaustion=fields["paperDeveloperExhaustion"]?.toDoubleOrNull(),
            filmDevelopmentTime=fields["filmDevelopmentTime"]?.toDoubleOrNull(),
            paperDevelopmentTime=fields["paperDevelopmentTime"]?.toDoubleOrNull(),
            grainRedScale=fields["grainRedScale"]?.toDoubleOrNull(),
            grainGreenScale=fields["grainGreenScale"]?.toDoubleOrNull(),
            grainBlueScale=fields["grainBlueScale"]?.toDoubleOrNull(),
            halationColor=fields["halationColor"]?.let(HalationColor::fromId),
            exportPoolStatus=fields["exportPoolStatus"]?.let { id -> ExportPoolStatus.entries.firstOrNull { it.id == id } },
            exportWorkers=fields["exportWorkers"]?.toLongOrNull(),
            exportWorkerCap=fields["exportWorkerCap"]?.toLongOrNull(),
            exportInteractiveWaits=fields["exportInteractiveWaits"]?.toLongOrNull(),
            exportInteractiveSerialChunks=fields["exportInteractiveSerialChunks"]?.toLongOrNull(),
            exportAdmissionWaitMs=fields["exportAdmissionWaitMs"]?.toDoubleOrNull(),
            exportParallelChunks=fields["exportParallelChunks"]?.toLongOrNull(),
            exportSerialChunks=fields["exportSerialChunks"]?.toLongOrNull(),
            exportInteractiveWaitMs=fields["exportInteractiveWaitMs"]?.toDoubleOrNull(),
            gpuRequested=fields["gpuRequested"]?.toBooleanStrictOrNull(),
            gpuActive=fields["gpuActive"]?.toBooleanStrictOrNull(),
            gpuBackend=fields["gpuBackend"]?.let{id->GpuBackend.entries.firstOrNull{it.id==id}},
            gpuStage=fields["gpuStage"]?.let{id->GpuStage.entries.firstOrNull{it.id==id}},
            gpuAdapterModel=fields["gpuAdapterModel"]?.toIntOrNull(),
            gpuBatches=fields["gpuBatches"]?.toLongOrNull(),
            gpuFallbacks=fields["gpuFallbacks"]?.toLongOrNull(),
            gpuReason=fields["gpuReason"]?.let{id->GpuReason.entries.firstOrNull{it.id==id}},
            gpuSelfTest=fields["gpuSelfTest"]?.toBooleanStrictOrNull(),
            seed = fields["seed"]?.toLongOrNull(), previousOperation = fields["previousOperation"]?.let { runCatching { Operation.valueOf(it) }.getOrNull() },
        )
    }
}
