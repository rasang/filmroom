package dev.filmroom.ui

import android.app.Application
import android.graphics.*
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.crop.CropSnapshot
import dev.filmroom.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PendingCompositionGeometryHostTest {
 private fun bitmap(w:Int,h:Int,color:Int)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
 private fun center(view:PhotoView):Int {val out=bitmap(view.width,view.height,Color.BLACK);return try{view.draw(Canvas(out));out.getPixel(view.width/2,view.height/2)}finally{out.recycle()}}
 @Test fun pending135DirectionBlocksOldOverlayAndGesturesUntilMatchingFrame(){
  val source=bitmap(300,200,Color.RED);var desired=CompositionTransform(zoom=2.0,targetAspect=1.5)
  val old=BorderSnapshot.fixed135("fixture","pending",300,200,composition=desired)
  val exact=bitmap(old.outputWidth,old.outputHeight,Color.BLUE)
  val overlay=bitmap(old.outputWidth,old.outputHeight,Color.TRANSPARENT)
  val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,400,400);bitmap=exact}
  var events=0
  fun bind(s:BorderSnapshot,o:Bitmap){view.bindComposition(s,source,desired,true,o){t,_->desired=t;events++}}
  try{
   bind(old,overlay);view.pan(15.0,0.0);view.finishCompositionGesture();assertTrue(view.isLiveComposition)
   desired=desired.copy(targetAspect=2.0/3);bind(old,overlay)
   assertTrue(view.isCompositionEditing);assertFalse(view.compositionGeometryReady);assertFalse(view.isLiveComposition)
   assertEquals(Color.BLUE,center(view));val count=events
   view.pan(40.0,30.0);view.pinch(2.0,200.0,200.0);view.finishCompositionGesture();assertEquals(count,events)
   // Rapid back-and-forth requests cannot revive a mismatched overlay.
   desired=desired.copy(targetAspect=1.5);bind(old,overlay);assertTrue(view.compositionGeometryReady)
   desired=desired.copy(targetAspect=2.0/3);bind(old,overlay);assertFalse(view.compositionGeometryReady)
   val next=BorderSnapshot.fixed135("fixture","pending",300,200,composition=desired)
   val nextExact=bitmap(next.outputWidth,next.outputHeight,Color.GREEN);val nextOverlay=bitmap(next.outputWidth,next.outputHeight,Color.TRANSPARENT)
   try{view.bitmap=nextExact;bind(next,nextOverlay);assertTrue(view.compositionGeometryReady);view.pan(10.0,0.0);assertTrue(view.isLiveComposition);assertTrue(events>count)}finally{view.close();nextExact.recycle();nextOverlay.recycle()}
  }finally{view.close();source.recycle();exact.recycle();overlay.recycle()}
 }
 @Test fun pendingPlainPresetKeepsDisplayedAspectAndIgnoresMovesButFreeAspectIsImmediate(){
  val source=bitmap(300,200,Color.RED);var settings=CropSettings(CropMode.SQUARE,CompositionTransform(zoom=2.0))
  val old=CropSnapshot.create(300,200,settings=settings);val exact=bitmap(old.outputWidth,old.outputHeight,Color.BLUE)
  val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,400,400);bitmap=exact};var events=0
  fun bind(s:CropSnapshot){view.bindCrop(s,source,settings,true){t,_->settings=settings.copy(transform=t);events++}}
  try{
   bind(old);view.pan(10.0,0.0);view.finishCompositionGesture();assertTrue(view.isLiveComposition)
   settings=settings.copy(mode=CropMode.RATIO_2_3);bind(old)
   assertFalse(view.compositionGeometryReady);assertFalse(view.isLiveComposition);assertTrue(view.isCompositionEditing)
   assertEquals(1.0,view.viewport.imageWidth/view.viewport.imageHeight,1e-9);assertEquals(Color.BLUE,center(view))
   val count=events;view.pan(10.0,0.0);view.pinch(1.1,200.0,200.0);view.finishCompositionGesture();assertEquals(count,events)
   val next=CropSnapshot.create(300,200,settings=settings);bind(next);assertTrue(view.compositionGeometryReady)
   view.pan(10.0,0.0);assertTrue(view.isLiveComposition);assertTrue(events>count)
   settings=CropSettings(CropMode.FREE,settings.effectiveTransform(300,200).copy(targetAspect=1.8));bind(next)
   assertTrue("FREE uses source-space selection, not the old fixed aperture",view.compositionGeometryReady)
   val before=events;view.pan(5.0,0.0);assertTrue(events>before);assertTrue(view.isLiveComposition)
  }finally{view.close();source.recycle();exact.recycle()}
 }
}
