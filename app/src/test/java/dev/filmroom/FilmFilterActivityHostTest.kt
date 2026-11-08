package dev.filmroom
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import dev.filmroom.i18n.L10n
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.ui.PhotoView
import dev.filmroom.ui.FilmCatalogFilter as F
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPopupMenu
import java.io.File
import java.time.Duration
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w360dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilmFilterActivityHostTest {
 private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
 private fun await(done:()->Boolean){repeat(2000){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(done())return;Thread.sleep(5)};error("filter UI timeout")}
 @Test fun filterDoesNotChangeStockTabsRemainAndLocalesFit() {
  resetAndroidViewModelFactoryForHostTest()
  val app=RuntimeEnvironment.getApplication()
  try {for(lang in L10n.supported) {
   L10n.choose(app,lang);app.getSharedPreferences("local-session",0).edit().clear().commit()
   Robolectric.buildActivity(MainActivity::class.java).setup().use{host->
    val a=host.get()
    val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
    @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
    val op=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
    await{(op.get(vm) as? Job)?.isCompleted==true};vm.localDetailEnabled=false
    (MainActivity::class.java.getDeclaredField("photo").apply{isAccessible=true}.get(a) as PhotoView).disableDetail()
    vm.renderPreview={source,_,_,_->source.copy(Bitmap.Config.ARGB_8888,false)};vm.detailPreviewEdge={96}
    val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff62746c.toInt())}
    val p=EditorState(filmStock="kodak_portra_400")
    flow.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"filter-$lang"),"fixture.jpg","image/jpeg",6000,4000,0),original=bitmap,rendered=bitmap.copy(Bitmap.Config.ARGB_8888,false),parameters=p,renderedParameters=p)
    val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    fun tagged(tag:String)=all(root).single{it.tag==tag}
    await{all(root).any{it.tag=="filmStock"}}
    tagged("filmStock").performClick();await{all(root).any{it.tag=="film-selector"}}
    fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800)}
    layout()
    assertTrue(all(root).any{it.tag=="filmStock"})
    val selector=tagged("film-selector") as ViewGroup
    assertEquals("film-filter-scroll",selector.getChildAt(0).tag)
    assertTrue(selector.getChildAt(0) is HorizontalScrollView)
    val expectedHeight=selector.height
    val expectedStripHeight=selector.getChildAt(1).height
    assertEquals(8,(selector.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin)
    fun assertStable(state:String){
     layout()
     assertEquals("$lang/$state selector height",expectedHeight,selector.height)
     assertEquals("$lang/$state strip height",expectedStripHeight,selector.getChildAt(1).height)
     System.getProperty("filmroom.filterReviewDir")?.let{folder->
      if(lang=="zh") {File(folder).mkdirs();val image=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(image));File(folder,"stable-$state.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
     }
    }
    assertStable("all")
    assertTrue(selector.getChildAt(1).top-selector.getChildAt(0).bottom>=4)
    val card=tagged("kodak_portra_400") as Button
    println("ART density=${a.resources.displayMetrics.density} bounds=${card.compoundDrawables[1].bounds} class=${card.compoundDrawables[1].javaClass}")
    assertEquals((56*a.resources.displayMetrics.density).toInt(),card.compoundDrawables[1].bounds.height())
    assertTrue(card.isSelected)
    val none=tagged("none") as Button
    assertEquals(card.minLines,none.minLines)
    assertEquals(card.top,none.top)
    assertTrue("Font-metric rounding differs by at most one pixel",kotlin.math.abs(card.height-none.height)<=1)
    for(key in listOf("COLOR","BW","REVERSAL","MOVIE")) {
     tagged("film-filter:$key").performClick();assertStable(key)
     assertEquals(p,flow.value.parameters)
     val row=(selector.getChildAt(1) as HorizontalScrollView).getChildAt(0) as ViewGroup
     for(i in 0 until row.childCount) {val id=row.getChildAt(i).tag as? String ?: continue
      if(key=="BW")assertTrue(id in setOf("kodak_doublex","kodak_trix","kodak_tri_x_400","kodak_tmax_100","ilford_hp5_plus_400","fujifilm_neopan_acros_100_ii"))
     }
    }
    tagged("film-filter:ALL").performClick()
    tagged("film-filter:brand").performClick()
    val popup=ShadowPopupMenu.getLatestPopupMenu()
    assertNotNull(popup);assertEquals(5,popup.menu.size())
    if(lang=="zh"){
     shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
     val window=shadowOf(app).latestPopupWindow
     window?.contentView?.rootView?.let{v->
      System.getProperty("filmroom.filterReviewDir")?.let{folder->if(v.width>0 && v.height>0){
       File(folder).mkdirs();val image=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(image))
       File(folder,"brand-menu.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
      }}
     }
    }
    popup.menu.performIdentifierAction(2,0) // Fuji
    assertStable("fuji")
    assertEquals(p,flow.value.parameters)
    assertTrue((tagged("film-filter:brand") as Button).text.contains(F.label("fujifilm",lang)))
    tagged("film-filter:MOVIE").performClick();assertStable("empty")
    assertTrue(all(selector).filterIsInstance<android.widget.TextView>().any{it.text.toString()==F.label("empty",lang)})
    tagged("film-filter:ALL").performClick();layout()
    assertTrue((tagged("kodak_portra_400") as Button).isSelected)
    val output=System.getProperty("filmroom.filterReviewDir")
    if(output!=null){File(output).mkdirs();val bmp=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bmp));File(output,"filter-$lang.png").outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}
    tagged("film-filter:BW").performClick()
    tagged("ilford_hp5_plus_400").performClick()
    await{flow.value.parameters.filmStock=="ilford_hp5_plus_400" && flow.value.canExport && all(root).filterIsInstance<Button>().any{it.tag=="ilford_hp5_plus_400" && it.isSelected}}
    assertTrue((tagged("ilford_hp5_plus_400") as Button).isSelected)
    assertEquals(p.brightness,flow.value.parameters.brightness)
    val saved=Bundle();host.saveInstanceState(saved)
    assertNull(saved.getString("filmFilterBrand"));assertEquals("BW",saved.getString("filmFilterCategory"))
    vm.cancel()
   }
  }} finally {L10n.choose(app,"zh")}
 }
}
