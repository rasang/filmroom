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
