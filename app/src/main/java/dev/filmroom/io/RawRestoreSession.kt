package dev.filmroom.io

import android.content.SharedPreferences
import java.io.IOException

/** A valid, probed original and an existing spool survived a failed cache rebuild. */
internal class RawCacheMigrationFailure(cause: Throwable) : IOException("RAW cache rebuild failed", cause)

internal object RawRestoreSession {
    /** Keep the saved keys for an explicit retry; ordinary invalid/missing-source failures expire them. */
    fun retainAfterFailure(preferences: SharedPreferences, failure: Throwable): Boolean {
        val retryable = generateSequence(failure) { it.cause }.take(16)
            .any { it is RawCacheMigrationFailure }
        if (!retryable) preferences.edit().clear().apply()
        return retryable
    }
}
