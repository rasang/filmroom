package dev.filmroom.border

import dev.filmroom.model.CompositionTransform
import org.json.JSONObject
import kotlin.math.*

/** Frozen output contract. v1 is the historical uncropped prototype; v2 is fixed 135 composition. */
data class BorderSnapshot(
    val stock:String,val assetVersion:String,val geometryVersion:Int=1,
    val photoWidth:Int,val photoHeight:Int,val rail:Int,val end:Int,
    val featherTotalAt3072:Double=4.0,
    val sourceWidth:Int=photoWidth,val sourceHeight:Int=photoHeight,
    val canonicalWidth:Int=sourceWidth,val canonicalHeight:Int=sourceHeight,
    val composition:CompositionTransform=CompositionTransform(),
    val appearance:FrameAppearanceContext?=null,
) {
    val portrait get()=if(geometryVersion==1)photoHeight>photoWidth else if(geometryVersion==3)composition.targetAspect!!<1.0 else canonicalHeight>canonicalWidth
    /** Legacy integer margins; new rendering/UI uses apertureLeft/apertureTop instead. */
    val left get()=if(portrait)rail else end
    val top get()=if(portrait)end else rail
    val pixelsPerMm get()=minOf(photoWidth,photoHeight)/24.0
    val frameWidthMm get()=if(portrait)35.0 else 38.0
    val frameHeightMm get()=if(portrait)38.0 else 35.0
    val outputWidth get()=if(geometryVersion==1)photoWidth+2*left else ceil(frameWidthMm*pixelsPerMm).toInt()
    val outputHeight get()=if(geometryVersion==1)photoHeight+2*top else ceil(frameHeightMm*pixelsPerMm).toInt()
    val frameOriginX get()=if(geometryVersion==1)0.0 else (outputWidth-frameWidthMm*pixelsPerMm)/2
    val frameOriginY get()=if(geometryVersion==1)0.0 else (outputHeight-frameHeightMm*pixelsPerMm)/2
    val apertureLeft get()=if(geometryVersion==1)left.toDouble() else frameOriginX+(if(portrait)5.5 else 1.0)*pixelsPerMm
    val apertureTop get()=if(geometryVersion==1)top.toDouble() else frameOriginY+(if(portrait)1.0 else 5.5)*pixelsPerMm
    val sourceRect get()=if(geometryVersion==1)CompositionTransform.Rect(0.0,0.0,1.0,1.0) else composition.rect(canonicalWidth,canonicalHeight)
    val outputBytes get()=outputWidth.toLong()*outputHeight*12L
    val featherRadius get()=minOf(photoWidth,photoHeight)*(featherTotalAt3072/2.0/3072.0)
    init {
        require(stock.matches(Regex("[a-z0-9_]{1,80}")) && assetVersion.matches(Regex("[a-zA-Z0-9_.-]{1,96}")))
        appearance?.let { require(geometryVersion>=2);require(it.filmKind==FrameProfileCatalog.kind(stock)) }
        require(geometryVersion in 1..3)
        require(if(geometryVersion==3)composition.targetAspect==1.5 || composition.targetAspect==2.0/3.0 else composition.targetAspect==null)
        require(featherTotalAt3072.isFinite() && featherTotalAt3072 in 0.0..16.0)
        require(photoWidth in 1..30000 && photoHeight in 1..30000)
        require(sourceWidth in 1..30000 && sourceHeight in 1..30000 && sourceWidth.toLong()*sourceHeight<=150_000_000L)
        require(canonicalWidth in 1..30000 && canonicalHeight in 1..30000 && canonicalWidth.toLong()*canonicalHeight<=150_000_000L)
        require(rail in 1..7500 && end in 1..7500)
        if(geometryVersion==1){
            require(sourceWidth==photoWidth && sourceHeight==photoHeight)
            require(rail<=ceil(minOf(photoWidth,photoHeight)*.23).toInt().coerceAtLeast(1))
            require(end<=ceil(minOf(photoWidth,photoHeight)*.04).toInt().coerceAtLeast(1))
        }else{
            require(if(portrait)photoWidth%2==0 && photoHeight%3==0 && photoWidth/2==photoHeight/3 else photoWidth%3==0 && photoHeight%2==0 && photoWidth/3==photoHeight/2)
            require(composition==composition.clamped(canonicalWidth,canonicalHeight))
        }
        require(outputWidth in 1..30000 && outputHeight in 1..30000 && outputWidth.toLong()*outputHeight<=150_000_000L)
    }
    fun json()=JSONObject().apply{
        put("stock",stock);put("assetVersion",assetVersion);put("geometryVersion",geometryVersion)
        appearance?.let { put("appearance",it.json()) }
        put("photoWidth",photoWidth);put("photoHeight",photoHeight);put("rail",rail);put("end",end)
        put("frame","25/25A");put("featherTotalAt3072",featherTotalAt3072)
        if(geometryVersion>=2){
            put("sourceWidth",sourceWidth);put("sourceHeight",sourceHeight);put("canonicalWidth",canonicalWidth);put("canonicalHeight",canonicalHeight)
            put("centerX",composition.centerX);put("centerY",composition.centerY);put("zoom",composition.zoom)
            if(geometryVersion==3)put("targetAspect",composition.targetAspect)
        }
    }.toString()
    companion object {
        fun fromJson(text:String):BorderSnapshot=JSONObject(text).let{j->
            require(j.getString("frame")=="25/25A")
            val version=j.getInt("geometryVersion");require(version==3 || !j.has("targetAspect"));val pw=j.getInt("photoWidth");val ph=j.getInt("photoHeight")
            BorderSnapshot(j.getString("stock"),j.getString("assetVersion"),version,pw,ph,j.getInt("rail"),j.getInt("end"),j.getDouble("featherTotalAt3072"),
                if(version>=2)j.getInt("sourceWidth")else pw,if(version>=2)j.getInt("sourceHeight")else ph,
                if(version>=2)j.getInt("canonicalWidth")else pw,if(version>=2)j.getInt("canonicalHeight")else ph,
                if(version>=2)CompositionTransform(j.getDouble("centerX"),j.getDouble("centerY"),j.getDouble("zoom"),if(version==3)j.getDouble("targetAspect")else null)else CompositionTransform(),
                if(j.has("appearance"))FrameAppearanceContext.fromJson(j.getJSONObject("appearance"))else null)
        }
        /** Historical v1 fixture/migration path. New editor captures must use fixed135. */
        fun create(stock:String,version:String,width:Int,height:Int,railRatio:Double,endRatio:Double,featherTotalAt3072:Double):BorderSnapshot {
            require(railRatio.isFinite() && railRatio in .001..0.23 && endRatio.isFinite() && endRatio in .001..0.04)
            val short=minOf(width,height)
            return BorderSnapshot(stock,version,photoWidth=width,photoHeight=height,rail=(short*railRatio).roundToInt().coerceAtLeast(1),end=(short*endRatio).roundToInt().coerceAtLeast(1),featherTotalAt3072=featherTotalAt3072)
        }
        /** Fixed physical frame: nominal 38×35 crop, 36×24 aperture. One uniform scale, no slicing. */
        fun fixed135(stock:String,version:String,sourceWidth:Int,sourceHeight:Int,canonicalWidth:Int=sourceWidth,canonicalHeight:Int=sourceHeight,composition:CompositionTransform=CompositionTransform(),featherTotalAt3072:Double=4.0):BorderSnapshot {
            val aspect=composition.targetAspect
            require(aspect==null || aspect==1.5 || aspect==2.0/3.0)
            val portrait=aspect?.let{it<1.0} ?: (canonicalHeight>canonicalWidth)
            val unit=if(portrait)minOf(sourceWidth/2,sourceHeight/3).coerceAtLeast(1)else minOf(sourceWidth/3,sourceHeight/2).coerceAtLeast(1)
            val pw=unit*(if(portrait)2 else 3);val ph=unit*(if(portrait)3 else 2);val short=minOf(pw,ph)
            return BorderSnapshot(stock,version,if(aspect==null)2 else 3,pw,ph,(short*5.5/24).roundToInt().coerceAtLeast(1),(short/24.0).roundToInt().coerceAtLeast(1),featherTotalAt3072,sourceWidth,sourceHeight,canonicalWidth,canonicalHeight,composition.clamped(canonicalWidth,canonicalHeight))
        }
    }
}
