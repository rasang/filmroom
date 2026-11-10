package dev.filmroom.effects

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.*
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.filmroom.ui.FilmroomPalette as P

/** Native vector icons: never font glyphs, with a consistent optical centre. */
class EffectControlIcon(private val kind:String, private val ink:Int):Drawable(){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c:Canvas){
        c.save();c.translate(bounds.left.toFloat(),bounds.top.toFloat());c.scale(bounds.width()/24f,bounds.height()/24f)
        paint.color=ink;paint.strokeWidth=1.8f;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND;paint.style=Paint.Style.STROKE
        when(kind){
            "back"->{c.drawLine(19f,12f,5f,12f,paint);c.drawPath(Path().apply{moveTo(11f,6f);lineTo(5f,12f);lineTo(11f,18f)},paint)}
            "replay"->{c.drawArc(4f,4f,20f,20f,-130f,310f,false,paint);c.drawPath(Path().apply{moveTo(4f,3f);lineTo(4f,9f);lineTo(10f,9f)},paint)}
            "live"->{c.drawCircle(12f,12f,9.5f,paint);c.drawCircle(12f,12f,6.5f,paint);paint.style=Paint.Style.FILL;c.drawCircle(12f,12f,3.5f,paint)}
            "video"->{c.drawRoundRect(2f,5f,22f,19f,2.5f,2.5f,paint);paint.style=Paint.Style.FILL;c.drawPath(Path().apply{moveTo(10f,9f);lineTo(15f,12f);lineTo(10f,15f);close()},paint)}
            else->{paint.style=Paint.Style.FILL;c.drawPath(Path().apply{moveTo(8f,5f);quadTo(7f,5f,7f,6f);lineTo(7f,18f);quadTo(7f,19f,8f,19f);lineTo(19f,12f);close()},paint)}
        };c.restore()
    }
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter}
    override fun getOpacity()=PixelFormat.TRANSLUCENT
}
object EffectControls {
    fun setPrimary(button:Button,icon:String,primary:Boolean){
        val c=button.context;fun dp(n:Int)=(n*c.resources.displayMetrics.density+.5f).toInt()
        val start=button.paddingStart;val top=button.paddingTop;val end=button.paddingEnd;val bottom=button.paddingBottom
        val ink=if(primary)P.onAccent else P.ink
        button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf((ink and 0xffffff) or 0x66000000,ink)))
        button.backgroundTintList=null
        button.background=RippleDrawable(ColorStateList.valueOf(0x30ffffff),GradientDrawable().apply{setColor(if(primary)P.accent else P.raised);cornerRadius=dp(14).toFloat()},null)
        button.compoundDrawableTintList=ColorStateList.valueOf(ink)
        button.setCompoundDrawablesRelative(EffectControlIcon(icon,ink).apply{setBounds(0,0,dp(18),dp(18))},null,null,null)
        button.setPaddingRelative(start,top,end,bottom)
    }
    /** Visual unavailability only: retain enabled/click listener so the tap can explain why. */
    fun setLiveAvailability(button:Button, primary:Boolean, deviceBlocked:Boolean) {
        setPrimary(button,"live",primary && !deviceBlocked)
        button.alpha=if(deviceBlocked) .45f else 1f
    }
    /** A whole-button touch target with its switch embedded, matching the quality button. */
    fun audioToggle(c:Context,label:String):android.widget.Switch {
        fun dp(n:Int)=(n*c.resources.displayMetrics.density+.5f).toInt()
        return android.widget.Switch(c).apply {
            text=label;contentDescription=label;textSize=13f;setTextColor(P.ink)
            gravity=android.view.Gravity.CENTER_VERTICAL
            minWidth=0;minimumWidth=0;minHeight=dp(56);minimumHeight=dp(56)
            setPadding(dp(12),dp(12),dp(12),dp(12));switchPadding=dp(8)
            backgroundTintList=null
            background=RippleDrawable(ColorStateList.valueOf(0x30ffffff),GradientDrawable().apply{setColor(P.raised);cornerRadius=dp(14).toFloat()},null)
        }
    }
    fun button(c:Context,label:String,icon:String,primary:Boolean=false,action:()->Unit):Button {
        fun dp(n:Int)=(n*c.resources.displayMetrics.density+.5f).toInt()
        val ink=if(primary)P.onAccent else P.ink
        val fill=if(primary)P.accent else P.raised
        return Button(c).apply{
            text=label;contentDescription=label;isAllCaps=false;textSize=13f;gravity=android.view.Gravity.CENTER
            minWidth=0;minimumWidth=0;minHeight=dp(56);minimumHeight=dp(56)
            setPadding(dp(12),dp(12),dp(12),dp(12));setTextColor(ink)
            backgroundTintList=null
            background=RippleDrawable(ColorStateList.valueOf(0x30ffffff),GradientDrawable().apply{setColor(fill);cornerRadius=dp(14).toFloat()},null)
            setCompoundDrawablesRelative(EffectControlIcon(icon,ink).apply{setBounds(0,0,dp(18),dp(18))},null,null,null)
            setPrimary(this,icon,primary);setPaddingRelative(dp(12),dp(12),dp(12),dp(12));compoundDrawablePadding=dp(6);setOnClickListener{action()}
        }
    }
}
/** Keeps icon/text horizontal. At large accessibility sizes whole buttons wrap, never clip. */
class EffectActionBar(c:Context):ViewGroup(c){
    private val gap=(8*c.resources.displayMetrics.density+.5f).toInt()
    private val rows=mutableListOf<List<View>>()
    override fun onMeasure(ws:Int,hs:Int){
        val available=(MeasureSpec.getSize(ws)-paddingLeft-paddingRight).coerceAtLeast(1)
        rows.clear();var row=mutableListOf<View>();var used=0
        for(i in 0 until childCount){val v=getChildAt(i);if(v.visibility==GONE)continue
            v.measure(MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
            val needed=v.measuredWidth+(if(row.isEmpty())0 else gap)
            if(row.isNotEmpty()&&used+needed>available){rows.add(row);row=mutableListOf();used=0}
            if(row.isNotEmpty())used+=gap;used+=v.measuredWidth;row.add(v)
        };if(row.isNotEmpty())rows.add(row)
        var height=paddingTop+paddingBottom
        for(line in rows){val extra=(available-line.sumOf{it.measuredWidth}-gap*(line.size-1))/line.size;val h=line.maxOf{it.measuredHeight}
            line.forEach{it.measure(MeasureSpec.makeMeasureSpec(it.measuredWidth+extra,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))};height+=h
        };height+=gap*(rows.size-1).coerceAtLeast(0)
        setMeasuredDimension(resolveSize(available+paddingLeft+paddingRight,ws),resolveSize(height,hs))
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){var y=paddingTop;for(line in rows){var x=paddingLeft;for(v in line){v.layout(x,y,x+v.measuredWidth,y+v.measuredHeight);x+=v.measuredWidth+gap};y+=line.maxOf{it.measuredHeight}+gap}}
}
