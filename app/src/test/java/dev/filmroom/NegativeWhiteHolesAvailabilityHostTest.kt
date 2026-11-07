package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.border.*
import dev.filmroom.i18n.L10n
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.EditorState
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NegativeWhiteHolesAvailabilityHostTest {
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    @Test fun capabilityMatchesActualRendererMatteSelectionForEveryProfileAndRoute() {
        val supported135=FrameProfileCatalog.kinds.filterKeys{BorderCatalog.available(it,null)}
        assertEquals(27,supported135.size)
        assertEquals(CandidateBorderDefinitions.profiles.keys+setOf("kodak_doublex","kodak_trix")+Reviewed135Definitions.stocks,supported135.keys)
        assertEquals(FrameProfileCatalog.kinds.keys,supported135.keys)
        for((stock,kind) in supported135) {
            for(route in if(kind==FrameAppearanceContext.FilmKind.REVERSAL)listOf("direct")else listOf("print","direct")) {
                for(paper in listOf(EditorState().paperStock,"kodak_2302")) {
                    val state=EditorState(filmStock=stock,paperStock=paper,outputRoute=route)
                    val effective=state.frameStock
                    val contexts=listOf(false,true).map{FrameAppearanceContext.forProfile(effective,route,it)}
                    val mattes=contexts.map { c ->
                        when {
                            effective in Reviewed135Definitions.stocks -> Reviewed135Definitions.selected(effective,c).spec.holeMatteSrgb
                            effective in BwBorderDefinitions.profiles -> BwBorderDefinitions.selected(effective,c).spec.holeMatteSrgb
                            c.material==FrameAppearanceContext.Material.NEGATIVE_TRANSMISSION -> DirectBorderDefinitions.selectedDefinition(effective,c).spec.holeMatteSrgb
                            else -> CandidateBorderDefinitions.selectedDefinition(effective,c.negativeWhiteHoles).spec.holeMatteSrgb
                        }
                    }
                    assertEquals("$stock/$route/$paper effective=$effective",mattes[0]!=mattes[1],state.negativeWhiteHolesSupported)
                    assertEquals(contexts.map{it.holeMatteSrgb},mattes)
                }
            }
        }
        assertFalse(EditorState(filmStock="unknown").negativeWhiteHolesSupported)
        assertFalse(EditorState(outputRoute="unsupported").negativeWhiteHolesSupported)
    }
    @Test fun stockRouteResetAndSessionRoundTripKeepStoredChoice() {
        for(white in listOf(false,true))for(route in listOf("print","direct")) {
            var p=EditorState(negativeWhiteHoles=white,filmBorder=true).selectOutputRoute(route)
            for(stock in FrameProfileCatalog.kinds.filterValues{it==FrameAppearanceContext.FilmKind.REVERSAL}.keys) {
                p=p.selectFilm(stock,p.paperStock,"direct",p.paperStock)
                assertFalse(p.negativeWhiteHolesSupported);assertEquals(white,p.negativeWhiteHoles)
                p=EditorState.fromJson(p.json());assertFalse(p.negativeWhiteHolesSupported);assertEquals(white,p.negativeWhiteHoles)
                val reset=p.processingDefaults(p.paperStock,"direct")
                assertEquals(white,reset.negativeWhiteHoles);assertFalse(reset.negativeWhiteHolesSupported)
                p=p.selectFilm("kodak_portra_400",p.paperStock,"print",p.paperStock)
                assertEquals(route,p.outputRoute);assertTrue(p.negativeWhiteHolesSupported);assertEquals(white,p.negativeWhiteHoles)
                assertEquals(white,p.processingDefaults(p.paperStock,"print").negativeWhiteHoles)
            }
        }
    }
    @Test fun bothLayoutsImmediatelyGrayEveryReversalAndRestoreNegativeChoice() {
        val app=RuntimeEnvironment.getApplication();L10n.choose(app,"zh")
        val stocks=FrameProfileCatalog.kinds.filterValues{it==FrameAppearanceContext.FilmKind.REVERSAL}.keys+"kodak_portra_400"
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
                assertTrue((operation.get(vm) as? Job)?.isCompleted!=false);vm.localDetailEnabled=false
                @Suppress("UNCHECKED_CAST") val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as MutableStateFlow<EditorUiState>
                for((field,value) in listOf("group" to "film","selected" to "filmStock"))MainActivity::class.java.getDeclaredField(field).apply{isAccessible=true}.set(a,value)
                val tools=MainActivity::class.java.getDeclaredField("tools").apply{isAccessible=true}.get(a) as ViewGroup
                val bitmap=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
                val photo=PhotoSource(File(app.cacheDir,"holes-ui.png"),"fixture","image/png",96,64,1)
                fun bind(p:EditorState,editable:Boolean=true):Switch {
                    val state=EditorUiState(photo=photo,original=bitmap,rendered=bitmap,parameters=p,renderedParameters=p,busy=if(editable)null else "busy")
                    flow.value=state
                    MainActivity::class.java.getDeclaredMethod("bind",EditorUiState::class.java).apply{isAccessible=true}.invoke(a,state)
                    return all(tools).filterIsInstance<Switch>().single{it.tag=="negative-white-holes-switch"}
                }
                for(stock in stocks)for(route in if(FrameProfileCatalog.kind(stock)==FrameAppearanceContext.FilmKind.REVERSAL)listOf("direct")else listOf("print","direct"))
                    for(border in listOf(false,true))for(white in listOf(false,true)) {
                        val p=EditorState(filmStock=stock,outputRoute=route,filmBorder=border,negativeWhiteHoles=white)
                        val toggle=bind(p);val expected=border && p.negativeWhiteHolesSupported
                        assertEquals("$w/$stock/$route/$border",expected,toggle.isEnabled)
                        assertEquals(if(expected)1f else .38f,toggle.alpha,0f)
                        assertEquals(white,toggle.isChecked);assertEquals("负片白孔",toggle.text.toString())
                        val node=toggle.createAccessibilityNodeInfo();assertEquals(expected,node.isEnabled);node.recycle()
                        if(!expected){toggle.isChecked=!white;assertEquals(p,vm.state.value.parameters)}
                        val restored=bind(p.copy(filmStock="kodak_portra_400",outputRoute=route,filmBorder=true))
                        assertTrue(restored.isEnabled);assertEquals(white,restored.isChecked)
                }
                assertFalse(bind(EditorState(filmBorder=true),editable=false).isEnabled)
                for(route in listOf("print","direct")) {
                    val p=EditorState(filmStock="kodak_portra_400",outputRoute=route,filmBorder=true,negativeWhiteHoles=false)
                    val active=bind(p)
                    vm.renderPreview={b,_,_,_->b.copy(Bitmap.Config.ARGB_8888,false)}
                    active.performClick()
                    assertTrue("Enabled $route click updates preference",vm.state.value.parameters.negativeWhiteHoles)
                    assertEquals(p.copy(negativeWhiteHoles=true),vm.state.value.parameters)
                    vm.cancel()
                }
                for(stock in listOf("kodak_portra_400","kodak_ektachrome_100")) {
                    bind(EditorState(filmStock=stock,outputRoute="direct",filmBorder=true,negativeWhiteHoles=true))
                    val row=all(tools).single{it.tag=="border-options-scroll"}
                    row.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.AT_MOST));row.layout(0,0,w,row.measuredHeight)
                    val image=Bitmap.createBitmap(w,row.height,Bitmap.Config.ARGB_8888);row.draw(Canvas(image))
                    val out=File("build/white-hole-availability-host-renders").apply{mkdirs()}
                    File(out,"$stock-${w}x$h.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
                }
            }
        }}finally{RuntimeEnvironment.setQualifiers("w400dp-h800dp-port-mdpi")}
    }
}
