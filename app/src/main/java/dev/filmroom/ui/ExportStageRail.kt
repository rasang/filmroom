package dev.filmroom.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** Seven equal steps, not an overall-time estimate. Only the current step has a measured fill. */
class ExportStageRail(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var stage=1
    private var fraction=0f
    private var target=0f
    private var completed=false
    private var measured=false
    private var running=false
    private var animator:ValueAnimator?=null
    private var viewportVisible=true
    fun setViewportVisible(visible:Boolean){viewportVisible=visible;if(!visible)stop()}
    fun pause(){stop()}
    fun bind(stage:Int,percent:Int?,completed:Boolean,running:Boolean) {
        val next=(percent ?: 0).coerceIn(0,100)/100f
        val sameStage=this.stage==stage
        val changed=this.stage!=stage || target!=next || this.completed!=completed || this.running!=running || measured!=(percent!=null)
        this.stage=stage.coerceIn(1,7);this.completed=completed;this.running=running;this.measured=percent!=null
        if(!changed)return
        animator?.cancel();target=next
        // No autonomous/indeterminate motion: unknown work is an outlined active step.
        if(sameStage && percent!=null && running && viewportVisible && isShown && windowVisibility==VISIBLE && ValueAnimator.areAnimatorsEnabled()) {
            animator=ValueAnimator.ofFloat(fraction,next).apply {
                duration=180;addUpdateListener{fraction=it.animatedValue as Float;invalidate()};start()
            }
        }else{fraction=next;invalidate()}
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val gap=4*resources.displayMetrics.density
        val segment=((width-gap*6)/7).coerceAtLeast(0f)
        for(i in 0..6) {
            val x=i*(segment+gap)
            paint.color=Color.rgb(56,61,57)
            canvas.drawRoundRect(x,0f,x+segment,height.toFloat(),height/2f,height/2f,paint)
            val amount=when {completed || i<stage-1->1f;i==stage-1->fraction;else->0f}
            paint.color=if(running && i==stage-1)Color.rgb(220,185,132)else Color.rgb(171,183,169)
            if(amount>0)canvas.drawRoundRect(x,0f,x+segment*amount,height.toFloat(),height/2f,height/2f,paint)
            if(i==stage-1 && !measured && !completed) {
                paint.style=Paint.Style.STROKE;paint.strokeWidth=resources.displayMetrics.density
                val inset=paint.strokeWidth/2
                canvas.drawRoundRect(x+inset,inset,x+segment-inset,height-inset,height/2f,height/2f,paint)
                paint.style=Paint.Style.FILL
            }
        }
    }
    private fun stop(){animator?.cancel();animator=null;fraction=target;invalidate()}
    override fun onDetachedFromWindow(){stop();super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE)stop()}
    override fun onVisibilityAggregated(isVisible:Boolean){super.onVisibilityAggregated(isVisible);if(!isVisible)stop()}
}
