package dev.filmroom.io

import dev.filmroom.diagnostics.*
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class PhotoLocationAccessTest {
    private val selected=Uri.parse("content://media/external/images/media/123")
    @Test fun grantedMediaRequestsOriginalBytes() {
        val seen=mutableListOf<Uri>()
        PhotoLocationAccess.openSelected(selected,true,{error("must not map")},{seen+=it;ByteArrayInputStream(byteArrayOf(1))})!!.close()
        assertEquals(listOf(MediaStore.setRequireOriginal(selected)),seen)
    }
    @Test fun deniedPermissionDoesNotTryOriginalOrMapUris() {
        val seen=mutableListOf<Uri>()
        PhotoLocationAccess.openSelected(selected,false,{error("must not map")},{seen+=it;ByteArrayInputStream(byteArrayOf())})!!.close()
        assertEquals(listOf(selected),seen)
    }
    @Test fun pickerPrivacyCannotBeBypassedEvenWithLocationPermission() {
        for(path in listOf("picker/0/provider/media/123","picker_get_content/0/provider/media/123")) {
            val picker=Uri.parse("content://media/$path");val seen=mutableListOf<Uri>()
            PhotoLocationAccess.openSelected(picker,true,{error("must not map picker")},{seen+=it;ByteArrayInputStream(byteArrayOf())})!!.close()
            assertEquals(listOf(picker),seen)
        }
    }
    @Test fun safMapsOnlyWithExistingPermission() {
        val document=Uri.parse("content://com.android.providers.media.documents/document/image%3A123")
        val seen=mutableListOf<Uri>()
        PhotoLocationAccess.openSelected(document,true,{assertEquals(document,it);selected},{seen+=it;ByteArrayInputStream(byteArrayOf())})!!.close()
        assertEquals(listOf(MediaStore.setRequireOriginal(selected)),seen)
    }
    @Test fun cloudProviderRemainsOnSelectedUri() {
        val cloud=Uri.parse("content://example.cloud/photo/abc");val seen=mutableListOf<Uri>()
        PhotoLocationAccess.openSelected(cloud,true,{null},{seen+=it;ByteArrayInputStream(byteArrayOf())})!!.close()
        assertEquals(listOf(cloud),seen)
    }
    @Test fun revokedOrUnsupportedOriginalFallsBackWithoutBlockingImport() {
        for(error in listOf(SecurityException(),UnsupportedOperationException(),java.io.FileNotFoundException(),IllegalArgumentException())) {
            val seen=mutableListOf<Uri>()
            PhotoLocationAccess.openSelected(selected,true,{null},{seen+=it;if(it!=selected)throw error;ByteArrayInputStream(byteArrayOf())})!!.close()
            assertEquals(listOf(MediaStore.setRequireOriginal(selected),selected),seen)
        }
    }
    @Test fun nullOriginalFallsBackToSelected() {
        val seen=mutableListOf<Uri>()
        PhotoLocationAccess.openSelected(selected,true,{null},{seen+=it;if(it==selected)ByteArrayInputStream(byteArrayOf())else null})!!.close()
        assertEquals(2,seen.size)
    }
    @Test fun modernPickerExplicitlyRequestsMetadataButOtherIntentsAreUntouched() {
        val picker=PhotoLocationAccess.requestPickerMetadata(Intent(MediaStore.ACTION_PICK_IMAGES))
        assertTrue(picker.getBooleanExtra(PhotoLocationAccess.REQUEST_LOCATION_METADATA_ACCESS,false))
        val files=PhotoLocationAccess.requestPickerMetadata(Intent(Intent.ACTION_OPEN_DOCUMENT))
        assertFalse(files.hasExtra(PhotoLocationAccess.REQUEST_LOCATION_METADATA_ACCESS))
    }
    @Test fun documentMappingFailureStillOpensSelectedItem() {
        val document=Uri.parse("content://com.android.providers.media.documents/document/image%3A123")
        for(error in listOf(SecurityException(),UnsupportedOperationException(),IllegalArgumentException())) {
            val seen=mutableListOf<Uri>()
            PhotoLocationAccess.openSelected(document,true,{throw error},{seen+=it;ByteArrayInputStream(byteArrayOf())})!!.close()
            assertEquals(listOf(document),seen)
        }
    }
    @Test fun userScopedPickerCannotBeMapped() {
        val picker=Uri.parse("content://10@media/picker/10/provider/media/123")
        val seen=mutableListOf<Uri>()
        PhotoLocationAccess.openSelected(picker,true,{error("must not map picker")},{seen+=it;ByteArrayInputStream(byteArrayOf())})!!.close()
        assertEquals(listOf(picker),seen)
    }

    @Test fun diagnosticRoutesCaptureFallbackWithoutUris() {
        val events=mutableListOf<GpsDiagnosticInfo>()
        PhotoLocationAccess.openSelected(selected,true,{null},{
            if(it!=selected)throw SecurityException("private path")
            ByteArrayInputStream(byteArrayOf())
        },{events+=it})!!.close()
        assertEquals(GpsAccessRoute.ORIGINAL,events.first().route)
        assertEquals(GpsAccessRoute.SELECTED_FALLBACK,events.last().route)
        assertEquals(GpsOriginalFallback.ORIGINAL_DENIED,events.last().fallback)
        assertFalse(events.toString().contains("private path"))
    }
    @Test fun diagnosticFailureCannotBreakSelectedOpen() {
        PhotoLocationAccess.openSelected(selected,false,{error("mapping forbidden")},{
            ByteArrayInputStream(byteArrayOf(42))
        },{error("observer unavailable")})!!.use { assertEquals(42,it.read()) }
    }
}
