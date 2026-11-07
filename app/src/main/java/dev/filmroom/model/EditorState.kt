package dev.filmroom.model

import org.json.JSONObject

/** Only supported middle-level controls cross the native boundary. */
data class EditorState(
    val filmStock: String = "kodak_portra_400",
    val paperStock: String = "kodak_portra_endura",
    val brightness: Float = 0f,
    val filmExposure: Float = 0f,
    val contrast: Float = 1f,
    val warmth: Float = 0f,
    val saturation: Float = 1f,
    val grain: Float = 1f,
    val grainSize: Float = 1f,
    val halation: Float = 1f,
    val halationSize: Float = 1f,
    val diffusionFilter: String = "off",
    val diffusionStrength: Float = 0.5f,
    val diffusionSize: Float = 1f,
    val printYellow: Float = 0f,
    val printMagenta: Float = 0f,
    val preflash: Float = 0f,
    val scanSharpenAmount: Float = 0f,
    val scanSharpenRadius: Float = 0.7f,
    val filmFormatMm: Float = 35f,
    val lensBlurUm: Float = 0f,
    val scanBlur: Float = 0f,
    val scatterAmount: Float = 1f,
    val scatterSize: Float = 1f,
    val halationBounces: Int = 3,
    val halationDecay: Float = 0.5f,
    val halationNormalize: Boolean = true,
    val dirAmount: Float = 1f,
    val dirDiffusionUm: Float = 20f,
    val dirSameLayer: Float = 1f,
    val dirInterLayer: Float = 1f,
    val grainSublayers: Boolean = true,
    val grainLayers: Int = 1,
    val grainBlur: Float = 0.65f,
    val grainDyeCloud: Float = 1f,
    val grainUniformity: Float = 1f,
    val outputRoute: String = "print",
    val useGpu: Boolean = true,
    val outputColorSpace: String = "sRGB",
    val outputGamut: Boolean = true,
    val printExposureEv: Float = 0f,
    val printIlluminant: String = "TH-KG3",
    val printCompensation: Boolean = true,
    val normalizePrintExposure: Boolean = true,
    val preflashYellow: Float = 0f,
    val preflashMagenta: Float = 0f,
    val scanWhiteCorrection: Boolean = false,
    val scanBlackCorrection: Boolean = false,
    val scanWhiteLevel: Float = 0.98f,
    val scanBlackLevel: Float = 0.01f,
    val autoExposure: Boolean = false,
    val metering: String = "center_weighted",
    val highlightBoostEv: Float = 0f,
    val highlightBoostRange: Float = 0.3f,
    val highlightProtectEv: Float = 4f,
    val scanGlarePercent: Float = 0f,
    val scanGlareRoughness: Float = 0.7f,
    val scanGlareBlur: Float = 0.5f,
    val grainClumpSizeUm: Float = 0.2f,
    val grainRedScale: Float = 1f,
    val grainGreenScale: Float = 1f,
    val grainBlueScale: Float = 1f,
    val halationColor: String = "stock",
    val grainClumpStrengthNm: Float = 30f,
    val printDiffusionFilter: String = "off",
    val printDiffusionStrength: Float = 0.5f,
    val printDiffusionSize: Float = 1f,
    val neutralFiltersFromDatabase: Boolean = true,
    val neutralCyan: Float = 0f,
    val neutralMagenta: Float = 65f,
    val neutralYellow: Float = 55f,
    val filmGamma: Float = 1f,
    val paperGamma: Float = 1f,
    val paperGammaFast: Float = 1f,
    val paperGammaSlow: Float = 1f,
    val paperGammaRed: Float = 1f,
    val paperGammaGreen: Float = 1f,
    val paperGammaBlue: Float = 1f,
    val paperDeveloperExhaustion: Float = 0f,
    val filmDevelopmentTime: Float = 0f,
    val paperDevelopmentTime: Float = 0f,
    val imprint: dev.filmroom.imprint.ImprintSettings = dev.filmroom.imprint.ImprintSettings(),
    val filmBorder: Boolean = false, // reset defaults OFF
    val filmBorderStage: Int = 0, // 0 frozen legacy post-composite, 1 source/edge pre-imaging
    val mediumFormat: dev.filmroom.border.MediumFormat? = null, // presentation only; null retains 135
    val mediumPortrait: Boolean = false, // explicit even for a square aperture
    val negativeWhiteHoles: Boolean = true, // presentation preference; reversal output remains white
    val composition: CompositionTransform = CompositionTransform(),
    val crop: CropSettings = CropSettings(),
    // null follows each film's default; explicit choice survives a forced reversal/direct route.
    val preferredOutputRoute: String? = null,
) {
    init { require(filmBorderStage in 0..1) }
    val preImagingBorder get()=filmBorder && filmBorderStage==1
    val isMonochromeFilm get()=filmStock in FilmProfileCapabilities.monochromeFilms
    val isMonochromePrint get()=paperStock in FilmProfileCapabilities.monochromePrints
    val neutralDatabaseSupported get()=(!isMonochromeFilm && !isMonochromePrint) || BwNeutralFilterEntries.contains(filmStock,paperStock,printIlluminant)
    val effectiveNeutralFiltersFromDatabase get()=neutralFiltersFromDatabase && neutralDatabaseSupported
    val frameStock get()=dev.filmroom.border.BorderCatalog.frameStock(filmStock,paperStock,outputRoute)
    internal val frameFormats get()=FrameFormatCapabilities(
        dev.filmroom.border.BorderCatalog.available(frameStock,null),
        dev.filmroom.border.BorderCatalog.PROTOTYPE_ENABLED && dev.filmroom.border.MediumFormatStocks.available(frameStock))
    fun selectFilmBorder(enabled:Boolean):EditorState {
        if(!enabled)return copy(filmBorder=false)
        val formats=frameFormats
        if(!formats.available)return copy(filmBorder=false)
        val selected=formats.formatForEnable(effectiveMediumFormat)
        val framed=copy(filmBorder=true,filmBorderStage=1)
        return if(selected==effectiveMediumFormat)framed else framed.selectMediumFormat(selected,mediumPortrait)
    }
    val effectiveMediumFormat get()=mediumFormat?.takeIf{dev.filmroom.border.MediumFormatStocks.available(frameStock)}
    val effectiveMediumPortrait get()=effectiveMediumFormat!=null && mediumPortrait
    val negativeWhiteHolesSupported get()=frameFormats.has135 && effectiveMediumFormat==null && (outputRoute=="print" || outputRoute=="direct") && dev.filmroom.border.FrameProfileCatalog.kinds[frameStock]==dev.filmroom.border.FrameAppearanceContext.FilmKind.NEGATIVE
    fun controlSupported(id:String):Boolean=when(id){
        "neutralFiltersFromDatabase"->neutralDatabaseSupported
        "filmDevelopmentTime"->filmStock=="kodak_doublex"
        "halationBounces","halationDecay"->filmStock!="cinestill_800t"
        "paperDevelopmentTime"->paperStock=="kodak_2302" && outputRoute=="print"
        "saturation","dirInterLayer","grainRedScale","grainGreenScale","grainBlueScale","halationColor"->!isMonochromeFilm
        "warmth","paperGammaFast","paperGammaSlow","paperGammaRed","paperGammaGreen","paperGammaBlue","paperDeveloperExhaustion"->!isMonochromePrint
        else->true
    }
    val isReversalFilm get()=dev.filmroom.border.FrameProfileCatalog.kinds[filmStock]==dev.filmroom.border.FrameAppearanceContext.FilmKind.REVERSAL
    val scannerCorrectionSupported get()=outputRoute!="direct" || isReversalFilm
    val effectiveScanWhiteCorrection get()=scanWhiteCorrection && scannerCorrectionSupported
    val effectiveScanBlackCorrection get()=scanBlackCorrection && scannerCorrectionSupported
    fun selectOutputRoute(route:String):EditorState {
        require(route=="print" || route=="direct")
        return copy(outputRoute=if(isReversalFilm)"direct" else route,preferredOutputRoute=route)
    }
    fun selectFilm(id:String,defaultPaper:String,defaultRoute:String,previousDefaultPaper:String?):EditorState {
        // Legacy negative/direct states can only have arisen from a manual route choice.
        val preference=preferredOutputRoute ?: if(dev.filmroom.border.FrameProfileCatalog.kinds[filmStock]==dev.filmroom.border.FrameAppearanceContext.FilmKind.NEGATIVE && outputRoute=="direct")"direct" else null
        val reversal=dev.filmroom.border.FrameProfileCatalog.kinds[id]==dev.filmroom.border.FrameAppearanceContext.FilmKind.REVERSAL
        val selectedMedium=mediumFormat?.takeIf{dev.filmroom.border.MediumFormatStocks.available(id)}
        val selected=copy(filmStock=id,mediumFormat=selectedMedium,
            mediumPortrait=mediumPortrait && dev.filmroom.border.MediumFormatStocks.available(id),filmFormatMm=if(filmFormatMm==(if(filmStock=="kodak_trix")8f else 35f)) {if(id=="kodak_trix")8f else 35f}else filmFormatMm,paperStock=if(paperStock==previousDefaultPaper)defaultPaper else paperStock,
            outputRoute=if(reversal)"direct" else preference ?: defaultRoute,preferredOutputRoute=preference)
        return if(filmBorder)selected.selectFilmBorder(true)else selected
    }
    /** Restore processing controls while leaving the user's composition and output preferences intact. */
    fun processingDefaults(defaultPaper:String,defaultRoute:String)=EditorState(
        filmStock=filmStock,paperStock=defaultPaper,outputRoute=defaultRoute,filmFormatMm=if(filmStock=="kodak_trix")8f else 35f,
        imprint=imprint,filmBorder=filmBorder,filmBorderStage=filmBorderStage,mediumFormat=mediumFormat,mediumPortrait=mediumPortrait,negativeWhiteHoles=negativeWhiteHoles,composition=composition,crop=crop,
        useGpu=useGpu,outputColorSpace=outputColorSpace,outputGamut=outputGamut,
    )
    /** Only the active editor session is upgraded. Saved recipes and frozen queues use fromJson unchanged. */
    fun forLiveSessionRestore():EditorState = if(filmBorder && filmBorderStage==0)copy(filmBorderStage=1)else this

    /** Switching the output frame does not silently alter grain/optical filmFormatMm. */
    fun selectMediumFormat(format:dev.filmroom.border.MediumFormat?,portrait:Boolean):EditorState {
        require(format in frameFormats.formats)
        return copy(mediumFormat=format,mediumPortrait=format!=null && portrait,
            composition=composition.copy(targetAspect=dev.filmroom.border.BorderCatalog.frameAspect(frameStock,portrait,format)))
    }
    fun presentationTransform(w:Int,h:Int):CompositionTransform = presentationTransformForFrame(w,h,frameStock)
    /** Frozen legacy queue validation may use its explicit historical frame, never current selection. */
    internal fun presentationTransformForFrame(w:Int,h:Int,frameStock:String):CompositionTransform {
        if(!filmBorder)return crop.effectiveTransform(w,h)
        effectiveMediumFormat?.let { format ->
            val aspect=dev.filmroom.border.BorderCatalog.frameAspect(frameStock,mediumPortrait,format)
            return composition.copy(targetAspect=aspect).clamped(w,h)
        }
        val bw=frameStock in setOf("kodak_doublex","kodak_trix","kodak_2302")
        // Keep legacy implicit colour orientation exactly; explicit orientation survives
        // stock switches while its physical aperture ratio follows the selected frame.
        val aspect=if(!bw && composition.targetAspect==null)null else dev.filmroom.border.BorderCatalog.frameAspect(frameStock,composition.targetAspect?.let{it<1.0} ?: (h>w))
        return composition.copy(targetAspect=aspect).clamped(w,h)
    }
    fun withPresentationTransform(value:CompositionTransform)=if(filmBorder)copy(composition=value) else copy(crop=crop.copy(transform=value))
    fun json(mode: String = "preview", includePresentation: Boolean = true) = JSONObject().apply {
        if(includePresentation){
            put("imprint",imprint.json())
            put("filmBorder",filmBorder);put("filmBorderStage",filmBorderStage)
            mediumFormat?.let{put("mediumFormat",it.name);put("mediumPortrait",mediumPortrait)}
            put("negativeWhiteHoles",negativeWhiteHoles)
            put("composition",composition.json())
            put("crop",crop.json())
            put("preferredOutputRoute",preferredOutputRoute ?: JSONObject.NULL)
        }
        put("filmDevelopmentTime",filmDevelopmentTime);put("paperDevelopmentTime",paperDevelopmentTime)
        put("schemaVersion", 1); put("filmStock", filmStock); put("paperStock", paperStock)
        put("brightnessEv", brightness); put("filmExposureEv", filmExposure)
        put("contrast", contrast); put("warmth", warmth); put("saturation", saturation)
        put("grainAmount", grain); put("grainSize", grainSize)
        put("halationAmount", halation); put("halationSize", halationSize)
        put("diffusionFilter", diffusionFilter); put("diffusionStrength", diffusionStrength); put("diffusionSize", diffusionSize)
        put("printYellow", printYellow); put("printMagenta", printMagenta); put("preflash", preflash)
        put("scanSharpenAmount", scanSharpenAmount); put("scanSharpenRadius", scanSharpenRadius)
        put("filmFormatMm", filmFormatMm)
        put("lensBlurUm", lensBlurUm)
        put("scanBlur", scanBlur)
        put("scatterAmount", scatterAmount)
        put("scatterSize", scatterSize)
        put("halationBounces", halationBounces)
        put("halationDecay", halationDecay)
        put("halationNormalize", halationNormalize)
        put("dirAmount", dirAmount)
        put("dirDiffusionUm", dirDiffusionUm)
        put("dirSameLayer", dirSameLayer)
        put("dirInterLayer", dirInterLayer)
        put("grainSublayers", grainSublayers)
        put("grainLayers", grainLayers)
        put("grainBlur", grainBlur)
        put("grainDyeCloud", grainDyeCloud)
        put("grainUniformity", grainUniformity)
        put("outputRoute", outputRoute)
        put("useGpu", useGpu)
        put("outputColorSpace", outputColorSpace)
        put("outputGamut", outputGamut)
        put("printExposureEv", printExposureEv)
        put("printIlluminant", printIlluminant)
        put("printCompensation", printCompensation)
        put("normalizePrintExposure", normalizePrintExposure)
        put("preflashYellow", preflashYellow)
        put("preflashMagenta", preflashMagenta)
        put("scanWhiteCorrection", if(includePresentation)scanWhiteCorrection else effectiveScanWhiteCorrection)
        put("scanBlackCorrection", if(includePresentation)scanBlackCorrection else effectiveScanBlackCorrection)
        put("scanWhiteLevel", scanWhiteLevel)
        put("scanBlackLevel", scanBlackLevel)
        put("autoExposure", autoExposure)
        put("metering", metering)
        put("highlightBoostEv", highlightBoostEv)
        put("highlightBoostRange", highlightBoostRange)
        put("highlightProtectEv", highlightProtectEv)
        put("scanGlarePercent", scanGlarePercent)
        put("scanGlareRoughness", scanGlareRoughness)
        put("scanGlareBlur", scanGlareBlur)
        put("grainClumpSizeUm", grainClumpSizeUm)
        put("grainClumpStrengthNm", grainClumpStrengthNm)
        put("printDiffusionFilter", printDiffusionFilter)
        put("printDiffusionStrength", printDiffusionStrength)
        put("printDiffusionSize", printDiffusionSize)
        put("neutralFiltersFromDatabase", neutralFiltersFromDatabase)
        put("neutralCyan", neutralCyan)
        put("neutralMagenta", neutralMagenta)
        put("neutralYellow", neutralYellow)
        put("filmGamma", filmGamma)
        put("paperGamma", paperGamma)
        put("paperGammaFast", paperGammaFast)
        put("paperGammaSlow", paperGammaSlow)
        put("paperGammaRed", paperGammaRed)
        put("paperGammaGreen", paperGammaGreen)
        put("paperGammaBlue", paperGammaBlue)
        put("paperDeveloperExhaustion", paperDeveloperExhaustion)
        put("grainRedScale", grainRedScale)
        put("grainGreenScale", grainGreenScale)
        put("grainBlueScale", grainBlueScale)
        put("halationColor", halationColor)
        put("seed", 42); put("mode", mode)
    }.toString()
    companion object {
        fun fromJson(s: String): EditorState = JSONObject(s).let { j ->
            val legacyScatter=if(j.optDouble("halationAmount",1.0)>0)1.0 else 0.0
            val storedFilm=j.optString("filmStock","kodak_portra_400")
            val storedRoute=j.optString("outputRoute","print")
            val storedKind=dev.filmroom.border.FrameProfileCatalog.kinds[storedFilm]
            val preference=if(j.has("preferredOutputRoute"))j.optString("preferredOutputRoute").takeIf{it=="print" || it=="direct"}
                else when {
                    storedKind==dev.filmroom.border.FrameAppearanceContext.FilmKind.NEGATIVE && storedRoute=="direct"->"direct"
                    storedKind==dev.filmroom.border.FrameAppearanceContext.FilmKind.REVERSAL && storedRoute=="print"->"print"
                    else->null
                }
            EditorState(
                filmDevelopmentTime=j.optDouble("filmDevelopmentTime",0.0).toFloat(),paperDevelopmentTime=j.optDouble("paperDevelopmentTime",0.0).toFloat(),
                imprint=j.optJSONObject("imprint")?.let{dev.filmroom.imprint.ImprintSettings.fromJson(it)} ?: dev.filmroom.imprint.ImprintSettings(),
                filmBorder=j.optBoolean("filmBorder",false),
                filmBorderStage=if(!j.has("filmBorderStage"))0 else j.get("filmBorderStage").let{value->
                    require(value is Int || value is Long){"filmBorderStage must be an integer"}
                    val stage=(value as Number).toLong();require(stage in 0..1);stage.toInt()
                },
                mediumFormat=j.optString("mediumFormat").takeIf{it.isNotEmpty()}?.let{dev.filmroom.border.MediumFormat.valueOf(it)},
                mediumPortrait=j.optBoolean("mediumPortrait",false),
                negativeWhiteHoles=j.optBoolean("negativeWhiteHoles",true),
                composition=j.optJSONObject("composition")?.let{CompositionTransform.fromJson(it)} ?: CompositionTransform(),
                crop=j.optJSONObject("crop")?.let{CropSettings.fromJson(it)} ?: CropSettings(),
                preferredOutputRoute=preference,
                filmStock=j.optString("filmStock", "kodak_portra_400"), paperStock=j.optString("paperStock", "kodak_portra_endura"),
                brightness=j.optDouble("brightnessEv",0.0).toFloat(), filmExposure=j.optDouble("filmExposureEv",0.0).toFloat(),
                contrast=j.optDouble("contrast",1.0).toFloat(), warmth=j.optDouble("warmth",0.0).toFloat(),
                saturation=j.optDouble("saturation",1.0).toFloat(), grain=j.optDouble("grainAmount",1.0).toFloat(),
                grainSize=j.optDouble("grainSize",1.0).toFloat(), halation=j.optDouble("halationAmount",1.0).toFloat(),
                halationSize=j.optDouble("halationSize",1.0).toFloat(),
                diffusionFilter=j.optString("diffusionFilter","off"), diffusionStrength=j.optDouble("diffusionStrength",0.5).toFloat(),
                diffusionSize=j.optDouble("diffusionSize",1.0).toFloat(), printYellow=j.optDouble("printYellow",0.0).toFloat(),
                printMagenta=j.optDouble("printMagenta",0.0).toFloat(), preflash=j.optDouble("preflash",0.0).toFloat(),
                scanSharpenAmount=j.optDouble("scanSharpenAmount",0.0).toFloat(), scanSharpenRadius=j.optDouble("scanSharpenRadius",0.7).toFloat(),
                filmFormatMm=j.optDouble("filmFormatMm",35.0).toFloat(),
                lensBlurUm=j.optDouble("lensBlurUm",0.0).toFloat(),
                scanBlur=j.optDouble("scanBlur",0.0).toFloat(),
                scatterAmount=j.optDouble("scatterAmount",legacyScatter).toFloat(),
                scatterSize=j.optDouble("scatterSize",1.0).toFloat(),
                halationBounces=j.optInt("halationBounces",3),
                halationDecay=j.optDouble("halationDecay",0.5).toFloat(),
                halationNormalize=j.optBoolean("halationNormalize",true),
                dirAmount=j.optDouble("dirAmount",1.0).toFloat(),
                dirDiffusionUm=j.optDouble("dirDiffusionUm",20.0).toFloat(),
                dirSameLayer=j.optDouble("dirSameLayer",1.0).toFloat(),
                dirInterLayer=j.optDouble("dirInterLayer",1.0).toFloat(),
                grainSublayers=j.optBoolean("grainSublayers",true),
                grainLayers=j.optInt("grainLayers",1),
                grainBlur=j.optDouble("grainBlur",0.65).toFloat(),
                grainDyeCloud=j.optDouble("grainDyeCloud",1.0).toFloat(),
                grainUniformity=j.optDouble("grainUniformity",1.0).toFloat(),
                outputRoute=if(storedKind==dev.filmroom.border.FrameAppearanceContext.FilmKind.REVERSAL)"direct" else storedRoute,
                useGpu=j.optBoolean("useGpu",true),
                outputColorSpace=j.optString("outputColorSpace","sRGB"),
                outputGamut=j.optBoolean("outputGamut",true),
                printExposureEv=j.optDouble("printExposureEv",0.0).toFloat(),
                printIlluminant=j.optString("printIlluminant","TH-KG3"),
                printCompensation=j.optBoolean("printCompensation",true),
                normalizePrintExposure=j.optBoolean("normalizePrintExposure",true),
                preflashYellow=j.optDouble("preflashYellow",0.0).toFloat(),
                preflashMagenta=j.optDouble("preflashMagenta",0.0).toFloat(),
                scanWhiteCorrection=j.optBoolean("scanWhiteCorrection",false),
                scanBlackCorrection=j.optBoolean("scanBlackCorrection",false),
                scanWhiteLevel=j.optDouble("scanWhiteLevel",0.98).toFloat(),
                scanBlackLevel=j.optDouble("scanBlackLevel",0.01).toFloat(),
                autoExposure=j.optBoolean("autoExposure",false),
                metering=j.optString("metering","center_weighted"),
                highlightBoostEv=j.optDouble("highlightBoostEv",0.0).toFloat(),
                highlightBoostRange=j.optDouble("highlightBoostRange",0.3).toFloat(),
                highlightProtectEv=j.optDouble("highlightProtectEv",4.0).toFloat(),
                scanGlarePercent=j.optDouble("scanGlarePercent",0.0).toFloat(),
                scanGlareRoughness=j.optDouble("scanGlareRoughness",0.7).toFloat(),
                scanGlareBlur=j.optDouble("scanGlareBlur",0.5).toFloat(),
                grainClumpSizeUm=j.optDouble("grainClumpSizeUm",0.2).toFloat(),
                grainRedScale=j.optDouble("grainRedScale",1.0).toFloat(),
                grainGreenScale=j.optDouble("grainGreenScale",1.0).toFloat(),
                grainBlueScale=j.optDouble("grainBlueScale",1.0).toFloat(),
                halationColor=j.optString("halationColor","stock"),
                grainClumpStrengthNm=j.optDouble("grainClumpStrengthNm",30.0).toFloat(),
                printDiffusionFilter=j.optString("printDiffusionFilter","off"),
                printDiffusionStrength=j.optDouble("printDiffusionStrength",0.5).toFloat(),
                printDiffusionSize=j.optDouble("printDiffusionSize",1.0).toFloat(),
                neutralFiltersFromDatabase=j.optBoolean("neutralFiltersFromDatabase",true),
                neutralCyan=j.optDouble("neutralCyan",0.0).toFloat(),
                neutralMagenta=j.optDouble("neutralMagenta",65.0).toFloat(),
                neutralYellow=j.optDouble("neutralYellow",55.0).toFloat(),
                filmGamma=j.optDouble("filmGamma",1.0).toFloat(),
                paperGamma=j.optDouble("paperGamma",1.0).toFloat(),
                paperGammaFast=j.optDouble("paperGammaFast",1.0).toFloat(),
                paperGammaSlow=j.optDouble("paperGammaSlow",1.0).toFloat(),
                paperGammaRed=j.optDouble("paperGammaRed",1.0).toFloat(),
                paperGammaGreen=j.optDouble("paperGammaGreen",1.0).toFloat(),
                paperGammaBlue=j.optDouble("paperGammaBlue",1.0).toFloat(),
                paperDeveloperExhaustion=j.optDouble("paperDeveloperExhaustion",0.0).toFloat()
            )
        }
    }
}

class EditHistory(initial: EditorState = EditorState()) {
    var current: EditorState = initial; private set
    private val previous = ArrayDeque<EditorState>()
    private val future = ArrayDeque<EditorState>()
    val canUndo get() = previous.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun update(next: EditorState) {
        if (next == current) return
        previous.addLast(current); if (previous.size > 50) previous.removeFirst()
        current = next; future.clear()
    }
    fun undo() { if(canUndo) { future.addLast(current); current=previous.removeLast() } }
    fun redo() { if(canRedo) { previous.addLast(current); current=future.removeLast() } }
}
