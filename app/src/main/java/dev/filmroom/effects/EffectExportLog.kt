package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import android.media.MediaCodec
import java.io.IOException
import java.util.concurrent.CancellationException

/** Local diagnostics only; no image identity, paths, messages or codec-provided text. */
internal class EffectExportLog(val scope:EffectExportScope) {
    private val id=Diagnostics.begin(Operation.EXPORT_ENCODE,SafeInfo(effectExport=EffectExportDiagnosticInfo(scope,EffectExportEvent.START)))
    var step:EffectExportStep=EffectExportStep.NONE
    var failureReason:EffectExportReason=EffectExportReason.NONE
    fun record(info:EffectExportDiagnosticInfo){Diagnostics.event(id,Stage.ENCODE_PROGRESS,SafeInfo(effectExport=info))}
    fun end(outcome:Outcome){Diagnostics.end(id,outcome,SafeInfo(effectExport=EffectExportDiagnosticInfo(scope,EffectExportEvent.END)));Diagnostics.flush()}
    companion object {
        fun reason(error:Throwable):EffectExportReason=when(error){
            is OutOfMemoryError->EffectExportReason.OUT_OF_MEMORY
            is MediaCodec.CodecException->EffectExportReason.CODEC_ERROR
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
                log.record(EffectExportDiagnosticInfo(scope,EffectExportEvent.ERROR,reason(e),log.step,
                    codecErrorCode=(e as? MediaCodec.CodecException)?.errorCode))
                throw e
            }
            finally{log.end(outcome)}
        }
    }
}
