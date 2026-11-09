package dev.filmroom
import dev.filmroom.effects.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.min
class EffectGeometryTest {
 @Test fun wholeBezelMatchesPortraitLandscapeSquare() {
  for(a in listOf(.6f,1f,1.6666666f,.05f,20f)) {
   val l=EffectGeometry.opticalLayout(a)
   val imageW=if(a>=1f)400f else 400f*a;val imageH=if(a>=1f)400f/a else 400f
   val scale=1f+44f/min(imageW,imageH)
   assertEquals(imageW*scale,l.outerW,.0001f);assertEquals(imageH*scale,l.outerH,.0001f)
   assertEquals(l.padX,l.padY,0f)
   assertEquals(imageW,l.outerW*(1-2*l.padX),.0001f)
   assertEquals(imageH,l.outerH*(1-2*l.padY),.0001f)
   assertEquals(a,EffectGeometry.sceneAspect(EffectKind.OPTICAL_LEAK,a),.00001f)
   assertEquals(1.1124f,EffectGeometry.sceneAspect(EffectKind.LIGHT_TABLE,a),0f)
  }
 }
 @Test fun containPreservesShapeAndAllFourEdgesAtPreviewAndEncoderSizes() {
  for((w,h) in listOf(420 to 285,1080 to 732,1072 to 720,285 to 420,420 to 420))for(a in listOf(.6f,1f,1.6666666f)) {
   val l=EffectGeometry.opticalLayout(a);val iw=w*(1-2*l.padX);val ih=h*(1-2*l.padY)
   val viewport=EffectGeometry.sourceViewportAspect(w,h,a);assertEquals(iw/ih,viewport,.000001f)
   val sx=if(viewport>a)a/viewport else 1f;val sy=if(viewport>a)1f else viewport/a
   assertEquals(iw*sx/a,ih*sy,.0002f)
   assertEquals(min(iw/a,ih),ih*sy,.0002f)
   assertTrue(sx in 0f..1f && sy in 0f..1f)
  }
 }
 @Test fun coverMotionStaysInViewportCoordinatesForEveryPhotoAspect() {
  for((w,h) in listOf(420 to 285,1080 to 732,1072 to 720))for(a in listOf(.6f,1f,1.6666666f)) {
   val l=EffectGeometry.opticalLayout(a);val innerH=h*(1-2*l.padY);val viewport=EffectGeometry.sourceViewportAspect(w,h,a)
   val sy=if(viewport>a)1f else viewport/a
   assertEquals(.018f*innerH,(.018f/sy)*(innerH*sy),.00001f)
  }
 }
 @Test fun marksFollowPhotoAndCentralCircleRemainsRoundAfterRotation() {
  for((w,h) in listOf(420 to 285,1080 to 732,285 to 420,732 to 1080))for(a in listOf(.6f,1f,1.6666666f,.05f,20f)) {
   val l=EffectGeometry.opticalLayout(a);val g=EffectGeometry.marks(w,h,a)
   assertEquals(a,g.frameWidth/g.frameHeight,.00001f)
   assertTrue(g.frameWidth<=g.imageWidth && g.frameHeight<=g.imageHeight)
   assertTrue(g.radius>0 && g.arm>0 && g.arm<=11.25f && g.radius<=16.25f)
   val sx=w*(1-2*l.padX)/g.width;val sy=h*(1-2*l.padY)/g.height
   assertEquals(sx,sy,.00001f);assertEquals(g.radius*sx,g.radius*sy,.0001f)
  }
 }
}
