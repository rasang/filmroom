package dev.filmroom.ui

import android.app.Application
import android.graphics.*
import dev.filmroom.border.BorderMemoryAdmission
import dev.filmroom.engine.NativeMemorySnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportContourV2HostTest {
 private fun pixels(b:Bitmap)=IntArray(b.width*b.height).also{b.getPixels(it,0,b.width,0,0,b.width,b.height)}
 private fun bitmap(w:Int,h:Int,value:Int)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(value,value,value))}
 @Test fun flatLowNoiseHardEdgesAndLegacyPortraitRemainBounded(){
  val flat=bitmap(240,160,25);assertTrue(pixels(ExportContourCache.get("flat",flat)).all{it==Color.BLACK})
  val random=Random(80);val noise=IntArray(240*160){val v=24+random.nextInt(-4,5);Color.rgb(v,v,v)};flat.setPixels(noise,0,240,0,0,240,160)
  val noisy=pixels(ExportContourCache.get("noise",flat));assertTrue(noisy.none{Color.red(it)>96});assertTrue(noisy.all{Color.red(it)==Color.green(it)&&Color.red(it)==Color.blue(it)&&Color.red(it)<=240});assertArrayEquals(noise,pixels(flat))
  val step=bitmap(160,120,0);Canvas(step).drawRect(80f,0f,160f,120f,Paint().apply{color=Color.WHITE});val contour=ExportContourCache.get("step",step)
  val bright=(0 until 160).filter{Color.red(contour.getPixel(it,60))>128};assertTrue(bright.isNotEmpty());assertTrue(bright.size<=2);assertTrue((0 until 75).all{contour.getPixel(it,60)==Color.BLACK});assertTrue((85 until 160).all{contour.getPixel(it,60)==Color.BLACK})
  listOf(160 to 240,240 to 160,1 to 1,1000 to 800).forEachIndexed{i,(w,h)->val b=bitmap(w,h,50);val out=ExportContourCache.get("shape-$i",b);assertTrue(out.width<=480&&out.height<=480);if(maxOf(w,h)<=480){assertEquals(w,out.width);assertEquals(h,out.height)};b.recycle()}
  flat.recycle();step.recycle()
 }
 @Test fun twentyJobsUseByteCapKeepBorrowedFramesAndCancelWithoutPublishing(){
  ExportContourCache.clear();val src=bitmap(480,480,40);val first=ExportContourCache.get("first:version",src);val start=ExportContourCache.computations
  repeat(20){ExportContourCache.get("job$it:version",src);assertTrue(ExportContourCache.cachedBytes<=4*1024*1024)}
  assertEquals(start+20,ExportContourCache.computations);assertFalse(first.isRecycled);val cached=ExportContourCache.find("job19:version")!!;val count=ExportContourCache.computations
  repeat(100){assertSame(cached,ExportContourCache.get("job19:version",src))};assertEquals(count,ExportContourCache.computations)
  val bytes=ExportContourCache.cachedBytes;ExportContourCache.removeJob("job19");assertTrue(ExportContourCache.cachedBytes<bytes);assertFalse(cached.isRecycled)
  var checks=0;try{ExportContourCache.get("cancel:version",src){if(++checks==4)throw java.util.concurrent.CancellationException()};fail()}catch(_:java.util.concurrent.CancellationException){}
  assertNull(ExportContourCache.find("cancel:version"));assertEquals(count,ExportContourCache.computations)
  ExportContourCache.clear();assertEquals(0,ExportContourCache.cachedBytes);assertFalse(first.isRecycled);assertFalse(cached.isRecycled);src.recycle()
 }
 @Test fun memoryPressureRejectsMissWithoutProducingOrChangingCachedFrames(){
  ExportContourCache.clear();val src=bitmap(200,120,30);val old=ExportContourCache.get("old",src);val count=ExportContourCache.computations
  try{BorderMemoryAdmission.withBudget(ExportContourCache.WORKING_BYTES,{NativeMemorySnapshot(0,0,true)},heapAvailable={Long.MAX_VALUE}){ExportContourCache.get("denied",src)};fail()}catch(_:dev.filmroom.io.PhotoException){}
  assertEquals(count,ExportContourCache.computations);assertNull(ExportContourCache.find("denied"));assertSame(old,ExportContourCache.find("old"));assertFalse(old.isRecycled);src.recycle()
 }
 @Test fun intakePersists480OnlyFromBorrowedPreviewAndLeavesScientificSnapshotUntouched()=kotlinx.coroutines.runBlocking {
  val app=org.robolectric.RuntimeEnvironment.getApplication()
  org.robolectric.shadows.ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
  val queue=dev.filmroom.export.ExportQueue(app)
  val params=dev.filmroom.model.EditorState().copy(filmExposure=1.25f)
  for((w,h) in listOf(720 to 480,200 to 160)) {
   val raw=File(app.cacheDir,"contour-input-$w.rgb").apply{writeBytes(ByteArray(48){it.toByte()})}
   val source=dev.filmroom.io.PhotoSource(raw,"fixture","image/dng",2,2,48,linearFile=raw)
   val thumbnail=bitmap(w,h,80)
   try {
    val job=queue.enqueue(source,params,dev.filmroom.io.ExportFormat.PNG,null,93,thumbnail)
    val dir=queue.store.directory(job.id);val jpeg=BitmapFactory.decodeFile(File(dir,"original-preview.jpg").path)
    assertEquals(minOf(w,480),jpeg.width);assertEquals(if(w>480)320 else h,jpeg.height)
    assertFalse(thumbnail.isRecycled);assertEquals(Color.rgb(80,80,80),thumbnail.getPixel(0,0));assertArrayEquals(ByteArray(48){it.toByte()},File(dir,"input.rgb").readBytes());assertEquals(params,job.parameters)
    jpeg.recycle();queue.cancel(job.id)
   } finally {thumbnail.recycle();raw.delete()}
  }
 }

 @Test fun cancellationAfterAllocationDoesNotPublish() {
  ExportContourCache.clear();val src=bitmap(100,100,30)
  var allChecks=0;ExportContourCache.get("probe",src){allChecks++}
  val count=ExportContourCache.computations;var checks=0
  try {ExportContourCache.get("last-cancel",src){if(++checks==allChecks)throw java.util.concurrent.CancellationException()};fail()}catch(_:java.util.concurrent.CancellationException){}
  assertEquals(allChecks,checks);assertNull(ExportContourCache.find("last-cancel"));assertEquals(count,ExportContourCache.computations);src.recycle()
 }
 @Test fun clearAndDeleteNeverWaitForComputeOrAllowOldWorkToRefill() {
  for(delete in listOf(false,true)) {
   ExportContourCache.clear();val src=bitmap(480,480,30)
   val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
   val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>();var checks=0
   val worker=Thread {try {ExportContourCache.get("slow:version",src){if(++checks==2){entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}}}catch(t:Throwable){failure.set(t)}}
   worker.start()
   try {
    assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
    val invalidated=java.util.concurrent.CountDownLatch(1)
    val invalidator=Thread {try {if(delete)ExportContourCache.removeJob("slow")else ExportContourCache.clear()}finally{invalidated.countDown()}}
    invalidator.start()
    // Ordering assertion: invalidation must complete while computation is deliberately blocked.
    assertTrue(invalidated.await(2,java.util.concurrent.TimeUnit.SECONDS));invalidator.join()
   } finally {release.countDown();worker.join(5000)}
   assertFalse(worker.isAlive);assertTrue(failure.get() is java.util.concurrent.CancellationException)
   assertNull(ExportContourCache.find("slow:version"));assertEquals(0,ExportContourCache.cachedBytes);src.recycle()
  }
 }

 @Test fun trimRejectsMissAlreadyWaitingForComputeLock() {
  ExportContourCache.clear();val src=bitmap(160,160,30)
  val holding=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
  val errors=java.util.concurrent.ConcurrentLinkedQueue<Throwable>();var calls=0
  val first=Thread {try {ExportContourCache.get("first-blocked",src){if(++calls==2){holding.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}}}catch(t:Throwable){errors.add(t)}}
  val queued=Thread {try {ExportContourCache.get("queued-before-trim",src)}catch(t:Throwable){errors.add(t)}}
  first.start()
  try {
   assertTrue(holding.await(5,java.util.concurrent.TimeUnit.SECONDS));queued.start()
   val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
   while(queued.state!=Thread.State.BLOCKED && System.nanoTime()<deadline)Thread.yield()
   assertEquals(Thread.State.BLOCKED,queued.state)
   ExportContourCache.clear()
  } finally {release.countDown();first.join(5000);queued.join(5000)}
  assertFalse(first.isAlive);assertFalse(queued.isAlive);assertEquals(2,errors.size)
  assertTrue(errors.all{it is java.util.concurrent.CancellationException});assertEquals(0,ExportContourCache.cachedBytes);src.recycle()
 }
 @Test fun deleteRejectsOnlyMatchingQueuedJobAndReleasesRegistration() {
  ExportContourCache.clear();val src=bitmap(160,160,30)
  val holding=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
  val errors=java.util.concurrent.ConcurrentLinkedQueue<Throwable>();var calls=0
  val first=Thread {try {ExportContourCache.get("first:version",src){if(++calls==2){holding.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}}}catch(t:Throwable){errors.add(t)}}
  val queued=Thread {try {ExportContourCache.get("queued:version",src)}catch(t:Throwable){errors.add(t)}}
  first.start()
  try {
   assertTrue(holding.await(5,java.util.concurrent.TimeUnit.SECONDS));queued.start()
   val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
   while(queued.state!=Thread.State.BLOCKED && System.nanoTime()<deadline)Thread.yield()
   assertEquals(Thread.State.BLOCKED,queued.state)
   ExportContourCache.removeJob("queued")
  } finally {release.countDown();first.join(5000);queued.join(5000)}
  assertFalse(first.isAlive);assertFalse(queued.isAlive);assertEquals(1,errors.size)
  assertTrue(errors.all{it is java.util.concurrent.CancellationException});assertNotNull(ExportContourCache.find("first:version"));assertNull(ExportContourCache.find("queued:version"))
  val requests=ExportContourCache::class.java.getDeclaredField("requests").apply{isAccessible=true}.get(ExportContourCache) as Set<*>
  assertTrue("Request registrations cannot grow after completed work",requests.isEmpty());src.recycle()
 }

}
