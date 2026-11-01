package dev.filmroom.border

import android.graphics.Bitmap
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.OutputColorSpace
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The same bounded two-row traversal feeds plain-crop and border preview/export. */
internal object FinalImageRows {
    fun walk(sourceWidth:Int,outputWidth:Int,outputHeight:Int,map:FinalImageSampling,checkActive:()->Unit,
             read:(Int,FloatArray)->Unit,compose:(Int,FloatArray,FloatArray,Double,FloatArray)->Unit,write:(Int,FloatArray)->Unit) {
        var first=FloatArray(sourceWidth*3);var second=FloatArray(sourceWidth*3)
        var firstIndex=-1;var secondIndex=-1;val row=FloatArray(outputWidth*3)
        for(y in 0 until outputHeight){
            checkActive();val sampling=map.row(y)
            if(map.rowHasPhoto(y)){
                if(firstIndex!=sampling.first){
                    if(secondIndex==sampling.first){val old=first;first=second;second=old;val index=firstIndex;firstIndex=secondIndex;secondIndex=index}
                    else {read(sampling.first,first);firstIndex=sampling.first}
                }
                if(sampling.second!=sampling.first && secondIndex!=sampling.second){read(sampling.second,second);secondIndex=sampling.second}
            }
            compose(y,first,if(sampling.second==sampling.first)first else second,sampling.fraction,row)
            write(y,row)
        }
    }
    fun file(source:File,destination:File,sourceWidth:Int,sourceHeight:Int,outputWidth:Int,outputHeight:Int,
             map:FinalImageSampling,checkActive:()->Unit,compose:(Int,FloatArray,FloatArray,Double,FloatArray)->Unit) {
        require(source.canonicalFile!=destination.canonicalFile)
        require(source.length()==sourceWidth.toLong()*sourceHeight*12L)
        val part=File(destination.parentFile,destination.name+".part")
        require(part.canonicalFile!=source.canonicalFile);check(!part.exists()||part.delete())
        try {
            RandomAccessFile(source,"r").use{input->FileOutputStream(part).use{file->
                val out=BufferedOutputStream(file,64*1024);val bytes=ByteArray(sourceWidth*12)
                val encoded=ByteBuffer.allocate(outputWidth*12).order(ByteOrder.LITTLE_ENDIAN)
                walk(sourceWidth,outputWidth,outputHeight,map,checkActive,read={index,row->
                    input.seek(index.toLong()*sourceWidth*12L);input.readFully(bytes)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(row)
                    require(row.all{it.isFinite()}){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_e47ae3f26387)}
                },compose=compose,write={_,row->encoded.clear();encoded.asFloatBuffer().put(row);out.write(encoded.array())})
                out.flush();file.fd.sync()
            }}
            checkActive();check(part.length()==outputWidth.toLong()*outputHeight*12L)
            Files.move(part.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{part.delete()}
    }
    fun bitmap(photo:Bitmap,outputWidth:Int,outputHeight:Int,map:FinalImageSampling,checkActive:()->Unit,
               compose:(Int,FloatArray,FloatArray,Double,FloatArray)->Unit):Bitmap {
        checkActive();require(maxOf(photo.width,photo.height)<=2048){dev.filmroom.i18n.L10n.text(dev.filmroom.R.string.text_5bba0c1f8416)}
        val out=Bitmap.createBitmap(outputWidth,outputHeight,Bitmap.Config.ARGB_8888)
        try {
            val ints=IntArray(photo.width);val pixels=IntArray(outputWidth)
            walk(photo.width,outputWidth,outputHeight,map,checkActive,read={y,row->
                photo.getPixels(ints,0,photo.width,0,y,photo.width,1)
                for(x in ints.indices){val c=ints[x];row[x*3]=LinearRgb.srgb8ToLinear[(c ushr 16)and 255];row[x*3+1]=LinearRgb.srgb8ToLinear[(c ushr 8)and 255];row[x*3+2]=LinearRgb.srgb8ToLinear[c and 255]}
            },compose=compose,write={y,row->
                for(x in pixels.indices)pixels[x]=(0xff shl 24)or(OutputColorSpace.SRGB.encode8(row[x*3])shl 16)or(OutputColorSpace.SRGB.encode8(row[x*3+1])shl 8)or OutputColorSpace.SRGB.encode8(row[x*3+2])
                out.setPixels(pixels,0,outputWidth,0,y,outputWidth,1)
            })
            return out
        }catch(t:Throwable){out.recycle();throw t}
    }
}
