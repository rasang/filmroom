package dev.filmroom.ui
import android.app.Application
import android.graphics.Bitmap
import android.view.MotionEvent
import dev.filmroom.crop.CropSnapshot
import dev.filmroom.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PlainCropPhotoViewHostTest {
 @Test fun freeCornerResizeChangesAspectWithoutChangingSourceAndCancelDoesNotFlush(){
  val source=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
  val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,600,400);bitmap=source}
  var settings=CropSettings(CropMode.FREE);var commits=0
  val snapshot=CropSnapshot.create(600,400,settings=settings)
  view.bindCrop(snapshot,source,settings,true){t,end->settings=settings.copy(transform=t);if(end)commits++}
  fun touch(action:Int,x:Float,y:Float){val e=MotionEvent.obtain(0,20,action,x,y,0);try{view.onTouchEvent(e)}finally{e.recycle()}}
  touch(MotionEvent.ACTION_DOWN,599f,399f);touch(MotionEvent.ACTION_MOVE,450f,250f)
  val rect=settings.rect(600,400)
  assertEquals(.75,rect.width,.01);assertEquals(.625,rect.height,.01)
  assertTrue(view.isLiveComposition);assertTrue(view.compositionGeometryReady);assertSame(source,view.bitmap)
  view.discardCompositionGesture();view.finishCompositionGesture();assertEquals(0,commits)
  view.bindCrop(snapshot,source,CropSettings(CropMode.FREE),false){_,_->}
  assertFalse(view.isCompositionEditing);assertFalse(view.isLiveComposition)
  view.close();assertFalse(source.isRecycled);source.recycle()
 }
 @Test fun ratioCropUsesIndependentViewerAndExactPendingHandoff(){
  val source=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888)
  val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,600,400);bitmap=source}
  var crop=CropSettings(CropMode.SQUARE);val snapshot=CropSnapshot.create(600,400,settings=crop)
  view.pinch(2.0,300.0,200.0);val viewer=view.viewerState()
  view.bindCrop(snapshot,source,crop,true){t,_->crop=crop.copy(transform=t)}
  assertEquals(1.0,view.viewport.zoom,0.0)
  view.pinch(2.0,300.0,200.0);view.pan(30.0,0.0);view.finishCompositionGesture()
  view.bindCrop(snapshot,source,crop,false){_,_->}
  assertTrue(view.isLiveComposition);assertArrayEquals(viewer,view.viewerState(),1e-6f)
  view.bindCrop(CropSnapshot.create(600,400,settings=crop),source,crop,false){_,_->}
  assertFalse(view.isLiveComposition);view.close();source.recycle()
 }
 @Test fun extremeAspectHandleRequestsRejectSafely(){
  for((w,h)in listOf(30000 to 1,1 to 30000)){
   val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
   val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,600,400);bitmap=source}
   var crop=CropSettings(CropMode.FREE)
   view.bindCrop(CropSnapshot.create(w,h,settings=crop),source,crop,true){t,_->crop=crop.copy(transform=t)}
   val a=view.compositionAperture()!!
   fun touch(action:Int,x:Float,y:Float){val e=MotionEvent.obtain(0,20,action,x,y,0);try{view.onTouchEvent(e)}finally{e.recycle()}}
   touch(MotionEvent.ACTION_DOWN,a.right,a.bottom)
   repeat(30){touch(MotionEvent.ACTION_MOVE,(it*100-1000).toFloat(),(it*80-800).toFloat())}
   touch(MotionEvent.ACTION_UP,a.right,a.bottom)
   val r=crop.rect(w,h);assertTrue(r.left>=0 && r.top>=0 && r.right<=1.0+1e-9 && r.bottom<=1.0+1e-9)
   assertTrue(crop.effectiveTransform(w,h).targetAspect!! in (1.0/30000)..30000.0)
   view.close();source.recycle()
  }
 }

}
