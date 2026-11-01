package dev.filmroom.model

/** Final presentation only. Independent of PhotoView's fit/zoom/pan and of every film/AE input. */
data class CompositionTransform(val centerX:Double=.5,val centerY:Double=.5,val zoom:Double=1.0,val targetAspect:Double?=null) {
    init {require(centerX.isFinite() && centerX in 0.0..1.0 && centerY.isFinite() && centerY in 0.0..1.0);require(zoom.isFinite() && zoom in 1.0..16.0);require(targetAspect==null || (targetAspect.isFinite() && targetAspect in (1.0/30000.0)..30000.0))}
    fun json()=org.json.JSONObject().put("version",if(targetAspect==null)1 else 2).put("centerX",centerX).put("centerY",centerY).put("zoom",zoom).apply{targetAspect?.let{put("targetAspect",it)}}
    companion object {
        fun fromJson(j:org.json.JSONObject):CompositionTransform {
            val version=j.getInt("version");require(version in 1..2)
            require(version==2 || !j.has("targetAspect")){"Legacy composition cannot carry explicit aspect"}
            return CompositionTransform(j.getDouble("centerX"),j.getDouble("centerY"),j.getDouble("zoom"),if(version==2)j.getDouble("targetAspect")else null)
        }
    }
    data class Rect(val left:Double,val top:Double,val width:Double,val height:Double) {
        val right get()=left+width
        val bottom get()=top+height
    }
    fun aspect(canonicalW:Int,canonicalH:Int):Double {require(canonicalW>0 && canonicalH>0);return targetAspect ?: if(canonicalW>=canonicalH)1.5 else 2.0/3.0}
    private fun extent(canonicalW:Int,canonicalH:Int):Pair<Double,Double> {
        require(canonicalW>0 && canonicalH>0)
        val sourceAspect=canonicalW.toDouble()/canonicalH
        val apertureAspect=aspect(canonicalW,canonicalH)
        return minOf(1.0,apertureAspect/sourceAspect)/zoom to minOf(1.0,sourceAspect/apertureAspect)/zoom
    }
    fun clamped(canonicalW:Int,canonicalH:Int):CompositionTransform {
        val(w,h)=extent(canonicalW,canonicalH)
        return copy(centerX=centerX.coerceIn(w/2,1-w/2),centerY=centerY.coerceIn(h/2,1-h/2))
    }
    fun rect(canonicalW:Int,canonicalH:Int):Rect {
        val(w,h)=extent(canonicalW,canonicalH);val c=clamped(canonicalW,canonicalH)
        return Rect((c.centerX-w/2).coerceIn(0.0,1-w),(c.centerY-h/2).coerceIn(0.0,1-h),w,h)
    }
    fun pan(dx:Double,dy:Double,apertureScreenWidth:Double,apertureScreenHeight:Double,canonicalW:Int,canonicalH:Int):CompositionTransform {
        val c=clamped(canonicalW,canonicalH)
        if(!dx.isFinite() || !dy.isFinite() || !apertureScreenWidth.isFinite() || !apertureScreenHeight.isFinite() || apertureScreenWidth<=0 || apertureScreenHeight<=0)return c
        val r=c.rect(canonicalW,canonicalH)
        return c.copy(centerX=(c.centerX-dx/apertureScreenWidth*r.width).coerceIn(0.0,1.0),centerY=(c.centerY-dy/apertureScreenHeight*r.height).coerceIn(0.0,1.0)).clamped(canonicalW,canonicalH)
    }
    fun zoomBy(factor:Double,focusXFraction:Double,focusYFraction:Double,canonicalW:Int,canonicalH:Int):CompositionTransform {
        val c=clamped(canonicalW,canonicalH)
        if(!factor.isFinite() || factor<=0 || !focusXFraction.isFinite() || !focusYFraction.isFinite())return c
        val fx=focusXFraction.coerceIn(0.0,1.0);val fy=focusYFraction.coerceIn(0.0,1.0)
        val old=c.rect(canonicalW,canonicalH);val next=c.copy(zoom=(c.zoom*factor).coerceIn(1.0,16.0));val size=next.extent(canonicalW,canonicalH)
        val sourceX=old.left+fx*old.width;val sourceY=old.top+fy*old.height
        return next.copy(centerX=(sourceX+(.5-fx)*size.first).coerceIn(0.0,1.0),centerY=(sourceY+(.5-fy)*size.second).coerceIn(0.0,1.0)).clamped(canonicalW,canonicalH)
    }
}
