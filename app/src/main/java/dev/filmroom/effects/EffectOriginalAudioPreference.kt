package dev.filmroom.effects

import android.content.Context
import java.io.File

/** Only the source video's sound is optional. EffectAudioTrack still supplies shutter/ambience. */
internal object EffectOriginalAudioPreference {
    private const val PREFS="effect_playback"
    private const val KEY="live_original_audio"
    fun read(context:Context):Boolean=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(KEY,true)
    fun write(context:Context,enabled:Boolean){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(KEY,enabled).apply()}
    fun source(kind:EffectKind,enabled:Boolean,file:File?):File? =
        file.takeIf { enabled && kind==EffectKind.OPTICAL_LEAK }
}
