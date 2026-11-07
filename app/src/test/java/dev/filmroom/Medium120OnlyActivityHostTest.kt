package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import dev.filmroom.border.BorderCatalog
import dev.filmroom.border.MediumFormat
import dev.filmroom.engine.NativeMemorySnapshot
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.ui.PhotoView
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
import java.io.File
import java.time.Duration

/** Previously 120-only saved choices stay on 120 after reviewed 135 artwork is added.
 * Science is a tiny fake; this does not validate the native film response. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Medium120OnlyActivityHostTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun await(done:()->Boolean){repeat(2000){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10));if(done())return;Thread.sleep(5)};error("120-only activity timeout")}
    @Test fun previous120SelectionsStayOn120AndNowOfferTheirOwnReviewed135(){
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication()
        dev.filmroom.i18n.L10n.choose(app,"en")
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        try{Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val a=controller.get()
            val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
            @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            vm.localDetailEnabled=false
            (MainActivity::class.java.getDeclaredField("photo").apply{isAccessible=true}.get(a) as PhotoView).disableDetail()
            vm.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
            BorderCatalog.initialize(app.assets){vm.borderMemorySnapshot()}
            vm.renderPreview={source,_,_,_->source.copy(Bitmap.Config.ARGB_8888,false)}
            vm.detailPreviewEdge={96}
            val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800)}
            fun tagged(tag:String)=all(root).single{it.tag==tag}
            for(stock in listOf("ilford_hp5_plus_400","kodak_tmax_100","kodak_tri_x_400","cinestill_800t","fujifilm_neopan_acros_100_ii")){
                val original=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff527682.toInt())}
                val p=EditorState(filmStock=stock,paperStock="ilford_multigrade_iv_rc",mediumFormat=MediumFormat.MF66)
                flow.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"120-only-$stock"),"fixture.jpg","image/jpeg",6000,4000,0),original=original,
                    rendered=original.copy(Bitmap.Config.ARGB_8888,false),parameters=p,renderedParameters=p)
                await{all(root).any{it.tag=="film-border-switch"}};layout()
                val toggle=tagged("film-border-switch") as Switch
                assertTrue("$stock must be enterable without borrowed 135",toggle.isEnabled)
                assertFalse(toggle.isChecked);toggle.performClick()
                await{flow.value.canExport && flow.value.presentation?.stock==stock}
                assertTrue(flow.value.parameters.filmBorder)
                assertEquals(MediumFormat.MF66,flow.value.parameters.effectiveMediumFormat)
                assertEquals(MediumFormat.MF66,flow.value.presentation!!.mediumFormat)
                tagged("composition").performClick()
                await{flow.value.compositionEditing && all(root).any{it.tag=="composition-format:MF66"}};layout()
                assertEquals(6,all(root).count{(it.tag as? String)?.startsWith("composition-format:")==true})
                assertTrue(all(root).any{it.tag=="composition-format:135"})
                tagged("composition-format:MF617").performClick()
                await{!flow.value.updating && flow.value.presentation?.mediumFormat==MediumFormat.MF617}
                tagged("crop-confirm").performClick();await{flow.value.canExport}
                assertEquals(MediumFormat.MF617,flow.value.parameters.effectiveMediumFormat)
                assertFalse((tagged("negative-white-holes-switch") as Switch).isEnabled)
                // Turning the frame off and on preserves this valid format, not a missing 135.
                (tagged("film-border-switch") as Switch).performClick();await{flow.value.canExport && !flow.value.parameters.filmBorder}
                (tagged("film-border-switch") as Switch).performClick();await{flow.value.canExport && flow.value.parameters.filmBorder}
                assertEquals(MediumFormat.MF617,flow.value.presentation!!.mediumFormat)
                println("120-ONLY UI PASS stock=$stock enabled=MF66 selected=MF617 own135=true")
            }
        }}finally{app.getSharedPreferences("local-session",0).edit().clear().commit()}
    }
}
