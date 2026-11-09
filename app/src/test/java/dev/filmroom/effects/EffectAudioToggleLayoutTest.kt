package dev.filmroom.effects

import android.app.Application
import android.content.res.Configuration
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE,application=Application::class)
class EffectAudioToggleLayoutTest {
    private fun layout(widthDp:Int,fontScale:Float=1f,label:String="Live 原声"):Triple<EffectActionBar,View,android.widget.Switch> {
        val base=ApplicationProvider.getApplicationContext<Application>()
        val context=base.createConfigurationContext(Configuration(base.resources.configuration).apply{this.fontScale=fontScale})
        val density=context.resources.displayMetrics.density
        val row=EffectActionBar(context)
        val quality=EffectControls.button(context,"清晰度：原图","video"){}
        val toggle=EffectControls.audioToggle(context,label)
        row.addView(quality);row.addView(toggle)
        val width=(widthDp*density).toInt()
        row.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        row.layout(0,0,row.measuredWidth,row.measuredHeight)
        return Triple(row,quality,toggle)
    }
    @Test fun normalPhoneUsesSameRowAndButtonHeight(){
        val (row,quality,toggle)=layout(328)
        assertEquals(quality.top,toggle.top)
        assertEquals(quality.height,toggle.height)
        assertTrue(toggle.left>=quality.right)
        assertTrue(toggle.right<=row.width)
        val bitmap=android.graphics.Bitmap.createBitmap(row.width,row.height,android.graphics.Bitmap.Config.ARGB_8888)
        row.draw(android.graphics.Canvas(bitmap))
        val target=java.io.File("build/reports/effect-audio-toggle/row.png").apply{parentFile.mkdirs()}
        target.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun smallPhoneDoesNotClip(){
        val (row,quality,toggle)=layout(288)
        assertTrue(quality.right<=row.width)
        assertTrue(toggle.right<=row.width)
        assertTrue(toggle.bottom<=row.height)
    }
    @Test fun largeTextCanWrapWholeControlsWithoutOverlap(){
        val (row,quality,toggle)=layout(288,1.8f,"Live original audio")
        assertTrue(quality.right<=row.width)
        assertTrue(toggle.right<=row.width)
        assertTrue(toggle.top>=quality.bottom || toggle.left>=quality.right)
    }
    @Test fun wholeButtonClickDirectlyTogglesOnce(){
        val (_,_,toggle)=layout(328)
        var calls=0
        toggle.setOnCheckedChangeListener { _,_->calls++ }
        assertFalse(toggle.isChecked)
        toggle.performClick()
        assertTrue(toggle.isChecked)
        assertEquals(1,calls)
        toggle.performClick()
        assertFalse(toggle.isChecked)
        assertEquals(2,calls)
    }
}
