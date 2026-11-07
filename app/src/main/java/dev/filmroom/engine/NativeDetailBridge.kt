package dev.filmroom.engine

import java.nio.ByteBuffer

/** Owned detail ABI. Injectable interface keeps ownership tests independent of JNI. */
interface DetailNativeApi {
    fun nativeCapabilities():String
    fun nativePlan(requestJson:String):String
    fun nativeCreate(canonicalPrivateAppRoot:String):String
    fun nativeBeginOperation(sessionId:Long):String
    fun nativeBuild(sessionId:Long,operationId:Long,sourceRelative:String,requestJson:String):String
    fun nativeTile(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer):String
    fun nativeRegion(sessionId:Long,operationId:Long,x:Int,y:Int,width:Int,height:Int,output:ByteBuffer):String = throw UnsupportedOperationException("Region ABI unavailable")
    fun nativeTileRgba8(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer):String = throw UnsupportedOperationException("RGBA8 ABI unavailable")
    fun nativeRegionRgba8(sessionId:Long,operationId:Long,x:Int,y:Int,width:Int,height:Int,output:ByteBuffer):String = throw UnsupportedOperationException("RGBA8 ABI unavailable")
    fun nativeCancel(sessionId:Long,operationId:Long):String
    fun nativeReleaseOperation(sessionId:Long,operationId:Long):String
    fun nativeDestroy(sessionId:Long):String
}
object NativeDetailBridge:DetailNativeApi {
    init { check(NativeFilmBridge.loadError==null) }
    external override fun nativeCapabilities():String
    external override fun nativePlan(requestJson:String):String
    external override fun nativeCreate(canonicalPrivateAppRoot:String):String
    external override fun nativeBeginOperation(sessionId:Long):String
    external override fun nativeBuild(sessionId:Long,operationId:Long,sourceRelative:String,requestJson:String):String
    external override fun nativeTile(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer):String
    external override fun nativeRegion(sessionId:Long,operationId:Long,x:Int,y:Int,width:Int,height:Int,output:ByteBuffer):String
    external override fun nativeTileRgba8(sessionId:Long,operationId:Long,cellX:Int,cellY:Int,output:ByteBuffer):String
    external override fun nativeRegionRgba8(sessionId:Long,operationId:Long,x:Int,y:Int,width:Int,height:Int,output:ByteBuffer):String
    external override fun nativeCancel(sessionId:Long,operationId:Long):String
    external override fun nativeReleaseOperation(sessionId:Long,operationId:Long):String
    external override fun nativeDestroy(sessionId:Long):String
}

/** Negotiate before beginning an operation: never discover the output shape by a failed JNI call. */
internal object DetailNativeContract {
    fun rgba8Available(capabilities:org.json.JSONObject)=
        capabilities.opt("outputRgba8")==true && capabilities.opt("outputRgba8Abi")==1
}


/** Fail closed: upload format and requested GPU are not execution evidence. */
internal object DetailBackendContract {
    const val CPU_ALGORITHM="owned-detail-v1-f64-fixed256-gutter1"
    const val GPU_ALGORITHM="owned-detail-v2-resident-f32-psf6-cap64-fir256-grain-rms-v1"
    fun validate(response:org.json.JSONObject,requestedGpu:Boolean) {
        val b=response.getJSONObject("backend")
        val algorithm=response.getJSONObject("identity").getString("algorithm")
        val quality=response.getString("quality")
        val count=b.opt("gpuBatches")
        val batches=if(count is Int || count is Long)(count as Number).toLong() else -1L
        val reason=b.opt("reason")
        val valid=b.opt("requestedGpu")==requestedGpu && reason is String && reason.matches(Regex("[a-z0-9_]{1,96}")) && when(b.optString("actualBackend")) {
            "cpu" -> algorithm==CPU_ALGORITHM && quality=="ExactSameWorkingGrid" &&
                b.opt("cpuFallback")==requestedGpu && b.optString("acceleratedStage")=="none" && batches==0L
            "wgpu" -> requestedGpu && algorithm==GPU_ALGORITHM && quality=="ApproximateSameWorkingGrid" &&
                b.opt("cpuFallback")==false && b.optString("acceleratedStage")=="full-resident-detail-chain" && batches>0L
            else -> false
        }
        if(!valid)throw DetailUnavailable("INVALID_RESULT")
    }
}
