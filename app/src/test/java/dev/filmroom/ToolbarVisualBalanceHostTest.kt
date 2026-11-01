package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.ui.ExportQueueEntry
import dev.filmroom.ui.ExportQueueSummary
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolbarVisualBalanceHostTest {
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 @Test fun actualSevenActionRowAtFourWidthsAndQueueStates(){
  resetAndroidViewModelFactoryForHostTest()
  dev.filmroom.i18n.L10n.choose(RuntimeEnvironment.getApplication(),"zh")
  android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,0f)
  val out=File("/workspace/shared/filmroom-toolbar-v0115/"+(System.getenv("FILMROOM_TOOLBAR_QA_LABEL")?:"after")).apply{mkdirs()}
  try { for((w,h) in listOf(320 to 740,360 to 780,393 to 852,740 to 360))for(font in listOf(1f,2f)){
   RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi");RuntimeEnvironment.setFontScale(font)
   Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
    val a=c.get();val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    val queue=all(root).filterIsInstance<ExportQueueEntry>().single();val top=(queue.parent as View).parent as ViewGroup
    for(count in listOf(0,3,100)){
     queue.bind(ExportQueueSummary(count,count>0,true));queue.setForeground(false)
     root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
     val actions=all(top).filterIsInstance<Button>();assertEquals(7,actions.size)
     actions.forEach{assertTrue(it.width>=44);assertTrue(it.height>=48);assertFalse(it.contentDescription.isNullOrBlank());assertFalse(it.tooltipText.isNullOrBlank())}
     // Show each glyph at the same opacity to inspect its design independently of editability.
     actions.forEach{it.isEnabled=true;it.alpha=1f}
     val bitmap=Bitmap.createBitmap(w,top.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(0xff111413.toInt());top.draw(canvas)
     File(out,"toolbar-${w}-font${font}-queue${count}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
   }
  }}finally{RuntimeEnvironment.setFontScale(1f);android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,1f)}
 }
}
