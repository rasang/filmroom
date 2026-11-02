package dev.filmroom.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import dev.filmroom.R
import dev.filmroom.i18n.L10n
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/** Host Android/Skia evidence, not an OEM phone screenshot or settings-routing test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportBackgroundGuidanceLayoutHostTest {
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @Test fun actionsFitFourLocalesSmallPortraitAndLandscape() {
        val app=RuntimeEnvironment.getApplication()
        try {
            for(language in L10n.supported)for((w,h) in listOf(320 to 640,740 to 320))for(font in listOf(1f,2f)) {
                RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi")
                RuntimeEnvironment.setFontScale(font);L10n.choose(app,language)
                app.getSharedPreferences("export-background-guidance",0).edit().clear().commit()
                Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
                    val activity=controller.get();activity.setTheme(R.style.AppTheme)
                    val guidance=ExportBackgroundGuidance(activity,{ExportBackgroundStatus.UNKNOWN},{false},{fail("No export from render")})
                    guidance.offer()
                    val dialog=ShadowAlertDialog.getLatestAlertDialog()
                    val root=dialog.window!!.decorView
                    val width=w-32;val height=h-32
                    root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.AT_MOST))
                    root.layout(0,0,width,root.measuredHeight)
                    shadowOf(Looper.getMainLooper()).idle()
                    val buttons=listOf(R.string.export_background_open,R.string.export_background_ignore,R.string.export_background_always_ignore)
                        .map { root.findViewWithTag<Button>(it) }
                    buttons.forEach { button ->
                        assertTrue("Action too short: $language $w font=$font",button.height>=48)
                        assertTrue("Unmeasured action",button.width>0)
                        if(font==1f)assertEquals("Wrapped action: $language $w ${button.text}",1,button.lineCount)
                        val available=button.width-button.compoundPaddingLeft-button.compoundPaddingRight
                        for(line in 0 until button.lineCount) {
                            assertTrue("Clipped action: $language ${button.text}",button.layout.getLineWidth(line)<=available+1)
                            assertEquals(0,button.layout.getEllipsisCount(line))
                        }
                    }
                    assertTrue(buttons[0].bottom<=buttons[1].top)
                    assertTrue(buttons[1].bottom<=buttons[2].top)
                    val scroll=buttons[0].parent.parent as ScrollView
                    scroll.isSmoothScrollingEnabled=false
                    scroll.fullScroll(View.FOCUS_DOWN);shadowOf(Looper.getMainLooper()).idle()
                    val last=buttons.last()
                    assertTrue("Last action unreachable: $language $w font=$font",
                        last.bottom<=scroll.scrollY+scroll.height-scroll.paddingBottom)
                    assertTrue("Primary outside scrollable content",buttons[0].top>=0)
                    val bitmap=Bitmap.createBitmap(width,root.height,Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    val output=File("build/background-guidance-host-renders").apply { mkdirs() }
                    File(output,"$language-${w}x$h-font$font.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                    buttons[0].performClick();shadowOf(Looper.getMainLooper()).idle()
                    val returned=ShadowAlertDialog.getLatestAlertDialog()
                    val returnRoot=returned.window!!.decorView
                    returnRoot.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.AT_MOST))
                    returnRoot.layout(0,0,width,returnRoot.measuredHeight)
                    val returnButtons=listOf(R.string.export_background_continue,R.string.export_background_cancel)
                        .map { returnRoot.findViewWithTag<Button>(it) }
                    val returnScroll=returnButtons[0].parent.parent as ScrollView
                    returnScroll.isSmoothScrollingEnabled=false
                    returnScroll.fullScroll(View.FOCUS_DOWN);shadowOf(Looper.getMainLooper()).idle()
                    for(button in returnButtons) {
                        val bounds=android.graphics.Rect()
                        button.getDrawingRect(bounds)
                        (returnRoot as ViewGroup).offsetDescendantRectToMyCoords(button,bounds)
                        assertTrue("Return action outside dialog: $language $w font=$font $bounds",bounds.top>=0 && bounds.bottom<=returnRoot.height && bounds.left>=0 && bounds.right<=width)
                        assertTrue("Return action too small: $language $w font=$font ${button.text} ${button.width}x${button.height}",button.height>=48 && button.width>0)
                        val available=button.width-button.compoundPaddingLeft-button.compoundPaddingRight
                        for(line in 0 until button.lineCount) {
                            assertTrue("Clipped return action: $language ${button.text}",button.layout.getLineWidth(line)<=available+1)
                            assertEquals(0,button.layout.getEllipsisCount(line))
                        }
                    }
                    val returnBitmap=Bitmap.createBitmap(width,returnRoot.height,Bitmap.Config.ARGB_8888)
                    returnRoot.draw(Canvas(returnBitmap))
                    File(output,"$language-return-${w}x$h-font$font.png").outputStream().use { returnBitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    returnBitmap.recycle();guidance.close()
                }
            }
        } finally { RuntimeEnvironment.setFontScale(1f);L10n.choose(app,"zh") }
    }
}
