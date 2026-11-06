package dev.filmroom.diagnostics

import dev.filmroom.engine.EngineException
import dev.filmroom.engine.NativeWorkUnavailable
import dev.filmroom.io.PhotoException

/** Fixed technical categories only. Never serialize class names, messages,
 * stack frames, causes, paths or arbitrary native/driver text. */
enum class PreviewFailureStage {
    START, CANCEL_PREWARM, WAIT_FOR_WORKER, LOAD_ENGINE, SOURCE_PREPARATION,
    ASSET_PREPARATION, METERING, CACHE_IDENTITY, CREATE_HANDLE, CAPABILITIES,
    PREPARED_CACHE, PREVIEW_CACHE, PARAMETERS, BITMAP_ALLOCATION,
    RESERVATION, NATIVE_RENDER, NATIVE_RESULT, SOURCE_VALIDATION, OUTPUT_VALIDATION,
    RENDER_REQUEST, COMPOSITION, ADOPTION
}
enum class PreviewExceptionType {
    OUT_OF_MEMORY_ERROR, UNSATISFIED_LINK_ERROR, NO_CLASS_DEF_FOUND_ERROR,
    LINKAGE_ERROR, ILLEGAL_ARGUMENT_EXCEPTION, ILLEGAL_STATE_EXCEPTION,
    IO_EXCEPTION, SECURITY_EXCEPTION, JSON_EXCEPTION, ENGINE_EXCEPTION,
    PHOTO_EXCEPTION, NATIVE_WORK_UNAVAILABLE, ASSET_IDENTITY_EXCEPTION, OTHER_THROWABLE
}
enum class PreviewFailureReason {
    MEMORY_EXHAUSTED, MEMORY_ADMISSION_REFUSED, LINKAGE_UNAVAILABLE,
    PRECONDITION_REJECTED, IO_FAILED, PERMISSION_DENIED, INVALID_JSON,
    ENGINE_REJECTED, SOURCE_REJECTED, ASSET_PATH_REJECTED, ASSET_MANIFEST_MISSING, UNEXPECTED_EXCEPTION
}

internal object PreviewFailure {
    fun type(t:Throwable):PreviewExceptionType=when(t) {
        is OutOfMemoryError -> PreviewExceptionType.OUT_OF_MEMORY_ERROR
        is UnsatisfiedLinkError -> PreviewExceptionType.UNSATISFIED_LINK_ERROR
        is NoClassDefFoundError -> PreviewExceptionType.NO_CLASS_DEF_FOUND_ERROR
        is LinkageError -> PreviewExceptionType.LINKAGE_ERROR
        is EngineException -> PreviewExceptionType.ENGINE_EXCEPTION
        is PhotoException -> PreviewExceptionType.PHOTO_EXCEPTION
        is NativeWorkUnavailable -> PreviewExceptionType.NATIVE_WORK_UNAVAILABLE
        is dev.filmroom.engine.PreviewAssetIdentityException -> PreviewExceptionType.ASSET_IDENTITY_EXCEPTION
        is SecurityException -> PreviewExceptionType.SECURITY_EXCEPTION
        is org.json.JSONException -> PreviewExceptionType.JSON_EXCEPTION
        is IllegalArgumentException -> PreviewExceptionType.ILLEGAL_ARGUMENT_EXCEPTION
        is IllegalStateException -> PreviewExceptionType.ILLEGAL_STATE_EXCEPTION
        is java.io.IOException -> PreviewExceptionType.IO_EXCEPTION
        else -> PreviewExceptionType.OTHER_THROWABLE
    }
    fun reason(t:Throwable):PreviewFailureReason=when(type(t)) {
        PreviewExceptionType.ASSET_IDENTITY_EXCEPTION -> (t as dev.filmroom.engine.PreviewAssetIdentityException).safeReason
        PreviewExceptionType.OUT_OF_MEMORY_ERROR -> PreviewFailureReason.MEMORY_EXHAUSTED
        PreviewExceptionType.NATIVE_WORK_UNAVAILABLE -> PreviewFailureReason.MEMORY_ADMISSION_REFUSED
        PreviewExceptionType.UNSATISFIED_LINK_ERROR, PreviewExceptionType.NO_CLASS_DEF_FOUND_ERROR,
        PreviewExceptionType.LINKAGE_ERROR -> PreviewFailureReason.LINKAGE_UNAVAILABLE
        PreviewExceptionType.ILLEGAL_ARGUMENT_EXCEPTION, PreviewExceptionType.ILLEGAL_STATE_EXCEPTION -> PreviewFailureReason.PRECONDITION_REJECTED
        PreviewExceptionType.IO_EXCEPTION -> PreviewFailureReason.IO_FAILED
        PreviewExceptionType.SECURITY_EXCEPTION -> PreviewFailureReason.PERMISSION_DENIED
        PreviewExceptionType.JSON_EXCEPTION -> PreviewFailureReason.INVALID_JSON
        PreviewExceptionType.ENGINE_EXCEPTION -> PreviewFailureReason.ENGINE_REJECTED
        PreviewExceptionType.PHOTO_EXCEPTION -> PreviewFailureReason.SOURCE_REJECTED
        PreviewExceptionType.OTHER_THROWABLE -> PreviewFailureReason.UNEXPECTED_EXCEPTION
    }
}
