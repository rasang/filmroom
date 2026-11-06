package dev.filmroom.imprint

import dev.filmroom.border.*
import dev.filmroom.io.OutputColorSpace
import dev.filmroom.io.PhotoMetadata
import kotlin.math.*

/** One frozen final-presentation plan for preview, exported scanlines and queued thumbnails. */
class ImprintPlan private constructor(
    val width:Int,val height:Int,val stamps:List<Stamp>,
    private val standardizeMedium:Boolean=false,
    val omittedFieldCount:Int=0,
) {
    data class Stamp(val raster:ImprintRaster,val left:Int,val top:Int) {
        val right get()=left+raster.width
        val bottom get()=top+raster.height
    }
    val residentBytes get()=stamps.sumOf{it.raster.residentBytes}
    val isEmpty get()=stamps.isEmpty()
    fun frameRaster(snapshot:BorderSnapshot,raster:BorderRaster):BorderRaster =
        if(standardizeMedium)MediumImprintMarks.wrap(snapshot,raster) else raster
    fun blendRow(y:Int,row:FloatArray,color:OutputColorSpace) {
        require(row.size==width*3)
        for(s in stamps)if(y>=s.top && y<s.bottom)s.raster.blendRow(y-s.top,row,s.left,color)
    }
    companion object {
        const val MAX_WORKING_BYTES=32L*1024*1024
        // Film-edge capture data has its own appearance; the date controls never modify it.
        private const val CAPTURE_DATA_GLOW=1.0
        fun exposureSupported(border:BorderSnapshot?)=border!=null &&
            (border.geometryVersion in 2..3 || (border.geometryVersion==4 && border.stock=="kodak_doublex") ||
             (border.geometryVersion==5 && MediumImprintMarks.layout(border)!=null))
        private fun advance(text:String,style:String)=text.sumOf{ImprintGlyphs.glyph(it,style).advance}
        private fun dimensions(text:String,style:String,h:Double):Pair<Int,Int> {
            val p=ceil(h*.25+2).toInt()
            return (ceil(advance(text,style)*h).toInt().coerceAtLeast(1)+2*p) to (ceil(h).toInt()+2*p)
        }
        private fun fit(text:String,style:String,requested:Double,maxWidth:Double,maxHeight:Double,glow:Double,tone:ImprintTone,availableBytes:Long,rotated:Boolean):ImprintRaster? {
            var h=requested.coerceIn(1.0,512.0)
            repeat(64) {
                val (w,hh)=dimensions(text,style,h)
                if(w<=maxWidth && hh<=maxHeight && w.toLong()*hh*3<=ImprintRaster.MAX_MASK_BYTES && w.toLong()*hh*3*(if(rotated)2 else 1)<=availableBytes)
                    return ImprintRaster.create(text,style,h,glow,tone)
                if(h==1.0)return null
                h=(h*.90).coerceAtLeast(1.0)
            }
            return null
        }
        fun create(settings:ImprintSettings,metadata:PhotoMetadata,width:Int,height:Int,border:BorderSnapshot?=null):ImprintPlan {
            require(width in 1..30000 && height in 1..30000)
            require(border==null || (width==border.outputWidth && height==border.outputHeight))
            if(!settings.enabled)return ImprintPlan(width,height,emptyList())
            val content=ImprintContent.from(metadata,settings);val stamps=mutableListOf<Stamp>();var omitted=0
            val tone=if(border?.appearance?.material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION)ImprintTone.DARK
                else if(border?.stock in setOf("kodak_doublex","kodak_trix","kodak_2302"))ImprintTone.MONOCHROME else ImprintTone.WARM
            val medium=border?.takeIf{it.geometryVersion==5}?.let{MediumImprintMarks.layout(it)}
            val bw=border?.takeIf{it.geometryVersion==4}?.layout
            val unit=if(bw!=null)40.0 else 1.0
            val ppm=border?.pixelsPerMm ?: 1.0
            val rotated=border?.mediumLayout?.rotated ?: (border?.portrait ?: false)
            val nativeWidth=when {medium!=null->medium.widthMm;bw!=null->bw.width;border!=null->38.0;else->width.toDouble()}
            val nativeHeight=when {border?.geometryVersion==5->61.0;bw!=null->bw.height;border!=null->35.0;else->height.toDouble()}
            fun add(r:ImprintRaster,bodyX:Double,bodyY:Double):Boolean {
                val left=(bodyX*ppm-r.coreLeft).roundToInt();val top=(bodyY*ppm-r.coreTop).roundToInt()
                val stamp=if(rotated){val rr=r.rotatedClockwise();Stamp(rr,((border?.frameOriginX ?: 0.0)+nativeHeight*ppm-top-r.height).roundToInt(),((border?.frameOriginY ?: 0.0)+left).roundToInt())}
                    else Stamp(r,((border?.frameOriginX ?: 0.0)+left).roundToInt(),((border?.frameOriginY ?: 0.0)+top).roundToInt())
                if(stamp.left<0 || stamp.top<0 || stamp.right>width || stamp.bottom>height)return false
                if(stamps.sumOf{it.raster.residentBytes}+stamp.raster.residentBytes>MAX_WORKING_BYTES)return false
                stamps+=stamp;return true
            }
            if(settings.datePlacement=="photo" || medium==null)content.date?.let { text ->
                val ax=when {border?.geometryVersion==5->3.0;bw!=null->bw.left;border!=null->1.0;else->0.0}
                val ay=when {border?.geometryVersion==5->2.5;bw!=null->bw.top;border!=null->5.5;else->0.0}
                val aw=when {border?.geometryVersion==5->border.mediumFormat!!.longitudinalMm;bw!=null->bw.apertureWidth;border!=null->36.0;else->width.toDouble()}
                val ah=when {border?.geometryVersion==5->56.0;bw!=null->bw.apertureHeight;border!=null->24.0;else->height.toDouble()}
                val mx=if(border!=null)1.4*unit else min(aw,ah)*.045
                val my=if(border!=null)1.25*unit else min(aw,ah)*.045
                val desired=(if(border!=null)(if(border.geometryVersion==5).98 else .96)*unit*ppm else min(aw,ah)*.026)*settings.size
                val r=fit(text,settings.effectiveDateStyle,desired,(aw-2*mx)*ppm,(ah-2*my)*ppm,settings.glow,tone,MAX_WORKING_BYTES-stamps.sumOf{it.raster.residentBytes},rotated)
                if(r!=null){
                    val x=if(settings.corner.endsWith("right"))ax+aw-mx-r.coreWidth/ppm else ax+mx
                    val y=if(settings.corner.startsWith("bottom"))ay+ah-my-r.coreHeight/ppm else ay+my
                    if(!add(r,x,y))omitted++
                }else omitted++
            }
            val fields=if(settings.exposure && exposureSupported(border))content.fields else emptyList()
            if(border!=null && border.geometryVersion!=5){
                // Fit the whole set at one height. Per-field shrinking made ISO/aperture
                // much smaller than the shutter and exposure compensation.
                var h=(.60*unit*ppm).coerceIn(1.0,512.0)
                var commonHeight:Double?=null
                repeat(64) {
                    if(commonHeight==null && fields.isNotEmpty()) {
                        val sizes=fields.map{dimensions(it.text,"pentax_mzs",h)}
                        val bytes=sizes.map{(w,hh)->w.toLong()*hh*3}
                        val peak=stamps.sumOf{it.raster.residentBytes}+bytes.sum()+(if(rotated)bytes.maxOrNull() ?: 0L else 0L)
                        if(sizes.all{(w,hh)->w<=2.35*unit*ppm && hh<=1.9*unit*ppm} &&
                            bytes.all{it<=ImprintRaster.MAX_MASK_BYTES} && peak<=MAX_WORKING_BYTES)commonHeight=h
                        else h=(h*.90).coerceAtLeast(1.0)
                    }
                }
                val fh=commonHeight
                if(fh==null)omitted+=fields.size else fields.forEachIndexed { index,field ->
                    // Center on real inter-perforation gaps. Even counts leave the center
                    // gap empty rather than moving lettering onto a perforation.
                    val half=fields.size/2
                    val offset=if(fields.size%2==1) index-half else if(index<half) index-half else index-half+1
                    val center=(19.0+offset*4.75)*unit
                    val r=ImprintRaster.create(field.text,"pentax_mzs",fh,CAPTURE_DATA_GLOW,tone)
                    val base=if(border.stock in setOf("fujifilm_c200","fujifilm_xtra_400","fujifilm_pro_400h"))30.48 else 31.28
                    val x=center-r.coreWidth/ppm/2
                    val y=nativeHeight-base*unit-r.coreHeight/ppm
                    if(!add(r,x,y))omitted++
                }
            }else if(medium!=null){
                data class Piece(val text:String,val style:String,val date:Boolean=false)
                val pieces=buildList {
                    if(settings.datePlacement=="edge")content.date?.let{add(Piece(it,settings.effectiveDateStyle,true))}
                    fields.forEach{add(Piece(it.text,"pentax_mzs"))}
                }
                if(pieces.isNotEmpty()){
                    val gaps=pieces.dropLast(1).map{if(it.date)2.0 else 1.8}
                    // Dates on the 120 edge share its fixed, fitted type size. Preserve the
                    // photo-date size preference, but never use it for film-edge lettering.
                    var h=(.78*ppm).coerceIn(1.0,512.0)
                    var fittedHeight:Double?=null
                    repeat(64){
                        if(fittedHeight==null){
                            val bodyWidth=pieces.sumOf{ceil(advance(it.text,it.style)*h)}+gaps.sum()*ppm
                            val p=ceil(.25*h+2)
                            val bytes=pieces.map{dimensions(it.text,it.style,h).let{(w,hh)->w.toLong()*hh*3}}
                            val peak=stamps.sumOf{it.raster.residentBytes}+bytes.sum()+(if(rotated)bytes.maxOrNull() ?: 0L else 0L)
                            val fits=bodyWidth+2*p+1<=(medium.clearEndMm-medium.clearStartMm)*ppm && ceil(h)+2*p<=2.5*ppm &&
                                bytes.all{it<=ImprintRaster.MAX_MASK_BYTES} && peak<=MAX_WORKING_BYTES
                            if(fits)fittedHeight=h else h=(h*.90).coerceAtLeast(1.0)
                        }
                    }
                    val fh=fittedHeight
                    if(fh==null)omitted+=pieces.size else {
                        val total=pieces.sumOf{ceil(advance(it.text,it.style)*fh)}+gaps.sum()*ppm
                        var x=(nativeWidth-total/ppm)/2
                        pieces.forEachIndexed { i,piece ->
                            // Create one raster at a time, so rotation cannot retain a second full set.
                            val r=ImprintRaster.create(piece.text,piece.style,fh,if(piece.date)settings.glow else CAPTURE_DATA_GLOW,tone)
                            val y=(nativeHeight-.92-r.coreHeight/ppm).coerceIn(58.5+r.coreTop/ppm,61.0-(r.height-r.coreTop)/ppm)
                            if(!add(r,x,y))omitted++
                            x+=r.coreWidth/ppm+(gaps.getOrNull(i) ?: 0.0)
                        }
                    }
                }
            }
            return ImprintPlan(width,height,stamps,medium!=null,omitted)
        }
    }
}
