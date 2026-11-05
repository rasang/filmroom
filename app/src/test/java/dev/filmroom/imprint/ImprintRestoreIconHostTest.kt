package dev.filmroom.imprint

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.model.EditorState
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
@Config(sdk=[35], qualifiers="w320dp-h640dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImprintRestoreIconHostTest {
    @Test fun normalScale() = verify(1f)
    @Test fun largeText() = verify(1.3f)
    @Test fun accessibilityText() = verify(2f)

    private fun verify(fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val metadata = PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2026:11:05 12:34:56"))
            val dialog = ImprintDialog.show(host.get(), EditorState(), metadata) {}
            shadowOf(Looper.getMainLooper()).idle()
            val root = dialog.window!!.decorView
            assertNull(root.findViewWithTag<View>("imprint-source"))
            val row = root.findViewWithTag<LinearLayout>("imprint-dialog-date-row")
            row.measure(View.MeasureSpec.makeMeasureSpec(224, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            val restore = root.findViewWithTag<ImageButton>("imprint-restore-capture")
            val date = root.findViewWithTag<View>("imprint-date-value")
            assertEquals(48, restore.width); assertEquals(48, restore.height)
            assertTrue(restore.left >= date.right)
            assertTrue(restore.right <= row.width)
            assertTrue(restore.contentDescription.isNotBlank())
            assertEquals(restore.contentDescription, restore.tooltipText)
            assertTrue(restore.background is android.graphics.drawable.RippleDrawable)
            assertTrue(restore.isClickable)
            assertTrue(restore.isFocusable)
            assertEquals(24, restore.drawable.intrinsicWidth)
            assertEquals(24, restore.drawable.intrinsicHeight)
            System.getProperty("filmroom.imprint.evidenceDir")?.let { path ->
                val directory = File(path).apply { mkdirs() }
                val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(dev.filmroom.ui.FilmroomPalette.surface)
                row.draw(Canvas(bitmap))
                File(directory, "date-reset-row-$fontScale.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
                // Draw the actual native dialog content, not a vector mock-up, at narrow width.
                val content = root.findViewWithTag<android.widget.ScrollView>("imprint-scroll").getChildAt(0)
                content.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                content.layout(0, 0, content.measuredWidth, content.measuredHeight)
                val full = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
                full.eraseColor(dev.filmroom.ui.FilmroomPalette.surface)
                content.draw(Canvas(full))
                File(directory, "date-reset-dialog-$fontScale.png").outputStream().use {
                    assertTrue(full.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                full.recycle()
            }
            dialog.dismiss()
        } finally {
            host.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
