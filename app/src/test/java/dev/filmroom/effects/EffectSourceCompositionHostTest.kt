package dev.filmroom.effects

import android.graphics.Bitmap
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.crop.CropPreview
import dev.filmroom.crop.CropSnapshot
import dev.filmroom.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
class EffectSourceCompositionHostTest {
    private fun image(w:Int=96,h:Int=64)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {
        for(y in 0 until h)for(x in 0 until w)setPixel(x,y,android.graphics.Color.rgb(x*255/w,y*255/h,(x+y)%256))
    }
    private fun pixels(b:Bitmap)=IntArray(b.width*b.height).also{b.getPixels(it,0,b.width,0,0,b.width,b.height)}
    @Test fun noRecordAndIdentityKeepOriginal() {
        assertNull(EffectSourceComposition.from(null))
        val raw=image();assertSame(raw,EffectSourceComposition(96,64,CropSettings()).apply(raw));raw.recycle()
    }
    @Test fun cropAndBothStraightenDirectionsMatchSavedCropExactly() {
        for(mode in listOf(CropMode.SQUARE,CropMode.RATIO_9_16,CropMode.RATIO_16_9))for(angle in listOf(-12.0,0.0,12.0)) {
            val raw=image();val settings=CropSettings(mode,CompositionTransform(.65,.4,1.4,straightenDegrees=angle))
            val composition=EffectSourceComposition(6000,4000,settings)
            val expected=CropPreview.compose(raw,CropSnapshot.create(96,64,6000,4000,settings))
            val actual=composition.apply(raw)
            assertEquals(expected.width,actual.width);assertEquals(expected.height,actual.height)
            assertArrayEquals(pixels(expected),pixels(actual))
            // Replay/export re-decodes the original and applies once, rather than cropping its prior result.
            val again=composition.apply(raw);assertArrayEquals(pixels(actual),pixels(again))
            assertFalse(raw.isRecycled);again.recycle();actual.recycle();expected.recycle();raw.recycle()
        }
    }
    @Test fun borderUsesApertureCompositionNotOuterRails() {
        val transform=CompositionTransform(.6,.4,1.7,targetAspect=2.0/3,straightenDegrees=7.0).clamped(6000,4000)
        val border=BorderSnapshot.authored("kodak_gold_200","test",96,64,6000,4000,transform,0.0)
        val c=requireNotNull(EffectSourceComposition.fromBorder(border))
        assertEquals(transform,c.settings.effectiveTransform(6000,4000))
        val s=c.size(960,640);assertEquals(2.0/3,s.width.toDouble()/s.height,.01)
        assertTrue(s.width<960);assertTrue(s.height<640)
    }
    @Test fun legacyBorderDoesNotInventCrop() {
        assertNull(EffectSourceComposition.fromBorder(BorderSnapshot.create("fixture_only","test",96,64,.2,.03,0.0)))
    }
    @Test fun previewAndExportUseSameNormalizedGeometry() {
        val c=EffectSourceComposition(6000,4000,CropSettings(CropMode.FREE,CompositionTransform(.7,.3,2.0,.8,8.0)))
        val preview=c.snapshot(1200,800);val export=c.snapshot(6000,4000)
        assertEquals(preview.settings,export.settings)
        assertEquals(preview.sourceRect,export.sourceRect)
        assertEquals(5.0,export.outputWidth.toDouble()/preview.outputWidth,.03)
    }
    @Test fun persistedCropRetainsAngleAndPanForHistoryReplay() {
        val settings=CropSettings(CropMode.SQUARE,CompositionTransform(.7,.3,2.0,straightenDegrees=-8.0))
        val before=CropSnapshot.create(960,640,6000,4000,settings)
        val restored=CropSnapshot.fromJson(before.json())
        assertEquals(before,restored)
        val a=EffectSourceComposition(before.canonicalWidth,before.canonicalHeight,before.settings)
        val b=EffectSourceComposition(restored.canonicalWidth,restored.canonicalHeight,restored.settings)
        assertEquals(a.size(1200,800),b.size(1200,800))
    }
    @Test fun compositionReservesAdditionalDecodeMemory() {
        assertTrue(EffectExportImages.decodeBudgetEdge(512L*1024*1024,128L*1024*1024,false,true)<
            EffectExportImages.decodeBudgetEdge(512L*1024*1024,128L*1024*1024,false,false))
    }
}
