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
    @Test fun opticalHousingReservesF3ReadoutWithoutChangingImageAspect() {
        // Independent design contract: longest image edge 400, side bezel 10 each,
        // header/footer 22 each. Portrait rotates this housing clockwise.
        for(aspect in listOf(.1f,.5f,1f,1.5f,2f,10f)) {
            val imageW=if(aspect>=1f)400.0 else 400.0*aspect
            val imageH=if(aspect>=1f)400.0/aspect else 400.0
            val px=if(aspect<1f)22.0 else 10.0;val py=if(aspect<1f)10.0 else 22.0
            val outerW=imageW+px*2;val outerH=imageH+py*2
            val layout=EffectGeometry.opticalLayout(aspect)
            assertEquals(outerW,layout.outerW.toDouble(),.0001)
            assertEquals(outerH,layout.outerH.toDouble(),.0001)
            assertEquals(px,(layout.padX*layout.outerW).toDouble(),.00001)
            assertEquals(py,(layout.padY*layout.outerH).toDouble(),.00001)
            assertEquals(aspect.toDouble(),imageW/imageH,.000001)
            val (w,h)=EffectVideoExporter.sceneDimensions(EffectKind.OPTICAL_LEAK,aspect)
            val scale=1080.0/kotlin.math.max(outerW,outerH)
            // Encoder rounds only down to an even pixel, by less than two pixels.
            assertTrue(w%2==0 && h%2==0)
            assertEquals(1080,kotlin.math.max(w,h))
            assertTrue(outerW*scale-w>=-.0001 && outerW*scale-w<2.0001)
            assertTrue(outerH*scale-h>=-.0001 && outerH*scale-h<2.0001)
            // Rounding changes viewport aspect very slightly. contain() must letterbox,
            // not stretch/crop the photo: independently reconstruct its pixel rectangle.
            val viewportW=w*(1.0-2*layout.padX)
            val viewportH=h*(1.0-2*layout.padY)
            val viewport=EffectGeometry.sourceViewportAspect(w,h,aspect)
            assertEquals(viewportW/viewportH,viewport.toDouble(),.00001)
            val fitX=if(viewport>aspect)aspect.toDouble()/viewport else 1.0
            val fitY=if(viewport>aspect)1.0 else viewport.toDouble()/aspect
            assertTrue(fitX>0 && fitX<=1 && fitY>0 && fitY<=1)
            assertEquals(aspect.toDouble(),viewportW*fitX/(viewportH*fitY),.00001)
        }
        assertEquals(1080 to 626,EffectVideoExporter.sceneDimensions(EffectKind.OPTICAL_LEAK,2f))
        assertEquals(626 to 1080,EffectVideoExporter.sceneDimensions(EffectKind.OPTICAL_LEAK,.5f))
        assertEquals(1020 to 1080,EffectVideoExporter.sceneDimensions(EffectKind.OPTICAL_LEAK,1f))
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
