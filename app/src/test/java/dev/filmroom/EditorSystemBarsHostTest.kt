package dev.filmroom

import android.app.AlertDialog
import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.ui.EditorSystemBars
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowInsetsController

/** Records actual platform controller API requests on30+, not OEM/system-bar rendering. */
@Implements(className="android.view.InsetsController",minSdk=30,isInAndroidSdk=false)
class RecordingEditorInsetsController:ShadowInsetsController(){
    @RealObject private lateinit var real:Any
    companion object {val calls=mutableListOf<Triple<Any,Boolean,Int>>()}
    @Implementation override fun hide(types:Int){calls.add(Triple(real,false,types));super.hide(types)}
    @Implementation public override fun show(types:Int){calls.add(Triple(real,true,types));super.show(types)}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,31,35],qualifiers="w780dp-h354dp-mdpi",shadows=[RecordingEditorInsetsController::class])
class EditorSystemBarsHostTest {
    @Before fun setup(){
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication();dev.filmroom.i18n.L10n.choose(app,"zh")
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        RecordingEditorInsetsController.calls.clear()
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,0f)
    }
    @org.junit.After fun cleanup(){android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,1f)}
    private fun root(a:Activity)=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun idle(){Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))}
    private fun assertRequest(a:Activity,hidden:Boolean){
        assertEquals(hidden,EditorSystemBars.hidesStatusBar(a))
        if(Build.VERSION.SDK_INT==29){
            val flags=a.window.decorView.systemUiVisibility
            assertEquals(hidden,flags and View.SYSTEM_UI_FLAG_FULLSCREEN!=0)
            assertEquals("Navigation must remain visible",0,flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
            assertEquals("Transient swipe behavior",View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY,flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }else{
            val own=RecordingEditorInsetsController.calls.filter{it.first===a.window.insetsController}
            assertTrue("Main must issue a platform request window=${a.window.insetsController} decor=${a.window.decorView.windowInsetsController} attached=${a.window.decorView.isAttachedToWindow} all=${RecordingEditorInsetsController.calls}",own.isNotEmpty())
            assertEquals(!hidden,own.last().second)
            assertEquals(WindowInsetsCompat.Type.statusBars(),own.last().third)
            assertTrue("Never request hiding navigation/caption/IME",own.filter{!it.second}.all{it.third==WindowInsetsCompat.Type.statusBars()})
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,WindowCompat.getInsetsController(a.window,a.window.decorView).systemBarsBehavior)
        }
    }
    @Test fun landscapeHidesOnlyStatusAndPortraitRecreationRestoresIt(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            idle();val a=c.get();val vm=ViewModelProvider(a)[EditorViewModel::class.java];val parameters=vm.state.value.parameters
            a.onWindowFocusChanged(true);assertRequest(a,true)
            val config=Configuration(a.resources.configuration).apply{orientation=Configuration.ORIENTATION_PORTRAIT;screenWidthDp=354;screenHeightDp=780}
            c.configurationChange(config).visible();idle();assertTrue(c.get().window.decorView.isAttachedToWindow);c.get().onWindowFocusChanged(true)
            assertRequest(c.get(),false);assertSame(vm,ViewModelProvider(c.get())[EditorViewModel::class.java]);assertEquals(parameters,vm.state.value.parameters)
            val landscape=Configuration(c.get().resources.configuration).apply{orientation=Configuration.ORIENTATION_LANDSCAPE;screenWidthDp=780;screenHeightDp=354}
            c.configurationChange(landscape).visible();idle();assertTrue(c.get().window.decorView.isAttachedToWindow);c.get().onWindowFocusChanged(true);assertRequest(c.get(),true)
        }
    }
    @Test fun dialogsPickerReturnAndTransientInsetsDoNotMutateEditingStateOrHideNavigation(){
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get();idle();val vm=ViewModelProvider(a)[EditorViewModel::class.java]
            val deadline=System.nanoTime()+10_000_000_000L
            while(!vm.state.value.startupReady && System.nanoTime()<deadline){idle();Thread.sleep(5)}
            assertTrue(vm.state.value.startupReady)
            @Suppress("UNCHECKED_CAST")
            val flow=EditorViewModel::class.java.getDeclaredField("mutable").apply{isAccessible=true}.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<EditorUiState>
            // Editing-state fixture only; decode/render validation is a separate gate.
            val source=dev.filmroom.io.PhotoSource(java.io.File(a.cacheDir,"fullscreen-state-fixture"),"fixture","image/png",32,24,0)
            val before=vm.state.value.parameters.copy(brightness=1.25f)
            flow.value=EditorUiState(photo=source,parameters=before,canUndo=true)
            idle()
            val dialog=AlertDialog.Builder(a).setMessage("Window focus fixture").setPositiveButton("OK",null).show()
            a.onWindowFocusChanged(false);dialog.dismiss();a.onWindowFocusChanged(true);assertRequest(a,true)
            // Equivalent Activity lifecycle around external photo picker/Settings; no fake image result.
            c.pause().stop().start().resume();a.onWindowFocusChanged(true);assertRequest(a,true)
            assertSame(vm,ViewModelProvider(a)[EditorViewModel::class.java]);assertEquals(before,vm.state.value.parameters);assertSame(source,vm.state.value.photo);assertTrue(vm.state.value.canUndo);assertFalse(vm.state.value.updating)
            val count=RecordingEditorInsetsController.calls.size
            val inset=WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(),Insets.of(0,24,0,0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(),Insets.of(0,0,0,24)).build()
            ViewCompat.dispatchApplyWindowInsets(root(a),inset)
            assertEquals("Insets callback must not repeatedly hide a transient bar",count,RecordingEditorInsetsController.calls.size)
            assertEquals(0,root(a).paddingTop);assertEquals(24,root(a).paddingBottom)
        }
    }
    @Test fun cutoutCaptionNavigationAndImeUseMaximumEdgesWithoutStatusBlankSpace(){
        val insets=WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(),Insets.of(0,24,0,0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(),Insets.of(0,0,18,24))
            .setDisplayCutout(androidx.core.view.DisplayCutoutCompat(android.graphics.Rect(30,0,6,0),listOf(android.graphics.Rect(0,0,30,20))))
            .setInsets(WindowInsetsCompat.Type.captionBar(),Insets.of(0,12,0,0))
            .setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,180)).build()
        val landscape=EditorSystemBars.safeInsets(insets,true)
        assertEquals(30,landscape.left);assertEquals(18,landscape.right);assertEquals(180,landscape.bottom)
        // Caption exists only API30+; legacy compatibility has no caption window type.
        assertEquals(if(Build.VERSION.SDK_INT>=30)12 else 0,landscape.top)
        assertEquals(24,EditorSystemBars.safeInsets(insets,false).top)
        Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
            val a=c.get()
            var dispatched=insets
            if(Build.VERSION.SDK_INT==29){
                // API29 AndroidX reads cutout from attached rootWindowInsets. Keep the
                // host root's cached platform insets consistent with the dispatched event.
                val viewRoot=View::class.java.getDeclaredMethod("getViewRootImpl").apply{isAccessible=true}.invoke(root(a))
                val platform=android.view.WindowInsets.Builder(insets.toWindowInsets()!!)
                    .setSystemWindowInsets(android.graphics.Insets.of(0,24,18,180))
                    .setStableInsets(android.graphics.Insets.of(0,24,18,24)).build()
                viewRoot.javaClass.getDeclaredField("mLastWindowInsets").apply{isAccessible=true}.set(viewRoot,platform)
                dispatched=WindowInsetsCompat.toWindowInsetsCompat(platform,root(a))
                assertEquals(30,root(a).rootWindowInsets.displayCutout!!.safeInsetLeft)
            }
            ViewCompat.dispatchApplyWindowInsets(root(a),dispatched)
            assertEquals(landscape.left,root(a).paddingLeft);assertEquals(landscape.bottom,root(a).paddingBottom)
            val config=Configuration(a.resources.configuration)
            Shadows.shadowOf(a).setInMultiWindowMode(true)
            a.onMultiWindowModeChanged(true,config);assertRequest(a,false)
            ViewCompat.dispatchApplyWindowInsets(root(a),dispatched);assertEquals(24,root(a).paddingTop)
            Shadows.shadowOf(a).setInMultiWindowMode(false);a.onMultiWindowModeChanged(false,config);assertRequest(a,true)
        }
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use{c->
            assertEquals(0,c.get().window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN)
            if(Build.VERSION.SDK_INT>=30)assertFalse(RecordingEditorInsetsController.calls.any{it.first===c.get().window.insetsController && !it.second})
        }
    }
}
