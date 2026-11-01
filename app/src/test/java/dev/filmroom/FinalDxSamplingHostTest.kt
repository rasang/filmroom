package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.border.*
import dev.filmroom.codec.NativeCodecBridge
import dev.filmroom.codec.PhotoCodecs
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.pow

/** Dense visible-band logical sampling, never optical-scanner or manufacturing certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalDxSamplingHostTest {
    private val direct=System.getenv("FILMROOM_DX_DIRECT")=="1"
    private val white=System.getenv("FILMROOM_DX_WHITE")!="0"
    private val corpus=File(System.getenv("FILMROOM_BORDER_CORPUS_ROOT") ?: "/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/typography-v0114-r2/qa-corpus")
    private val contractFile=File(System.getenv("FILMROOM_DX_CONTRACT") ?: "/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/typography-v0114-r2/dx-sampling-contract.json")
    private fun decode(v:Double)=if(v<=.04045)v/12.92 else ((v+.055)/1.055).pow(2.4)
    private fun luminance(pixel:Int)=(decode(((pixel ushr 16)and 255)/255.0)+decode(((pixel ushr 8)and 255)/255.0)+decode((pixel and 255)/255.0))/3
    private fun stockRows()=JSONObject(contractFile.readText()).getJSONArray("stocks")
    private fun spec(stock:JSONObject,webp:Boolean):Pair<StraightAlphaBorderLoader.Spec,()->java.io.InputStream>{
        val name=stock.getString("physical_stock")
        val definition=if(direct)DirectBorderDefinitions.selectedDefinition(name,FrameAppearanceContext.forProfile(name,"direct",white))else CandidateBorderDefinitions.definitions.single{it.spec.stock==name}
        if(webp)return definition.spec to {ApplicationProvider.getApplicationContext<Application>().assets.open(definition.assetPath)}
        val file=File(corpus,"$name.png")
        return definition.spec.copy(sha256=stock.getString("asset_sha256"),encodedBytes=file.length().toInt(),encoding=StraightAlphaBorderLoader.Encoding.PNG) to {file.inputStream()}
    }
    private fun snapshot(info:StraightAlphaBorderLoader.Spec,w:Int,h:Int)=BorderSnapshot.fixed135(info.stock,info.version,w,h)
        .let{if(direct)it.copy(appearance=FrameAppearanceContext.forProfile(info.stock,"direct",white))else it}
    /** Independent pre-revision alpha: v2 is forbidden to change any hole/window transparency. */
    private fun independentMask(stock:JSONObject):ByteArray {
        val oldRoot=File("/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/fixed135-complete18-review-v1/assets-only")
        val oldContract=JSONObject(File("/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/fixed135-final-dx-sampling-contract.json").readText()).getJSONArray("stocks")
        val name=stock.getString("physical_stock")
        val old=(0 until oldContract.length()).map{oldContract.getJSONObject(it)}.single{it.getString("physical_stock")==name}
        val file=File(oldRoot,"$name.png");val sha=old.getString("asset_sha256")
        val spec=StraightAlphaBorderLoader.Spec(name,"independent-v1-alpha",sha,file.length().toInt())
        val pixels=StraightAlphaBorderLoader.decodeStraight(spec,{file.inputStream()})
        stock.put("independent_alpha_png_sha256",sha)
        return ByteArray(pixels.size){(pixels[it] ushr 24).toByte()}
    }
    private fun verifyMask(pixels:IntArray,mask:ByteArray){
        assertEquals(1520*1400,pixels.size);assertEquals(pixels.size,mask.size)
        for(i in pixels.indices){val expected=mask[i].toInt()and 255;val actual=pixels[i] ushr 24;if(expected!=actual)assertEquals("Unchanged independent alpha at native pixel$i",expected,actual)}
    }
    private fun calibration(stock:JSONObject,source:IntArray):Double {
        val symbol=stock.getJSONArray("symbols").getJSONObject(0);val placement=symbol.getJSONArray("placement")
        val clock=symbol.getString("clock31");val x=placement.getInt(0);val w=placement.getInt(2)
        val clearRows=symbol.getJSONObject("unobscured_sample_rows").getJSONArray("clock")
        val calibrationY=clearRows.getInt(clearRows.length()/2)
        stock.put("threshold_calibration_native_y",calibrationY)
        fun sample(bit:Char)=listOf(-3,0,3).map{offset->luminance(source[calibrationY*1520+x+w*clock.indexOf(bit)+w/2+offset])}.average()
        val one=sample('1');val zero=sample('0');val light=stock.getString("bit1_polarity")=="lighter_than_material"
        assertTrue("Independent declared polarity",if(light)one>zero else one<zero)
        assertTrue("Native reference contrast",kotlin.math.abs(one-zero)>.02)
        return (one+zero)/2
    }
    @Test fun finalPngAndLosslessWebpPreview384RetainIndependentDxLogicalRows(){
        assumeTrue(System.getenv("FILMROOM_BORDER_CORPUS")=="1")
        val stocks=stockRows();assertEquals(9,stocks.length())
        for(i in 0 until stocks.length()){
            val stock=stocks.getJSONObject(i);val(pngSpec,png)=spec(stock,false)
            val mask=independentMask(stock)
            val original=StraightAlphaBorderLoader.decodeStraight(pngSpec,png);verifyMask(original,mask);val threshold=calibration(stock,original)
            for(webp in listOf(false,true)){
                val(info,open)=spec(stock,webp)
                val pixels=if(webp)StraightAlphaBorderLoader.decodeStraight(info,open)else original
                verifyMask(pixels,mask);assertArrayEquals("PNG/runtime WebP full RGBA",original,pixels)
                val raster=StraightAlphaFixed135Raster(info.stock,info.version,1520,1400,pixels,info.featherTotalAt3072,info.holeMatteSrgb)
                val nativeGrid=snapshot(info,1440,960)
                DxVisibleBandVerifier.verify(stock,nativeGrid,threshold,mask,if(webp)"native-source-WebP"else "native-source-PNG"){x,y->luminance(pixels[y*1520+x])}
                for(portrait in listOf(false,true)){
                    val w=if(portrait)256 else 384;val h=if(portrait)384 else 256
                    val snap=snapshot(info,w,h)
                    val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff808080.toInt())}
                    val preview=BorderPreview.compose(source,snap,raster)
                    try{DxVisibleBandVerifier.verify(stock,snap,threshold,mask,if(webp)"preview384-WebP" else "preview384-PNG"){x,y->luminance(preview.getPixel(x,y))}}
                    finally{preview.recycle();source.recycle()}
                }
            }
        }
    }
    @Test fun actualJpeg95PngAndTiff16Exports1536RetainDxLogicalRows()=runBlocking {
        assumeTrue(System.getenv("FILMROOM_BORDER_CORPUS")=="1" && System.getenv("FILMROOM_DX_NATIVE")=="1" && System.getenv("FILMROOM_HOST_JNI_DIR")!=null)
        assertTrue(NativeCodecBridge.available)
        val jobs=File(System.getenv("FILMROOM_DX_WORK_DIR") ?: "/workspace/shared/filmroom-border-webp-assessment/dx-work").apply{mkdirs()}
        require(jobs.usableSpace>1024L*1024*1024){"DX sequential encode needs at least 1 GiB free"}
        val job=File(jobs,UUID.randomUUID().toString()).apply{mkdir()}
        // This shared-disk UUID job is the test's private codec cache, not app/files/export-jobs.
        // Keep production queue scope unchanged; both codec input and output remain inside this root.
        val codecs=PhotoCodecs(job,memorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)})
        try{
            val stocks=stockRows();assertEquals(9,stocks.length())
            for(i in 0 until stocks.length()){
                val stock=stocks.getJSONObject(i);val(pngSpec,png)=spec(stock,false)
                val mask=independentMask(stock);val original=StraightAlphaBorderLoader.decodeStraight(pngSpec,png);verifyMask(original,mask)
                val threshold=calibration(stock,original)
                val(info,open)=spec(stock,true);val pixels=StraightAlphaBorderLoader.decodeStraight(info,open);verifyMask(pixels,mask)
                assertArrayEquals("PNG/runtime WebP full RGBA",original,pixels)
                val raster=StraightAlphaFixed135Raster(info.stock,info.version,1520,1400,pixels,info.featherTotalAt3072,info.holeMatteSrgb)
                for(portrait in listOf(false,true)){
                    val w=if(portrait)1024 else 1536;val h=if(portrait)1536 else 1024
                    val snap=snapshot(info,w,h)
                    val source=File(job,"source.rgb");val row=ByteBuffer.allocate(w*12).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(w*3){row.putFloat(.25f)};source.outputStream().buffered().use{out->repeat(h){out.write(row.array())}}
                    val framed=File(job,"film-result-${UUID.randomUUID()}.rgb")
                    BorderCompositor.composite(source,framed,snap,raster,OutputColorSpace.SRGB)
                    for(format in listOf("PNG","JPEG","TIFF16")){
                        val encoded=File(job,"codec-output-${UUID.randomUUID()}.${if(format=="JPEG")"jpg" else if(format=="PNG")"png" else "tif"}")
                        if(format=="PNG")framed.inputStream().use{input->encoded.outputStream().use{out->LinearRgbPng.encode(input,snap.outputWidth,snap.outputHeight,out)}}
                        else codecs.encode(framed,encoded,snap.outputWidth,snap.outputHeight,format,95,"sRGB")
                        if(format=="TIFF16")TiffSamples(encoded,snap).use{samples->DxVisibleBandVerifier.verify(stock,snap,threshold,mask,"export1536-TIFF16",samples::luminance)}
                        else{
                            val decoded=checkNotNull(BitmapFactory.decodeFile(encoded.absolutePath,BitmapFactory.Options().apply{inScaled=false;inPreferredConfig=Bitmap.Config.ARGB_8888}))
                            try{assertEquals(snap.outputWidth,decoded.width);assertEquals(snap.outputHeight,decoded.height);DxVisibleBandVerifier.verify(stock,snap,threshold,mask,"export1536-$format"){x,y->luminance(decoded.getPixel(x,y))}}
                            finally{decoded.recycle()}
                        }
                        check(encoded.delete())
                    }
                    check(framed.delete());check(source.delete())
                }
            }
        }finally{job.deleteRecursively()}
    }
    private inner class TiffSamples(file:File,s:BorderSnapshot):AutoCloseable {
        private val input=RandomAccessFile(file,"r");private val width=s.outputWidth;private val rows:Int;private val offsets:Triple<Int,Long,Long>
        private fun u16()=java.lang.Short.reverseBytes(input.readShort()).toInt()and 65535
        private fun u32()=Integer.reverseBytes(input.readInt()).toLong()and 0xffffffffL
        init{
            try{
                assertEquals(0x4949,input.readUnsignedShort());assertEquals(42,u16());val root=u32();input.seek(root)
                val entries=mutableMapOf<Int,Triple<Int,Long,Long>>()
                repeat(u16()){val tag=u16();val type=u16();val count=u32();val value=u32();entries[tag]=Triple(type,count,value)}
                assertEquals(s.outputWidth.toLong(),entries.getValue(256).third);assertEquals(s.outputHeight.toLong(),entries.getValue(257).third)
                val bits=entries.getValue(258);assertEquals(3L,bits.second);input.seek(bits.third);repeat(3){assertEquals(16,u16())}
                assertEquals(1L,entries.getValue(259).third) // uncompressed strips, unsigned integer RGB16
                rows=entries.getValue(278).third.toInt();offsets=entries.getValue(273)
            }catch(t:Throwable){input.close();throw t}
        }
        fun luminance(x:Int,y:Int):Double{
            val strip=y/rows;val offset=if(offsets.second==1L)offsets.third else{input.seek(offsets.third+strip*4L);u32()}
            input.seek(offset+((y%rows).toLong()*width+x)*6)
            return (decode(u16()/65535.0)+decode(u16()/65535.0)+decode(u16()/65535.0))/3
        }
        override fun close(){input.close()}
    }
}
