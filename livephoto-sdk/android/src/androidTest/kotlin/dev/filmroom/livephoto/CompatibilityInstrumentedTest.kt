package dev.filmroom.livephoto

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.filmroom.livephoto.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Compile in CI; execute only on a connected Android runtime. Does not certify OEM galleries. */
@RunWith(AndroidJUnit4::class)
class CompatibilityInstrumentedTest {
    private val input=Uri.parse("content://dev.filmroom.livephoto/selection-only")
    @Test fun remoteTargetDoesNotUseLocalEvidenceOrProbe() = runBlocking {
        var called=false
        val sdk=LivePhotoSdk(InstrumentationRegistry.getInstrumentation().targetContext,
            mapOf(TargetBrand.VIVO to DeviceSupportProbe { called=true;true }))
        val s=sdk.resolveProfile(VideoToLiveRequest(input,targetBrand=TargetBrand.VIVO))
        assertNull(s.environment);assertNull(s.deviceHint);assertFalse(called)
        assertEquals(SupportStatus.UNKNOWN,s.status)
        assertEquals(LivePhotoProfile.VIVO_SINGLE,s.selectedProfile)
    }
    @Test fun manualOverrideIsRetained() = runBlocking {
        val sdk=LivePhotoSdk(InstrumentationRegistry.getInstrumentation().targetContext)
        val s=sdk.resolveProfile(VideoToLiveRequest(input,LivePhotoProfile.SAMSUNG_SEF_106,targetBrand=TargetBrand.SAMSUNG))
        assertEquals(LivePhotoProfile.SAMSUNG_SEF_106,s.selectedProfile)
        assertEquals(SelectionReason.MANUAL,s.reason)
    }
    @Test fun nonzeroCoverExcludesLegacyVivo() = runBlocking {
        val sdk=LivePhotoSdk(InstrumentationRegistry.getInstrumentation().targetContext)
        val s=sdk.resolveProfile(VideoToLiveRequest(input,coverOffsetMs=500,targetBrand=TargetBrand.VIVO))
        assertFalse(s.candidates.any { it.profile==LivePhotoProfile.VIVO_PAIR_LEGACY })
    }
    @Test fun strictModeStopsUnknownBeforeReadingVideo() = runBlocking {
        val sdk=LivePhotoSdk(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            sdk.create(VideoToLiveRequest(input,targetBrand=TargetBrand.OTHER,requireConfirmedSupport=true))
            fail("Unknown should not pass strict mode")
        } catch(e:LivePhotoException) { assertEquals(ErrorCode.UNSUPPORTED_PROFILE,e.code) }
    }
}
