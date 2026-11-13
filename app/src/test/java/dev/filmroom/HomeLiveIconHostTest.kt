package dev.filmroom

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import dev.filmroom.effects.EffectKind
import dev.filmroom.effects.HomeLiveIconPulse
import dev.filmroom.model.EditorState
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeLiveIconHostTest {
    private fun field(a:MainActivity,n:String)=MainActivity::class.java.getDeclaredField(n).apply{isAccessible=true}.get(a)
    private fun resetState(v:View){assertEquals(1f,v.scaleX,0f);assertEquals(1f,v.scaleY,0f);assertEquals(1f,v.alpha,0f)}
    @Test fun disabledAnimationsAndDetachedViewStayAtRest(){
        Robolectric.buildActivity(Activity::class.java).setup().use{host->
            val v=View(host.get());v.scaleX=1.1f;v.alpha=.72f
            HomeLiveIconPulse.play(v,true);resetState(v)
            host.get().setContentView(v);HomeLiveIconPulse.play(v,false);resetState(v)
        }
    }
    @Test fun pulseFinishesOnceAndResetCancelsPendingReturn(){
        Robolectric.buildActivity(Activity::class.java).setup().use{host->
            val v=View(host.get());host.get().setContentView(v)
            HomeLiveIconPulse.play(v,true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));resetState(v)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));resetState(v)
            HomeLiveIconPulse.play(v,true);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            HomeLiveIconPulse.reset(v);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));resetState(v)
        }
    }
    @Test fun homeIconHasTransparentBackgroundAndWhiteOrangeGlyphWithHostScreenshots(){
        CameraLayoutRenderHostTest().chosenLanguageForLegacyFixture()
        val photo=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(70,100,120))}
        try{Robolectric.buildActivity(MainActivity::class.java).setup().use{host->
            val a=host.get()
            CameraLayoutRenderHostTest::class.java.getDeclaredMethod("installFixture",MainActivity::class.java,Bitmap::class.java,EditorState::class.java).apply{isAccessible=true}.invoke(CameraLayoutRenderHostTest(),a,photo,EditorState())
            val button=field(a,"homeMotionButton") as Button
            for(kind in listOf<EffectKind?>(null,EffectKind.LIGHT_TABLE)){
                MainActivity::class.java.getDeclaredField("homeMotionKind").apply{isAccessible=true}.set(a,kind)
                MainActivity::class.java.getDeclaredMethod("updateHomeMotionButton").apply{isAccessible=true}.invoke(a)
                assertEquals(Color.TRANSPARENT,(button.background as GradientDrawable).color!!.defaultColor)
                val drawable=button.compoundDrawables.filterNotNull().first()
                val originalBounds=android.graphics.Rect(drawable.bounds)
                val icon=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
                drawable.setBounds(0,0,64,64);drawable.draw(Canvas(icon))
                val expected=if(kind==null)Color.WHITE else field(a,"accent") as Int
                val pixels=IntArray(4096);icon.getPixels(pixels,0,64,0,0,64,64)
                assertTrue(pixels.any{Color.alpha(it)>200 && (it and 0xffffff)==(expected and 0xffffff)})
                icon.recycle();drawable.bounds=originalBounds
                val root=a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(780,View.MeasureSpec.EXACTLY));root.layout(0,0,360,780)
                val output=File("build/reports/home-live-icons").apply{mkdirs()}
                val image=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888);root.draw(Canvas(image))
                File(output,if(kind==null)"off-host.png" else "on-host.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            }
        }}finally{photo.recycle()}
    }
}
