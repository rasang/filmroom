package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import dev.filmroom.model.EditorState
import dev.filmroom.ui.PhotoView
import dev.filmroom.ui.PreviewHistogramView
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewHistogramActivityHostTest {
    private fun field(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    @Suppress("UNCHECKED_CAST")
    private fun state(activity: MainActivity): MutableStateFlow<EditorUiState> {
        val model = MainActivity::class.java.getDeclaredMethod("getModel").apply { isAccessible = true }.invoke(activity)
        return EditorViewModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(model) as MutableStateFlow<EditorUiState>
    }
    private fun install(activity: MainActivity, image: Bitmap) {
        val fixture = CameraLayoutRenderHostTest()
        CameraLayoutRenderHostTest::class.java.getDeclaredMethod("installFixture", MainActivity::class.java, Bitmap::class.java, EditorState::class.java)
            .apply { isAccessible = true }.invoke(fixture, activity, image, EditorState())
    }
    private fun await(message: String, condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (condition()) return
            Thread.sleep(5)
        }
        fail(message)
    }
    private fun layout(root: View, w: Int, h: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
    }
    private fun bounds(view: View, parent: ViewGroup): Rect = Rect(0, 0, view.width, view.height).also { parent.offsetDescendantRectToMyCoords(view, it) }
    private fun synthetic() = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) {
            val row = IntArray(256) { x -> val value = (x + 2 * y) / 2; Color.rgb(value, value, value) }
            setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    @Test fun translucentReadoutFitsOrientationsAndFontScalesWithoutInterceptingTouches() {
        val output = File(System.getProperty("filmroom.histogram.output") ?: "build/histogram-host-renders").apply { mkdirs() }
        try {
            for ((w, h) in listOf(320 to 640, 740 to 320)) for (font in listOf(1f, 2f)) {
                RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-mdpi")
                RuntimeEnvironment.setFontScale(font)
                CameraLayoutRenderHostTest().chosenLanguageForLegacyFixture()
                val image = synthetic()
                Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
                    val activity = host.get(); install(activity, image)
                    val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                    val histogram = field(activity, "histogram") as PreviewHistogramView
                    await("Histogram did not adopt the preview") { histogram.histogram != null }
                    layout(root, w, h)
                    val rect = bounds(histogram, root)
                    val photo = field(activity, "photo") as PhotoView
                    val photoRect = bounds(photo, root)
                    assertEquals(100, histogram.width); assertEquals(44, histogram.height)
                    assertTrue(photoRect.contains(rect)); assertEquals(photoRect.left + 12, rect.left)
                    for (name in listOf("detailLoading", "compare", "infoButton", "recipeButton"))
                        assertFalse("Overlaps $name", Rect.intersects(rect, bounds(field(activity, name) as View, root)))
                    val touch = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 20f, 20f, 0)
                    assertFalse(histogram.dispatchTouchEvent(touch)); touch.recycle()
                    val layer = Bitmap.createBitmap(100, 44, Bitmap.Config.ARGB_8888)
                    histogram.draw(Canvas(layer))
                    val alpha = Color.alpha(layer.getPixel(5, 22))
                    assertTrue("Panel must be translucent: $alpha", alpha in 100..180); layer.recycle()
                    val rendered = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(rendered)); File(output, "${w}x${h}-font${font.toInt()}.png").outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }; rendered.recycle()
                    (field(activity, "compare") as Button).performClick()
                    shadowOf(Looper.getMainLooper()).idle(); layout(root, w, h)
                    val badge = field(activity, "badge") as TextView
                    assertEquals(View.VISIBLE, badge.visibility)
                    assertTrue(bounds(badge, root).top >= bounds(histogram, root).bottom + 6)
                    assertFalse(Rect.intersects(bounds(badge, root), bounds(field(activity, "detailLoading") as View, root)))
                    assertTrue(bounds(badge, root).bottom <= photoRect.bottom)
                }
                image.recycle()
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun rapidChangesComparisonAndForegroundRestartOnlyPublishCurrentPreview() {
        CameraLayoutRenderHostTest().chosenLanguageForLegacyFixture()
        val original = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val rendered = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity = host.get(); install(activity, original)
            val mutable = state(activity)
            val histogram = field(activity, "histogram") as PreviewHistogramView
            await("Initial histogram") { histogram.histogram?.bins?.get(0) == 32768 }
            repeat(20) { index ->
                mutable.value = mutable.value.copy(rendered = if (index % 2 == 0) rendered else original)
                shadowOf(Looper.getMainLooper()).idle()
            }
            mutable.value = mutable.value.copy(rendered = rendered)
            await("Newest preview must win") { histogram.displayedInput?.bitmap === rendered }
            assertEquals(32768, histogram.histogram!!.bins[127])
            mutable.value = mutable.value.copy(compare = true)
            await("Compare must use original") { histogram.displayedInput?.bitmap === original }
            assertEquals(32768, histogram.histogram!!.bins[0])
            mutable.value = mutable.value.copy(compare = false, compositionEditing = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, histogram.visibility); assertNull(histogram.histogram)
            mutable.value = mutable.value.copy(compositionEditing = false)
            await("Commit returns histogram") { histogram.displayedInput?.bitmap === rendered }
            val crop = dev.filmroom.model.CropSettings(dev.filmroom.model.CropMode.SQUARE)
            mutable.value = mutable.value.copy(parameters = mutable.value.parameters.copy(crop = crop))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Committed live crop must not restore the previous graph", View.GONE, histogram.visibility)
            assertNull(histogram.histogram)
            mutable.value = mutable.value.copy(renderedParameters = mutable.value.parameters)
            await("Adopted crop can restore the graph") { histogram.displayedInput?.bitmap === rendered }
            host.pause().stop()
            assertNull(histogram.displayedInput); assertNull(histogram.histogram)
            host.start().resume().visible()
            await("Foreground restart must restore current histogram") { histogram.displayedInput?.bitmap === rendered }
            host.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            assertNull(histogram.displayedInput); assertNull(histogram.histogram)
            assertFalse(original.isRecycled); assertFalse(rendered.isRecycled)
        }
        original.recycle(); rendered.recycle()
    }
}
