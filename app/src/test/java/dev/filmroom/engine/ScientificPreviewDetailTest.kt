package dev.filmroom.engine

import dev.filmroom.io.DetailSource
import dev.filmroom.roi.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class ScientificPreviewDetailTest {
    private val memory={NativeMemorySnapshot(8L*1024*1024*1024,0,false)}
    private val key=DetailKey("source","mapping","data","{\"seed\":42,\"gpuScienceExperimental\":true,\"useGpu\":false}",12,9,true)
    private class Reservation:RetainedNativeWork {
        val active=AtomicBoolean();val closed=AtomicBoolean();val quarantined=AtomicBoolean()
        override fun quarantine(){quarantined.set(true)}
        override suspend fun <T> run(memory:()->NativeMemorySnapshot,work:suspend ()->T):T {
            check(active.compareAndSet(false,true));try{return work()}finally{active.set(false)}
        }
        override fun close(){check(!active.get());closed.set(true)}
    }
    private class Api(val key:DetailKey):DetailNativeApi {
        var backend="gpu_f32";var quality="ExperimentalSameWorkingGrid";var failBuild=false
        val builds=AtomicInteger();val destroys=AtomicInteger();val cancels=AtomicInteger()
        fun ok(op:String)=JSONObject().put("schemaVersion",1).put("operation",op).put("ok",true).put("code","OK")
        fun result(op:String)=ok(op).put("quality",quality)
            .put("identity",JSONObject().put("sourceRevision",key.source).put("mappingRevision",key.mapping).put("dataRevision",key.data).put("width",key.width).put("height",key.height).put("parameters",key.parameters).put("algorithm","owned-detail-v3-guarded64-resident-fullgrid-gutter1"))
            .put("backend",JSONObject().put("requestedGpu",true).put("actualBackend",backend).put("cpuFallback",backend!="gpu_f32"))
        override fun nativeCapabilities()=ok("capabilities").toString()
        override fun nativePlan(requestJson:String)=error("unused")
        override fun nativeCreate(canonicalPrivateAppRoot:String)=error("unused")
        override fun nativeBeginOperation(sessionId:Long)=ok("beginOperation").put("operationId","1").toString()
        override fun nativeBuild(sessionId:Long,operationId:Long,sourceRelative:String,requestJson:String)=error("Scientific path must use its delegate ABI")
        override fun nativeBuildWithGpuDelegate(sessionId:Long,operationId:Long,sourceRelative:String,requestJson:String,delegate:Any):String {
            builds.incrementAndGet();if(failBuild)throw IllegalStateException("injected")
            return result("build").toString()
        }
        override fun nativeTile(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer):String {
            repeat(key.width*key.height*3){output.putFloat(.5f)}
            val rect=JSONObject().put("x",0).put("y",0).put("w",key.width).put("h",key.height)
            return result("tile").put("outputValid",true).put("bytesWritten",output.capacity()).put("coreRect",rect).put("sampleRect",rect).toString()
        }
        override fun nativeCancel(sessionId:Long,operationId:Long):String{cancels.incrementAndGet();return ok("cancel").toString()}
        override fun nativeReleaseOperation(sessionId:Long,operationId:Long)=ok("releaseOperation").toString()
        override fun nativeCleanupAfterGpuDrain(sessionId:Long)="{\"ok\":true,\"cleaned\":false}"
        override fun nativeDestroy(sessionId:Long):String{destroys.incrementAndGet();return ok("destroy").toString()}
    }
    private fun session(api:Api,reservation:Reservation,sourceClosed:AtomicBoolean,drain:suspend ()->Unit)=OwnedDetailSession(
        api,1,key,true,reservation,DetailSource(key.width,key.height,key.source,key.mapping,"sources/test"){sourceClosed.set(true)},memory,
        gpuBuild=DetailGpuBuild(Any(),{drain();true},{release->release()}))
    @Test fun selectionIsExplicitAndPreservesPreviewGridEffectsAndSeed(){
        val all=OptimizationSnapshot(gpuDiffusionExperimental=true,gpuScannerExperimental=true,gpuOpticalExperimental=true,gpuExposureExperimental=true,gpuDirExperimental=true,gpuScienceExperimental=true)
        for(mode in listOf("preview","export")){
            val choice=ScientificGpuSelection.from(all,true,mode);assertTrue(choice.enabled)
            val params=JSONObject("""{"seed":42,"width":768,"height":512,"grain":0.8,"glare":true,"useGpu":true}""")
            choice.applyTo(params)
            assertEquals(42,params.getInt("seed"));assertEquals(768,params.getInt("width"));assertEquals(512,params.getInt("height"));assertEquals(.8,params.getDouble("grain"),0.0);assertTrue(params.getBoolean("glare"))
            assertFalse(params.getBoolean("useGpu"));assertFalse(params.getBoolean("previewBrightnessReuse"))
            for(field in listOf("Diffusion","Scanner","Optical","Exposure","Dir","Science"))assertTrue(params.getBoolean("gpu${field}Experimental"))
            assertFalse(ScientificGpuSelection.from(all,false,mode).enabled)
            assertFalse(ScientificGpuSelection.from(all.copy(cpuComparison=true),true,mode).enabled)
            assertFalse(ScientificGpuSelection.from(OptimizationSnapshot(),true,mode).enabled)
        }
        assertFalse(ScientificGpuSelection.from(all,true,"unknown").enabled)
    }
    @Test fun cleanupRequiresClosedSuccessEnvelope(){
        checkPreviewGpuCleanup("{\"ok\":true,\"cleaned\":true}");checkPreviewGpuCleanup("{\"ok\":true,\"cleaned\":false}")
        for(raw in listOf("{}","{\"ok\":true}","{\"ok\":true,\"cleaned\":\"true\"}","{\"ok\":false,\"code\":\"PREVIEW_STILL_RUNNING\"}","{\"ok\":true,\"cleaned\":true,\"privatePath\":\"secret\"}")){
            try{checkPreviewGpuCleanup(raw);fail(raw)}catch(e:EngineException){assertEquals(EngineFailure.INCOMPLETE,e.failure)}
        }
    }
    @Test fun detailUsesDistinctQualityAndReportsActualFallback()=runBlocking {
        for(backend in listOf("cpu","gpu_f32","mixed_gpu_f32_cpu_f64")){
            val api=Api(key).also{it.backend=backend};val reservation=Reservation();val sourceClosed=AtomicBoolean()
            val session=session(api,reservation,sourceClosed){assertTrue(reservation.active.get())}
            session.build("{}");assertEquals(1,api.builds.get());assertEquals(backend,session.actualBackend.id)
            val tile=session.tile(0,0);assertEquals(backend!="gpu_f32",tile.cpuFallback);assertTrue(tile.key.scienceExperimental);tile.bitmap.recycle()
            session.close();withTimeout(2000){session.awaitRetired()};assertTrue(reservation.closed.get());assertTrue(sourceClosed.get())
        }
    }
    @Test fun detailRejectsLegacyQualityAndBackendChanges()=runBlocking {
        val api=Api(key);val reservation=Reservation();val sourceClosed=AtomicBoolean();val session=session(api,reservation,sourceClosed){}
        api.quality="ExactSameWorkingGrid"
        try{session.build("{}");fail()}catch(e:DetailUnavailable){assertEquals("INVALID_RESULT",e.code)}
        api.quality="ExperimentalSameWorkingGrid";session.build("{}");api.backend="cpu"
        try{session.tile(0,0);fail()}catch(e:DetailUnavailable){assertEquals("INVALID_RESULT",e.code)}
        session.close();withTimeout(2000){session.awaitRetired()}
    }
    @Test fun cancellationKeepsSourceNativeOwnerAndReservationUntilDrain()=runBlocking {
        val api=Api(key);val reservation=Reservation();val sourceClosed=AtomicBoolean()
        val draining=CompletableDeferred<Unit>();val death=CompletableDeferred<Unit>()
        val session=session(api,reservation,sourceClosed){assertTrue(reservation.active.get());draining.complete(Unit);death.await();assertTrue(reservation.active.get())}
        val worker=launch(Dispatchers.Default){session.build("{}")}
        withTimeout(2000){draining.await()};worker.cancel();session.close();delay(30)
        assertTrue(reservation.active.get());assertFalse(reservation.closed.get());assertFalse(sourceClosed.get());assertEquals(0,api.destroys.get())
        death.complete(Unit);withTimeout(2000){worker.join();session.awaitRetired()}
        assertEquals(1,api.destroys.get());assertTrue(sourceClosed.get());assertTrue(reservation.closed.get())
    }
    @Test fun nativeExceptionStillDrainsInsideParentReservation()=runBlocking {
        val api=Api(key).also{it.failBuild=true};val reservation=Reservation();val sourceClosed=AtomicBoolean();var drained=false
        val session=session(api,reservation,sourceClosed){assertTrue(reservation.active.get());drained=true}
        try{session.build("{}");fail()}catch(_:IllegalStateException){}
        assertTrue(drained);session.close();withTimeout(2000){session.awaitRetired()}
    }
    @Test fun unknownDeathReturnsWithoutReleasingNativeSourceOrPassiveBytes()=runBlocking {
        val api=Api(key);val reservation=Reservation();val sourceClosed=AtomicBoolean();var proof:(()->Unit)?=null
        val session=OwnedDetailSession(api,1,key,true,reservation,DetailSource(key.width,key.height,key.source,key.mapping,"sources/test"){sourceClosed.set(true)},memory,
            gpuBuild=DetailGpuBuild(Any(),{false},{callback->proof=callback}))
        withTimeout(2000){try{session.build("{}");fail()}catch(e:DetailUnavailable){assertEquals("GPU_DRAIN_PENDING",e.code)}}
        assertTrue(session.isQuarantined);assertTrue(session.isClosed);assertTrue(reservation.quarantined.get())
        assertFalse(reservation.active.get());assertFalse(reservation.closed.get());assertFalse(sourceClosed.get());assertEquals(0,api.destroys.get())
        checkNotNull(proof).invoke();withTimeout(2000){session.awaitRetired()}
        assertEquals(1,api.destroys.get());assertTrue(sourceClosed.get());assertTrue(reservation.closed.get())
    }
    @Test fun transferredPreviewOwnerReleasesQueueBeforeBytesAndCleansBeforeRelease()=runBlocking {
        val limiter=NativeWorkLimiter();val owner=GpuResourceQuarantine();var proof:(()->Unit)?=null
        val cleaned=AtomicBoolean()
        limiter.runQuarantinable(1024,memory,owner.retention){
            owner.retainAfterProof(listOf(ByteBuffer.allocateDirect(8)),{proof=it}) {
                assertEquals(0,limiter.activeCount());assertTrue(limiter.reservedBytes()>0);cleaned.set(true)
            }
            assertEquals(1,limiter.activeCount())
        }
        assertTrue(owner.retained);assertEquals(0,limiter.activeCount());assertTrue(limiter.reservedBytes()>0);assertFalse(cleaned.get())
        checkNotNull(proof).invoke();withTimeout(2000){while(limiter.reservedBytes()!=0L)delay(5)}
        assertTrue(cleaned.get())
    }
    @Test fun schedulerQualityRequiresPerfAndDistinctScientificIdentity(){
        val ordinary=RenderKey("s","p","legacy","d","m",12,9)
        for(id in listOf(SCIENCE_GRID_ALGORITHM,SCIENCE_REGION_ALGORITHM)){
            val scientific=ordinary.copy(algorithmRevision=id)
            assertTrue(TileQuality.ExperimentalSameWorkingGrid.accepts(scientific,true))
            assertFalse(TileQuality.ExperimentalSameWorkingGrid.accepts(scientific,false))
            assertFalse(TileQuality.Exact.accepts(scientific,true))
            assertFalse(TileQuality.ApproximateTestOnly.accepts(scientific,true))
        }
        assertTrue(TileQuality.Exact.accepts(ordinary,false));assertFalse(TileQuality.ExperimentalSameWorkingGrid.accepts(ordinary,true))
    }
}
