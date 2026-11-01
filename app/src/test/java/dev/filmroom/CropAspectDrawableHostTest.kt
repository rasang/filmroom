package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.filmroom.ui.CropAspectDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropAspectDrawableHostTest {
    private fun raster(aspect:Double,kind:String):Bitmap=Bitmap.createBitmap(240,240,Bitmap.Config.ARGB_8888).apply{
        CropAspectDrawable(aspect,kind,Color.WHITE).apply{setBounds(0,0,240,240)}.draw(Canvas(this))
    }
    @Test fun everyRatioUsesItsOwnProportionsAndSpecialModesHaveDistinctSymbols(){
        val hashes=mutableSetOf<Int>()
        for(ratio in listOf(1.0,1.5,2.0/3,4.0/3,.75,16.0/9,9.0/16)){
            val b=raster(ratio,"ratio");val points=(0 until 240).flatMap{y->(0 until 240).filter{x->Color.alpha(b.getPixel(x,y))>128}.map{x->x to y}}
            val width=points.maxOf{it.first}-points.minOf{it.first}+1
            val height=points.maxOf{it.second}-points.minOf{it.second}+1
            assertEquals(ratio,width.toDouble()/height,.09)
            val pixels=IntArray(240*240);b.getPixels(pixels,0,240,0,0,240,240);assertTrue(hashes.add(pixels.contentHashCode()));b.recycle()
        }
        for(kind in listOf("original","free")){
            val b=raster(1.5,kind);val pixels=IntArray(240*240);b.getPixels(pixels,0,240,0,0,240,240)
            assertTrue("$kind must not reuse a ratio outline",hashes.add(pixels.contentHashCode()));b.recycle()
        }
        assertEquals(9,hashes.size)
    }
}
