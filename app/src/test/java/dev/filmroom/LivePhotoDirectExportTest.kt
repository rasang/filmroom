package dev.filmroom.effects

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.livephoto.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class LivePhotoDirectExportTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset(){context.getSharedPreferences("live_photo_export_notice",Context.MODE_PRIVATE).edit().clear().commit()}
    @Test fun unavailableLiveButtonIsDimmedButStillClickableAndCanRecover(){
        var clicks=0
        val button=EffectControls.button(context,"Live","live",true){clicks++}
        EffectControls.setLiveAvailability(button,true,true)
        assertEquals(.45f,button.alpha)
        assertTrue(button.isEnabled)
        assertTrue(button.performClick())
        assertEquals(1,clicks)
        EffectControls.setLiveAvailability(button,true,false)
        assertEquals(1f,button.alpha)
        assertTrue(button.isEnabled)
    }
    @Test fun noticeShownOnlyOnceAcrossInstances(){
        assertTrue(LivePhotoExportNotice(context).consumeFirstNotice())
        assertFalse(LivePhotoExportNotice(context).consumeFirstNotice())
    }
    private fun selection(profile:LivePhotoProfile?, status:SupportStatus, candidates:List<ProfileCapability>)=
        ProfileSelection(TargetBrand.VIVO,null,profile,status,SelectionReason.CANDIDATE,candidates,emptyList())
    @Test fun unknownRetainsCandidateAndUnknown(){
        val original=selection(LivePhotoProfile.VIVO_SINGLE,SupportStatus.UNKNOWN,emptyList())
        assertEquals(original,EffectLivePhotoExporter.forDirectAttempt(original))
        assertFalse(EffectLivePhotoExporter.Preflight(original).unsupported)
    }
    @Test fun allNegativeStillOffersWritableCandidateWithoutClaimingSupport(){
        val original=selection(null,SupportStatus.UNSUPPORTED,listOf(ProfileCapability(LivePhotoProfile.VIVO_SINGLE,SupportStatus.UNSUPPORTED,null),ProfileCapability(LivePhotoProfile.GOOGLE,SupportStatus.UNSUPPORTED,null)))
        val retry=EffectLivePhotoExporter.forDirectAttempt(original)
        assertEquals(LivePhotoProfile.VIVO_SINGLE,retry.selectedProfile)
        assertEquals(SupportStatus.UNSUPPORTED,retry.status)
        assertTrue(EffectLivePhotoExporter.Preflight(retry).unsupported)
    }
    @Test fun unsupportedLegacyCoverNeverSelected(){
        val original=selection(null,SupportStatus.UNSUPPORTED,listOf(ProfileCapability(LivePhotoProfile.VIVO_PAIR_LEGACY,SupportStatus.UNSUPPORTED,null),ProfileCapability(LivePhotoProfile.GOOGLE,SupportStatus.UNSUPPORTED,null)))
        assertEquals(LivePhotoProfile.GOOGLE,EffectLivePhotoExporter.forDirectAttempt(original).selectedProfile)
    }
    @Test fun genuinelyEmptyCandidatesRemainUnavailable(){
        assertNull(EffectLivePhotoExporter.forDirectAttempt(selection(null,SupportStatus.UNSUPPORTED,emptyList())).selectedProfile)
    }
    @Test fun declaredUnsupportedCannotBeReenabledByDirectAttempt(){
        val original=selection(null,SupportStatus.UNSUPPORTED,listOf(ProfileCapability(LivePhotoProfile.VIVO_SINGLE,SupportStatus.UNKNOWN,null)))
            .copy(vivoCapability=VivoCapability.read { "2" })
        val retry=EffectLivePhotoExporter.forDirectAttempt(original)
        assertNull(retry.selectedProfile)
        assertTrue(EffectLivePhotoExporter.Preflight(retry).deviceUnsupported)
    }
    @Test fun foreignVivoCapabilityCannotBlockAnotherBrand(){
        val original=selection(null,SupportStatus.UNSUPPORTED,listOf(ProfileCapability(LivePhotoProfile.GOOGLE,SupportStatus.UNKNOWN,null)))
            .copy(brand=TargetBrand.SAMSUNG,vivoCapability=VivoCapability.read { "2" })
        val retry=EffectLivePhotoExporter.forDirectAttempt(original)
        assertEquals(LivePhotoProfile.GOOGLE,retry.selectedProfile)
        assertFalse(EffectLivePhotoExporter.Preflight(retry).deviceUnsupported)
    }
    @Test fun confirmedCandidateRetained(){
        val original=selection(LivePhotoProfile.GOOGLE,SupportStatus.SUPPORTED,emptyList())
        assertEquals(original,EffectLivePhotoExporter.forDirectAttempt(original))
    }
}
