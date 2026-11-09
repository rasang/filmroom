package dev.filmroom.io

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** Resolve only a provider-certified equivalent address. Never search by name or guess a media ID.
 * Does not request/take grants, copy/re-encode an image, or discard embedded Live video bytes.
 */
internal object OriginalPhotoRecovery {
    fun resolve(context:Context, value:String?):String? = resolve(value,
        readable={uri->context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.read() >= 0 } ?: false},
        equivalent={uri->if(Build.VERSION.SDK_INT>=29) MediaStore.getMediaUri(context,Uri.parse(uri))?.toString() else null})

    internal fun resolve(value:String?,readable:(String)->Boolean,equivalent:(String)->String?):String? {
        val original=OriginalPhotoUri.sanitize(value) ?: return null
        if(runCatching { readable(original) }.getOrDefault(false))return original
        // Android's own media/document provider supplies the equivalence, never a backup-supplied redirect.
        val authority=Uri.parse(original).authority
        if(authority !in setOf("media","com.android.providers.media.documents","com.android.externalstorage.documents"))return null
        val candidate=runCatching { equivalent(original) }.getOrNull()?.takeIf(::mediaItem) ?: return null
        return candidate.takeIf { runCatching { readable(it) }.getOrDefault(false) }
    }
    private fun mediaItem(value:String)=Regex("content://media/(external|external_primary|[0-9a-fA-F-]+)/(images/media|file)/[1-9][0-9]*").matches(value)
}
