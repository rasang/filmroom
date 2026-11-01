package dev.filmroom
import dev.filmroom.model.*
import dev.filmroom.border.BorderSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE)
class CropModelHostTest {
 @Test fun defaultOriginalIsIdentityAndIndependentFromBorder(){
  for((w,h) in listOf(600 to 400,400 to 600,400 to 400,30000 to 2)){
   val state=EditorState();assertTrue(state.crop.isIdentity(w,h));assertEquals(1.0,state.presentationTransform(w,h).rect(w,h).width,1e-9)
   assertNull(state.composition.targetAspect)
  }
 }
 @Test fun everyModeHasBoundedCoverAndPreservesFocalZoom(){
  for(mode in CropMode.entries)for((w,h)in listOf(600 to 400,400 to 600,400 to 400)){
   val t=CropSettings(mode).effectiveTransform(w,h);val r=t.rect(w,h)
   assertEquals(t.targetAspect!!,r.width*w/(r.height*h),1e-9)
   val z=t.zoomBy(2.0,.5,.5,w,h).pan(1e9,-1e9,300.0,200.0,w,h).rect(w,h)
   assertTrue(z.left>=0 && z.top>=0 && z.right<=1.0+1e-9 && z.bottom<=1.0+1e-9)
  }
 }
 @Test fun arbitraryFreeRectangleRoundTripsExactly(){
  val r=CompositionTransform.Rect(.2,.1,.55,.7)
  val crop=CropSettings.fromRect(r,400,600)
  val got=crop.rect(400,600)
  assertEquals(r.left,got.left,1e-9);assertEquals(r.top,got.top,1e-9);assertEquals(r.width,got.width,1e-9);assertEquals(r.height,got.height,1e-9)
  assertEquals(crop,CropSettings.fromJson(crop.json()))
 }
 @Test fun explicit135DirectionDoesNotSwapCanonicalSourceAndOldSnapshotsStayV2(){
  for((w,h) in listOf(600 to 400,400 to 600))for(aspect in listOf(1.5,2.0/3)){
   val s=BorderSnapshot.fixed135("fixture","v",w,h,composition=CompositionTransform(targetAspect=aspect))
   assertEquals(3,s.geometryVersion);assertEquals(w,s.canonicalWidth);assertEquals(h,s.canonicalHeight)
   assertEquals(aspect<1,s.portrait);assertEquals(aspect,s.photoWidth.toDouble()/s.photoHeight,1e-9)
   assertEquals(s,BorderSnapshot.fromJson(s.json()))
   assertEquals(aspect,s.sourceRect.width*w/(s.sourceRect.height*h),1e-9)
  }
  val old=BorderSnapshot.fixed135("fixture","v",400,600)
  assertEquals(2,old.geometryVersion);assertTrue(old.portrait);assertEquals(old,BorderSnapshot.fromJson(old.json()))
 }
 @Test fun presentationJsonMigrationAndScienceIdentity(){
  val base=EditorState();val changed=base.copy(composition=CompositionTransform(targetAspect=1.5),crop=CropSettings(CropMode.SQUARE,CompositionTransform(zoom=2.0)))
  assertEquals(base.json(includePresentation=false),changed.json(includePresentation=false))
  assertEquals(changed,EditorState.fromJson(changed.json()))
  val legacy=org.json.JSONObject(base.json()).apply{remove("crop")}
  assertEquals(CropSettings(),EditorState.fromJson(legacy.toString()).crop)
 }
 @Test fun invalidFreeGeometryAndLegacyAspectAreRejected(){
  fun rejects(block:()->Unit){try{block();fail("accepted invalid crop")}catch(_:IllegalArgumentException){}}
  rejects{CropSettings.fromRect(CompositionTransform.Rect(-.1,0.0,.5,.5),400,600)}
  rejects{CropSettings.fromRect(CompositionTransform.Rect(0.0,0.0,.001,.001),400,600)}
  rejects{BorderSnapshot.fixed135("fixture","v",400,600,composition=CompositionTransform(targetAspect=1.0))}
  val old=org.json.JSONObject(BorderSnapshot.fixed135("fixture","v",400,600).json()).put("targetAspect",1.5)
  rejects{BorderSnapshot.fromJson(old.toString())}
 }
}
