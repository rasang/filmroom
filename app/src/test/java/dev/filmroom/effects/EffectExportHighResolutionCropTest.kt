package dev.filmroom.effects

import android.graphics.Bitmap
import android.graphics.Color
import dev.filmroom.crop.CropPreview
import dev.filmroom.model.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
class EffectExportHighResolutionCropTest {
    private fun image(w:Int,h:Int)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(60,120,180))}
    @Test fun diagnosticVideoBudgetAbove2048PreservesCropResolution() {
        for(edge in listOf(2165,2168,2300)) {
            val source=image(edge*3/4,edge)
            val composition=EffectSourceComposition(3072,4096,CropSettings(CropMode.RATIO_3_2))
            val expected=composition.size(source.width,source.height)
            val output=composition.applyForExport(source)
            assertEquals(expected.width,output.width);assertEquals(expected.height,output.height)
            assertEquals(source.width,output.width) // No hidden downscale to the preview limit.
            assertEquals(source.getPixel(0,0),output.getPixel(output.width/2,output.height/2))
            assertFalse(source.isRecycled);output.recycle();source.recycle()
        }
    }
    @Test fun exportOutputItselfCanRemainAbovePreviewLimit() {
        val source=image(2300,1533)
        val composition=EffectSourceComposition(2300,1533,CropSettings(CropMode.RATIO_16_9))
        val output=composition.applyForExport(source)
        assertEquals(2300,output.width);assertTrue(output.width>2048)
        assertEquals(composition.size(2300,1533).height,output.height)
        output.recycle();source.recycle()
    }
    @Test fun largeLandscapeAndPortraitWithStraighteningAreAccepted() {
        for((w,h) in listOf(2300 to 1533,1725 to 2300))for(angle in listOf(-8.0,8.0)) {
            val source=image(w,h)
            val composition=EffectSourceComposition(w,h,CropSettings(CropMode.SQUARE,CompositionTransform(straightenDegrees=angle)))
            val expected=composition.size(w,h);val output=composition.applyForExport(source)
            assertEquals(expected.width,output.width);assertEquals(expected.height,output.height)
            assertEquals(source.getPixel(0,0),output.getPixel(output.width/2,output.height/2))
            output.recycle();source.recycle()
        }
    }
    @Test fun exportMatchesPreviewPixelsAtPreviewResolution() {
        val source=image(96,64).apply { for(x in 0 until width)setPixel(x,20,Color.RED) }
        val composition=EffectSourceComposition(6000,4000,CropSettings(CropMode.SQUARE,CompositionTransform(.6,.4,1.3,straightenDegrees=9.0)))
        val a=composition.apply(source);val b=composition.applyForExport(source)
        assertEquals(a.width,b.width);assertEquals(a.height,b.height)
        val p=IntArray(a.width*a.height);val q=IntArray(p.size)
        a.getPixels(p,0,a.width,0,0,a.width,a.height);b.getPixels(q,0,b.width,0,0,b.width,b.height)
        assertArrayEquals(p,q);a.recycle();b.recycle();source.recycle()
    }
    @Test fun previewStillRejectsOver2048() {
        val source=image(2049,2)
        val composition=EffectSourceComposition(2049,2,CropSettings(CropMode.SQUARE))
        try { CropPreview.compose(source,composition.snapshot(2049,2));fail("Preview limit must remain") }
        catch(expected:IllegalArgumentException) {} finally{source.recycle()}
    }
    @Test fun exportCancellationKeepsCallerInputAlive() {
        val source=image(2168,1445)
        val composition=EffectSourceComposition(2168,1445,CropSettings(CropMode.SQUARE))
        var calls=0
        try { composition.applyForExport(source){if(++calls>3)throw CancellationException("test")};fail("Must cancel") }
        catch(expected:CancellationException) { assertFalse(source.isRecycled) }
        finally{source.recycle()}
    }
    @Test fun identityExportRetainsInputOwnership() {
        val source=image(2300,2)
        assertSame(source,EffectSourceComposition(2300,2,CropSettings()).applyForExport(source))
        source.recycle()
    }
    @Test fun memoryBudgetIsNotReplacedByPreviewLimit() {
        val heap=268435456L;val remaining=225620944L
        assertEquals(2168,EffectExportImages.decodeBudgetEdge(heap,remaining,false,true))
        assertTrue(EffectExportImages.decodeBudgetEdge(heap,remaining,true,true)<2048)
        assertTrue(EffectExportImages.decodeBudgetEdge(heap,remaining/2,false,true)<2168)
    }
}
