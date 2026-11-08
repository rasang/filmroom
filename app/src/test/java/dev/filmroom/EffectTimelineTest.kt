package dev.filmroom
import dev.filmroom.effects.*
import org.junit.Assert.*
import org.junit.Test
class EffectTimelineTest {
 @Test fun lampPreservesDarkStrikeHoldAndRecovery(){
  assertEquals(.24f,EffectTimeline.at(EffectKind.LIGHT_TABLE,999).gain,0f)
  assertEquals(.24f,EffectTimeline.at(EffectKind.LIGHT_TABLE,1000).gain,0f)
  assertEquals(4f,EffectTimeline.at(EffectKind.LIGHT_TABLE,1021).gain,0f)
  assertEquals(4f,EffectTimeline.at(EffectKind.LIGHT_TABLE,1198).gain,0f)
  assertEquals(1f,EffectTimeline.at(EffectKind.LIGHT_TABLE,2398).gain,.00001f)
  assertEquals(0f,EffectTimeline.at(EffectKind.LIGHT_TABLE,3000).bloom,.00001f)
 }
 @Test fun opticalOpeningIsUncoveredAndLongerInBothModes(){
  assertEquals(1200L,EffectTimeline.opticalHoldMs(false))
  assertEquals(500L,EffectTimeline.opticalHoldMs(true))
  for(slow in listOf(false,true)) {
   val first=EffectTimeline.at(EffectKind.OPTICAL_LEAK,0,slow)
   val hold=EffectTimeline.opticalHoldMs(slow)
   for(t in 0L..hold) assertEquals(first,EffectTimeline.at(EffectKind.OPTICAL_LEAK,t,slow))
   assertEquals(0f,first.cover,0f);assertFalse(first.showResult)
   assertTrue(EffectTimeline.at(EffectKind.OPTICAL_LEAK,hold+1,slow).cover>0f)
  }
 }
 @Test fun opticalKeepsFullLeakAndSwitchesOnlyAtPeak(){
  for(slow in listOf(false,true)) {
   val start=EffectTimeline.opticalHoldMs(slow)+EffectTimeline.opticalTransitionMs(slow)
   assertEquals(0f,EffectTimeline.at(EffectKind.OPTICAL_LEAK,start,slow).leak,0f)
   assertFalse(EffectTimeline.at(EffectKind.OPTICAL_LEAK,start+149,slow).showResult)
   val peak=EffectTimeline.at(EffectKind.OPTICAL_LEAK,start+150,slow)
   assertTrue(peak.showResult);assertEquals(1f,peak.leak,.00001f)
   assertTrue(EffectTimeline.at(EffectKind.OPTICAL_LEAK,start+609,slow).leak>0f)
   val end=EffectTimeline.at(EffectKind.OPTICAL_LEAK,start+610,slow)
   assertEquals(0f,end.leak,0f);assertTrue(end.showResult)
  }
 }
 @Test fun slowStillSlowsCoverAndLeavesCleanTail(){
  assertEquals(225L,EffectTimeline.opticalTransitionMs(false))
  assertEquals(1650L,EffectTimeline.opticalTransitionMs(true))
  // Compare corresponding points of all cover/settling stages, not only endpoints.
  for(t in listOf(0L,45L,90L,135L,180L,225L)) {
   val normal=EffectTimeline.at(EffectKind.OPTICAL_LEAK,1200+t)
   val slow=EffectTimeline.at(EffectKind.OPTICAL_LEAK,500+t*22/3,true)
   assertEquals(normal.cover,slow.cover,.00001f)
   assertEquals(normal.settling,slow.settling,.00001f)
  }
  assertEquals(EffectTimeline.at(EffectKind.OPTICAL_LEAK,3000,true),EffectTimeline.at(EffectKind.OPTICAL_LEAK,2760,true))
 }
 @Test fun allNinetyFramesAndLiveCoverUseSameCompletedTail(){
  for(k in EffectKind.values())for(slow in listOf(false,true)) {
   val end=EffectTimeline.at(k,3000,slow)
   val frames=(0 until 90).map { EffectTimeline.at(k,(it*1_000_000_000L/30)/1_000_000,slow) }
   assertEquals(end,frames.last())
   assertEquals(end,EffectTimeline.at(k,2900,slow)) // Live Photo cover offset
   assertTrue(frames.distinct().size>10)
  }
 }
 @Test fun everyVariantCompletesAllStagesWithinThreeSeconds(){
  for(k in EffectKind.values())for(slow in listOf(false,true)) {
   assertEquals(3000L,EffectTimeline.durationMs(k,slow))
   val end=EffectTimeline.at(k,3000,slow)
   if(k==EffectKind.LIGHT_TABLE){assertEquals(1f,end.gain,0f);assertEquals(0f,end.bloom,0f)}
   else {assertTrue(end.showResult);assertEquals(0f,end.leak,0f);assertEquals(0f,end.settling,0f)}
   // The last encoded frame at 30fps is already the clean held result.
   assertEquals(end,EffectTimeline.at(k,2966,slow))
  }
 }
 @Test fun timelineClampsAndRemainsFinite(){
  for(k in EffectKind.values())for(slow in listOf(false,true)){
   assertEquals(EffectTimeline.at(k,0,slow),EffectTimeline.at(k,-50,slow))
   assertEquals(EffectTimeline.at(k,EffectTimeline.durationMs(k,slow),slow),EffectTimeline.at(k,Long.MAX_VALUE,slow))
   for(t in 0L..EffectTimeline.durationMs(k,slow)){
    val s=EffectTimeline.at(k,t,slow)
    assertTrue(listOf(s.gain,s.lamp,s.bloom,s.cool,s.spill,s.cover,s.settling,s.leak,s.leakDrift).all{it.isFinite()&&it>=0})
    assertTrue(s.leak<=1f&&s.cover<=1f)
   }
  }
 }
}
