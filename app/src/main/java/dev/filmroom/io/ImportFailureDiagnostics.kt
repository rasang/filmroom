package dev.filmroom.io

import android.graphics.ImageDecoder
import dev.filmroom.diagnostics.ImportFailure
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/** Bounded cause traversal. Persist only a fixed category; no messages, paths or stack traces. */
internal fun classifyImportCause(cause: Throwable?): ImportFailure {
    var current = cause
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var fallback:ImportFailure?=null
    repeat(8) {
        val error = current ?: return fallback ?: ImportFailure.OTHER
        if (!seen.add(error)) return fallback ?: ImportFailure.OTHER
        when (error) {
            is PhotoException -> if (error.codecCode != null) return ImportFailure.CODEC_REPORTED
            is OutOfMemoryError -> return ImportFailure.MEMORY_ALLOCATION
            is SecurityException -> return ImportFailure.PERMISSION_DENIED
            is FileNotFoundException -> return ImportFailure.FILE_NOT_FOUND
            is ImageDecoder.DecodeException -> if(fallback==null)fallback=when (error.error) {
                ImageDecoder.DecodeException.SOURCE_INCOMPLETE -> ImportFailure.PLATFORM_INCOMPLETE
                ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA -> ImportFailure.PLATFORM_MALFORMED
                ImageDecoder.DecodeException.SOURCE_EXCEPTION -> ImportFailure.PLATFORM_SOURCE_EXCEPTION
                else -> ImportFailure.PLATFORM_OTHER
            }
            is IOException -> if(fallback==null)fallback=ImportFailure.IO
        }
        current = error.cause
    }
    return fallback ?: ImportFailure.OTHER
}
