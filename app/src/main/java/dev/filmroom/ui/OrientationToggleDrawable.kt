package dev.filmroom.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Overlapping frame silhouettes; the front frame marks the explicit direction, even for 6×6. */
class OrientationToggleDrawable(private val portrait:Boolean,private val color:Int):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        style=Paint.Style.STROKE;strokeWidth=1.6f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    private var drawableAlpha=255
    override fun draw(canvas:Canvas){
        val save=canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        paint.color=color;paint.alpha=drawableAlpha
        // The muted rear frame is the alternative orientation; the bright front frame is current.
        val rear=if(portrait)RectF(3f,7f,16f,15f)else RectF(10f,3f,18f,16f)
        val front=if(portrait)RectF(10f,6f,18f,20f)else RectF(4f,10f,18f,18f)
        paint.alpha=drawableAlpha*120/255;canvas.drawRoundRect(rear,1.4f,1.4f,paint);paint.alpha=drawableAlpha
        canvas.drawRoundRect(front,1.4f,1.4f,paint)
        val arrows=Path().apply{
            moveTo(5f,3f);lineTo(2f,3f);lineTo(2f,6f)
            moveTo(19f,21f);lineTo(22f,21f);lineTo(22f,18f)
        }
        canvas.drawPath(arrows,paint);canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int){drawableAlpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
