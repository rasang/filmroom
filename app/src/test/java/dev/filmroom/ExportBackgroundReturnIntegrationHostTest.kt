package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.i18n.L10n
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
import dev.filmroom.ui.ExportBackgroundGuidance
import dev.filmroom.ui.ExportBackgroundStatus
import java.io.File
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
import org.robolectric.shadows.ShadowToast

/** Actual MainActivity callback: verified return opens options, not another prompt or an export job. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportBackgroundReturnIntegrationHostTest {
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Test fun verifiedReturnDirectlyOpensConfigurationWithParametersIntact() {
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication();L10n.choose(app,"zh")
        app.getSharedPreferences("export-notification-ui",0).edit().clear().commit()
        app.getSharedPreferences("export-background-guidance",0).edit().clear().commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity=controller.get();val model=ViewModelProvider(activity)[EditorViewModel::class.java]
            val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
            val deadline=System.nanoTime()+10_000_000_000L
            while((operation.get(model) as? Job)?.isCompleted==false && System.nanoTime()<deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));Thread.sleep(5)
            }
            assertTrue((operation.get(model) as? Job)?.isCompleted!=false)
            model.localDetailEnabled=false
            val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
            val params=EditorState()
            val ready=EditorUiState(photo=PhotoSource(File(app.cacheDir,"return-test.png"),"fixture","image/png",96,64,1),
                original=bitmap,rendered=bitmap,parameters=params,renderedParameters=params)
            @Suppress("UNCHECKED_CAST")
            val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(model) as MutableStateFlow<EditorUiState>
            flow.value=ready;assertTrue(model.state.value.canExport)
            val guidance=MainActivity::class.java.getDeclaredField("exportBackgroundGuidance").apply{isAccessible=true}.get(activity) as ExportBackgroundGuidance
            var status=ExportBackgroundStatus.UNKNOWN
            ExportBackgroundGuidance::class.java.getDeclaredField("status").apply{isAccessible=true}.set(guidance,{status})
            ExportBackgroundGuidance::class.java.getDeclaredField("openSettings").apply{isAccessible=true}.set(guidance,{true})
            guidance.offer()
            ShadowAlertDialog.getLatestAlertDialog().window!!.decorView.findViewWithTag<View>(R.string.export_background_open).performClick()
            shadowOf(Looper.getMainLooper()).idle();controller.pause()
            status=ExportBackgroundStatus.STANDARD_ALLOWED;ShadowToast.reset();controller.resume()
            val options=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(options.isShowing)
            assertTrue(all(options.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString()==L10n.text(R.string.text_cef4e28b1774) })
            assertFalse(app.getSharedPreferences("export-notification-ui",0).getBoolean("offered",false))
            assertEquals(1,ShadowToast.shownToastCount())
            assertEquals(params,model.state.value.parameters);assertFalse(model.state.value.exporting)
            guidance.onResume();assertSame(options,ShadowAlertDialog.getLatestAlertDialog());assertEquals(1,ShadowToast.shownToastCount())
            val root=options.window!!.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(288,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(608,View.MeasureSpec.AT_MOST))
            root.layout(0,0,288,root.measuredHeight)
            val capture=Bitmap.createBitmap(288,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(capture))
            val out=File("build/background-return-host-renders").apply{mkdirs()}
            File(out,"verified-return-export-options-zh.png").outputStream().use{capture.compress(Bitmap.CompressFormat.PNG,100,it)}
            capture.recycle();options.cancel();shadowOf(Looper.getMainLooper()).idle()
            guidance.onResume();assertFalse(model.state.value.exporting)
            assertEquals(params,model.state.value.parameters)
        }
    }
}
