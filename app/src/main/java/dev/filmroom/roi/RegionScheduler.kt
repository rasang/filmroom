package dev.filmroom.roi

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One current viewport, one worker. Published bitmap ownership belongs to Canvas/GC. */
class RegionScheduler(
    private val provider: TileProvider,
    val byteBudget: Long = 12L * 1024 * 1024,
    worker: Executor? = null,
    private val onChanged: () -> Unit = {}
) : AutoCloseable {
    private val ownedWorker=if(worker==null)Executors.newSingleThreadExecutor { r -> Thread(r,"roi-visible").apply{isDaemon=true} } else null
    private val worker=worker ?: ownedWorker!!
    private val main=Handler(Looper.getMainLooper())
    private val cache=LinkedHashMap<PixelRect,TileResult>(4,.75f,true)
    private var demand:PixelRect?=null
    private var paused=false
    private var closed=false
    private var running=false
    private var token=AtomicBoolean(false)
    private var failed:PixelRect?=null
    var key:RenderKey?=null;private set
    var viewportRevision=0L;private set
    var cacheBytes=0L;private set
    var lastFailure:Throwable?=null;private set
    var lastError:String?=null;private set
    val runningCount get()=if(running)1 else 0
    private fun mainOnly(){check(Looper.myLooper()==Looper.getMainLooper())}
    private fun covers(a:PixelRect,b:PixelRect)=a.left<=b.left && a.top<=b.top && a.right>=b.right && a.bottom>=b.bottom
    private fun coveringKey():PixelRect? {val d=demand ?: return null;return cache.keys.firstOrNull{covers(it,d)}}
    private fun covered():Boolean { val hit=coveringKey() ?: return false;return cache[hit]!=null }
    val ready get()=covered()
    val hasDemand get()=demand!=null
    fun update(k:RenderKey,visible:PixelRect?) {
        mainOnly();check(!closed)
        require(maxOf(k.virtualFullWidth,k.virtualFullHeight)<=1536)
        visible?.let{require(it.left>=0 && it.top>=0 && it.right<=k.virtualFullWidth && it.bottom<=k.virtualFullHeight && it.width>0 && it.height>0)}
        if(k!=key || visible!=demand){
            token.set(true);token=AtomicBoolean(false);failed=null;lastError=null;lastFailure=null
            if(k!=key){cache.clear();cacheBytes=0}
            key=k;demand=visible;viewportRevision++
        }
        onChanged();pump()
    }
    fun setPaused(value:Boolean){mainOnly();if(closed)return;paused=value;if(!paused)pump()}
    fun drawableTiles():List<TileResult>{
        mainOnly();val d=demand ?: return emptyList()
        // Reuse one same-science overlapping region while its replacement is pending;
        // uncovered pixels continue to show the underlay, never a stretched crop.
        val hit=coveringKey() ?: cache.keys.maxByOrNull { r ->
            (minOf(r.right,d.right)-maxOf(r.left,d.left)).coerceAtLeast(0).toLong() *
                (minOf(r.bottom,d.bottom)-maxOf(r.top,d.top)).coerceAtLeast(0)
        }?.takeIf{it.left<d.right && it.right>d.left && it.top<d.bottom && it.bottom>d.top} ?: return emptyList()
        return listOfNotNull(cache[hit])
    }
    fun forEachDrawable(draw:(TileResult)->Unit){drawableTiles().forEach(draw)}
    private fun pump(){
        if(closed || paused || running || covered())return
        val k=key ?: return;val rect=demand ?: return
        if(failed==rect)return
        val sample=rect.sampleRect(k)
        if(sample.width.toLong()*sample.height*4>byteBudget){failed=rect;lastFailure=dev.filmroom.engine.DetailUnavailable("BUDGET_EXCEEDED");lastError="Region bitmap budget";onChanged();return}
        val cancellation=token
        running=true
        try {worker.execute {
            var result:TileResult?=null;var error:Throwable?=null
            try {check(Looper.myLooper()!=Looper.getMainLooper());result=provider.render(k,rect){cancellation.get()}}
            catch(t:Exception){error=t}catch(t:LinkageError){error=t}catch(t:OutOfMemoryError){error=t}
            val completed=result;val failure=error
            main.post {
                running=false
                val valid=!closed && key==k && demand==rect && !cancellation.get()
                val accepted=completed!=null && completed.key==k && completed.coreRect==rect && completed.sampleRect==sample && completed.quality==TileQuality.Exact &&
                    !completed.bitmap.isRecycled && completed.bitmap.width==sample.width && completed.bitmap.height==sample.height && completed.bitmap.config==Bitmap.Config.ARGB_8888 && completed.bitmap.allocationByteCount.toLong()==sample.width.toLong()*sample.height*4
                if(valid && accepted){
                    val bytes=completed!!.bitmap.allocationByteCount.toLong()
                    while(cacheBytes+bytes>byteBudget && cache.isNotEmpty()){val old=cache.entries.first();cacheBytes-=old.value.bitmap.allocationByteCount;cache.remove(old.key)}
                    cache.put(rect,completed)?.let{cacheBytes-=it.bitmap.allocationByteCount};cacheBytes+=bytes
                }else{
                    completed?.bitmap?.takeUnless{it.isRecycled}?.recycle()
                    if(valid){failed=rect;lastFailure=failure;lastError=failure?.message ?: "Rejected region contract"}
                }
                onChanged();pump()
            }
        }}catch(t:RuntimeException){running=false;failed=rect;lastFailure=t;lastError="Worker unavailable";onChanged()}
        catch(t:OutOfMemoryError){running=false;failed=rect;lastFailure=t;lastError="Worker memory";onChanged()}
    }
    override fun close(){mainOnly();closed=true;token.set(true);cache.clear();cacheBytes=0;demand=null;ownedWorker?.shutdown()}
}
