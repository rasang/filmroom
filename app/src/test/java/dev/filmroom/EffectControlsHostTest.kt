package dev.filmroom.effects
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.Button
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EffectControlsHostTest {
 @Test fun actionButtonsNeverOverlapAndHaveHorizontalIcons(){
  val labels=listOf(listOf("重播","导出视频","导出 Live Photo"),listOf("Replay","Export video","Export Live Photo"),listOf("再生","動画を書き出す","Live Photoを書き出す"),listOf("다시 재생","동영상 내보내기","Live Photo 내보내기"))
  var count=0
  for((lang,texts) in labels.withIndex())for(font in listOf(1f,1.5f,2f))for(width in listOf(288,358,640)){
   val app=RuntimeEnvironment.getApplication();val config=Configuration(app.resources.configuration).apply{fontScale=font;densityDpi=160}
   val c=app.createConfigurationContext(config);val bar=EffectActionBar(c).apply{layoutDirection=View.LAYOUT_DIRECTION_LTR}
   texts.forEachIndexed{i,s->bar.addView(EffectControls.button(c,s,listOf("replay","video","live")[i],i==2){})}
   bar.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));bar.layout(0,0,width,bar.measuredHeight)
   for(i in 0..2){val b=bar.getChildAt(i) as Button;assertTrue(b.width>0);assertTrue(b.height>=56);assertTrue(b.right<=width);assertNotNull(b.compoundDrawablesRelative[0]);assertNull(b.compoundDrawablesRelative[1]);assertEquals(texts[i],b.text.toString());assertTrue(b.layout.lineCount>0);for(line in 0 until b.layout.lineCount){assertEquals(0,b.layout.getEllipsisCount(line));assertTrue("text clipped: ${texts[i]} width=$width font=$font",b.layout.getLineWidth(line)<=b.layout.width+1)}
    for(j in i+1..2){val n=bar.getChildAt(j);assertTrue(b.right<=n.left || b.bottom<=n.top)}
   }
   val bitmap=Bitmap.createBitmap(width,bar.height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff181818.toInt());bar.draw(Canvas(bitmap))
   val dir=File("/workspace/scratch/b88978eaec71/beta16-hotfix/test-artifacts/effect-controls");dir.mkdirs();File(dir,"bar-$lang-$font-$width.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();count++
  };assertEquals(36,count)
 }
 @Test fun repeatedClicksAndPrimaryStyleRemainIndependent(){val c=RuntimeEnvironment.getApplication();var clicks=0;val b=EffectControls.button(c,"Live Photo","live",true){clicks++};b.performClick();b.performClick();assertEquals(2,clicks);EffectControls.setPrimary(b,"live",false);assertEquals("Live Photo",b.text.toString());assertNotNull(b.compoundDrawablesRelative[0])}
 @Test fun primaryRefreshPreservesButtonAndBackPadding(){
  val c=RuntimeEnvironment.getApplication();fun dp(n:Int)=(n*c.resources.displayMetrics.density+.5f).toInt()
  val b=EffectControls.button(c,"导出 Live Photo","live",true){}
  fun check(n:Int){assertEquals(dp(n),b.paddingStart);assertEquals(dp(n),b.paddingEnd);assertEquals(dp(n),b.paddingTop);assertEquals(dp(n),b.paddingBottom)}
  check(12);EffectControls.setPrimary(b,"live",false);check(12);EffectControls.setPrimary(b,"live",true);check(12)
  b.setPaddingRelative(dp(13),dp(13),dp(13),dp(13));EffectControls.setPrimary(b,"back",false);check(13)
 }

}
