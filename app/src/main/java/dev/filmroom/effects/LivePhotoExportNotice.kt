package dev.filmroom.effects

import android.content.Context
import android.content.SharedPreferences

/** This records only that a non-blocking tip was shown, never gallery compatibility. */
class LivePhotoExportNotice(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("live_photo_export_notice", Context.MODE_PRIVATE))
    fun consumeFirstNotice(): Boolean {
        if (preferences.getBoolean("shown", false)) return false
        preferences.edit().putBoolean("shown", true).apply()
        return true
    }
}
