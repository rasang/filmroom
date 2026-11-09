package dev.filmroom.effects

import dev.filmroom.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class EffectMotionPreviewLogRestoreTest {
 @Test fun measuresUniqueFramesAndEndsOnce() {
  var now=100L;val recorded=mutableListOf<MotionPreviewDiagnosticInfo>()
  val log=EffectMotionPreviewLog({now}){info,_->recorded.add(info)}
  now=150;log.started();now=160;log.frame(0,10,1000000);now=193;log.frame(33000,43,3000000)
  val s=log.snapshot();assertEquals(2L,s.submittedFrames);assertEquals(50L,s.prepareMs);assertEquals(33L,s.sampleSpanMs)
  assertEquals(10000L,s.ptsLagMeanUs);assertEquals(2000L,s.glSubmitCpuMeanUs)
  log.end();log.end();log.frame(66000,76,1);assertEquals(1,recorded.size);assertEquals(2L,log.snapshot().submittedFrames)
 }
 @Test fun rejectsInvalidInputsAndKeepsFallbackCause() {
  val log=EffectMotionPreviewLog({0}){_,_->}
  log.path(MotionPreviewPath.BETA25_FALLBACK,MotionPreviewFallback.CODED_SIZE_BUDGET,5000,3000,true)
  log.path(MotionPreviewPath.STATIC,width=-1,height=40000)
  log.frame(-1,0,0);log.frame(null,60001,0);log.frame(null,0,-1)
  val s=log.snapshot();assertEquals(0L,s.submittedFrames);assertEquals(5000,s.sourceWidth);assertEquals(3000,s.sourceHeight)
  assertEquals(MotionPreviewFallback.CODED_SIZE_BUDGET,s.fallback);assertEquals(MotionPreviewHardware.UNKNOWN,s.hardware)
 }
 @Test fun safeInfoRoundTripsSnapshot() {
  val d=MotionPreviewDecoderSnapshot(codedWidth=3840,codedHeight=2160,hardware=true,decoderCandidates=3,selectionReason=MotionPreviewDecoderSelection.HARDWARE_SUPPORTED,decoded=60,workerCount=2,workerTotalUs=100,sourceBitDepth=10,selectedRateSupported=false)
  val info=MotionPreviewDiagnosticInfo(MotionPreviewPath.SURFACE,decoder=d,submittedFrames=59)
  assertEquals(info,MotionPreviewDiagnosticInfo.parse(info.fields().mapValues{it.value.toString()}))
  assertEquals(50L,d.fields()["motionDecoderWorkerMeanUs"])
 }
 @Test fun parserDropsInvalidUntrustedDiagnostics() {
  assertNull(MotionPreviewDiagnosticInfo.parse(mapOf("motionPreviewPath" to "INVALID")))
  val s=MotionPreviewDiagnosticInfo.parse(mapOf("motionPreviewPath" to "STATIC","motionPreviewSourceWidth" to "-1","motionPreviewSubmittedFrames" to "10001"))!!
  assertNull(s.sourceWidth);assertEquals(0L,s.submittedFrames)
  val d=MotionPreviewDecoderSnapshot(sourceProfile=-1,sourceBitDepth=65)
  assertFalse(d.fields().containsKey("motionDecoderSourceProfile"));assertFalse(d.fields().containsKey("motionDecoderSourceBitDepth"))
 }
}
