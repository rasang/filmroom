package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.codec.NativeCodecBridge
import dev.filmroom.codec.PhotoCodecs
import dev.filmroom.engine.*
import dev.filmroom.io.*
import dev.filmroom.model.CompositionTransform
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.*
import kotlin.concurrent.thread

/** Whole host JVM + native graphics/JNI resource observation, not Android/device memory promises. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConcurrentBorderMemoryHostTest {
    private fun procKiB(file:String,key:String):Long=File(file).readLines().first{it.startsWith("$key:")}.filter{it.isDigit()}.toLong()
    @Test fun borrowedAtlasSurvivesEvictionWhilePreviewAndFinalEncodeShareAdmission()=runBlocking {
        assumeTrue(System.getenv("FILMROOM_BORDER_CONCURRENCY")=="1")
        val native=System.getenv("FILMROOM_BORDER_CONCURRENCY_NATIVE")=="1"
        if(native){assertNotNull(System.getenv("FILMROOM_HOST_JNI_DIR"));assertTrue(NativeCodecBridge.available)}
        val app=ApplicationProvider.getApplicationContext<Application>()
        val root=File(System.getenv("FILMROOM_BORDER_CONCURRENCY_WORK") ?: "/workspace/shared/filmroom-border-webp-assessment/concurrency-work").apply{mkdirs()}
        require(root.usableSpace>=1024L*1024*1024){"At least 1 GiB free is required"}
        val job=File(root,UUID.randomUUID().toString()).apply{mkdir()}
        val baseline=procKiB("/proc/self/status","VmRSS")
        val hostAvailable=procKiB("/proc/meminfo","MemAvailable")
        val peakRss=AtomicLong(baseline);val peakDisk=AtomicLong();val monitorRunning=AtomicBoolean(true)
        val monitorError=AtomicReference<Throwable?>(null)
        val ample=NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)
        val memory=AtomicReference(ample)
        val decodeCalls=AtomicInteger();val offMainDecodes=AtomicInteger();val previews=AtomicInteger();val encodes=AtomicInteger()
        val exportActive=AtomicBoolean();val previewActive=AtomicBoolean();val decodesWhileExport=AtomicInteger();val rowsWhilePreview=AtomicInteger()
        val maxCached=AtomicInteger();var lowRejected=false;var cancelLeaseReleased=false;var completed=false
        val names=setOf("kodak_portra_800","kodak_kodachrome_64")
        val definitions=CandidateBorderDefinitions.definitions.filter{it.spec.stock in names}
        assertEquals(2,definitions.size)
        val registry=LazyBorderRegistry(definitions,definitions.associate{it.spec.stock to it.key}){definition,checkActive->
            check(Looper.myLooper()!=Looper.getMainLooper()){"Decode unexpectedly ran on Main"}
            BorderMemoryAdmission.withBudget(BorderMemoryAdmission.DECODE_ESTIMATE_BYTES,{memory.get()}){
                checkActive();decodeCalls.incrementAndGet();offMainDecodes.incrementAndGet()
                if(exportActive.get())decodesWhileExport.incrementAndGet()
                StraightAlphaBorderLoader.load(definition.spec,{app.assets.open(definition.assetPath)},checkActive)
            }
        }
        fun inspectCache(){val count=registry.cachedAtlasCount;maxCached.accumulateAndGet(count,::maxOf);assertTrue(count<=1)}
        val a=registry.snapshot("kodak_portra_800",1536,1024)
        val b=registry.snapshot("kodak_kodachrome_64",1536,1024)
        val monitor=thread(name="border-concurrency-resource-monitor"){
            try{while(monitorRunning.get()){
                peakRss.accumulateAndGet(procKiB("/proc/self/status","VmRSS"),::maxOf)
                peakDisk.accumulateAndGet(job.walkTopDown().filter{it.isFile}.sumOf{it.length()},::maxOf)
                Thread.sleep(10)
            }}catch(t:Throwable){monitorError.set(t)}
        }
        try{
            // Two simultaneous cold requests serialize one decode and borrow the same immutable raster.
            val coldReady=CountDownLatch(2);val coldGo=CountDownLatch(1)
            val first=async(Dispatchers.IO){coldReady.countDown();check(coldGo.await(30,TimeUnit.SECONDS));registry.resolve(a)}
            val second=async(Dispatchers.IO){coldReady.countDown();check(coldGo.await(30,TimeUnit.SECONDS));registry.resolve(a)}
            check(coldReady.await(30,TimeUnit.SECONDS));coldGo.countDown()
            val borrowed=first.await();assertSame(borrowed,second.await());assertEquals(1,decodeCalls.get());inspectCache()
            val original=FloatArray(3).also{borrowed.sample(a,.5,.5,it)}
            memory.set(ample.copy(lowMemory=true))
            withContext(Dispatchers.IO){assertThrows(PhotoException::class.java){registry.resolve(b)}}
            lowRejected=true;assertEquals(1,decodeCalls.get());assertEquals(0,registry.cachedAtlasCount)
            assertArrayEquals(original,FloatArray(3).also{borrowed.sample(a,.5,.5,it)},0f)
            memory.set(ample)
            withContext(Dispatchers.IO){registry.resolve(b)};assertEquals(2,decodeCalls.get());inspectCache()
            assertArrayEquals(original,FloatArray(3).also{borrowed.sample(a,.5,.5,it)},0f)

            val ready=CountDownLatch(2);val go=CountDownLatch(1)
            val previewTask=async(Dispatchers.IO){
                previewActive.set(true);ready.countDown();check(go.await(30,TimeUnit.SECONDS))
                val source=Bitmap.createBitmap(1536,1024,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff808080.toInt())}
                try{repeat(8){index->
                    val snap=registry.snapshot(if(index%2==0)a.stock else b.stock,1536,1024,1536,1024,CompositionTransform(.55,.45,1.5))
                    val raster=registry.resolve(snap);inspectCache()
                    val estimate=BorderCompositor.workingBytes(snap,raster)+snap.outputWidth.toLong()*snap.outputHeight*4
                    BorderMemoryAdmission.withBudget(estimate,{memory.get()}){
                        val rendered=BorderPreview.compose(source,snap,raster)
                        try{assertEquals(snap.outputWidth,rendered.width);assertEquals(snap.outputHeight,rendered.height);assertEquals(0xff808080.toInt(),rendered.getPixel(rendered.width/2,rendered.height/2));previews.incrementAndGet()}
                        finally{rendered.recycle()}
                    }
                }}finally{source.recycle();previewActive.set(false)}
            }
            val exportTask=async(Dispatchers.IO){
                exportActive.set(true);ready.countDown();check(go.await(30,TimeUnit.SECONDS))
                try{
                    val input=File(job,"post-film.rgb");val row=ByteBuffer.allocate(a.sourceWidth*12).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(a.sourceWidth*3){row.putFloat(LinearRgb.srgb8ToLinear[128])}
                    input.outputStream().buffered().use{out->repeat(a.sourceHeight){out.write(row.array())}}
                    val framed=File(job,"framed.rgb")
                    BorderMemoryAdmission.withBudget(BorderCompositor.workingBytes(a,borrowed),{memory.get()}){
                        BorderCompositor.composite(input,framed,a,borrowed,OutputColorSpace.SRGB){if(previewActive.get())rowsWhilePreview.incrementAndGet()}
                    }
                    assertEquals(a.outputBytes,framed.length())
                    if(native){
                        val codec=PhotoCodecs(job,memorySnapshot={memory.get()})
                        for(format in listOf("JPEG","TIFF16")){
                            val out=File(job,if(format=="JPEG")"encoded.jpg" else "encoded.tif")
                            codec.encode(framed,out,a.outputWidth,a.outputHeight,format,95,"sRGB")
                            assertTrue(out.length()>0);encodes.incrementAndGet()
                            if(format=="JPEG"){
                                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                                BitmapFactory.decodeFile(out.absolutePath,bounds);assertEquals(a.outputWidth,bounds.outWidth);assertEquals(a.outputHeight,bounds.outHeight)
                            }else out.inputStream().use{assertEquals('I'.code,it.read());assertEquals('I'.code,it.read())}
                            peakDisk.accumulateAndGet(job.walkTopDown().filter{it.isFile}.sumOf{it.length()},::maxOf)
                            check(out.delete())
                        }
                    }else{
                        val out=File(job,"encoded.png")
                        framed.inputStream().use{inputStream->out.outputStream().use{output->LinearRgbPng.encode(inputStream,a.outputWidth,a.outputHeight,output)}}
                        encodes.incrementAndGet();peakDisk.accumulateAndGet(job.walkTopDown().filter{it.isFile}.sumOf{it.length()},::maxOf);check(out.delete())
                    }
                    assertArrayEquals(original,FloatArray(3).also{borrowed.sample(a,.5,.5,it)},0f)
                }finally{exportActive.set(false)}
            }
            check(ready.await(30,TimeUnit.SECONDS));go.countDown()
            awaitAll(previewTask,exportTask)
            assertEquals(8,previews.get());assertEquals(if(native)2 else 1,encodes.get())
            assertTrue("Actual decode/export overlap",decodesWhileExport.get()>0)
            assertTrue("Actual composition/preview overlap",rowsWhilePreview.get()>0)
            assertEquals(10,decodeCalls.get());assertEquals(decodeCalls.get(),offMainDecodes.get());inspectCache()
            assertTrue(peakDisk.get()<200L*1024*1024)

            // No manual GC. A tiny exact ledger capacity proves cancellation released its lease.
            val budget=BorderMemoryAdmission.DECODE_ESTIMATE_BYTES
            val exact=NativeMemorySnapshot(64L*1024*1024+2*budget,0,false)
            assertThrows(java.util.concurrent.CancellationException::class.java){
                BorderMemoryAdmission.withBudget(budget,{exact},{Long.MAX_VALUE}){
                    assertThrows(NativeWorkUnavailable::class.java){NativeWorkReservations.retain(1,{exact}).close()}
                    throw java.util.concurrent.CancellationException("authored cancellation")
                }
            }
            BorderMemoryAdmission.withBudget(budget,{exact},{Long.MAX_VALUE}){};cancelLeaseReleased=true
            completed=true
        }finally{
            monitorRunning.set(false);monitor.join();registry.clearCache()
            val report=JSONObject().apply{
                put("completed",completed);put("scope","whole host JVM + native graphics + optional JNI, not device or atlas-only RSS")
                put("admission_fixture_available_bytes",ample.availableBytes);put("admission_fixture_threshold_bytes",ample.thresholdBytes)
                put("admission_fixture_note","Injected 4 GiB ample snapshot; not measured Android available memory")
                put("host_proc_memavailable_baseline_kib",hostAvailable);put("baseline_rss_kib",baseline);put("peak_rss_kib",peakRss.get());put("rss_increase_kib",peakRss.get()-baseline)
                put("peak_temp_logical_bytes",peakDisk.get());put("temp_bound_bytes",200L*1024*1024);put("sample_interval_ms",10)
                put("runtime_max_heap_bytes",Runtime.getRuntime().maxMemory());put("manual_gc",false)
                put("native_jpeg_tiff",native);put("preview_renders",previews.get());put("actual_encodes",encodes.get())
                put("atlas_decodes",decodeCalls.get());put("off_main_decodes",offMainDecodes.get());put("max_cached_atlases",maxCached.get())
                put("decodes_while_export_active",decodesWhileExport.get());put("compositor_checks_while_preview_active",rowsWhilePreview.get())
                put("low_os_rejected_before_decode",lowRejected);put("cancel_lease_released",cancelLeaseReleased)
                put("monitor_error",monitorError.get()?.toString() ?: JSONObject.NULL)
                for(name in listOf("memory.max","memory.current")){val file=File("/sys/fs/cgroup/$name");if(file.isFile)put("cgroup_$name",file.readText().trim())}
            }
            try{System.getenv("FILMROOM_BORDER_CONCURRENCY_REPORT")?.let{File(it).apply{parentFile?.mkdirs();writeText(report.toString(2)+"\n")}}}
            finally{job.deleteRecursively()}
            assertNull("Resource monitor failure",monitorError.get())
        }
    }
}
