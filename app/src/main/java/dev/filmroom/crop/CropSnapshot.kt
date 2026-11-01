package dev.filmroom.crop

import dev.filmroom.model.CropSettings
import org.json.JSONObject
import kotlin.math.floor

/** Frozen final-presentation crop. Never an engine input or a resume checkpoint. */
data class CropSnapshot private constructor(
    val sourceWidth:Int,val sourceHeight:Int,val canonicalWidth:Int,val canonicalHeight:Int,
    val settings:CropSettings,val outputWidth:Int,val outputHeight:Int,val version:Int=1,
) {
    val sourceRect get()=settings.rect(canonicalWidth,canonicalHeight)
    val isIdentity get()=settings.isIdentity(canonicalWidth,canonicalHeight)
    val outputBytes get()=outputWidth.toLong()*outputHeight*12L
    init {
        require(version==1)
        for((w,h) in listOf(sourceWidth to sourceHeight,canonicalWidth to canonicalHeight,outputWidth to outputHeight))
            require(w in 1..30000 && h in 1..30000 && w.toLong()*h<=150_000_000L)
        val expected=dimensions(sourceWidth,sourceHeight,canonicalWidth,canonicalHeight,settings)
        require(outputWidth==expected.first && outputHeight==expected.second){"Crop dimensions do not match frozen source window"}
    }
    fun json()=JSONObject().put("version",version).put("sourceWidth",sourceWidth).put("sourceHeight",sourceHeight)
        .put("canonicalWidth",canonicalWidth).put("canonicalHeight",canonicalHeight).put("settings",settings.json())
        .put("outputWidth",outputWidth).put("outputHeight",outputHeight).toString()
    companion object {
        private fun dimensions(w:Int,h:Int,cw:Int,ch:Int,settings:CropSettings):Pair<Int,Int> {
            require(w>0 && h>0 && cw>0 && ch>0)
            if(settings.isIdentity(cw,ch))return w to h
            val rect=settings.rect(cw,ch);val aspect=settings.effectiveTransform(cw,ch).aspect(cw,ch)
            val height=minOf(w*rect.width/aspect,h*rect.height)
            // Fit the actual source-pixel crop window; never enlarge it to an export canvas.
            val outW=floor(height*aspect+1e-9).toInt();val outH=floor(height+1e-9).toInt()
            require(outW>=1 && outH>=1){"Crop is smaller than one output pixel"}
            return outW to outH
        }
        fun create(sourceWidth:Int,sourceHeight:Int,canonicalWidth:Int=sourceWidth,canonicalHeight:Int=sourceHeight,settings:CropSettings=CropSettings()):CropSnapshot {
            val(w,h)=dimensions(sourceWidth,sourceHeight,canonicalWidth,canonicalHeight,settings)
            return CropSnapshot(sourceWidth,sourceHeight,canonicalWidth,canonicalHeight,settings,w,h)
        }
        fun fromJson(text:String):CropSnapshot=JSONObject(text).let{j->
            require(j.getInt("version")==1)
            CropSnapshot(j.getInt("sourceWidth"),j.getInt("sourceHeight"),j.getInt("canonicalWidth"),j.getInt("canonicalHeight"),
                CropSettings.fromJson(j.getJSONObject("settings")),j.getInt("outputWidth"),j.getInt("outputHeight"),j.getInt("version"))
        }
    }
}
