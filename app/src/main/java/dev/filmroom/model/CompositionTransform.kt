package dev.filmroom.model

import kotlin.math.*

/** Final presentation only. Independent of viewer zoom/pan and of every film/AE input. */
data class CompositionTransform(val centerX:Double=.5,val centerY:Double=.5,val zoom:Double=1.0,val targetAspect:Double?=null,val straightenDegrees:Double=0.0) {
    init {
        require(centerX.isFinite() && centerX in 0.0..1.0 && centerY.isFinite() && centerY in 0.0..1.0)
        require(zoom.isFinite() && zoom in 1.0..16.0)
        require(targetAspect==null || (targetAspect.isFinite() && targetAspect in (1.0/30000.0)..30000.0))
        require(straightenDegrees.isFinite() && straightenDegrees in -MAX_STRAIGHTEN..MAX_STRAIGHTEN)
    }
    // Identity keeps the historical bytes/default fields. New readers never silently discard an angle.
    fun json()=org.json.JSONObject().put("version",if(straightenDegrees!=0.0)3 else if(targetAspect==null)1 else 2)
        .put("centerX",centerX).put("centerY",centerY).put("zoom",zoom).apply{
            targetAspect?.let{put("targetAspect",it)};if(straightenDegrees!=0.0)put("straightenDegrees",straightenDegrees)
        }
    companion object {
        const val MAX_STRAIGHTEN=15.0
        fun fromJson(j:org.json.JSONObject):CompositionTransform {
            val version=j.getInt("version");require(version in 1..3)
            require(version!=1 || !j.has("targetAspect")){"Legacy composition cannot carry explicit aspect"}
            val angle=if(j.has("straightenDegrees"))j.getDouble("straightenDegrees")else 0.0
            require(version==3 || angle==0.0){"Legacy composition cannot carry rotation"}
            require(version!=3 || j.has("straightenDegrees"))
            return CompositionTransform(j.getDouble("centerX"),j.getDouble("centerY"),j.getDouble("zoom"),
                if(version==2 || (version==3 && j.has("targetAspect")))j.getDouble("targetAspect")else null,angle)
        }
    }
    data class Rect(val left:Double,val top:Double,val width:Double,val height:Double) {
        val right get()=left+width
        val bottom get()=top+height
    }
    fun aspect(canonicalW:Int,canonicalH:Int):Double {require(canonicalW>0 && canonicalH>0);return targetAspect ?: if(canonicalW>=canonicalH)1.5 else 2.0/3.0}
    /** Automatic cover zoom is independent of the user's zoom, so returning to zero restores scale. */
    fun rotationCover(canonicalW:Int,canonicalH:Int):Double {
        val sourceAspect=canonicalW.toDouble()/canonicalH
        val a=aspect(canonicalW,canonicalH)
        val w=minOf(1.0,a/sourceAspect);val h=minOf(1.0,sourceAspect/a)
        val radians=Math.toRadians(straightenDegrees);val c=abs(cos(radians));val s=abs(sin(radians))
        return maxOf(1.0,c*w+s*h/sourceAspect,c*h+s*w*sourceAspect)
    }
    private fun extent(canonicalW:Int,canonicalH:Int):Pair<Double,Double> {
        require(canonicalW>0 && canonicalH>0)
        val sourceAspect=canonicalW.toDouble()/canonicalH;val apertureAspect=aspect(canonicalW,canonicalH)
        val scale=zoom*rotationCover(canonicalW,canonicalH)
        return minOf(1.0,apertureAspect/sourceAspect)/scale to minOf(1.0,sourceAspect/apertureAspect)/scale
    }
    private fun sourceOffset(u:Double,v:Double,w:Double,h:Double,canonicalW:Int,canonicalH:Int):Pair<Double,Double> {
        val a=canonicalW.toDouble()/canonicalH;val theta=Math.toRadians(straightenDegrees);val c=cos(theta);val s=sin(theta)
        return (c*u*w+s*v*h/a) to (-s*u*w*a+c*v*h)
    }
    fun clamped(canonicalW:Int,canonicalH:Int):CompositionTransform {
        val(w,h)=extent(canonicalW,canonicalH);val a=canonicalW.toDouble()/canonicalH
        val theta=Math.toRadians(straightenDegrees);val c=abs(cos(theta));val s=abs(sin(theta))
        val halfW=((c*w+s*h/a)/2).coerceAtMost(.5);val halfH=((s*w*a+c*h)/2).coerceAtMost(.5)
        return copy(centerX=centerX.coerceIn(halfW,1-halfW),centerY=centerY.coerceIn(halfH,1-halfH))
    }
    /** The unrotated local crop window. For source coverage use sourcePoint/sourceBounds. */
    fun rect(canonicalW:Int,canonicalH:Int):Rect {
        val(w,h)=extent(canonicalW,canonicalH);val c=clamped(canonicalW,canonicalH)
        return Rect((c.centerX-w/2).coerceIn(0.0,1-w),(c.centerY-h/2).coerceIn(0.0,1-h),w,h)
    }
    fun sourcePoint(u:Double,v:Double,canonicalW:Int,canonicalH:Int):Pair<Double,Double> {
        val c=clamped(canonicalW,canonicalH);val(w,h)=extent(canonicalW,canonicalH)
        val(dx,dy)=sourceOffset(u-.5,v-.5,w,h,canonicalW,canonicalH)
        return c.centerX+dx to c.centerY+dy
    }
    fun sourceBounds(canonicalW:Int,canonicalH:Int):Rect {
        val points=listOf(sourcePoint(0.0,0.0,canonicalW,canonicalH),sourcePoint(1.0,0.0,canonicalW,canonicalH),sourcePoint(0.0,1.0,canonicalW,canonicalH),sourcePoint(1.0,1.0,canonicalW,canonicalH))
        val l=points.minOf{it.first}.coerceAtLeast(0.0);val t=points.minOf{it.second}.coerceAtLeast(0.0)
        return Rect(l,t,points.maxOf{it.first}.coerceAtMost(1.0)-l,points.maxOf{it.second}.coerceAtMost(1.0)-t)
    }
    fun pan(dx:Double,dy:Double,apertureScreenWidth:Double,apertureScreenHeight:Double,canonicalW:Int,canonicalH:Int):CompositionTransform {
        val c=clamped(canonicalW,canonicalH)
        if(!dx.isFinite() || !dy.isFinite() || !apertureScreenWidth.isFinite() || !apertureScreenHeight.isFinite() || apertureScreenWidth<=0 || apertureScreenHeight<=0)return c
        val r=c.rect(canonicalW,canonicalH);val(ox,oy)=sourceOffset(dx/apertureScreenWidth,dy/apertureScreenHeight,r.width,r.height,canonicalW,canonicalH)
        return c.copy(centerX=(c.centerX-ox).coerceIn(0.0,1.0),centerY=(c.centerY-oy).coerceIn(0.0,1.0)).clamped(canonicalW,canonicalH)
    }
    fun zoomBy(factor:Double,focusXFraction:Double,focusYFraction:Double,canonicalW:Int,canonicalH:Int):CompositionTransform {
        val c=clamped(canonicalW,canonicalH)
        if(!factor.isFinite() || factor<=0 || !focusXFraction.isFinite() || !focusYFraction.isFinite())return c
        val fx=focusXFraction.coerceIn(0.0,1.0);val fy=focusYFraction.coerceIn(0.0,1.0)
        val source=c.sourcePoint(fx,fy,canonicalW,canonicalH);val next=c.copy(zoom=(c.zoom*factor).coerceIn(1.0,16.0));val(w,h)=next.extent(canonicalW,canonicalH)
        val(dx,dy)=sourceOffset(fx-.5,fy-.5,w,h,canonicalW,canonicalH)
        return next.copy(centerX=(source.first-dx).coerceIn(0.0,1.0),centerY=(source.second-dy).coerceIn(0.0,1.0)).clamped(canonicalW,canonicalH)
    }
}
