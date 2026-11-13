package dev.filmroom.effects

import android.content.ContentResolver
import android.net.Uri
import dev.filmroom.io.PhotoMetadata

internal object EffectCaptureMetadata {
    fun read(resolver: ContentResolver, snapshot: String?, sourceUri: String?, resultUri: String?): PhotoMetadata =
        resolve(snapshot,listOfNotNull(sourceUri,resultUri).distinct()) { uri ->
            runCatching { resolver.openInputStream(Uri.parse(uri))?.use(PhotoMetadata::readCaptureMetadata) }.getOrNull()
        }

    internal fun resolve(snapshot:String?,candidates:List<String>,read:(String)->PhotoMetadata?):PhotoMetadata {
        val cached = snapshot?.takeIf { it.length <= 131072 }?.let { runCatching { PhotoMetadata.fromJson(it) }.getOrNull() }
        if (cached?.livePhotoDates(0)?.original != null) return cached
        var fallback:PhotoMetadata?=null
        for (uri in candidates) {
            val metadata=read(uri) ?: continue
            if(metadata.hasCaptureFacts() && fallback==null)fallback=metadata
            if(metadata.livePhotoDates(0).original!=null) {
                // Never replace a frozen snapshot with a date-only lookup. A restored backup
                // may deliberately omit GPS, so do not rehydrate missing GPS into a snapshot.
                return cached?.withCaptureDateFallback(metadata) ?: fallback!!.withCaptureDateFallback(metadata)
            }
        }
        return cached ?: fallback ?: PhotoMetadata.unavailable()
    }
}
