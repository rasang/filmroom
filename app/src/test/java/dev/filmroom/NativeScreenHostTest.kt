package dev.filmroom

import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** JVM framework simulation only; NOT an Android device/emulator render test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class NativeScreenHostTest {
    @org.junit.Before fun chosenLanguageForLegacyFixture(){
        resetAndroidViewModelFactoryForHostTest()
        dev.filmroom.i18n.L10n.choose(org.robolectric.RuntimeEnvironment.getApplication(),"zh")
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,0f)
    }
    @org.junit.After fun restoreAnimationScale(){
        android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}.invoke(null,1f)
    }

 private fun descendants(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList())
 @Test fun chineseEmptyScreenHasNativeImportAndDisabledExport(){
  Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
   val activity=controller.get();val views=descendants(activity.window.decorView)
   assertTrue(views.filterIsInstance<TextView>().any{it.text.toString()=="把照片带进暗房"})
   assertTrue(views.filterIsInstance<Button>().first{it.text.toString()=="导入照片"}.isEnabled)
   assertFalse(views.filterIsInstance<Button>().first{it.contentDescription=="导出"}.isEnabled)
   val permissions=activity.packageManager.getPackageInfo(activity.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
   assertFalse(permissions.contains("android.permission.INTERNET"));assertFalse(permissions.contains("android.permission.READ_MEDIA_IMAGES"))
  }
 }
 @Test fun activityRecreationDoesNotInventPhotoOrEffect(){
  Robolectric.buildActivity(MainActivity::class.java).setup().use {controller->
   val before=androidx.lifecycle.ViewModelProvider(controller.get())[EditorViewModel::class.java]
   val deadline=System.nanoTime()+10_000_000_000L
   while(!before.state.value.startupReady && System.nanoTime()<deadline){
    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));Thread.sleep(5)
   }
   assertTrue("Startup recovery must finish before rotation",before.state.value.startupReady)
   assertNull(before.state.value.photo)
   val parameters=before.state.value.parameters
   // This test's contract is Activity recreation, not injection of the process/system
   // locale into the Activity's explicitly localized resources (the no-arg controller API).
   val previousActivity=controller.get()
   controller.recreate()
   assertNotSame(previousActivity,controller.get())
   repeat(2){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16))}
   val after=androidx.lifecycle.ViewModelProvider(controller.get())[EditorViewModel::class.java]
   assertSame(before,after);assertNull(after.state.value.photo);assertEquals(parameters,after.state.value.parameters)
   val app=org.robolectric.RuntimeEnvironment.getApplication()
   val cached=dev.filmroom.i18n.L10n::class.java.getDeclaredField("localizedResources").apply{isAccessible=true}.get(dev.filmroom.i18n.L10n) as android.content.res.Resources
   val currentTag=dev.filmroom.i18n.L10n::class.java.getDeclaredField("currentTag").apply{isAccessible=true}.get(dev.filmroom.i18n.L10n)
   println("CONFIG_LOCALE pref=${dev.filmroom.i18n.L10n.language(app)} current=$currentTag activity=${controller.get().resources.configuration.locales.toLanguageTags()} cached=${cached.configuration.locales.toLanguageTags()} cachedText=${cached.getString(R.string.text_1f14f4b50d0b)} lookup=${dev.filmroom.i18n.L10n.text(R.string.text_1f14f4b50d0b)} fresh=${dev.filmroom.i18n.L10n.wrap(app).resources.getString(R.string.text_1f14f4b50d0b)}")
   val texts=descendants(controller.get().window.decorView).filterIsInstance<TextView>().map{it.text.toString()}
   assertTrue("Expected Chinese empty editor after rotation; actual=$texts",texts.contains("把照片带进暗房"))
  }
 }
}
