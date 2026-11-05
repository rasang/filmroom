package dev.filmroom.codec

import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.engine.NativeWorkReservations
import dev.filmroom.io.PhotoException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class CameraRawMemoryBudgetTest {
 private val mib=1024L*1024
 private val high={NativeMemorySnapshot(4096*mib,128*mib,false)}
 private class Api:CodecNativeApi {
  override val available=true
  val decode=CopyOnWriteArrayList<Int>();val probe=CopyOnWriteArrayList<Int>();val encode=CopyOnWriteArrayList<Int>();val destroyed=AtomicInteger()
  override fun nativeCreate(root:String)=1L
  override fun nativeDestroy(handle:Long){destroyed.incrementAndGet()}
  override fun nativeCancel(handle:Long){}
  override fun nativeProbe(handle:Long,input:String,budget:String):String {probe.add(JSONObject(budget).getInt("memoryBudgetMiB"));return "{\"ok\":true}"}
  override fun nativeDecode(handle:Long,input:String,output:String,budget:String):String {decode.add(JSONObject(budget).getInt("memoryBudgetMiB"));return "{\"ok\":true}"}
  override fun nativeEncode(handle:Long,input:String,output:String,parameters:String):String {encode.add(JSONObject(parameters).getInt("memoryBudgetMiB"));return "{\"ok\":true}"}
 }
 private fun tiff(root:File,name:String,dng:Boolean=false):File {
  val b=ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
  b.put(byteArrayOf(73,73));b.putShort(42);b.putInt(8);b.putShort(1);b.putShort((if(dng)50706 else 256).toShort());b.putShort(1);b.putInt(4);b.putInt(1);b.putInt(0)
  return File(root,name).apply{writeBytes(b.array())}
 }
 @Test fun contentNotSuffixSelectsCameraCapAndLegacyOperationsStay128()=runBlocking {
  val root=kotlin.io.path.createTempDirectory("camera-budget").toFile();val api=Api();val calls=AtomicInteger();val codec=PhotoCodecs(root,api,memorySnapshot=high,memoryBudgetMiB={calls.incrementAndGet();512})
  try {
   for(name in listOf("a.NEF","b.CR2","c.ARW","misleading.DNG")) codec.decode(tiff(root,name),File(root,"out"))
   codec.decode(tiff(root,"actually.NEF",true),File(root,"out"));codec.encode(File(root,"a.NEF"),File(root,"out"),8,8,"JPEG",95)
   codec.probe(File(root,"a.NEF"));codec.probe(File(root,"actually.NEF"))
   assertEquals(listOf(256,256,256,256,128),api.decode.toList());assertEquals(listOf(128),api.encode.toList());assertEquals(listOf(256,128),api.probe.toList());assertEquals(6,calls.get())
  }finally{root.deleteRecursively()}
 }
 @Test fun cr3OrfAndOriSignaturesUse256DecodeAnd32MetadataLease()=runBlocking {
  val root=kotlin.io.path.createTempDirectory("additional-camera-budget").toFile();val api=Api()
  val codec=PhotoCodecs(root,api,memorySnapshot=high,memoryBudgetMiB={512})
  val metadataCodec=PhotoCodecs(root,api,memorySnapshot={NativeMemorySnapshot(320*mib,128*mib,false)},memoryBudgetMiB={error("metadata must not ask decode preference")})
  try {
   val signatures=listOf("IIRO".toByteArray(),"IIRS".toByteArray(),"MMOR".toByteArray(),byteArrayOf(0,0,0,32)+"ftypcrx ".toByteArray())
   for((i,signature) in signatures.withIndex()) {
    val input=File(root,"$i.jpeg").apply{writeBytes(signature+ByteArray(16))}
    codec.decode(input,File(root,"out"));metadataCodec.probe(input)
   }
   assertEquals(List(4){256},api.decode.toList());assertEquals(List(4){256},api.probe.toList())
  }finally{root.deleteRecursively()}
 }
 @Test fun frozenRequestAndSharedLedgerChargeTheSame256()=runBlocking {
  val root=kotlin.io.path.createTempDirectory("frozen-budget").toFile();val api=Api();val calls=AtomicInteger();val memory={NativeMemorySnapshot(1024*mib,128*mib,false)}
  val codec=PhotoCodecs(root,api,memorySnapshot=memory,memoryBudgetMiB={if(calls.incrementAndGet()==1)256 else 128})
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
  val hold=launch{NativeWorkReservations.run(160*mib,memory){entered.complete(Unit);release.await()}};entered.await()
  try {
   val decode=async{codec.decode(tiff(root,"camera.bin"),File(root,"out"))}
   delay(200);assertEquals(1,calls.get());assertTrue("256+16 cannot fit beside160+16 in416MiB;128 would incorrectly enter",api.decode.isEmpty())
   release.complete(Unit);hold.join();withTimeout(5000){decode.await()};assertEquals(listOf(256),api.decode.toList());assertEquals(1,calls.get());assertTrue(NativeWorkReservations.detailRetryIdle())
  }finally{release.complete(Unit);hold.join();root.deleteRecursively()}
 }
 @Test fun dynamicLowerBudgetAndLowMemoryRemainFailClosed()=runBlocking {
  val root=kotlin.io.path.createTempDirectory("dynamic-budget").toFile();val raw=tiff(root,"camera.bin")
  try {
   val api=Api();PhotoCodecs(root,api,memorySnapshot=high,memoryBudgetMiB={192}).decode(raw,File(root,"out"));assertEquals(listOf(192),api.decode.toList())
   for(low in listOf(false,true)) {
    val blocked=Api();val codec=PhotoCodecs(root,blocked,memorySnapshot={NativeMemorySnapshot(4096*mib,128*mib,low)},memoryBudgetMiB={if(low)256 else 127})
    try{codec.decode(raw,File(root,"out"));fail()}catch(_:PhotoException){}
    assertTrue(blocked.decode.isEmpty());if(low)assertEquals(1,blocked.destroyed.get())
   }
  }finally{root.deleteRecursively()}
 }
 @Test fun cameraMetadataKeeps32MiBLeaseDespite256DecodeCap()=runBlocking {
  val root=kotlin.io.path.createTempDirectory("metadata-budget").toFile();val api=Api();val codec=PhotoCodecs(root,api,memorySnapshot={NativeMemorySnapshot(320*mib,128*mib,false)},memoryBudgetMiB={error("metadata must not ask decode preference")})
  try{codec.probe(tiff(root,"camera.bin"));assertEquals(listOf(256),api.probe.toList());assertTrue(NativeWorkReservations.detailRetryIdle())}finally{root.deleteRecursively()}
 }
}
