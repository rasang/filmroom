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
import java.io.File
import java.time.Duration

/** Real composition controls/history; scientific processing is deliberately a tiny fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediumFormatActivityHostTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
    private fun await(done:()->Boolean){repeat(2000){idle();if(done())return;Thread.sleep(5)};error("Medium format Activity timeout")}
    @Test fun formatsLiveInCompositionAndCancelConfirmUndoKeepOneDraft() {
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication()
        dev.filmroom.i18n.L10n.choose(app,"en")
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        try { Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
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
            fun layout(){root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,400,800)}
            fun tagged(tag:String)=all(root).single{it.tag==tag}
            fun formatTag(format:MediumFormat?)="composition-format:${format?.name ?: "135"}"
            fun noMisplacedControls(){
                assertFalse(all(root).any{it.tag=="medium-format-selector" || (it.tag as? String)?.startsWith("medium-format-evidence")==true})
                assertFalse(all(root).filterIsInstance<android.widget.TextView>().any{it.text.contains("120 reference") || it.text.contains("Reference reconstruction")})
            }
            fun enter(){tagged("composition").performClick();await{flow.value.compositionEditing && all(root).any{it.tag==formatTag(null)}};layout()}
            fun select(format:MediumFormat?){
                tagged(formatTag(format)).performClick()
                await{!flow.value.updating && flow.value.renderedParameters==flow.value.parameters && flow.value.presentation?.mediumFormat==format}
                assertEquals(format,flow.value.parameters.effectiveMediumFormat)
                assertTrue(flow.value.compositionEditing);assertFalse(flow.value.canExport)
                assertTrue(tagged(formatTag(format)).isSelected);noMisplacedControls();layout()
                assertEquals("Landscape",(tagged("crop-mode:RATIO_3_2") as android.widget.TextView).text.toString())
                assertEquals("Portrait",(tagged("crop-mode:RATIO_2_3") as android.widget.TextView).text.toString())
                val strip=tagged("strip:composition-formats") as android.widget.HorizontalScrollView
                val chosen=tagged(formatTag(format))
                assertTrue("Selected format remains visible after rebuild",chosen.left>=strip.scrollX && chosen.right<=strip.scrollX+strip.width)
            }
            await{all(root).any{it.tag=="film-border-switch"}};layout();noMisplacedControls()
            assertFalse(all(root).any{(it.tag as? String)?.startsWith("composition-format:")==true})
            (tagged("film-border-switch") as Switch).performClick()
            await{flow.value.canExport && flow.value.presentation!=null}
            assertFalse("Film cards must not contain frame controls",all(root).any{(it.tag as? String)?.startsWith("composition-format:")==true})
            val baseline=flow.value.parameters;val initialCalls=scienceCalls
            vm.chooseCompositionFormat(MediumFormat.MF617);assertEquals(baseline,flow.value.parameters)
            enter()
            assertEquals(6,all(root).count{(it.tag as? String)?.startsWith("composition-format:")==true})
            tagged(formatTag(null)).performClick();assertEquals("Reselecting 135 is a no-op",baseline,flow.value.parameters)
            for(format in MediumFormat.entries){
                select(format)
                for(portrait in listOf(true,false)){
                    tagged("crop-mode:${if(portrait)CropMode.RATIO_2_3 else CropMode.RATIO_3_2}").performClick()
                    await{!flow.value.updating && flow.value.presentation?.mediumPortrait==portrait}
                    assertEquals(format,flow.value.presentation!!.mediumFormat)
                    assertEquals(format.geometry(portrait).aspect,flow.value.presentation!!.composition.targetAspect!!,0.0)
                }
                layout()
                val screenshot=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888)
                try {root.draw(android.graphics.Canvas(screenshot));val target=File("build/ui-host-renders/medium120-composition").apply{mkdirs()}
                    File(target,"${format.name}-activity.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
                }finally{screenshot.recycle()}
            }
            val beforeGesture=flow.value.parameters
            photo.pinch(1.5,photo.width/2.0,photo.height/2.0);photo.pan(7.0,3.0);photo.finishCompositionGesture()
            await{!flow.value.updating && flow.value.renderedParameters==flow.value.parameters}
            assertNotEquals("Format and pan/zoom share the same draft",beforeGesture.composition,flow.value.parameters.composition)
            val retainedFormatButton=tagged(formatTag(MediumFormat.MF617))
            tagged("crop-cancel").performClick();await{flow.value.canExport}
            assertEquals("Cancel includes the format selection",baseline,flow.value.parameters)
            retainedFormatButton.performClick();assertEquals("Old detached control cannot edit outside a draft",baseline,flow.value.parameters)
            enter();select(MediumFormat.MF66)
            tagged("crop-mode:RATIO_2_3").performClick();await{!flow.value.updating && flow.value.parameters.effectiveMediumPortrait}
            select(MediumFormat.MF67);assertTrue(flow.value.parameters.effectiveMediumPortrait)
            select(MediumFormat.MF66);assertTrue("Square has explicit orientation",flow.value.parameters.effectiveMediumPortrait)
            val beforeConfirmedGesture=flow.value.parameters.composition
            photo.pinch(1.3,photo.width/2.0,photo.height/2.0);photo.pan(4.0,-2.0);photo.finishCompositionGesture()
            await{!flow.value.updating && flow.value.renderedParameters==flow.value.parameters}
            assertNotEquals(beforeConfirmedGesture,flow.value.parameters.composition)
            val committed=flow.value.parameters
            tagged("crop-confirm").performClick();await{flow.value.canExport}
            assertEquals(committed,flow.value.parameters)
            assertFalse((tagged("negative-white-holes-switch") as Switch).isEnabled)
            noMisplacedControls()
            vm.undo();await{flow.value.canExport};assertEquals("One undo restores the entire draft",baseline,flow.value.parameters)
            vm.redo();await{flow.value.canExport};assertEquals(committed,flow.value.parameters)
            enter();select(null)
            assertEquals("Returning to 135 keeps portrait",2.0/3.0,flow.value.parameters.composition.targetAspect!!,0.0)
            tagged("crop-confirm").performClick();await{flow.value.canExport}
            assertTrue(tagged("negative-white-holes-switch").isEnabled)
            assertEquals(initialCalls,scienceCalls);assertEquals(35f,flow.value.parameters.filmFormatMm,0f)
            // Border OFF keeps all existing plain crop modes and never shows 120 choices.
            (tagged("film-border-switch") as Switch).performClick();await{flow.value.canExport}
            tagged("composition").performClick();await{flow.value.compositionEditing};idle()
            assertEquals(9,all(root).count{(it.tag as? String)?.startsWith("crop-mode:")==true})
            assertFalse(all(root).any{(it.tag as? String)?.startsWith("composition-format:")==true})
            val unframed=flow.value.parameters
            vm.chooseCompositionFormat(MediumFormat.MF617);assertEquals(unframed,flow.value.parameters)
            a.onBackPressedDispatcher.onBackPressed();await{flow.value.canExport};assertEquals(unframed,flow.value.parameters)
            noMisplacedControls()
            (tagged("film-border-switch") as Switch).performClick();await{flow.value.canExport}
            val rapidBaseline=flow.value.parameters
            enter()
            // Do not wait for rendering between rapid taps and Back: stale drafts must not publish later.
            tagged(formatTag(MediumFormat.MF617)).performClick()
            tagged(formatTag(MediumFormat.MF645)).performClick()
            tagged(formatTag(MediumFormat.MF66)).performClick()
            assertEquals(MediumFormat.MF66,flow.value.parameters.effectiveMediumFormat)
            a.onBackPressedDispatcher.onBackPressed()
            val renderJob=EditorViewModel::class.java.getDeclaredField("renderJob").apply{isAccessible=true}
            await{(renderJob.get(vm) as? Job)?.isActive!=true && flow.value.canExport && flow.value.renderedParameters==rapidBaseline}
            assertEquals(rapidBaseline,flow.value.parameters)
        }} finally {BorderCatalog.clearCache();BorderCatalog.initialize(app.assets)}
    }
}
