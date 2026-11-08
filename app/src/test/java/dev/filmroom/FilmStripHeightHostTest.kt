package dev.filmroom
import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.filmroom.ui.FilmStockScrollView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w360dp-h800dp-mdpi")
class FilmStripHeightHostTest {
 @Test fun catalogHeightSurvivesEmptySingleFilteredRotatedAndLargeFont(){
  val context=RuntimeEnvironment.getApplication()
  for(scale in listOf(1f,1.6f)) {
   val config=android.content.res.Configuration(context.resources.configuration);config.fontScale=scale
   val c=context.createConfigurationContext(config)
   for(labels in listOf(listOf("无","Fujifilm\nC200","Kodak\nVery long name that wraps across multiple lines"),listOf("なし","富士フイルム\nC200","コダック ポートラ\n長いフィルム名"),listOf("없음","후지필름\nC200","코닥 포트라 매우 긴 필름 이름"))) {
    val refs=labels.map{label->Button(c).apply{text=label;textSize=12f;isAllCaps=false;minLines=2;maxLines=Int.MAX_VALUE;setPadding(8,4,8,4);val art=ColorDrawable(0xff665544.toInt());art.setBounds(0,0,56,56);setCompoundDrawables(null,art,null,null);compoundDrawablePadding=2;layoutParams=LinearLayout.LayoutParams(112,-2).apply{marginEnd=4}}}
    val row=LinearLayout(c).apply{orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false}
    val strip=FilmStockScrollView(c,112,56).apply{heightReferenceCards=refs;addView(row)}
    for(width in listOf(360,800,240,360)) {
     var expected=-1
     for(indices in listOf(listOf(0,1,2),listOf(1),emptyList(),listOf(0),listOf(0,1,2))) {
      row.removeAllViews()
      for(i in indices)row.addView(refs[i])
      if(indices.isEmpty())row.addView(TextView(c).apply{tag="film-filter-empty";text="暂无符合条件的胶卷"})
      strip.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));strip.layout(0,0,width,strip.measuredHeight)
      if(expected<0)expected=strip.measuredHeight
      assertEquals("scale=$scale width=$width indices=$indices",expected,strip.measuredHeight)
      for(i in 0 until row.childCount)assertEquals(refs.maxOf{it.measuredHeight},row.getChildAt(i).height)
     }
    }
   }
  }
 }
}
