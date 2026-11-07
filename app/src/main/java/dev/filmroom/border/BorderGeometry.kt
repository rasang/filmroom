package dev.filmroom.border

import dev.filmroom.model.CompositionTransform
import org.json.JSONObject
import kotlin.math.*

/** Frozen output contract. v1 legacy uncropped, v2/v3 fixed 135; v4 original BW layouts; v5 nominal 120 continuous strips.
 * The legacy *Mm accessors use authored atlas-coordinate units for v4, not physical millimetres. */
data class BorderSnapshot(
    val stock:String,val assetVersion:String,val geometryVersion:Int=1,
    val photoWidth:Int,val photoHeight:Int,val rail:Int,val end:Int,
    val featherTotalAt3072:Double=4.0,
    val sourceWidth:Int=photoWidth,val sourceHeight:Int=photoHeight,
    val canonicalWidth:Int=sourceWidth,val canonicalHeight:Int=sourceHeight,
    val composition:CompositionTransform=CompositionTransform(),
    val appearance:FrameAppearanceContext?=null,
    val mediumFormat:MediumFormat?=null,
    val mediumPortrait:Boolean=false,
    val stripCenterMm:Double?=null,
) {
    internal val mediumLayout=mediumFormat?.geometry(mediumPortrait)
    internal val layout get()=if(geometryVersion==4)BwFrameGeometry.forStock(stock) else null
    val portrait get()=if(geometryVersion==5)mediumPortrait else if(geometryVersion==1)photoHeight>photoWidth else if(geometryVersion>=3)composition.targetAspect!!<1.0 else canonicalHeight>canonicalWidth
    /** Legacy integer margins; new rendering/UI uses apertureLeft/apertureTop instead. */
    val left get()=if(geometryVersion==5)(mediumLayout!!.left*pixelsPerMm).roundToInt() else if(portrait)rail else end
    val top get()=if(geometryVersion==5)(mediumLayout!!.top*pixelsPerMm).roundToInt() else if(portrait)end else rail
    val pixelsPerMm get()=mediumLayout?.let{photoWidth/it.apertureWidth} ?: layout?.let{photoWidth/(if(portrait)it.apertureHeight else it.apertureWidth)} ?: (minOf(photoWidth,photoHeight)/24.0)
    val frameWidthMm get()=mediumLayout?.width ?: layout?.let{if(portrait)it.height else it.width} ?: (if(portrait)35.0 else 38.0)
    val frameHeightMm get()=mediumLayout?.height ?: layout?.let{if(portrait)it.width else it.height} ?: (if(portrait)38.0 else 35.0)
    val outputWidth get()=if(geometryVersion==1)photoWidth+2*left else ceil(frameWidthMm*pixelsPerMm).toInt()
    val outputHeight get()=if(geometryVersion==1)photoHeight+2*top else ceil(frameHeightMm*pixelsPerMm).toInt()
    val frameOriginX get()=if(geometryVersion==1)0.0 else (outputWidth-frameWidthMm*pixelsPerMm)/2
    val frameOriginY get()=if(geometryVersion==1)0.0 else (outputHeight-frameHeightMm*pixelsPerMm)/2
    val apertureLeft get()=if(geometryVersion==1)left.toDouble() else frameOriginX+(mediumLayout?.left ?: layout?.let{if(portrait)it.height-it.bottom else it.left} ?: (if(portrait)5.5 else 1.0))*pixelsPerMm
    val apertureTop get()=if(geometryVersion==1)top.toDouble() else frameOriginY+(mediumLayout?.top ?: layout?.let{if(portrait)it.left else it.top} ?: (if(portrait)1.0 else 5.5))*pixelsPerMm
    val sourceRect get()=if(geometryVersion==1)CompositionTransform.Rect(0.0,0.0,1.0,1.0) else composition.rect(canonicalWidth,canonicalHeight)
    val outputBytes get()=outputWidth.toLong()*outputHeight*12L
    val featherRadius get()=minOf(photoWidth,photoHeight)*(featherTotalAt3072/2.0/3072.0)
    init {
        require(stock.matches(Regex("[a-z0-9_]{1,80}")) && assetVersion.matches(Regex("[a-zA-Z0-9_.-]{1,96}")))
        appearance?.let { require(geometryVersion>=2);require(it.filmKind==FrameProfileCatalog.kind(stock)) }
        require(geometryVersion in 1..5)
        require((geometryVersion==5)==(mediumFormat!=null))
        require(if(geometryVersion==5) stripCenterMm!=null && stripCenterMm.isFinite() && stripCenterMm in 87.0..733.0 else stripCenterMm==null && !mediumPortrait)
        if(geometryVersion==5)require(MediumFormatStocks.available(stock))
        require(geometryVersion!=4 || layout!=null){"Unknown authored frame geometry"}
        require(if(geometryVersion==5)composition.targetAspect==mediumLayout!!.aspect else if(geometryVersion==4)composition.targetAspect==layout!!.aspect || composition.targetAspect==1/layout!!.aspect else if(geometryVersion==3)composition.targetAspect==1.5 || composition.targetAspect==2.0/3.0 else composition.targetAspect==null)
        require(featherTotalAt3072.isFinite() && featherTotalAt3072 in 0.0..16.0)
        require(photoWidth in 1..30000 && photoHeight in 1..30000)
        require(sourceWidth in 1..30000 && sourceHeight in 1..30000 && sourceWidth.toLong()*sourceHeight<=150_000_000L)
        require(canonicalWidth in 1..30000 && canonicalHeight in 1..30000 && canonicalWidth.toLong()*canonicalHeight<=150_000_000L)
        require(rail in 1..7500 && end in 1..7500)
        if(geometryVersion==1){
            require(composition.straightenDegrees==0.0)
            require(sourceWidth==photoWidth && sourceHeight==photoHeight)
            require(rail<=ceil(minOf(photoWidth,photoHeight)*.23).toInt().coerceAtLeast(1))
            require(end<=ceil(minOf(photoWidth,photoHeight)*.04).toInt().coerceAtLeast(1))
        }else{
            if(geometryVersion in 2..3)require(if(portrait)photoWidth%2==0 && photoHeight%3==0 && photoWidth/2==photoHeight/3 else photoWidth%3==0 && photoHeight%2==0 && photoWidth/3==photoHeight/2)
            require(composition==composition.clamped(canonicalWidth,canonicalHeight))
        }
        require(outputWidth in 1..30000 && outputHeight in 1..30000 && outputWidth.toLong()*outputHeight<=150_000_000L)
    }
    fun json()=JSONObject().apply{
        put("stock",stock);put("assetVersion",assetVersion);put("geometryVersion",geometryVersion)
        appearance?.let { put("appearance",it.json()) }
        put("photoWidth",photoWidth);put("photoHeight",photoHeight);put("rail",rail);put("end",end)
        if(geometryVersion==5){put("mediumFormat",mediumFormat!!.name);put("mediumPortrait",mediumPortrait);put("stripCenterMm",stripCenterMm)}
        put("frame",if(geometryVersion==5)"synthetic-120-reference-v1" else if(geometryVersion==4)"reviewed-reference" else "25/25A");put("featherTotalAt3072",featherTotalAt3072)
        if(geometryVersion>=2){
            put("sourceWidth",sourceWidth);put("sourceHeight",sourceHeight);put("canonicalWidth",canonicalWidth);put("canonicalHeight",canonicalHeight)
            if(composition.straightenDegrees!=0.0)put("straightenDegrees",composition.straightenDegrees)
            put("centerX",composition.centerX);put("centerY",composition.centerY);put("zoom",composition.zoom)
            if(geometryVersion>=3)put("targetAspect",composition.targetAspect)
        }
    }.toString()
    companion object {
        fun fromJson(text:String):BorderSnapshot=JSONObject(text).let{j->
            require(j.getString("frame")==if(j.getInt("geometryVersion")==5)"synthetic-120-reference-v1" else if(j.getInt("geometryVersion")==4)"reviewed-reference" else "25/25A")
            val version=j.getInt("geometryVersion");require(version>=2 || !j.has("straightenDegrees"));require(version==5 || (!j.has("mediumFormat") && !j.has("mediumPortrait") && !j.has("stripCenterMm")));require(version>=3 || !j.has("targetAspect"));val pw=j.getInt("photoWidth");val ph=j.getInt("photoHeight")
            BorderSnapshot(j.getString("stock"),j.getString("assetVersion"),version,pw,ph,j.getInt("rail"),j.getInt("end"),j.getDouble("featherTotalAt3072"),
                if(version>=2)j.getInt("sourceWidth")else pw,if(version>=2)j.getInt("sourceHeight")else ph,
                if(version>=2)j.getInt("canonicalWidth")else pw,if(version>=2)j.getInt("canonicalHeight")else ph,
                if(version>=2)CompositionTransform(j.getDouble("centerX"),j.getDouble("centerY"),j.getDouble("zoom"),if(version>=3)j.getDouble("targetAspect")else null,if(j.has("straightenDegrees"))j.getDouble("straightenDegrees")else 0.0)else CompositionTransform(),
                if(j.has("appearance"))FrameAppearanceContext.fromJson(j.getJSONObject("appearance"))else null,
                if(version==5)MediumFormat.valueOf(j.getString("mediumFormat"))else null,
                if(version==5)j.getBoolean("mediumPortrait")else false,
                if(version==5)j.getDouble("stripCenterMm")else null)
        }
        fun medium120(stock:String,version:String,sourceWidth:Int,sourceHeight:Int,canonicalWidth:Int=sourceWidth,canonicalHeight:Int=sourceHeight,composition:CompositionTransform=CompositionTransform(),mediumFormat:MediumFormat,mediumPortrait:Boolean=false,stripCenterMm:Double=410.0,featherTotalAt3072:Double=4.0):BorderSnapshot {
            val g=mediumFormat.geometry(mediumPortrait)
            val c=composition.copy(targetAspect=g.aspect).clamped(canonicalWidth,canonicalHeight)
            val scale=minOf(sourceWidth/g.apertureWidth,sourceHeight/g.apertureHeight)
            val pw=maxOf(1,(g.apertureWidth*scale).roundToInt())
            val ph=maxOf(1,(pw/g.aspect).roundToInt())
            return BorderSnapshot(stock,version,5,pw,ph,1,1,featherTotalAt3072,sourceWidth,sourceHeight,canonicalWidth,canonicalHeight,c,null,mediumFormat,mediumPortrait,stripCenterMm)
        }
        fun authored(stock:String,version:String,sourceWidth:Int,sourceHeight:Int,canonicalWidth:Int=sourceWidth,canonicalHeight:Int=sourceHeight,composition:CompositionTransform=CompositionTransform(),featherTotalAt3072:Double=4.0):BorderSnapshot {
            val g=BwFrameGeometry.forStock(stock)
            if(g==null){
                val normalized=if(composition.targetAspect==null || composition.targetAspect==1.5 || composition.targetAspect==2.0/3.0)composition
                    else composition.copy(targetAspect=if(composition.targetAspect<1.0)2.0/3.0 else 1.5)
                return fixed135(stock,version,sourceWidth,sourceHeight,canonicalWidth,canonicalHeight,normalized,featherTotalAt3072)
            }
            val portrait=composition.targetAspect?.let{it<1} ?: (canonicalHeight>canonicalWidth)
            val c=composition.copy(targetAspect=if(portrait)1/g.aspect else g.aspect).clamped(canonicalWidth,canonicalHeight)
            val aw=if(portrait)g.apertureHeight else g.apertureWidth;val ah=if(portrait)g.apertureWidth else g.apertureHeight
            val scale=minOf(sourceWidth/aw,sourceHeight/ah)
            val pw=maxOf(1,(aw*scale).roundToInt());val ph=maxOf(1,(pw*ah/aw).roundToInt())
            return BorderSnapshot(stock,version,4,pw,ph,1,1,featherTotalAt3072,sourceWidth,sourceHeight,canonicalWidth,canonicalHeight,c)
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
