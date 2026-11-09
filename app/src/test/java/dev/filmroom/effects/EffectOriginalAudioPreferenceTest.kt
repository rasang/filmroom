package dev.filmroom.effects

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class EffectOriginalAudioPreferenceTest {
    private val context:Context get()=ApplicationProvider.getApplicationContext()
    @Before fun reset(){context.getSharedPreferences("effect_playback",Context.MODE_PRIVATE).edit().clear().commit()}
    @Test fun defaultPreservesOriginalSound(){assertTrue(EffectOriginalAudioPreference.read(context))}
    @Test fun disabledChoiceSurvivesAnotherPageOrActivityContext(){
        EffectOriginalAudioPreference.write(context,false)
        assertFalse(EffectOriginalAudioPreference.read(context.createConfigurationContext(context.resources.configuration)))
        EffectOriginalAudioPreference.write(context,true)
        assertTrue(EffectOriginalAudioPreference.read(context))
    }
    @Test fun muteOnlyRemovesSourceReferenceNotItsVideo(){
        val motion=File(context.cacheDir,"sound-selection.mp4").apply{writeBytes(byteArrayOf(1,2,3))}
        try {
            assertNull(EffectOriginalAudioPreference.source(EffectKind.OPTICAL_LEAK,false,motion))
            assertTrue(motion.isFile)
            assertArrayEquals(byteArrayOf(1,2,3),motion.readBytes())
            assertSame(motion,EffectOriginalAudioPreference.source(EffectKind.OPTICAL_LEAK,true,motion))
        }finally{motion.delete()}
    }
    @Test fun nextPhotoUsesItsOwnAudioReferenceAndSamePreference(){
        EffectOriginalAudioPreference.write(context,false)
        val next=File("next-photo.mp4")
        assertNull(EffectOriginalAudioPreference.source(EffectKind.OPTICAL_LEAK,EffectOriginalAudioPreference.read(context),next))
        EffectOriginalAudioPreference.write(context,true)
        assertSame(next,EffectOriginalAudioPreference.source(EffectKind.OPTICAL_LEAK,EffectOriginalAudioPreference.read(context),next))
    }
    @Test fun lightTableAndOrdinaryPhotoDoNotAcquireSourceSound(){
        assertNull(EffectOriginalAudioPreference.source(EffectKind.LIGHT_TABLE,true,File("motion.mp4")))
        assertNull(EffectOriginalAudioPreference.source(EffectKind.OPTICAL_LEAK,true,null))
    }
    @Test fun mutedPreviewIsImmediatelyPreparedForExistingEffectTrack(){
        val audio=EffectPreviewAudio(context)
        val source=EffectOriginalAudioPreference.source(EffectKind.OPTICAL_LEAK,false,File("motion.mp4"))
        audio.prepare(EffectKind.OPTICAL_LEAK,false,source)
        assertTrue(audio.isPrepared(EffectKind.OPTICAL_LEAK,false,source))
        // The original effect track remains the fallback in both preview and export.
        assertTrue(EffectAudioTrack.assetPath(EffectKind.OPTICAL_LEAK,false).isNotBlank())
        audio.stop()
    }
}
