package dev.filmroom.border

import kotlin.math.*

/** Isolated straight-alpha sRGB atlas adapter; NOT an approved stock registry.
 *
 * Contract v1: a uniformly scaled 38×35 mm atlas, rectangular 36×24 mm cleared aperture,
 * even integer pixels/mm, transparent perforations, and opaque material around the aperture.
 * The RGB export's perforation matte is version-pinned sRGB (legacy white), independent of UI theme. This is
 * an output presentation choice, not a claim that scanned perforations are physically white.
 *
 * Aperture and holes have distinct semantics. Every aperture tap (plus one atlas-pixel guard)
 * gets material from the nearest aperture edge, never its hidden RGB. Each edge donor is the
 * first fully opaque texel found along its outward normal, bounded to 0.5 mm. Missing material
 * rejects the atlas. Edge-distance ties choose top, left, bottom, right, in that order. Corners
 * are filtered from those deterministic donors. Only the compositor owns the aperture feather.
 *
 * All other texels retain their straight alpha. RGB is decoded before premultiplication;
 * premultiplied linear RGB and alpha are bilinearly filtered for magnification. Minification
 * integrates the exact axis-aligned box footprint (no capped supersampling), then applies the pinned matte.
 * Return encoding is opaque sRGB because BorderRaster's caller performs output-primary conversion.
 * Resident memory: one immutable packed atlas + O(perimeter) donor indices + a 256-entry LUT.
 * Construction from premultiplied pixel storage is invalid; callers must supply straight ARGB.
 */
class StraightAlphaFixed135Raster(
    override val stock:String,
    override val version:String,
    private val width:Int,
    private val height:Int,
    straightArgb:IntArray,
    override val featherTotalAt3072:Double,
    private val holeMatteSrgb:Int=0xffffff,
):BorderRaster {
    companion object { const val SAMPLING_CONTRACT="straight-alpha-versioned-matte-aperture-extend-v2" }
    private val data:IntArray
    private val linear=DoubleArray(256){v->val c=v/255.0;if(c<=.04045)c/12.92 else ((c+.055)/1.055).pow(2.4)}
    private val matte=doubleArrayOf(linear[(holeMatteSrgb ushr 16)and 255],linear[(holeMatteSrgb ushr 8)and 255],linear[holeMatteSrgb and 255])
    private val left:Int;private val top:Int;private val right:Int;private val bottom:Int
    private val topDonors:IntArray;private val bottomDonors:IntArray
    private val leftDonors:IntArray;private val rightDonors:IntArray
    override val railRatio=5.5/24.0
    override val endRatio=1.0/24.0
    override val residentBytes:Long
    init {
        require(holeMatteSrgb in 0..0xffffff)
        require(width>0 && height>0 && width%76==0 && width.toLong()*35==height.toLong()*38){"Atlas needs even integer pixels/mm and fixed 38x35 aspect"}
        require(width.toLong()*height==straightArgb.size.toLong())
        val ppm=width/38
        left=ppm;right=37*ppm;top=11*ppm/2;bottom=59*ppm/2
        val perimeter=2L*(right-left+bottom-top)
        residentBytes=straightArgb.size*4L+perimeter*4L+linear.size*8L
        require(residentBytes<=BorderCompositor.MAX_ATLAS_BYTES){"Atlas exceeds bounded resident budget"}
        require(featherTotalAt3072.isFinite() && featherTotalAt3072 in 0.0..16.0)
        data=straightArgb.copyOf()
        // Do not infer aperture vs holes from alpha: aperture geometry is separately frozen.
        for(y in top until bottom)for(x in left until right)require(data[y*width+x] ushr 24==0){"Aperture must be fully cleared"}
        val search=maxOf(1,ceil(ppm*.5).toInt())
        fun donor(x:Int,y:Int,dx:Int,dy:Int):Int {
            for(step in 0 until search){
                val xx=x+step*dx;val yy=y+step*dy
                if(xx !in 0 until width || yy !in 0 until height)break
                val index=yy*width+xx
                if(data[index] ushr 24==255)return index
            }
            throw IllegalArgumentException("Opaque aperture material missing within 0.5 mm")
        }
        topDonors=IntArray(right-left){x->donor(left+x,top-1,0,-1)}
        bottomDonors=IntArray(right-left){x->donor(left+x,bottom,0,1)}
        leftDonors=IntArray(bottom-top){y->donor(left-1,top+y,-1,0)}
        rightDonors=IntArray(bottom-top){y->donor(right,top+y,1,0)}
    }
    override fun validate(snapshot:BorderSnapshot){require(snapshot.geometryVersion in 2..3);super.validate(snapshot)}
    private fun pixel(x:Int,y:Int):Int {
        val original=data[y*width+x]
        if(x<left-1 || x>right || y<top-1 || y>bottom)return original
        if((x<left || x>=right || y<top || y>=bottom) && original ushr 24==255)return original
        // The guard excludes transparent hole edges from aperture filtering only when physically
        // adjacent to the aperture; approved geometry must keep holes outside this one-pixel band.
        val xx=x.coerceIn(left,right-1);val yy=y.coerceIn(top,bottom-1)
        val dt=abs(y-(top-1));val dl=abs(x-(left-1));val db=abs(bottom-y);val dr=abs(right-x)
        val nearest=minOf(dt,dl,db,dr)
        val index=when(nearest){dt->topDonors[xx-left];dl->leftDonors[yy-top];db->bottomDonors[xx-left];else->rightDonors[yy-top]}
        return data[index]
    }
    override fun holeCoverage(snapshot:BorderSnapshot,x:Double,y:Double):Double {
        val localX=(x-snapshot.frameOriginX)/snapshot.pixelsPerMm
        val localY=(y-snapshot.frameOriginY)/snapshot.pixelsPerMm
        val lx=if(snapshot.portrait)localY else localX
        val ly=if(snapshot.portrait)35-localX else localY
        val rawX=lx/38*width-.5;val rawY=ly/35*height-.5
        val footprint=(width/38.0)/snapshot.pixelsPerMm
        fun clear(xx:Int,yy:Int)=1-(pixel(xx,yy) ushr 24)/255.0
        if(footprint>1.0){
            val loX=rawX+.5-footprint/2;val hiX=rawX+.5+footprint/2
            val loY=rawY+.5-footprint/2;val hiY=rawY+.5+footprint/2
            val x0=floor(loX).toInt().coerceIn(0,width-1);val x1=(ceil(hiX).toInt()-1).coerceIn(0,width-1)
            val y0=floor(loY).toInt().coerceIn(0,height-1);val y1=(ceil(hiY).toInt()-1).coerceIn(0,height-1)
            var result=0.0
            for(j in y0..y1){val wy=(minOf(hiY,if(j==height-1)Double.POSITIVE_INFINITY else j+1.0)-maxOf(loY,if(j==0)Double.NEGATIVE_INFINITY else j.toDouble()))/footprint
                for(i in x0..x1){val wx=(minOf(hiX,if(i==width-1)Double.POSITIVE_INFINITY else i+1.0)-maxOf(loX,if(i==0)Double.NEGATIVE_INFINITY else i.toDouble()))/footprint
                    result+=wx*wy*clear(i,j)}}
            return result.coerceIn(0.0,1.0)
        }
        val sx=rawX.coerceIn(0.0,width-1.0);val sy=rawY.coerceIn(0.0,height-1.0)
        val x0=floor(sx).toInt();val y0=floor(sy).toInt();val x1=minOf(x0+1,width-1);val y1=minOf(y0+1,height-1)
        val fx=sx-x0;val fy=sy-y0
        return ((clear(x0,y0)*(1-fx)+clear(x1,y0)*fx)*(1-fy)+(clear(x0,y1)*(1-fx)+clear(x1,y1)*fx)*fy).coerceIn(0.0,1.0)
    }
    override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray) {
        val localX=(x-snapshot.frameOriginX)/snapshot.pixelsPerMm
        val localY=(y-snapshot.frameOriginY)/snapshot.pixelsPerMm
        val lx=if(snapshot.portrait)localY else localX
        val ly=if(snapshot.portrait)35-localX else localY
        val rawX=lx/38*width-.5;val rawY=ly/35*height-.5
        val sx=rawX.coerceIn(0.0,width-1.0);val sy=rawY.coerceIn(0.0,height-1.0)
        val footprint=(width/38.0)/snapshot.pixelsPerMm
        if(footprint>1.0){
            // Exact area minification: total work per complete downscaled frame is bounded by
            // atlas area plus boundary overlap, without a second atlas/mipmap allocation.
            val loX=rawX+.5-footprint/2;val hiX=rawX+.5+footprint/2
            val loY=rawY+.5-footprint/2;val hiY=rawY+.5+footprint/2
            val startX=floor(loX).toInt().coerceIn(0,width-1);val endX=(ceil(hiX).toInt()-1).coerceIn(0,width-1)
            val startY=floor(loY).toInt().coerceIn(0,height-1);val endY=(ceil(hiY).toInt()-1).coerceIn(0,height-1)
            var red=0.0;var green=0.0;var blue=0.0;var alpha=0.0
            for(yy in startY..endY){
                val wy=(minOf(hiY,if(yy==height-1)Double.POSITIVE_INFINITY else yy+1.0)-maxOf(loY,if(yy==0)Double.NEGATIVE_INFINITY else yy.toDouble()))/footprint
                for(xx in startX..endX){
                    val wx=(minOf(hiX,if(xx==width-1)Double.POSITIVE_INFINITY else xx+1.0)-maxOf(loX,if(xx==0)Double.NEGATIVE_INFINITY else xx.toDouble()))/footprint
                    val p=pixel(xx,yy);val weight=wx*wy*(p ushr 24)/255.0
                    if(weight==0.0)continue
                    alpha+=weight;red+=linear[(p ushr 16)and 255]*weight
                    green+=linear[(p ushr 8)and 255]*weight;blue+=linear[p and 255]*weight
                }
            }
            rgb[0]=encode(red+matte[0]*(1-alpha));rgb[1]=encode(green+matte[1]*(1-alpha));rgb[2]=encode(blue+matte[2]*(1-alpha))
            return
        }
        val x0=floor(sx).toInt();val y0=floor(sy).toInt();val x1=minOf(x0+1,width-1);val y1=minOf(y0+1,height-1)
        val fx=sx-x0;val fy=sy-y0
        val p00=pixel(x0,y0);val p10=pixel(x1,y0);val p01=pixel(x0,y1);val p11=pixel(x1,y1)
        val alpha=((p00 ushr 24)*(1-fx)+(p10 ushr 24)*fx)*(1-fy)/255+
            ((p01 ushr 24)*(1-fx)+(p11 ushr 24)*fx)*fy/255
        for(c in 0..2){
            val shift=16-8*c
            val upper=component(p00,shift)*(1-fx)+component(p10,shift)*fx
            val lower=component(p01,shift)*(1-fx)+component(p11,shift)*fx
            rgb[c]=encode(upper*(1-fy)+lower*fy+matte[c]*(1-alpha))
        }
    }
    private fun component(pixel:Int,shift:Int):Double {
        val alpha=(pixel ushr 24)/255.0
        return if(alpha==0.0)0.0 else linear[(pixel ushr shift)and 255]*alpha
    }
    private fun encode(value:Double):Float {
        val v=value.coerceIn(0.0,1.0)
        return (if(v<=.0031308)v*12.92 else 1.055*v.pow(1/2.4)-.055).toFloat()
    }
}
