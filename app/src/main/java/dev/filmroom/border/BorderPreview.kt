package dev.filmroom.border

import android.graphics.Bitmap
import dev.filmroom.io.OutputColorSpace
import dev.filmroom.io.LinearRgb
import kotlinx.coroutines.*

/** Preview adapter only. It uses the same float row map/compositor as final export. */
object BorderPreview {
    internal suspend fun owned(dispatcher:CoroutineDispatcher=Dispatchers.Default,create:()->Bitmap):Bitmap {
        var produced:Bitmap?=null;var delivered=false
        try{val result=withContext(dispatcher){create().also{produced=it}};delivered=true;return result}
        finally{if(!delivered)produced?.takeUnless{it.isRecycled}?.recycle()}
    }
    fun compose(photo:Bitmap,s:BorderSnapshot,raster:BorderRaster,imprint:dev.filmroom.imprint.ImprintPlan?=null,checkActive:()->Unit={}):Bitmap =
        composeInternal(photo,s,raster,imprint,null,checkActive)
    /** Fill a caller-owned gesture overlay in the same atlas traversal as the exact image.
     * The overlay still has its historical sRGB/8-bit-alpha contract. It is never used to
     * build the exact image. Caller keeps ownership and must release it on failure. */
    internal fun composeWithOverlay(photo:Bitmap,s:BorderSnapshot,raster:BorderRaster,imprint:dev.filmroom.imprint.ImprintPlan?,overlay:Bitmap,checkActive:()->Unit={}):Bitmap =
        composeInternal(photo,s,raster,imprint,overlay,checkActive)
    private fun composeInternal(photo:Bitmap,s:BorderSnapshot,raster:BorderRaster,imprint:dev.filmroom.imprint.ImprintPlan?,overlay:Bitmap?,checkActive:()->Unit):Bitmap {
        checkActive();raster.validate(s)
        require(overlay==null || (overlay!==photo && !overlay.isRecycled && overlay.width==s.outputWidth && overlay.height==s.outputHeight && overlay.config==Bitmap.Config.ARGB_8888))
        require(photo.width==s.sourceWidth && photo.height==s.sourceHeight)
        require(maxOf(photo.width,photo.height)<=2048){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8f256a031a2d)}
        val effective=imprint?.frameRaster(s,raster) ?: raster
        val map=BorderSampling(s)
        val overlayRow=overlay?.let{IntArray(s.outputWidth)}
        return FinalImageRows.bitmap(photo,s.outputWidth,s.outputHeight,map,checkActive){y,first,second,fy,row->
            BorderCompositor.composeSampledRow(s,effective,OutputColorSpace.SRGB,y,map,first,second,fy,row,overlayRow)
            if(overlayRow!=null)overlay!!.setPixels(overlayRow,0,s.outputWidth,0,y,s.outputWidth,1)
            imprint?.blendRow(y,row,OutputColorSpace.SRGB)
        }
    }
}
