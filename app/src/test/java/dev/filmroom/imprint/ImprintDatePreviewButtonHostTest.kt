package dev.filmroom.imprint

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImprintDatePreviewButtonHostTest {
    private fun button(date: String?, style: String = "contax_t2") = ImprintDatePreviewButton(
        RuntimeEnvironment.getApplication(), date, style, 1.0, 1.0, Color.BLACK, Color.WHITE)

    private fun pixels(button: ImprintDatePreviewButton, width: Int): IntArray {
        button.setBackgroundColor(Color.BLACK)
        button.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(56, View.MeasureSpec.EXACTLY))
        button.layout(0, 0, width, 56)
        val bitmap = Bitmap.createBitmap(width, 56, Bitmap.Config.ARGB_8888)
        button.draw(Canvas(bitmap))
        return IntArray(width * 56).also { bitmap.getPixels(it, 0, width, 0, 0, width, 56) }
    }

    @Test fun dateUsesRenderedGlyphsRatherThanOrdinaryButtonText() {
        val button = button("'26 11 05")
        assertEquals("'26 11 05", button.displayedDate)
        assertEquals("contax_t2", button.dateStyle)
        assertEquals("", button.text.toString())
        assertTrue(pixels(button, 240).any { Color.red(it) > 80 })
        var clicked = false
        button.setOnClickListener { clicked = true }
        button.performClick()
        assertTrue(clicked)
    }

    @Test fun missingDateKeepsAnAccessibleClickablePlaceholderWithoutFakeDate() {
        val button = button(null)
        button.contentDescription = "Date imprint · No photo time available"
        assertNull(button.displayedDate)
        assertEquals("", button.text.toString())
        assertTrue(button.contentDescription.contains("No photo time"))
        assertTrue(pixels(button, 100).any { Color.red(it) > 80 })
    }

    @Test fun narrowControlsFitEveryStyleWithoutClippingIntoPadding() {
        for (style in ImprintSettings.DATE_STYLES.filter { it != "off" }) {
            val date = ImprintContent.formatDate(java.time.LocalDate.of(2026, 11, 5), style)!!
            val image = pixels(button(date, style), 64)
            assertTrue("$style should remain visible", image.any { Color.red(it) > 30 })
            for (y in 0 until 56) for (x in listOf(0, 1, 62, 63))
                assertEquals("$style should fit inside control", Color.BLACK, image[y * 64 + x])
        }
    }
}
