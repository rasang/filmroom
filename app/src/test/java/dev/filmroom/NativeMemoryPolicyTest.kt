package dev.filmroom
import dev.filmroom.engine.NativeMemoryPolicy as P
import org.junit.Assert.*
import org.junit.Test
class NativeMemoryPolicyTest {
 @Test fun refusesLowOrInsufficientMemoryRatherThanClampingUp(){assertNull(P.chooseBudgetMiB(4096*P.MIB,256*P.MIB,true));assertNull(P.chooseBudgetMiB(767*P.MIB,256*P.MIB,false));assertNull(P.chooseBudgetMiB(128*P.MIB,256*P.MIB,false))}
 @Test fun budgetBoundaryAndCeiling(){assertEquals(128,P.chooseBudgetMiB(768*P.MIB,256*P.MIB,false));assertEquals(512,P.chooseBudgetMiB(8192*P.MIB,256*P.MIB,false));assertEquals(256,P.previewEdge(128));assertEquals(256,P.previewEdge(223));assertEquals(512,P.previewEdge(224))}
 @Test fun detailIsConditionalAndInteractiveRemainsSmall(){
  assertEquals(512,P.previewEdge(512));assertEquals(512,P.previewEdge(415,true))
  assertEquals(768,P.previewEdge(416,true));assertEquals(768,P.previewEdge(512,true))
  assertEquals(256,P.previewEdge(128,true))
  for(budget in 128..512){val edge=P.previewEdge(budget,true);assertTrue(96*P.MIB+edge.toLong()*edge*512<=budget*P.MIB)}
 }
 @Test fun highDetailAdmissionCountsDirectBuffersAndStaysBounded(){
  assertEquals(96*P.MIB+768L*768*540,dev.filmroom.engine.NativeWorkReservations.previewEstimate(768,768))
  assertTrue(dev.filmroom.engine.NativeWorkReservations.previewEstimate(768,768)<416*P.MIB)
  assertThrows(IllegalArgumentException::class.java){dev.filmroom.engine.NativeWorkReservations.previewEstimate(769,768)}
 }
}
