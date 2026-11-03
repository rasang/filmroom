package dev.filmroom.export

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** The caller must persist and bind pending consent to its immutable operation/record snapshot. */
enum class ExportMediaDeletionConsentKind { DELETE_REQUEST, RECOVERABLE_WRITE }
enum class ExportMediaDeletionFailure {
    CANCELLED, INVALID_URI, UNVERIFIED_IDENTITY, INACCESSIBLE, DELETE_FAILED
}
sealed interface ExportMediaDeletionResult {
    data object Deleted : ExportMediaDeletionResult
    data object Missing : ExportMediaDeletionResult
    data class NeedsConsent(val sender: IntentSender, val kind: ExportMediaDeletionConsentKind) : ExportMediaDeletionResult
    data class Failed(val reason: ExportMediaDeletionFailure) : ExportMediaDeletionResult
}

/**
 * Deletes only stored export output URIs. Never pass input/photo picker URIs here.
 * No filesystem fallback, URI conversion, collection delete, recursive tree delete, or permission bypass.
 * No retained Activity and no work on construction/recreation. Caller serializes operations, launches
 * consent on the foreground Activity, and commits only the matching unchanged record after success.
 * This does not promise rollback: earlier items in a clear operation may already have been deleted.
 */
class ExportMediaDeletionCoordinator internal constructor(
    private val resolver: ContentResolver,
    private val packageName: String,
    private val sdk: Int,
    private val requestDelete: (Uri) -> IntentSender,
) {
    constructor(context: Context) : this(
        context.applicationContext.contentResolver,
        context.applicationContext.packageName,
        Build.VERSION.SDK_INT,
        { uri ->
            // Guard the platform call itself: the stored/test SDK value is not a runtime API guard.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(context.applicationContext.contentResolver, listOf(uri)).intentSender
            } else {
                throw SecurityException("System delete requests require Android 11 or newer")
            }
        },
    )

    suspend fun delete(outputUri: String, expectedExportMillis: Long?, expectedDisplayName: String?): ExportMediaDeletionResult = withContext(Dispatchers.IO) {
        coroutineContext.ensureActive()
        val uri = exactOutputUri(outputUri) ?: return@withContext failed(ExportMediaDeletionFailure.INVALID_URI)
        deleteChecked(uri, expectedExportMillis, expectedDisplayName, allowConsent = true)
    }

    /**
     * Accept only a result for the caller's still-current persisted URI+kind+operation token.
     * A cancelled prompt never triggers a query or deletion. A lost callback must not be inferred
     * as approval after restart: leave the record and require a fresh user action.
     */
    suspend fun resume(outputUri: String, expectedExportMillis: Long?, expectedDisplayName: String?, consentKind: ExportMediaDeletionConsentKind, granted: Boolean): ExportMediaDeletionResult =
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            if (!granted) return@withContext failed(ExportMediaDeletionFailure.CANCELLED)
            val uri = exactOutputUri(outputUri) ?: return@withContext failed(ExportMediaDeletionFailure.INVALID_URI)
            when (consentKind) {
                ExportMediaDeletionConsentKind.DELETE_REQUEST -> {
                    if (sdk < 30) return@withContext failed(ExportMediaDeletionFailure.INVALID_URI)
                    // Android completes the delete before RESULT_OK. Never issue another delete here.
                    when (inspect(uri, expectedExportMillis, expectedDisplayName)) {
                        Inspection.Missing -> ExportMediaDeletionResult.Deleted
                        is Inspection.Owned -> failed(ExportMediaDeletionFailure.DELETE_FAILED)
                        is Inspection.Failure -> failed(ExportMediaDeletionFailure.INACCESSIBLE)
                    }
                }
                ExportMediaDeletionConsentKind.RECOVERABLE_WRITE -> {
                    if (sdk != 29) return@withContext failed(ExportMediaDeletionFailure.INVALID_URI)
                    deleteChecked(uri, expectedExportMillis, expectedDisplayName, allowConsent = false)
                }
            }
        }

    private suspend fun deleteChecked(uri: Uri, expectedExportMillis: Long?, expectedDisplayName: String?, allowConsent: Boolean): ExportMediaDeletionResult {
        val identity = when (val inspection = inspect(uri, expectedExportMillis, expectedDisplayName)) {
            Inspection.Missing -> return ExportMediaDeletionResult.Missing
            is Inspection.Failure -> return failed(inspection.reason)
            is Inspection.Owned -> inspection
        }
        coroutineContext.ensureActive()
        return try {
            // Item URI plus an atomic identity predicate prevents an intervening row replacement
            // from turning this into deletion of a different photo. Never relax this selection.
            val count = resolver.delete(uri,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(packageName, identity.name, identity.path))
            if (count == 1) ExportMediaDeletionResult.Deleted
            else if (count == 0 && inspect(uri, expectedExportMillis, expectedDisplayName) == Inspection.Missing) ExportMediaDeletionResult.Missing
            else failed(ExportMediaDeletionFailure.DELETE_FAILED)
        } catch (error: SecurityException) {
            if (!allowConsent) return failed(ExportMediaDeletionFailure.INACCESSIBLE)
            try {
                when {
                    sdk >= 30 -> {
                        // Recheck immediately before handing the exact URI to the OS. The OS owns
                        // the consent UI/deletion; its request API has no identity-predicate parameter.
                        if (inspect(uri, expectedExportMillis, expectedDisplayName) != identity)
                            failed(ExportMediaDeletionFailure.UNVERIFIED_IDENTITY)
                        else ExportMediaDeletionResult.NeedsConsent(requestDelete(uri), ExportMediaDeletionConsentKind.DELETE_REQUEST)
                    }
                    sdk == 29 && error is RecoverableSecurityException -> ExportMediaDeletionResult.NeedsConsent(
                        error.userAction.actionIntent.intentSender, ExportMediaDeletionConsentKind.RECOVERABLE_WRITE)
                    else -> failed(ExportMediaDeletionFailure.INACCESSIBLE)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed(ExportMediaDeletionFailure.INACCESSIBLE)
            }
        } catch (_: java.io.IOException) {
            failed(ExportMediaDeletionFailure.DELETE_FAILED)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            failed(ExportMediaDeletionFailure.DELETE_FAILED)
        }
    }

    private sealed interface Inspection {
        data class Owned(val name: String, val path: String) : Inspection
        data object Missing : Inspection
        data class Failure(val reason: ExportMediaDeletionFailure) : Inspection
    }

    private fun inspect(uri: Uri, expectedExportMillis: Long?, expectedDisplayName: String?): Inspection = try {
        if (sdk < 29) Inspection.Failure(ExportMediaDeletionFailure.UNVERIFIED_IDENTITY)
        else resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            // Only an actual empty cursor proves missing. Null cursor and denied query never do.
            if (!cursor.moveToFirst()) return@use Inspection.Missing
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val owner = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME))
            val path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
            val downloads = uri.pathSegments[1] == "downloads"
            val expectedPath = if (downloads) "Download/暗房" else "Pictures/暗房"
            val namePattern = if (downloads) DOWNLOAD_NAME else IMAGE_NAME
            if (cursor.count != 1 || id.toString() != uri.lastPathSegment || owner != packageName ||
                path?.trimEnd('/') != expectedPath || name == null || name != expectedDisplayName || !namePattern.matches(name) ||
                expectedExportMillis == null || expectedExportMillis <= 0 || !name.matches(Regex(".+_${expectedExportMillis}_[0-9a-f]{6}\\.[a-z0-9]+")))
                Inspection.Failure(ExportMediaDeletionFailure.UNVERIFIED_IDENTITY)
            else Inspection.Owned(name, checkNotNull(path))
        } ?: Inspection.Failure(ExportMediaDeletionFailure.INACCESSIBLE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Inspection.Failure(ExportMediaDeletionFailure.INACCESSIBLE)
    }

    companion object {
        private val IMAGE_NAME = Regex(".+_[0-9]+_[0-9a-f]{6}\\.(jpg|png)")
        private val DOWNLOAD_NAME = Regex(".+_[0-9]+_[0-9a-f]{6}\\.tif")
        private fun failed(reason: ExportMediaDeletionFailure) = ExportMediaDeletionResult.Failed(reason)

        /** Explicitly support only the two collections actually written by PhotoRepository. */
        internal fun exactOutputUri(raw: String): Uri? {
            val uri = Uri.parse(raw)
            if (uri.scheme != "content" || uri.encodedAuthority != MediaStore.AUTHORITY ||
                uri.query != null || uri.fragment != null) return null
            val parts = uri.pathSegments
            if (parts.firstOrNull() != MediaStore.VOLUME_EXTERNAL_PRIMARY) return null
            val item = (parts.size == 4 && parts[1] == "images" && parts[2] == "media") ||
                (parts.size == 3 && parts[1] == "downloads")
            if (!item || parts.last().toLongOrNull()?.let { it > 0 && it.toString() == parts.last() } != true) return null
            // Reject encoded separators, duplicate/trailing slashes and alternate spellings.
            if (uri.encodedPath != "/" + parts.joinToString("/")) return null
            return uri
        }
    }
}
