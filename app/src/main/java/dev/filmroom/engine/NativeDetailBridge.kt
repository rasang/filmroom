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
    external override fun nativeCancel(sessionId:Long,operationId:Long):String
    external override fun nativeReleaseOperation(sessionId:Long,operationId:Long):String
    external override fun nativeDestroy(sessionId:Long):String
}
