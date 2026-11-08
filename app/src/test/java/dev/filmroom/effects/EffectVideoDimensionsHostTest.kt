package dev.filmroom.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EffectVideoDimensionsHostTest {
    @Test fun lightTableStaysFixedForEverySourceOrientation() {
        for(aspect in listOf(.5f,1f,2f))
            assertEquals(1080 to 970,EffectVideoExporter.sceneDimensions(EffectKind.LIGHT_TABLE,aspect))
    }
    @Test fun opticalHousingFollowsLandscapePortraitAndSquare() {
        assertEquals(1080 to 564,EffectVideoExporter.sceneDimensions(EffectKind.OPTICAL_LEAK,2f))
        assertEquals(564 to 1080,EffectVideoExporter.sceneDimensions(EffectKind.OPTICAL_LEAK,.5f))
        assertEquals(1080 to 1080,EffectVideoExporter.sceneDimensions(EffectKind.OPTICAL_LEAK,1f))
        val landscape=EffectVideoExporter.sceneDimensions(EffectKind.OPTICAL_LEAK,1.5f)
        val portrait=EffectVideoExporter.sceneDimensions(EffectKind.OPTICAL_LEAK,1f/1.5f)
        assertEquals(landscape.first,portrait.second)
        assertEquals(landscape.second,portrait.first)
    }
    @Test fun everySceneFitsTheEncoderWithOnlyEvenRounding() {
        for(kind in EffectKind.values()) for(sourceAspect in listOf(.1f,.5f,1f,1.5f,2f,10f)) {
            val (w,h)=EffectVideoExporter.sceneDimensions(kind,sourceAspect)
            val aspect=EffectGeometry.sceneAspect(kind,sourceAspect).toDouble()
            assertTrue(w in 2..1080 && h in 2..1080 && w%2==0 && h%2==0)
            if(aspect>=1.0) assertTrue(abs(h-w/aspect)<2.0)
            else assertTrue(abs(w-h*aspect)<2.0)
        }
    }
}
