package dev.filmroom.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock

/** Tiny first-frame-visible spinner; ProgressBar owns start/stop on visibility and detach. */
internal class SmallLoadingDrawable(color:Int,private val density:Float):Drawable(),Animatable,Runnable {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{this.color=color;style=Paint.Style.STROKE;strokeWidth=2f*density;strokeCap=Paint.Cap.ROUND}
    private val arc=RectF()
    private var active=false
    private var started=0L
    override fun onBoundsChange(bounds:Rect){val inset=3f*density;arc.set(bounds.left+inset,bounds.top+inset,bounds.right-inset,bounds.bottom-inset)}
    override fun draw(canvas:Canvas){
        val angle=if(active)((SystemClock.uptimeMillis()-started)%1000L)*.36f else 0f
        canvas.drawArc(arc,angle-90f,270f,false,paint)
    }
    override fun start(){if(active)return;active=ValueAnimator.areAnimatorsEnabled();started=SystemClock.uptimeMillis();invalidateSelf();if(active)scheduleSelf(this,started+33)}
    override fun stop(){active=false;unscheduleSelf(this);invalidateSelf()}
    override fun isRunning()=active
    override fun run(){if(!active)return;if(!ValueAnimator.areAnimatorsEnabled()){stop();return};invalidateSelf();scheduleSelf(this,SystemClock.uptimeMillis()+33)}
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
