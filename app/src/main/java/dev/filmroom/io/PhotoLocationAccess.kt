package dev.filmroom.io

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import java.io.InputStream

/** Read only the selected item. Never turn a picker URI into a broader MediaStore grant. */
internal object PhotoLocationAccess {
    // Public picker extra (newer system/extension pickers). Older pickers ignore it.
    // User choice in the picker is final; permission alone must not bypass redaction.
    const val REQUEST_LOCATION_METADATA_ACCESS = "android.provider.extra.REQUEST_LOCATION_METADATA_ACCESS"

    fun requestPickerMetadata(intent: Intent): Intent = intent.apply {
        if (action == MediaStore.ACTION_PICK_IMAGES) putExtra(REQUEST_LOCATION_METADATA_ACCESS, true)
    }

    fun isPickerUri(uri: Uri): Boolean = uri.authority?.substringAfter('@') == MediaStore.AUTHORITY &&
        uri.pathSegments.firstOrNull()?.startsWith("picker") == true

    fun open(context: Context, selected: Uri): InputStream? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        return openSelected(selected, granted, { uri -> MediaStore.getMediaUri(context, uri) },
            { uri -> context.contentResolver.openInputStream(uri) })
    }

    /** Injectable seam for refusal, unsupported provider and picker privacy tests. */
    internal fun openSelected(selected: Uri, granted: Boolean, mediaUri: (Uri) -> Uri?,
        open: (Uri) -> InputStream?): InputStream? {
        if (granted && selected.scheme == "content" && !isPickerUri(selected)) {
            val media = if (selected.authority?.substringAfter('@') == MediaStore.AUTHORITY) selected
                else try { mediaUri(selected) } catch (_: SecurityException) { null }
                    catch (_: IllegalArgumentException) { null } catch (_: UnsupportedOperationException) { null }
            if (media != null && media.scheme == "content" && media.authority?.substringAfter('@') == MediaStore.AUTHORITY && !isPickerUri(media)) {
                try { open(MediaStore.setRequireOriginal(media))?.let { return it } }
                catch (_: SecurityException) { /* Permission can be revoked while importing. */ }
                catch (_: UnsupportedOperationException) { /* Provider cannot supply original bytes. */ }
                catch (_: java.io.FileNotFoundException) { /* Original route unavailable; selected URI may work. */ }
                catch (_: IllegalArgumentException) { /* OEM/provider does not implement this route. */ }
            }
        }
        return open(selected)
    }
}
