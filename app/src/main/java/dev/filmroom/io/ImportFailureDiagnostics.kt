package dev.filmroom.io

import android.graphics.ImageDecoder
import dev.filmroom.diagnostics.CodecErrorCode
import dev.filmroom.diagnostics.CodecStage
import dev.filmroom.io.formats.PhotoFormat
import java.io.FileNotFoundException
import java.io.IOException

/** Fixed categories only. Never persist exception messages, names, stacks or source data. */
internal fun classifyImportCause(cause: Throwable?): CodecErrorCode {
    var current = cause
    val seen = mutableSetOf<Throwable>()
    repeat(8) {
        val t = current ?: return CodecErrorCode.IMPORT_UNEXPECTED
        if (!seen.add(t)) return CodecErrorCode.IMPORT_UNEXPECTED
        when (t) {
            is OutOfMemoryError -> return CodecErrorCode.IMPORT_MEMORY_ALLOCATION
            is SecurityException -> return CodecErrorCode.IMPORT_PERMISSION_DENIED
            is FileNotFoundException -> return CodecErrorCode.IMPORT_SOURCE_NOT_FOUND
            is ImageDecoder.DecodeException -> return when (t.error) {
                ImageDecoder.DecodeException.SOURCE_INCOMPLETE -> CodecErrorCode.IMPORT_PLATFORM_INCOMPLETE
                ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA -> CodecErrorCode.IMPORT_PLATFORM_MALFORMED
                ImageDecoder.DecodeException.SOURCE_EXCEPTION -> CodecErrorCode.IMPORT_PLATFORM_SOURCE_EXCEPTION
                else -> CodecErrorCode.IMPORT_PLATFORM_DECODE_OTHER
            }
        }
        if (t !is PhotoException && t is IOException) return CodecErrorCode.IMPORT_IO
        current = t.cause ?: return CodecErrorCode.IMPORT_UNEXPECTED
    }
    return CodecErrorCode.IMPORT_UNEXPECTED
}

/** Keep the original exception's UI and trusted codec detail, adding only missing context. */
internal fun enrichImportFailure(error: PhotoException, stage: CodecStage, format: PhotoFormat?): PhotoException =
    PhotoException(error.reason, error.message.orEmpty(), error, error.cleanupUncertain,
        error.codecCode ?: classifyImportCause(error.cause), error.codecStage ?: stage,
        error.rawTechnical, error.uiMessage, error.inputFormatHint ?: format)
