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
class FilmStockArtworkHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun layout(v:View,w:Int,h:Int){v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);shadowOf(android.os.Looper.getMainLooper()).idle()}
    @Test fun filmExposureLeadsAndPrintExposureFollowsPaper() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val a=controller.get()
            val group=MainActivity::class.java.getDeclaredField("group").apply{isAccessible=true}
            val selected=MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}
            val build=MainActivity::class.java.getDeclaredMethod("buildTools").apply{isAccessible=true}
            group.set(a,"adjust");selected.set(a,"");build.invoke(a)
            val exposure=all(a.window.decorView).filterIsInstance<Button>().single{it.tag=="filmExposure"}
            val row=exposure.parent as ViewGroup
            assertSame(exposure,row.getChildAt(0));assertEquals("brightness",row.getChildAt(1).tag)
            group.set(a,"print");selected.set(a,"");build.invoke(a)
            val print=all(a.window.decorView).filterIsInstance<Button>().single{it.tag=="printExposureEv"}
            val printRow=print.parent as ViewGroup
            assertEquals("paper",printRow.getChildAt(0).tag)
            assertSame(print,printRow.getChildAt(1))
        }
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun print2302UsesPlainPaperButtonAndKeepsProfileAcrossSessionRoundTrip() {
        try { for((w,h) in listOf(360 to 780,740 to 320)) {
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi")
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val a=controller.get()
                val model=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
                val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(model) as MutableStateFlow<EditorUiState>
                val state=dev.filmroom.model.EditorState(filmStock="kodak_doublex",paperStock="kodak_2302",outputRoute="print")
                val restored=dev.filmroom.model.EditorState.fromJson(state.json())
                assertEquals(state,restored);assertEquals("kodak_2302",restored.paperStock)
                assertEquals("kodak_doublex",restored.frameStock)
                flow.value=EditorUiState(parameters=restored)
                MainActivity::class.java.getDeclaredField("group").apply{isAccessible=true}.set(a,"print")
                MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.set(a,"paper")
                MainActivity::class.java.getDeclaredMethod("buildTools").apply{isAccessible=true}.invoke(a)
                val paper=all(a.window.decorView).filterIsInstance<Button>().single{it.text.contains("2302")}
                assertTrue(paper.compoundDrawables.all{it==null})
                assertTrue(paper.compoundDrawablesRelative.all{it==null})
            }
        }} finally { RuntimeEnvironment.setQualifiers("w360dp-h780dp-440dpi") }
    }
    @Test fun cameraProfilesHaveCorrectArtworkOrNeutralFallbackAndPrintMediaHaveNone() {
        val context=RuntimeEnvironment.getApplication()
        val profiles=JSONObject(context.assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        val ids=(0 until profiles.length()).map{profiles.getJSONObject(it)}.filter{it.getString("kind") in listOf("negative","reversal")}.map{it.getString("id")}.toSet()
        assertEquals(27,ids.size);assertEquals(ids,FilmStockArtwork.physicalStockByProfile.keys)
        assertEquals(25,FilmStockArtwork.physicalStockByProfile.values.toSet().size)
        for(id in ids){
            val bitmap=context.assets.open(FilmStockArtwork.assetPath(id)!!).use{BitmapFactory.decodeStream(it)}
            assertNotNull(id,bitmap);assertEquals(240,bitmap.width);assertEquals(240,bitmap.height);assertTrue(bitmap.hasAlpha())
            assertTrue(id,FilmStockArtwork.drawable(context,id) is BitmapDrawable)
        }
        assertEquals(FilmStockArtwork.assetPath("kodak_portra_800"),FilmStockArtwork.assetPath("kodak_portra_800_push1"))
        assertEquals(FilmStockArtwork.assetPath("kodak_portra_800"),FilmStockArtwork.assetPath("kodak_portra_800_push2"))
        assertNotEquals(FilmStockArtwork.cardLabel("kodak_portra_800_push1",""),FilmStockArtwork.cardLabel("kodak_portra_800_push2",""))
        assertNull(FilmStockArtwork.assetPath("kodak_2302"))
        assertFalse(context.assets.list("film-rolls")!!.contains("kodak_2302.webp"))
        assertNull(FilmStockArtwork.assetPath("unknown_stock"))
        assertFalse(FilmStockArtwork.drawable(context,"unknown_stock") is BitmapDrawable)
    }
    @Suppress("UNCHECKED_CAST")
    @Test fun actualActivityArtworkRendersAt440dpiWithLastSelectionAndLargeText() {
        val target=File("build/ui-host-renders/film-artwork").apply{mkdirs()}
        try { for((width,height) in listOf(360 to 780,740 to 320,480 to 320)) for(scale in listOf(1f,2f)) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h${height}dp-440dpi");RuntimeEnvironment.setFontScale(scale)
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
                if(scale>1f && width<=height){
                    all(root).filterIsInstance<Button>().single{it.text.toString()=="调整胶片型号"}.performClick()
                    content=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView
                    layout(content,w-(32*density).toInt(),h-(32*density).toInt())
                }
                fun strip()=all(content).filterIsInstance<HorizontalScrollView>().single{it.tag=="strip:films"}
                // Keep a real host-rendered 135-cartridge view as well as cinema stocks,
                // so label/leader geometry can be reviewed at actual display density.
                val cartridge=Bitmap.createBitmap(content.width,content.height,Bitmap.Config.ARGB_8888)
                content.draw(Canvas(cartridge))
                File(target,"${width}x${height}-440dpi-font${scale.toInt()}-135-cartridges.png").outputStream().use{cartridge.compress(Bitmap.CompressFormat.PNG,100,it)}
                cartridge.recycle()
                strip().isSmoothScrollingEnabled=false
                strip().scrollTo(strip().getChildAt(0).width,0)
                val cards=strip().getChildAt(0) as ViewGroup
                assertEquals(27,cards.childCount)
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
                assertTrue(chosen.compoundDrawables.any{it != null})
                assertTrue(chosen.width>=48*density);assertTrue(chosen.height>=48*density)
                assertEquals("Click does not recenter the film list ${width}x$height font$scale oldViewport=$initialWidth newViewport=${strip().width} oldContent=$initialContent newContent=${strip().getChildAt(0).width}",x,strip().scrollX)
                assertTrue(chosen.left>=strip().scrollX);assertTrue(chosen.right<=strip().scrollX+strip().width)
                for(button in all(strip()).filterIsInstance<Button>()){
                    assertNull(button.ellipsize)
                    assertTrue("Each card fits its viewport",button.width<=strip().width)
                    val textWidth=button.width-button.compoundPaddingLeft-button.compoundPaddingRight
                    // getLineWidth includes trailing break spaces; getLineMax measures the visible line.
                    for(line in 0 until button.layout.lineCount)assertTrue("Whole label fits without clipping ${width}x$height font$scale ${button.text} line=$line width=${button.layout.getLineWidth(line)} room=$textWidth layoutWidth=${button.layout.width} card=${button.width} max=${button.layout.getLineMax(line)} glyphs=[${button.text.subSequence(button.layout.getLineStart(line),button.layout.getLineEnd(line))}]",button.layout.getLineMax(line)<=textWidth+1)
                    for(line in 0 until button.layout.lineCount)assertEquals(0,button.layout.getEllipsisCount(line))
                }
                val output=Bitmap.createBitmap(content.width,content.height,Bitmap.Config.ARGB_8888);content.draw(Canvas(output))
                File(target,"${width}x${height}-440dpi-font${scale.toInt()}-last-stock.png").outputStream().use{output.compress(Bitmap.CompressFormat.PNG,100,it)}
                output.recycle()
            }
        }} finally {RuntimeEnvironment.setFontScale(1f)}
    }
}
