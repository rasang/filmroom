package dev.filmroom.engine

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE)
class DetailOutputAdmissionTest {
    private val mib=1024L*1024
    private fun base()=JSONObject().put("width",1536).put("height",1024).put("parameters",JSONObject().put("brightnessEv",.5))
    private fun plan(region:Boolean)=JSONObject().put("peakReservedBytes",if(region)2*mib else mib).put("maxDimension",1536).put("regionOutput",region)
    private fun lease()=object:RetainedNativeWork {
        override suspend fun <T> run(memory:()->NativeMemorySnapshot,work:suspend ()->T):T=work()
        override fun close(){}
    }
    @Test fun regionAdmissionFailureSelectsOriginalCellBudgetExactlyOnce(){
        val requests=mutableListOf<JSONObject>();val charges=mutableListOf<Long>();val base=base()
        val result=admitDetailOutput(base,true,plan={json->val r=JSONObject(json);requests+=r;plan(r.optBoolean("regionOutput"))},retain={bytes->charges+=bytes;if(bytes==14*mib)throw NativeWorkUnavailable() else lease()})
        assertFalse(result.regionOutput);assertEquals(listOf(14*mib,9*mib),charges);assertEquals(2,requests.size)
        assertTrue(requests.first().getBoolean("regionOutput"));assertFalse(requests.last().has("regionOutput"));assertFalse(base.has("regionOutput"))
        for(r in requests){assertEquals(1536,r.getInt("width"));assertEquals(1024,r.getInt("height"));assertEquals(.5,r.getJSONObject("parameters").getDouble("brightnessEv"),0.0)}
    }
    @Test fun onlyExplicitWorkingPlanBudgetChoosesCells(){
        var calls=0
        val result=admitDetailOutput(base(),true,plan={json->calls++;if(JSONObject(json).optBoolean("regionOutput"))throw DetailUnavailable("WORKING_MEMORY_BUDGET") else plan(false)},retain={lease()})
        assertFalse(result.regionOutput);assertEquals(2,calls)
        for(error in listOf(IOException("io"),OutOfMemoryError("oom"),CancellationException("cancel"),DetailUnavailable("STORAGE_BUDGET"),DetailUnavailable("BUDGET_EXCEEDED"),DetailUnavailable("UNSUPPORTED"))){
            calls=0
            try{admitDetailOutput(base(),true,plan={calls++;throw error},retain={kotlin.error("must not reserve")});fail("Expected original failure")}
            catch(t:Throwable){assertSame(error,t)}
            assertEquals(1,calls)
        }
    }
    @Test fun successfulRegionAndOldAbiEachPlanOnlyOnce(){
        for(region in listOf(true,false)){
            var calls=0;var bytes=0L
            val result=admitDetailOutput(base(),region,plan={calls++;if(region)plan(true) else plan(false).apply{remove("regionOutput")}},retain={bytes=it;lease()})
            assertEquals(region,result.regionOutput);assertEquals(1,calls);assertEquals(if(region)14*mib else 9*mib,bytes)
        }
    }
    @Test fun rejectedGpuLedgerReservationsRetryCpuRegionWithoutChangingScience(){
        val limiter=NativeWorkLimiter();val capacity=200*mib
        val snapshot=NativeMemorySnapshot(capacity*2+64*mib,0,false)
        val requests=mutableListOf<JSONObject>();val charges=mutableListOf<Long>()
        val base=base().put("parameters",JSONObject().put("useGpu",true).put("brightnessEv",.5)
            .put("previewMetering",JSONObject().put("owner","same-owner").put("ev",1.25)))
        val science=base.getJSONObject("parameters").toString()
        val result=admitDetailOutput(base,true,plan={json->
            val r=JSONObject(json);requests+=r
            val gpu=r.optBoolean("gpuAttempt",true);val region=r.optBoolean("regionOutput")
            plan(region).put("gpuAttempt",gpu).put("peakReservedBytes",if(gpu)240*mib else 150*mib)
        },retain={bytes->charges+=bytes;limiter.retainForWork(bytes,snapshot)})
        assertTrue(result.regionOutput)
        assertEquals(listOf(true,true,false),requests.map{it.optBoolean("gpuAttempt",true)})
        assertEquals(listOf(true,false,true),requests.map{it.optBoolean("regionOutput")})
        assertEquals(listOf(252*mib,248*mib,162*mib),charges)
        assertEquals(178*mib,limiter.reservedBytes());assertEquals(0,limiter.activeCount())
        for(r in requests)assertEquals(science,r.getJSONObject("parameters").toString())
        assertTrue(JSONObject(result.request).getJSONObject("parameters").getBoolean("useGpu"))
        assertFalse(JSONObject(result.request).getBoolean("gpuAttempt"));assertFalse(base.has("gpuAttempt"))
        result.reservation.close();assertEquals(0L,limiter.reservedBytes())
    }
    @Test fun cpuRegionBudgetFailureStillAllowsOriginalLegacyCpuPlan(){
        val limiter=NativeWorkLimiter();val snapshot=NativeMemorySnapshot(130*mib*2+64*mib,0,false)
        val requests=mutableListOf<JSONObject>()
        val result=admitDetailOutput(base(),true,plan={json->
            val r=JSONObject(json);requests+=r;val gpu=r.optBoolean("gpuAttempt",true);val region=r.optBoolean("regionOutput")
            plan(region).put("gpuAttempt",gpu).put("peakReservedBytes",when{gpu->240*mib;region->150*mib;else->100*mib})
        },retain={limiter.retainForWork(it,snapshot)})
        assertEquals(4,requests.size);assertFalse(result.regionOutput)
        assertFalse(JSONObject(result.request).getBoolean("gpuAttempt"));assertEquals(124*mib,limiter.reservedBytes())
        result.reservation.close();assertEquals(0L,limiter.reservedBytes())
    }
    @Test fun nativeCpuPlanNeedsNoGpuReservationRetry(){
        var calls=0;var charged=0L
        val result=admitDetailOutput(base(),true,plan={calls++;plan(true).put("gpuAttempt",false).put("peakReservedBytes",100*mib)},retain={charged=it;lease()})
        assertEquals(1,calls);assertEquals(112*mib,charged);result.reservation.close()
    }
    @Test fun userCpuSelectionNeverAttemptsGpuPolicy(){
        val requests=mutableListOf<JSONObject>()
        val b=base();b.getJSONObject("parameters").put("useGpu",false)
        val result=admitDetailOutput(b,true,plan={json->val r=JSONObject(json);requests+=r;plan(r.optBoolean("regionOutput")).put("gpuAttempt",false)},retain={if(it==14*mib)throw NativeWorkUnavailable() else lease()})
        assertEquals(2,requests.size);assertTrue(requests.all{!it.getBoolean("gpuAttempt") && !it.getJSONObject("parameters").getBoolean("useGpu")})
        result.reservation.close()
    }
    @Test fun allBudgetRefusalsAreBoundedAndLeaveNoLedgerBytes(){
        val limiter=NativeWorkLimiter();var calls=0
        try {admitDetailOutput(base(),true,plan={json->calls++;plan(JSONObject(json).optBoolean("regionOutput"))},retain={limiter.retainForWork(it,NativeMemorySnapshot(0,0,true))});fail()}
        catch(_:NativeWorkUnavailable){}
        assertEquals(4,calls);assertEquals(0L,limiter.reservedBytes());assertEquals(0,limiter.activeCount())
    }
    @Test fun operationalCpuRequestRejectsGpuPlanAndNeverReserves(){
        val b=base().put("gpuAttempt",false)
        try{admitDetailOutput(b,true,plan={plan(true).put("gpuAttempt",true)},retain={error("must not reserve")});fail()}
        catch(e:DetailUnavailable){assertEquals("INVALID_RESULT",e.code)}
    }
    @Test fun cpuRetryDoesNotSwallowOomIoOrCancellation(){
        for(error in listOf(IOException("io"),OutOfMemoryError("oom"),CancellationException("cancel"),DetailUnavailable("STORAGE_BUDGET"))){
            var calls=0
            try{admitDetailOutput(base(),true,plan={json->calls++;if(JSONObject(json).optBoolean("gpuAttempt",true))throw NativeWorkUnavailable() else throw error},retain={kotlin.error("no successful plan")});fail()}
            catch(t:Throwable){assertSame(error,t)}
            assertEquals(3,calls)
        }
    }

}
