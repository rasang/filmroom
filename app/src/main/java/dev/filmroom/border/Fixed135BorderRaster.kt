package dev.filmroom.border

import kotlin.math.floor
import kotlin.math.pow

/** Immutable authored test/review atlas. Entire 38×35 frame is scaled uniformly and rotated;
 * never sliced, stretched along one axis, repeated or loaded from a user-supplied path.
 * Construction does not approve an asset: production registry remains empty and disabled.
 */
class Fixed135BorderRaster(
    override val stock:String,override val version:String,
    private val width:Int,private val height:Int,pixels:IntArray,
    override val featherTotalAt3072:Double,
):BorderRaster {
    private val data=run {
        require(width>0 && height>0 && width.toLong()*35==height.toLong()*38)
        require(width.toLong()*height==pixels.size.toLong() && pixels.size*4L<=BorderCompositor.MAX_ATLAS_BYTES)
        pixels.copyOf()
    }
    override val residentBytes=data.size*4L
    override val railRatio=5.5/24.0
    override val endRatio=1.0/24.0
    init {
        require(width>0 && height>0 && width.toLong()*35==height.toLong()*38)
        require(width.toLong()*height==data.size.toLong() && residentBytes<=BorderCompositor.MAX_ATLAS_BYTES)
        require(data.all{it ushr 24==255}){"Atlas must have opaque border; aperture mask belongs to compositor"}
    }
    override fun validate(snapshot:BorderSnapshot){require(snapshot.geometryVersion in 2..3);super.validate(snapshot)}
    override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){
        val localX=(x-snapshot.frameOriginX)/snapshot.pixelsPerMm
        val localY=(y-snapshot.frameOriginY)/snapshot.pixelsPerMm
        val landscapeX=if(snapshot.portrait)localY else localX
        val landscapeY=if(snapshot.portrait)35.0-localX else localY
        val sx=(landscapeX/38*width-.5).coerceIn(0.0,width-1.0)
        val sy=(landscapeY/35*height-.5).coerceIn(0.0,height-1.0)
        val x0=floor(sx).toInt();val y0=floor(sy).toInt();val x1=minOf(x0+1,width-1);val y1=minOf(y0+1,height-1)
        val fx=sx-x0;val fy=sy-y0
        fun linear(pixel:Int,shift:Int):Double {val v=((pixel ushr shift)and 255)/255.0;return if(v<=.04045)v/12.92 else ((v+.055)/1.055).pow(2.4)}
        for(c in 0..2){
            val shift=16-8*c
            val top=linear(data[y0*width+x0],shift)*(1-fx)+linear(data[y0*width+x1],shift)*fx
            val bottom=linear(data[y1*width+x0],shift)*(1-fx)+linear(data[y1*width+x1],shift)*fx
            val v=top*(1-fy)+bottom*fy
            rgb[c]=(if(v<=.0031308)v*12.92 else 1.055*v.pow(1/2.4)-.055).toFloat().coerceIn(0f,1f)
        }
    }
}
