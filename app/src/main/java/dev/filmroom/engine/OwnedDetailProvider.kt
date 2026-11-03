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
data class DetailKey(val source:String,val mapping:String,val data:String,val parameters:String,val width:Int,val height:Int,val scienceExperimental:Boolean=false)
/** Caller owns bitmap. Exactness applies only to key.width × key.height, not original-size export. */
enum class DetailActualBackend(val id:String) { CPU("cpu"), GPU("gpu_f32"), MIXED("mixed_gpu_f32_cpu_f64") }
data class DetailTile(val key:DetailKey,val core:DetailRect,val sample:DetailRect,val bitmap:Bitmap,val cpuFallback:Boolean)

/** Experimental adapter; constructing it does not enable UI. No export APIs/checkpoints are used. */
class OwnedDetailProvider internal constructor(
    private val suppliedRoot:File,private val suppliedApi:DetailNativeApi?,private val memory:()->NativeMemorySnapshot,
    private val gpuContext:Context?=null
) {
    constructor(context:Context):this(context.applicationContext.dataDir.absoluteFile,null,{NativeWorkReservations.snapshot(context.applicationContext)},context.applicationContext)
    private val api:DetailNativeApi get()=try{suppliedApi ?: NativeDetailBridge}catch(_:LinkageError){throw DetailUnavailable("UNAVAILABLE")}
    private val root:File get()=suppliedRoot.canonicalFile
    private val sources:DetailSourceFactory get()=DetailSourceFactory(suppliedRoot,memory)
    fun supportsRegions():Boolean {
        val caps=status(try{api.nativeCapabilities()}catch(_:LinkageError){throw DetailUnavailable("UNAVAILABLE")},"capabilities")
        return caps.optBoolean("visibleRegion")
    }
    suspend fun cleanupOwnedOrphans()=withContext(Dispatchers.IO) {
        sources.cleanupOrphans()
        if(File(root,"files/engine-data").isDirectory && File(root,"cache/detail-preview/sessions").isDirectory) {
            val temporary=positiveId(status(try{api.nativeCreate(root.path)}catch(_:LinkageError){throw DetailUnavailable("UNAVAILABLE")},"create"),"sessionId")
            try{api.nativeDestroy(temporary)}catch(_:LinkageError){throw DetailUnavailable("UNAVAILABLE")}
        }
    }
    suspend fun open(photo:PhotoSource,parameters:EditorState,maxEdge:Int=1536,preferRegion:Boolean=false):OwnedDetailSession {
        var owned:OwnedDetailSession?=null
        try { return withContext(Dispatchers.IO) {
        sources.cleanupOrphans()
        val dataRoot=File(root,"files/engine-data")
        if(!dataRoot.isDirectory)throw DetailUnavailable("DATA_NOT_READY") // ordinary engine owns installing immutable bundled data
        val job=currentCoroutineContext()
        val preferences=gpuContext?.let{OptimizationPreferences(it)}
        val optimization=preferences?.snapshot() ?: OptimizationSnapshot()
        val gpuSelection=ScientificGpuSelection.from(optimization,dev.filmroom.BuildConfig.PERF_BUILD,"preview")
        val started=System.nanoTime()
        val digest=MessageDigest.getInstance("SHA-256")
        dataRoot.walkTopDown().filter{it.isFile}.sortedBy{it.relativeTo(dataRoot).path}.forEach {
            digest.update(it.relativeTo(dataRoot).path.toByteArray());digest.update(DetailSourceFactory.sha256(it,job).toByteArray())
        }
        val dataRevision=digest.digest().joinToString(""){"%02x".format(it)}
        val source=sources.prepare(photo,maxEdge)
        var session:OwnedDetailSession?=null
        val pressureRequested=java.util.concurrent.atomic.AtomicBoolean(false)
        val pressureOwner=java.util.concurrent.atomic.AtomicReference<OwnedDetailSession?>()
        var retained:RetainedNativeWork?=null
        var sessionId=0L
        try {
            val params=gpuSelection.applyTo(JSONObject(parameters.json("preview", includePresentation=false))).apply {
                if(optimization.cpuComparison)put("useGpu",false)
            }.toString()
            val key=DetailKey(source.sourceRevision,source.mappingRevision,dataRevision,params,source.width,source.height,gpuSelection.enabled)
            val request=JSONObject().put("schemaVersion",1).put("width",key.width).put("height",key.height)
                .put("sourceRevision",key.source).put("mappingRevision",key.mapping).put("dataRevision",key.data)
                .put("parameters",JSONObject(params)).put("memoryBudgetBytes",512L*1024*1024)
                .put("diskBudgetBytes",(root.usableSpace-64L*1024*1024).coerceAtLeast(0))
                .put("stageCacheBytes",16L*1024*1024).put("spectrumCacheBytes",32L*1024*1024)
            // Decide output strategy once, before native context construction. Only
            // explicit ROI memory-plan/admission refusal can choose legacy cells.
            val admission=admitDetailOutput(request,preferRegion,plan={ json ->
                status(api.nativePlan(json),"plan").getJSONObject("plan").also {
                    if(it.getLong("diskReservedBytes")>root.usableSpace-64L*1024*1024)throw DetailUnavailable("STORAGE_BUDGET")
                }
            },retain={ bytes -> NativeWorkReservations.retainForDetail(bytes,memory){pressureRequested.set(true);pressureOwner.get()?.close()} })
            retained=admission.reservation
            sessionId=positiveId(status(api.nativeCreate(root.path),"create"),"sessionId")
            val gpuBuild=if(gpuSelection.enabled)DetailGpuBuild(gpuSelection.router(checkNotNull(gpuContext))) else null
            session=OwnedDetailSession(api,sessionId,key,gpuSelection.enabled || JSONObject(params).getBoolean("useGpu"),retained,source,memory,regionOutput=admission.regionOutput,gpuBuild=gpuBuild)
            pressureOwner.set(session)
            retained=null // ownership transferred, including failures/cancellation during build
            owned=session
            if(pressureRequested.get()){session.close();throw DetailUnavailable("RESOURCE_PRESSURE")}
            session.build(admission.request).also { result ->
                preferences?.record("detail",optimization,result,(System.nanoTime()-started)/1_000_000.0)
            }
            job.ensureActive()
            session
        } catch(t:Throwable) {
            if(session!=null)session.close() else {
                try{if(sessionId!=0L)runCatching{api.nativeDestroy(sessionId)}}finally{try{retained?.close()}finally{source.close()}}
            }
            if(pressureRequested.get() && job.isActive && (t is CancellationException || (t is DetailUnavailable && t.code in setOf("CANCELLED","INVALID_HANDLE","INVALID_OPERATION"))))throw DetailUnavailable("RESOURCE_PRESSURE")
            throw t
        }
    } } catch(t:Throwable){
        owned?.let{session->session.close();if(!session.key.scienceExperimental)withContext(NonCancellable+Dispatchers.IO){session.awaitRetired()}}
        if(t is LinkageError)throw DetailUnavailable("UNAVAILABLE");throw t
    }
    }
}

class OwnedDetailSession internal constructor(
    private val api:DetailNativeApi,private val id:Long,val key:DetailKey,
    private val requestedGpu:Boolean,private val reservation:RetainedNativeWork,
    private val source:DetailSource,private val memory:()->NativeMemorySnapshot,
    private val conversionDispatcher:CoroutineDispatcher=Dispatchers.Default,
    val regionOutput:Boolean=false,
    private val gpuBuild:DetailGpuBuild?=null
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
    private var activeOperation:Long?=null
    private var destroyStarted=false
    @Volatile private var helperSafe=true
    val isQuarantined:Boolean get()=!helperSafe && closed
    private var released=false
    private val retirementComplete=CompletableDeferred<Unit>()
    suspend fun awaitRetired(){retirementComplete.await()}
    private var destroyed=false
    private var identity:String?=null
    private var backendIdentity:String?=null
    var actualBackend:DetailActualBackend=DetailActualBackend.CPU
        private set
    private fun destroyIfReady(){
        if(closed && !destroyStarted && (gpuBuild==null || (workers==0 && helperSafe))){
            destroyStarted=true
            CoroutineScope(Dispatchers.IO).launch {
                val safe=if(gpuBuild==null){runCatching{api.nativeDestroy(id)};true}
                    else runCatching{status(api.nativeDestroy(id),"destroy")}.isSuccess
                synchronized(lock){
                    if(safe){destroyed=true;releaseIfDone()}
                    else {reservation.quarantine();quarantineOwners[this@OwnedDetailSession]=this@OwnedDetailSession}
                }
            }
        }
    }
    private fun releaseIfDone(){destroyIfReady();if(closed && destroyed && workers==0 && !released){released=true;CoroutineScope(Dispatchers.IO).launch{
        try{runCatching{source.close()}}finally{try{runCatching{reservation.close()}}finally{quarantineOwners.remove(this@OwnedDetailSession);retirementComplete.complete(Unit)}}
    }}}
    override fun close(){
        var listener:(()->Unit)?=null
        val retire=synchronized(lock){if(closed)false else {closed=true;listener=retirementListener;retirementListener=null;true}}
        if(retire){
            val active=synchronized(lock){destroyIfReady();activeOperation}
            if(gpuBuild!=null && active!=null)CoroutineScope(Dispatchers.IO).launch{runCatching{api.nativeCancel(id,active)}}
            runCatching{listener?.invoke()}
        }
    }
    internal suspend fun build(request:String):JSONObject {
        require(key.scienceExperimental==(gpuBuild!=null))
        val result=operation("build") { op,enter ->
            enter()
            if(gpuBuild==null)api.nativeBuild(id,op,source.relativePath,request)
            else {
                helperSafe=false
                try {api.nativeBuildWithGpuDelegate(id,op,source.relativePath,request,gpuBuild.delegate)}
                finally {
                    val safe=withContext(NonCancellable){runCatching{gpuBuild.drain()}.getOrDefault(false)}
                    if(safe){
                        val cleaned=runCatching{checkPreviewGpuCleanup(api.nativeCleanupAfterGpuDrain(id))}.isSuccess
                        if(cleaned)helperSafe=true else {
                            reservation.quarantine();quarantineOwners[this]=this;close()
                            throw DetailUnavailable("GPU_CLEANUP_PENDING")
                        }
                    } else {
                        // Worker slot exits, but native/source/passive bytes stay owned.
                        // Callback comes only after every launched helper is proven dead.
                        reservation.quarantine()
                        quarantineOwners[this]=this
                        gpuBuild.whenSafeToRelease {
                            CoroutineScope(Dispatchers.IO).launch {
                                if(runCatching{checkPreviewGpuCleanup(api.nativeCleanupAfterGpuDrain(id))}.isSuccess)
                                    synchronized(lock){helperSafe=true;releaseIfDone()}
                            }
                        }
                        close()
                        throw DetailUnavailable("GPU_DRAIN_PENDING")
                    }
                }
            }
        }
        validateIdentity(result)
        identity=canonical(result.getJSONObject("identity"))
        backendIdentity=canonical(result.getJSONObject("backend"))
        actualBackend=DetailActualBackend.entries.single{it.id==result.getJSONObject("backend").getString("actualBackend")}
        return result
    }
    suspend fun tile(cellX:Int,cellY:Int):DetailTile {
        require(cellX>=0 && cellY>=0 && cellX<(key.width+255)/256 && cellY<(key.height+255)/256)
        val core=DetailRect(cellX*256,cellY*256,minOf(256,key.width-cellX*256),minOf(256,key.height-cellY*256))
        return render(core,null)
    }
    suspend fun region(core:DetailRect,cancelled:()->Boolean):DetailTile {
        if(!regionOutput)throw DetailUnavailable("UNSUPPORTED")
        require(core.x>=0 && core.y>=0 && core.width>0 && core.height>0 && core.x.toLong()+core.width<=key.width && core.y.toLong()+core.height<=key.height)
        return render(core,cancelled)
    }
    private suspend fun render(core:DetailRect,cancelled:(()->Boolean)?):DetailTile {
        val sx=(core.x-1).coerceAtLeast(0);val sy=(core.y-1).coerceAtLeast(0)
        val sample=DetailRect(sx,sy,minOf(key.width,core.x+core.width+1)-sx,minOf(key.height,core.y+core.height+1)-sy)
        lateinit var output:ByteBuffer
        var ownedBitmap:Bitmap?=null
        try { return operationWithResult(if(cancelled==null)"tile" else "region",cancelled,work={ op,enter ->
            output=ByteBuffer.allocateDirect(sample.width*sample.height*12).order(ByteOrder.nativeOrder())
            enter();if(cancelled==null)api.nativeTile(id,op,core.x/256,core.y/256,output) else api.nativeRegion(id,op,core.x,core.y,core.width,core.height,output)
        }) { response ->
            validateIdentity(response)
            if(!response.getBoolean("outputValid") || response.getLong("bytesWritten")!=output.capacity().toLong() || rect(response.getJSONObject("coreRect"))!=core || rect(response.getJSONObject("sampleRect"))!=sample || canonical(response.getJSONObject("identity"))!=identity || canonical(response.getJSONObject("backend"))!=backendIdentity)throw DetailUnavailable("INVALID_RESULT")
            // Keep session workers and the retained-work slot through postprocessing,
            // not merely through JNI: direct RGB, IntArray and bitmap still overlap.
            withContext(conversionDispatcher) {
                fun checkCurrent(){
                    if(cancelled?.invoke()==true)throw CancellationException("Obsolete viewport")
                    synchronized(lock){if(closed)throw CancellationException("Detail retired")}
                }
                currentCoroutineContext().ensureActive();checkCurrent()
                val rowCount=minOf(16,sample.height)
                val pixels=IntArray(sample.width*rowCount)
                val bitmap=Bitmap.createBitmap(sample.width,sample.height,Bitmap.Config.ARGB_8888)
                ownedBitmap=bitmap
                output.rewind()
                for(y in 0 until sample.height step rowCount){
                    val rows=minOf(rowCount,sample.height-y)
                    for(i in 0 until sample.width*rows){
                        if(i%256==0){currentCoroutineContext().ensureActive();checkCurrent()}
                        val r=output.float;val g=output.float;val b=output.float
                        if(!r.isFinite() || !g.isFinite() || !b.isFinite())throw DetailUnavailable("INVALID_RESULT")
                        pixels[i]=0xff000000.toInt() or (LinearRgb.toSrgb8(r) shl 16) or (LinearRgb.toSrgb8(g) shl 8) or LinearRgb.toSrgb8(b)
                    }
                    bitmap.setPixels(pixels,0,sample.width,0,y,sample.width,rows)
                }
                currentCoroutineContext().ensureActive();checkCurrent()
                DetailTile(key,core,sample,bitmap,response.getJSONObject("backend").getBoolean("cpuFallback"))
            }
        }}catch(t:Throwable){ownedBitmap?.takeUnless{it.isRecycled}?.recycle();throw t}
    }
    private fun validateIdentity(response:JSONObject) {
        val j=response.getJSONObject("identity");val backend=response.getJSONObject("backend")
        val algorithm=if(key.scienceExperimental)"owned-detail-v3-guarded64-resident-fullgrid-gutter1" else "owned-detail-v1-f64-fixed256-gutter1"
        val quality=if(key.scienceExperimental)"ExperimentalSameWorkingGrid" else "ExactSameWorkingGrid"
        val actual=backend.getString("actualBackend")
        val allowed=if(key.scienceExperimental)setOf("cpu","gpu_f32","mixed_gpu_f32_cpu_f64") else setOf("cpu")
        if(j.getString("sourceRevision")!=key.source || j.getString("mappingRevision")!=key.mapping || j.getString("dataRevision")!=key.data || j.getInt("width")!=key.width || j.getInt("height")!=key.height || j.getString("algorithm")!=algorithm || response.getString("quality")!=quality || actual !in allowed || backend.getBoolean("requestedGpu")!=requestedGpu || backend.getBoolean("cpuFallback")!=(requestedGpu && actual!="gpu_f32"))throw DetailUnavailable("INVALID_RESULT")
        // Native serializes defaults too; every explicitly requested field must survive unchanged.
        val actualParameters=JSONObject(j.getString("parameters"));val expected=JSONObject(key.parameters)
        expected.keys().forEach { k -> if(!jsonEquivalent(expected.get(k),actualParameters.opt(k)))throw DetailUnavailable("INVALID_RESULT") }
    }
    private suspend fun operation(name:String,cancelled:(()->Boolean)?=null,work:suspend (Long,()->Unit)->String):JSONObject =
        operationWithResult(name,cancelled,work){it}
    private suspend fun <T> operationWithResult(name:String,cancelled:(()->Boolean)?=null,work:suspend (Long,()->Unit)->String,consume:suspend (JSONObject)->T):T {
        val caller=currentCoroutineContext();caller.ensureActive();if(cancelled?.invoke()==true)throw CancellationException("Obsolete viewport")
        val op=synchronized(lock){if(closed)throw CancellationException("Detail retired");if(workers!=0)throw DetailUnavailable("BUSY");positiveId(status(try{api.nativeBeginOperation(id)}catch(_:LinkageError){throw DetailUnavailable("UNAVAILABLE")},"beginOperation"),"operationId").also{workers++;activeOperation=it}}
        var invoked=false
        val watcher=CoroutineScope(Dispatchers.Default).launch {
            while(isActive){if(!caller.isActive || cancelled?.invoke()==true){runCatching{api.nativeCancel(id,op)};break};delay(10)}
        }
        try {
            return withContext(Dispatchers.IO) {
                reservation.run({if(cancelled?.invoke()==true)throw CancellationException("Obsolete viewport");synchronized(lock){if(closed)throw CancellationException("Detail retired")};memory()}) {
                    caller.ensureActive()
                    if(cancelled?.invoke()==true)throw CancellationException("Obsolete viewport")
                    val response=work(op){invoked=true} // synchronous: buffer, session, reservation remain strongly owned through return
                    caller.ensureActive()
                    if(cancelled?.invoke()==true)throw CancellationException("Obsolete viewport")
                    consume(status(response,name))
                }
            }
        } catch(_:LinkageError){
            invoked=false // symbol lookup failed before entering JNI; abandon the pending token
            throw DetailUnavailable("UNAVAILABLE")
        } finally {
            watcher.cancel()
            try{if(!invoked)runCatching{api.nativeReleaseOperation(id,op)}}finally{synchronized(lock){workers--;activeOperation=null;releaseIfDone()}}
        }
    }
    private companion object {val quarantineOwners=java.util.concurrent.ConcurrentHashMap<OwnedDetailSession,OwnedDetailSession>()}
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
