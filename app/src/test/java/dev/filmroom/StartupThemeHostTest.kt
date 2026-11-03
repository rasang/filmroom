package dev.filmroom

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Looper
import android.util.TypedValue
import android.view.View
import androidx.lifecycle.ViewModelProvider
import dev.filmroom.i18n.L10n
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** Checks resources and host Activity lifecycle, not Android/OEM launch animation or phone flash timing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,31,35],application=FilmroomApplication::class,qualifiers="en-rUS-w360dp-h780dp-mdpi")
class StartupThemeHostTest {
    private val dark=Color.rgb(24,24,24)
    private val app get()=RuntimeEnvironment.getApplication() as FilmroomApplication
    @Before fun isolate(){
        resetAndroidViewModelFactoryForHostTest()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,0f)
    }
    private fun value(context:Context,id:Int):Int {
        val out=TypedValue();assertTrue("Missing theme attribute $id",context.theme.resolveAttribute(id,out,true));return out.data
    }
    private fun assertTheme(context:Context){
        assertEquals(dark,value(context,android.R.attr.colorBackground))
        assertEquals(dark,value(context,android.R.attr.windowBackground))
        assertEquals(dark,value(context,android.R.attr.statusBarColor))
        assertEquals(dark,value(context,android.R.attr.navigationBarColor))
        assertEquals(0,value(context,android.R.attr.windowLightStatusBar))
        assertEquals(0,value(context,android.R.attr.windowLightNavigationBar))
        assertEquals(0,value(context,android.R.attr.forceDarkAllowed))
        if(Build.VERSION.SDK_INT>=31)assertEquals(dark,value(context,android.R.attr.windowSplashScreenBackground))
    }
    private fun idle(){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32))}
    @Test fun lightSystemLaunchLanguageChoiceAndRecreationStayDark(){verifyLaunch("notnight")}
    @Test fun darkSystemLaunchLanguageChoiceAndRecreationStayDark(){verifyLaunch("night")}
    private fun verifyLaunch(mode:String){
        RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h780dp-$mode-mdpi")
        app.getSharedPreferences("filmroom-language",0).edit().clear().commit();L10n.initialize(app)
        val launch=android.view.ContextThemeWrapper(app,app.applicationInfo.theme)
        assertEquals(R.style.AppTheme,app.applicationInfo.theme);assertTheme(launch)
        assertEquals("Keep the current launcher logo",R.mipmap.ic_launcher,app.applicationInfo.icon)
        Robolectric.buildActivity(MainActivity::class.java).setup().use{controller->
            val first=controller.get();assertTheme(first)
            assertEquals(dark,(first.window.decorView.background as ColorDrawable).color)
            assertEquals(0,first.window.decorView.systemUiVisibility and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR))
            val dialog=ShadowAlertDialog.getLatestAlertDialog();assertTrue(dialog.isShowing)
            assertEquals(0,dialog.listView.checkedItemPosition);assertFalse(L10n.hasChosen(app))
            assertEquals(listOf("中文","English","日本語","한국어"),(0 until dialog.listView.adapter.count).map{dialog.listView.adapter.getItem(it).toString()})
            val floating=value(dialog.context,android.R.attr.colorBackgroundFloating)
            assertEquals("Language chooser must match the neutral panel",Color.rgb(34,34,34),floating)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
            assertEquals("zh",L10n.language(app));assertTrue(L10n.hasChosen(app))
            val vm=ViewModelProvider(first)[EditorViewModel::class.java]
            val deadline=System.nanoTime()+10_000_000_000L
            while(!vm.state.value.startupReady && System.nanoTime()<deadline){idle();Thread.sleep(5)}
            assertTrue(vm.state.value.startupReady);val parameters=vm.state.value.parameters;val photo=vm.state.value.photo
            controller.recreate();idle();assertNotSame(first,controller.get())
            assertSame(vm,ViewModelProvider(controller.get())[EditorViewModel::class.java])
            assertEquals(parameters,vm.state.value.parameters);assertSame(photo,vm.state.value.photo)
            assertTheme(controller.get());assertEquals(dark,(controller.get().window.decorView.background as ColorDrawable).color)
            assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        }
    }
}
