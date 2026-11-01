package dev.filmroom

import android.graphics.Bitmap
import dev.filmroom.border.*
import dev.filmroom.io.*
import dev.filmroom.model.CompositionTransform
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest

/** Explicit opt-in research inputs, never Android assets or a production stock approval.
 * Reflective host ImageIO gives straight ARGB without Android premultiplication quantization.
 * Standard runs skip exactly this one test unless FILMROOM_RGBA_RESEARCH=1 is present.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class ResearchRgbaBorderHostTest {
    private fun decode(file:File):IntArray {
        val image=Class.forName("javax.imageio.ImageIO").getMethod("read",File::class.java).invoke(null,file)
        val type=image.javaClass;val intType=Int::class.javaPrimitiveType!!
        assertEquals(1520,type.getMethod("getWidth").invoke(image));assertEquals(1400,type.getMethod("getHeight").invoke(image))
        return type.getMethod("getRGB",intType,intType,intType,intType,IntArray::class.java,intType,intType)
            .invoke(image,0,0,1520,1400,null,0,1520) as IntArray
    }
    private fun read(file:File)=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let{b->FloatArray(b.remaining()).also{b.get(it)}}
    @Test fun actualNegativeAndBlackResearchAtlasUseSharedFinalOverlayAcrossSizesOrientationsAndPrimaries(){
        assumeTrue("Set FILMROOM_RGBA_RESEARCH=1 to read external, unapproved research PNGs",System.getenv("FILMROOM_RGBA_RESEARCH")=="1")
        val root=File(System.getenv("FILMROOM_RGBA_RESEARCH_ROOT") ?: "/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/fixed135-new3-v1/assets-only")
        val hashes=mapOf("portra800" to "3ff71f3f1bacb8b453f1fb910ef7e976fcd57c7aaaff5ad86443491502b0b5e5","kodachrome64" to "f0c051655533c3e79d0140234d54f09ff42df0b4f1f2835b8f5cf7b80bd6c41f")
        val qaDir=System.getenv("FILMROOM_RGBA_QA_DIR")?.let{File(it).also{directory->check(directory.isDirectory || directory.mkdirs())}}
        val dir=Files.createTempDirectory("research-rgba").toFile()
        try {
            for((stock,hash) in hashes){
                val file=File(root,"$stock-fixed135-36x24.png");assertTrue("Missing explicit research input $file",file.isFile)
                assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)})
                val argb=decode(file)
                assertEquals(0,argb[136*1520+95]);assertEquals(0,argb[1264*1520+95])
                assertEquals(0,argb[220*1520+40]);assertEquals(0,argb[1179*1520+1479])
                val half=argb[80*1520+72]
                assertEquals(191,half ushr 24)
                assertEquals(if(stock=="portra800")0x00c56838 else 0x000e0b07,half and 0x00ffffff)
                val raster=StraightAlphaFixed135Raster("research_$stock","review-$hash",1520,1400,argb,4.0)
                assertTrue(raster.residentBytes<9L*1024*1024)
                for(edge in listOf(96,384))for(portrait in listOf(false,true)){
                    val w=if(portrait)edge*2/3 else edge;val h=if(portrait)edge else edge*2/3
                    val s=BorderSnapshot.fixed135(raster.stock,raster.version,w,h,composition=CompositionTransform(.61,.43,1.7))
                    raster.validate(s)
                    val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    val pixels=IntArray(w*h){i->val v=32+(i%w)*170/(w-1);(0xff shl 24)or(v shl 16)or(v shl 8)or v}
                    bitmap.setPixels(pixels,0,w,0,0,w,h)
                    val input=File(dir,"source.rgb")
                    input.outputStream().buffered().use{out->val row=ByteBuffer.allocate(w*12).order(ByteOrder.LITTLE_ENDIAN);for(y in 0 until h){row.clear();for(x in 0 until w)repeat(3){row.putFloat(LinearRgb.srgb8ToLinear[pixels[y*w+x]and 255])};out.write(row.array())}}
                    val before=input.readBytes();val output=File(dir,"output.rgb")
                    val preview=BorderPreview.compose(bitmap,s,raster)
                    try {
                        if(edge==384 && qaDir!=null){
                            val name="$stock-${if(portrait)"portrait" else "landscape"}-fixed135-prototype"
                            File(qaDir,"$name.png").outputStream().use{check(preview.compress(Bitmap.CompressFormat.PNG,100,it))}
                            File(qaDir,"$name.txt").writeText("""
                                Isolated prototype renderer QA; generated/researched artwork NOT production approved.
                                No personal photograph: immutable authored grayscale gradient, 32..202 sRGB.
                                Asset: ${file.absolutePath}
                                SHA-256: $hash
                                Decoder: host ImageIO straight ARGB, not Android production decode.
                                Renderer: ${StraightAlphaFixed135Raster.SAMPLING_CONTRACT} + shared BorderPreview/BorderCompositor.
                                Hole matte: fixed linear white; this is not a physical scan-color claim.
                                Feather: 4 px total at 3072 photo short side, prototype-only internally versioned value.
                                Crop: center=(0.61,0.43), zoom=1.7, fixed135 center-cover and clamp.
                                Output: ${s.outputWidth} x ${s.outputHeight}, sRGB PNG, no source modification.
                                Snapshot: ${s.json()}
                            """.trimIndent()+"\n")
                        }
                        for(color in OutputColorSpace.entries){
                            BorderCompositor.composite(input,output,s,raster,color)
                            assertEquals(s.outputBytes,output.length());assertArrayEquals(before,input.readBytes())
                            val values=read(output)
                            if(color==OutputColorSpace.SRGB){
                                for(y in 0 until s.outputHeight)for(x in 0 until s.outputWidth){
                                    val actual=preview.getPixel(x,y);val at=(y*s.outputWidth+x)*3
                                    for(c in 0..2)assertEquals(OutputColorSpace.SRGB.encode8(values[at+c]),(actual ushr (16-c*8))and 255)
                                }
                            }
                            // First/last hole on both tracks; portrait rotates tracks to left/right.
                            for((ax,ay) in listOf(95.0 to 136.0,1425.0 to 136.0,95.0 to 1264.0,1425.0 to 1264.0)){
                                val lx=ax/40;val ly=ay/40
                                val x=(s.frameOriginX+(if(portrait)35-ly else lx)*s.pixelsPerMm).toInt()
                                val y=(s.frameOriginY+(if(portrait)lx else ly)*s.pixelsPerMm).toInt()
                                val at=(y*s.outputWidth+x)*3
                                for(c in 0..2)assertEquals("white hole $stock $edge $portrait $color",1f,values[at+c],1e-6f)
                            }
                            val frame=FloatArray(3);raster.sample(s,.5,.5,frame);BorderColor.convert(frame,color)
                            for(c in 0..2)assertEquals(frame[c],values[c],1e-6f)
                        }
                        // A point inside cleared aperture must sample extended material, not its black RGB.
                        val frame=FloatArray(3);raster.sample(s,s.apertureLeft+.01,s.apertureTop+s.photoHeight/2.0,frame)
                        if(stock=="portra800"){assertTrue(frame[0]>.5f);assertTrue(frame[0]>frame[2]+.2f)}else assertTrue(frame[0]<.2f)
                        output.writeText("prior");var checks=0
                        assertThrows(InterruptedException::class.java){BorderCompositor.composite(input,output,s,raster,OutputColorSpace.SRGB){if(++checks==4)throw InterruptedException("test")}}
                        assertEquals("prior",output.readText());assertFalse(File(dir,"output.rgb.part").exists());assertArrayEquals(before,input.readBytes())
                    }finally{preview.recycle();bitmap.recycle()}
                }
            }
        }finally{dir.deleteRecursively()}
    }
}
