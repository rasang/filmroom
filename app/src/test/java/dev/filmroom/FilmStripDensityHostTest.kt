package dev.filmroom

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import dev.filmroom.io.PhotoSource
import dev.filmroom.ui.FilmStockArtwork
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h780dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilmStripDensityHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun layout(v:View,w:Int,h:Int){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);shadowOf(android.os.Looper.getMainLooper()).idle()}
    @Suppress("UNCHECKED_CAST")
    @Test fun twoAndAHalfCardsUseActualPaneWidthAndKeepLastClickStable() {
        val target=File(System.getenv("FILMROOM_FILM_STRIP_QA") ?: "build/ui-host-renders/film-strip-25").apply{mkdirs()}
        try { for((width,height) in listOf(320 to 780,393 to 820,600 to 960,780 to 393)) for(scale in listOf(1f,2f)) for(dpi in listOf(160,440)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-${dpi}dpi");RuntimeEnvironment.setFontScale(scale)
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val a=controller.get()
                val model=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
                val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(model) as MutableStateFlow<EditorUiState>
                val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.rgb(62,79,76))}
                flow.value=EditorUiState(photo=PhotoSource(File(a.cacheDir,"art-fixture"),"fixture.jpg","image/jpeg",16,16,100),original=bitmap,rendered=bitmap)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val density=a.resources.displayMetrics.density;val w=(width*density).toInt();val h=(height*density).toInt()
                layout(root,w,h)
                var content=root
                if(scale>1f && all(root).none{it.tag=="strip:films"}){
                    all(root).filterIsInstance<Button>().single{it.text.toString()=="调整胶片型号"}.performClick()
                    content=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
                    layout(content,w-(32*density).toInt(),h-(32*density).toInt())
                }
                fun strip()=all(content).filterIsInstance<HorizontalScrollView>().single{it.tag=="strip:films"}
                strip().scrollTo(0,0)
                val initialCards=strip().getChildAt(0) as ViewGroup
                val first=initialCards.getChildAt(0)
                val second=initialCards.getChildAt(1)
                val third=initialCards.getChildAt(2)
                val viewport=strip().width-strip().paddingLeft-strip().paddingRight
                if(scale==1f){
                    assertEquals("No hidden leading gutter",0,first.left)
                    assertTrue("Two complete cards",second.right<=viewport)
                    assertEquals("Exactly half third card at actual pane width",third.width/2f,(viewport-third.left).toFloat(),1f)
                    assertTrue(kotlin.math.abs(first.width-second.width)<=1);assertTrue(kotlin.math.abs(first.width-third.width)<=1)
                }
                // Keep a real host-rendered 135-cartridge view as well as cinema stocks,
                // so label/leader geometry can be reviewed at actual display density.
                val cartridge=Bitmap.createBitmap(content.width,content.height,Bitmap.Config.ARGB_8888)
                content.draw(Canvas(cartridge))
                File(target,"${width}x${height}-${dpi}dpi-font${scale.toInt()}-135-cartridges.png").outputStream().use{cartridge.compress(Bitmap.CompressFormat.PNG,100,it)}
                cartridge.recycle()
                strip().isSmoothScrollingEnabled=false
                strip().scrollTo(strip().getChildAt(0).width,0)
                val cards=strip().getChildAt(0) as ViewGroup
                assertEquals(22,cards.childCount)
                val last=cards.getChildAt(cards.childCount-1) as Button
                val x=strip().scrollX;val initialWidth=strip().width;val initialContent=cards.width;assertTrue(x>0);last.performClick();shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));layout(content,content.width,content.height)
                // Expanded dialog can relayout its scroll gutters after text reflow. Normal
                // editor must preserve exact pixels; either surface must retain the last stock.
                // Film selection is tap-only and keeps the current browsing position.
                assertTrue("Never reset to the first stock",strip().scrollX>0)
                val chosen=all(strip()).filterIsInstance<Button>().single{it.isSelected}
                val catalogRows=org.json.JSONObject(a.assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
                val expectedLast=(0 until catalogRows.length()).map{catalogRows.getJSONObject(it)}.last{it.getString("kind") in setOf("negative","reversal")}.getString("id")
                assertEquals(expectedLast,chosen.tag)
                assertTrue(chosen.compoundDrawables.any{it is BitmapDrawable})
                assertTrue(chosen.width>=48*density);assertTrue(chosen.height>=48*density)
                assertEquals("Click does not recenter the film list ${width}x$height font$scale oldViewport=$initialWidth newViewport=${strip().width} oldContent=$initialContent newContent=${strip().getChildAt(0).width}",x,strip().scrollX)
                assertTrue(chosen.left>=strip().scrollX);assertTrue(chosen.right<=strip().scrollX+strip().width)
                for(button in all(strip()).filterIsInstance<Button>()){
                    assertNull(button.ellipsize)
                    assertTrue("Each card fits its viewport",button.width<=strip().width)
                    val textWidth=button.width-button.compoundPaddingLeft-button.compoundPaddingRight
                    for(line in 0 until button.layout.lineCount)assertTrue("Whole label fits without clipping ${button.text}",button.layout.getLineWidth(line)<=textWidth+1)
                    for(line in 0 until button.layout.lineCount)assertEquals(0,button.layout.getEllipsisCount(line))
                }
                val output=Bitmap.createBitmap(content.width,content.height,Bitmap.Config.ARGB_8888);content.draw(Canvas(output))
                File(target,"${width}x${height}-${dpi}dpi-font${scale.toInt()}-last-stock.png").outputStream().use{output.compress(Bitmap.CompressFormat.PNG,100,it)}
                output.recycle()
            }
        }} finally {RuntimeEnvironment.setFontScale(1f)}
    }
}
