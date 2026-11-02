package dev.filmroom

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.model.EditorState
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class PrintExposureOrderHostTest {
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    @Test fun printExposureFollowsPaperInBothLayoutsWithoutChangingStableIdsValuesOrSelection() {
        val app=RuntimeEnvironment.getApplication();dev.filmroom.i18n.L10n.choose(app,"zh")
        try { for((w,h) in listOf(400 to 800,740 to 360)) {
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-${if(w>h)"land" else "port"}-mdpi")
            resetAndroidViewModelFactoryForHostTest();app.getSharedPreferences("local-session",0).edit().clear().commit()
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val a=controller.get();val vm=ViewModelProvider(a)[EditorViewModel::class.java]
                val operation=EditorViewModel::class.java.getDeclaredField("operationJob").apply{isAccessible=true}
                val deadline=System.nanoTime()+10_000_000_000L
                while((operation.get(vm) as? Job)?.isCompleted==false && System.nanoTime()<deadline) {
                    shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));Thread.sleep(5)
                }
                assertTrue((operation.get(vm) as? Job)?.isCompleted!=false)
                @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
                val params=EditorState(printExposureEv=1.25f,filmExposure=-.5f)
                flow.value=EditorUiState(parameters=params)
                fun set(name:String,value:String)=MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}.set(a,value)
                fun build(){MainActivity::class.java.getDeclaredMethod("buildTools").apply{isAccessible=true}.invoke(a)}
                val tools=MainActivity::class.java.getDeclaredField("tools").apply{isAccessible=true}.get(a) as ViewGroup
                fun ids():List<String> = if(w>h)(0 until tools.childCount).mapNotNull{tools.getChildAt(it).tag?.toString()?.takeIf{it.startsWith("parameter-section:")}?.removePrefix("parameter-section:")}
                    else all(tools).filterIsInstance<Button>().mapNotNull{it.tag as? String}
                set("group","print");set("selected","paper");build()
                assertEquals(listOf("paper","printExposureEv"),ids().take(2));assertEquals(1,ids().count{it=="printExposureEv"})
                assertEquals("printExposureEv",ids()[1]);assertTrue(ids().contains("outputRoute"))
                assertFalse(ids().contains("filmExposure"));assertEquals(params,vm.state.value.parameters)
                if(w<h)assertEquals("paper",MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.get(a))
                val firstSection=if(w>h)tools.getChildAt(0)else null
                build();assertEquals(listOf("paper","printExposureEv"),ids().take(2));assertEquals(params,vm.state.value.parameters)
                if(w>h)assertSame("Unchanged landscape section retains its view",firstSection,tools.getChildAt(0))
                set("group","adjust");set("selected","filmExposure");build()
                assertEquals("filmExposure",ids().first());assertEquals(params,vm.state.value.parameters)
                set("group","print");set("selected","");build()
                assertEquals(listOf("paper","printExposureEv"),ids().take(2))
                if(w<h)assertEquals("paper",MainActivity::class.java.getDeclaredField("selected").apply{isAccessible=true}.get(a))
            }
        }}finally{RuntimeEnvironment.setQualifiers("w400dp-h800dp-port-mdpi")}
    }
}
