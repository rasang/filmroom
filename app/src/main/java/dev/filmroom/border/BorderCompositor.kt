package dev.filmroom.border

import dev.filmroom.io.OutputColorSpace
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Last stage only: fixed-window crop/resize and border never alter the film source/checkpoint.
 * At most two source float rows, one byte row, one output float/byte row and one x map.
 * Photograph stays float32 until the existing JPEG/PNG/TIFF16 INTEGER encoder.
 */
object BorderCompositor {
    const val MAX_ATLAS_BYTES=16L*1024*1024
    fun workingBytes(s:BorderSnapshot,raster:BorderRaster):Long=s.sourceWidth*36L+s.outputWidth*36L+raster.residentBytes+128L*1024
    fun composite(source:File,destination:File,s:BorderSnapshot,raster:BorderRaster,color:OutputColorSpace,checkActive:()->Unit={}) {
        raster.validate(s)
        val map=BorderSampling(s)
        FinalImageRows.file(source,destination,s.sourceWidth,s.sourceHeight,s.outputWidth,s.outputHeight,map,checkActive){y,first,second,fy,row->
            composeSampledRow(s,raster,color,y,map,first,second,fy,row)
        }
    }

    /** Legacy same-row test entry. New preview/export call the shared two-row sampler below. */
    internal fun composeRow(s:BorderSnapshot,raster:BorderRaster,color:OutputColorSpace,y:Int,photo:FloatArray,output:FloatArray) {
        require(s.geometryVersion==1)
        composeSampledRow(s,raster,color,y,BorderSampling(s),photo,photo,0.0,output)
    }
    internal fun composeSampledRow(s:BorderSnapshot,raster:BorderRaster,color:OutputColorSpace,y:Int,map:BorderSampling,first:FloatArray,second:FloatArray,fy:Double,output:FloatArray) {
        val rgb=FloatArray(3)
        val yd=minOf(y+.5-s.apertureTop,s.apertureTop+s.photoHeight-y-.5)
        for(x in 0 until s.outputWidth){
            val distance=minOf(x+.5-s.apertureLeft,s.apertureLeft+s.photoWidth-x-.5,yd)
            val a=if(s.featherRadius==0.0)if(distance>=0)1.0 else 0.0 else ((distance+s.featherRadius)/(2*s.featherRadius)).coerceIn(0.0,1.0).let{it*it*(3-2*it)}
            val at=x*3
            if(a<1.0){raster.sample(s,x+.5,y+.5,rgb);BorderColor.convert(rgb,color)}
            if(a<=0.0){for(c in 0..2)output[at+c]=rgb[c];continue}
            for(c in 0..2){
                val photo=map.sample(first,second,fy,x,c)
                output[at+c]=(if(a==1.0)photo else rgb[c]*(1-a)+photo*a).toFloat()
            }
        }
    }
}
