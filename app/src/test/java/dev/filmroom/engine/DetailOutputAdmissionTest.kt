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
}
