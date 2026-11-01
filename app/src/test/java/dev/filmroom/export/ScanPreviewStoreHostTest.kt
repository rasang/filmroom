package dev.filmroom.export
import android.app.Application
import android.graphics.*
import dev.filmroom.ExportPreviewSelection
import dev.filmroom.border.*
import dev.filmroom.crop.*
import dev.filmroom.io.ExportFormat
import dev.filmroom.model.*
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScanPreviewStoreHostTest {
 private val asset=object:BorderRaster {
  override val stock="kodak_portra_400";override val version="test-v1";override val railRatio=.2;override val endRatio=.03;override val residentBytes=12L;override val featherTotalAt3072=4.0
  override fun sample(snapshot:BorderSnapshot,x:Double,y:Double,rgb:FloatArray){rgb.fill(0f)}
 }
 private val provider=object:BorderProvider {
  override fun snapshot(stock:String,width:Int,height:Int)=BorderSnapshot.create(stock,"test-v1",width,height,.2,.03,4.0)
  override fun resolve(snapshot:BorderSnapshot)=asset
 }
 private fun job(p:EditorState=EditorState(),border:BorderSnapshot?=null,crop:CropSnapshot?=null)=ExportJob(UUID.randomUUID().toString(),1,p,2400,1600,ExportFormat.JPEG,95,border=border,crop=crop)
 private fun dir()=File(RuntimeEnvironment.getApplication().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
 private fun source(d:File):File {
  val b=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888);Canvas(b).apply{drawColor(Color.BLUE);drawRect(0f,0f,120f,160f,Paint().apply{color=Color.RED})}
  return File(d,"scan-source-${UUID.randomUUID()}.png").also{it.outputStream().use{o->b.compress(Bitmap.CompressFormat.PNG,100,o)};b.recycle()}
 }
 @Test fun onlyMatchingAttemptAtStageSixSelectsRealColorAndFinalStillWins(){
  val d=dir();try{
   val id=ScanPreviewStore.begin(d);val input=source(d);val bytes=input.readBytes();val j=job();ScanPreviewStore.publish(d,id,input,j,provider){}
   assertArrayEquals(bytes,input.readBytes());val image=checkNotNull(ScanPreviewStore.select(d));val bitmap=BitmapFactory.decodeFile(image.path)
   assertTrue(Color.red(bitmap.getPixel(30,80))>240);assertTrue(Color.blue(bitmap.getPixel(210,80))>240);bitmap.recycle()
   File(d,"negative-preview.png").writeText("native negative")
   assertEquals("negative-preview.png",ExportPreviewSelection.select(d,ExportJobState.RUNNING,5).file!!.name)
   assertEquals(image,ExportPreviewSelection.select(d,ExportJobState.RUNNING,6).file)
   assertEquals("negative-preview.png",ExportPreviewSelection.select(d,ExportJobState.FAILED,6).file!!.name)
   File(d,"final-preview.jpg").writeText("real encoded final")
   assertEquals("final-preview.jpg",ExportPreviewSelection.select(d,ExportJobState.COMPLETED,8).file!!.name)
   val next=ScanPreviewStore.begin(d);assertNotEquals(id,next);assertNull(ScanPreviewStore.select(d));assertFalse(image.exists())
  }finally{d.deleteRecursively()}
 }
 @Test fun cancellationMalformedSourceAndOldTokenCannotPublish(){
  val d=dir();try{
   val old=ScanPreviewStore.begin(d);val input=source(d);val j=job()
   try{ScanPreviewStore.publish(d,old,input,j,provider){throw CancellationException()};fail()}catch(_:CancellationException){}
   assertNull(ScanPreviewStore.select(d));assertFalse(d.listFiles()!!.any{it.name.endsWith(".part")})
   ScanPreviewStore.begin(d)
   try{ScanPreviewStore.publish(d,old,input,j,provider){};fail()}catch(_:IllegalArgumentException){}
   val current=ScanPreviewStore.begin(d);val bad=File(d,"scan-source-${UUID.randomUUID()}.png").apply{writeText("broken")}
   try{ScanPreviewStore.publish(d,current,bad,j,provider){};fail()}catch(_:IllegalArgumentException){}
   assertNull(ScanPreviewStore.select(d));ScanPreviewStore.clear(d);assertFalse(d.listFiles()!!.any{it.name.startsWith("scan-")})
  }finally{d.deleteRecursively()}
 }
 @Test fun cropUsesFrozenNormalizedWindowAndBorderUsesFrozen135Orientation(){
  val d=dir();try{
   val settings=CropSettings.fromRect(CompositionTransform.Rect(0.0,0.0,.5,1.0),2400,1600)
   val p=EditorState(crop=settings);val crop=CropSnapshot.create(2400,1600,2400,1600,settings)
   var id=ScanPreviewStore.begin(d);ScanPreviewStore.publish(d,id,source(d),job(p,crop=crop),provider){}
   var b=BitmapFactory.decodeFile(ScanPreviewStore.select(d)!!.path);assertEquals(120,b.width);assertEquals(160,b.height);assertTrue(Color.red(b.getPixel(60,80))>240);b.recycle()
   val comp=CompositionTransform(targetAspect=2.0/3.0);val bp=EditorState(filmBorder=true,composition=comp)
   val border=BorderSnapshot.fixed135("kodak_portra_400","test-v1",2400,1600,2400,1600,comp)
   id=ScanPreviewStore.begin(d);ScanPreviewStore.publish(d,id,source(d),job(bp,border=border),provider){}
   b=BitmapFactory.decodeFile(ScanPreviewStore.select(d)!!.path);assertTrue(b.height>b.width)
   assertTrue(kotlin.math.abs(b.width.toDouble()/b.height-border.outputWidth.toDouble()/border.outputHeight)<.015);b.recycle()
  }finally{d.deleteRecursively()}
 }
}
