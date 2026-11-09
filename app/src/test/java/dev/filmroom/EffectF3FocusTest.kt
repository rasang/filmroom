package dev.filmroom
import dev.filmroom.effects.*
import org.junit.Assert.*
import org.junit.Test
class EffectF3FocusTest {
 @Test fun beta27FocusDurations() { assertEquals(1150L,EffectTimeline.focusDurationMs(false));assertEquals(545L,EffectTimeline.focusDurationMs(true)) }
 @Test fun focusIsMonotonicAndSettlesBeforeShutter() {
  for(slow in listOf(false,true)) {
   assertEquals(0f,EffectTimeline.focusProgress(0,slow),0f)
   var previous=0f
   for(t in 0L..3000L step 10) { val f=EffectTimeline.focusProgress(t,slow);assertTrue(f>=previous);assertTrue(f in 0f..1f);previous=f }
   assertEquals(1f,EffectTimeline.focusProgress(EffectTimeline.opticalHoldMs(slow),slow),0f)
   assertEquals(3000L,EffectTimeline.durationMs(EffectKind.OPTICAL_LEAK,slow))
  }
 }
 @Test fun replayAndNegativeTimeAreDeterministic() {
  assertEquals(0f,EffectTimeline.focusProgress(-100),0f)
  assertEquals(1f,EffectTimeline.focusProgress(30000),0f)
  assertEquals(EffectTimeline.focusProgress(410),EffectTimeline.focusProgress(410),0f)
 }
}
