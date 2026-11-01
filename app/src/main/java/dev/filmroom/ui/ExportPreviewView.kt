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
    private var intro = false
    private var foreground = false
    private var version: String? = null
    private var binding: String? = null
    private var fade: ValueAnimator? = null
    private var breathing: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    internal val animating get() = (fade?.isRunning == true && fade?.isPaused != true) || (breathing?.isRunning == true && breathing?.isPaused != true)
    internal var transitionCount = 0; private set

    fun bindJob(key: String) {
        if (binding == key) return
        release(); binding = key
    }
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) { fade?.pause(); breathing?.pause() }
        else if (ValueAnimator.areAnimatorsEnabled()) {
            fade?.resume(); breathing?.resume(); updateBreathing()
        } else { finishAnimations() }
    }
    fun setBreathing(value: Boolean) {
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
        fade?.cancel(); fade = null; breathing?.cancel(); breathing = null
        blend = 1f; previous = null; light = 0f; invalidate()
    }
    fun release() { finishAnimations(); current = null; version = null; lightTarget = false; invalidate() }
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
            paint.shader=RadialGradient(width*.38f,height*.38f,max(width,height).toFloat().coerceAtLeast(1f),
                intArrayOf(Color.argb((light*30).toInt(),255,240,211),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint); paint.shader=null
        }
    }
}

/** Tiny, bounded, process-memory cache; source remains immutable. Called only off the UI thread. */
internal object ExportContourCache {
    private val cache = object : LruCache<String, Bitmap>(4*1024*1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    internal var computations = 0; private set
    @Synchronized fun clear() { cache.evictAll() }
    @Synchronized fun get(key: String, source: Bitmap): Bitmap {
        cache.get(key)?.let { return it }
        val scale=min(1f,320f/max(source.width,source.height))
        val small=Bitmap.createScaledBitmap(source,max(1,(source.width*scale).toInt()),max(1,(source.height*scale).toInt()),true)
        val w=small.width; val h=small.height; val pixels=IntArray(w*h); small.getPixels(pixels,0,w,0,0,w,h)
        val gray=IntArray(w*h) { i -> val p=pixels[i]; ((p shr 16 and 255)*77+(p shr 8 and 255)*150+(p and 255)*29)/256 }
        val smooth=IntArray(w*h)
        for(y in 1 until h-1) for(x in 1 until w-1) {
            var sum=0; for(dy in -1..1) for(dx in -1..1) sum+=gray[(y+dy)*w+x+dx]*(if(dx==0)2 else 1)*(if(dy==0)2 else 1)
            smooth[y*w+x]=sum/16
        }
        pixels.fill(Color.BLACK)
        for(y in 2 until h-2) for(x in 2 until w-2) {
            val i=y*w+x
            val gx= -smooth[i-w-1]+smooth[i-w+1]-2*smooth[i-1]+2*smooth[i+1]-smooth[i+w-1]+smooth[i+w+1]
            val gy= -smooth[i-w-1]-2*smooth[i-w]-smooth[i-w+1]+smooth[i+w-1]+2*smooth[i+w]+smooth[i+w+1]
            val v=((sqrt((gx*gx+gy*gy).toDouble())-34)*1.25).toInt().coerceIn(0,225)
            pixels[i]=Color.rgb(v,v,v)
        }
        val result=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        if(small!==source)small.recycle()
        computations++; cache.put(key,result); return result
    }
}
