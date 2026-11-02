package dev.filmroom.engine

import org.json.JSONObject

internal data class DetailOutputAdmission(val request:String,val regionOutput:Boolean,val reservation:RetainedNativeWork)

/** One pre-build policy decision. Never retry render failures, OOM, IO or cancellation as cells. */
internal fun admitDetailOutput(
    base:JSONObject,preferRegion:Boolean,
    plan:(String)->JSONObject,retain:(Long)->RetainedNativeWork
):DetailOutputAdmission {
    fun attempt(region:Boolean):DetailOutputAdmission {
        val request=JSONObject(base.toString()).also { if(region)it.put("regionOutput",true) else it.remove("regionOutput") }.toString()
        val p=plan(request);val peak=p.getLong("peakReservedBytes")
        if(p.getInt("maxDimension")!=1536 || peak<=0 || p.optBoolean("regionOutput",false)!=region)throw DetailUnavailable("INVALID_RESULT")
        val reservation=retain(Math.addExact(peak,(if(region)12L else 8L)*1024*1024))
        try{return DetailOutputAdmission(request,region,reservation)}catch(t:Throwable){runCatching{reservation.close()};throw t}
    }
    if(!preferRegion)return attempt(false)
    return try{attempt(true)}
    catch(e:NativeWorkUnavailable){attempt(false)}
    catch(e:DetailUnavailable){if(e.code=="WORKING_MEMORY_BUDGET")attempt(false) else throw e}
}
