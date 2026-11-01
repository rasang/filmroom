package dev.filmroom

import android.os.Looper
import dev.filmroom.model.EditorState
import dev.filmroom.engine.ExportDisplayProgress
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class EditorRotationHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){resetAndroidViewModelFactoryForHostTest();dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")} 
    private fun model(a:MainActivity)=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}.invoke(a) as EditorViewModel
    @Suppress("UNCHECKED_CAST")
    @Test fun recreationRetainsExportParametersAndSelectedTool(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val before=c.get();val vm=model(before)
            val mutable=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
            val parameters=EditorState(grainRedScale=2f,halationColor="amber")
            mutable.value=EditorUiState(parameters=parameters,exporting=true,busy="正在生成颗粒 · 可取消",exportProgress=ExportDisplayProgress(5,65,100))
            val group=MainActivity::class.java.getDeclaredField("group").apply{isAccessible=true};group.set(before,"grain")
            val selected=MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true};selected.set(before,"grainRedScale")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
            org.robolectric.RuntimeEnvironment.setQualifiers("w780dp-h360dp-land-mdpi")
            c.recreate();shadowOf(Looper.getMainLooper()).idle()
            val after=c.get()
            assertSame(vm,model(after));assertEquals(parameters,model(after).state.value.parameters)
            assertEquals(ExportDisplayProgress(5,65,100),model(after).state.value.exportProgress)
            assertTrue(model(after).state.value.exporting);assertFalse(model(after).state.value.cancelling)
            assertEquals("grain",group.get(after));assertEquals("grainRedScale",selected.get(after))
            val progress=MainActivity::class.java.getDeclaredField("exportProgressText").apply{isAccessible=true}.get(after) as android.widget.TextView
            assertEquals("生成颗粒\n第 5/7 步 · 65%",progress.text.toString())
            assertTrue(progress.isShown)
            assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        }
        org.robolectric.RuntimeEnvironment.setQualifiers("w360dp-h780dp-port-mdpi")
    }
}
