package dev.filmroom.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import dev.filmroom.R
import dev.filmroom.model.CompositionTransform
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/** Relative horizontal wheel: a gesture moves 0.08 degrees per dp; no bitmap work on MOVE. */
class StraightenDialView(context:Context,private val ink:Int,private val muted:Int,private val accent:Int):View(context) {
    private val density=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var onAngleChanged:((Double,Boolean)->Unit)?=null
    var angle:Double=0.0
        set(value){field=value.coerceIn(-CompositionTransform.MAX_STRAIGHTEN,CompositionTransform.MAX_STRAIGHTEN);contentDescription=context.getString(R.string.composition_straighten_value,label());invalidate()}
    private var startX=0f
    private var startAngle=0.0
    private var dragging=false
    init{isFocusable=true;isClickable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;minimumHeight=maxOf(68*density,13*resources.displayMetrics.scaledDensity+55*density).toInt();angle=0.0}
    private fun label()=String.format(Locale.ROOT,if(angle==0.0)"%.1f°" else "%+.1f°",angle)
    private fun update(value:Double,commit:Boolean){
        if(!value.isFinite())return
        val rounded=round(value.coerceIn(-CompositionTransform.MAX_STRAIGHTEN,CompositionTransform.MAX_STRAIGHTEN)*10)/10
        angle=if(abs(rounded)<.05)0.0 else rounded
        onAngleChanged?.invoke(angle,commit)
    }
    override fun onMeasure(widthSpec:Int,heightSpec:Int){setMeasuredDimension(MeasureSpec.getSize(widthSpec),resolveSize(minimumHeight,heightSpec))}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);val cx=width/2f
        paint.color=ink;paint.alpha=if(isEnabled)255 else 100;paint.textAlign=Paint.Align.CENTER
        paint.textSize=13*resources.displayMetrics.scaledDensity
        val labelBaseline=maxOf(18*density,-paint.fontMetrics.ascent+3*density)
        canvas.drawText(context.getString(R.string.composition_straighten_value,label()),cx,labelBaseline,paint)
        val top=labelBaseline+12*density;val gap=9*density
        for(tick in -150..150){
            val x=cx+(tick-angle*10).toFloat()*gap
            if(x<12*density || x>width-12*density)continue
            val fade=(1-abs(x-cx)/(width/2f)).coerceIn(.12f,1f)
            paint.color=if(tick==0)accent else muted;paint.alpha=((if(isEnabled)220 else 90)*fade).toInt();paint.strokeWidth=density
            val major=tick%10==0;canvas.drawLine(x,top,x,top+(if(major)21 else if(tick%5==0)15 else 9)*density,paint)
        }
        paint.color=accent;paint.alpha=if(isEnabled)255 else 100;paint.strokeWidth=2*density
        canvas.drawLine(cx,top-3*density,cx,top+24*density,paint)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(!isEnabled)return false
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{dragging=true;startX=event.x;startAngle=angle;parent?.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_MOVE->if(dragging)update(startAngle+(event.x-startX)/density*.08,false)
            MotionEvent.ACTION_UP->{if(dragging){dragging=false;update(angle,true);performClick()};parent?.requestDisallowInterceptTouchEvent(false)}
            MotionEvent.ACTION_CANCEL->{if(dragging){dragging=false;update(startAngle,true)};parent?.requestDisallowInterceptTouchEvent(false)}
        };return true
    }
    override fun performClick():Boolean{super.performClick();return true}
    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        if(isEnabled && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)){update(angle+if(keyCode==KeyEvent.KEYCODE_DPAD_RIGHT).1 else -.1,true);return true}
        return super.onKeyDown(keyCode,event)
    }
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){
        super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.SeekBar"
        info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,-15f,15f,angle.toFloat())
        if(isEnabled){info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)}
    }
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean {
        if(isEnabled)when(action){
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD->{update(angle+1,true);return true}
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD->{update(angle-1,true);return true}
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id->{if(args?.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)!=true)return false;val value=args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);update(value.toDouble(),true);return true}
        }
        return super.performAccessibilityAction(action,args)
    }
}
