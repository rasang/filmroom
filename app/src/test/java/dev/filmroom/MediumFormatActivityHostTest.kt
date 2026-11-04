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
import dev.filmroom.model.CropMode
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
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.time.Duration

/** Real selectors/history/composition; scientific processing is deliberately a tiny fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediumFormatActivityHostTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
    private fun await(done:()->Boolean){repeat(2000){idle();if(done())return;Thread.sleep(5)};error("Medium format Activity timeout")}
    @Test fun selectRotateCancelUndoAndReturnTo135UseSameScientificRender() {
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication()
        dev.filmroom.i18n.L10n.choose(app,"en")
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val a=controller.get()
            val vm=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
            @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            await{(operation.get(vm) as? Job)?.isCompleted==true}
            vm.localDetailEnabled=false
            val photo=MainActivity::class.java.getDeclaredField("photo").apply{isAccessible=true}.get(a) as PhotoView
            photo.disableDetail()
            vm.borderMemorySnapshot={NativeMemorySnapshot(4L*1024*1024*1024,128L*1024*1024,false)}
            BorderCatalog.initialize(app.assets){vm.borderMemorySnapshot()}
            var scienceCalls=0
            vm.renderPreview={source,_,_,_->scienceCalls++;source.copy(Bitmap.Config.ARGB_8888,false)}
            vm.detailPreviewEdge={96}
            val original=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff527682.toInt())}
            val p=EditorState()
            flow.value=EditorUiState(photo=PhotoSource(File(app.cacheDir,"medium-ui-source"),"fixture.jpg","image/jpeg",6000,4000,0),original=original,
                rendered=original.copy(Bitmap.Config.ARGB_8888,false),parameters=p,renderedParameters=p)
            val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800)
            fun tagged(tag:String)=all(root).single{it.tag==tag}
            await{all(root).any{it.tag=="medium-format-selector"}}
            assertFalse(tagged("medium-format-selector").isEnabled)
            (tagged("film-border-switch") as Switch).performClick()
            await{flow.value.canExport && flow.value.presentation!=null}
            val initialCalls=scienceCalls
            fun select(index:Int){
                tagged("medium-format-selector").performClick();idle()
                val dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing)
                val list=dialog.listView
                list.performItemClick(list.adapter.getView(index,null,list),index,index.toLong())
                await{flow.value.canExport && flow.value.parameters.effectiveMediumFormat==(if(index==0)null else MediumFormat.entries[index-1])}
                assertFalse(dialog.isShowing)
            }
            for((index,format) in MediumFormat.entries.withIndex()){
                select(index+1)
                assertEquals(format,flow.value.presentation!!.mediumFormat)
                assertFalse(tagged("negative-white-holes-switch").isEnabled)
                assertTrue((tagged("medium-format-evidence-status") as android.widget.TextView).text.contains("Reference reconstruction"))
                root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800)
                val screenshot=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888)
                try {
                    root.draw(android.graphics.Canvas(screenshot))
                    val target=File("build/ui-host-renders/medium120").apply{mkdirs()}
                    File(target,"${format.name}-activity.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
                }finally{screenshot.recycle()}
            }
            select(2) // square still has two physical edge orientations
            vm.beginComposition();assertTrue(flow.value.compositionEditing)
            vm.chooseCompositionMode(CropMode.RATIO_2_3)
            await{flow.value.presentation?.mediumPortrait==true && !flow.value.updating}
            assertEquals(1.0,flow.value.presentation!!.composition.targetAspect!!,0.0)
            vm.cancelComposition();await{flow.value.canExport && flow.value.presentation?.mediumPortrait==false}
            vm.beginComposition();vm.chooseCompositionMode(CropMode.RATIO_2_3);vm.confirmComposition()
            await{flow.value.canExport && flow.value.presentation?.mediumPortrait==true}
            vm.undo();await{flow.value.canExport && flow.value.presentation?.mediumPortrait==false}
            select(0)
            assertTrue(tagged("negative-white-holes-switch").isEnabled)
            assertEquals(initialCalls,scienceCalls)
            assertEquals(35f,flow.value.parameters.filmFormatMm,0f)
            // Canceling the chooser leaves both format and export-ready state untouched.
            tagged("medium-format-selector").performClick();idle()
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            assertNull(flow.value.parameters.effectiveMediumFormat)
            assertTrue(flow.value.canExport)
            tagged("medium-format-selector").performClick();idle()
            val stale=ShadowAlertDialog.getLatestAlertDialog()
            flow.value=flow.value.copy(photo=PhotoSource(File(app.cacheDir,"replacement-source"),"fixture2.jpg","image/jpeg",6000,4000,0))
            val staleList=stale.listView
            staleList.performItemClick(staleList.adapter.getView(1,null,staleList),1,1L)
            assertNull(flow.value.parameters.effectiveMediumFormat)
            assertFalse(stale.isShowing)
        }
    }
}
