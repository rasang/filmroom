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
@Config(sdk=[35],application=Application::class,qualifiers="w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrintMediumActivityHostTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun await(done:()->Boolean){repeat(2000){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(done())return;Thread.sleep(5)};error("paper UI timeout")}
    @Test fun allLocalesKeepButtonDialogHintAndSelectedIdsConsistent() {
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
                fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800)}
                fun tagged(tag:String)=all(root).single{it.tag==tag}
                val original=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff527682.toInt())}
                val p=EditorState(filmStock="kodak_portra_400",paperStock="kodak_portra_endura")
                flow.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"paper-$language"),"fixture.jpg","image/jpeg",6000,4000,0),original=original,rendered=original.copy(Bitmap.Config.ARGB_8888,false),parameters=p,renderedParameters=p)
                await{all(root).any{it.tag=="print"}}
                tagged("print").performClick()
                await{all(root).any{it.tag=="paper-stock"}}
                for(id in listOf("ilford_multigrade_iv_rc","kodak_portra_endura","kodak_2302","kodak_2383")) {
                    val button=tagged("paper-stock") as Button
                    assertTrue(button.isEnabled);button.performClick()
                    val dialog=ShadowAlertDialog.getLatestAlertDialog()
                    val adapter=dialog.listView.adapter
                    assertEquals(papers.size,adapter.count)
                    for((index,row) in papers.withIndex()) {
                        val expected=PrintMediumLabels.label(row.getString("id"),row.getString("name"),row.getString("kind"))
                        val view=adapter.getView(index,null,dialog.listView)
                        assertTrue("$language/$index",all(view).filterIsInstance<TextView>().any{it.text.toString()==expected})
                    }
                    val index=papers.indexOfFirst{it.getString("id")==id}
                    dialog.listView.performItemClick(adapter.getView(index,null,dialog.listView),index,adapter.getItemId(index))
                    await{flow.value.canExport && flow.value.parameters.paperStock==id && renderedPaper==id}
                    val row=papers[index]
                    assertEquals(PrintMediumLabels.label(id,row.getString("name"),row.getString("kind")),(tagged("paper-stock") as Button).text.toString())
                    assertEquals("kodak_portra_400",flow.value.parameters.filmStock)
                    val expectedHint=flow.value.parameters.isMonochromePrint
                    assertEquals(expectedHint,all(root).filterIsInstance<TextView>().any{it.text.toString()==a.getString(R.string.print_medium_bw_help)})
                    layout()
                    if(id=="ilford_multigrade_iv_rc")System.getProperty("filmroom.paperReviewDir")?.let{dir->
                        File(dir).mkdirs();val screenshot=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888);root.draw(Canvas(screenshot));File(dir,"paper-$language.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
                    }
                    println("PAPER UI PASS language=$language paper=$id film=kodak_portra_400 hint=$expectedHint")
                }
            }
        }} finally { app.getSharedPreferences("local-session",0).edit().clear().commit();L10n.choose(app,"zh") }
    }
}
