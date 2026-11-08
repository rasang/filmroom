package dev.filmroom.effects
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EffectF3TexturesHostTest {
 @Test fun blurTextureIsBoundedAndDoesNotMutateSource() {
  val source=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888);source.eraseColor(android.graphics.Color.RED)
  val blurred=EffectTextures.defocused(source);assertEquals(256,blurred.width);assertEquals(171,blurred.height)
  assertFalse(source.isRecycled);assertEquals(android.graphics.Color.RED,source.getPixel(30,30));assertEquals(android.graphics.Color.RED,blurred.getPixel(30,30))
  blurred.recycle();source.recycle()
 }
 @Test fun missingReadoutIsTransparentAndKnownValuesStayInHeader() {
  val empty=EffectTextures.readout(1260,852,1.5f,EffectCameraReadout())
  val px=IntArray(empty.width*empty.height);empty.getPixels(px,0,empty.width,0,0,empty.width,empty.height)
  assertTrue(px.all{it==0});empty.recycle()
  val b=EffectTextures.readout(1260,932,1.5f,EffectCameraReadout("250","5.6"))
  val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height)
  assertTrue(pixels.any{it ushr 24>0})
  for(y in b.height/8 until b.height)for(x in 0 until b.width)assertEquals(0,pixels[y*b.width+x])
  System.getProperty("filmroom.f3.capture")?.let{dir ->
   File(dir).mkdirs();File(dir,"readout.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
   val marks=EffectTextures.marks(1260,932,1.5f);File(dir,"marks.png").outputStream().use{marks.compress(Bitmap.CompressFormat.PNG,100,it)};marks.recycle()
  };b.recycle()
 }
 @Test fun portraitReadoutIsOnRightAndRotatedClockwiseNotAtTop() {
  val reading=EffectCameraReadout("250","5.6")
  val landscape=EffectTextures.readout(1260,932,1.5f,reading)
  val portrait=EffectTextures.readout(932,1260,2f/3f,reading)
  fun bounds(b:Bitmap):android.graphics.Rect {
   var left=b.width;var top=b.height;var right=0;var bottom=0
   for(y in 0 until b.height)for(x in 0 until b.width)if(b.getPixel(x,y) ushr 24>0){left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)}
   return android.graphics.Rect(left,top,right+1,bottom+1)
  }
  val lb=bounds(landscape);val pb=bounds(portrait)
  assertTrue(lb.bottom<landscape.height/8)
  assertTrue(pb.left>portrait.width*7/8)
  assertTrue(pb.top>portrait.height/5 && pb.bottom<portrait.height*3/4)
  // The long horizontal pair becomes vertical, with the LCD preceding the ADR downward.
  assertEquals(lb.width().toDouble(),pb.height().toDouble(),2.0)
  assertEquals(lb.height().toDouble(),pb.width().toDouble(),2.0)
  System.getProperty("filmroom.f3.capture")?.let { dir ->
   val marks=EffectTextures.marks(932,1260,2f/3f);File(dir,"portrait-marks.png").outputStream().use{marks.compress(Bitmap.CompressFormat.PNG,100,it)};marks.recycle()
   File(dir,"portrait-readout.png").outputStream().use{portrait.compress(Bitmap.CompressFormat.PNG,100,it)}
   File(dir,"landscape-readout.png").outputStream().use{landscape.compress(Bitmap.CompressFormat.PNG,100,it)}
  }
  landscape.recycle();portrait.recycle()
 }
 @Test fun portraitMissingExifStaysBlankAndSquareStaysAtTop() {
  for(a in listOf(.5f,1f,2f)) {
   val l=EffectGeometry.opticalLayout(a)
   val b=EffectTextures.readout((l.outerW*3).toInt(),(l.outerH*3).toInt(),a,EffectCameraReadout())
   val px=IntArray(b.width*b.height);b.getPixels(px,0,b.width,0,0,b.width,b.height)
   assertTrue(px.all{it==0});assertEquals(a<1f,l.portrait);b.recycle()
  }
 }
 @Test fun splitEtchingRotatesWithPortraitCamera() {
  fun alphaNear(b:Bitmap,x:Int,y:Int):Int { var a=0; for(yy in y-1..y+1)for(xx in x-1..x+1)a=maxOf(a,b.getPixel(xx,yy) ushr 24);return a }
  for(a in listOf(1.5f,1f,2f/3f)) {
   val l=EffectGeometry.opticalLayout(a)
   val b=EffectTextures.marks((l.outerW*3).toInt(),(l.outerH*3).toInt(),a)
   val x=b.width/2;val y=b.height/2
   val horizontal=alphaNear(b,x+10,y);val vertical=alphaNear(b,x,y+10)
   if(a<1f) {assertTrue(vertical>0);assertEquals(0,horizontal)}
   else {assertTrue(horizontal>0);assertEquals(0,vertical)}
   b.recycle()
  }
 }

}
