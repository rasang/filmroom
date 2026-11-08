package dev.filmroom.io

import android.content.Intent
import android.net.Uri
import dev.filmroom.diagnostics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
class OriginalPhotoAccessHostTest {
    @Test fun alreadyDurableReadDoesNotRequestAgain() {
        assertEquals(OriginalPhotoGrant.ALREADY_PERSISTED,OriginalPhotoAccess.retainSelected({true},{error("must not request")}))
    }
    @Test fun successRequiresVerifiedReadGrantAfterRequest() {
        var granted=false
        assertEquals(OriginalPhotoGrant.PERSISTED,OriginalPhotoAccess.retainSelected({granted},{granted=true}))
        assertEquals(OriginalPhotoGrant.TEMPORARY_ONLY,OriginalPhotoAccess.retainSelected({false},{}))
    }
    @Test fun unsupportedAndDeniedRemainTemporaryWithoutThrowing() {
        assertEquals(OriginalPhotoGrant.TEMPORARY_ONLY,OriginalPhotoAccess.retainSelected({false},{throw SecurityException("private detail")}))
        assertEquals(OriginalPhotoGrant.TEMPORARY_ONLY,OriginalPhotoAccess.retainSelected({false},{throw UnsupportedOperationException()}))
        assertEquals(OriginalPhotoGrant.REQUEST_FAILED,OriginalPhotoAccess.retainSelected({false},{throw IllegalStateException()}))
    }
    @Test fun failedVerificationDoesNotClaimDurability() {
        var reads=0
        assertEquals(OriginalPhotoGrant.VERIFY_FAILED,OriginalPhotoAccess.retainSelected({if(reads++==0)false else throw SecurityException()},{}))
    }
    @Test fun unknownOriginAndPrivateCacheAreNotGranted() {
        val context=RuntimeEnvironment.getApplication()
        assertEquals(OriginalPhotoGrant.NOT_REQUESTED,OriginalPhotoAccess.retain(context,Uri.parse("content://photos/source"),ImportOrigin.UNKNOWN))
        assertEquals(OriginalPhotoGrant.NOT_APPLICABLE,OriginalPhotoAccess.retainRead(context,Uri.parse("file:///private/cache/photo")))
        assertEquals(OriginalPhotoGrant.NOT_APPLICABLE,OriginalPhotoAccess.retainRead(context,Uri.parse("content://${context.packageName}.presetqr/private")))
    }
    @Test fun persistedListMustMatchExactUriAndReadBit() {
        val context=RuntimeEnvironment.getApplication();val resolver=context.contentResolver
        val target=Uri.parse("content://photos/target")
        resolver.takePersistableUriPermission(Uri.parse("content://photos/other"),Intent.FLAG_GRANT_READ_URI_PERMISSION)
        resolver.takePersistableUriPermission(target,Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(OriginalPhotoGrant.PERSISTED,OriginalPhotoAccess.retainRead(context,target))
        assertEquals(OriginalPhotoGrant.ALREADY_PERSISTED,OriginalPhotoAccess.retainRead(context,target))
        assertTrue(resolver.persistedUriPermissions.any{it.uri==target&&it.isReadPermission})
        // Existing records' grants are never evicted to make room for the new one.
        assertTrue(resolver.persistedUriPermissions.any{it.uri!=target&&it.isReadPermission})
    }
    @Test fun oldDiagnosticsRemainReadableWithoutGrantField() {
        val old=ImportDiagnosticInfo.fromFields(mapOf("importOrigin" to "PHOTO_PICKER", "importPhase" to "COMPLETE"))!!
        assertEquals(ImportOrigin.PHOTO_PICKER,old.origin)
        assertNull(old.originalPhotoGrant)
        assertFalse(old.fields().containsKey("importOriginalPhotoGrant"))
    }
    @Test fun diagnosticContainsOnlyFixedGrantCategoryAndRoundTrips() {
        for(value in OriginalPhotoGrant.entries) {
            val info=ImportDiagnosticInfo(originalPhotoGrant=value)
            assertEquals(mapOf("importOriginalPhotoGrant" to value.name),info.fields())
            assertEquals(info,ImportDiagnosticInfo.fromFields(info.fields().mapValues{it.value.toString()}))
            val safe=SafeInfo(importDiagnostic=info)
            assertEquals(safe,SafeInfo.parse(safe.fields()))
        }
        assertNull(ImportDiagnosticInfo.fromFields(mapOf("importOriginalPhotoGrant" to "content://private/photo")))
    }
}
