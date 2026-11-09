package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import android.media.MediaCodec
import java.io.IOException
import java.util.concurrent.CancellationException

/** Local diagnostics only; no image identity, paths, messages or codec-provided text. */
internal class EffectExportLog(val scope:EffectExportScope) {
    private val started=System.nanoTime()/1_000_000
    private val initial=SafeInfo(effectExport=EffectExportDiagnosticInfo(scope,EffectExportEvent.START))
    private val queued=if(scope==EffectExportScope.PRELOAD || scope==EffectExportScope.LIVE_PHOTO)QueuedDiagnosticTrace(Operation.EXPORT_ENCODE,initial) else null
    private val id=if(queued==null)Diagnostics.begin(Operation.EXPORT_ENCODE,initial) else null
    var step:EffectExportStep=EffectExportStep.NONE
    var failureReason:EffectExportReason=EffectExportReason.NONE
    fun record(info:EffectExportDiagnosticInfo){val safe=SafeInfo(effectExport=info);if(queued!=null)queued.event(Stage.ENCODE_PROGRESS,safe) else id?.let{Diagnostics.event(it,Stage.ENCODE_PROGRESS,safe)}}
    fun phase(value:EffectExportStep){if(step!=value){step=value;record(EffectExportDiagnosticInfo(scope,EffectExportEvent.STEP,step=value,elapsedMs=elapsed()))}}
    fun elapsed()=(System.nanoTime()/1_000_000-started).coerceAtLeast(0)
    fun end(outcome:Outcome,flush:Boolean=true){val safe=SafeInfo(effectExport=EffectExportDiagnosticInfo(scope,EffectExportEvent.END,step=step,elapsedMs=elapsed()));if(queued!=null)queued.end(outcome,safe) else id?.let{Diagnostics.end(it,outcome,safe)};if(flush&&queued==null)Diagnostics.flush()}
    companion object {
        fun reason(error:Throwable):EffectExportReason=when(error){
            is OutOfMemoryError->EffectExportReason.OUT_OF_MEMORY
            is MediaCodec.CodecException->EffectExportReason.CODEC_ERROR
            is SecurityException->EffectExportReason.PERMISSION_DENIED
            is dev.filmroom.livephoto.core.LivePhotoException->when(error.code) {
                dev.filmroom.livephoto.core.ErrorCode.UNSUPPORTED_PROFILE -> EffectExportReason.LIVE_UNSUPPORTED_PROFILE
                dev.filmroom.livephoto.core.ErrorCode.RESOURCE_LIMIT -> EffectExportReason.LIVE_RESOURCE_LIMIT
                dev.filmroom.livephoto.core.ErrorCode.VERIFICATION_FAILED -> EffectExportReason.LIVE_VERIFY_FAILED
                dev.filmroom.livephoto.core.ErrorCode.PUBLISH_FAILED -> EffectExportReason.LIVE_PUBLISH_FAILED
                dev.filmroom.livephoto.core.ErrorCode.BUSY -> EffectExportReason.LIVE_BUSY
                dev.filmroom.livephoto.core.ErrorCode.OUTPUT_EXISTS -> EffectExportReason.LIVE_OUTPUT_EXISTS
                dev.filmroom.livephoto.core.ErrorCode.IO -> EffectExportReason.IO_ERROR
                else -> EffectExportReason.LIVE_INVALID_INPUT
            }
            is IOException->EffectExportReason.IO_ERROR
            is IllegalArgumentException->EffectExportReason.INVALID_ARGUMENT
            is IllegalStateException->EffectExportReason.INVALID_STATE
            else->EffectExportReason.UNKNOWN
        }
        inline fun <T> trace(scope:EffectExportScope,block:(EffectExportLog)->T):T {
            val log=EffectExportLog(scope)
            var outcome=Outcome.FAILED
            try{return block(log).also{outcome=Outcome.SUCCESS}}
            catch(e:CancellationException){outcome=Outcome.CANCELLED;throw e}
            catch(e:Throwable){
                log.record(EffectExportDiagnosticInfo(scope,EffectExportEvent.ERROR,if(log.failureReason!=EffectExportReason.NONE)log.failureReason else reason(e),log.step,
                    codecErrorCode=(e as? MediaCodec.CodecException)?.errorCode))
                throw e
            }
            finally{log.end(outcome)}
        }
    }
}
