package dev.filmroom

import android.os.SystemClock
import dev.filmroom.diagnostics.*
import dev.filmroom.engine.EngineException
import dev.filmroom.engine.EngineFailure
import dev.filmroom.io.PhotoException
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*

/** Records stage transitions only. No slider ticks, file identifiers, or exception text. */
class OperationTrace private constructor(val id:OperationId) {
    @Volatile private var commitReached=false
    @Volatile private var previewStage:PreviewFailureStage?=null
    /** In-memory breadcrumb; persisted with the terminal failure, never per slider tick. */
    fun previewStage(stage:PreviewFailureStage){previewStage=stage}
    fun commitState(){commitReached=true}
    suspend fun mark(stage:Stage,info:SafeInfo=SafeInfo(),error:ErrorCode?=null)=withContext(Dispatchers.IO){Diagnostics.event(id,stage,info,error)}
    /** Only from IO completion callbacks; no main-thread filesystem write. */
    fun committed(){commitReached=true;Diagnostics.event(id,Stage.MEDIA_COMMITTED)}
    companion object {
        suspend fun <T> run(operation:Operation,info:SafeInfo=SafeInfo(),work:suspend (OperationTrace)->T):T {
            val started=SystemClock.elapsedRealtime()
            var captured:OperationId?=null
            var active:OperationTrace?=null
            try {
                val correlated=if(operation==Operation.PREVIEW_RENDER)currentCoroutineContext()[PreviewDiagnosticContext]?.enrich(info) ?: info else info
                withContext(Dispatchers.IO){captured=Diagnostics.begin(operation,correlated)}
                val id=checkNotNull(captured)
                val trace=OperationTrace(id);active=trace
                if(operation==Operation.PREVIEW_RENDER)trace.previewStage(PreviewFailureStage.START)
                val result=work(trace)
                withContext(NonCancellable+Dispatchers.IO){Diagnostics.end(id,Outcome.SUCCESS,Diagnostics.memoryInfo().copy(durationMs=(SystemClock.elapsedRealtime()-started).coerceAtLeast(0)))}
                return result
            } catch(c:CancellationException) {
                withContext(NonCancellable+Dispatchers.IO){captured?.let{id->Diagnostics.event(id,Stage.CANCEL_REQUESTED,error=ErrorCode.CANCELLED);Diagnostics.end(id,if(active?.commitReached==true)Outcome.SUCCESS else Outcome.CANCELLED,SafeInfo(durationMs=(SystemClock.elapsedRealtime()-started).coerceAtLeast(0)))}}
                throw c
            } catch(t:Throwable) {
                withContext(NonCancellable+Dispatchers.IO){captured?.let{id->Diagnostics.event(id,Stage.ERROR,failureInfo(t,(SystemClock.elapsedRealtime()-started).coerceAtLeast(0),active?.previewStage),errorCode(t,operation));Diagnostics.end(id,Outcome.FAILED)}}
                throw t
            }
        }
        internal fun failureInfo(t: Throwable, durationMs: Long, previewStage:PreviewFailureStage?=null): SafeInfo = (if((t as? PhotoException)?.importDiagnostic!=null)Diagnostics.memoryInfo() else SafeInfo()).copy(
            durationMs = durationMs,
            previewFailureStage = previewStage,
            previewExceptionType = previewStage?.let { PreviewFailure.type(t) },
            previewFailureReason = previewStage?.let { PreviewFailure.reason(t) },
            previewCleanupExceptionType = previewStage?.let { t.suppressed.firstOrNull()?.let(PreviewFailure::type) },
            nativeEngineCode = (t as? EngineException)?.nativeEngineCode,
            nativeEngineStage = (t as? EngineException)?.nativeEngineStage,
            codecCode = (t as? PhotoException)?.codecCode,
            codecStage = (t as? PhotoException)?.codecStage,
            rawTechnical = (t as? PhotoException)?.rawTechnical,
            codecDetail = (t as? PhotoException)?.codecDetail,
            importDiagnostic = (t as? PhotoException)?.importDiagnostic,
        )
        internal fun errorCode(t:Throwable,operation:Operation?=null):ErrorCode=when(t){
            is OutOfMemoryError->ErrorCode.MEMORY_LIMIT
            is EngineException->when(t.failure){EngineFailure.GPU_COMPLETION_UNCERTAIN->ErrorCode.GPU_COMPLETION_UNCERTAIN;EngineFailure.BUDGET->ErrorCode.MEMORY_LIMIT;EngineFailure.STORAGE->ErrorCode.DISK_SPACE;EngineFailure.UNAVAILABLE->ErrorCode.ENGINE_UNAVAILABLE;EngineFailure.PARAMETERS,EngineFailure.PROFILE,EngineFailure.IMAGE_SIZE->ErrorCode.INVALID_ARGUMENT;EngineFailure.CANCELLED->ErrorCode.CANCELLED;else->ErrorCode.RENDER_FAILED}
            is PhotoException->when(t.reason){PhotoException.Reason.TOO_LARGE->ErrorCode.MEMORY_LIMIT;PhotoException.Reason.UNSUPPORTED->ErrorCode.UNSUPPORTED_FORMAT;PhotoException.Reason.STORAGE->ErrorCode.DISK_SPACE;PhotoException.Reason.EXPORT->ErrorCode.ENCODE_FAILED;else->ErrorCode.DECODE_FAILED}
            is SecurityException->ErrorCode.PERMISSION_LOST
            else->if(operation==Operation.PREVIEW_RENDER)when(t){
                is LinkageError->ErrorCode.ENGINE_UNAVAILABLE
                is dev.filmroom.engine.NativeWorkUnavailable->ErrorCode.MEMORY_LIMIT
                is IllegalArgumentException->ErrorCode.INVALID_ARGUMENT
                else->ErrorCode.RENDER_FAILED
            }else ErrorCode.UNKNOWN
        }
    }
}

fun EditorState.safeInfo(width:Int?=null,height:Int?=null)=SafeInfo(width=width,height=height,
    film=FilmProfile.fromId(filmStock),paper=PaperProfile.fromId(paperStock),brightness=brightness.toDouble(),filmExposure=filmExposure.toDouble(),
    contrast=contrast.toDouble(),warmth=warmth.toDouble(),saturation=saturation.toDouble(),grain=grain.toDouble(),grainSize=grainSize.toDouble(),halation=halation.toDouble(),halationSize=halationSize.toDouble(),
    diffusionFilter=DiffusionProfile.fromId(diffusionFilter),diffusionStrength=diffusionStrength.toDouble(),diffusionSize=diffusionSize.toDouble(),
    printYellow=printYellow.toDouble(),printMagenta=printMagenta.toDouble(),preflash=preflash.toString().toDouble(),
    scanSharpenAmount=scanSharpenAmount.toDouble(),scanSharpenRadius=scanSharpenRadius.toDouble(),
    filmFormatMm=filmFormatMm.toString().toDouble(),
    lensBlurUm=lensBlurUm.toString().toDouble(),
    scanBlur=scanBlur.toString().toDouble(),
    scatterAmount=scatterAmount.toString().toDouble(),
    scatterSize=scatterSize.toString().toDouble(),
    halationBounces=halationBounces.toLong(),
    halationDecay=halationDecay.toString().toDouble(),
    halationNormalize=halationNormalize,
    dirAmount=dirAmount.toString().toDouble(),
    dirDiffusionUm=dirDiffusionUm.toString().toDouble(),
    dirSameLayer=dirSameLayer.toString().toDouble(),
    dirInterLayer=dirInterLayer.toString().toDouble(),
    grainSublayers=grainSublayers,
    grainLayers=grainLayers.toLong(),
    grainBlur=grainBlur.toString().toDouble(),
    grainDyeCloud=grainDyeCloud.toString().toDouble(),
    grainUniformity=grainUniformity.toString().toDouble(),
    outputRoute=OutputRoute.fromId(outputRoute),
    useGpu=useGpu,
    outputColorSpace=OutputColorProfile.fromId(outputColorSpace),
    outputGamut=outputGamut,
    printExposureEv=printExposureEv.toString().toDouble(),
    printIlluminant=PrintIlluminant.fromId(printIlluminant),
    printCompensation=printCompensation,
    normalizePrintExposure=normalizePrintExposure,
    preflashYellow=preflashYellow.toString().toDouble(),
    preflashMagenta=preflashMagenta.toString().toDouble(),
    scanWhiteCorrection=effectiveScanWhiteCorrection,
    scanBlackCorrection=effectiveScanBlackCorrection,
    scanWhiteLevel=scanWhiteLevel.toString().toDouble(),
    scanBlackLevel=scanBlackLevel.toString().toDouble(),
    autoExposure=autoExposure,
    metering=MeteringMode.fromId(metering),
    highlightBoostEv=highlightBoostEv.toString().toDouble(),
    highlightBoostRange=highlightBoostRange.toString().toDouble(),
    highlightProtectEv=highlightProtectEv.toString().toDouble(),
    scanGlarePercent=scanGlarePercent.toString().toDouble(),
    scanGlareRoughness=scanGlareRoughness.toString().toDouble(),
    scanGlareBlur=scanGlareBlur.toString().toDouble(),
    grainClumpSizeUm=grainClumpSizeUm.toString().toDouble(),
    grainClumpStrengthNm=grainClumpStrengthNm.toString().toDouble(),
    printDiffusionFilter=DiffusionProfile.fromId(printDiffusionFilter),
    printDiffusionStrength=printDiffusionStrength.toString().toDouble(),
    printDiffusionSize=printDiffusionSize.toString().toDouble(),
    neutralFiltersFromDatabase=neutralFiltersFromDatabase,
    neutralCyan=neutralCyan.toString().toDouble(),
    neutralMagenta=neutralMagenta.toString().toDouble(),
    neutralYellow=neutralYellow.toString().toDouble(),
    filmDevelopmentTime=filmDevelopmentTime.toString().toDouble(),paperDevelopmentTime=paperDevelopmentTime.toString().toDouble(),
    filmGamma=filmGamma.toString().toDouble(),
    paperGamma=paperGamma.toString().toDouble(),
    paperGammaFast=paperGammaFast.toString().toDouble(),
    paperGammaSlow=paperGammaSlow.toString().toDouble(),
    paperGammaRed=paperGammaRed.toString().toDouble(),
    paperGammaGreen=paperGammaGreen.toString().toDouble(),
    paperGammaBlue=paperGammaBlue.toString().toDouble(),
    paperDeveloperExhaustion=paperDeveloperExhaustion.toString().toDouble(),grainRedScale=grainRedScale.toString().toDouble(),grainGreenScale=grainGreenScale.toString().toDouble(),grainBlueScale=grainBlueScale.toString().toDouble(),halationColor=HalationColor.fromId(halationColor),seed=42)
