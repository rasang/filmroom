package dev.filmroom

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import dev.filmroom.i18n.L10n
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w393dp-h852dp-mdpi")
class UpdateSettingsStyleHostTest {
 private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
 @Test fun lastCardAndLogButtonStyleMatchInFourLanguages(){
  val app=RuntimeEnvironment.getApplication()
  try {for((lang,title) in listOf("zh" to "检测更新","en" to "Check for updates","ja" to "更新を確認","ko" to "업데이트 확인")){
   L10n.choose(app,lang)
   Robolectric.buildActivity(SettingsActivity::class.java).setup().use{host->
    val root=host.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    root.measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(852,View.MeasureSpec.EXACTLY));root.layout(0,0,393,852)
    val views=all(root);val content=views.single{it.tag=="settings-content"} as ViewGroup
    val update=views.single{it.tag=="settings-section:update"} as ViewGroup
    assertSame(update,content.getChildAt(content.childCount-1))
    assertEquals(listOf("settings-section:language","settings-section:photos","settings-section:history","settings-section:backup","settings-section:diagnostics","settings-section:update"),views.filter{(it.tag as? String)?.startsWith("settings-section:")==true}.map{it.tag})
    val check=views.single{it.tag=="check-updates"} as Button
    val logs=all(views.single{it.tag=="settings-section:diagnostics"}).filterIsInstance<Button>().last()
    assertEquals(title,check.text.toString());assertEquals(logs.textSize,check.textSize,0f);assertEquals(logs.currentTextColor,check.currentTextColor)
    val cb=check.background as GradientDrawable;val lb=logs.background as GradientDrawable
    assertEquals(lb.color,cb.color);assertEquals(lb.cornerRadius,cb.cornerRadius,0f)
    assertEquals(logs.minHeight,check.minHeight);assertTrue(check.height>=48);assertEquals(logs.width,check.width)
    val cp=check.layoutParams as LinearLayout.LayoutParams;val lp=logs.layoutParams as LinearLayout.LayoutParams
    assertEquals(lp.topMargin,cp.topMargin);assertEquals(lp.bottomMargin,cp.bottomMargin)
    assertTrue(check.width>0);assertTrue(update.top>logs.bottom);assertEquals(0,check.layout.getEllipsisCount(0))
   }
  }}finally{L10n.choose(app,"zh")}
 }
}
