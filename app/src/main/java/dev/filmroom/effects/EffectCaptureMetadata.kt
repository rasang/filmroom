package dev.filmroom.effects

import android.content.ContentResolver
import android.net.Uri
import dev.filmroom.io.PhotoMetadata

internal object EffectCaptureMetadata {
    fun read(resolver: ContentResolver, snapshot: String?, sourceUri: String?, resultUri: String?): PhotoMetadata {
        val cached = snapshot?.takeIf { it.length <= 131072 }?.let { runCatching { PhotoMetadata.fromJson(it) }.getOrNull() }
        if (cached?.livePhotoDates(0)?.original != null) return cached
        for (uri in listOfNotNull(sourceUri, resultUri).distinct()) {
            val metadata = runCatching { resolver.openInputStream(Uri.parse(uri))?.use(PhotoMetadata::readCaptureDates) }.getOrNull()
            if (metadata?.livePhotoDates(0)?.original != null) return metadata
        }
        return cached ?: PhotoMetadata.unavailable()
    }
}
