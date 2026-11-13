package dev.filmroom.export

import android.app.Application
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.app.RemoteAction
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Fake provider only: no real gallery or original photo is touched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class ExportMediaDeletionCoordinatorHostTest {
    class Provider : ContentProvider() {
        val rows = mutableMapOf<Uri, ContentValues>()
        val deleted = mutableListOf<Uri>()
        var queryError: RuntimeException? = null
        var deleteError: RuntimeException? = null
        var nullCursor = false
        var zeroDelete = false
        var beforeDelete: (() -> Unit)? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("unused")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
            check(selection == null && args == null)
            queryError?.let { throw it }
            if (nullCursor) return null
            return MatrixCursor(projection!!).apply { rows[uri]?.let { row -> addRow(projection.map { row.get(it) }.toTypedArray()) } }
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            check(uri.pathSegments.last().toLong() > 0)
            check(selection == "owner_package_name=? AND _display_name=? AND relative_path=?")
            beforeDelete?.invoke()
            deleteError?.let { throw it }
            deleted += uri
            if (zeroDelete) return 0
            val row = rows[uri] ?: return 0
            if (args?.toList() != listOf(row.getAsString("owner_package_name"), row.getAsString("_display_name"), row.getAsString("relative_path"))) return 0
            return if (rows.remove(uri) != null) 1 else 0
        }
    }
    private val output = "content://media/external_primary/images/media/42"
    private val stamp = 1730000000000L
    private val outputName = "export_${stamp}_abcdef.png"
    private fun setup(sdk: Int = 29): Triple<Provider, ExportMediaDeletionCoordinator, MutableList<Uri>> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val provider = Provider().apply {
            attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media" })
            rows[Uri.parse(output)] = ContentValues().apply {
                put("_id", 42); put("owner_package_name", app.packageName)
                put("relative_path", "Pictures/暗房/"); put("_display_name", "export_${stamp}_abcdef.png")
            }
        }
        ShadowContentResolver.registerProviderInternal("media", provider)
        val requests = mutableListOf<Uri>()
        val coordinator = ExportMediaDeletionCoordinator(app.contentResolver, app.packageName, sdk) { uri ->
            requests += uri
            sender()
        }
        return Triple(provider, coordinator, requests)
    }
    private fun sender() = PendingIntent.getActivity(ApplicationProvider.getApplicationContext<Application>(), 0,
        Intent("dev.filmroom.TEST_DELETE"), PendingIntent.FLAG_IMMUTABLE).intentSender
    @Test fun ownExactOutputDeletesWithoutTouchingAnyOtherRow() = runBlocking {
        val (p, c, _) = setup()
        val other = Uri.parse("content://media/external_primary/images/media/43")
        p.rows[other] = ContentValues(p.rows[Uri.parse(output)])
        assertEquals(ExportMediaDeletionResult.Deleted, c.delete(output, stamp, outputName))
        assertEquals(listOf(Uri.parse(output)), p.deleted)
        assertTrue(p.rows.containsKey(other))
    }
    @Test fun badUrisNeverReachDelete() = runBlocking {
        val (p, c, _) = setup()
        listOf("file:///sdcard/DCIM/original.jpg", "content://evil/external_primary/images/media/42",
            "content://media/external_primary/images/media", "$output?x=1", "$output#x", "$output/",
            "content://media/external_primary/images/media/042", "content://media/external_primary/images%2Fmedia/42",
            "content://media/external_primary/file/42", "content://provider/tree/dir/document/photo").forEach {
            assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.INVALID_URI), c.delete(it, stamp, outputName))
        }
        assertTrue(p.deleted.isEmpty())
    }
    @Test fun originalForeignAndReusedRowsFailClosed() = runBlocking {
        for ((column, value) in listOf("owner_package_name" to "other.app", "relative_path" to "DCIM/Camera/",
            "_display_name" to "original.png", "_display_name" to "export_${stamp + 1}_abcdef.png", "_id" to "99")) {
            val (p, c, _) = setup()
            p.rows[Uri.parse(output)]!!.put(column, value)
            assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.UNVERIFIED_IDENTITY), c.delete(output, stamp, outputName))
            assertTrue(p.deleted.isEmpty())
        }
        val (p, c, _) = setup()
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.UNVERIFIED_IDENTITY), c.delete(output, null, outputName))
        assertTrue(p.deleted.isEmpty())
    }
    @Test fun missingIsDifferentFromDeniedNullCursorAndProviderFailure() = runBlocking {
        val (p, c, _) = setup()
        p.rows.clear()
        assertEquals(ExportMediaDeletionResult.Missing, c.delete(output, stamp, outputName))
        p.nullCursor = true
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.INACCESSIBLE), c.delete(output, stamp, outputName))
        p.nullCursor = false
        for (error in listOf(SecurityException("denied"), IllegalStateException("provider down"))) {
            p.queryError = error
            assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.INACCESSIBLE), c.delete(output, stamp, outputName))
        }
        assertTrue(p.deleted.isEmpty())
    }
    @Test fun zeroDeleteWithExistingRowIsFailureNotMissing() = runBlocking {
        val (p, c, _) = setup(); p.zeroDelete = true
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.DELETE_FAILED), c.delete(output, stamp, outputName))
    }
    @Test fun api30ConsentCancellationAndCompletionNeverRetryDelete() = runBlocking {
        val (p, c, requests) = setup(30); p.deleteError = SecurityException("requires consent")
        val result = c.delete(output, stamp, outputName) as ExportMediaDeletionResult.NeedsConsent
        assertEquals(ExportMediaDeletionConsentKind.DELETE_REQUEST, result.kind)
        assertEquals(listOf(Uri.parse(output)), requests)
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.CANCELLED), c.resume(output, stamp, outputName, result.kind, false))
        assertTrue(p.rows.containsKey(Uri.parse(output)))
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.DELETE_FAILED), c.resume(output, stamp, outputName, result.kind, true))
        p.rows.clear() // Simulate Android's deletion completed before RESULT_OK.
        assertEquals(ExportMediaDeletionResult.Deleted, c.resume(output, stamp, outputName, result.kind, true))
        assertTrue(p.deleted.isEmpty())
    }
    @Test fun api29RecoverableConsentRetriesOnlyAfterGrantedAndRevalidatesIdentity() = runBlocking {
        val (p, c, _) = setup()
        val app = ApplicationProvider.getApplicationContext<Application>()
        p.deleteError = RecoverableSecurityException(SecurityException("consent"), "delete",
            RemoteAction(Icon.createWithResource(app, android.R.drawable.ic_delete), "delete", "delete",
                PendingIntent.getActivity(app, 1, Intent("test"), PendingIntent.FLAG_IMMUTABLE)))
        val result = c.delete(output, stamp, outputName) as ExportMediaDeletionResult.NeedsConsent
        assertEquals(ExportMediaDeletionConsentKind.RECOVERABLE_WRITE, result.kind)
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.CANCELLED), c.resume(output, stamp, outputName, result.kind, false))
        p.deleteError = null
        p.rows[Uri.parse(output)]!!.put("_display_name", "export_${stamp + 1}_abcdef.png")
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.UNVERIFIED_IDENTITY), c.resume(output, stamp, outputName, result.kind, true))
        assertTrue(p.deleted.isEmpty())
        p.rows[Uri.parse(output)]!!.put("_display_name", "export_${stamp}_abcdef.png")
        assertEquals(ExportMediaDeletionResult.Deleted, c.resume(output, stamp, outputName, result.kind, true))
    }
    @Test fun exactNameRequiredEvenForSameMillisecondAndLegacyRecords() = runBlocking {
        val (p, c, _) = setup()
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.UNVERIFIED_IDENTITY), c.delete(output, stamp, null))
        p.rows[Uri.parse(output)]!!.put("_display_name", "export_${stamp}_fedcba.png")
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.UNVERIFIED_IDENTITY), c.delete(output, stamp, outputName))
        assertTrue(p.deleted.isEmpty())
    }
    @Test fun directDeletePredicateRejectsIdentityChangeAfterQuery() = runBlocking {
        val (p, c, _) = setup()
        p.beforeDelete = { p.rows[Uri.parse(output)]!!.put("_display_name", "other_${stamp}_fedcba.png") }
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.DELETE_FAILED), c.delete(output, stamp, outputName))
        assertTrue(p.rows.containsKey(Uri.parse(output)))
    }
    @Test fun downloadsItemUsesSameStrictSafetyChecks() = runBlocking {
        val (p, c, _) = setup()
        val uri = "content://media/external_primary/downloads/42"
        val name = "export_${stamp}_abcdef.tif"
        p.rows[Uri.parse(uri)] = ContentValues(p.rows.remove(Uri.parse(output))).apply {
            put("relative_path", "Download/暗房/"); put("_display_name", name)
        }
        assertEquals(ExportMediaDeletionResult.Deleted, c.delete(uri, stamp, name))
        assertEquals(listOf(Uri.parse(uri)), p.deleted)
    }
    @Test fun permissionRefusalAndProviderRuntimeErrorsAreNotMissing() = runBlocking {
        val (p, c, requests) = setup()
        p.deleteError = SecurityException("non recoverable")
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.INACCESSIBLE), c.delete(output, stamp, outputName))
        p.deleteError = UnsupportedOperationException("provider unavailable")
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.DELETE_FAILED), c.delete(output, stamp, outputName))
        assertTrue(requests.isEmpty())
        assertTrue(p.rows.containsKey(Uri.parse(output)))
    }

    @Test fun api29PlatformFactoryIsGuardedEvenIfInvokedThroughUnexpectedSdkRouting() = runBlocking {
        val (p, _, _) = setup()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val platform = ExportMediaDeletionCoordinator(app)
        @Suppress("UNCHECKED_CAST")
        val factory = ExportMediaDeletionCoordinator::class.java.getDeclaredField("requestDelete").apply {
            isAccessible = true
        }.get(platform) as (Uri) -> android.content.IntentSender
        try {
            factory(Uri.parse(output))
            fail("API29 must never execute MediaStore.createDeleteRequest")
        } catch (_: SecurityException) { /* Expected fail-closed platform boundary. */ }
        p.deleteError = SecurityException("requires consent")
        // Exercise the coordinator catch boundary using its real platform factory, not a fake sender.
        val unexpectedRoute = ExportMediaDeletionCoordinator(app.contentResolver, app.packageName, 30, factory)
        assertEquals(ExportMediaDeletionResult.Failed(ExportMediaDeletionFailure.INACCESSIBLE),
            unexpectedRoute.delete(output, stamp, outputName))
        assertTrue(p.deleted.isEmpty())
        assertTrue(p.rows.containsKey(Uri.parse(output)))
    }

    @Test fun livePairImageAndVideoDeleteOnlyExactStoredMembers() = runBlocking {
        val (p,c,_) = setup()
        val image=Uri.parse(output);val video=Uri.parse("content://media/external_primary/video/media/77")
        val stem="Filmroom_0123456789abcdef0123456789abcdef_MP"
        p.rows[image]!!.put("relative_path","DCIM/Filmroom/");p.rows[image]!!.put("_display_name","$stem.jpeg")
        p.rows[video]=ContentValues(p.rows[image]).apply{put("_id",77);put("_display_name","$stem.mp4")}
        assertEquals(ExportMediaDeletionResult.Deleted,c.delete(image.toString(),null,"$stem.jpeg",true))
        assertEquals(ExportMediaDeletionResult.Deleted,c.delete(video.toString(),null,"$stem.mp4",true))
        assertEquals(listOf(image,video),p.deleted)
    }
    @Test fun liveVideoRequiresExplicitLiveScopeAndExactIdentity() = runBlocking {
        val (p,c,_) = setup()
        val video=Uri.parse("content://media/external_primary/video/media/77")
        val name="Filmroom_0123456789abcdef0123456789abcdef_MP.mp4"
        p.rows[video]=ContentValues(p.rows[Uri.parse(output)]).apply{put("_id",77);put("_display_name",name);put("relative_path","DCIM/Filmroom/")}
        assertTrue(c.delete(video.toString(),stamp,name) is ExportMediaDeletionResult.Failed)
        assertTrue(c.delete(video.toString(),null,name.replace("01234567","aaaaaaaa"),true) is ExportMediaDeletionResult.Failed)
        p.rows[video]!!.put("relative_path","DCIM/Camera/")
        assertTrue(c.delete(video.toString(),null,name,true) is ExportMediaDeletionResult.Failed)
        assertTrue(p.deleted.isEmpty())
    }
    @Test fun liveConsentResumeUsesVideoUriWithoutDeletingAgain() = runBlocking {
        val (p,c,_) = setup(30)
        val video="content://media/external_primary/video/media/77"
        val name="Filmroom_0123456789abcdef0123456789abcdef_MP.mp4"
        assertEquals(ExportMediaDeletionResult.Deleted,c.resume(video,null,name,ExportMediaDeletionConsentKind.DELETE_REQUEST,true,true))
        assertTrue(p.deleted.isEmpty())
    }

}
