package dev.filmroom.border

import dev.filmroom.model.CompositionTransform
import kotlin.math.floor

/** Shared final-presentation pixel-center map and bilinear float math for border/plain crop. */
internal open class FinalImageSampling(
    private val sourceWidth:Int,private val sourceHeight:Int,outputWidth:Int,
    private val crop:CompositionTransform.Rect,
    private val apertureLeft:Double,private val apertureTop:Double,
    private val apertureWidth:Int,private val apertureHeight:Int,private val featherRadius:Double=0.0,
    private val repeatX:Double=0.0,private val repeatY:Double=0.0,private val allRows:Boolean=false,
) {
    val x0=IntArray(outputWidth)
    val fraction=DoubleArray(outputWidth)
    private fun snap(v:Double):Double {val n=kotlin.math.round(v);return if(kotlin.math.abs(v-n)<1e-9)n else v}
    private fun repeat(v:Double,pitch:Double,extent:Int):Double=if(pitch>0)v-kotlin.math.round((v-extent/2.0)/pitch)*pitch else v
    init {
        for(x in x0.indices){
            val value=snap(((crop.left+repeat(x+.5-apertureLeft,repeatX,apertureWidth)/apertureWidth*crop.width)*sourceWidth-.5).coerceIn(0.0,sourceWidth-1.0))
            x0[x]=floor(value).toInt();fraction[x]=value-x0[x]
        }
    }
    data class Row(val first:Int,val second:Int,val fraction:Double)
    fun row(y:Int):Row {
        val value=snap(((crop.top+repeat(y+.5-apertureTop,repeatY,apertureHeight)/apertureHeight*crop.height)*sourceHeight-.5).coerceIn(0.0,sourceHeight-1.0))
        val first=floor(value).toInt()
        return Row(first,minOf(first+1,sourceHeight-1),value-first)
    }
    fun rowHasPhoto(y:Int):Boolean=allRows || minOf(y+.5-apertureTop,apertureTop+apertureHeight-y-.5)>=-featherRadius
    fun sample(first:FloatArray,second:FloatArray,fy:Double,x:Int,channel:Int):Double {
        val sx=x0[x];val next=minOf(sx+1,sourceWidth-1);val fx=fraction[x]
        val upper=if(fx==0.0)first[sx*3+channel].toDouble()else first[sx*3+channel]*(1-fx)+first[next*3+channel]*fx
        if(fy==0.0)return upper
        val lower=if(fx==0.0)second[sx*3+channel].toDouble()else second[sx*3+channel]*(1-fx)+second[next*3+channel]*fx
        return upper*(1-fy)+lower*fy
    }
}
internal class BorderSampling(val s:BorderSnapshot):FinalImageSampling(s.sourceWidth,s.sourceHeight,s.outputWidth,s.sourceRect,
    s.apertureLeft,s.apertureTop,s.photoWidth,s.photoHeight,s.featherRadius,
    if(s.portrait)(s.layout?.pitch ?: 0.0)*s.pixelsPerMm else 0.0,
    if(!s.portrait)(s.layout?.pitch ?: 0.0)*s.pixelsPerMm else 0.0,s.geometryVersion==4)
