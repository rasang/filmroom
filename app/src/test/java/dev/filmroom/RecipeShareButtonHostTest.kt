package dev.filmroom

import android.app.AlertDialog
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.InsetDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import dev.filmroom.i18n.L10n
import dev.filmroom.model.EditorState
import dev.filmroom.presets.PresetQrShareDialog
import dev.filmroom.presets.SavedPresetStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class,qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipeShareButtonHostTest {
    private fun idle()=shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(64))
    @Test(timeout=60000) fun compactButtonHasTouchTargetAndBottomBreathingRoomInAllLanguages() {
        for (width in listOf(360,280)) for(language in listOf("zh","en","ja","ko")) {
            RuntimeEnvironment.setQualifiers("w${width}dp-h780dp-mdpi")
            RuntimeEnvironment.setFontScale(if(width==280)1.3f else 1f)
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { host ->
                val activity=host.get();activity.setTheme(R.style.AppTheme);L10n.choose(activity,language)
                var target:AlertDialog?=null
                val share=PresetQrShareDialog(activity,{ dialog ->
                    dialog.show(); target=dialog
                    for(id in listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE)) {
                        dialog.getButton(id).apply { setSingleLine(false);maxLines=Int.MAX_VALUE;ellipsize=null;setHorizontallyScrolling(false)
                            layoutParams=layoutParams.apply{height=ViewGroup.LayoutParams.WRAP_CONTENT} }
                    }
                },{})
                share.show(SavedPresetStore.Preset("fixture","柔光电影",0,SavedPresetStore.snapshot(EditorState())))
                repeat(15){idle();Thread.sleep(5)}
                val dialog=target!!;val button=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val bar=button.parent as LinearLayout
                assertTrue(button.height>=48);assertTrue(bar.paddingBottom>=12)
                assertTrue(button.background is InsetDrawable)
                assertTrue("icon start inset", button.paddingStart >= 12)
                assertTrue("label end inset", button.paddingEnd >= 12)
                assertEquals(20,button.compoundDrawablesRelative[0].bounds.width())
                assertTrue("bottom space",bar.height-button.bottom>=12)
                assertTrue(button.width<=bar.width-bar.paddingLeft-bar.paddingRight)
                assertTrue(button.layout.lineCount>0)
                assertEquals(0,button.layout.getEllipsisCount(button.layout.lineCount-1))
                if(width==360) assertTrue("compact height ${button.height}",button.height<=52)
                val decor=dialog.window!!.decorView
                val path=File(System.getProperty("filmroom.buttonReviewDir","build/ui-host-renders"),"share-$width-$language.png")
                path.parentFile.mkdirs();Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888).let { b->
                    decor.draw(Canvas(b));path.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle() }
                share.close();idle();assertFalse(dialog.isShowing)
            }
        }
        RuntimeEnvironment.setFontScale(1f)
    }
}
