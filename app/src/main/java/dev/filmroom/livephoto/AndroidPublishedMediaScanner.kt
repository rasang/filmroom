package dev.filmroom.livephoto

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import dev.filmroom.livephoto.core.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Public scanner API for our existing MediaStore rows; no broad storage permission or new inserts. */
internal class AndroidPublishedMediaScanner(private val context: Context): PublishedMediaScanner {
    @Suppress("DEPRECATION") // DATA is read only for our own already-published row; never used to insert or guess paths.
    override fun scan(mediaId: String, mimeType: String): MediaScanResult {
        fun result(status: MediaScanStatus, detail: String? = null): MediaScanResult {
            // No user path, URI, or filename is written to diagnostics.
            Log.i("FilmroomLiveScan", "2014 pair scan $mimeType: $status ${detail.orEmpty()}")
            return MediaScanResult(mediaId, status, detail)
        }
        val uri = Uri.parse(mediaId)
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY)
            return result(MediaScanStatus.PATH_UNAVAILABLE, "Not a MediaStore row")
        val path = try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(1) != context.packageName || cursor.getInt(2) != 0) null
                else cursor.getString(0)?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) { return result(MediaScanStatus.PATH_UNAVAILABLE, e.javaClass.simpleName) }
            ?: return result(MediaScanStatus.PATH_UNAVAILABLE, "Owned published path unavailable")
        try {
            val file = File(path)
            if (!file.isAbsolute || !file.isFile || !file.canRead())
                return result(MediaScanStatus.PATH_UNAVAILABLE, "Published file not readable")
        } catch (e: SecurityException) { return result(MediaScanStatus.PATH_UNAVAILABLE, e.javaClass.simpleName) }
        val complete = CountDownLatch(1)
        val returned = AtomicReference<Uri?>(null)
        return try {
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType)) { _, scanned ->
                returned.set(scanned); complete.countDown()
            }
            if (!complete.await(5, TimeUnit.SECONDS)) result(MediaScanStatus.TIMED_OUT)
            else {
                val scanned = returned.get()
                when {
                    scanned == null -> result(MediaScanStatus.REQUEST_FAILED, "Scanner returned no URI")
                    sameRow(uri, scanned) -> result(MediaScanStatus.SCANNED)
                    else -> result(MediaScanStatus.DIFFERENT_URI, "Scanner returned a different row; no automatic retry")
                }
            }
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            result(MediaScanStatus.REQUEST_FAILED, e.javaClass.simpleName)
        } catch (e: LinkageError) { result(MediaScanStatus.REQUEST_FAILED, e.javaClass.simpleName) }
    }

    private fun sameRow(original: Uri, scanned: Uri): Boolean = try {
        // The scanner may return the external alias for external_primary on the same volume.
        original.authority == scanned.authority &&
            scanned.pathSegments.getOrNull(1) in setOf(original.pathSegments.getOrNull(1), "file") &&
            scanned.pathSegments.firstOrNull() in setOf(original.pathSegments.firstOrNull(), "external") &&
            ContentUris.parseId(original) == ContentUris.parseId(scanned)
    } catch (_: Exception) { false }
}
