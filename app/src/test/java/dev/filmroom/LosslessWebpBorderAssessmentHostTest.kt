package dev.filmroom

import android.graphics.Bitmap
import dev.filmroom.border.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LosslessWebpBorderAssessmentHostTest {
    private fun riff(tag:String,payload:ByteArray):ByteArray {
        val size=12+8+payload.size+(payload.size and 1)
        val out=ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray());out.putInt(size-8);out.put("WEBP".toByteArray());out.put(tag.toByteArray());out.putInt(payload.size);out.put(payload)
        return out.array()
    }
    @Test fun explicitEncodingRejectsLossyAnimatedMalformedAndWrongSignature(){
        val encoding=StraightAlphaBorderLoader.Encoding.LOSSLESS_WEBP
        for(tag in listOf("VP8 ","ANIM","ANMF"))assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.validateEncoding(riff(tag,ByteArray(10)),encoding)}
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.validateEncoding(riff("VP8L",byteArrayOf(0x2f,0,0,0,0)).dropLast(1).toByteArray(),encoding)}
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.validateEncoding(riff("VP8L",byteArrayOf(0x2f,0,0,0,0)),StraightAlphaBorderLoader.Encoding.PNG)}
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.validateEncoding(riff("VP8X",ByteArray(10).apply{this[0]=2}),encoding)}
        assertThrows(IllegalArgumentException::class.java){StraightAlphaBorderLoader.validateEncoding(riff("VP8L",byteArrayOf(0x2f,0,0,0,32)),encoding)}
    }
    @Test fun all18AndroidStraightWebpDecodesExactlyMatchOriginalPngAndSharedPreview(){
        assumeTrue("External lossless WebP assessment opt-in",System.getenv("FILMROOM_BORDER_WEBP")=="1")
        val source=File(System.getenv("FILMROOM_BORDER_CORPUS_ROOT") ?: "/workspace/scratch/b88978eaec71/filmroom-sample-deliverables/typography-v0114-r2/qa-corpus")
        val manifest=JSONObject(File(source,"manifest.json").readText());val stocks=manifest.getJSONArray("stocks")
        assertEquals(18,stocks.length());assertEquals(20,CandidateBorderDefinitions.profiles.size)
        val app=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        for(i in 0 until stocks.length()){
            val item=stocks.getJSONObject(i);val stock=item.getString("physical_stock")
            val definition=CandidateBorderDefinitions.definitions.single{it.spec.stock==stock}
            val png=File(source,item.getString("asset"));require(png.parentFile.canonicalFile==source.canonicalFile)
            val webpSpec=definition.spec
            val pngSpec=webpSpec.copy(sha256=item.getString("sha256"),encodedBytes=png.length().toInt(),encoding=StraightAlphaBorderLoader.Encoding.PNG)
            val pngPixels=StraightAlphaBorderLoader.decodeStraight(pngSpec,{png.inputStream()})
            val webpPixels=StraightAlphaBorderLoader.decodeStraight(webpSpec,{app.assets.open(definition.assetPath)})
            assertArrayEquals("Whole straight ARGB including zero-alpha RGB: $stock",pngPixels,webpPixels)
            val a=StraightAlphaFixed135Raster(stock,pngSpec.version,1520,1400,pngPixels,pngSpec.featherTotalAt3072,pngSpec.holeMatteSrgb)
            val b=StraightAlphaFixed135Raster(stock,webpSpec.version,1520,1400,webpPixels,webpSpec.featherTotalAt3072,webpSpec.holeMatteSrgb)
            for(portrait in listOf(false,true)){
                val w=if(portrait)64 else 96;val h=if(portrait)96 else 64
                val s=BorderSnapshot.fixed135(stock,pngSpec.version,w,h)
                val sourceBitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff808080.toInt())}
                val pa=BorderPreview.compose(sourceBitmap,s,a);val pb=BorderPreview.compose(sourceBitmap,s,b)
                try{
                    val aa=IntArray(s.outputWidth*s.outputHeight);val bb=IntArray(aa.size)
                    pa.getPixels(aa,0,s.outputWidth,0,0,s.outputWidth,s.outputHeight);pb.getPixels(bb,0,s.outputWidth,0,0,s.outputWidth,s.outputHeight)
                    assertArrayEquals("Composed preview: $stock portrait=$portrait",aa,bb)
                }finally{pa.recycle();pb.recycle();sourceBitmap.recycle()}
            }
        }
        assertFalse(dev.filmroom.model.EditorState().filmBorder) // User-facing default stays OFF even in authorized candidate registry.
    }
}
