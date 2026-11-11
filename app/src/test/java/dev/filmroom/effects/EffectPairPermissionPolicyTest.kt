package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectPairPermissionPolicyTest {
    @Test fun settingsGrantRetriesOnceButUnchangedAccessAndOrdinaryImagesDoNot() {
        assertTrue(EffectPairPermissionPolicy.shouldReloadAfterAccessChange(0,2,true,false))
        assertTrue(EffectPairPermissionPolicy.shouldReloadAfterAccessChange(1,2,true,false))
        assertTrue(EffectPairPermissionPolicy.shouldReloadAfterAccessChange(0,1,true,false))
        // Android 14 can replace the selected set without changing permission bits.
        assertTrue(EffectPairPermissionPolicy.shouldReloadAfterAccessChange(1,1,true,false))
        assertFalse(EffectPairPermissionPolicy.shouldReloadAfterAccessChange(2,2,true,false))
        assertFalse(EffectPairPermissionPolicy.shouldReloadAfterAccessChange(null,2,true,false))
        assertFalse(EffectPairPermissionPolicy.shouldReloadAfterAccessChange(2,0,true,false))
        assertFalse(EffectPairPermissionPolicy.shouldReloadAfterAccessChange(0,2,false,false))
        assertFalse(EffectPairPermissionPolicy.shouldReloadAfterAccessChange(0,2,true,true))
    }

    @Test fun imageOnlyOrSelectedPhotosGrantRequestsVideoWhenPairIsMissing() {
        assertTrue(EffectPairPermissionPolicy.shouldRequest(true,true,false,false,false,false))
    }
    @Test fun normalPhotoLightTableAndAlreadyLoadedVideoNeverRequest() {
        assertFalse(EffectPairPermissionPolicy.shouldRequest(true,false,false,false,false,false))
        assertFalse(EffectPairPermissionPolicy.shouldRequest(false,true,false,false,false,false))
        assertFalse(EffectPairPermissionPolicy.shouldRequest(true,true,true,false,false,false))
    }
    @Test fun fullAccessDenialAndPendingDialogDoNotRequestAgain() {
        assertFalse(EffectPairPermissionPolicy.shouldRequest(true,true,false,true,false,false))
        assertFalse(EffectPairPermissionPolicy.shouldRequest(true,true,false,false,true,false))
        assertFalse(EffectPairPermissionPolicy.shouldRequest(true,true,false,false,false,true))
    }
}
