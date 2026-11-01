package dev.filmroom.model

import org.json.JSONObject

/** No-border presentation crop. Original is an original-aspect crop, identity by default. */
enum class CropMode(val aspect:Double?) {
    ORIGINAL(null), FREE(null), SQUARE(1.0), RATIO_4_3(4.0/3), RATIO_3_4(3.0/4),
    RATIO_3_2(1.5), RATIO_2_3(2.0/3), RATIO_16_9(16.0/9), RATIO_9_16(9.0/16)
}
data class CropSettings(val mode:CropMode=CropMode.ORIGINAL,val transform:CompositionTransform=CompositionTransform()) {
    fun effectiveTransform(w:Int,h:Int):CompositionTransform {
        require(w>0 && h>0)
        val aspect=mode.aspect ?: if(mode==CropMode.FREE)transform.targetAspect ?: w.toDouble()/h else w.toDouble()/h
        return transform.copy(targetAspect=aspect).clamped(w,h)
    }
    fun rect(w:Int,h:Int)=effectiveTransform(w,h).rect(w,h)
    fun isIdentity(w:Int,h:Int):Boolean {val r=rect(w,h);return r.left==0.0 && r.top==0.0 && r.width==1.0 && r.height==1.0}
    fun json()=JSONObject().put("version",1).put("mode",mode.name).put("transform",transform.json())
    companion object {
        fun fromJson(j:JSONObject):CropSettings {require(j.getInt("version")==1);return CropSettings(CropMode.valueOf(j.getString("mode")),CompositionTransform.fromJson(j.getJSONObject("transform")))}
        /** Exact normalized source rectangle; rejects invalid/too-small rectangles, never silently stretches. */
        fun fromRect(rect:CompositionTransform.Rect,w:Int,h:Int,mode:CropMode=CropMode.FREE):CropSettings {
            require(w>0 && h>0)
            require(listOf(rect.left,rect.top,rect.width,rect.height).all{it.isFinite()})
            require(rect.left>=0 && rect.top>=0 && rect.width>0 && rect.height>0 && rect.right<=1.0+1e-9 && rect.bottom<=1.0+1e-9)
            val aspect=rect.width*w/(rect.height*h)
            val requested=mode.aspect ?: if(mode==CropMode.ORIGINAL)w.toDouble()/h else aspect
            require(kotlin.math.abs(aspect/requested-1)<1e-8){"Rectangle does not match crop mode"}
            val coverWidth=minOf(1.0,aspect/(w.toDouble()/h))
            val zoom=coverWidth/rect.width
            require(zoom>=1.0-1e-9 && zoom<=16.0+1e-9){"Crop exceeds bounded 16x zoom"}
            return CropSettings(mode,CompositionTransform(rect.left+rect.width/2,rect.top+rect.height/2,zoom.coerceIn(1.0,16.0),aspect).clamped(w,h))
        }
    }
}
