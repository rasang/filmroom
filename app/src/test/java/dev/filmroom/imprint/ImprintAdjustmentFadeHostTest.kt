package dev.filmroom.imprint

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImprintAdjustmentFadeHostTest {
    private val metadata = PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2026:11:05 12:34:56"))
    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))

    @Test fun onlyTrackedSliderRemainsAndRepeatedDragRestoresOriginalState() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val dialog = ImprintDialog.show(host.get(), EditorState(), metadata) {}
            settle()
            val root = dialog.window!!.decorView
            val size = root.findViewWithTag<SeekBar>("imprint-size")
            val glow = root.findViewWithTag<SeekBar>("imprint-glow")
            val date = root.findViewWithTag<View>("imprint-dialog-date-row")
            val fade = ImprintAdjustmentFade(dialog)
            val dim = dialog.window!!.attributes.dimAmount
            fade.begin(size); settle()
            assertEquals(1f, size.alpha, 0f); assertTrue(size.isEnabled)
            assertEquals(0f, glow.alpha, 0f); assertFalse(glow.isEnabled)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, date.importantForAccessibility)
            assertEquals(0f, dialog.window!!.attributes.dimAmount, 0f)
            fade.end()
            // Retouch during the fade-in must reverse it, not complete an obsolete animation.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            fade.begin(size); settle(); assertEquals(0f, glow.alpha, 0f)
            fade.end(); settle()
            assertEquals(1f, glow.alpha, 0f); assertTrue(glow.isEnabled)
            assertEquals(dim, dialog.window!!.attributes.dimAmount, 0f)
            fade.begin(glow); settle()
            assertEquals(1f, glow.alpha, 0f); assertEquals(0f, size.alpha, 0f)
            fade.finishImmediately(); settle()
            assertTrue(size.isEnabled); assertEquals(1f, size.alpha, 0f)
            dialog.dismiss()
        } finally { host.pause().stop().destroy() }
    }

    @Test fun previewIsDraftOnlyAndCancelAndRecreationNeverApplyIt() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            var preview: ImprintSettings? = null
            var applyCount = 0
            val original = ImprintSettings(dateStyle="contax_t2", dateEnabled=false)
            val state = EditorState(imprint=original)
            val dialog = ImprintDialog.show(host.get(), state, metadata, onPreview={ preview=it }) { applyCount++ }
            settle()
            val root = dialog.window!!.decorView
            root.findViewWithTag<SeekBar>("imprint-size").progress = 83
            root.findViewWithTag<SeekBar>("imprint-glow").progress = 19
            assertEquals(1.43, preview!!.size, 0.0001)
            assertEquals(.19, preview!!.glow, 0.0001)
            assertTrue(preview!!.dateEnabled)
            assertEquals(original, state.imprint)
            // Keyboard/accessibility progress changes preserve all dialog content.
            assertEquals(1f, root.findViewWithTag<View>("imprint-dialog-date-row").alpha, 0f)
            val saved = ImprintDialog.capture(dialog)
            dialog.cancel(); settle()
            assertNull(preview); assertEquals(0, applyCount)
            val restored = ImprintDialog.show(host.get(), state, metadata, saved, onPreview={ preview=it }) { applyCount++ }
            settle()
            assertEquals(83, restored.window!!.decorView.findViewWithTag<SeekBar>("imprint-size").progress)
            assertEquals(1f, restored.window!!.decorView.findViewWithTag<View>("imprint-dialog-date-row").alpha, 0f)
            restored.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); settle()
            assertNull(preview); assertEquals(0, applyCount)
        } finally { host.pause().stop().destroy() }
    }
    @Test fun photoPreviewReplacesOldDateAndRestoresSettledPixels() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val width = 400; val height = 300
        val source = android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.DKGRAY)
        val original = ImprintSettings(dateStyle="contax_t2", size=.6, glow=.1)
        val oldPlan = ImprintPlan.create(original,metadata,width,height)
        val crop = dev.filmroom.crop.CropSnapshot.create(width,height)
        val settled = dev.filmroom.crop.CropPreview.compose(source,crop,oldPlan) {}
        val photo = dev.filmroom.ui.PhotoView(host.get())
        fun pixels(): IntArray {
            val bitmap = android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888)
            photo.draw(android.graphics.Canvas(bitmap))
            return IntArray(width*height).also { bitmap.getPixels(it,0,width,0,0,width,height); bitmap.recycle() }
        }
        try {
            photo.bitmap=settled
            photo.layout(0,0,width,height)
            photo.bindCrop(crop,source,dev.filmroom.model.CropSettings(),false) { _, _ -> }
            photo.bindImprint(oldPlan)
            val before=pixels()
            photo.bindImprint(ImprintPlan.create(original.copy(size=1.6,glow=1.0,corner="top_left"),metadata,width,height),true)
            val during=pixels()
            assertFalse(before.contentEquals(during))
            // The old bottom-right lettering must be erased, not doubled beneath the draft.
            for(stamp in oldPlan.stamps) for(y in stamp.top until stamp.bottom) for(x in stamp.left until stamp.right)
                assertEquals(android.graphics.Color.DKGRAY,during[y*width+x])
            photo.bindImprint(oldPlan)
            assertArrayEquals(before,pixels())
        } finally { photo.close(); settled.recycle(); source.recycle(); host.pause().stop().destroy() }
    }

    @Test fun delayedDismissalFromAnOlderEditorCannotClearNewPreview() {
        val session = ImprintPreviewSession()
        var shown: ImprintSettings? = null
        val oldEditor = session.begin { shown=it }
        oldEditor(ImprintSettings(size=.7))
        val newEditor = session.begin { shown=it }
        val latest = ImprintSettings(size=1.5)
        newEditor(latest)
        oldEditor(null)
        assertEquals(latest,shown)
        oldEditor(ImprintSettings(size=.6))
        assertEquals(latest,shown)
        newEditor(null)
        assertNull(shown)
    }

}
