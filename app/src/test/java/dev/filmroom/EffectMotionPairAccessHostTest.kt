package dev.filmroom

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.effects.EffectMotionPairAccess
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EffectMotionPairAccessHostTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val image = "content://media/external_primary/images/media/10"
    @Before fun reset() {
        context.getSharedPreferences("effect-motion-pairs", Context.MODE_PRIVATE).edit().clear().commit()
        EffectMotionPairAccess.clearSessionForTests()
        shadowOf(context).denyPermissions(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }
    @Test fun transientSelectionIsNotPersistedButWorksInSession() {
        val video = Uri.parse("content://picked/video/20")
        assertFalse(EffectMotionPairAccess.rememberSelected(context, image, video) { false })
        assertEquals(listOf(video.toString()), EffectMotionPairAccess.candidates(context, image))
        assertTrue(context.getSharedPreferences("effect-motion-pairs", 0).all.isEmpty())
        EffectMotionPairAccess.clearSessionForTests()
        assertTrue(EffectMotionPairAccess.candidates(context, image).isEmpty())
    }
    @Test fun durableSelectionUsesHashedImageKeyAndNoRawImageInPrefs() {
        val video = Uri.parse("content://picked/video/21")
        assertTrue(EffectMotionPairAccess.rememberSelected(context, image, video) { true })
        val prefs = context.getSharedPreferences("effect-motion-pairs", 0).all
        assertEquals(1, prefs.size)
        assertTrue(prefs.keys.single().matches(Regex("[0-9a-f]{64}")))
        assertFalse(prefs.keys.contains(image))
        EffectMotionPairAccess.clearSessionForTests()
        assertEquals(listOf(video.toString()), EffectMotionPairAccess.candidates(context, image))
    }
    @Test fun temporaryReplacementRemovesOldDurableLink() {
        EffectMotionPairAccess.rememberSelected(context, image, Uri.parse("content://picked/video/1")) { true }
        EffectMotionPairAccess.rememberSelected(context, image, Uri.parse("content://picked/video/2")) { false }
        assertTrue(context.getSharedPreferences("effect-motion-pairs", 0).all.isEmpty())
        assertEquals(listOf("content://picked/video/2"), EffectMotionPairAccess.candidates(context, image))
    }
    @Test fun pickerAndDocumentUrisAreNeverRewrittenIntoMediaStoreUris() {
        assertNull(EffectMotionPairAccess.directImageVolume(Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/10")))
        assertNull(EffectMotionPairAccess.directImageVolume(Uri.parse("content://com.android.providers.media.documents/document/image:10")))
        assertNull(EffectMotionPairAccess.directImageVolume(Uri.parse("file:///external_primary/images/media/10")))
        assertEquals("external_primary", EffectMotionPairAccess.directImageVolume(Uri.parse(image)))
    }
    @Test fun automaticSearchNeedsVideoPermissionAndUsesExactLocation() {
        val provider = MediaProvider()
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        assertTrue(EffectMotionPairAccess.candidates(context, image).isEmpty())
        assertEquals(0, provider.queries)
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        val found = EffectMotionPairAccess.candidates(context, image)
        assertEquals("queries=${provider.queries} permission=${context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO)} selection=${provider.videoSelection} args=${provider.videoArgs?.toList()}", listOf("content://media/external_primary/video/media/20"), found)
        assertEquals(2, provider.queries)
        assertArrayEquals(arrayOf("DCIM/Camera/", "IMG_1.mp4", "IMG_1.MP4"), provider.videoArgs)
        assertTrue(provider.videoSelection!!.contains("relative_path = ?"))
    }
    @Test @Config(sdk = [29]) fun legacyPermissionStillUsesSameDirectoryLookup() {
        val provider = MediaProvider()
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        assertEquals(listOf("content://media/external_primary/video/media/20"), EffectMotionPairAccess.candidates(context, image))
        assertEquals(2, provider.queries)
        assertArrayEquals(arrayOf("DCIM/Camera/", "IMG_1.mp4", "IMG_1.MP4"), provider.videoArgs)
    }
    @Test fun tooManyCandidatesDoNotBecomeTruncatedGuess() {
        val provider = MediaProvider().apply { count = 9 }
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        assertTrue(EffectMotionPairAccess.candidates(context, image).isEmpty())
        assertEquals(2, provider.queries)
    }
    @Test fun api33RequestsOnlyVideoPermission() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_VIDEO), EffectMotionPairAccess.requiredPermissions())
    }
    @Test @Config(sdk = [29, 32]) fun legacyRequestsStoragePermission() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), EffectMotionPairAccess.requiredPermissions())
    }
    @Test @Config(sdk = [34]) fun partialVideoAccessIsQueryableAndRevocationStopsQueries() {
        assertArrayEquals(arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED), EffectMotionPairAccess.requiredPermissions())
        val provider = installProvider()
        assertFalse(EffectMotionPairAccess.canQueryVideos(context))
        assertTrue(EffectMotionPairAccess.candidates(context, image).isEmpty())
        assertEquals(0, provider.queries)
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertTrue(EffectMotionPairAccess.canQueryVideos(context))
        assertFalse(EffectMotionPairAccess.hasFullVideoAccess(context))
        assertEquals(1, EffectMotionPairAccess.candidates(context, image).size)
        shadowOf(context).denyPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertTrue(EffectMotionPairAccess.candidates(context, image).isEmpty())
        assertEquals(2, provider.queries)
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        assertTrue(EffectMotionPairAccess.canQueryVideos(context))
        assertTrue(EffectMotionPairAccess.hasFullVideoAccess(context))
    }
    @Test fun pickerAndDocumentUseOnlyAuthorizedNameAndReturnAllCandidatesForValidation() {
        val provider = installProvider().apply { count = 2 }
        ShadowContentResolver.registerProviderInternal("com.android.providers.media.documents", provider)
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        for (uri in listOf("content://media/picker/0/provider/media/999", "content://com.android.providers.media.documents/document/image:999")) {
            assertEquals(listOf("content://media/external/video/media/20", "content://media/external/video/media/21"), EffectMotionPairAccess.candidates(context, uri))
            assertArrayEquals(arrayOf("_display_name"), provider.imageProjection)
            assertEquals(uri, provider.imageUri.toString())
            assertArrayEquals(arrayOf("IMG_1.mp4", "IMG_1.MP4"), provider.videoArgs)
            assertFalse(provider.videoSelection!!.contains("relative_path"))
        }
    }
    @Test fun missingRelativePathUsesNameOnlyAndOverflowIsRejected() {
        val provider = installProvider().apply { path = null }
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        assertEquals(1, EffectMotionPairAccess.candidates(context, image).size)
        assertArrayEquals(arrayOf("IMG_1.mp4", "IMG_1.MP4"), provider.videoArgs)
        provider.count = 9
        assertTrue(EffectMotionPairAccess.candidates(context, "content://media/picker/0/provider/media/999").isEmpty())
    }
    @Test fun deniedImageMetadataCannotTriggerVideoQuery() {
        val provider = installProvider().apply { denyImage = true }
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        assertTrue(EffectMotionPairAccess.candidates(context, "content://media/picker/0/provider/media/999").isEmpty())
        assertEquals(1, provider.queries)
        assertNull(provider.videoSelection)
    }
    @Test fun lookupReasonsAreTypedAndDoNotExposeMetadata() {
        var reason:dev.filmroom.diagnostics.PairLookupStatus?=null
        val report:(dev.filmroom.diagnostics.PairLookupStatus)->Unit={reason=it}
        EffectMotionPairAccess.candidates(context,image,report=report)
        assertEquals(dev.filmroom.diagnostics.PairLookupStatus.NO_PERMISSION,reason)
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        val provider=installProvider().apply { count=0 }
        EffectMotionPairAccess.candidates(context,image,report=report)
        assertEquals(dev.filmroom.diagnostics.PairLookupStatus.NO_VISIBLE_SAME_NAME,reason)
        provider.count=9
        EffectMotionPairAccess.candidates(context,image,report=report)
        assertEquals(dev.filmroom.diagnostics.PairLookupStatus.TOO_MANY_CANDIDATES,reason)
        provider.denyImage=true
        EffectMotionPairAccess.candidates(context,image,report=report)
        assertEquals(dev.filmroom.diagnostics.PairLookupStatus.ACCESS_DENIED,reason)
    }
    @Test fun nativeIdLookupUsesBoundArgumentWithoutReadingPickerName() {
        val provider=installProvider()
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        val id="test' OR 1=1 --"
        assertEquals(listOf("content://media/external/video/media/20"),EffectMotionPairAccess.candidates(context,image,pairId=id))
        assertEquals(1,provider.queries)
        assertNull(provider.imageUri)
        assertEquals("live_photo =?",provider.videoSelection)
        assertArrayEquals(arrayOf(id),provider.videoArgs)
    }
    @Test fun idFailureFallsBackAndOverflowDoesNot() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        for(mode in listOf("missing","denied","empty")) {
            val provider=installProvider().apply { idMode=mode }
            var reason:dev.filmroom.diagnostics.PairLookupStatus?=null
            assertEquals(1,EffectMotionPairAccess.candidates(context,image,pairId="synthetic-id",idReport={reason=it}).size)
            assertEquals(3,provider.queries)
            assertEquals(when(mode) {
                "missing"->dev.filmroom.diagnostics.PairLookupStatus.QUERY_FAILED
                "denied"->dev.filmroom.diagnostics.PairLookupStatus.ACCESS_DENIED
                else->dev.filmroom.diagnostics.PairLookupStatus.NO_VISIBLE_ID
            },reason)
        }
        val provider=installProvider().apply { count=9 }
        assertTrue(EffectMotionPairAccess.candidates(context,image,pairId="synthetic-id").isEmpty())
        assertEquals(1,provider.queries)
        provider.count=2;provider.duplicate=true
        assertEquals(1,EffectMotionPairAccess.candidates(context,image,pairId="synthetic-id").size)
        provider.duplicate=false
        assertEquals(2,EffectMotionPairAccess.candidates(context,image,pairId="synthetic-id").size)
    }
    @Test @Config(sdk=[29,33,34]) fun idQueryRespectsVideoPermissions() {
        val provider=installProvider()
        assertTrue(EffectMotionPairAccess.candidates(context,image,pairId="synthetic-id").isEmpty())
        assertEquals(0,provider.queries)
        val permission=if(android.os.Build.VERSION.SDK_INT>=34) Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            else if(android.os.Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        shadowOf(context).grantPermissions(permission)
        assertEquals(1,EffectMotionPairAccess.candidates(context,image,pairId="synthetic-id").size)
        assertEquals(1,provider.queries)
    }
    @Test fun invalidIdsNeverReachVendorQuery() {
        val provider=installProvider()
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_VIDEO)
        for(id in listOf("", "x".repeat(257), "bad\u0000id")) {
            var reason:dev.filmroom.diagnostics.PairLookupStatus?=null
            EffectMotionPairAccess.candidates(context,image,pairId=id,idReport={reason=it})
            assertEquals(dev.filmroom.diagnostics.PairLookupStatus.INVALID_ID,reason)
            assertFalse(provider.videoSelection!!.contains("live_photo"))
        }
    }
    private fun installProvider() = MediaProvider().also {
        it.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", it)
    }
    private class MediaProvider : ContentProvider() {
        var queries = 0
        var idMode="normal"
        var duplicate=false
        var count = 1
        var path: String? = "DCIM/Camera/"
        var denyImage = false
        var imageProjection: Array<out String>? = null
        var imageUri: Uri? = null
        var videoArgs: Array<out String>? = null
        var videoSelection: String? = null
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            return if (!uri.pathSegments.contains("video")) {
                imageProjection = projection; imageUri = uri
                if (denyImage) throw SecurityException("Image grant revoked")
                MatrixCursor(projection!!).apply {
                    addRow(projection.map { if (it == "_display_name") "IMG_1.jpg" else path }.toTypedArray())
                }
            } else {
                videoArgs = args; videoSelection = selection
                if(selection=="live_photo =?") when(idMode) {
                    "missing" -> throw IllegalArgumentException("no such column")
                    "denied" -> throw SecurityException("denied")
                    "empty" -> return MatrixCursor(arrayOf("_id"))
                }
                MatrixCursor(arrayOf("_id")).apply { repeat(this@MediaProvider.count) { addRow(arrayOf(20L + if(duplicate) 0 else it)) } }
            }
        }
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
}
