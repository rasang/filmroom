package dev.filmroom.effects

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.filmroom.diagnostics.PairLookupStatus
import dev.filmroom.diagnostics.OriginalPhotoGrant
import dev.filmroom.io.OriginalPhotoAccess
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Candidate discovery only. A name is never proof of a Live pair: the caller must verify both IDs. */
internal object EffectMotionPairAccess {
    private const val PREFS = "effect-motion-pairs"
    private const val MAX_CANDIDATES = 8
    private val session = ConcurrentHashMap<String, String>()
    private fun key(image: String) = MessageDigest.getInstance("SHA-256")
        .digest(image.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun sessionKey(context: Context, image: String) = context.packageName + ":" + key(image)

    /** Call only after an explicitly selected URI has passed native pair validation. */
    fun remember(context: Context, imageUri: String, videoUri: Uri): Boolean =
        rememberSelected(context, imageUri, videoUri) {
            OriginalPhotoAccess.retainRead(context, videoUri).let {
                it == OriginalPhotoGrant.PERSISTED || it == OriginalPhotoGrant.ALREADY_PERSISTED
            }
        }

    internal fun rememberSelected(context: Context, imageUri: String, videoUri: Uri, retain: () -> Boolean): Boolean {
        if (imageUri.isBlank() || videoUri.scheme != "content") return false
        val value = videoUri.toString()
        // A transient picker grant is useful for this process, but never represented as durable.
        session[sessionKey(context, imageUri)] = value
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!runCatching(retain).getOrDefault(false)) {
            prefs.edit().remove(key(imageUri)).commit()
            return false
        }
        return prefs.edit().putString(key(imageUri), value).commit()
    }

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasFullVideoAccess(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun canQueryVideos(context: Context): Boolean = requiredPermissions().any {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun candidates(context: Context, imageUri: String, includeSaved: Boolean = true,
        report: (PairLookupStatus) -> Unit = {}): List<String> {
        fun stop(status: PairLookupStatus): List<String> { report(status); return emptyList() }
        val saved = session[sessionKey(context, imageUri)] ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(imageUri), null)
        // Explicit selections still require the caller to revalidate access and native pair IDs.
        if (includeSaved && saved != null) { report(PairLookupStatus.SAVED); return listOf(saved) }
        if (!canQueryVideos(context)) return stop(PairLookupStatus.NO_PERMISSION)
        val image = runCatching { Uri.parse(imageUri) }.getOrNull() ?: return stop(PairLookupStatus.IMAGE_METADATA_UNAVAILABLE)
        if (image.scheme != "content") return stop(PairLookupStatus.IMAGE_METADATA_UNAVAILABLE)
        val volume = directImageVolume(image)
        return try {
            val resolver = context.contentResolver
            // Never extract a MediaStore ID or filesystem path from a Picker/document URI.
            // Read only metadata through the original authorized URI.
            val projection = if (volume != null) arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
                else arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val location = resolver.query(image, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else {
                    val nameIndex = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val pathIndex = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val name = if (nameIndex >= 0) c.getString(nameIndex) else null
                    val path = if (volume != null && pathIndex >= 0) c.getString(pathIndex)?.takeIf { it.isNotBlank() } else null
                    if (name.isNullOrBlank() || c.moveToNext()) null else name to path
                }
            } ?: return stop(PairLookupStatus.IMAGE_METADATA_UNAVAILABLE)
            val dot = location.first.lastIndexOf('.')
            if (dot <= 0) return stop(PairLookupStatus.IMAGE_METADATA_UNAVAILABLE)
            val stem = location.first.substring(0, dot)
            val collection = MediaStore.Video.Media.getContentUri(volume ?: MediaStore.VOLUME_EXTERNAL)
            val nameSelection = "(${MediaStore.MediaColumns.DISPLAY_NAME} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} = ?)"
            val path = location.second
            val selection = if (path != null) "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND $nameSelection" else nameSelection
            val args = if (path != null) arrayOf(path, "$stem.mp4", "$stem.MP4") else arrayOf("$stem.mp4", "$stem.MP4")
            val matches = mutableListOf<String>()
            resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    if (matches.size == MAX_CANDIDATES) return stop(PairLookupStatus.TOO_MANY_CANDIDATES) // Never truncate an ambiguous search.
                    val id = c.getLong(0)
                    if (id < 0) return stop(PairLookupStatus.QUERY_FAILED)
                    matches += ContentUris.withAppendedId(collection, id).toString()
                }
            }
            ?: return stop(PairLookupStatus.QUERY_FAILED)
            // Multiple same-name candidates are not resolved here: only strict native IDs can pair them.
            report(if(matches.isEmpty()) PairLookupStatus.NO_VISIBLE_SAME_NAME else PairLookupStatus.CANDIDATES)
            matches.distinct()
        } catch (_: SecurityException) { stop(PairLookupStatus.ACCESS_DENIED) }
          catch (_: Exception) { stop(PairLookupStatus.QUERY_FAILED) }
    }

    /** Picker and documents URIs cannot be converted into wider MediaStore access. */
    internal fun directImageVolume(uri: Uri): String? {
        val parts = uri.pathSegments
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY || parts.size != 4 ||
            parts[1] != "images" || parts[2] != "media" || parts[3].toLongOrNull()?.let { it >= 0 } != true) return null
        return parts[0].takeIf { it.isNotBlank() && it != "internal" }
    }

    internal fun clearSessionForTests() = session.clear()
}
