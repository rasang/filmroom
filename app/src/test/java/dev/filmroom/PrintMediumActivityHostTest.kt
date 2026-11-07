package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.i18n.L10n
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.ui.PhotoView
import dev.filmroom.ui.PrintMediumLabels
import kotlinx.coroutines.Job
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
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration

/** Real controls and exact selected IDs; fake pixels isolate UI from native rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w320dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrintMediumActivityHostTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun await(done:()->Boolean){repeat(2000){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(done())return;Thread.sleep(5)};error("paper UI timeout")}
    @Test fun allLocalesKeepCarouselHintAndSelectedIdsConsistent() {
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication()
        val catalog=JSONObject(app.assets.open("engine-data/profile_catalog.json").bufferedReader().use{it.readText()}).getJSONArray("profiles")
        val papers=(0 until catalog.length()).map{catalog.getJSONObject(it)}.filter{it.getString("kind") in listOf("paper","cine_print")}
        try { for(language in L10n.supported) {
            L10n.choose(app,language)
            app.getSharedPreferences("local-session",0).edit().clear().commit()
            Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
                val a=controller.get()
                val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
                @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
                val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
                await{(operation.get(vm) as? Job)?.isCompleted==true}
                vm.localDetailEnabled=false
                (MainActivity::class.java.getDeclaredField("photo").apply{isAccessible=true}.get(a) as PhotoView).disableDetail()
                vm.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
                var renderedPaper=""
                vm.renderPreview={source,parameters,_,_->renderedPaper=parameters.paperStock;source.copy(Bitmap.Config.ARGB_8888,false)}
                vm.detailPreviewEdge={96}
                val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,320,800)}
                fun tagged(tag:String)=all(root).single{it.tag==tag}
                val original=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff527682.toInt())}
                val p=EditorState(filmStock="kodak_portra_400",paperStock="kodak_portra_endura")
                flow.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"paper-$language"),"fixture.jpg","image/jpeg",6000,4000,0),original=original,rendered=original.copy(Bitmap.Config.ARGB_8888,false),parameters=p,renderedParameters=p)
                await{all(root).any{it.tag=="print"}}
                tagged("print").performClick()
                await{all(root).any{it.tag=="strip:papers"}}
                val paperStrip=tagged("strip:papers")
                val paperPanel=paperStrip.parent as ViewGroup
                assertSame("Paper cards follow the tool tabs without a duplicate heading row",paperStrip,paperPanel.getChildAt(1))
                for(id in papers.map{it.getString("id")}) {
                    val button=tagged(id) as Button
                    assertTrue(button.isEnabled)
                    val before=ShadowAlertDialog.getLatestAlertDialog()
                    button.performClick()
                    await{flow.value.canExport && flow.value.parameters.paperStock==id && renderedPaper==id}
                    assertSame(before,ShadowAlertDialog.getLatestAlertDialog())
                    val row=papers.first{it.getString("id")==id}
                    assertTrue((tagged(id) as Button).isSelected)
                    assertTrue((tagged(id) as Button).contentDescription.toString().contains(PrintMediumLabels.label(id,row.getString("name"),row.getString("kind"))))
                    for(item in papers){
                        val card=tagged(item.getString("id")) as Button
                        assertNotNull(card.compoundDrawables[1])
                    }
                    assertEquals("kodak_portra_400",flow.value.parameters.filmStock)
                    val expectedHint=flow.value.parameters.isMonochromePrint
                    assertEquals(expectedHint,all(root).filterIsInstance<TextView>().any{it.text.toString()==a.getString(R.string.print_medium_bw_help)})
                    layout()
                    for(item in papers){
                        val card=tagged(item.getString("id")) as Button
                        val expected=PrintMediumLabels.cardLabel(item.getString("id"),item.getString("name"),item.getString("kind"))
                        assertEquals(expected,card.text.toString())
                        assertEquals("No accidental line wraps: $language/${item.getString("id")}",expected.lines().size,card.layout.lineCount)
                        val room=card.width-card.compoundPaddingLeft-card.compoundPaddingRight
                        for(line in 0 until card.layout.lineCount){
                            assertEquals(0,card.layout.getEllipsisCount(line))
                            assertTrue(card.layout.getLineMax(line)<=room+1f)
                        }
                    }
                    if(id in listOf("fujifilm_crystal_archive_typeii","kodak_2393","ilford_multigrade_iv_rc"))System.getProperty("filmroom.paperReviewDir")?.let{dir->
                        File(dir).mkdirs();val screenshot=Bitmap.createBitmap(320,800,Bitmap.Config.ARGB_8888);root.draw(Canvas(screenshot));File(dir,if(id=="ilford_multigrade_iv_rc")"paper-$language.png" else "paper-$language-$id.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
                    }
                    println("PAPER UI PASS language=$language paper=$id film=kodak_portra_400 hint=$expectedHint")
                }
                // Exercise actual none cards, not only model serialization.
                (tagged("none") as Button).also{assertTrue(it.isEnabled);it.performClick()}
                await{flow.value.canExport && flow.value.parameters.paperStock=="none"}
                assertEquals("kodak_portra_400",flow.value.parameters.filmStock)
                fun chooseFilm(id:String) {
                    val target=android.widget.LinearLayout(a)
                    MainActivity::class.java.getDeclaredMethod("stockChoices",EditorState::class.java,EditorUiState::class.java,android.widget.LinearLayout::class.java).apply{isAccessible=true}.invoke(a,flow.value.parameters,flow.value,target)
                    val card=all(target).filterIsInstance<Button>().single{it.tag==id}
                    assertTrue(card.isEnabled);assertNotNull(card.compoundDrawables[1]);card.performClick()
                    await{flow.value.canExport && flow.value.parameters.filmStock==id}
                }
                chooseFilm("none")
                assertEquals("none",flow.value.parameters.paperStock)
                (tagged("kodak_portra_endura") as Button).performClick()
                await{flow.value.canExport && flow.value.parameters.paperStock=="kodak_portra_endura"}
                assertEquals("none",flow.value.parameters.filmStock)
                chooseFilm("kodak_portra_400")
                assertEquals("kodak_portra_endura",flow.value.parameters.paperStock)
                val selectedId=flow.value.parameters.paperStock
                val strip=tagged("strip:papers") as android.widget.HorizontalScrollView
                strip.scrollBy(-24,0)
                val scrollX=strip.scrollX
                assertEquals(selectedId,flow.value.parameters.paperStock)
                (tagged(selectedId) as Button).performClick()
                layout()
                assertEquals(scrollX,(tagged("strip:papers") as android.widget.HorizontalScrollView).scrollX)
                val direct=flow.value.parameters.copy(outputRoute="direct")
                flow.value=flow.value.copy(parameters=direct,renderedParameters=direct)
                await{!(tagged(selectedId) as Button).isEnabled}
                assertEquals(selectedId,flow.value.parameters.paperStock)
                for(item in papers)assertFalse((tagged(item.getString("id")) as Button).isEnabled)
            }
        }} finally { app.getSharedPreferences("local-session",0).edit().clear().commit();L10n.choose(app,"zh") }
    }
}
