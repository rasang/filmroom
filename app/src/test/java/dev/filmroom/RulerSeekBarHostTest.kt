package dev.filmroom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.ui.RulerSeekBar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Framework host checks, not device-rendered screenshot evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="mdpi")
class RulerSeekBarHostTest {
    @Test fun rulerDrawsAcrossRangeWithoutChangingValueOrNativeThumb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = RulerSeekBar(context).apply { max = 200 }
        view.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48,View.MeasureSpec.EXACTLY))
        view.layout(0,0,320,48)
        val bitmap = Bitmap.createBitmap(320,48,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            for (value in listOf(0,100,200)) {
                view.progress = value
                view.draw(canvas)
                assertEquals(value,view.progress)
            }
            assertNotNull(view.thumb)
            assertEquals(SeekBar::class.java.name,view.accessibilityClassName)
        } finally { bitmap.recycle() }
    }

    @Test fun nativeAccessibilitySetProgressIsPreserved() {
        val view = RulerSeekBar(ApplicationProvider.getApplicationContext()).apply { max = 200; progress = 0 }
        val args = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,120f) }
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,args))
        assertEquals(120,view.progress)
    }
}
