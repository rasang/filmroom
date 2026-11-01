package dev.filmroom.ui

import android.app.Application
import android.graphics.*
import android.os.Looper
import dev.filmroom.border.*
import dev.filmroom.crop.*
import dev.filmroom.model.*
import dev.filmroom.roi.*
import dev.filmroom.io.PhotoSource
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropViewVisualAndRoiHostTest {
 private fun source()=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).apply {
  val c=Canvas(this);c.drawColor(Color.rgb(35,100,160));val p=Paint()
  for(y in 0..3)for(x in 0..5){p.color=Color.rgb(35+x*38,45+y*55,180-x*20);c.drawRect(x*100f,y*100f,(x+1)*100f,(y+1)*100f,p)}
  p.color=Color.WHITE;p.textSize=24f;c.drawText("AUTHORED SOURCE / TOP",12f,30f,p)
  p.color=Color.YELLOW;c.drawCircle(170f,140f,45f,p);p.color=Color.RED;c.drawRect(380f,240f,460f,330f,p)
 }
 private val raster=object:BorderRaster {
  override val stock="fixture_only";override val version="view_qa";override val railRatio=.2;override val endRatio=.04
  override val residentBytes=16L;override val featherTotalAt3072=4.0
  override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){
   val lx=if(snapshot.portrait)y else x;val ly=if(snapshot.portrait)snapshot.outputWidth-x else y
   val hole=(ly<snapshot.pixelsPerMm*4 || ly>snapshot.pixelsPerMm*31) && (lx/snapshot.pixelsPerMm%4.75) in 1.0..2.9
   rgb[0]=if(hole)1f else .25f;rgb[1]=if(hole)1f else .12f;rgb[2]=if(hole)1f else .07f
  }
 }
 @Test fun actualPhotoViewVisualSheetOptional(){
  val destination=System.getenv("FILMROOM_CROP_VIEW_QA_DIR");assumeTrue(!destination.isNullOrBlank())
  val dir=File(destination!!).apply{mkdirs()};val photo=source()
  val sheet=Bitmap.createBitmap(1200,1500,Bitmap.Config.ARGB_8888);val sheetCanvas=Canvas(sheet);sheetCanvas.drawColor(Color.DKGRAY)
  val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=15f}
  var index=0
  fun save(name:String,view:PhotoView){
   val frame=Bitmap.createBitmap(400,460,Bitmap.Config.ARGB_8888)
   try{view.draw(Canvas(frame));File(dir,"$name.png").outputStream().use{assertTrue(frame.compress(Bitmap.CompressFormat.PNG,100,it))}
    val x=(index%3)*400f;val y=(index/3)*500f;sheetCanvas.drawBitmap(frame,x,y+30,null);sheetCanvas.drawText(name,x+8,y+20,label);index++
   }finally{frame.recycle()}
  }
  fun plain(name:String,settings:CropSettings,editing:Boolean){
   val snap=CropSnapshot.create(photo.width,photo.height,settings=settings);val exact=CropPreview.compose(photo,snap)
   val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,400,460);bitmap=exact}
   try{view.bindCrop(snap,photo,settings,editing){_,_->};save(name,view)}finally{view.close();exact.recycle()}
  }
  try {
   plain("01_original",CropSettings(),false)
   val free=CropSettings.fromRect(CompositionTransform.Rect(.12,.13,.7,.7),600,400)
   plain("02_free_handles",free,true);plain("03_free_locked",free,false)
   val square=CropSettings(CropMode.SQUARE,CompositionTransform(.55,.5,1.2))
   plain("04_square_edit",square,true);plain("05_square_locked",square,false)
   for((name,aspect)in listOf("landscape" to 1.5,"portrait" to 2.0/3)){
    val crop=CompositionTransform(.55,.5,1.2,aspect).clamped(600,400)
    val snap=BorderSnapshot.fixed135(raster.stock,raster.version,600,400,composition=crop)
    val exact=BorderPreview.compose(photo,snap,raster);val overlay=InteractiveBorderPreview.create(snap,raster)
    val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,400,460);bitmap=exact}
    try{
     var moved=crop
     view.bindComposition(snap,photo,crop,true,overlay){t,_->moved=t};view.pan(15.0,0.0);save("${index+1}_${name}_live",view)
     view.finishCompositionGesture()
     val settledSnapshot=snap.copy(composition=moved)
     val settled=BorderPreview.compose(photo,settledSnapshot,raster)
     try{view.bitmap=settled;view.bindComposition(settledSnapshot,photo,moved,false,overlay){_,_->};save("${index+1}_${name}_locked",view)}finally{settled.recycle()}
    }finally{view.close();exact.recycle();overlay.recycle()}
   }
   File(dir,"contact-sheet.png").outputStream().use{assertTrue(sheet.compress(Bitmap.CompressFormat.PNG,100,it))}
   File(dir,"README.txt").writeText("Actual Android Canvas PhotoView host output. Authored colored source and explicitly non-authentic authored frame fixture. No film science or device performance claim. Free handles/full-source editor; fixed ratio edit and canonical shared CropPreview/BorderPreview locked output. Nine individual views plus contact sheet.\n")
  }finally{photo.recycle();sheet.recycle()}
 }
 @Test fun plainBorderDirectionAndGestureTransitionsKeepOneScientificSession(){
  val photo=source();val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,600,500);bitmap=photo}
  val sessions=mutableListOf<EditorDetailControllerTest.FakeSession>();val paramsSeen=mutableListOf<EditorState>()
  view.configureDetail(EditorDetailProvider{src,params->paramsSeen+=params;EditorDetailControllerTest.FakeSession(RenderKey(src.cacheKey,params.json(),"a","d","oriented",1536,1024)).also{sessions+=it}})
  val src=PhotoSource(File("authored-unused"),"same-science","image/jpeg",600,400,0)
  fun await(done:()->Boolean){repeat(1200){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(done())return;Thread.sleep(2)};error("ROI deadline")}
  var crop=CropSettings(CropMode.SQUARE,CompositionTransform(zoom=3.0));var state=EditorState(crop=crop)
  view.bindDetail(src,state,true,presentedBorder=true)
  view.bindCrop(CropSnapshot.create(600,400,settings=crop),photo,crop,true){t,_->crop=crop.copy(transform=t)}
  await{view.detailCacheBytes>0};assertEquals(1,sessions.size)
  repeat(8){i->
   state=state.copy(negativeWhiteHoles=i%2==0)
   if(i%2==0){
    crop=CropSettings(CropMode.SQUARE,CompositionTransform(.5,.5,3.0));state=state.copy(filmBorder=false,crop=crop)
    view.bindDetail(src,state,true,presentedBorder=true)
    view.bindCrop(CropSnapshot.create(600,400,settings=crop),photo,crop,true){t,_->crop=crop.copy(transform=t)}
    view.pan(5.0,0.0);view.finishCompositionGesture()
    view.bindCrop(CropSnapshot.create(600,400,settings=crop),photo,crop,false){_,_->}
   }else{
    val c=CompositionTransform(zoom=3.0,targetAspect=if(i%4==1)1.5 else 2.0/3)
    state=state.copy(filmBorder=true,composition=c)
    view.bindDetail(src,state,true,presentedBorder=true)
    val snap=BorderSnapshot.fixed135(raster.stock,raster.version,600,400,composition=c)
    val overlay=InteractiveBorderPreview.create(snap,raster)
    try{view.bindComposition(snap,photo,c,true,overlay){_,_->};view.pan(5.0,0.0);view.finishCompositionGesture();view.bindComposition(snap,photo,c,false,overlay){_,_->}}
    finally{view.bindComposition(snap,photo,c,false){_,_->};overlay.recycle()}
   }
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
   assertEquals(1,sessions.size);assertFalse(sessions.single().closed)
  }
  assertEquals(CropSettings(),paramsSeen.single().crop);assertEquals(CompositionTransform(),paramsSeen.single().composition);assertFalse(paramsSeen.single().filmBorder);assertTrue(paramsSeen.single().negativeWhiteHoles)
  assertNotNull(view.detailKey);assertTrue(view.detailCacheBytes>0)
  view.close();await{sessions.single().closed};photo.recycle()
 }
 @Test fun fullSourceFreeEditorRetainsZoomedScienceUntilRealParameterChange(){
  val photo=source();val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,600,500);bitmap=photo}
  val sessions=mutableListOf<EditorDetailControllerTest.FakeSession>()
  view.configureDetail(EditorDetailProvider{src,p->EditorDetailControllerTest.FakeSession(RenderKey(src.cacheKey,p.json(),"a","d","o",1536,1024)).also{sessions+=it}})
  val src=PhotoSource(File("unused"),"retain-edit-science","image/jpeg",600,400,0)
  val locked=CropSettings(CropMode.SQUARE,CompositionTransform(zoom=3.0))
  val free=CropSettings(CropMode.FREE,locked.effectiveTransform(600,400))
  fun bind(settings:CropSettings,editing:Boolean,brightness:Float=0f){
   view.bindDetail(src,EditorState(crop=settings,brightness=brightness),true,presentedBorder=true)
   view.bindCrop(CropSnapshot.create(600,400,settings=settings),photo,settings,editing){_,_->}
  }
  fun await(done:()->Boolean){repeat(1200){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(done())return;Thread.sleep(2)};error("ROI retain deadline")}
  try{
   bind(locked,false);await{view.detailCacheBytes>0};assertEquals(1,sessions.size)
   repeat(8){i->
    bind(free,true);assertEquals(1.0,view.compositionDetailZoom(),.0001)
    view.pan(5.0,0.0)
    if(i%2==0)view.finishCompositionGesture()else view.discardCompositionGesture()
    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
    assertEquals(1,sessions.size);assertFalse(sessions[0].closed)
    bind(if(i%2==0)free else locked,false)
    await{view.detailCacheBytes>0};assertEquals(1,sessions.size);assertFalse(sessions[0].closed)
   }
   bind(locked,false,.1f);await{sessions.size==2 && sessions[0].closed && view.detailCacheBytes>0}
   assertFalse(sessions[1].closed)
  }finally{view.close();photo.recycle()}
 }

}
