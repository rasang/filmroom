package dev.filmroom.effects

import dev.filmroom.io.formats.PhotoFormat
import dev.filmroom.diagnostics.*

/** Only parsed-header MIME or a native-validated RAW identity, never a filename/provider claim.
 * Mapping itself opens no stream or descriptor. */
internal object EffectInputFormat {
    fun fromDecodedHeader(mime:String?):PhotoFormat = when(mime) {
        "image/jpeg" -> PhotoFormat.JPEG
        "image/png" -> PhotoFormat.PNG
        "image/webp" -> PhotoFormat.WEBP
        "image/heif", "image/heic" -> PhotoFormat.HEIF
        "image/avif" -> PhotoFormat.AVIF
        "image/gif" -> PhotoFormat.GIF
        "image/bmp", "image/x-ms-bmp" -> PhotoFormat.BMP
        "image/x-adobe-dng" -> PhotoFormat.DNG
        "image/x-nikon-nef", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-sony-arw",
        "image/x-panasonic-rw2", "image/x-fuji-raf", "image/x-olympus-orf", "image/x-pentax-pef" -> PhotoFormat.CAMERA_RAW
        else -> PhotoFormat.UNKNOWN
    }
    suspend fun <T> decode(correlationId:Long,block:suspend ((String)->Unit)->T):T {
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
