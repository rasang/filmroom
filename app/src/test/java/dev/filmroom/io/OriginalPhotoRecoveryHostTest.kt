package dev.filmroom.io

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class OriginalPhotoRecoveryHostTest {
    private val doc="content://com.android.providers.media.documents/document/image%3A42"
    private val media="content://media/external/images/media/42"
    @Test fun readableOriginalWinsWithoutConvertingOrTakingNewGrants(){
        assertEquals(doc,OriginalPhotoRecovery.resolve(doc,{true},{error("must not convert readable original")}))
    }
    @Test fun officialEquivalentIsAcceptedOnlyWhenActuallyReadable(){
        assertEquals(media,OriginalPhotoRecovery.resolve(doc,{it==media},{media}))
        assertNull(OriginalPhotoRecovery.resolve(doc,{false},{media}))
    }
    @Test fun deniedOldUriCanUseReadableProviderEquivalent(){
        assertEquals(media,OriginalPhotoRecovery.resolve(doc,{if(it==doc)throw SecurityException() else true},{media}))
    }
    @Test fun noGuessedIdsOrNamesForPickerWithoutProviderMapping(){
        assertNull(OriginalPhotoRecovery.resolve("content://media/picker/0/provider/media/42",{false},{null}))
    }
    @Test fun rejectForeignProviderRedirectsAndPrivatePaths(){
        assertNull(OriginalPhotoRecovery.resolve("content://unknown/document/42",{false},{error("foreign provider")}))
        assertNull(OriginalPhotoRecovery.resolve(doc,{false},{"file:///data/private"}))
        assertNull(OriginalPhotoRecovery.resolve(doc,{false},{"content://media/external/video/media/42"}))
    }
}
