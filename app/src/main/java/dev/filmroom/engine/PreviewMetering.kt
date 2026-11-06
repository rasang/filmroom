package dev.filmroom.engine

import dev.filmroom.io.DetailSourceFactory
import dev.filmroom.io.LinearSpool
import dev.filmroom.io.PhotoSource
import dev.filmroom.io.LinearPreview
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.decodeFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Immutable source-scoped result, deliberately independent of viewport/render resolution.
 * The grid always covers the oriented full photo. RAW uses its unclipped linear spool.
 * The core itself meters on at most 256 pixels per edge; pinning this first sampling grid
 * avoids fit/detail dependent sample phases. This is NOT full-resolution export metering.
 */
internal data class PreviewMeteringResult(
    val sourceRevision:String,val mappingRevision:String,val dataRevision:String,
    val owner:String,val method:String,val width:Int,val height:Int,val ev:Double
) {
    init { require(ev.isFinite() && Math.pow(2.0,ev).isFinite()) }
    fun json()=JSONObject().put("algorithm",ALGORITHM).put("sourceRevision",sourceRevision)
        .put("mappingRevision",mappingRevision).put("dataRevision",dataRevision).put("owner",owner)
        .put("method",method).put("width",width).put("height",height).put("ev",ev)
    companion object { const val ALGORITHM="full-photo-canonical256-core-ae-v1" }
}

/** Only the native boundary is injectable. Source/decoder/lease/admission stay real in host tests. */
internal interface PreviewMeteringNative {
    fun capabilities():String
    fun measure(input:ByteBuffer,width:Int,height:Int,method:String):String
}
private object JniPreviewMeteringNative:PreviewMeteringNative {
    override fun capabilities()=NativeFilmBridge.nativeCapabilities()
    override fun measure(input:ByteBuffer,width:Int,height:Int,method:String)=NativeFilmBridge.nativeMeasurePreview(input,width,height,method)
}

internal object PreviewMetering {
    private val methods=setOf("average","median","center_weighted","partial","matrix","multi_zone","highlight_weighted")
    suspend fun resolve(photo:PhotoSource,root:File,method:String,memory:()->NativeMemorySnapshot):PreviewMeteringResult=
        resolveWithNative(photo,root,method,memory,JniPreviewMeteringNative)
    internal suspend fun resolveWithNative(photo:PhotoSource,root:File,method:String,memory:()->NativeMemorySnapshot,native:PreviewMeteringNative):PreviewMeteringResult=withContext(Dispatchers.IO) {
        require(method in methods)
        photo.retainDetailSource().use {
            val job=currentCoroutineContext()
            val factory=DetailSourceFactory(root,memory)
            val input=factory.checkedSourceFile(photo.linearFile ?: photo.localFile)
            // No cold full-file hash: identity belongs only to this immutable PhotoSource.
            // Replacement/size/time changes invalidate; never persist/reuse across sessions.
            fun stamp()=photo.previewSourceRevision()
            val before=stamp()
            val manifest=File(root.canonicalFile,"files/engine-data/.prepared-v2")
            // No stock/LUT affects input-space metering, but asset replacement still invalidates.
            val data="linear-srgb-matrix-v1:"+DetailSourceFactory.sha256(manifest,job)
            photo.previewMetering.get("$before:$data:${PreviewMeteringResult.ALGORITHM}",method) { owner ->
                NativeWorkReservations.run(128L*1024*1024,memory) {
                    job.ensureActive()
                    val caps=JSONObject(try{native.capabilities()}catch(_:LinkageError){throw EngineException(EngineFailure.UNAVAILABLE)})
                    if(caps.optInt("sharedPreviewMeteringAbi")!=1 || caps.optString("sharedPreviewMeteringAlgorithm")!=PreviewMeteringResult.ALGORITHM)throw EngineException(EngineFailure.UNAVAILABLE)
                    val sourceRevision=before
                    val grid=if(photo.linearFile!=null) {
                        LinearSpool.linearPreview(input,photo.originalWidth,photo.originalHeight,256,job)
                    }else {
                        val bitmap=decodeFile(input,256,job,onDimensions={w,h->check(w==photo.originalWidth && h==photo.originalHeight){"Source orientation changed"}})
                        try {
                            val buffer=ByteBuffer.allocateDirect(bitmap.width*bitmap.height*12).order(ByteOrder.nativeOrder())
                            val row=IntArray(bitmap.width)
                            repeat(bitmap.height){y->job.ensureActive();bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
                                row.forEach { c->buffer.putFloat(LinearRgb.srgb8ToLinear[(c ushr 16)and 255]);buffer.putFloat(LinearRgb.srgb8ToLinear[(c ushr 8)and 255]);buffer.putFloat(LinearRgb.srgb8ToLinear[c and 255]) }
                            }
                            buffer.rewind();LinearPreview(bitmap.width,bitmap.height,buffer)
                        }finally{bitmap.recycle()}
                    }
                    val result=JSONObject(try{native.measure(grid.buffer(),grid.width,grid.height,method)}catch(_:LinkageError){throw EngineException(EngineFailure.UNAVAILABLE)})
                    job.ensureActive()
                    if(!result.optBoolean("ok"))throw EngineException(EngineFailure.PARAMETERS)
                    check(result.getString("algorithm")==PreviewMeteringResult.ALGORITHM && result.getString("method")==method)
                    check(before==stamp()) { "Source changed during metering" }
                    val mapping="oriented-original-v1:${photo.mimeType}:${photo.originalWidth}x${photo.originalHeight}:linear=${photo.linearFile!=null}:snapshot=$sourceRevision:meter-grid:"+
                        (if(photo.linearFile!=null)"linear-bilinear-center-v1" else "image-decoder-srgb-white-v1")+":${grid.width}x${grid.height}"
                    PreviewMeteringResult(sourceRevision,mapping,data,owner,method,grid.width,grid.height,result.getDouble("ev"))
                }
            }
        }
    }
}
