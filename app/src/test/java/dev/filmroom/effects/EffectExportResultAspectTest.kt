package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectExportResultAspectTest {
    @Test fun portraitResultRemainsPortraitEvenWhenSourceLandscape() {
        val size=EffectExportResolution.desired(EffectKind.OPTICAL_LEAK,6000,4000,3000,4500,EffectExportQuality.P1080)
        assertTrue(size.width<size.height)
        assertEquals(1080,size.height)
        assertEquals(2.0/3,size.width.toDouble()/size.height,.003)
    }
    @Test fun landscapeResultRemainsLandscapeEvenWhenSourcePortrait() {
        val size=EffectExportResolution.desired(EffectKind.OPTICAL_LEAK,4000,6000,4500,3000,EffectExportQuality.P1920)
        assertTrue(size.width>size.height)
        assertEquals(1920,size.width)
        assertEquals(1.5,size.width.toDouble()/size.height,.003)
    }
    @Test fun railCanvasRatherThanPhotoApertureDeterminesExportAspect() {
        val size=EffectExportResolution.desired(EffectKind.OPTICAL_LEAK,6000,4000,6000,5500,EffectExportQuality.P1920)
        assertEquals(6000.0/5500,size.width.toDouble()/size.height,.003)
    }
    @Test fun originalQualityAlsoUsesResultAspect() {
        val size=EffectExportResolution.desired(EffectKind.OPTICAL_LEAK,6000,4000,4000,4000,EffectExportQuality.ORIGINAL)
        assertEquals(size.width,size.height)
        assertTrue(size.width>=6000)
    }
}
