package dev.filmroom.crop

import android.graphics.Bitmap
import dev.filmroom.io.OutputColorSpace
import dev.filmroom.border.FinalImageSampling
import dev.filmroom.border.FinalImageRows
import java.io.File

/** Final-stage float crop only: no source/film/AE mutation, no encoding or 8-bit roundtrip. */
object CropCompositor {
    fun workingBytes(s:CropSnapshot)=s.sourceWidth*36L+s.outputWidth*36L+128L*1024+if(s.settings.transform.straightenDegrees!=0.0)dev.filmroom.border.FinalImageTileCache.workingBytes(s.sourceWidth,s.sourceHeight)+s.outputWidth*12L else 0L
    fun additionalDiskBytes(s:CropSnapshot,withImprint:Boolean=false)=if(s.isIdentity && !withImprint)0L else Math.addExact(Math.multiplyExact(s.outputBytes,3L),8L*1024*1024)
    internal fun map(s:CropSnapshot)=FinalImageSampling(s.sourceWidth,s.sourceHeight,s.outputWidth,s.sourceRect,0.0,0.0,s.outputWidth,s.outputHeight,transform=s.settings.effectiveTransform(s.canonicalWidth,s.canonicalHeight),canonicalWidth=s.canonicalWidth,canonicalHeight=s.canonicalHeight)
    internal fun row(map:FinalImageSampling,first:FloatArray,second:FloatArray,fy:Double,out:FloatArray){
        for(x in map.x0.indices)for(c in 0..2)out[x*3+c]=map.sample(first,second,fy,x,c).toFloat()
    }
    fun composite(source:File,destination:File,s:CropSnapshot,imprint:dev.filmroom.imprint.ImprintPlan?=null,color:dev.filmroom.io.OutputColorSpace=dev.filmroom.io.OutputColorSpace.SRGB,checkActive:()->Unit={}) = composite(source,destination,s,imprint,color,null,checkActive)
    /** Reports successfully written rows, not completion of flush/sync/rename. */
    fun composite(source:File,destination:File,s:CropSnapshot,imprint:dev.filmroom.imprint.ImprintPlan?=null,color:dev.filmroom.io.OutputColorSpace=dev.filmroom.io.OutputColorSpace.SRGB,onProgress:((Int,Int)->Unit)?,checkActive:()->Unit={}) {
        val map=map(s)
        FinalImageRows.file(source,destination,s.sourceWidth,s.sourceHeight,s.outputWidth,s.outputHeight,map,checkActive,onProgress){y,first,second,fy,row->
            CropCompositor.row(map,first,second,fy,row)
            imprint?.blendRow(y,row,color)
        }
    }
}
/** Bitmap adapter for memory-budgeted effect export. Scratch memory is row/tile bounded.
 * Caller owns and budgets the decoded source and output bitmaps; no preview-size cap applies. */
object CropBitmapCompositor {
    fun compose(photo:Bitmap,s:CropSnapshot,imprint:dev.filmroom.imprint.ImprintPlan?=null,checkActive:()->Unit={}):Bitmap {
        require(photo.width==s.sourceWidth && photo.height==s.sourceHeight)
        val map=CropCompositor.map(s)
        return FinalImageRows.bitmap(photo,s.outputWidth,s.outputHeight,map,checkActive){y,first,second,fy,row->
            CropCompositor.row(map,first,second,fy,row)
            imprint?.blendRow(y,row,OutputColorSpace.SRGB)
        }
    }
}
/** Preview retains its own admission limit; both adapters use exactly the same pixel traversal. */
object CropPreview {
    fun compose(photo:Bitmap,s:CropSnapshot,imprint:dev.filmroom.imprint.ImprintPlan?=null,checkActive:()->Unit={}):Bitmap {
        checkActive()
        require(maxOf(photo.width,photo.height)<=2048){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5bba0c1f8416)}
        return CropBitmapCompositor.compose(photo,s,imprint,checkActive)
    }
}
