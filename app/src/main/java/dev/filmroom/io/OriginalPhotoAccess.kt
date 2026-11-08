package dev.filmroom.io

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.filmroom.diagnostics.ImportOrigin
import dev.filmroom.diagnostics.OriginalPhotoGrant

/** Retain only the exact URI explicitly returned by the import picker. No cache/path substitution. */
internal object OriginalPhotoAccess {
    fun retain(context: Context, uri: Uri, origin: ImportOrigin): OriginalPhotoGrant {
        if (origin != ImportOrigin.PHOTO_PICKER && origin != ImportOrigin.FILES_PICKER) return OriginalPhotoGrant.NOT_REQUESTED
        return retainRead(context, uri)
    }

    /** Only call after explicit picker selection, never from a persisted/backup URI alone. */
    fun retainRead(context: Context, uri: Uri): OriginalPhotoGrant {
        if (OriginalPhotoUri.sanitize(uri.toString()) == null ||
            uri.authority == context.packageName || uri.authority.orEmpty().startsWith(context.packageName + ".")) return OriginalPhotoGrant.NOT_APPLICABLE
        val resolver = context.contentResolver
        return retainSelected(
            { resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission } },
            { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        )
    }

    /** Never confuse a successful API return with a verified durable read grant. */
    internal fun retainSelected(hasPersistedRead: () -> Boolean, takeRead: () -> Unit): OriginalPhotoGrant {
        if (try { hasPersistedRead() } catch (_: Exception) { false }) return OriginalPhotoGrant.ALREADY_PERSISTED
        try { takeRead() }
        catch (_: SecurityException) { return OriginalPhotoGrant.TEMPORARY_ONLY }
        catch (_: UnsupportedOperationException) { return OriginalPhotoGrant.TEMPORARY_ONLY }
        catch (_: Exception) { return OriginalPhotoGrant.REQUEST_FAILED }
        return try {
            if (hasPersistedRead()) OriginalPhotoGrant.PERSISTED else OriginalPhotoGrant.TEMPORARY_ONLY
        } catch (_: Exception) { OriginalPhotoGrant.VERIFY_FAILED }
    }
}
