package dev.filmroom.engine

import android.graphics.Bitmap
import dev.filmroom.diagnostics.*
import dev.filmroom.io.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class OwnedDetailProviderTest {
    private val memory={NativeMemorySnapshot(8L*1024*1024*1024,0,false)}
    private val key=DetailKey("source","mapping","data","{}",300,260)
    private open class Fake(val key:DetailKey):DetailNativeApi {
        var malformed=false;var block=false
        val entered=CountDownLatch(1);val exit=CountDownLatch(1);val destroyed=CountDownLatch(1)
        val cancels=AtomicInteger();val released=AtomicInteger();val begins=AtomicInteger()
        fun ok(op:String)=JSONObject().put("schemaVersion",1).put("operation",op).put("ok",true).put("code","OK").put("outputValid",false)
        open fun identity()=JSONObject().put("sourceRevision",key.source).put("mappingRevision",key.mapping).put("dataRevision",key.data).put("width",key.width).put("height",key.height).put("parameters",key.parameters).put("algorithm","owned-detail-v1-f64-fixed256-gutter1")
        open fun result(op:String)=ok(op).put("identity",identity()).put("quality","ExactSameWorkingGrid").put("backend",JSONObject().put("actualBackend","cpu").put("requestedGpu",true).put("cpuFallback",true).put("reason","gpu_not_built").put("acceleratedStage","none").put("gpuBatches",0))
        override fun nativeCapabilities()=ok("capabilities").toString()
        override fun nativePlan(requestJson:String):String=error("unused")
        override fun nativeCreate(canonicalPrivateAppRoot:String)=ok("create").put("sessionId","1").toString()
        override fun nativeBeginOperation(sessionId:Long)=ok("beginOperation").put("operationId",begins.incrementAndGet().toString()).toString()
        override fun nativeBuild(sessionId:Long,operationId:Long,sourceRelative:String,requestJson:String)=result("build").toString()
        open fun channel(x:Int,y:Int,ch:Int):Float=0.5f
        override fun nativeTile(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer):String {
            entered.countDown();if(block)check(exit.await(10,TimeUnit.SECONDS))
            val x=cellX*256;val y=cellY*256;val w=minOf(256,key.width-x);val h=minOf(256,key.height-y)
            val sx=maxOf(0,x-1);val sy=maxOf(0,y-1);val sw=minOf(key.width,x+w+1)-sx;val sh=minOf(key.height,y+h+1)-sy
            for(py in 0 until sh)for(px in 0 until sw)for(ch in 0..2)output.putFloat(channel(sx+px,sy+py,ch))
            fun rect(x:Int,y:Int,w:Int,h:Int)=JSONObject().put("x",x).put("y",y).put("w",w).put("h",h)
            return result("tile").put("outputValid",true).put("bytesWritten",sw*sh*12+(if(malformed)1 else 0)).put("coreRect",rect(x,y,w,h)).put("sampleRect",rect(sx,sy,sw,sh)).toString()
        }
        override fun nativeRegion(sessionId:Long,operationId:Long,x:Int,y:Int,width:Int,height:Int,output:ByteBuffer):String {
            val sx=maxOf(0,x-1);val sy=maxOf(0,y-1);val sw=minOf(key.width,x+width+1)-sx;val sh=minOf(key.height,y+height+1)-sy
            for(py in 0 until sh)for(px in 0 until sw)for(ch in 0..2)output.putFloat(channel(sx+px,sy+py,ch))
            fun rect(x:Int,y:Int,w:Int,h:Int)=JSONObject().put("x",x).put("y",y).put("w",w).put("h",h)
            return result("region").put("outputValid",true).put("bytesWritten",sw*sh*12).put("coreRect",rect(x,y,width,height)).put("sampleRect",rect(sx,sy,sw,sh)).toString()
        }
        override fun nativeCancel(sessionId:Long,operationId:Long):String{cancels.incrementAndGet();return ok("cancel").toString()}
        override fun nativeReleaseOperation(sessionId:Long,operationId:Long):String{released.incrementAndGet();return ok("releaseOperation").toString()}
        override fun nativeDestroy(sessionId:Long):String{destroyed.countDown();return ok("destroy").toString()}
    }
    private fun retained(clean:()->Unit):RetainedNativeWork {
        val owner=NativeWorkReservations.retainForDetail(1L,memory)
        return object:RetainedNativeWork {
            override suspend fun <T> run(memory:()->NativeMemorySnapshot,work:suspend ()->T):T=owner.run(memory,work)
            override fun close(){try{owner.close()}finally{clean()}}
        }
    }
    private fun session(fake:Fake,clean:AtomicInteger)=OwnedDetailSession(fake,1,key,true,retained{clean.incrementAndGet()},DetailSource(300,260,"source","mapping","sources/test"){clean.incrementAndGet()},memory)
    @Test fun validatesGuttersAndUsesSrgbTransfer()=runBlocking {
        val f=Fake(key);val c=AtomicInteger();val s=session(f,c);s.build("{}")
        val tile=s.tile(1,1)
        assertEquals(DetailRect(256,256,44,4),tile.core);assertEquals(DetailRect(255,255,45,5),tile.sample)
        assertEquals(188,(tile.bitmap.getPixel(0,0) shr 16) and 255)
        tile.bitmap.recycle();s.close();withTimeout(2000){while(c.get()!=2)delay(5)}
    }
    @Test fun rejectsMalformedOutput()=runBlocking {
        val f=Fake(key);f.malformed=true;val c=AtomicInteger();val s=session(f,c);s.build("{}")
        try{s.tile(0,0);fail()}catch(e:DetailUnavailable){assertEquals("INVALID_RESULT",e.code)}finally{s.close()}
    }
    @Test fun destroyDoesNotReleaseWorkerReservationUntilNativeReturns()=runBlocking {
        val f=Fake(key);f.block=true;val c=AtomicInteger();val s=session(f,c);s.build("{}")
        val worker=launch(Dispatchers.Default){s.tile(0,0)}
        assertTrue(f.entered.await(5,TimeUnit.SECONDS));worker.cancel();s.close()
        assertTrue(f.destroyed.await(5,TimeUnit.SECONDS));assertEquals(0,c.get())
        withTimeout(2000){while(f.cancels.get()==0)delay(5)}
        f.exit.countDown();worker.join();withTimeout(2000){while(c.get()!=2)delay(5)}
    }
    @Test fun passiveBytesDoNotConsumeWorkerSlotOrDeadlock()=runBlocking {
        val limiter=NativeWorkLimiter();val retain=limiter.retain(100L*1024*1024,memory())
        assertEquals(0,limiter.activeCount());assertEquals(100L*1024*1024,limiter.reservedBytes())
        limiter.run(1,memory){assertEquals(1,limiter.activeCount())};retain.close();assertEquals(0,limiter.reservedBytes())
        try{limiter.retain(1024,NativeMemorySnapshot(0,0,true));fail()}catch(_:NativeWorkUnavailable){}
    }
    @Test fun cancellationBeforeNativeDispatchAbandonsAllocatedToken()=runBlocking {
        val f=Fake(key);val c=AtomicInteger();val s=session(f,c);s.build("{}")
        val ready=CountDownLatch(2);val gate=CompletableDeferred<Unit>()
        val holders=List(2){launch(Dispatchers.Default){NativeWorkReservations.run(1,memory){ready.countDown();gate.await()}}}
        assertTrue(ready.await(5,TimeUnit.SECONDS))
        val worker=launch(Dispatchers.Default){s.tile(0,0)}
        withTimeout(2000){while(f.begins.get()<2)delay(5)}
        worker.cancelAndJoin();assertEquals(1,f.released.get());assertEquals(1L,f.entered.count)
        gate.complete(Unit);holders.forEach{it.join()};s.close()
    }
    @Test fun passivePressureEvictsInsteadOfWaitingForever()=runBlocking {
        val limiter=NativeWorkLimiter();var retained:AutoCloseable?=null
        retained=limiter.retain(100L*1024*1024,memory()){retained!!.close()}
        val low={NativeMemorySnapshot(200L*1024*1024,0,false)}
        withTimeout(2000){limiter.run(1,low){assertEquals(1,limiter.activeCount())}}
        assertEquals(0,limiter.reservedBytes())
    }
    @Test fun originalLinearGridPreservesNegativeHdrAndNeverUpscales()=runBlocking {
        val root=Files.createTempDirectory("detail-test").toFile()
        try {
            val sourceFile=File(root,"original.raw").apply{writeText("raw")}
            val linear=File(root,"linear.f32le")
            val bytes=ByteBuffer.allocate(4*2*12).order(ByteOrder.LITTLE_ENDIAN);repeat(8){bytes.putFloat(-0.5f);bytes.putFloat(2f);bytes.putFloat(0.5f)};linear.writeBytes(bytes.array())
            val photo=PhotoSource(sourceFile,"test","image/heic",4,2,3,linearFile=linear)
            val prepared=DetailSourceFactory(root,memory).prepare(photo,1536)
            assertEquals(4,prepared.width);assertEquals(2,prepared.height)
            assertTrue(prepared.mappingRevision.contains("image/heic"));assertTrue(prepared.mappingRevision.contains("linear=true"))
            val grid=File(root,"cache/detail-preview/${prepared.relativePath}")
            val floats=ByteBuffer.wrap(grid.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(-0.5f,floats.float,0f);assertEquals(2f,floats.float,0f)
            prepared.close();assertTrue(grid.exists());photo.clearLinearPreview();photo.detailGridCache.awaitRetirements();assertFalse(grid.exists());assertTrue(linear.exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun orphanCleanupPreservesLiveUnknownAndSymlinkTargets()=runBlocking {
        val root=Files.createTempDirectory("detail-orphan").toFile()
        try {
            val raw=File(root,"source").apply{writeText("source")};val linear=File(root,"linear")
            val bytes=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(0f).putFloat(1f).putFloat(2f);linear.writeBytes(bytes.array())
            val factory=DetailSourceFactory(root,memory)
            val photo=PhotoSource(raw,"test","image/heic",1,1,6,linearFile=linear)
            val prepared=factory.prepare(photo)
            val grid=File(root,"cache/detail-preview/${prepared.relativePath}")
            factory.cleanupOrphans();assertTrue(grid.exists())
            val sources=grid.parentFile!!.parentFile!!
            val unknown=File(sources,"unknown").apply{mkdir()};File(unknown,"keep").writeText("keep")
            val outside=File(root,"outside").apply{mkdir()};val keep=File(outside,"keep").apply{writeText("keep")}
            val orphan=File(sources,"grid-00000000-0000-0000-0000-000000000000").apply{mkdir()}
            File(orphan,".owned-detail-source-v1").writeText("filmroom-detail-v1");File(orphan,".lease").writeText("")
            Files.createSymbolicLink(File(orphan,"escape").toPath(),outside.toPath())
            factory.cleanupOrphans();assertFalse(orphan.exists());assertTrue(keep.exists());assertTrue(unknown.exists());assertTrue(grid.exists())
            prepared.close();photo.clearLinearPreview();photo.detailGridCache.awaitRetirements()
        }finally{root.deleteRecursively()}
    }
    @Test fun failedOwnershipMarkerCleansDirectoryAndLease()=runBlocking {
        val root=Files.createTempDirectory("detail-marker-fail").toFile()
        try {
            val raw=File(root,"source").apply{writeText("source")};val linear=File(root,"linear").apply{writeBytes(ByteArray(12))}
            val factory=DetailSourceFactory(root,memory){file->file.writeText("partial");throw java.io.IOException("injected full disk")}
            try{factory.prepare(PhotoSource(raw,"test","image/heic",1,1,6,linearFile=linear));fail()}catch(_:java.io.IOException){}
            assertTrue(File(root,"cache/detail-preview/sources").listFiles().orEmpty().isEmpty())
            assertTrue(raw.exists());assertTrue(linear.exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun trustedRootAliasReachesNativePlanAndCleansItsPreparedGrid()=runBlocking {
        val outer=Files.createTempDirectory("detail-provider-root-alias").toFile().canonicalFile
        try {
            val real=File(outer,"data/dev.filmroom").apply{mkdirs()}
            val aliasParent=File(outer,"user/0");aliasParent.parentFile!!.mkdirs()
            Files.createSymbolicLink(aliasParent.toPath(),File(outer,"data").toPath())
            val alias=File(aliasParent,"dev.filmroom")
            File(alias,"files/engine-data").mkdirs()
            val raw=File(alias,"source").apply{writeText("original")}
            val linear=File(alias,"linear").apply{writeBytes(ByteArray(12))}
            var reachedPlan=false
            val api=object:Fake(key){override fun nativePlan(requestJson:String):String {
                reachedPlan=true
                throw UnsatisfiedLinkError("deliberately stop after source preparation")
            }}
            val provider=OwnedDetailProvider(alias,api,memory)
            val photo=PhotoSource(raw,"source","image/heif",1,1,raw.length(),linearFile=linear)
            try{provider.open(photo,dev.filmroom.model.EditorState());fail()}
            catch(e:DetailUnavailable){assertEquals("UNAVAILABLE",e.code)}
            assertTrue("Trusted alias must pass source guard and reach native plan",reachedPlan)
            assertEquals(1,File(real,"cache/detail-preview/sources").listFiles().orEmpty().size)
            photo.clearLinearPreview();photo.detailGridCache.awaitRetirements()
            assertTrue(File(real,"cache/detail-preview/sources").listFiles().orEmpty().isEmpty())
            assertTrue(raw.exists());assertTrue(linear.exists())
        }finally{outer.deleteRecursively()}
    }
    @Test fun absentPlanAbiFallsBackAndDeletesPreparedSource()=runBlocking {
        val root=Files.createTempDirectory("detail-abi-fail").toFile()
        try {
            File(root,"files/engine-data").mkdirs()
            val raw=File(root,"source").apply{writeText("source")};val linear=File(root,"linear").apply{writeBytes(ByteArray(12))}
            val api=object:Fake(key){override fun nativePlan(requestJson:String):String=throw UnsatisfiedLinkError("missing owned ABI")}
            val provider=OwnedDetailProvider(root,api,memory)
            val photo=PhotoSource(raw,"test","image/heic",1,1,6,linearFile=linear)
            try{provider.open(photo,dev.filmroom.model.EditorState());fail()}
            catch(e:DetailUnavailable){assertEquals("UNAVAILABLE",e.code)}
            assertEquals(1,File(root,"cache/detail-preview/sources").listFiles().orEmpty().size)
            photo.clearLinearPreview();photo.detailGridCache.awaitRetirements()
            assertTrue(File(root,"cache/detail-preview/sources").listFiles().orEmpty().isEmpty())
            assertTrue(raw.exists());assertTrue(linear.exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun absentTileAbiAbandonsTokenWithoutUncaughtCleanupFailure()=runBlocking {
        val api=object:Fake(key){override fun nativeTile(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer):String=throw UnsatisfiedLinkError("missing tile ABI")}
        val c=AtomicInteger();val s=session(api,c);s.build("{}")
        try{s.tile(0,0);fail()}catch(e:DetailUnavailable){assertEquals("UNAVAILABLE",e.code)}
        assertEquals(1,api.released.get());s.close();withTimeout(2000){while(c.get()!=2)delay(5)}
    }
    @Test fun cleanupIoFailureStillReleasesReservationWithoutEscaping()=runBlocking {
        val f=Fake(key);val released=AtomicInteger()
        val s=OwnedDetailSession(f,1,key,true,retained{released.incrementAndGet()},DetailSource(300,260,"source","mapping","sources/test"){throw java.io.IOException("injected cleanup failure")},memory)
        s.close();withTimeout(2000){while(released.get()!=1)delay(5)}
        assertTrue(s.isClosed)
    }
    @Test fun discardDefersOriginalDeletionUntilSourceLeaseExits() {
        val root=Files.createTempDirectory("detail-lease").toFile()
        try{val file=File(root,"source").apply{writeText("original")};val photo=PhotoSource(file,"test","image/jpeg",1,1,8)
            val lease=photo.retainDetailSource();photo.discardWhenUnused();assertTrue(file.exists());lease.close();assertFalse(file.exists())
        }finally{root.deleteRecursively()}
    }
    @Test fun closeAfterJniBeforeConversionKeepsReservationUntilConversionExits()=runBlocking {
        val queued=CountDownLatch(1);var conversion:Runnable?=null
        val dispatcher=object:CoroutineDispatcher(){
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable){conversion=block;queued.countDown()}
        }
        val clean=AtomicInteger();val f=Fake(key)
        val s=OwnedDetailSession(f,1,key,true,retained{clean.incrementAndGet()},DetailSource(300,260,"source","mapping","sources/test"){clean.incrementAndGet()},memory,dispatcher)
        s.build("{}")
        val result=async(Dispatchers.IO){runCatching{s.tile(0,0)}}
        assertTrue(withContext(Dispatchers.IO){queued.await(5,TimeUnit.SECONDS)})
        s.close();val retirement=async(Dispatchers.IO){s.awaitRetired()}
        assertTrue(withContext(Dispatchers.IO){f.destroyed.await(5,TimeUnit.SECONDS)});delay(30)
        assertFalse(retirement.isCompleted)
        assertEquals("JNI returned but direct buffer/conversion still owns reservation",0,clean.get())
        withContext(Dispatchers.IO){conversion!!.run()}
        assertTrue(result.await().isFailure);withTimeout(2000){retirement.await()};assertEquals(2,clean.get())
    }

    @Test fun boundedRowConversionMatchesOriginalTransferForEveryChannelAndGlobalPixel()=runBlocking {
        val f=object:Fake(key){override fun channel(x:Int,y:Int,ch:Int):Float=when((x+y+ch)%17){0->-.2f;1->2f;else->((x+y*3+ch*7)%1024)/1023f}}
        val clean=AtomicInteger();val s=OwnedDetailSession(f,1,key,true,retained{clean.incrementAndGet()},DetailSource(300,260,"source","mapping","sources/test"){clean.incrementAndGet()},memory,regionOutput=true)
        s.build("{}")
        try {for(tile in listOf(s.tile(0,0),s.region(DetailRect(7,9,271,243)){false})){
            for(y in 0 until tile.sample.height)for(x in 0 until tile.sample.width){
                val gx=x+tile.sample.x;val gy=y+tile.sample.y
                val expected=0xff000000.toInt() or (LinearRgb.toSrgb8(f.channel(gx,gy,0)) shl 16) or (LinearRgb.toSrgb8(f.channel(gx,gy,1)) shl 8) or LinearRgb.toSrgb8(f.channel(gx,gy,2))
                assertEquals("row/chunk transfer at $gx,$gy",expected,tile.bitmap.getPixel(x,y))
            };tile.bitmap.recycle()
        }}finally{s.close()}
        withTimeout(2000){while(clean.get()!=2)delay(5)}
    }

    @Test fun rgba8UsesNegotiatedAbiAndReleasesScratchAfterCopy()=runBlocking {
        val f=object:Fake(key){
            override fun nativeTile(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer):String=error("Legacy conversion should not run")
            override fun nativeTileRgba8(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer):String {
                val x=cellX*256;val y=cellY*256;val w=minOf(256,key.width-x);val h=minOf(256,key.height-y)
                val sx=maxOf(0,x-1);val sy=maxOf(0,y-1);val sw=minOf(key.width,x+w+1)-sx;val sh=minOf(key.height,y+h+1)-sy
                repeat(sw*sh){output.put(188.toByte());output.put(64.toByte());output.put(32.toByte());output.put(255.toByte())}
                fun rect(x:Int,y:Int,w:Int,h:Int)=JSONObject().put("x",x).put("y",y).put("w",w).put("h",h)
                return result("tile").put("outputValid",true).put("outputFormat","srgb-rgba8").put("bytesWritten",sw*sh*4).put("coreRect",rect(x,y,w,h)).put("sampleRect",rect(sx,sy,sw,sh)).toString()
            }
        }
        val clean=AtomicInteger();val pool=PreviewBufferPool(1024*1024)
        val session=OwnedDetailSession(f,1,key,true,retained{clean.incrementAndGet()},DetailSource(300,260,"source","mapping","sources/test"){clean.incrementAndGet()},memory,rgba8Output=true,outputPool=pool)
        session.build("{}")
        try {repeat(20){val tile=session.tile(1,1);assertEquals(DetailRect(255,255,45,5),tile.sample);assertEquals(0L,pool.usage().borrowedBytes);assertEquals(900L,pool.usage().allocatedBytes);tile.bitmap.recycle()}}
        finally{session.close();session.awaitRetired()}
        assertEquals(0L,pool.usage().allocatedBytes)
    }
    @Test fun cancellationCannotReturnScratchWhileNativeStillBorrowsIt()=runBlocking {
        val f=Fake(key).apply{block=true};val clean=AtomicInteger();val pool=PreviewBufferPool(1024*1024)
        val session=OwnedDetailSession(f,1,key,true,retained{clean.incrementAndGet()},DetailSource(300,260,"source","mapping","sources/test"){clean.incrementAndGet()},memory,outputPool=pool)
        session.build("{}")
        val worker=launch(Dispatchers.Default){session.tile(0,0)}
        assertTrue(f.entered.await(5,TimeUnit.SECONDS));val bytes=pool.usage().borrowedBytes;assertTrue(bytes>0)
        worker.cancel();session.close();delay(30)
        assertEquals(bytes,pool.usage().borrowedBytes);assertEquals(0,pool.usage().idleCount)
        f.exit.countDown();worker.join();session.awaitRetired()
        assertEquals(0L,pool.usage().borrowedBytes);assertEquals(0L,pool.usage().allocatedBytes)
    }
    @Test fun advertisedRgbaAbiMissingSymbolFailsExplicitlyWithoutLegacyRetry()=runBlocking {
        val f=object:Fake(key){override fun nativeTileRgba8(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer):String=throw UnsatisfiedLinkError("mismatched RGBA8 library")}
        val clean=AtomicInteger();val pool=PreviewBufferPool(1024*1024)
        val session=OwnedDetailSession(f,1,key,true,retained{clean.incrementAndGet()},DetailSource(300,260,"source","mapping","sources/test"){clean.incrementAndGet()},memory,rgba8Output=true,outputPool=pool)
        session.build("{}")
        try{session.tile(0,0);fail()}catch(e:DetailUnavailable){assertEquals("UNAVAILABLE",e.code)}
        assertEquals(1,f.released.get());assertEquals(0L,pool.usage().borrowedBytes)
        session.close();session.awaitRetired()
    }

    @Test fun rgba8CapabilityMustExplicitlyMatchSupportedAbi(){
        assertTrue(DetailNativeContract.rgba8Available(JSONObject().put("outputRgba8",true).put("outputRgba8Abi",1)))
        for(capabilities in listOf(JSONObject(),JSONObject().put("outputRgba8",true),JSONObject().put("outputRgba8",true).put("outputRgba8Abi",2),JSONObject().put("outputRgba8","true").put("outputRgba8Abi",1),JSONObject().put("outputRgba8",true).put("outputRgba8Abi","1")))assertFalse(DetailNativeContract.rgba8Available(capabilities))
    }

    @Test fun acceptsActualGpuAndRejectsPostBuildCpuIdentitySwitch()=runBlocking {
        val f=object:Fake(key) {
            var cpu=false
            override fun result(op:String):JSONObject {
                val response=super.result(op)
                if(!cpu) {
                    response.getJSONObject("identity").put("algorithm",DetailBackendContract.GPU_ALGORITHM)
                    response.put("quality","ApproximateSameWorkingGrid")
                    response.getJSONObject("backend").put("actualBackend","wgpu").put("cpuFallback",false)
                        .put("reason","approximate_resident_detail").put("acceleratedStage","full-resident-detail-chain").put("gpuBatches",7)
                }
                return response
            }
        }
        val c=AtomicInteger();val s=session(f,c)
        try {
            s.build("{}")
            val tile=s.tile(0,0)
            assertFalse(tile.cpuFallback);assertEquals("wgpu",tile.actualBackend)
            assertEquals("ApproximateSameWorkingGrid",tile.quality);assertEquals("approximate_resident_detail",tile.backendReason)
            tile.bitmap.recycle()
            f.cpu=true
            try{s.tile(0,0);fail("CPU pixels cannot enter a published GPU identity")}catch(e:DetailUnavailable){assertEquals("INVALID_RESULT",e.code)}
        } finally {s.close();s.awaitRetired()}
        assertEquals(2,c.get())
    }

    @Test fun providerPassesCpuAdmissionPolicyThroughToNativeBuild()=runBlocking {
        val root=Files.createTempDirectory("detail-cpu-admission").toFile().canonicalFile
        try {
            File(root,"files/engine-data").mkdirs()
            val original=File(root,"source.raw").apply{writeText("original")}
            val linear=File(root,"linear.f32le").apply{writeBytes(ByteArray(12))}
            val policies=mutableListOf<Boolean>();var built:JSONObject?=null
            val api=object:Fake(key) {
                override fun nativePlan(requestJson:String):String {
                    val r=JSONObject(requestJson);val gpu=r.optBoolean("gpuAttempt",true);policies+=gpu
                    return ok("plan").put("plan",JSONObject().put("maxDimension",1536)
                        .put("regionOutput",r.optBoolean("regionOutput")).put("gpuAttempt",gpu)
                        .put("peakReservedBytes",(if(gpu)250L else 100L)*1024*1024).put("diskReservedBytes",1)).toString()
                }
                override fun nativeBuild(sessionId:Long,operationId:Long,sourceRelative:String,requestJson:String):String {
                    built=JSONObject(requestJson)
                    assertFalse(built!!.getBoolean("gpuAttempt"));assertTrue(built!!.getJSONObject("parameters").getBoolean("useGpu"))
                    return result("build").toString()
                }
                override fun identity():JSONObject {
                    val r=checkNotNull(built)
                    return JSONObject().put("sourceRevision",r.getString("sourceRevision")).put("mappingRevision",r.getString("mappingRevision"))
                        .put("dataRevision",r.getString("dataRevision")).put("width",r.getInt("width")).put("height",r.getInt("height"))
                        .put("parameters",r.getJSONObject("parameters").toString()).put("algorithm",DetailBackendContract.CPU_ALGORITHM)
                }
            }
            // Shared ledger capacity is 200 MiB. GPU's 250 MiB plan is refused;
            // CPU's 100 MiB + interop/safety fits and must govern nativeBuild.
            val memory={NativeMemorySnapshot(464L*1024*1024,0,false)}
            val provider=OwnedDetailProvider(root,api,memory)
            val session=provider.open(PhotoSource(original,"source","image/x-adobe-dng",1,1,original.length(),linearFile=linear),
                dev.filmroom.model.EditorState(useGpu=true,autoExposure=false),preferRegion=true)
            try {
                assertEquals(listOf(true,true,false),policies);assertNotNull(built)
                assertTrue(session.regionOutput);assertTrue(JSONObject(session.key.parameters).getBoolean("useGpu"))
            } finally {session.close();session.awaitRetired()}
            assertTrue(original.exists());assertTrue(linear.exists())
        } finally {root.deleteRecursively()}
    }

    @Test fun outputDiagnosticsFollowValidatedBackendAndNativeReservationBoundaries()=runBlocking {
        val f=Fake(key);val c=AtomicInteger();val s=session(f,c);val records=mutableListOf<DetailTimingSnapshot>()
        try {
            s.build("{}")
            val timing=DetailTiming(DetailAttemptKind.TILE,19,record=records::add)
            val tile=withContext(timing){s.tile(0,0)}
            assertSame(timing,tile.diagnosticTiming)
            tile.diagnosticTiming!!.mark(DetailTimingPoint.ADOPTED);tile.diagnosticTiming!!.drawn()
            val record=records.single()
            assertEquals(19L,record.context);assertEquals(DetailActualBackend.CPU,record.backend);assertEquals(true,record.cpuFallback)
            for(point in listOf(DetailTimingPoint.RESERVATION_REQUESTED,DetailTimingPoint.RESERVATION_ACQUIRED,DetailTimingPoint.JNI_ENTER,DetailTimingPoint.JNI_EXIT,DetailTimingPoint.CONVERSION_START,DetailTimingPoint.CONVERSION_END,DetailTimingPoint.RETURNED,DetailTimingPoint.ADOPTED,DetailTimingPoint.FIRST_DRAW))assertTrue("Missing $point",record.offsets.containsKey(point))
            assertTrue(record.offsets[DetailTimingPoint.JNI_EXIT]!!>=record.offsets[DetailTimingPoint.JNI_ENTER]!!)
            tile.bitmap.recycle()
        }finally{s.close();s.awaitRetired()}
    }
    @Test fun invalidNativeIdentityNeverPublishesDiagnosticBackend()=runBlocking {
        val f=object:Fake(key){var invalid=false;override fun result(op:String)=super.result(op).also{if(invalid)it.getJSONObject("backend").put("actualBackend","secret_path")}}
        val c=AtomicInteger();val s=session(f,c);val records=mutableListOf<DetailTimingSnapshot>()
        try {
            s.build("{}")
            f.invalid=true;val timing=DetailTiming(DetailAttemptKind.TILE,record=records::add)
            try{withContext(timing){s.tile(0,0)};fail()}catch(_:DetailUnavailable){}
            assertEquals(DetailTimingTerminal.FAILED,records.single().terminal);assertNull(records.single().backend)
            assertEquals(DetailDiagnosticFailure.INVALID_RESULT,records.single().failure);assertFalse(records.single().fields().toString().contains("secret_path"))
        }finally{s.close();s.awaitRetired()}
    }
    @Test fun detailSourceDiagnosticsDistinguishExistingLinearAndDoNotExposeHashes()=runBlocking {
        val root=Files.createTempDirectory("detail-timing-source").toFile();val records=mutableListOf<DetailTimingSnapshot>()
        try{
            val raw=File(root,"private-name.raw").apply{writeText("secret")};val linear=File(root,"private-linear").apply{writeBytes(ByteArray(12))}
            val timing=DetailTiming(DetailAttemptKind.OPEN,record=records::add)
            val photo=PhotoSource(raw,"secret-title","image/x-adobe-dng",1,1,raw.length(),linearFile=linear)
            val prepared=DetailSourceFactory(root,memory).prepare(photo,1536,timing)
            try{timing.finish(DetailTimingTerminal.OPEN_READY);val record=records.single();assertEquals(DetailSourceRoute.EXISTING_LINEAR,record.sourceRoute)
                assertTrue(record.offsets.containsKey(DetailTimingPoint.SOURCE_HASH_END));assertTrue(record.offsets.containsKey(DetailTimingPoint.RESIZE_END));assertFalse(record.offsets.containsKey(DetailTimingPoint.DECODE_START))
                val text=record.fields().toString();assertFalse(text.contains("private"));assertFalse(text.contains("secret"));assertFalse(text.contains(prepared.sourceRevision));assertFalse(text.contains(prepared.mappingRevision))
            }finally{prepared.close();photo.clearLinearPreview();photo.detailGridCache.awaitRetirements()}
        }finally{root.deleteRecursively()}
    }

}
