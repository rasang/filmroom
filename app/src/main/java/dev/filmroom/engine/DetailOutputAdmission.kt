package dev.filmroom.engine

import org.json.JSONObject

internal data class DetailOutputAdmission(val request:String,val regionOutput:Boolean,val reservation:RetainedNativeWork)

/** Pre-build admission only. Never retry render failures, OOM, IO or cancellation.
 * A rejected GPU reservation gets an independently prepaid CPU-attempt plan;
 * scientific parameters (including requested useGpu and shared AE) stay intact.
 */
internal fun admitDetailOutput(
    base:JSONObject,preferRegion:Boolean,
    plan:(String)->JSONObject,retain:(Long)->RetainedNativeWork
):DetailOutputAdmission {
    fun attempt(region:Boolean,gpuAttempt:Boolean):DetailOutputAdmission {
        val request=JSONObject(base.toString()).also {
            if(region)it.put("regionOutput",true) else it.remove("regionOutput")
            // Operational policy is deliberately outside parameters/DetailKey.
            if(gpuAttempt)it.remove("gpuAttempt") else it.put("gpuAttempt",false)
        }.toString()
        val p=plan(request);val peak=p.getLong("peakReservedBytes")
        if(p.getInt("maxDimension")!=1536 || peak<=0 || p.optBoolean("regionOutput",false)!=region ||
            (!gpuAttempt && p.optBoolean("gpuAttempt",false)))throw DetailUnavailable("INVALID_RESULT")
        val reservation=retain(Math.addExact(peak,(if(region)12L else 8L)*1024*1024))
        try{return DetailOutputAdmission(request,region,reservation)}catch(t:Throwable){runCatching{reservation.close()};throw t}
    }
    val regions=if(preferRegion)listOf(true,false) else listOf(false)
    val gpuRequested=base.getJSONObject("parameters").optBoolean("useGpu",true)
    val policies=if(gpuRequested && base.optBoolean("gpuAttempt",true))listOf(true,false) else listOf(false)
    var last:Exception?=null
    for(gpuAttempt in policies)for(region in regions) {
        try{return attempt(region,gpuAttempt)}
        catch(e:NativeWorkUnavailable){last=e}
        catch(e:DetailUnavailable){if(e.code=="WORKING_MEMORY_BUDGET")last=e else throw e}
    }
    throw checkNotNull(last)
}
