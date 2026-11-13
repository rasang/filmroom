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
import dev.filmroom.effects.HomeLiveIconDrawable
import dev.filmroom.effects.HomeLiveIconMotion
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
            val v=Button(host.get());HomeLiveIconPulse.install(v,Color.WHITE);v.scaleX=1.1f;v.alpha=.72f
            HomeLiveIconPulse.play(v,true);resetState(v)
            host.get().setContentView(v);HomeLiveIconPulse.play(v,false);resetState(v)
        }
    }
    @Test fun pulseFinishesOnceAndResetCancelsPendingReturn(){
        Robolectric.buildActivity(Activity::class.java).setup().use{host->
            val v=Button(host.get());HomeLiveIconPulse.install(v,Color.WHITE);host.get().setContentView(v)
            HomeLiveIconPulse.play(v,true)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200));resetState(v)
            val icon=v.compoundDrawables[0] as HomeLiveIconDrawable
            assertFalse(icon.isRunning);assertEquals(1f,HomeLiveIconMotion.layer(icon.elapsedMs,2).scale,.001f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));resetState(v)
            HomeLiveIconPulse.play(v,true);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            HomeLiveIconPulse.reset(v);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));resetState(v)
            assertEquals(0L,icon.elapsedMs);assertFalse(icon.isRunning)
        }
    }

    @Test fun rippleLayersAreStaggeredAndReturnExactlyToRest(){
        for(i in 0..2){
            val peak=HomeLiveIconMotion.LAYER_DURATION_MS/2+i*HomeLiveIconMotion.LAYER_DELAY_MS
            assertEquals(1.24f,HomeLiveIconMotion.layer(peak,i).scale,.0001f)
            assertEquals(.4f,HomeLiveIconMotion.layer(peak,i).opacity,.0001f)
            assertEquals(1f,HomeLiveIconMotion.layer(0,i).scale,0f)
            assertEquals(1f,HomeLiveIconMotion.layer(HomeLiveIconMotion.DURATION_MS,i).scale,0f)
        }
        val early=(0..2).map{HomeLiveIconMotion.layer(160,it).scale}
        assertTrue(early[0]>early[1]);assertTrue(early[1]>early[2])
        val late=(0..2).map{HomeLiveIconMotion.layer(840,it).scale}
        assertTrue(late[0]<late[1]);assertTrue(late[1]<late[2])
    }
    @Test fun drawableMotionDoesNotMoveButtonAndCanBeInterrupted(){
        Robolectric.buildActivity(Activity::class.java).setup().use{host->
            val v=Button(host.get());HomeLiveIconPulse.install(v,Color.WHITE);host.get().setContentView(v)
            val icon=v.compoundDrawables[0] as HomeLiveIconDrawable
            HomeLiveIconPulse.play(v,true);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            assertTrue(icon.elapsedMs>0);resetState(v)
            HomeLiveIconPulse.install(v,Color.rgb(255,140,70));assertSame(icon,v.compoundDrawables[0])
            HomeLiveIconPulse.play(v,true);assertTrue(icon.elapsedMs<100)
            HomeLiveIconPulse.reset(v);assertEquals(0L,icon.elapsedMs)
        }
    }
    @Test fun renderRippleFramesWithinStableDrawableBounds(){
        val out=File("build/reports/home-live-icons").apply{mkdirs()}
        val icon=HomeLiveIconDrawable().apply{setBounds(0,0,240,240);setTint(Color.rgb(255,140,70))}
        val frames=listOf(0L,160L,320L,420L,500L,580L,740L,840L,1000L)
        val strip=Bitmap.createBitmap(240*frames.size,240,Bitmap.Config.ARGB_8888)
        frames.forEachIndexed{i,time->
            val frame=Bitmap.createBitmap(240,240,Bitmap.Config.ARGB_8888);icon.seek(time);icon.draw(Canvas(frame))
            val pixels=IntArray(240*240);frame.getPixels(pixels,0,240,0,0,240,240)
            assertTrue(pixels.any{Color.alpha(it)>0})
            for(x in 0..239){assertEquals(0,Color.alpha(pixels[x]));assertEquals(0,Color.alpha(pixels[239*240+x]))}
            for(y in 0..239){assertEquals(0,Color.alpha(pixels[y*240]));assertEquals(0,Color.alpha(pixels[y*240+239]))}
            Canvas(strip).drawBitmap(frame,i*240f,0f,null);frame.recycle()
        }
        File(out,"ripple-frames.png").outputStream().use{strip.compress(Bitmap.CompressFormat.PNG,100,it)};strip.recycle()
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
