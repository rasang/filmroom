package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.ScrollView
import dev.filmroom.i18n.L10n
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Host layout/Skia bounds, not a phone screenshot or system-bar integration test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w780dp-h354dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmptyHomeLandscapeHostTest {
    private fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
    private fun layout(root:View,w:Int,h:Int){
        root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));root.layout(0,0,w,h)
    }
    @Test(timeout=120000) fun importFitsInitiallyInLandscapeAndRemainsReachableWithLargeText(){
        resetAndroidViewModelFactoryForHostTest()
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("local-session",0).edit().clear().commit()
        val animator=android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale",Float::class.javaPrimitiveType).apply{isAccessible=true}
        animator.invoke(null,0f)
        try{for(tag in L10n.supported)for((w,h) in listOf(780 to 354,640 to 320))for(font in listOf(1f,2f)){
            RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi");RuntimeEnvironment.setFontScale(font);L10n.choose(app,tag)
            Robolectric.buildActivity(MainActivity::class.java).setup().use{c->
                val a=c.get()
                val vm=androidx.lifecycle.ViewModelProvider(a)[EditorViewModel::class.java]
                val deadline=System.nanoTime()+5_000_000_000L
                while(!vm.state.value.startupReady && System.nanoTime()<deadline){Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();Thread.sleep(5)}
                assertTrue(vm.state.value.startupReady)
                val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                // Exercise asymmetric side bars plus top/bottom reservations through the real listener.
                root.dispatchApplyWindowInsets(WindowInsets.Builder().setSystemWindowInsets(Insets.of(24,24,0,24)).build())
                layout(root,w,h)
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));layout(root,w,h)
                val scroll=MainActivity::class.java.getDeclaredField("welcomeScroll").apply{isAccessible=true}.get(a) as ScrollView
                val button=all(scroll).filterIsInstance<Button>().single{it.text.toString()==L10n.text(R.string.text_a7fc4b2dbd50)}
                val compare=MainActivity::class.java.getDeclaredField("compare").apply{isAccessible=true}.get(a) as View
                assertEquals("Empty-home compare must not occlude import $tag $w $font",View.GONE,compare.visibility)
                assertTrue("Import touch height $tag $w $font",button.height>=48)
                assertTrue("Import touch width $tag $w $font",button.width>=48)
                if(font>1f){scroll.scrollTo(0,scroll.getChildAt(0).height);layout(root,w,h)}
                val visible=Rect();assertTrue(button.getGlobalVisibleRect(visible))
                assertEquals("Import clipped vertically $tag $w font=$font scroll=${scroll.scrollY} child=${scroll.getChildAt(0).height} viewport=${scroll.height}",button.height,visible.height())
                assertEquals("Import clipped horizontally $tag $w font=$font",button.width,visible.width())
                val out=File("build/empty-home-landscape-renders").apply{mkdirs()}
                val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image));File(out,"$tag-${w}x$h-font$font.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            }
        }}finally{RuntimeEnvironment.setFontScale(1f);L10n.choose(app,"zh");animator.invoke(null,1f)}
    }
}
