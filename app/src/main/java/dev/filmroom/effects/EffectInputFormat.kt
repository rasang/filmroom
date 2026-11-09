package dev.filmroom.effects

import dev.filmroom.io.formats.PhotoFormat
import dev.filmroom.diagnostics.*

/** Only ImageDecoder's parsed-header MIME, never a filename or provider getType() claim.
 * No extra stream, descriptor or media request is opened for diagnostics. */
internal object EffectInputFormat {
    fun fromDecodedHeader(mime:String?):PhotoFormat = when(mime) {
        "image/jpeg" -> PhotoFormat.JPEG
        "image/png" -> PhotoFormat.PNG
        "image/webp" -> PhotoFormat.WEBP
        "image/heif", "image/heic" -> PhotoFormat.HEIF
        "image/avif" -> PhotoFormat.AVIF
        "image/gif" -> PhotoFormat.GIF
        "image/bmp", "image/x-ms-bmp" -> PhotoFormat.BMP
        else -> PhotoFormat.UNKNOWN
    }
    fun <T> decode(correlationId:Long,block:((String)->Unit)->T):T {
        var format=PhotoFormat.UNKNOWN
        var outcome=Outcome.FAILED
        val trace=QueuedDiagnosticTrace(Operation.IMPORT,SafeInfo(effectExport=EffectExportDiagnosticInfo(
            EffectExportScope.INPUT_DECODE,EffectExportEvent.START,correlationId=correlationId,inputFormat=format)))
        try { return block{format=fromDecodedHeader(it)}.also{outcome=Outcome.SUCCESS} }
        catch(e:java.util.concurrent.CancellationException){outcome=Outcome.CANCELLED;throw e}
        finally { trace.end(outcome,SafeInfo(effectExport=EffectExportDiagnosticInfo(
            EffectExportScope.INPUT_DECODE,EffectExportEvent.INPUT_FORMAT,correlationId=correlationId,inputFormat=format))) }
    }
}
