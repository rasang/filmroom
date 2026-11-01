package dev.filmroom.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Small code-native symbols: proportions describe the crop, not a generic repeated glyph. */
class CropAspectDrawable(private val aspect:Double,private val kind:String,private val color:Int):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    override fun draw(canvas:Canvas){
        val save=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        paint.color=color;paint.strokeWidth=1.6f
        val ratio=aspect.coerceIn(.25,4.0).toFloat()
        val w=if(ratio>=1f)19f else 19f*ratio;val h=if(ratio>=1f)19f/ratio else 19f
        val r=RectF(12f-w/2,12f-h/2,12f+w/2,12f+h/2)
        if(kind=="free"){
            val corners=Path().apply{
                moveTo(4f,9f);lineTo(4f,4f);lineTo(9f,4f)
                moveTo(15f,4f);lineTo(20f,4f);lineTo(20f,9f)
                moveTo(20f,15f);lineTo(20f,20f);lineTo(15f,20f)
                moveTo(9f,20f);lineTo(4f,20f);lineTo(4f,15f)
                moveTo(8f,16f);lineTo(16f,8f);moveTo(12f,8f);lineTo(16f,8f);lineTo(16f,12f)
            };canvas.drawPath(corners,paint)
        }else{
            canvas.drawRoundRect(r,1.4f,1.4f,paint)
            if(kind=="original"){
                val landscape=Path().apply{moveTo(r.left+3,r.bottom-3);lineTo(11f,11f);lineTo(14f,14f);lineTo(r.right-3,r.bottom-3)}
                canvas.drawPath(landscape,paint)
                canvas.drawCircle(r.right-4,r.top+4,1f,paint)
            }
        }
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
