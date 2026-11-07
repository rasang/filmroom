package dev.filmroom.border

import android.graphics.Bitmap
import dev.filmroom.imprint.ImprintPlan
import dev.filmroom.io.LinearPreview
import dev.filmroom.io.LinearRgb
import dev.filmroom.io.OutputColorSpace
import dev.filmroom.model.EditorState
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Versioned artistic input, NOT a measured negative-density reconstruction.
 * Existing display-referred edge artwork is linearized once, composed with the original
 * scene, and both enter camera/film/print/scan together. Only no-emulsion holes are restored
 * after imaging. The meter always receives the separate original scene, never this canvas.
 */
object PreImagingInput {
    const val VERSION=1
    const val ALGORITHM="artistic-source-edge-linear-v1:clear-holes-v1"
    fun snapshot(params:EditorState,sourceWidth:Int,sourceHeight:Int,canonicalWidth:Int,canonicalHeight:Int,provider:BorderProvider=BorderCatalog):BorderSnapshot {
        require(params.preImagingBorder)
        return provider.snapshot(params.frameStock,sourceWidth,sourceHeight,canonicalWidth,canonicalHeight,
            params.composition,params.negativeWhiteHoles,params.outputRoute,params.effectiveMediumFormat,params.effectiveMediumPortrait)
    }
    fun nativeJson(s:BorderSnapshot,meteringPath:File?=null)=JSONObject().apply {
        put("version",VERSION);put("frameWidth",s.outputWidth);put("frameHeight",s.outputHeight)
        put("photoWidth",s.photoWidth);put("photoHeight",s.photoHeight)
        put("meteringWidth",s.sourceWidth);put("meteringHeight",s.sourceHeight)
        meteringPath?.let{put("meteringPath",it.absolutePath)}
    }
    fun previewWorkingBytes(s:BorderSnapshot,raster:BorderRaster,imprint:Boolean)=
        BorderCompositor.workingBytes(s,raster)+s.outputBytes+(if(imprint)ImprintPlan.MAX_WORKING_BYTES else 0)
    /** Pre-imaging input plus the final hole-only float output. Native scratch is separately
     * planned at the complete frame dimensions. Source input remains intact for AE/resume. */
    fun additionalDiskBytes(s:BorderSnapshot)=Math.addExact(Math.multiplyExact(s.outputBytes,2L),8L*1024*1024)

    /** Frame planning cannot stand in for an uncropped source spool: panoramas may
     * contain far more pixels than the selected film aperture. Existing files already
     * reduce free disk; charge only a source that still needs to be prepared. */
    fun nativeAndPendingSourceDiskBytes(plan:dev.filmroom.engine.NativeExportPlan,s:BorderSnapshot,sourceReady:Boolean):Long {
        require(plan.width==s.outputWidth && plan.height==s.outputHeight)
        val pending=if(sourceReady)0L else Math.multiplyExact(s.sourceWidth.toLong()*s.sourceHeight,12L)
        return Math.addExact(dev.filmroom.engine.ExportStoragePolicy.afterInput(plan),pending)
    }

    private fun hole(s:BorderSnapshot,raster:BorderRaster,x:Int,y:Int):Double =
        raster.holeCoverage(s,x+.5,y+.5).also{require(it.isFinite() && it in 0.0..1.0)}
    private fun matte(s:BorderSnapshot)=if((s.appearance?.holeMatteSrgb ?: 0xffffff)==0)0f else 1f
    private fun inputRow(s:BorderSnapshot,raster:BorderRaster,imprint:ImprintPlan?,map:BorderSampling,y:Int,first:FloatArray,second:FloatArray,fy:Double,row:FloatArray) {
        BorderCompositor.composeSampledRow(s,raster,OutputColorSpace.SRGB,y,map,first,second,fy,row)
        val white=matte(s)
        for(x in 0 until s.outputWidth){
            val h=hole(s,raster,x,y)
            if(h==0.0)continue
            // Remove the display matte before exposure: a white cut-out must not become
            // a fictitious high-intensity lamp feeding halation and camera diffusion.
            // Partial coverage is unpremultiplied exactly once; no RGB-based hole guessing.
            for(c in 0..2)row[x*3+c]=if(h>=1.0-1e-9)0f else ((row[x*3+c]-h*white)/(1-h)).coerceAtLeast(0.0).toFloat()
        }
        imprint?.blendRow(y,row,OutputColorSpace.SRGB)
        require(row.all{it.isFinite()})
    }
    fun file(source:File,destination:File,s:BorderSnapshot,raster:BorderRaster,imprint:ImprintPlan?=null,checkActive:()->Unit={}) {
        checkActive();raster.validate(s)
        val effective=imprint?.frameRaster(s,raster) ?: raster
        val map=BorderSampling(s)
        FinalImageRows.file(source,destination,s.sourceWidth,s.sourceHeight,s.outputWidth,s.outputHeight,map,checkActive){y,a,b,fy,row->
            inputRow(s,effective,imprint,map,y,a,b,fy,row)
        }
    }
    /** No 8-bit intermediate: the RAW linear input can retain values above one. */
    fun preview(bitmap:Bitmap?,linear:LinearPreview?,s:BorderSnapshot,raster:BorderRaster,imprint:ImprintPlan?=null,checkActive:()->Unit={}):LinearPreview {
        checkActive();raster.validate(s);require((bitmap==null)!=(linear==null))
        val w=bitmap?.width ?: checkNotNull(linear).width;val h=bitmap?.height ?: checkNotNull(linear).height
        require(w==s.sourceWidth && h==s.sourceHeight)
        require(s.outputWidth.toLong()*s.outputHeight<=dev.filmroom.engine.NativeMemoryPolicy.DETAIL_PREVIEW_EDGE.toLong()*dev.filmroom.engine.NativeMemoryPolicy.DETAIL_PREVIEW_EDGE)
        val effective=imprint?.frameRaster(s,raster) ?: raster
        val map=BorderSampling(s)
        val output=ByteBuffer.allocateDirect(Math.toIntExact(s.outputBytes)).order(ByteOrder.nativeOrder())
        val floats=linear?.buffer()?.asFloatBuffer();val ints=bitmap?.let{IntArray(w)}
        FinalImageRows.walk(w,s.outputWidth,s.outputHeight,map,checkActive,read={y,row->
            if(floats!=null){floats.position(y*w*3);floats.get(row)}else{
                bitmap!!.getPixels(checkNotNull(ints),0,w,0,y,w,1)
                for(x in 0 until w){val p=ints[x];row[x*3]=LinearRgb.srgb8ToLinear[(p ushr 16)and 255];row[x*3+1]=LinearRgb.srgb8ToLinear[(p ushr 8)and 255];row[x*3+2]=LinearRgb.srgb8ToLinear[p and 255]}
            }
            require(row.all{it.isFinite()})
        },compose={y,a,b,fy,row->inputRow(s,effective,imprint,map,y,a,b,fy,row)},write={_,row->row.forEach(output::putFloat)})
        checkActive();output.rewind();return LinearPreview(s.outputWidth,s.outputHeight,output)
    }
    internal fun restoreHoleRow(s:BorderSnapshot,raster:BorderRaster,y:Int,row:FloatArray) {
        val matte=matte(s)
        for(x in 0 until s.outputWidth){val h=hole(s,raster,x,y);if(h>0)for(c in 0..2)row[x*3+c]=(row[x*3+c]*(1-h)+matte*h).toFloat()}
    }
    /** Only clear/perforation pixels are masked here. No photograph, rail, text or date
     * is overlaid after imaging, and neutral mattes are invariant across output primaries. */
    fun restoreHoles(bitmap:Bitmap,s:BorderSnapshot,raster:BorderRaster,checkActive:()->Unit={}) {
        raster.validate(s);require(bitmap.width==s.outputWidth && bitmap.height==s.outputHeight && bitmap.isMutable)
        val ints=IntArray(s.outputWidth);val row=FloatArray(s.outputWidth*3)
        repeat(s.outputHeight){y->
            checkActive();bitmap.getPixels(ints,0,s.outputWidth,0,y,s.outputWidth,1)
            for(x in ints.indices){val p=ints[x];row[x*3]=LinearRgb.srgb8ToLinear[(p ushr 16)and 255];row[x*3+1]=LinearRgb.srgb8ToLinear[(p ushr 8)and 255];row[x*3+2]=LinearRgb.srgb8ToLinear[p and 255]}
            restoreHoleRow(s,raster,y,row)
            for(x in ints.indices)if(hole(s,raster,x,y)>0)ints[x]=(0xff shl 24)or(OutputColorSpace.SRGB.encode8(row[x*3])shl 16)or(OutputColorSpace.SRGB.encode8(row[x*3+1])shl 8)or OutputColorSpace.SRGB.encode8(row[x*3+2])
            bitmap.setPixels(ints,0,s.outputWidth,0,y,s.outputWidth,1)
        }
    }
    fun restoreHoles(source:File,destination:File,s:BorderSnapshot,raster:BorderRaster,checkActive:()->Unit={}) {
        raster.validate(s);require(source.canonicalFile!=destination.canonicalFile && source.length()==s.outputBytes)
        val part=File(destination.parentFile,destination.name+".part")
        require(part.canonicalFile!=source.canonicalFile);check(!part.exists() || part.delete())
        try {
            source.inputStream().buffered(65536).use{input->FileOutputStream(part).use{file->
                val output=BufferedOutputStream(file,65536);val bytes=ByteArray(s.outputWidth*12);val row=FloatArray(s.outputWidth*3)
                val data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val stream=DataInputStream(input)
                repeat(s.outputHeight){y->checkActive();stream.readFully(bytes);data.position(0);data.get(row);require(row.all{it.isFinite()});restoreHoleRow(s,raster,y,row);data.position(0);data.put(row);output.write(bytes)}
                output.flush();file.fd.sync()
            }}
            checkActive();check(part.length()==s.outputBytes)
            Files.move(part.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{part.delete()}
    }
}
