package dev.filmroom

import dev.filmroom.effects.EffectGeometry
import org.junit.Assert.*
import org.junit.Test

class EffectPreviewFinderGeometryTest {
    @Test fun equalAndIndependentlyRoundedDimensionsKeepOriginalShader(){
        assertFalse(EffectGeometry.needsPreviewFinder(1536,1024,1536,1024))
        assertFalse(EffectGeometry.needsPreviewFinder(1536,1024,768,512))
        assertFalse(EffectGeometry.needsPreviewFinder(1536,1024,1535,1023))
        assertFalse(EffectGeometry.needsPreviewFinder(1536,1024,767,513))
        assertFalse(EffectGeometry.needsPreviewFinder(1024,1536,513,767))
    }
    @Test fun actualFilmRailAspectIsNotTreatedAsRounding(){
        assertTrue(EffectGeometry.needsPreviewFinder(1536,1024,1536,1408))
        assertTrue(EffectGeometry.needsPreviewFinder(1024,1536,1408,1536))
        assertTrue(EffectGeometry.needsPreviewFinder(1536,1024,1536,1030))
        assertTrue(EffectGeometry.needsPreviewFinder(1536,1536,1536,1580))
    }
    @Test fun landscape135MovesSlackAboveAndBelowFinder(){
        val f=EffectGeometry.previewFinder(1200,1100,1.5f)
        assertEquals(1f,f.scaleX,0f);assertEquals(800f/1100f,f.scaleY,0.00001f)
        assertEquals(1200,f.rasterWidth);assertEquals(800,f.rasterHeight)
    }
    @Test fun portrait135MovesSlackLeftAndRight(){
        val f=EffectGeometry.previewFinder(1100,1200,2f/3f)
        assertEquals(800f/1100f,f.scaleX,0.00001f);assertEquals(1f,f.scaleY,0f)
        assertEquals(800,f.rasterWidth);assertEquals(1200,f.rasterHeight)
    }
    @Test fun equalAspectHasNoGeometryChange(){
        val f=EffectGeometry.previewFinder(1200,800,1.5f)
        assertEquals(1f,f.scaleX,0f);assertEquals(1f,f.scaleY,0f)
        assertEquals(1200,f.rasterWidth);assertEquals(800,f.rasterHeight)
    }
    @Test fun allFormatsRemainCenteredContainedAndUndistorted(){
        for(a in listOf(1f,1.5f,2f/3f,4f/3f,6f/7f,1.7f))for((w,h)in listOf(1200 to 1100,800 to 1200,1 to 1)){
            val f=EffectGeometry.previewFinder(w,h,a)
            assertTrue(f.scaleX>0&&f.scaleX<=1);assertTrue(f.scaleY>0&&f.scaleY<=1)
            assertEquals(a,w*f.scaleX/(h*f.scaleY),0.00001f)
            // Entire finder maps 0..1, rather than crop/cover sampling the original photograph.
            val left=(1-f.scaleX)/2;val top=(1-f.scaleY)/2
            assertEquals(0f,(left-.5f)/f.scaleX+.5f,0.00001f)
            assertEquals(1f,(left+f.scaleX-.5f)/f.scaleX+.5f,0.00001f)
            assertEquals(0f,(top-.5f)/f.scaleY+.5f,0.00001f)
            assertTrue(f.rasterWidth>=1&&f.rasterHeight>=1)
        }
    }
    @Test fun existingExportLayoutRetainsResultAspect(){
        val resultAspect=1200f/1100f
        val aspect=EffectGeometry.sceneAspect(dev.filmroom.effects.EffectKind.OPTICAL_LEAK,resultAspect)
        assertEquals(resultAspect,aspect,0.000001f)
        assertEquals(1.1124f,EffectGeometry.sceneAspect(dev.filmroom.effects.EffectKind.LIGHT_TABLE,1.5f),0f)
    }
    @Test fun resizeRecomputesOnlyFinderRect(){
        val a=EffectGeometry.previewFinder(1200,1100,1.5f);val b=EffectGeometry.previewFinder(600,550,1.5f)
        assertEquals(a.scaleX,b.scaleX,0f);assertEquals(a.scaleY,b.scaleY,0f)
        assertEquals(a.rasterWidth/2,b.rasterWidth);assertEquals(a.rasterHeight/2,b.rasterHeight)
    }
    @Test fun invalidGeometryRejected(){
        for(a in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY))try{EffectGeometry.previewFinder(10,10,a);fail("invalid aspect") }catch(_:IllegalArgumentException){}
        try{EffectGeometry.previewFinder(0,10,1f);fail("invalid size")}catch(_:IllegalArgumentException){}
    }
}
