package dev.filmroom.imprint

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Switch
import dev.filmroom.border.MediumFormat
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImprintPlacementSwitchHostTest {
    private val metadata = PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2026:11:05 12:34:56"))
    private val medium = EditorState(filmBorder=true, mediumFormat=MediumFormat.MF66)
    private fun toggle(dialog: AlertDialog) = dialog.window!!.decorView.findViewWithTag<Switch>("imprint-placement")
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun placementSwitchOnlyAppearsForAnActive120Border() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for (state in listOf(medium, medium.copy(mediumFormat=null), medium.copy(filmBorder=false))) {
                val dialog = ImprintDialog.show(host.get(), state, metadata) {}
                idle()
                assertEquals(if (state.filmBorder && state.effectiveMediumFormat != null) View.VISIBLE else View.GONE, toggle(dialog).visibility)
                assertFalse(toggle(dialog).isChecked)
                dialog.dismiss()
            }
        } finally { host.pause().stop().destroy() }
    }

    @Test fun switchRestoresLegacyDraftAndAppliesWithoutChangingDateMasterSwitch() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val old = ImprintSettings(dateStyle="canon_data_back", dateEnabled=false)
            var applied: ImprintSettings? = null
            val legacy = Bundle().apply { putInt("imprint-placement", 1) }
            val dialog = ImprintDialog.show(host.get(), medium.copy(imprint=old), metadata, legacy) { applied=it }
            idle(); assertTrue(toggle(dialog).isChecked)
            toggle(dialog).performClick(); assertFalse(toggle(dialog).isChecked)
            toggle(dialog).performClick(); assertTrue(toggle(dialog).isChecked)
            val saved = ImprintDialog.capture(dialog)
            dialog.dismiss(); assertNull(applied)
            val restored = ImprintDialog.show(host.get(), medium.copy(imprint=old), metadata, saved) { applied=it }
            idle(); assertTrue(toggle(restored).isChecked)
            restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            assertEquals("edge", applied!!.datePlacement)
            assertFalse(applied!!.dateEnabled)
            val photo = ImprintDialog.show(host.get(), medium.copy(imprint=applied!!), metadata) { applied=it }
            idle(); toggle(photo).performClick()
            photo.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            assertEquals("photo", applied!!.datePlacement)
            assertFalse(applied!!.dateEnabled)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun hiddenSwitchPreservesSaved120Preference() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val old = ImprintSettings(dateStyle="contax_t2", datePlacement="edge")
            var applied: ImprintSettings? = null
            val dialog = ImprintDialog.show(host.get(), EditorState(imprint=old), metadata) { applied=it }
            idle(); assertEquals(View.GONE, toggle(dialog).visibility)
            assertTrue(toggle(dialog).isChecked)
            assertFalse(dialog.window!!.decorView.findViewWithTag<android.widget.TextView>("imprint-content").text.contains(ImprintText.EDGE_DATE_INACTIVE.text(host.get())))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            assertEquals(old, applied)
        } finally { host.pause().stop().destroy() }
    }
    @Test @Config(qualifiers="w320dp-h480dp-port-mdpi")
    fun restoreIsAccessibleIconToRightOfDateAndRestoresOriginalWithoutApplying() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val old = ImprintSettings(dateStyle="contax_t2", manualDateTime="2020-01-02 03:04:05")
            var applied: ImprintSettings? = null
            val dialog = ImprintDialog.show(host.get(), medium.copy(imprint=old), metadata) { applied=it }
            idle()
            val root = dialog.window!!.decorView
            val row = root.findViewWithTag<android.widget.LinearLayout>("imprint-dialog-date-row")
            val date = root.findViewWithTag<android.widget.Button>("imprint-date-value")
            val restore = root.findViewWithTag<android.widget.ImageButton>("imprint-restore-capture")
            assertEquals(row, date.parent); assertEquals(row, restore.parent)
            row.measure(View.MeasureSpec.makeMeasureSpec(224, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0,0,row.measuredWidth,row.measuredHeight)
            assertTrue(restore.left >= date.right)
            assertTrue(restore.right <= row.width)
            assertTrue(restore.width >= 48 && restore.height >= 48)
            assertTrue(date.width > 0)
            assertTrue(restore.contentDescription.isNotBlank())
            assertNotNull(restore.drawable)
            restore.performClick(); idle()
            assertNull(applied)
            assertTrue(date.text.contains("2026-11-05"))
            assertFalse(ImprintDialog.capture(dialog).getBoolean("imprint-manual"))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            assertNull(applied!!.manualDateTime)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun edgeSwitchDisablesCornerWithoutChangingSavedPreference() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val old = ImprintSettings(dateStyle="contax_t2", corner="top_left")
            var applied: ImprintSettings? = null
            val dialog = ImprintDialog.show(host.get(), medium.copy(imprint=old), metadata) { applied=it }
            idle()
            val corner = dialog.window!!.decorView.findViewWithTag<android.widget.Spinner>("imprint-corner")
            val label = dialog.window!!.decorView.findViewWithTag<View>("imprint-corner-label")
            assertTrue(corner.isEnabled); assertEquals(3, corner.selectedItemPosition)
            toggle(dialog).performClick(); idle()
            assertFalse(corner.isEnabled); assertTrue(corner.alpha < 1f)
            assertFalse(label.isEnabled); assertTrue(label.alpha < 1f)
            assertEquals(3, corner.selectedItemPosition)
            toggle(dialog).performClick(); idle()
            assertTrue(corner.isEnabled); assertEquals(1f, corner.alpha, 0f)
            assertEquals(3, corner.selectedItemPosition)
            toggle(dialog).performClick(); idle()
            val draft = ImprintDialog.capture(dialog); dialog.dismiss()
            val restored = ImprintDialog.show(host.get(), medium.copy(imprint=old), metadata, draft) { applied=it }
            idle()
            assertFalse(restored.window!!.decorView.findViewWithTag<android.widget.Spinner>("imprint-corner").isEnabled)
            restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle()
            assertEquals("top_left", applied!!.corner); assertEquals("edge", applied!!.datePlacement)
            // A retained edge preference in 135/no-border falls back to the photo, so the corner is editable.
            for (state in listOf(medium.copy(mediumFormat=null), medium.copy(filmBorder=false))) {
                val fallback = ImprintDialog.show(host.get(), state.copy(imprint=applied!!), metadata) {}
                idle()
                assertTrue(fallback.window!!.decorView.findViewWithTag<android.widget.Spinner>("imprint-corner").isEnabled)
                fallback.dismiss()
            }
        } finally { host.pause().stop().destroy() }
    }

}
