package dev.filmroom.effects
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EffectPickerIconHostTest {
 @Test fun iconsAreDistinctAndConfinedToBounds() {
  fun draw(light:Boolean):IntArray {
   val b=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888)
   EffectPickerIcon(light,0xffe5a35d.toInt()).apply{setBounds(12,12,84,84);draw(Canvas(b))}
   val p=IntArray(96*96);b.getPixels(p,0,96,0,0,96,96)
   assertTrue(p.any{it ushr 24>0})
   for(y in 0 until 96)for(x in 0 until 96)if(x<12||x>=84||y<12||y>=84)assertEquals(0,p[y*96+x])
   b.recycle();return p
  }
  assertFalse(draw(true).contentEquals(draw(false)))
 }
 @Test fun recycledRowsKeepMatchingLabelsIconsAndTargets() {
  val context=RuntimeEnvironment.getApplication();val parent=FrameLayout(context)
  val adapter=EffectPickerAdapter(context,arrayOf("观片台","取景器"))
  val row=adapter.getView(0,null,parent) as TextView
  assertEquals("观片台",row.text.toString());assertEquals(2,adapter.count)
  val icon=row.compoundDrawablesRelative[0]
  val recycled=adapter.getView(1,row,parent) as TextView
  assertSame(row,recycled);assertEquals("取景器",recycled.text.toString())
  assertNotSame(icon,recycled.compoundDrawablesRelative[0]);assertEquals(icon.bounds,recycled.compoundDrawablesRelative[0].bounds)
  assertEquals(1L,adapter.getItemId(1))
  val scale=context.resources.displayMetrics.density
  assertTrue(recycled.minHeight>=48*scale)
 }
 @Test fun renderActualPickerRows() {
  val context=RuntimeEnvironment.getApplication();val parent=FrameLayout(context)
  val adapter=EffectPickerAdapter(context,arrayOf("观片台","取景器"))
  val scale=context.resources.displayMetrics.density
  val width=(320*scale).toInt();val h=(60*scale).toInt()
  val image=Bitmap.createBitmap(width,h*2,Bitmap.Config.ARGB_8888);image.eraseColor(0xff222222.toInt())
  val canvas=Canvas(image)
  for(i in 0..1){val row=adapter.getView(i,null,parent);row.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,1073741824),android.view.View.MeasureSpec.makeMeasureSpec(h,1073741824));row.layout(0,0,width,h);val save=canvas.save();canvas.translate(0f,i*h.toFloat());row.draw(canvas);canvas.restoreToCount(save)}
  System.getProperty("filmroom.icons.capture")?.let{File(it).outputStream().use{out->image.compress(Bitmap.CompressFormat.PNG,100,out)}}
  image.recycle()
 }
}
