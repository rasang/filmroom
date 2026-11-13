package dev.filmroom.effects

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Three concentric layers, not a scaled button. Timings are our single-shot interaction tuning. */
internal object HomeLiveIconMotion {
    const val LAYER_DURATION_MS=840L
    const val LAYER_DELAY_MS=80L
    const val DURATION_MS=LAYER_DURATION_MS+2*LAYER_DELAY_MS
    data class Layer(val scale:Float,val opacity:Float)
    fun layer(elapsedMs:Long,index:Int):Layer {
        val t=((elapsedMs-index*LAYER_DELAY_MS).toDouble()/LAYER_DURATION_MS).coerceIn(0.0,1.0)
        val wave=sin(PI*t).let{it*it}.toFloat()
        return Layer(1f+.24f*wave,1f-.60f*wave)
    }
}

internal class HomeLiveIconDrawable:Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var tint=Color.WHITE
    private var drawableAlpha=255
    private var filter:ColorFilter?=null
    private var animator:ValueAnimator?=null
    internal var elapsedMs:Long=0L
        private set
    internal val isRunning:Boolean get()=animator?.isRunning==true

    override fun setTint(tintColor:Int){tint=tintColor;invalidateSelf()}
    override fun setAlpha(alpha:Int){drawableAlpha=alpha.coerceIn(0,255);invalidateSelf()}
    override fun getAlpha()=drawableAlpha
    override fun setColorFilter(colorFilter:ColorFilter?){filter=colorFilter;invalidateSelf()}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT

    internal fun seek(elapsed:Long){elapsedMs=elapsed;invalidateSelf()}
    fun reset(){animator?.cancel();animator=null;seek(0)}
    fun play(){
        reset()
        animator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=HomeLiveIconMotion.DURATION_MS
            interpolator=LinearInterpolator()
            addUpdateListener{seek((it.animatedFraction*duration).toLong())}
            start()
        }
    }
    override fun draw(canvas:Canvas){
        val save=canvas.save()
        canvas.translate(bounds.exactCenterX(),bounds.exactCenterY())
        val unit=minOf(bounds.width(),bounds.height())/24f
        canvas.scale(unit,unit)
        for(index in 0..2){
            val layer=HomeLiveIconMotion.layer(elapsedMs,index)
            val layerSave=canvas.save();canvas.scale(layer.scale,layer.scale)
            paint.color=tint;paint.alpha=(drawableAlpha*(Color.alpha(tint)/255f)*layer.opacity).toInt();paint.colorFilter=filter
            if(index<2){
                paint.style=Paint.Style.STROKE
                paint.strokeWidth=if(index==0)1.7f else .9f
                canvas.drawCircle(0f,0f,if(index==0)2.76f else 6.6f,paint)
            }else{
                paint.style=Paint.Style.FILL
                for(dot in 0 until 36){val angle=(dot*10.0-90)*PI/180
                    canvas.drawCircle((cos(angle)*9.1).toFloat(),(sin(angle)*9.1).toFloat(),.4f,paint)
                }
            }
            canvas.restoreToCount(layerSave)
        }
        canvas.restoreToCount(save)
    }
}
