package dev.filmroom.roi

import android.graphics.Bitmap
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import kotlinx.coroutines.*
import kotlin.math.abs

/** Produces a full scientific frame, including the pre-imaging border and imprints when enabled.
 * Implementations own cancellation during dispatcher handoffs and must never return
 * a bitmap still being written by native code. They must keep source leases until JNI drains. */
interface ZoomFrameProvider : AutoCloseable {
    suspend fun render(photo:PhotoSource, parameters:EditorState, baseBitmap:Bitmap, edge:Int):Bitmap
    fun cancel()
}

/** Geometry-only policy. This does not claim that the native memory planner will admit a level. */
object ZoomFramePolicy {
    /** The engine returns a complete frame for pre-imaging, or a bare uncropped photo.
     * Post-imaging borders/crops/imprints are composed by the editor, not renderZoom;
     * they must keep their existing detail route until that composition is shared. */
    fun supportsPresentation(parameters:EditorState):Boolean = parameters.preImagingBorder ||
        (!parameters.filmBorder && parameters.crop==dev.filmroom.model.CropSettings() && !parameters.imprint.enabled)
    fun usesWholeFrame(parameters:EditorState):Boolean = parameters.useGpu && supportsPresentation(parameters)
    const val FIRST_EDGE=2048
    const val MAX_EDGE=3072
    const val MIN_ZOOM=1.15
    fun target(zoom:Double, originalEdge:Int, baseEdge:Int):Int? {
        if(!zoom.isFinite() || zoom<MIN_ZOOM || originalEdge<=1536 || baseEdge<=0)return null
        val target=if(zoom>=2.0)MAX_EDGE else FIRST_EDGE
        return target.takeIf{it>baseEdge}
    }
    fun next(target:Int, readyEdge:Int, baseEdge:Int):Int? {
        if(readyEdge>=target)return null
        return if(readyEdge<FIRST_EDGE && baseEdge<FIRST_EDGE)FIRST_EDGE else target
    }
    /** Allow only one-pixel rounding of the same frame, not a different crop or stretched image. */
    fun matchesFrame(width:Int,height:Int,baseWidth:Int,baseHeight:Int,edge:Int):Boolean =
        width>0 && height>0 && baseWidth>0 && baseHeight>0 && maxOf(width,height)<=edge &&
            maxOf(width,height)>maxOf(baseWidth,baseHeight) &&
            abs(width.toLong()*baseHeight-height.toLong()*baseWidth)<=maxOf(width,height)+maxOf(baseWidth,baseHeight).toLong()
}

/** Main-confined owner for progressive, bounded full-frame zoom refinement.
 * Pan reuses a frame. A higher tier failure preserves the last valid frame. Retain
 * must account for the returned bitmap and may request eviction from any thread.
 * Caller must bind with eligible=false during editing, background export or comparison. */
class ZoomFrameController(
    private val provider:ZoomFrameProvider,
    private val scope:CoroutineScope,
    private val changed:()->Unit,
    private val retain:(Bitmap,()->Unit)->AutoCloseable,
    private val settleMillis:Long=250,
    private val refinementTimeoutMillis:Long=8_000,
) : AutoCloseable {
    private class Key(val photo:PhotoSource,val parameters:EditorState) {
        val revision:String=photo.previewSourceRevision()
        fun same(other:Key?)=other!=null && photo===other.photo && revision==other.revision && parameters==other.parameters
    }
    private var key:Key?=null
    private var base:Bitmap?=null
    private var allowed=false
    private var front=true
    private var zoom=1.0
    private var moving=false
    private var closed=false
    private var generation=0L
    private var job:Job?=null
    private var started=false
    private var inFlight=0
    private var ready:Bitmap?=null
    private var readyKey:Key?=null
    private var readyEdge=0
    private var readyTier=0
    private var lease:AutoCloseable?=null
    private var failedEdge:Int?=null
    private var attemptEdge:Int?=null
    private var desired:Int?=null
    private var pressureBlocked=false
    val bitmap:Bitmap? get()=ready?.takeIf{!closed && front && allowed && zoom>=ZoomFramePolicy.MIN_ZOOM && key?.same(readyKey)==true && !it.isRecycled}
    val loading:Boolean get()=!closed && front && allowed && started
    val failed:Boolean get()=failedEdge!=null || pressureBlocked
    /** Retain ownership while cancelled JNI drains; only then may the ROI fallback start. */
    val ownsDetail:Boolean get()=!closed && front && allowed && desired!=null &&
        (bitmap!=null || !failed || inFlight>0)

    fun bind(photo:PhotoSource?,parameters:EditorState,baseBitmap:Bitmap?,eligible:Boolean,compareOriginal:Boolean=false,exporting:Boolean=false) {
        if(closed)return
        val next=photo?.let{Key(it,parameters)}
        if(key?.same(next)!=true)pressureBlocked=false
        val nextAllowed=eligible && !compareOriginal && !exporting && ZoomFramePolicy.usesWholeFrame(parameters) && baseBitmap?.isRecycled==false
        if(key?.same(next)!=true || next==null || base!==baseBitmap || (allowed && !nextAllowed)) {
            invalidate(clear=true)
        }
        key=next;base=baseBitmap;allowed=nextAllowed
        demand()
    }
    fun viewport(zoom:Double,moving:Boolean=false) {
        if(closed)return
        this.zoom=zoom;this.moving=moving
        if(zoom.isFinite() && zoom<ZoomFramePolicy.MIN_ZOOM)pressureBlocked=false
        demand()
    }
    fun foreground(value:Boolean) {
        if(closed || front==value)return
        front=value
        if(value)pressureBlocked=false
        if(!value)invalidate(clear=true)
        demand()
    }
    /** Explicit pressure retirement. The same request cannot immediately allocate again
     * from pan/rebind; leaving zoom, a new source/science key, or foreground reentry resets it. */
    fun releaseForPressure() {
        if(closed)return
        pressureBlocked=true
        invalidate(clear=true)
    }
    private fun demand() {
        val source=key;val input=base
        val target=if(!closed && front && allowed && source!=null && input!=null && !input.isRecycled)
            ZoomFramePolicy.target(zoom,maxOf(source.photo.originalWidth,source.photo.originalHeight),maxOf(input.width,input.height)) else null
        val previousDesired=desired
        desired=target
        if(previousDesired!=desired)changed()
        if(pressureBlocked)return
        if(target==null){if(job!=null || ready!=null || failedEdge!=null)invalidate(clear=true);return}
        if(moving){if(job!=null && !started)invalidate(clear=false,resetFailure=false);return}
        if(job?.isActive==true)return
        val edge=ZoomFramePolicy.next(target,readyTier,maxOf(input!!.width,input.height)) ?: return
        if(failedEdge==edge)return
        schedule(source!!,input,edge)
    }
    private fun schedule(request:Key,input:Bitmap,edge:Int) {
        val token=++generation
        val previous=job
        attemptEdge=edge
        job=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            var produced:Bitmap?=null
            var tokenLease:AutoCloseable?=null
            var watchdog:Job?=null
            var enteredProvider=false
            try {
                // Preserve the drain chain even if this queued request is itself cancelled.
                withContext(NonCancellable){previous?.join()}
                delay(settleMillis)
                ensureActive()
                if(token!=generation || !request.same(key) || desired==null || moving)return@launch
                started=true;changed()
                // Refinement is optional. Stop an expensive tier rather than leave the
                // UI spinning forever. Cancellation does NOT release native ownership:
                // the provider and subsequent requests still wait for JNI to drain.
                val renderingJob=currentCoroutineContext().job
                watchdog=scope.launch {
                    delay(refinementTimeoutMillis)
                    if(token==generation && started) {
                        failedEdge=edge;started=false
                        provider.cancel()
                        renderingJob.cancel(CancellationException("Zoom refinement time budget exceeded"))
                        changed()
                    }
                }
                inFlight++;enteredProvider=true
                val result=provider.render(request.photo,request.parameters,input,edge)
                produced=result
                ensureActive()
                if(token!=generation || !request.same(key) || desired==null)return@launch
                check(request.revision==request.photo.previewSourceRevision()){"Zoom source changed"}
                check(ZoomFramePolicy.matchesFrame(result.width,result.height,input.width,input.height,edge)){"Zoom frame geometry mismatch"}
                check(maxOf(result.width,result.height)>readyEdge){"Zoom frame did not improve sampling"}
                val evicted=java.util.concurrent.atomic.AtomicBoolean(false)
                tokenLease=retain(result){
                    evicted.set(true)
                    scope.launch {if(ready===result || token==generation)releaseForPressure()}
                }
                if(evicted.get())throw IllegalStateException("Zoom frame evicted during admission")
                ensureActive()
                if(token!=generation)return@launch
                clearReady()
                ready=result;readyKey=request;readyEdge=maxOf(result.width,result.height);readyTier=edge;lease=tokenLease
                produced=null;tokenLease=null;failedEdge=null
            } catch(t:CancellationException) {
                throw t
            } catch(_:Exception) {
                if(token==generation)failedEdge=edge
            } catch(_:OutOfMemoryError) {
                if(token==generation)failedEdge=edge
            } finally {
                watchdog?.cancel()
                if(enteredProvider)inFlight--
                tokenLease?.close()
                produced?.takeUnless{it.isRecycled}?.recycle()
                if(token==generation){started=false;job=null;attemptEdge=null;changed();demand()}
                else changed() // A retired native call may have held ROI ownership until now.
            }
        }
    }
    private fun invalidate(clear:Boolean,resetFailure:Boolean=true) {
        generation++
        if(job?.isActive==true){if(started)provider.cancel();job?.cancel()}
        // Keep the old job reference: the next request must join its native drain.
        started=false;attemptEdge=null
        if(resetFailure)failedEdge=null
        if(clear)clearReady()
        changed()
    }
    private fun clearReady() {
        val old=ready;val oldLease=lease
        ready=null;readyKey=null;readyEdge=0;readyTier=0;lease=null
        try{old?.takeUnless{it.isRecycled}?.recycle()}finally{oldLease?.close()}
    }
    override fun close() {
        if(closed)return
        closed=true;invalidate(clear=true)
        val retiring=job;job=null
        // Lifecycle scope may already be cancelled. Wait for synchronous JNI to
        // drain off main before closing the independent provider.
        CoroutineScope(Dispatchers.Default).launch {try{retiring?.join()}finally{provider.close()}}
    }
}
