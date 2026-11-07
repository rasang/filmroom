package dev.filmroom.export

import android.app.Application
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.DownloadsExportHostTest
import dev.filmroom.codec.*
import dev.filmroom.engine.*
import dev.filmroom.io.*
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowStatFs
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fake native compute/codec, real queue/cache/metadata/journal/provider flow. Not a GPU speed test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class EncodedExportQueueHostTest {
    private class Processor:ExportProcessing {
        var renders=0;var healthy=true;var request="science-v1:budget256:data-sha:gpu-owner";var receipt:EncodedReuseIdentity?=null
        var onLookup:suspend ()->Unit={}
        var onPlan:suspend ()->Unit={}
        var afterRender:(File)->Unit={}
        override fun cancel(){}
        override suspend fun plan(width:Int,height:Int,parameters:EditorState):NativeExportPlan {
            onPlan();return NativeExportPlan(width,height,width.toLong()*height*12,width.toLong()*height*12,0,width.toLong()*height*24)
        }
        override suspend fun render(input:File,width:Int,height:Int,parameters:EditorState,workspace:File,onProgress:(EngineProgress)->Unit):File {
            renders++;receipt=EncodedReuseIdentity(request,"{\"probe\":{\"backend\":\"wgpu\"},\"tiles\":{\"coreWidth\":2,\"coreHeight\":2}}")
            return File(workspace,"film-result-${java.util.UUID.randomUUID()}.rgb").also{input.copyTo(it,true);afterRender(input)}
        }
        override suspend fun encodedReuseIdentity(width:Int,height:Int,parameters:EditorState):String? {onLookup();return request.takeIf{healthy}}
        override fun completedEncodedReuseIdentity()=receipt
    }
    private class Api:CodecNativeApi {
        override val available=true
        var calls=0;var legacy=false;var invalid=false
        override fun nativeCreate(root:String)=1L
        override fun nativeCancel(handle:Long){}
        override fun nativeDestroy(handle:Long){}
        override fun nativeProbe(handle:Long,input:String,budget:String)="{}"
        override fun nativeDecode(handle:Long,input:String,output:String,budget:String)="{}"
        override fun nativeEncode(handle:Long,input:String,output:String,parameters:String):String {
            calls++;val p=JSONObject(parameters);val format=p.getString("format")
            val bytes=if(format=="JPEG")jpeg()else tiff()
            File(output).writeBytes(bytes)
            if(legacy)return "{\"ok\":true}"
            return JSONObject().put("ok",true).put("schemaVersion",1).put("operation","encode").put("stage","complete").put("outputValid",!invalid)
                .put("format",format).put("width",2).put("height",2).put("colorSpace",p.getString("outputColorSpace"))
                .put("bitDepth",if(format=="JPEG")8 else 16).put("outputOrientation",1).put("outputBytes",bytes.size).toString()
        }
    }
    private data class Fixture(val app:Application,val queue:ExportQueue,val repository:PhotoRepository,val api:Api,val processor:Processor,val provider:DownloadsExportHostTest.Provider,val source:PhotoSource)
    private fun setup(sourceHashNanoTime:()->Long=System::nanoTime):Fixture {
        val app=ApplicationProvider.getApplicationContext<Application>()
        File(app.filesDir,"export-jobs").deleteRecursively()
        EncodedPayloadCache.get(app.cacheDir).trimForReserve(Long.MAX_VALUE){0}
        ShadowStatFs.registerStats(app.filesDir.path,4096,1_000_000,1_000_000)
        ShadowStatFs.registerStats(app.cacheDir.path,4096,1_000_000,1_000_000)
        val provider=DownloadsExportHostTest.Provider();provider.attachInfo(app,android.content.pm.ProviderInfo().apply{authority=MediaStore.AUTHORITY;exported=false})
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY,provider)
        app.getSharedPreferences("filmroom-pending-exports",0).edit().clear().commit()
        val api=Api();val processor=Processor()
        val repository=PhotoRepository(app,PhotoCodecs(app.cacheDir,api,memorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)},exportJobsRoot=File(app.filesDir,"export-jobs")))
        val queue=ExportQueue(app,processor,repository=repository,sourceHashNanoTime=sourceHashNanoTime)
        val encoded=File(app.cacheDir,"encoded-test-source.image").apply{writeText("source")}
        val linear=File(app.cacheDir,"encoded-test-source.rgb").apply{writeBytes(ByteArray(48))}
        val metadata=PhotoMetadata.fromAttributes(mapOf("Make" to "Camera","DateTimeOriginal" to "2001:02:03 04:05:06","OffsetTimeOriginal" to "+01:00","GPSLatitudeRef" to "N","GPSLatitude" to "1/1,2/1,3/1"))
        val source=PhotoSource(encoded,"private","image/dng",2,2,6,linearFile=linear,photoMetadata=metadata)
        return Fixture(app,queue,repository,api,processor,provider,source)
    }
    private suspend fun run(f:Fixture,format:ExportFormat=ExportFormat.JPEG,p:EditorState=EditorState()):ExportJob {
        val item=f.queue.enqueue(f.source,p,format,null,95);f.queue.runPending{}
        return f.queue.store.get(item.id)!!.also{assertEquals(it.message,ExportJobState.COMPLETED,it.state)}
    }
    private fun bytes(f:Fixture,job:ExportJob)=File(f.app.cacheDir,"provider-${android.net.Uri.parse(job.mediaUri).lastPathSegment}.bin").readBytes().also{assertTrue("Published ${job.format} row must retain encoded bytes after thumbnail read",it.isNotEmpty())}
    @Test fun twoJobsReusePayloadButPublishSeparateRowsWithFreshExifAndColdIdenticalBytes()=runBlocking {
        for(format in listOf(ExportFormat.JPEG,ExportFormat.TIFF16,ExportFormat.PNG)) {
            val f=setup();val first=run(f,format);delay(5);val second=run(f,format)
            assertEquals(1,f.processor.renders);assertEquals(if(format==ExportFormat.PNG)0 else 1,f.api.calls)
            assertNotEquals(first.mediaUri,second.mediaUri);assertNotEquals(first.exportMillis,second.exportMillis)
            assertEquals(2,f.provider.rows.size);assertTrue(f.provider.rows.values.all{it.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==0})
            assertFalse(bytes(f,first).contentEquals(bytes(f,second)))
            // Independent cold encode under the SECOND job's precise metadata/time must match all bytes.
            val cold=f.repository.exportLinear(f.source.linearFile!!,2,2,format,photoMetadata=f.source.photoMetadata,exportMillis=second.exportMillis!!)
            assertArrayEquals(File(f.app.cacheDir,"provider-${cold.uri.lastPathSegment}.bin").readBytes(),bytes(f,second))
            assertEquals(0,f.repository.cleanupPendingExports().removed)
            assertTrue(f.source.linearFile!!.exists())
        }
    }
    @Test fun newMetadataNeverRetainsOldGpsEvenWhenReusingAnEncodedPixelPayload()=runBlocking {
        val f=setup();var bare:ByteArray?=null
        f.repository.exportLinear(f.source.linearFile!!,2,2,ExportFormat.JPEG,photoMetadata=f.source.photoMetadata,exportMillis=1000,onEncodedPayload={bare=it.readBytes()})
        val fresh=PhotoMetadata.fromAttributes(mapOf("Make" to "Different Camera","DateTimeOriginal" to "2020:01:02 03:04:05","OffsetTimeOriginal" to "+00:00"))
        val copy=File(f.app.cacheDir,"fresh-payload.jpg").apply{writeBytes(checkNotNull(bare))}
        val warm=f.repository.exportEncodedPayload(copy,2,2,ExportFormat.JPEG,"sRGB",fresh,2001)
        val cold=f.repository.exportLinear(f.source.linearFile!!,2,2,ExportFormat.JPEG,photoMetadata=fresh,exportMillis=2001)
        val warmFile=File(f.app.cacheDir,"provider-${warm.uri.lastPathSegment}.bin")
        assertArrayEquals(File(f.app.cacheDir,"provider-${cold.uri.lastPathSegment}.bin").readBytes(),warmFile.readBytes())
        val exif=androidx.exifinterface.media.ExifInterface(warmFile.path)
        assertNull(exif.getAttribute("GPSLatitude"));assertEquals("Different Camera",exif.getAttribute("Make"));assertEquals("001",exif.getAttribute("SubSecTime"))
    }
    @Test fun changedBackendBudgetAndUnhealthyProbeFallBackToScience()=runBlocking {
        val f=setup();run(f);f.processor.request="different-budget-or-device";run(f);f.processor.healthy=false;run(f)
        assertEquals(3,f.processor.renders);assertEquals(3,f.api.calls)
    }
    @Test fun legacyCodecProofKeepsItsOriginalColdBehavior()=runBlocking {
        val f=setup();f.api.legacy=true;run(f);run(f);assertEquals(2,f.processor.renders)
    }
    @Test fun corruptCacheFallsBackBeforeAnyMediaRowIsPrepared()=runBlocking {
        for(format in listOf(ExportFormat.JPEG,ExportFormat.PNG)) {
            val f=setup();run(f,format);val payload=File(f.app.cacheDir,"encoded-payload-v1/payload")
            payload.writeBytes(payload.readBytes().also{it[it.size/2]=(it[it.size/2].toInt() xor 1).toByte()})
            run(f,format);assertEquals(2,f.processor.renders);assertEquals(if(format==ExportFormat.PNG)0 else 2,f.api.calls);assertEquals(2,f.provider.rows.size)
        }
    }
    @Test fun pngWideGamutReuseMatchesCurrentColdMetadataAndPixels()=runBlocking {
        for(space in listOf(OutputColorSpace.DISPLAY_P3,OutputColorSpace.ADOBE_RGB)) {
            val f=setup();val parameters=EditorState(outputColorSpace=space.id)
            val first=run(f,ExportFormat.PNG,parameters);val second=run(f,ExportFormat.PNG,parameters)
            assertEquals(1,f.processor.renders);assertEquals(0,f.api.calls);assertNotEquals(first.mediaUri,second.mediaUri)
            val cold=f.repository.exportLinear(f.source.linearFile!!,2,2,ExportFormat.PNG,outputColorSpace=space.id,photoMetadata=f.source.photoMetadata,exportMillis=second.exportMillis!!)
            assertArrayEquals(File(f.app.cacheDir,"provider-${cold.uri.lastPathSegment}.bin").readBytes(),bytes(f,second))
        }
    }
    @Test fun cancelledPngEncodingRemovesPendingRowAndNeverInstallsPartialPayload()=runBlocking {
        val f=setup();val cache=EncodedPayloadCache.get(f.app.cacheDir);val key=EncodedPayloadCache.key("cancelled-png")
        val side=cache.beginStream(key,"receipt"){_,write->write();true}!!
        try {
            try {
                f.repository.exportLinearRgbPng(f.source.linearFile!!,2,2,pixelPayload=side,onProgress={_,_->throw CancellationException("cancel PNG row")})
                fail("Expected cancellation")
            }catch(_:CancellationException){}
        }finally{side.close()}
        assertNull(cache.acquire(key));assertTrue(f.provider.rows.isEmpty())
        assertFalse(File(f.app.cacheDir,"encoded-payload-v1/payload.part").exists())
        assertEquals(0,f.repository.cleanupPendingExports().removed)
    }

    @Test fun oldProcessingAdapterDefaultNeverEnablesCache()=runBlocking {
        val old=object:ExportProcessing {
            override suspend fun plan(width:Int,height:Int,parameters:EditorState):NativeExportPlan=error("unused")
            override suspend fun render(input:File,width:Int,height:Int,parameters:EditorState,workspace:File,onProgress:(EngineProgress)->Unit):File=error("unused")
            override fun cancel(){}
        }
        assertNull(old.encodedReuseIdentity(2,2,EditorState()));assertNull(old.completedEncodedReuseIdentity())
    }
    @Test fun warmHitBlocksProcessWidePrewarmThroughLookupAndPublicationWithoutBlockingScience()=runBlocking {
        // PNG assertions intentionally target the integrated PNG delta, not base3491.
        for(format in listOf(ExportFormat.JPEG,ExportFormat.TIFF16,ExportFormat.PNG)) {
            val f=setup()
            f.processor.onPlan={ExportWorkAdmissions.run {assertTrue(ExportWorkAdmissions.active.value)}}
            val first=withTimeout(5000){run(f,format)}
            val renders=f.processor.renders;val codecCalls=f.api.calls
            val lookupFlags=mutableListOf<Pair<Boolean,Boolean>>()
            f.processor.onLookup={lookupFlags+=ExportWorkAdmissions.prewarmBlocked to ExportWorkAdmissions.active.value}
            val item=f.queue.enqueue(f.source,EditorState(),format,null,95)
            val publicationFlags=mutableListOf<Pair<Boolean,Boolean>>()
            withTimeout(5000){f.queue.runPending {job->if(job.mediaUri!=null)publicationFlags+=ExportWorkAdmissions.prewarmBlocked to ExportWorkAdmissions.active.value}}
            val second=checkNotNull(f.queue.store.get(item.id))
            assertEquals(ExportJobState.COMPLETED,second.state);assertNotEquals(first.mediaUri,second.mediaUri)
            assertEquals("This must actually be a cache hit",renders,f.processor.renders);assertEquals(codecCalls,f.api.calls)
            assertTrue(lookupFlags.isNotEmpty());assertTrue(lookupFlags.all{it==(true to false)})
            assertTrue(publicationFlags.size>=2);assertTrue(publicationFlags.all{it==(true to false)})
            assertTrue(f.provider.rows.values.all{it.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==0})
            assertFalse(ExportWorkAdmissions.prewarmBlocked);assertFalse(ExportWorkAdmissions.active.value)
            assertTrue(f.source.linearFile!!.exists())
        }
    }
    @Test fun cancelledWarmLookupClearsActivityAndKeepsOriginalSource()=runBlocking {
        val f=setup();run(f)
        val entered=CompletableDeferred<Unit>()
        f.processor.onLookup={entered.complete(Unit);awaitCancellation()}
        val item=f.queue.enqueue(f.source,EditorState(),ExportFormat.JPEG,null,95)
        val runner=launch {f.queue.runPending{}}
        try {
            withTimeout(5000){entered.await()};assertTrue(ExportWorkAdmissions.prewarmBlocked)
            withTimeout(5000){f.queue.cancel(item.id);runner.join()}
            assertEquals(ExportJobState.CANCELLED,f.queue.store.get(item.id)!!.state)
            assertFalse(ExportWorkAdmissions.prewarmBlocked);assertFalse(ExportWorkAdmissions.active.value)
            assertEquals(1,f.processor.renders);assertEquals(1,f.provider.rows.size);assertTrue(f.source.linearFile!!.exists())
        } finally {runner.cancelAndJoin()}
    }
    @Test fun warmOutputFailureClearsActivityWithoutInstallingAnotherRow()=runBlocking {
        val f=setup();run(f)
        f.provider.openFailure=PhotoException(PhotoException.Reason.UNREADABLE,"synthetic warm write failure")
        val item=f.queue.enqueue(f.source,EditorState(),ExportFormat.JPEG,null,95)
        withTimeout(5000){f.queue.runPending{}}
        assertEquals(ExportJobState.FAILED,f.queue.store.get(item.id)!!.state)
        assertEquals(1,f.processor.renders);assertEquals(1,f.api.calls);assertEquals(1,f.provider.rows.size)
        assertFalse(ExportWorkAdmissions.prewarmBlocked);assertFalse(ExportWorkAdmissions.active.value)
        assertTrue(f.source.linearFile!!.exists())
    }
    @Test fun rejectedGenerationAndAlreadyCommittedRecoveryReleaseActivity()=runBlocking {
        val f=setup();val complete=run(f)
        f.queue.store.change(complete.id){it.copy(state=ExportJobState.RETRY)}
        withTimeout(5000){f.queue.runPending{}}
        assertEquals(ExportJobState.COMPLETED,f.queue.store.get(complete.id)!!.state)
        assertEquals(1,f.processor.renders);assertEquals(1,f.provider.rows.size)
        assertFalse(ExportWorkAdmissions.prewarmBlocked)
        val rejected=f.queue.enqueue(f.source,EditorState(),ExportFormat.JPEG,null,95)
        f.queue.store.change(rejected.id){it.copy(requestGeneration=Long.MIN_VALUE)}
        withTimeout(5000){f.queue.runPending{}}
        assertEquals(ExportJobState.PAUSED,f.queue.store.get(rejected.id)!!.state)
        assertFalse(ExportWorkAdmissions.prewarmBlocked);assertFalse(ExportWorkAdmissions.active.value)
    }
    /** Advancing only during a hash makes expiry deterministic without sleeping or large RAM. */
    private class HashClock {
        var expire=false
        private var now=0L
        fun read():Long {if(expire)now+=EncodedSourceHash.HASH_BUDGET_NANOS;return now}
    }
    private fun assertSettled(f:Fixture,rows:Int) {
        assertEquals(rows,f.provider.rows.size)
        assertTrue(f.provider.rows.values.all{it.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==0})
        assertFalse(File(f.app.cacheDir,"encoded-payload-v1/payload.part").exists())
        assertFalse(ExportWorkAdmissions.prewarmBlocked)
        assertFalse(ExportWorkAdmissions.active.value)
        assertTrue(f.source.linearFile!!.exists())
    }
    @Test fun sourceHashLookupExpiryFallsBackColdWithoutDestroyingExistingEntry()=runBlocking {
        val clock=HashClock();val f=setup(clock::read)
        run(f);assertEquals(1,f.processor.renders)
        clock.expire=true
        val cold=run(f)
        assertEquals(2,f.processor.renders);assertEquals(2,f.api.calls);assertTrue(bytes(f,cold).isNotEmpty())
        assertSettled(f,2)
        clock.expire=false
        run(f)
        assertEquals("Lookup timeout must not destroy the previous valid payload",2,f.processor.renders)
        assertEquals(2,f.api.calls);assertSettled(f,3)
    }
    @Test fun sourceHashPublicationExpiryKeepsSuccessfulOutputAndDropsPngTee()=runBlocking {
        for(format in listOf(ExportFormat.JPEG,ExportFormat.TIFF16,ExportFormat.PNG)) {
            val clock=HashClock();val f=setup(clock::read);var lookups=0;var sawPngTee=false
            f.processor.onLookup={
                lookups++
                // PNG hashes once before installing its tee and again after normal publication.
                if(lookups==(if(format==ExportFormat.PNG)3 else 2)) {
                    if(format==ExportFormat.PNG) {
                        val part=File(f.app.cacheDir,"encoded-payload-v1/payload.part")
                        assertTrue("Exercise an actual completed tee, not pre-tee rejection",part.length()>0)
                        sawPngTee=true
                    }
                    clock.expire=true
                }
            }
            val first=run(f,format)
            assertTrue(bytes(f,first).isNotEmpty());assertSettled(f,1)
            assertFalse(File(f.app.cacheDir,"encoded-payload-v1/payload").exists())
            if(format==ExportFormat.PNG)assertTrue(sawPngTee)
            f.processor.onLookup={};clock.expire=false
            run(f,format);assertEquals("Expired publication must not produce a hit",2,f.processor.renders)
            run(f,format);assertEquals("A subsequent complete attempt can publish normally",2,f.processor.renders)
            assertEquals(if(format==ExportFormat.PNG)0 else 2,f.api.calls);assertSettled(f,3)
        }
    }
    @Test fun sourceMutationAfterRenderCannotPublishOldDigest()=runBlocking {
        for(format in listOf(ExportFormat.JPEG,ExportFormat.PNG)) {
            val f=setup{0}
            f.processor.afterRender={input->
                // Preserve length; a changed finite RGB float must be found by content revalidation.
                RandomAccessFile(input,"rw").use{it.seek(3);it.write(0x3f)}
            }
            run(f,format)
            assertFalse(File(f.app.cacheDir,"encoded-payload-v1/payload").exists());assertSettled(f,1)
            f.processor.afterRender={}
            run(f,format);assertEquals(2,f.processor.renders)
            run(f,format);assertEquals(2,f.processor.renders);assertSettled(f,3)
        }
    }
    @Test fun sparseLargeResizedLinearSourceUsesFullSourceIdentityBeforePreparedInput()=runBlocking {
        val f=setup{0}
        val sourceBytes=2560L*2560*12
        assertTrue(sourceBytes>EncodedPayloadCache.MAX_SOURCE_BYTES)
        fun pending(mutated:Boolean):Pair<ExportJob,File> {
            val job=ExportJob(ExportJobStore.newId(),System.currentTimeMillis(),EditorState(),2,2,
                ExportFormat.JPEG,95,inputKind="LINEAR_SOURCE",sourceWidth=2560,sourceHeight=2560,
                maxEdge=2,requestGeneration=ExportQueue.currentGeneration(),photoMetadata=f.source.photoMetadata.toJson())
            val dir=f.queue.store.directory(job.id);check(dir.mkdirs())
            val source=File(dir,"source.rgb")
            RandomAccessFile(source,"rw").use {
                it.setLength(sourceBytes)
                if(mutated){it.seek(EncodedPayloadCache.MAX_SOURCE_BYTES+17);it.write(1)}
            }
            // An existing tiny prepared spool must not be mistaken for the full source identity.
            File(dir,"input.rgb").writeBytes(ByteArray(48))
            f.queue.store.update(job)
            return job to source
        }
        try {
        val (first,source)=pending(false)
        val digest=checkNotNull(EncodedSourceHash("LINEAR_SOURCE",2560,2560,{0}).hash(source){})
        val metadata=f.source.photoMetadata
        val key=EncodedExportIdentity.key(first,digest,f.processor.request,metadata)
        val preparedDigest=checkNotNull(EncodedSourceHash("LINEAR",2,2,{0}).hash(File(source.parentFile,"input.rgb")){})
        assertNotEquals(key,EncodedExportIdentity.key(first,preparedDigest,f.processor.request,metadata))
        val encoded=File(f.app.cacheDir,"large-source-seed.jpg").apply{writeBytes(jpeg())}
        try {
            val receipt="{\"probe\":{\"backend\":\"wgpu\"}}"
            checkNotNull(EncodedPayloadCache.get(f.app.cacheDir).stage(key,encoded,receipt,0,{Long.MAX_VALUE}){}).commit{}
            f.queue.runPending{}
            val warm=checkNotNull(f.queue.store.get(first.id))
            assertEquals(warm.message,ExportJobState.COMPLETED,warm.state)
            assertEquals(0,f.processor.renders);assertEquals(0,f.api.calls);assertTrue(bytes(f,warm).isNotEmpty())
            assertSettled(f,1)
            val (second,_)=pending(true)
            f.queue.runPending{}
            val cold=checkNotNull(f.queue.store.get(second.id))
            assertEquals(cold.message,ExportJobState.COMPLETED,cold.state)
            assertEquals("Mutation beyond 64 MiB must miss despite identical prepared input",1,f.processor.renders)
            assertEquals(1,f.api.calls);assertTrue(bytes(f,cold).isNotEmpty());assertSettled(f,2)
        } finally {encoded.delete()}
        } finally {File(f.app.filesDir,"export-jobs").deleteRecursively()}
    }

    companion object {
        private fun jpeg():ByteArray {
            val cls=Class.forName("java.awt.image.BufferedImage")
            val image=cls.getConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).newInstance(2,2,1)
            val bytes=ByteArrayOutputStream()
            check(Class.forName("javax.imageio.ImageIO").getMethod("write",Class.forName("java.awt.image.RenderedImage"),String::class.java,OutputStream::class.java).invoke(null,image,"jpeg",bytes)==true)
            return bytes.toByteArray()
        }
        private fun tiff():ByteArray {
            val count=11;val data=8+2+count*12+4;val b=ByteBuffer.allocate(data+6+24).order(ByteOrder.LITTLE_ENDIAN)
            b.put(73).put(73).putShort(42).putInt(8).putShort(count.toShort())
            fun e(tag:Int,type:Int,n:Int,value:Int){b.putShort(tag.toShort()).putShort(type.toShort()).putInt(n).putInt(value)}
            e(256,4,1,2);e(257,4,1,2);e(258,3,3,data);e(259,3,1,1);e(262,3,1,2);e(273,4,1,data+6);e(274,3,1,1);e(277,3,1,3);e(278,4,1,2);e(279,4,1,24);e(284,3,1,1)
            b.putInt(0).putShort(16).putShort(16).putShort(16);return b.array()
        }
    }
}
