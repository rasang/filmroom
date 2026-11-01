package dev.filmroom

import android.app.Application
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.io.*
import dev.filmroom.model.CompositionTransform
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToInt

/** Optional test artifact generation only; no production code/asset or test-count change. */
internal object RuntimeBorderVisualQa {
    private val representative=setOf("kodak_portra_400","kodak_portra_800","kodak_ektachrome_100","fujifilm_c200","kodak_vision3_500t","kodak_ektar_100","kodak_gold_200","kodak_ultramax_400","kodak_portra_160","fujifilm_xtra_400","kodak_vision3_250d","kodak_vision3_200t")
    fun render(output:File)=runBlocking {withContext(Dispatchers.IO){
        val out=output.canonicalFile
        val repository=generateSequence(File(System.getProperty("user.dir")).canonicalFile){it.parentFile}.firstOrNull{File(it,"settings.gradle.kts").isFile}
        require(repository==null || !out.toPath().startsWith(repository.toPath())){"Personal-photo QA must be outside repository/source packages"}
        check(out.isDirectory || out.mkdirs());require(out.usableSpace>=1024L*1024*1024)
        val photoFile=System.getenv("FILMROOM_BORDER_QA_PHOTO")?.let(::File)
        val photoHash=photoFile?.let(::sha256)
        val upright=photoFile?.let(::decodeUpright)
        val orientations=System.getenv("FILMROOM_BORDER_QA_ORIENTATIONS")?.split(',')?.toSet() ?: setOf("landscape","portrait")
        require(orientations.isNotEmpty() && orientations.all{it in setOf("landscape","portrait")})
        val sources=linkedMapOf<String,Bitmap>().apply{for(o in listOf("landscape","portrait"))if(o in orientations)put(o,prepareSource(upright,o=="portrait"))}
        val route=System.getenv("FILMROOM_BORDER_QA_ROUTE")
        val white=System.getenv("FILMROOM_BORDER_QA_NEGATIVE_WHITE")!="0"
        val app=ApplicationProvider.getApplicationContext<Application>()
        val work=File(out,".qa-work-${UUID.randomUUID()}").apply{mkdir()}
        val records=JSONArray()
        try{
            assertEquals(18,CandidateBorderDefinitions.definitions.size)
            val requested=System.getenv("FILMROOM_BORDER_QA_STOCKS")?.split(',')?.map{it.trim()}?.filter{it.isNotEmpty()}?.toSet()
            require(requested==null || requested.all{stock->CandidateBorderDefinitions.definitions.any{it.spec.stock==stock}}){"Unknown QA stock filter"}
            val definitions=CandidateBorderDefinitions.definitions.filter{requested==null || it.spec.stock in requested}.map{if(route!=null && FrameAppearanceContext.forProfile(it.spec.stock,route,white).material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION)DirectBorderDefinitions.selectedDefinition(it.spec.stock,FrameAppearanceContext.forProfile(it.spec.stock,route,white)) else CandidateBorderDefinitions.selectedDefinition(it.spec.stock,white)}
            for(definition in definitions){
                val raster=StraightAlphaBorderLoader.load(definition.spec,{app.assets.open(definition.assetPath)})
                for((orientation,source) in sources){
                    val input=File(work,"source.rgb");writeLinear(input,source)
                    val variants=if(definition.spec.stock in representative)listOf("center" to CompositionTransform(),"panzoom" to CompositionTransform(.58,.56,1.25))else listOf("center" to CompositionTransform())
                    for((variant,transform) in variants){
                        val s=BorderSnapshot.fixed135(definition.spec.stock,definition.spec.version,source.width,source.height,composition=transform).let{if(route==null)it else it.copy(appearance=FrameAppearanceContext.forProfile(definition.spec.stock,route,white))}
                        val preview=BorderPreview.compose(source,s,raster)
                        val framed=File(work,"framed.rgb")
                        val name="${definition.spec.stock}-$orientation-$variant"
                        val encoded=File(out,"$name.png")
                        try{
                            BorderCompositor.composite(input,framed,s,raster,OutputColorSpace.SRGB)
                            framed.inputStream().use{floatInput->encoded.outputStream().use{png->LinearRgbPng.encode(floatInput,s.outputWidth,s.outputHeight,png)}}
                            val final=checkNotNull(BitmapFactory.decodeFile(encoded.absolutePath,BitmapFactory.Options().apply{inScaled=false;inPreferredConfig=Bitmap.Config.ARGB_8888}))
                            try{
                                assertEquals(preview.width,final.width);assertEquals(preview.height,final.height)
                                val a=IntArray(final.width);val b=IntArray(final.width)
                                for(y in 0 until final.height){preview.getPixels(a,0,a.size,0,y,a.size,1);final.getPixels(b,0,b.size,0,y,b.size,1);assertArrayEquals("Final PNG vs shared preview $name row$y",a,b)}
                                if(definition.spec.stock in representative && variant=="center")saveSeams(final,s,out,name)
                            }finally{final.recycle()}
                            val record=JSONObject().apply{
                                put("file",encoded.name);put("runtime_asset",definition.assetPath);put("runtime_sha256",definition.spec.sha256)
                                put("source",if(photoFile==null)"authored colored pattern; no personal photo" else if(orientation=="landscape")"test-only upright landscape crop centered vertically at0.60; person NOT rotated" else "upright original composition, bounded downscale only")
                                put("source_file_sha256",photoHash ?: JSONObject.NULL);put("source_width",source.width);put("source_height",source.height)
                                put("photo_decode","bounded BitmapFactory ARGB8888 preferred sRGB, inScaled=false, EXIF1-8 applied before QA crop; no additional color grading")
                                put("orientation",orientation);put("variant",variant);put("snapshot",s.json())
                                put("preview_equals_final_encoded_png",true);put("film_science_run",false)
                                put("source_notice",System.getenv("FILMROOM_BORDER_QA_NOTICE") ?: "Existing QA source contract")
                                put("notice","Runtime final border/composition demonstration only; not film-color-effect acceptance.4px-at3072 feather remains provisional.")
                            }
                            records.put(record);File(out,"$name.json").writeText(record.toString(2)+"\n")
                        }finally{preview.recycle();framed.delete()}
                    }
                    input.delete()
                }
            }
            assertEquals("Original photo must remain byte-identical",photoHash,photoFile?.let(::sha256))
            for(orientation in sources.keys)contactSheet(out,orientation,definitions.map{it.spec.stock})
            if("portrait" in sources)comparisonSheet(out,representative.filter{stock->definitions.any{it.spec.stock==stock}})
            File(out,"visual-qa-manifest.json").writeText(JSONObject().put("scope","Actual runtime WebP + BorderPreview + float BorderCompositor + LinearRgbPng; no film-science processing")
                .put("source_photo_sha256",photoHash ?: JSONObject.NULL).put("original_photo_modified_or_copied",false)
                .put("personal_photo_outputs_outside_repository",true).put("renders",records).toString(2)+"\n")
            File(out,"README.txt").writeText("Runtime final border / composition QA, NOT film-science color-effect acceptance.\nAll full PNGs are real shared float-compositor+PNG-encoder outputs and matched BorderPreview pixel-for-pixel.\nContact sheets arrange these final images; seam details only enlarge an actual output crop.\nPortrait uses upright original composition after bounded downscale. Landscape is an explicitly test-only crop of the upright photo (vertical center0.60), not a sideways person.\nPanzoom examples use normalized center(.58,.56),zoom1.25. Originals are read only and never copied into this directory or the app.\n4px total feather at3072 remains provisional. No scanner or manufacturing-precision claim.\n")
        System.getenv("FILMROOM_BORDER_QA_NOTICE")?.let{File(out,"SOURCE-NOTICE.txt").writeText(it+"\nQA rendering may resample the screenshot crop; this is not original-photo quality or a science export.\n")}
        }finally{sources.values.forEach{it.recycle()};upright?.recycle();work.deleteRecursively()}
    }}
    private fun decodeUpright(file:File):Bitmap {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true;inScaled=false};BitmapFactory.decodeFile(file.absolutePath,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0)
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
        val raw=checkNotNull(BitmapFactory.decodeFile(file.absolutePath,BitmapFactory.Options().apply{inSampleSize=sample;inScaled=false;inPreferredConfig=Bitmap.Config.ARGB_8888;inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB)}))
        val orientation=ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,1).coerceIn(1,8)
        if(orientation==1)return raw
        val mapping=ExifMapping(raw.width,raw.height,orientation)
        val pixels=IntArray(raw.width*raw.height);raw.getPixels(pixels,0,raw.width,0,0,raw.width,raw.height)
        val result=Bitmap.createBitmap(mapping.width,mapping.height,Bitmap.Config.ARGB_8888)
        try{val row=IntArray(mapping.width);for(y in 0 until mapping.height){for(x in row.indices)row[x]=pixels[mapping.rawY(x,y)*raw.width+mapping.rawX(x,y)];result.setPixels(row,0,row.size,0,y,row.size,1)}}catch(t:Throwable){result.recycle();throw t}finally{raw.recycle()}
        return result
    }
    private fun prepareSource(photo:Bitmap?,portrait:Boolean):Bitmap {
        if(photo!=null && portrait){val scale=1536.0/maxOf(photo.width,photo.height);return Bitmap.createScaledBitmap(photo,(photo.width*scale).roundToInt(),(photo.height*scale).roundToInt(),true).let{if(it===photo)it.copy(Bitmap.Config.ARGB_8888,false)else it}}
        val w=if(portrait)1024 else 1536;val h=if(portrait)1536 else 1024
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawColor(Color.WHITE)
        if(photo!=null){
            val cropW=minOf(photo.width.toDouble(),photo.height*1.5);val cropH=cropW/1.5
            val left=((photo.width-cropW)/2).roundToInt();val top=(photo.height*.60-cropH/2).coerceIn(0.0,photo.height-cropH).roundToInt()
            canvas.drawBitmap(photo,Rect(left,top,(left+cropW).roundToInt(),(top+cropH).roundToInt()),Rect(0,0,w,h),paint)
        }else{
            for(y in 0 until h step 64)for(x in 0 until w step 64){paint.color=Color.HSVToColor(floatArrayOf(((x/64*29+y/64*17)%360).toFloat(),.35f,.45f+.4f*((x/64+y/64)%2)));canvas.drawRect(x.toFloat(),y.toFloat(),(x+64).toFloat(),(y+64).toFloat(),paint)}
            paint.color=Color.WHITE;paint.strokeWidth=7f;canvas.drawLine(0f,0f,w.toFloat(),h.toFloat(),paint);canvas.drawLine(w.toFloat(),0f,0f,h.toFloat(),paint)
            paint.color=Color.BLACK;paint.style=Paint.Style.STROKE;paint.strokeWidth=12f;canvas.drawCircle(w*.58f,h*.56f,140f,paint);paint.style=Paint.Style.FILL
            paint.textSize=44f;canvas.drawText("SYNTHETIC QA / NOT A PHOTO",40f,70f,paint)
        }
        return bitmap
    }
    private fun writeLinear(file:File,bitmap:Bitmap){
        val pixels=IntArray(bitmap.width);val row=ByteBuffer.allocate(bitmap.width*12).order(ByteOrder.LITTLE_ENDIAN)
        file.outputStream().buffered().use{out->for(y in 0 until bitmap.height){bitmap.getPixels(pixels,0,pixels.size,0,y,pixels.size,1);row.clear();for(pixel in pixels){row.putFloat(LinearRgb.srgb8ToLinear[(pixel ushr 16)and 255]);row.putFloat(LinearRgb.srgb8ToLinear[(pixel ushr 8)and 255]);row.putFloat(LinearRgb.srgb8ToLinear[pixel and 255])};out.write(row.array())}}
    }
    private fun saveSeams(bitmap:Bitmap,s:BorderSnapshot,dir:File,name:String){
        fun detail(rect:Rect,suffix:String){
            val crop=Bitmap.createBitmap(rect.width()*8,rect.height()*8,Bitmap.Config.ARGB_8888)
            try{Canvas(crop).drawBitmap(bitmap,rect,Rect(0,0,crop.width,crop.height),Paint().apply{isFilterBitmap=false});File(dir,"$name-$suffix-seam-8x.png").outputStream().use{check(crop.compress(Bitmap.CompressFormat.PNG,100,it))}}
            finally{crop.recycle()}
        }
        val left=(s.apertureLeft.toInt()-32).coerceIn(0,bitmap.width-64);val middleY=(s.apertureTop+s.photoHeight/2-96).toInt().coerceIn(0,bitmap.height-192)
        detail(Rect(left,middleY,left+64,middleY+192),"left")
        val middleX=(s.apertureLeft+s.photoWidth/2-96).toInt().coerceIn(0,bitmap.width-192);val top=(s.apertureTop.toInt()-32).coerceIn(0,bitmap.height-64)
        detail(Rect(middleX,top,middleX+192,top+64),"top")
    }
    private fun sha256(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use{input->val row=ByteArray(65536);while(true){val n=input.read(row);if(n<0)break;digest.update(row,0,n)}}
        return digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
    }
    private fun contactSheet(dir:File,orientation:String,names:List<String>){
        val cellW=300;val cellH=340
        val sheet=Bitmap.createBitmap(cellW*6,cellH*((names.size+5)/6)+60,Bitmap.Config.ARGB_8888);val canvas=Canvas(sheet);canvas.drawColor(Color.rgb(245,245,245));val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color=Color.BLACK;paint.textSize=22f;canvas.drawText("ACTUAL RUNTIME / $orientation / FINAL BORDER ONLY — NO FILM SCIENCE",12f,35f,paint)
        try{for((index,name)in names.withIndex()){
            val bitmap=checkNotNull(BitmapFactory.decodeFile(File(dir,"$name-$orientation-center.png").absolutePath,BitmapFactory.Options().apply{inSampleSize=4;inScaled=false}))
            try{val x=(index%6)*cellW;val y=(index/6)*cellH+60;val scale=minOf((cellW-12f)/bitmap.width,(cellH-40f)/bitmap.height);val width=bitmap.width*scale;val height=bitmap.height*scale
                canvas.drawBitmap(bitmap,null,RectF(x+(cellW-width)/2,y.toFloat(),x+(cellW+width)/2,y+height),paint);paint.textSize=14f;paint.color=Color.BLACK;canvas.drawText(name,x+6f,y+cellH-12f,paint)
            }finally{bitmap.recycle()}
        };File(dir,"contact-$orientation.png").outputStream().use{check(sheet.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{sheet.recycle()}
    }
    private fun comparisonSheet(dir:File,names:List<String>){
        if(names.isEmpty())return
        val sheet=Bitmap.createBitmap(800,names.size*430+50,Bitmap.Config.ARGB_8888);val canvas=Canvas(sheet);canvas.drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{color=Color.BLACK;textSize=20f}
        canvas.drawText("PORTRAIT: CENTER vs PAN/ZOOM — FINAL BORDER ONLY",12f,30f,paint)
        try{for((index,name)in names.withIndex())for((column,variant)in listOf("center","panzoom").withIndex()){
            val bitmap=checkNotNull(BitmapFactory.decodeFile(File(dir,"$name-portrait-$variant.png").absolutePath,BitmapFactory.Options().apply{inSampleSize=4;inScaled=false}))
            try{val y=50+index*430;val scale=minOf(388f/bitmap.width,390f/bitmap.height);val w=bitmap.width*scale;val h=bitmap.height*scale
                canvas.drawBitmap(bitmap,null,RectF(column*400+(400-w)/2,y.toFloat(),column*400+(400+w)/2,y+h),paint);paint.textSize=15f;canvas.drawText("$name / $variant",column*400+5f,y+420f,paint)
            }finally{bitmap.recycle()}
        };File(dir,"contact-portrait-center-vs-panzoom.png").outputStream().use{check(sheet.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{sheet.recycle()}
    }
}
