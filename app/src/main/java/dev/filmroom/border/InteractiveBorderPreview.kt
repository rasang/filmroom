package dev.filmroom.border

import android.graphics.Bitmap
import dev.filmroom.model.CompositionTransform
import kotlin.math.roundToInt

/** Gesture-only screen overlay. Never used for export, checkpoints, or film processing.
 * Generated once per physical frame/preview geometry, not per crop/move. The aperture is
 * transparent; holes retain their version-pinned matte. Canvas's transient blend may differ
 * from final linear composition; the exact shared compositor replaces it after commit.
 */
internal object InteractiveBorderPreview {
    fun key(s:BorderSnapshot)=s.copy(composition=CompositionTransform(targetAspect=s.composition.targetAspect)).json()
    /** Also used by the exact preview pass, before it converts this sRGB sample to linear. */
    internal fun pixel(photo:Double,rgb:FloatArray):Int {
        val alpha=((1-photo)*255).roundToInt().coerceIn(0,255)
        if(alpha==0)return 0
        return (alpha shl 24) or ((rgb[0].coerceIn(0f,1f)*255).roundToInt() shl 16) or
            ((rgb[1].coerceIn(0f,1f)*255).roundToInt() shl 8) or (rgb[2].coerceIn(0f,1f)*255).roundToInt()
    }
    fun create(s:BorderSnapshot,raster:BorderRaster,checkActive:()->Unit={}):Bitmap {
        checkActive();raster.validate(s)
        require(maxOf(s.sourceWidth,s.sourceHeight)<=2048)
        val out=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888)
        try {
            val row=IntArray(s.outputWidth);val rgb=FloatArray(3)
            for(y in 0 until s.outputHeight){
                checkActive();val dy=minOf(y+.5-s.apertureTop,s.apertureTop+s.photoHeight-y-.5)
                for(x in row.indices){
                    val d=minOf(x+.5-s.apertureLeft,s.apertureLeft+s.photoWidth-x-.5,dy)
                    val photo=raster.photoCoverage(s,x+.5,y+.5) ?: if(s.featherRadius==0.0){if(d>=0)1.0 else 0.0}else ((d+s.featherRadius)/(2*s.featherRadius)).coerceIn(0.0,1.0).let{it*it*(3-2*it)}
                    val alpha=((1-photo)*255).roundToInt().coerceIn(0,255)
                    if(alpha==0){row[x]=0;continue}
                    raster.sample(s,x+.5,y+.5,rgb)
                    row[x]=pixel(photo,rgb)
                }
                out.setPixels(row,0,row.size,0,y,row.size,1)
            }
            return out
        }catch(t:Throwable){out.recycle();throw t}
    }
}
