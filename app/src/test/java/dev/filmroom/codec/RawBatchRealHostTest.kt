package dev.filmroom.codec

import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.model.RawBatchMode
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** Real Linux JNI, production Kotlin request construction and leases; not a phone test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class RawBatchRealHostTest {
 private fun hash(f:File):String {val h=MessageDigest.getInstance("SHA-256");f.inputStream().use{s->val b=ByteArray(65536);while(true){val n=s.read(b);if(n<0)break;h.update(b,0,n)}};return h.digest().joinToString(""){"%02x".format(it)}}
 @Test fun allPreferencesCrossRealJniWithIdenticalOutputAndCancellationCleanup()=runBlocking {
  assumeTrue(System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
  val names=listOf("FILMROOM_BATCH_DNG","FILMROOM_BATCH_CAMERA")
  assumeTrue(names.all{System.getenv(it)!=null})
  assertTrue(NativeCodecBridge.available)
  val root=kotlin.io.path.createTempDirectory("real-raw-batch").toFile().canonicalFile
  try {
   for(name in names){
    val original=File(System.getenv(name)!!);val originalHash=hash(original)
    val input=original.copyTo(File(root,if(name.endsWith("DNG"))"input.dng" else "input.nef"));var mode=RawBatchMode.STANDARD
    val codec=PhotoCodecs(root,NativeCodecBridge,memorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)},rawBatchPreference={mode})
    val handle=NativeCodecBridge.nativeCreate(root.path)
    try {
     for(invalid in listOf("null","123","true","{}","[]")){
      val badOutput=File(root,"invalid.linear")
      val response=org.json.JSONObject(NativeCodecBridge.nativeDecode(handle,input.path,badOutput.path,"{\"memoryBudgetMiB\":128,\"diskBudgetBytes\":1073741824,\"rawBatch\":$invalid}"))
      assertFalse(response.toString(),response.getBoolean("ok"));assertFalse(badOutput.exists())
     }
    } finally {NativeCodecBridge.nativeDestroy(handle)}
    var reference:String?=null;var dimensions:Pair<Int,Int>?=null
    for(m in listOf(RawBatchMode.STANDARD,RawBatchMode.AUTO,RawBatchMode.SMALL,RawBatchMode.LARGE)){
     mode=m;val output=File(root,"result.linear")
     val result=withTimeout(180000){codec.decode(input,output)};assertTrue(result.toString(),result.getBoolean("ok"))
     val gotHash=hash(output);val gotDimensions=result.getInt("width") to result.getInt("height")
     if(reference==null){reference=gotHash;dimensions=gotDimensions}else{assertEquals(reference,gotHash);assertEquals(dimensions,gotDimensions)}
     System.getenv(name+"_SHA256")?.let{assertEquals(it,gotHash)}
     assertEquals(gotDimensions.first.toLong()*gotDimensions.second*12,output.length());assertTrue(output.delete())
    }
    mode=RawBatchMode.LARGE;val cancelled=File(root,"cancelled.linear")
    val job=launch(Dispatchers.IO){codec.decode(input,cancelled)};delay(25);job.cancelAndJoin()
    assertFalse(cancelled.exists());assertTrue(NativeWorkReservations.detailRetryIdle())
    val retry=File(root,"retry.linear");assertTrue(codec.decode(input,retry).getBoolean("ok"));assertEquals(reference,hash(retry));assertTrue(retry.delete())
    assertEquals(originalHash,hash(input));assertEquals(originalHash,hash(original));assertTrue(input.delete());assertFalse(root.listFiles()!!.any{it.isDirectory})
   }
  }finally{root.deleteRecursively()}
 }
}
