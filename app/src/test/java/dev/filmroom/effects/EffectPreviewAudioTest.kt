package dev.filmroom.effects
import org.junit.Assert.*
import org.junit.Test
class EffectPreviewAudioTest {
 @Test fun previewUsesExportTrack(){assertEquals("effect_audio/optical_leak.m4a",EffectAudioTrack.assetPath(EffectKind.OPTICAL_LEAK,false))}
 @Test fun slowUsesItsOwnTiming(){assertEquals("effect_audio/optical_leak_slow.m4a",EffectAudioTrack.assetPath(EffectKind.OPTICAL_LEAK,true))}
 @Test fun tableTimingDoesNotChange(){assertEquals(EffectAudioTrack.assetPath(EffectKind.LIGHT_TABLE,false),EffectAudioTrack.assetPath(EffectKind.LIGHT_TABLE,true))}
 @Test fun latePreparationSeeksToVisualClock(){assertEquals(380,EffectPreviewAudioTiming.offset(1380,1000))}
 @Test fun expiredPreparationDoesNotReplaySound(){assertNull(EffectPreviewAudioTiming.offset(4000,1000));assertNull(EffectPreviewAudioTiming.offset(9000,1000))}
 @Test fun nonnegativeClock(){assertEquals(0,EffectPreviewAudioTiming.offset(999,1000))}
 @Test fun swipedPhotoStaysSilent(){val p=EffectPreviewPlayback(false);assertTrue(p.stopped);assertEquals(3000,p.elapsed(10,0,3000));p.replay();assertFalse(p.stopped)}
 @Test fun pauseStopsPlaybackUntilExplicitReplay(){val p=EffectPreviewPlayback(true);p.stop();assertEquals(3000,p.elapsed(10,0,3000));p.replay();assertEquals(10,p.elapsed(10,0,3000))}
}
