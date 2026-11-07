package dev.filmroom.border

import kotlin.math.*

/** Original BW overlays, composed AFTER film processing. Aperture alpha means photograph;
 * perforation/clear rail alpha means fixed matte. Neither is inferred from pixel RGB.
 * All atlas texels are filtered in premultiplied linear light; minification integrates the
 * whole box footprint. The photograph remains float and is sampled by FinalImageSampling.
 * No third-party image, demonstration photograph, inversion, or toning is embedded here. */
internal class AuthoredBwBorderRaster(
    override val stock:String,override val version:String,
    private val width:Int,private val height:Int,pixels:IntArray,
    override val featherTotalAt3072:Double,private val holeMatteSrgb:Int,
):BorderRaster {
    private val geometry=requireNotNull(BwFrameGeometry.forStock(stock))
    private val super8Fringe=stock=="kodak_trix" && version==BwBorderDefinitions.SUPER8_EDGE_VERSION
    private val data=pixels.copyOf()
    private val linear=DoubleArray(256){i->val v=i/255.0;if(v<=.04045)v/12.92 else ((v+.055)/1.055).pow(2.4)}
    override val residentBytes=data.size*4L+2048
    override val supportsFixed135=false
    override val supportsAuthoredLayout=true
    override val railRatio=0.0
    override val endRatio=0.0
    init {
        require(width.toDouble()==geometry.width && height.toDouble()==geometry.height)
        require(data.size.toLong()==width.toLong()*height && residentBytes<=BorderCompositor.MAX_ATLAS_BYTES)
        require(holeMatteSrgb in 0..0xffffff)
    }
    override fun validate(snapshot:BorderSnapshot){require(snapshot.geometryVersion==4);super.validate(snapshot)}
    private inline fun taps(s:BorderSnapshot,x:Double,y:Double,accept:(Int,Int,Double)->Unit) {
        val localX=(x-s.frameOriginX)/s.pixelsPerMm;val localY=(y-s.frameOriginY)/s.pixelsPerMm
        val xx=if(s.portrait)localY else localX
        val yy=if(s.portrait)geometry.height-localX else localY
        val footprint=1/s.pixelsPerMm
        if(footprint>1){
            val loX=xx-footprint/2;val hiX=xx+footprint/2;val loY=yy-footprint/2;val hiY=yy+footprint/2
            val x0=floor(loX).toInt().coerceIn(0,width-1);val x1=(ceil(hiX).toInt()-1).coerceIn(0,width-1)
            val y0=floor(loY).toInt().coerceIn(0,height-1);val y1=(ceil(hiY).toInt()-1).coerceIn(0,height-1)
            for(j in y0..y1){
                val wy=(minOf(hiY,if(j==height-1)Double.POSITIVE_INFINITY else j+1.0)-maxOf(loY,if(j==0)Double.NEGATIVE_INFINITY else j.toDouble()))/footprint
                for(i in x0..x1){
                    val wx=(minOf(hiX,if(i==width-1)Double.POSITIVE_INFINITY else i+1.0)-maxOf(loX,if(i==0)Double.NEGATIVE_INFINITY else i.toDouble()))/footprint
                    accept(i,j,wx*wy)
                }
            }
        }else{
            val sx=(xx-.5).coerceIn(0.0,width-1.0);val sy=(yy-.5).coerceIn(0.0,height-1.0)
            val x0=floor(sx).toInt();val y0=floor(sy).toInt();val fx=sx-x0;val fy=sy-y0
            accept(x0,y0,(1-fx)*(1-fy));accept(minOf(x0+1,width-1),y0,fx*(1-fy))
            accept(x0,minOf(y0+1,height-1),(1-fx)*fy);accept(minOf(x0+1,width-1),minOf(y0+1,height-1),fx*fy)
        }
    }
    override fun photoCoverage(snapshot:BorderSnapshot,x:Double,y:Double):Double {
        var coverage=0.0
        taps(snapshot,x,y){i,j,w->if(geometry.imageWindow(i+.5,j+.5,super8Fringe))coverage+=w*(1-(data[j*width+i] ushr 24)/255.0)}
        return coverage.coerceIn(0.0,1.0)
    }
    override fun holeCoverage(snapshot:BorderSnapshot,x:Double,y:Double):Double {
        var coverage=0.0
        taps(snapshot,x,y){i,j,w->if(!geometry.imageWindow(i+.5,j+.5,super8Fringe))coverage+=w*(1-(data[j*width+i] ushr 24)/255.0)}
        return coverage.coerceIn(0.0,1.0)
    }
    override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray) {
        var red=0.0;var green=0.0;var blue=0.0;var coverage=0.0
        taps(snapshot,x,y){i,j,w->
            val pixel=data[j*width+i];val alpha=(pixel ushr 24)/255.0
            val photo=geometry.imageWindow(i+.5,j+.5,super8Fringe)
            if(photo)coverage+=w*(1-alpha)
            val matte=if(photo)0.0 else w*(1-alpha)
            red+=w*alpha*linear[(pixel ushr 16)and 255]+matte*linear[(holeMatteSrgb ushr 16)and 255]
            green+=w*alpha*linear[(pixel ushr 8)and 255]+matte*linear[(holeMatteSrgb ushr 8)and 255]
            blue+=w*alpha*linear[pixel and 255]+matte*linear[holeMatteSrgb and 255]
        }
        val border=(1-coverage).coerceIn(0.0,1.0)
        fun encode(v:Double):Float {val a=if(border<=1e-12)0.0 else (v/border).coerceIn(0.0,1.0);return (if(a<=.0031308)a*12.92 else 1.055*a.pow(1/2.4)-.055).toFloat()}
        rgb[0]=encode(red);rgb[1]=encode(green);rgb[2]=encode(blue)
    }
}
