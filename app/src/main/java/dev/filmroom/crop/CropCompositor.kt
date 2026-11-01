package dev.filmroom.crop

import android.graphics.Bitmap
import dev.filmroom.border.FinalImageSampling
import dev.filmroom.border.FinalImageRows
import java.io.File

/** Final-stage float crop only: no source/film/AE mutation, no encoding or 8-bit roundtrip. */
object CropCompositor {
    fun workingBytes(s:CropSnapshot)=s.sourceWidth*36L+s.outputWidth*36L+128L*1024
    fun additionalDiskBytes(s:CropSnapshot)=if(s.isIdentity)0L else Math.addExact(Math.multiplyExact(s.outputBytes,3L),8L*1024*1024)
    internal fun map(s:CropSnapshot)=FinalImageSampling(s.sourceWidth,s.sourceHeight,s.outputWidth,s.sourceRect,0.0,0.0,s.outputWidth,s.outputHeight)
    internal fun row(map:FinalImageSampling,first:FloatArray,second:FloatArray,fy:Double,out:FloatArray){
        for(x in map.x0.indices)for(c in 0..2)out[x*3+c]=map.sample(first,second,fy,x,c).toFloat()
    }
    fun composite(source:File,destination:File,s:CropSnapshot,checkActive:()->Unit={}) {
        val map=map(s)
        FinalImageRows.file(source,destination,s.sourceWidth,s.sourceHeight,s.outputWidth,s.outputHeight,map,checkActive){_,first,second,fy,row->
            CropCompositor.row(map,first,second,fy,row)
        }
    }
}
/** Preview uses identical float mapping/interpolation; Bitmap encoding exists only at preview boundary. */
object CropPreview {
    fun compose(photo:Bitmap,s:CropSnapshot,checkActive:()->Unit={}):Bitmap {
        require(photo.width==s.sourceWidth && photo.height==s.sourceHeight)
        val map=CropCompositor.map(s)
        return FinalImageRows.bitmap(photo,s.outputWidth,s.outputHeight,map,checkActive){_,first,second,fy,row->
            CropCompositor.row(map,first,second,fy,row)
        }
    }
}
