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
    fun compose(photo:Bitmap,s:BorderSnapshot,raster:BorderRaster,checkActive:()->Unit={}):Bitmap {
        checkActive();raster.validate(s)
        require(photo.width==s.sourceWidth && photo.height==s.sourceHeight)
        require(maxOf(photo.width,photo.height)<=2048){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_8f256a031a2d)}
        val map=BorderSampling(s)
        return FinalImageRows.bitmap(photo,s.outputWidth,s.outputHeight,map,checkActive){y,first,second,fy,row->
            BorderCompositor.composeSampledRow(s,raster,OutputColorSpace.SRGB,y,map,first,second,fy,row)
        }
    }
}
