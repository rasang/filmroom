package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectPreviewAudioUpgradeTest {
    @Test fun lateMixJoinsCurrentTimeline(){
        assertEquals(EffectPreviewAudioUpgrade.Action.WAIT,EffectPreviewAudioUpgrade.decide(2600,1000,false,false))
        assertEquals(EffectPreviewAudioUpgrade.Action.UPGRADE,EffectPreviewAudioUpgrade.decide(3000,1000,true,true))
        assertEquals(2000,EffectPreviewAudioTiming.offset(3000,1000))
    }
    @Test fun absentTrackDoesNotPollForever(){assertEquals(EffectPreviewAudioUpgrade.Action.DONE,EffectPreviewAudioUpgrade.decide(3000,1000,true,false))}
    @Test fun completedAnimationDoesNotRestart(){assertEquals(EffectPreviewAudioUpgrade.Action.DONE,EffectPreviewAudioUpgrade.decide(4000,1000,true,true))}
}
