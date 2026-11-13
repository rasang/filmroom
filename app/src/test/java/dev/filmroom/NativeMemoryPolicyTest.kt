package dev.filmroom
import dev.filmroom.engine.NativeMemoryPolicy as P
import org.junit.Assert.*
import org.junit.Test
class NativeMemoryPolicyTest {
 @Test fun exportCeilingDoublesWithoutChangingPreviewOrHeadroom(){
  assertEquals(1024,P.chooseExportBudgetMiB(8192*P.MIB,256*P.MIB,false))
  assertEquals(512,P.chooseBudgetMiB(8192*P.MIB,256*P.MIB,false))
  assertEquals(512,P.chooseExportBudgetMiB(2304*P.MIB,256*P.MIB,false))
  assertEquals(1023,P.chooseExportBudgetMiB(4351*P.MIB,256*P.MIB,false))
  assertEquals(1024,P.chooseExportBudgetMiB(4352*P.MIB,256*P.MIB,false))
  assertEquals(128,P.chooseExportBudgetMiB(768*P.MIB,256*P.MIB,false))
  assertNull(P.chooseExportBudgetMiB(767*P.MIB,256*P.MIB,false))
  assertNull(P.chooseExportBudgetMiB(8192*P.MIB,256*P.MIB,true))
  assertNull(P.chooseExportBudgetMiB(8192*P.MIB,-1,false))
  assertEquals("1024",dev.filmroom.diagnostics.SafeInfo(memoryBudgetMiB=1024).fields()["memoryBudgetMiB"])
  assertFalse(dev.filmroom.diagnostics.SafeInfo(memoryBudgetMiB=1025).fields().containsKey("memoryBudgetMiB"))
 }

 @Test fun refusesLowOrInsufficientMemoryRatherThanClampingUp(){assertNull(P.chooseBudgetMiB(4096*P.MIB,256*P.MIB,true));assertNull(P.chooseBudgetMiB(767*P.MIB,256*P.MIB,false));assertNull(P.chooseBudgetMiB(128*P.MIB,256*P.MIB,false))}
 @Test fun budgetBoundaryAndCeiling(){assertEquals(128,P.chooseBudgetMiB(768*P.MIB,256*P.MIB,false));assertEquals(512,P.chooseBudgetMiB(8192*P.MIB,256*P.MIB,false));assertEquals(512,P.previewEdge(128));assertEquals(512,P.previewEdge(223));assertEquals(512,P.previewEdge(224))}
 @Test fun detailIsConditionalAndInteractiveRemainsSmall(){
  assertEquals(512,P.previewEdge(512));assertEquals(512,P.previewEdge(415,true))
  assertEquals(768,P.previewEdge(416,true));assertEquals(768,P.previewEdge(512,true))
  assertEquals(512,P.previewEdge(128,true))
  for(budget in 128..512){
   assertEquals(512,P.previewEdge(budget))
   assertTrue(P.previewEdge(budget,true) in setOf(512,768))
  }
 }
 @Test fun baselineAdmissionUsesActualSizeAndNeverInflatesBudget(){
  assertFalse(P.previewFitsBudget(512,512,128))
  assertFalse(P.previewFitsBudget(512,512,223))
  assertTrue(P.previewFitsBudget(512,512,224))
  assertFalse(P.previewFitsBudget(512,384,191))
  assertTrue(P.previewFitsBudget(512,384,192))
  assertFalse(P.previewFitsBudget(384,512,191))
  assertTrue(P.previewFitsBudget(384,512,192))
  assertTrue(P.previewFitsBudget(512,128,128))
  assertFalse(P.previewFitsBudget(512,129,128))
  assertTrue(P.previewFitsBudget(128,128,128))
  assertTrue(P.previewFitsBudget(768,768,416))
  assertFalse(P.previewFitsBudget(768,768,383))
  for(budget in 128..512){
   val expected=96*P.MIB+512L*512*512<=budget*P.MIB
   assertEquals(expected,P.previewFitsBudget(512,512,budget))
  }
 }
 @Test fun admissionRejectsInvalidDimensionsAndBudget(){
  for((width,height) in listOf(0 to 512,512 to 0,769 to 512,512 to 769)){
   assertThrows(IllegalArgumentException::class.java){P.previewFitsBudget(width,height,512)}
  }
  for(budget in listOf(127,513))assertThrows(IllegalArgumentException::class.java){P.previewFitsBudget(512,512,budget)}
 }
 @Test fun refinedGatePreservesExistingSettledBudgetAndKeepsItsOwnLimit(){
  assertEquals(416,P.REFINED_MIN_BUDGET_MIB);assertEquals(1536,P.REFINED_PREVIEW_EDGE)
  assertEquals(512,P.previewEdge(P.REFINED_MIN_BUDGET_MIB-1,true))
  assertEquals(768,P.previewEdge(P.REFINED_MIN_BUDGET_MIB,true))
 }
 @Test fun highDetailAdmissionCountsDirectBuffersAndStaysBounded(){
  assertEquals(96*P.MIB+768L*768*540,dev.filmroom.engine.NativeWorkReservations.previewEstimate(768,768))
  assertTrue(dev.filmroom.engine.NativeWorkReservations.previewEstimate(768,768)<416*P.MIB)
  assertThrows(IllegalArgumentException::class.java){dev.filmroom.engine.NativeWorkReservations.previewEstimate(769,768)}
 }
}
