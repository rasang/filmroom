package dev.filmroom.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.LruCache
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.*

/** Presentation only. Never participates in the renderer or writes job files. */
internal class ExportPreviewView(context: Context) : View(context) {
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var blend = 1f
    private var light = 0f
    private var lightTarget = false
    private var emphasis:ValueAnimator?=null
    private var strength=30f
    private var strongTarget=false
    private var intro = false
    private var foreground = false
    private var version: String? = null
    private var binding: String? = null
    private var fade: ValueAnimator? = null
    private var breathing: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    internal val animating get() = (emphasis?.isRunning==true && emphasis?.isPaused!=true) || (fade?.isRunning == true && fade?.isPaused != true) || (breathing?.isRunning == true && breathing?.isPaused != true)
    internal var transitionCount = 0; private set

    fun bindJob(key: String) {
        if (binding == key) return
        release(); binding = key
    }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { fade?.pause(); breathing?.pause(); emphasis?.pause() }
        else if (ValueAnimator.areAnimatorsEnabled()) {
            fade?.resume(); breathing?.resume(); emphasis?.resume(); updateBreathing()
        } else { finishAnimations() }
    }
    fun setBreathing(value: Boolean, strong: Boolean = false) {
        if(strongTarget!=strong){
            strongTarget=strong;emphasis?.cancel();emphasis=null
            val target=if(strong)78f else 30f
            if(foreground && ValueAnimator.areAnimatorsEnabled())emphasis=ValueAnimator.ofFloat(strength,target).apply{
                duration=450;interpolator=AccelerateDecelerateInterpolator();addUpdateListener{strength=it.animatedValue as Float;invalidate()};start()
            }else strength=target
        }
        if (lightTarget == value) return
        lightTarget = value
        breathing?.cancel(); breathing = null
        updateBreathing()
    }
    private fun updateBreathing() {
        if (!foreground || current == null || !ValueAnimator.areAnimatorsEnabled() || breathing != null) return
        if (!lightTarget && light <= 0f) return
        val startLight = light
        breathing = ValueAnimator.ofFloat(if(lightTarget)0f else light, if(lightTarget)1f else 0f).apply {
            duration = if(lightTarget)2800 else 450
            if(lightTarget){repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE}
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { light = it.animatedValue as Float; invalidate() }; start()
            if(lightTarget && startLight>0f)setCurrentFraction((acos((1f-2f*startLight).coerceIn(-1f,1f))/PI).toFloat())
        }
    }
    /** Keep the previous visible pixels until the next bitmap is ready, including interrupted fades. */
    fun show(bitmap: Bitmap, key: String, blackIntro: Boolean = false) {
        if (version == key) return
        val old = if (blend < 1f && width > 0 && height > 0) runCatching {
            val scale = min(1f,480f/max(width,height))
            Bitmap.createBitmap(max(1,(width*scale).toInt()), max(1,(height*scale).toInt()), Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it); c.scale(it.width.toFloat()/width, it.height.toFloat()/height); drawContent(c)
            }
        }.getOrNull() ?: current else current
        fade?.cancel(); fade = null
        previous = old; current = bitmap; version = key; transitionCount++; intro = blackIntro
        val animate = foreground && ValueAnimator.areAnimatorsEnabled() && (old != null || blackIntro)
        blend = if (animate) 0f else 1f
        if (animate) fade = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (blackIntro) 1000 else 450
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { blend = it.animatedValue as Float; if (blend == 1f) previous = null; invalidate() }
            start()
        } else previous = null
        updateBreathing(); invalidate()
    }
    private fun finishAnimations() {
        fade?.cancel(); fade = null; breathing?.cancel(); breathing = null; emphasis?.cancel();emphasis=null
        blend = 1f; previous = null; light = 0f; invalidate()
    }
    fun release() { finishAnimations(); current = null; version = null; lightTarget = false;strongTarget=false;strength=30f; invalidate() }
    override fun onDetachedFromWindow() { setForeground(false); super.onDetachedFromWindow() }
    private fun photo(canvas: Canvas, bitmap: Bitmap, alpha: Int) {
        if (bitmap.isRecycled) return
        val scale = max(width.toFloat()/bitmap.width, height.toFloat()/bitmap.height)
        val w=bitmap.width*scale; val h=bitmap.height*scale
        paint.shader=null; paint.alpha=alpha
        canvas.drawBitmap(bitmap,null,RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),paint)
    }
    private fun drawContent(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        previous?.let { photo(canvas,it,if(intro)(max(0f,1f-2f*blend)*255).toInt() else 255) }
        current?.let { photo(canvas,it,((if(intro && previous!=null)max(0f,2f*blend-1f) else blend)*255).toInt()) }
    }
    override fun onDraw(canvas: Canvas) {
        if(!ValueAnimator.areAnimatorsEnabled() && animating)finishAnimations()
        super.onDraw(canvas); drawContent(canvas)
        if (light > 0f && current != null) {
            paint.alpha=255
            paint.shader=RadialGradient(width*.38f,height*.38f,max(width,height).toFloat().coerceAtLeast(1f)*(1f+(strength-30f)/240f),
                intArrayOf(Color.argb((light*strength).toInt(),255,240,211),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint); paint.shader=null
        }
    }
}

/** Tiny, bounded, process-memory cache; source remains immutable. Called only off the UI thread. */
internal object ExportContourCache {
    const val VERSION = 2
    const val MAX_EDGE = 480
    const val WORKING_BYTES = 6L*1024*1024
    private val cache = object : LruCache<String, Bitmap>(4*1024*1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // A visible View may still hold an evicted bitmap. Never recycle shared entries here.
    }
    private val computeLock = Any()
    private val cacheLock = Any()
    private var epoch = 0L
    private class Request(val key:String,var invalidated:Boolean=false)
    private val requests=mutableSetOf<Request>()
    @Volatile internal var computations = 0; private set
    internal val cachedBytes: Int get() = synchronized(cacheLock) { cache.size() }
    fun clear() = synchronized(cacheLock) { epoch++; cache.evictAll() }
    fun removeJob(id:String) = synchronized(cacheLock) {
        val prefix="v$VERSION:$id:"
        requests.filter{it.key.startsWith(prefix)}.forEach{it.invalidated=true}
        cache.snapshot().keys.filter{it.startsWith(prefix)}.forEach{cache.remove(it)}
    }
    fun find(key: String): Bitmap? = synchronized(cacheLock) {
        cache.get("v$VERSION:$key")?.takeUnless { it.isRecycled }
    }
    fun get(key: String, source: Bitmap): Bitmap = get(key,source) {}
    fun get(key: String, source: Bitmap, checkActive: () -> Unit): Bitmap {
        checkActive()
        val request=Request("v$VERSION:$key")
        val startedEpoch = synchronized(cacheLock) {
            cache.get("v$VERSION:$key")?.takeUnless { it.isRecycled }?.let { return it }
            requests.add(request)
            epoch
        }
        try {return synchronized(computeLock) {
        checkActive()
        synchronized(cacheLock) {
            if(epoch!=startedEpoch || request.invalidated)throw java.util.concurrent.CancellationException("Queued contour invalidated")
            cache.get("v$VERSION:$key")?.takeUnless { it.isRecycled }?.let { return it }
        }
        require(!source.isRecycled && source.width>0 && source.height>0)
        val scale=min(1.0,MAX_EDGE.toDouble()/max(source.width,source.height))
        val small=Bitmap.createScaledBitmap(source,max(1,(source.width*scale).toInt()),max(1,(source.height*scale).toInt()),true)
        var unpublished:Bitmap?=null
        try {
            val w=small.width; val h=small.height; val pixels=IntArray(w*h)
            small.getPixels(pixels,0,w,0,0,w,h)
            val gray=IntArray(w*h) { i -> val p=pixels[i]; ((p shr 16 and 255)*77+(p shr 8 and 255)*150+(p and 255)*29)/256 }
            val smooth=IntArray(w*h)
            // Convex, weak denoise: no unsharp overshoot or bright halo around a step edge.
            for(y in 1 until h-1) {
                if((y and 15)==0)checkActive()
                for(x in 1 until w-1) {
                    var sum=0
                    for(dy in -1..1)for(dx in -1..1)sum+=gray[(y+dy)*w+x+dx]*(if(dx==0)2 else 1)*(if(dy==0)2 else 1)
                    smooth[y*w+x]=(gray[y*w+x]*3+sum/16)/4
                }
            }
            val magnitude=FloatArray(w*h);val direction=ByteArray(w*h)
            for(y in 2 until h-2) {
                if((y and 15)==0)checkActive()
                for(x in 2 until w-2) {
                    val i=y*w+x
                    val gx= -smooth[i-w-1]+smooth[i-w+1]-2*smooth[i-1]+2*smooth[i+1]-smooth[i+w-1]+smooth[i+w+1]
                    val gy= -smooth[i-w-1]-2*smooth[i-w]-smooth[i-w+1]+smooth[i+w-1]+2*smooth[i+w]+smooth[i+w+1]
                    magnitude[i]=Math.hypot(gx.toDouble(),gy.toDouble()).toFloat()
                    val ax=abs(gx).toFloat();val ay=abs(gy).toFloat()
                    direction[i]=(if(ay<ax*.4142)0 else if(ax<ay*.4142)1 else if(gx*gy>0)2 else 3).toByte()
                }
            }
            pixels.fill(Color.BLACK)
            for(y in 3 until h-3) {
                if((y and 15)==0)checkActive()
                for(x in 3 until w-3) {
                    val i=y*w+x
                    val d=when(direction[i].toInt()){0->1;1->w;2->w+1;else->w-1}
                    val m=magnitude[i];val peak=max(magnitude[i-d],magnitude[i+d])
                    val narrow=if(m<peak*.92f).16f else 1f
                    val v=((m-26)*1.05f*narrow).roundToInt().coerceIn(0,240)
                    pixels[i]=Color.rgb(v,v,v)
                }
            }
            checkActive()
            val result=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
            unpublished=result
            checkActive()
            synchronized(cacheLock) {
                if(epoch!=startedEpoch || request.invalidated)throw java.util.concurrent.CancellationException("Contour cache invalidated")
                cache.put("v$VERSION:$key",result);unpublished=null;computations++
            }
            return result
        } finally {
            unpublished?.recycle()
            if(small!==source)small.recycle()
        }
        }
        } finally {synchronized(cacheLock){requests.remove(request)}}
    }
}
