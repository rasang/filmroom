package dev.filmroom.imprint

import dev.filmroom.io.OutputColorSpace
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImprintRasterHostTest {
    private val styles=listOf("contax_t2","yashica_t4","canon_data_back","canon_luna","pentax_mzs")
    private fun pixels(r:ImprintRaster,space:OutputColorSpace=OutputColorSpace.SRGB):FloatArray {
        val result=FloatArray(r.width*r.height*3)
        for(y in 0 until r.height) {val row=FloatArray(r.width*3);r.blendRow(y,row,0,space);row.copyInto(result,y*r.width*3)}
        return result
    }
    @Test fun deterministicDistinctStylesAndBoundedMasks() {
        val signatures=styles.map { style ->
            val a=ImprintRaster.create("'0123456789",style,40.0,.55)
            val b=ImprintRaster.create("'0123456789",style,40.0,.55)
            assertArrayEquals(pixels(a),pixels(b),0f)
            assertTrue(a.residentBytes<=ImprintRaster.MAX_MASK_BYTES)
            pixels(a).contentHashCode()
        }
        assertEquals(5,signatures.toSet().size)
    }
    @Test fun glowAddsVisibleBoundedNonClippedExteriorWithoutChangingDimensions() {
        val a=ImprintRaster.create("8",styles[0],60.0,0.0)
        val b=ImprintRaster.create("8",styles[0],60.0,1.0)
        assertEquals(a.width,b.width);assertEquals(a.height,b.height)
        val x=pixels(a);val y=pixels(b)
        assertTrue(y.sum()>x.sum())
        val exterior=y.indices.filter{x[it]==0f}.map{y[it]}
        assertTrue(exterior.maxOrNull()!!>0f)
        assertTrue(y.all{it.isFinite() && it in 0f..1f})
        // Full halo must end within the padded mask, never at its clipped boundary.
        for(py in 0 until b.height)for(px in 0 until b.width) {
            if(px==0 || py==0 || px==b.width-1 || py==b.height-1)
                for(c in 0..2)assertEquals(0f,y[(py*b.width+px)*3+c],0f)
        }
    }
    @Test fun rotationAndClippingAndAllOutputSpaces() {
        val a=ImprintRaster.create("ISO400 # 250 F5.6 +0.5 800mm",styles[4],22.0,.5)
        val rotated=a.rotatedClockwise().rotatedClockwise().rotatedClockwise().rotatedClockwise()
        assertArrayEquals(pixels(a),pixels(rotated),0f)
        OutputColorSpace.entries.forEach { s-> assertTrue(pixels(a,s).all{it.isFinite()&&it in 0f..1f}) }
        val row=FloatArray(30){.2f};a.blendRow(5,row,Int.MIN_VALUE,OutputColorSpace.SRGB)
        a.blendRow(5,row,Int.MAX_VALUE,OutputColorSpace.SRGB)
        assertTrue(row.all{it==.2f})
        assertTrue(pixels(ImprintRaster.create("25年4月28日",styles[3],40.0,.5)).sum()>0)
    }
    @Test fun rejectsInvalidInputAndSupportsSmallPreview() {
        listOf(Double.NaN,0.0,513.0).forEach { h->assertThrows(IllegalArgumentException::class.java){ImprintRaster.create("12",styles[0],h,.5)} }
        assertThrows(IllegalArgumentException::class.java){ImprintRaster.create("x".repeat(49),styles[0],32.0,.5)}
        styles.forEach { assertTrue(pixels(ImprintRaster.create("28",it,3.0,.5)).sum()>0) }
    }
    @Test fun straightAlphaHelperAndMeterSymbols() {
        val r=ImprintRaster.create("ISO400 [o] # 30s",styles[4],24.0,.55)
        val row=IntArray(r.width)
        var visible=false
        for(y in 0 until r.height) {r.writeArgbRow(y,row);if(row.any{(it ushr 24)>0})visible=true}
        assertTrue(visible)
        assertEquals(r.width,r.rotatedClockwise().height)
        assertEquals(r.height,r.rotatedClockwise().width)
    }
    @Test fun renderSyntheticComparisonSamples() {
        val dir=File("build/reports/imprint-samples").apply{mkdirs()}
        val image=Bitmap.createBitmap(1300,800,Bitmap.Config.ARGB_8888)
        val texts=listOf("'18 10 26","02 01.14","22 5 84","25年4月28日","ISO400 # 250 F5.6 +0.5 800mm")
        val g=Canvas(image);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=12f}
        g.drawColor(Color.rgb(235,232,224))
        for(i in styles.indices) {
            val top=i*160
            val body=if(styles[i]=="pentax_mzs")24.0 else 46.0
            paint.color=Color.rgb(55,55,52);g.drawText(styles[i]+"   body $body px / glow 1.0",20f,(top+20).toFloat(),paint)
            val r=ImprintRaster.create(texts[i],styles[i],body,1.0)
            for(y in 0 until 125) {
                val row=FloatArray(1300*3)
                for(x in 0 until 1300) {val v=if(x<650).025f else .22f;row[x*3]=v;row[x*3+1]=v*.94f;row[x*3+2]=v*.85f}
                r.blendRow(y-20,row,35,OutputColorSpace.SRGB)
                r.blendRow(y-20,row,685,OutputColorSpace.SRGB)
                for(x in 0 until 1300)image.setPixel(x,top+30+y,(0xff shl 24) or (OutputColorSpace.SRGB.encode8(row[x*3]) shl 16) or (OutputColorSpace.SRGB.encode8(row[x*3+1]) shl 8) or OutputColorSpace.SRGB.encode8(row[x*3+2]))
            }
        }
        File(dir,"five-families.png").outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))};image.recycle()
    }
}
