package dev.filmroom
import dev.filmroom.engine.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class ScanPreviewAdmissionHostTest {
 @Test fun threeAdditionalNativeMiBAreChargedAtAdmissionBoundary()=runBlocking {
  val mib=1024L*1024;val oldEstimate=112*mib+256*32768L+8*mib;val estimate=oldEstimate+3*mib
  val limiter=NativeWorkLimiter();var called=false
  // run adds the existing16MiB per-operation safety allowance; capacity is half remaining headroom.
  val tooSmall=NativeMemorySnapshot(64*mib+2*(estimate+16*mib)-1,0,false)
  try{limiter.run(estimate,{tooSmall}){called=true};fail()}catch(_:NativeWorkUnavailable){}
  assertFalse(called);assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
  val exact=tooSmall.copy(availableBytes=tooSmall.availableBytes+1)
  limiter.run(estimate,{exact}){called=true};assertTrue(called);assertEquals(0L,limiter.reservedBytes())
 }
 @Test fun onlyCurrentOwnedOutputUuidCanChooseSourcePath(){
  val dir=java.io.File("/tmp/scan-owned")
  val first=java.io.File(dir,"film-result-12345678-1234-1234-1234-123456789abc.rgb")
  val second=java.io.File(dir,"film-result-22345678-1234-1234-1234-123456789abc.rgb")
  assertEquals("scan-source-12345678-1234-1234-1234-123456789abc.png",scanPreviewForOutput(first,dir)!!.name)
  assertNotEquals(scanPreviewForOutput(first,dir),scanPreviewForOutput(second,dir))
  assertNull(scanPreviewForOutput(first,java.io.File("/tmp/other-job")));assertNull(scanPreviewForOutput(first,null))
  assertNull(scanPreviewForOutput(java.io.File(dir,"arbitrary-native-path.png"),dir))
 }
}
