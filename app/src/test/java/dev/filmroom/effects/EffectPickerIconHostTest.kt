package dev.filmroom.effects
import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.filmroom.ui.FilmroomPalette
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
 @Test fun sharedThreeOptionPickerKeepsWhiteOrangeSelectionWhenRecycled() {
  val context=RuntimeEnvironment.getApplication();val parent=FrameLayout(context)
  val adapter=EffectPickerAdapter(context,arrayOf("无","观片台","取景器"),listOf(null,EffectKind.LIGHT_TABLE,EffectKind.OPTICAL_LEAK),1)
  val row=adapter.getView(1,null,parent) as TextView
  assertEquals(FilmroomPalette.accent,row.currentTextColor)
  val off=adapter.getView(0,row,parent) as TextView
  assertSame(row,off);assertEquals("无",off.text.toString());assertEquals(FilmroomPalette.ink,off.currentTextColor)
  assertNotNull(off.compoundDrawablesRelative[0]);assertEquals(3,adapter.count)
 }
 @Test fun sharedIconsHonorTintAlphaAndKeepEmptyBackground() {
  for(kind in listOf(null,EffectKind.LIGHT_TABLE,EffectKind.OPTICAL_LEAK)) {
   val bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888)
   val icon=EffectPickerIcon(kind,Color.WHITE).apply {setBounds(0,0,96,96);alpha=128}
   icon.draw(Canvas(bitmap))
   val pixels=IntArray(96*96);bitmap.getPixels(pixels,0,96,0,0,96,96)
   assertEquals(0,bitmap.getPixel(0,0));assertTrue(pixels.any{Color.alpha(it)>0})
   assertTrue(pixels.all{Color.alpha(it)<255})
   assertTrue(pixels.filter{Color.alpha(it)>0}.all{Color.red(it)==255&&Color.green(it)==255&&Color.blue(it)==255})
   bitmap.recycle()
  }
 }
 @Test fun renderActualPickerRows() {
  Robolectric.buildActivity(Activity::class.java).setup().use { host ->
   val context=host.get()
   // Relative compound drawables only resolve after the row inherits a layout direction.
   // The old unattached fixture silently drew text without the drawable or its reserved width.
   val parent=LinearLayout(context).apply {
    orientation=LinearLayout.VERTICAL
    layoutDirection=View.LAYOUT_DIRECTION_LTR
    setBackgroundColor(0xff222222.toInt())
   }
   context.setContentView(parent)
   val adapter=EffectPickerAdapter(context,arrayOf("观片台","取景器"))
   val scale=context.resources.displayMetrics.density
   val width=(320*scale).toInt();val h=(60*scale).toInt()
   for(i in 0..1) {
    val row=adapter.getView(i,null,parent) as TextView
    parent.addView(row,LinearLayout.LayoutParams(width,h))
   }
   shadowOf(Looper.getMainLooper()).idle()
   parent.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h*2,View.MeasureSpec.EXACTLY))
   parent.layout(0,0,width,h*2)
   for(i in 0..1) {
    val row=parent.getChildAt(i) as TextView
    assertTrue(row.isAttachedToWindow)
    assertEquals(View.LAYOUT_DIRECTION_LTR,row.layoutDirection)
    assertNotNull(row.compoundDrawables[0])
    assertTrue("icon width and gap must be reserved",row.compoundPaddingLeft >= (67*scale).toInt())
   }
   val image=Bitmap.createBitmap(width,h*2,Bitmap.Config.ARGB_8888)
   parent.draw(Canvas(image))
   for(i in 0..1) {
    var orangePixels=0
    for(y in i*h until (i+1)*h) for(x in (24*scale).toInt() until (53*scale).toInt()) {
     val color=image.getPixel(x,y)
     if(Color.red(color)>120 && Color.red(color)>Color.green(color)*1.15 && Color.green(color)>Color.blue(color)*1.2) orangePixels++
    }
    assertTrue("rendered row $i must visibly contain its orange icon",orangePixels>20)
   }
   File(System.getProperty("filmroom.icons.capture") ?: "build/test-artifacts/effect-picker-b.png").apply {parentFile?.mkdirs()}.outputStream().use{out->image.compress(Bitmap.CompressFormat.PNG,100,out)}
   image.recycle()
  }
 }
}
