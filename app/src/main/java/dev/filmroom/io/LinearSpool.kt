package dev.filmroom.io

import android.graphics.Bitmap
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Immutable, bounded linear-sRGB engine input. Display encoding must never feed RAW effects. */
class LinearPreview internal constructor(val width:Int,val height:Int,private val storage:ByteBuffer) {
    /** A fresh cursor for each native call; readers cannot mutate the source cache. */
    fun buffer():ByteBuffer=storage.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).apply { rewind() }
}

/** Bounded linear-light resampling for effect previews and exports; never quantizes RAW input. */
object LinearSpool {
    fun linearPreview(file:File,width:Int,height:Int,maxEdge:Int,job:CoroutineContext):LinearPreview {
        require(maxEdge in 1..dev.filmroom.engine.NativeMemoryPolicy.DETAIL_PREVIEW_EDGE)
        return boundedLinearPreview(file,width,height,maxEdge,job)
    }
    /** Separate1536 source; ordinary RAW preview cache and its768 limit stay unchanged. */
    internal fun refinedPreview(file:File,width:Int,height:Int,job:CoroutineContext,maxEdge:Int=dev.filmroom.engine.NativeMemoryPolicy.REFINED_PREVIEW_EDGE):LinearPreview {
        require(maxEdge in 1..dev.filmroom.engine.NativeMemoryPolicy.ZOOM_REFINED_MAX_EDGE)
        return boundedLinearPreview(file,width,height,maxEdge,job)
    }
    private fun boundedLinearPreview(file:File,width:Int,height:Int,maxEdge:Int,job:CoroutineContext):LinearPreview {
        job.ensureActive()
        val size=PhotoLimits.fit(width,height,maxEdge)
        val data=ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(size.width,size.height),12)).order(ByteOrder.nativeOrder())
        rows(file,width,height,size.width,size.height,job){_,row->row.forEach { data.putFloat(it) }}
        job.ensureActive();data.rewind()
        return LinearPreview(size.width,size.height,data)
    }

    fun preview(file:File,width:Int,height:Int,maxEdge:Int,job:CoroutineContext):Bitmap {
        val size=PhotoLimits.fit(width,height,maxEdge)
        val bitmap=Bitmap.createBitmap(size.width,size.height,Bitmap.Config.ARGB_8888)
        try {
            val pixels=IntArray(size.width)
            rows(file,width,height,size.width,size.height,job){y,row->
                for(x in pixels.indices)pixels[x]=0xff000000.toInt() or (LinearRgb.toSrgb8(row[x*3]) shl 16) or (LinearRgb.toSrgb8(row[x*3+1]) shl 8) or LinearRgb.toSrgb8(row[x*3+2])
                bitmap.setPixels(pixels,0,size.width,0,y,size.width,1)
            }
            job.ensureActive();return bitmap
        } catch(t:Throwable){bitmap.recycle();throw t}
    }
    fun resize(file:File,width:Int,height:Int,target:File,size:PhotoLimits.Dimensions,job:CoroutineContext) {
        require(target.createNewFile())
        var success=false
        try {
            target.outputStream().buffered(256*1024).use {output->
                val bytes=ByteBuffer.allocate(size.width*12).order(ByteOrder.LITTLE_ENDIAN)
                rows(file,width,height,size.width,size.height,job){_,row->bytes.clear();row.forEach{bytes.putFloat(it)};output.write(bytes.array())}
            }
            job.ensureActive();success=true
        } finally {if(!success)target.delete()}
    }
    fun fromBitmap(bitmap:Bitmap,target:File,job:CoroutineContext) {
        require(target.createNewFile());var success=false
        try {
            target.outputStream().buffered(256*1024).use {out->
                val colors=IntArray(bitmap.width);val bytes=ByteBuffer.allocate(bitmap.width*12).order(ByteOrder.LITTLE_ENDIAN)
                repeat(bitmap.height){y->job.ensureActive();bitmap.getPixels(colors,0,bitmap.width,0,y,bitmap.width,1);bytes.clear()
                    colors.forEach{c->bytes.putFloat(LinearRgb.srgb8ToLinear[(c ushr 16)and 255]);bytes.putFloat(LinearRgb.srgb8ToLinear[(c ushr 8)and 255]);bytes.putFloat(LinearRgb.srgb8ToLinear[c and 255])};out.write(bytes.array())}
            };job.ensureActive();success=true
        }finally{if(!success)target.delete()}
    }
    private fun rows(file:File,width:Int,height:Int,outWidth:Int,outHeight:Int,job:CoroutineContext,consumer:(Int,FloatArray)->Unit){
        require(file.length()==LinearRgb.byteCount(width,height))
        RandomAccessFile(file,"r").use {input->
            val a=ByteArray(width*12);val b=ByteArray(width*12)
            val af=ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();val bf=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val row=FloatArray(outWidth*3)
            repeat(outHeight){y->job.ensureActive();val fy=((y+0.5)*height/outHeight-0.5).coerceIn(0.0,(height-1).toDouble());val y0=floor(fy).toInt();val y1=(y0+1).coerceAtMost(height-1);val ty=(fy-y0).toFloat()
                input.seek(y0.toLong()*width*12);input.readFully(a);input.seek(y1.toLong()*width*12);input.readFully(b)
                repeat(outWidth){x->val fx=((x+0.5)*width/outWidth-0.5).coerceIn(0.0,(width-1).toDouble());val x0=floor(fx).toInt();val x1=(x0+1).coerceAtMost(width-1);val tx=(fx-x0).toFloat()
                    repeat(3){c->val p=af.get(x0*3+c)*(1-tx)+af.get(x1*3+c)*tx;val q=bf.get(x0*3+c)*(1-tx)+bf.get(x1*3+c)*tx;val v=p*(1-ty)+q*ty;require(v.isFinite());row[x*3+c]=v}}
                consumer(y,row)
            }
        }
    }
}
