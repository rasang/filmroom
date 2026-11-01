package dev.filmroom.engine

import android.content.Context
import android.graphics.Bitmap
import dev.filmroom.io.DetailSource
import dev.filmroom.io.DetailSourceFactory
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class DetailUnavailable(val code:String):Exception("Detail unavailable: $code")
data class DetailRect(val x:Int,val y:Int,val width:Int,val height:Int)
data class DetailKey(val source:String,val mapping:String,val data:String,val parameters:String,val width:Int,val height:Int)
/** Caller owns bitmap. Exactness applies only to key.width × key.height, not original-size export. */
data class DetailTile(val key:DetailKey,val core:DetailRect,val sample:DetailRect,val bitmap:Bitmap,val cpuFallback:Boolean)

/** Experimental adapter; constructing it does not enable UI. No export APIs/checkpoints are used. */
class OwnedDetailProvider internal constructor(
    private val suppliedRoot:File,private val suppliedApi:DetailNativeApi?,private val memory:()->NativeMemorySnapshot
) {
    constructor(context:Context):this(context.applicationContext.dataDir.absoluteFile,null,{NativeWorkReservations.snapshot(context.applicationContext)})
    private val api:DetailNativeApi get()=try{suppliedApi ?: NativeDetailBridge}catch(_:LinkageError){throw DetailUnavailable("UNAVAILABLE")}
    private val root:File get()=suppliedRoot.canonicalFile
    private val sources:DetailSourceFactory get()=DetailSourceFactory(suppliedRoot,memory)
    suspend fun cleanupOwnedOrphans()=withContext(Dispatchers.IO) {
        sources.cleanupOrphans()
        if(File(root,"files/engine-data").isDirectory && File(root,"cache/detail-preview/sessions").isDirectory) {
            val temporary=positiveId(status(try{api.nativeCreate(root.path)}catch(_:LinkageError){throw DetailUnavailable("UNAVAILABLE")},"create"),"sessionId")
            try{api.nativeDestroy(temporary)}catch(_:LinkageError){throw DetailUnavailable("UNAVAILABLE")}
        }
    }
    suspend fun open(photo:PhotoSource,parameters:EditorState,maxEdge:Int=1536):OwnedDetailSession {
        var owned:OwnedDetailSession?=null
        try { return withContext(Dispatchers.IO) {
        sources.cleanupOrphans()
        val dataRoot=File(root,"files/engine-data")
        if(!dataRoot.isDirectory)throw DetailUnavailable("DATA_NOT_READY") // ordinary engine owns installing immutable bundled data
        val job=currentCoroutineContext()
        val digest=MessageDigest.getInstance("SHA-256")
        dataRoot.walkTopDown().filter{it.isFile}.sortedBy{it.relativeTo(dataRoot).path}.forEach {
            digest.update(it.relativeTo(dataRoot).path.toByteArray());digest.update(DetailSourceFactory.sha256(it,job).toByteArray())
        }
        val dataRevision=digest.digest().joinToString(""){"%02x".format(it)}
        val source=sources.prepare(photo,maxEdge)
        var session:OwnedDetailSession?=null
        val pressureRequested=java.util.concurrent.atomic.AtomicBoolean(false)
        val pressureOwner=java.util.concurrent.atomic.AtomicReference<OwnedDetailSession?>()
        var retained:AutoCloseable?=null
        var sessionId=0L
        try {
            val params=parameters.json("preview", includePresentation=false)
            val key=DetailKey(source.sourceRevision,source.mappingRevision,dataRevision,params,source.width,source.height)
            val request=JSONObject().put("schemaVersion",1).put("width",key.width).put("height",key.height)
                .put("sourceRevision",key.source).put("mappingRevision",key.mapping).put("dataRevision",key.data)
                .put("parameters",JSONObject(params)).put("memoryBudgetBytes",512L*1024*1024)
                .put("diskBudgetBytes",(root.usableSpace-64L*1024*1024).coerceAtLeast(0))
                .put("stageCacheBytes",16L*1024*1024).put("spectrumCacheBytes",32L*1024*1024).toString()
            val plan=status(api.nativePlan(request),"plan").getJSONObject("plan")
            val peak=plan.getLong("peakReservedBytes")
            if(plan.getInt("maxDimension")!=1536 || peak<=0 || plan.getLong("diskReservedBytes")>root.usableSpace-64L*1024*1024)throw DetailUnavailable("BUDGET_EXCEEDED")
            // Full peak is retained conservatively even while idle; no worker slot is held.
            retained=NativeWorkReservations.retain(Math.addExact(peak,8L*1024*1024),memory){pressureRequested.set(true);pressureOwner.get()?.close()}
            sessionId=positiveId(status(api.nativeCreate(root.path),"create"),"sessionId")
            session=OwnedDetailSession(api,sessionId,key,parameters.useGpu,retained,source,memory)
            pressureOwner.set(session)
            retained=null // ownership transferred, including failures/cancellation during build
            owned=session
            if(pressureRequested.get()){session.close();throw DetailUnavailable("BUDGET_EXCEEDED")}
            session.build(request)
            job.ensureActive()
            session
        } catch(t:Throwable) {
            if(session!=null)session.close() else {
                try{if(sessionId!=0L)runCatching{api.nativeDestroy(sessionId)}}finally{try{retained?.close()}finally{source.close()}}
            }
            throw t
        }
    } } catch(t:Throwable){owned?.close();if(t is LinkageError)throw DetailUnavailable("UNAVAILABLE");throw t}
    }
}

class OwnedDetailSession internal constructor(
    private val api:DetailNativeApi,private val id:Long,val key:DetailKey,
    private val requestedGpu:Boolean,private val reservation:AutoCloseable,
    private val source:DetailSource,private val memory:()->NativeMemorySnapshot
):AutoCloseable {
    private val lock=Any()
    @Volatile private var closed=false
    val isClosed:Boolean get()=closed
    private var retirementListener:(()->Unit)?=null
    /** Listener may run on any thread; UI must marshal publication/removal to Main. */
    fun setRetirementListener(listener:()->Unit){
        val already=synchronized(lock){if(closed)true else {retirementListener=listener;false}}
        if(already)runCatching{listener()}
    }
    private var workers=0
    private var released=false
    private var destroyed=false
    private var identity:String?=null
    private fun releaseIfDone(){if(closed && destroyed && workers==0 && !released){released=true;CoroutineScope(Dispatchers.IO).launch{try{runCatching{source.close()}}finally{runCatching{reservation.close()}}}}}
    override fun close(){
        var listener:(()->Unit)?=null
        val retire=synchronized(lock){if(closed)false else {closed=true;listener=retirementListener;retirementListener=null;true}}
        if(retire){
            CoroutineScope(Dispatchers.IO).launch {try{runCatching{api.nativeDestroy(id)}}finally{synchronized(lock){destroyed=true;releaseIfDone()}}}
            runCatching{listener?.invoke()}
        }
    }
    internal suspend fun build(request:String) {
        val result=operation("build") { op,enter -> enter();api.nativeBuild(id,op,source.relativePath,request) }
        validateIdentity(result)
        identity=canonical(result.getJSONObject("identity"))
    }
    suspend fun tile(cellX:Int,cellY:Int):DetailTile {
        require(cellX>=0 && cellY>=0 && cellX<(key.width+255)/256 && cellY<(key.height+255)/256)
        val core=DetailRect(cellX*256,cellY*256,minOf(256,key.width-cellX*256),minOf(256,key.height-cellY*256))
        val sx=(core.x-1).coerceAtLeast(0);val sy=(core.y-1).coerceAtLeast(0)
        val sample=DetailRect(sx,sy,minOf(key.width,core.x+core.width+1)-sx,minOf(key.height,core.y+core.height+1)-sy)
        lateinit var output:ByteBuffer
        val response=operation("tile") { op,enter ->
            output=ByteBuffer.allocateDirect(sample.width*sample.height*12).order(ByteOrder.nativeOrder())
            enter();api.nativeTile(id,op,cellX,cellY,output)
        }
        validateIdentity(response)
        if(!response.getBoolean("outputValid") || response.getLong("bytesWritten")!=output.capacity().toLong() || rect(response.getJSONObject("coreRect"))!=core || rect(response.getJSONObject("sampleRect"))!=sample || canonical(response.getJSONObject("identity"))!=identity)throw DetailUnavailable("INVALID_RESULT")
        var ownedBitmap:Bitmap?=null
        try { return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val pixels=IntArray(sample.width*sample.height)
            output.rewind()
            for(i in pixels.indices){
                if(i%256==0)currentCoroutineContext().ensureActive()
                val r=output.float;val g=output.float;val b=output.float
                if(!r.isFinite() || !g.isFinite() || !b.isFinite())throw DetailUnavailable("INVALID_RESULT")
                pixels[i]=0xff000000.toInt() or (LinearRgb.toSrgb8(r) shl 16) or (LinearRgb.toSrgb8(g) shl 8) or LinearRgb.toSrgb8(b)
            }
            val bitmap=Bitmap.createBitmap(pixels,sample.width,sample.height,Bitmap.Config.ARGB_8888)
            ownedBitmap=bitmap
            try {currentCoroutineContext().ensureActive();synchronized(lock){if(closed)throw CancellationException("Detail retired")};DetailTile(key,core,sample,bitmap,requestedGpu)}catch(t:Throwable){bitmap.recycle();throw t}
        }
        } catch(t:Throwable){ownedBitmap?.takeUnless{it.isRecycled}?.recycle();throw t}
    }
    private fun validateIdentity(response:JSONObject) {
        val j=response.getJSONObject("identity");val backend=response.getJSONObject("backend")
        if(j.getString("sourceRevision")!=key.source || j.getString("mappingRevision")!=key.mapping || j.getString("dataRevision")!=key.data || j.getInt("width")!=key.width || j.getInt("height")!=key.height || j.getString("algorithm")!="owned-detail-v1-f64-fixed256-gutter1" || response.getString("quality")!="ExactSameWorkingGrid" || backend.getString("actualBackend")!="cpu" || backend.getBoolean("requestedGpu")!=requestedGpu || backend.getBoolean("cpuFallback")!=requestedGpu)throw DetailUnavailable("INVALID_RESULT")
        // Native serializes defaults too; every explicitly requested field must survive unchanged.
        val actual=JSONObject(j.getString("parameters"));val expected=JSONObject(key.parameters)
        expected.keys().forEach { k -> if(!jsonEquivalent(expected.get(k),actual.opt(k)))throw DetailUnavailable("INVALID_RESULT") }
    }
    private suspend fun operation(name:String,work:(Long,()->Unit)->String):JSONObject {
        val caller=currentCoroutineContext();caller.ensureActive()
        val op=synchronized(lock){if(closed)throw CancellationException("Detail retired");if(workers!=0)throw DetailUnavailable("BUSY");positiveId(status(try{api.nativeBeginOperation(id)}catch(_:LinkageError){throw DetailUnavailable("UNAVAILABLE")},"beginOperation"),"operationId").also{workers++}}
        var invoked=false
        val watcher=CoroutineScope(Dispatchers.Default).launch {
            while(isActive){if(!caller.isActive){runCatching{api.nativeCancel(id,op)};break};delay(10)}
        }
        try {
            return withContext(Dispatchers.IO) {
                NativeWorkReservations.run(1L,{synchronized(lock){if(closed)throw CancellationException("Detail retired")};memory()}) {
                    caller.ensureActive()
                    val response=work(op){invoked=true} // synchronous: buffer, session, reservation remain strongly owned through return
                    caller.ensureActive()
                    status(response,name)
                }
            }
        } catch(_:LinkageError){
            invoked=false // symbol lookup failed before entering JNI; abandon the pending token
            throw DetailUnavailable("UNAVAILABLE")
        } finally {
            watcher.cancel()
            try{if(!invoked)runCatching{api.nativeReleaseOperation(id,op)}}finally{synchronized(lock){workers--;releaseIfDone()}}
        }
    }
}
internal fun status(raw:String,operation:String?):JSONObject {
    if(raw.length>65536)throw DetailUnavailable("INVALID_RESULT")
    val j=try{JSONObject(raw)}catch(_:Exception){throw DetailUnavailable("INVALID_RESULT")}
    if(j.optInt("schemaVersion")!=1 || (operation!=null && j.optString("operation")!=operation))throw DetailUnavailable("INVALID_RESULT")
    if(!j.optBoolean("ok") || j.optString("code")!="OK")throw DetailUnavailable(j.optString("code","INVALID_RESULT"))
    return j
}
internal fun positiveId(j:JSONObject,name:String):Long=j.getString(name).toLongOrNull()?.takeIf{it>0} ?: throw DetailUnavailable("INVALID_RESULT")
private fun rect(j:JSONObject)=DetailRect(j.getInt("x"),j.getInt("y"),j.getInt("w"),j.getInt("h"))
private fun jsonEquivalent(a:Any?,b:Any?):Boolean=if(a is Number && b is Number)a.toDouble()==b.toDouble() else a==b
private fun canonical(j:JSONObject):String=j.keys().asSequence().sorted().joinToString("|"){it+":"+j.get(it).toString()}
