package dev.filmroom.ui
import android.animation.ValueAnimator
import android.app.Application
import android.graphics.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportPreviewEmphasisHostTest {
 private fun field(v:ExportPreviewView,key:String)=ExportPreviewView::class.java.getDeclaredField(key).apply{isAccessible=true}.get(v)
 private fun bitmap(v:ExportPreviewView)=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}
 private fun view(real:Boolean=false):ExportPreviewView {
  val photo=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888);Canvas(photo).apply{drawColor(Color.BLACK);val p=Paint().apply{color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=2f};drawRect(50f,40f,120f,180f,p);drawRect(160f,70f,230f,180f,p)}
  val source=if(real){val original=javaClass.getResourceAsStream("/queue/public-source.jpg").use{BitmapFactory.decodeStream(it)};ExportContourCache.get("stage3-evidence-city",original).also{original.recycle();photo.recycle()}}else photo
  return ExportPreviewView(RuntimeEnvironment.getApplication()).apply{layout(0,0,320,200);bindJob("light-test");setForeground(true);show(source,"contour");setBreathing(true);(field(this,"breathing")as ValueAnimator).setCurrentFraction(1f)}
 }
 @Test fun stageThreeGetsStrongerWithoutAbruptFirstFrameOrChangingImage(){
  val v=view();val source=field(v,"current");val previous=bitmap(v);v.setBreathing(true,true)
  assertEquals(previous.getPixel(130,100),bitmap(v).getPixel(130,100))
  (field(v,"emphasis")as ValueAnimator).setCurrentFraction(1f)
  val enhanced=bitmap(v);assertTrue(Color.red(enhanced.getPixel(130,100))>Color.red(previous.getPixel(130,100))+20)
  assertTrue(Color.red(enhanced.getPixel(50,70))>240 && Color.green(enhanced.getPixel(50,70))>225);assertSame(source,field(v,"current"))
  v.setForeground(false);assertFalse(v.animating);v.release()
 }
 @Test fun controlledBeforeAfterFramesAndReducedMotion(){
  val dir=File(System.getProperty("filmroom.imprint.evidenceDir") ?: "/workspace/shared/scan-preview17-staging/visuals").apply{mkdirs()};val v=view(true)
  fun save(name:String){File(dir,"$name.png").outputStream().use{bitmap(v).compress(Bitmap.CompressFormat.PNG,100,it)}}
  save("stage2-peak-original");v.setBreathing(true,true)
  repeat(10){(field(v,"emphasis")as ValueAnimator).setCurrentFraction(it/9f);save("strength-${it.toString().padStart(2,'0')}")}
  save("stage3-peak-enhanced")
  repeat(56){(field(v,"breathing")as ValueAnimator).setCurrentFraction(it/55f*2f);save("stage3-${it.toString().padStart(2,'0')}")}
  val scale=ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true};scale.invoke(null,0f)
  try{v.setForeground(true);assertFalse(v.animating)}finally{scale.invoke(null,1f);v.release()}
 }
}
