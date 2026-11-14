package dev.filmroom.io

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import dev.filmroom.diagnostics.*
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
    fun open(context: Context, selected: Uri, observe:(GpsDiagnosticInfo)->Unit={}): InputStream? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        return openSelected(selected, granted, { uri -> MediaStore.getMediaUri(context, uri) },
            { uri -> context.contentResolver.openInputStream(uri) },observe)
    }
    /** Diagnostics describe the attempted stream route, not proof of unredacted bytes. */
    internal fun openSelected(selected: Uri, granted: Boolean, mediaUri: (Uri) -> Uri?,
        open: (Uri) -> InputStream?, observe:(GpsDiagnosticInfo)->Unit={}): InputStream? {
        var fallback:GpsOriginalFallback?=null
        fun report(route:GpsAccessRoute) { runCatching{observe(GpsDiagnosticInfo(locationPermission=granted,route=route,fallback=fallback))} }
        if (granted && selected.scheme == "content" && !isPickerUri(selected)) {
            val media = if (selected.authority?.substringAfter('@') == MediaStore.AUTHORITY) selected
                else try { mediaUri(selected) } catch (_: SecurityException) { fallback=GpsOriginalFallback.MAPPING_DENIED;null }
                    catch (_: IllegalArgumentException) { fallback=GpsOriginalFallback.MAPPING_INVALID;null }
                    catch (_: UnsupportedOperationException) { fallback=GpsOriginalFallback.MAPPING_UNSUPPORTED;null }
            if (media != null && media.scheme == "content" && media.authority?.substringAfter('@') == MediaStore.AUTHORITY && !isPickerUri(media)) {
                try {
                    report(GpsAccessRoute.ORIGINAL)
                    open(MediaStore.setRequireOriginal(media))?.let { return it }
                    fallback=GpsOriginalFallback.ORIGINAL_NULL
                }
                catch (_: SecurityException) { fallback=GpsOriginalFallback.ORIGINAL_DENIED }
                catch (_: UnsupportedOperationException) { fallback=GpsOriginalFallback.ORIGINAL_UNSUPPORTED }
                catch (_: java.io.FileNotFoundException) { fallback=GpsOriginalFallback.ORIGINAL_MISSING }
                catch (_: IllegalArgumentException) { fallback=GpsOriginalFallback.ORIGINAL_INVALID }
            } else if(fallback==null) fallback=GpsOriginalFallback.NO_MEDIA_MAPPING
        }
        report(if(isPickerUri(selected))GpsAccessRoute.PICKER_SELECTED else if(fallback!=null)GpsAccessRoute.SELECTED_FALLBACK else GpsAccessRoute.SELECTED)
        return open(selected)
    }
}
