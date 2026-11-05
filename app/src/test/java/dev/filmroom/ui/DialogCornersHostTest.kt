package dev.filmroom.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.TypedValue
import dev.filmroom.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Dialog contours should not depend on screen size or the device's day/night preference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,35], application=Application::class)
class DialogCornersHostTest {
    private fun assertRadius(context: Context) {
        val value=TypedValue()
        assertTrue(context.theme.resolveAttribute(android.R.attr.dialogCornerRadius,value,true))
        assertEquals(context.resources.getDimension(R.dimen.filmroom_dialog_corner_radius),
            value.getDimension(context.resources.displayMetrics),0.01f)
    }
    private fun verifyDialogs() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setTheme(R.style.AppTheme) }
        try {
            var applied=0
            repeat(2) {
                val dialog=AlertDialog.Builder(activity).setTitle("Filmroom")
                    .setMessage("Dialog contour")
                    .setPositiveButton("Apply") { _,_-> applied++ }
                    .setNegativeButton("Cancel",null).show()
                assertRadius(dialog.context)
                assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(dialog.isShowing)
            }
            assertEquals(0,applied)
            for(theme in listOf(0,R.style.ImprintCompactPickerTheme)) {
                val picker=DatePickerDialog(activity,theme,null,2026,10,5)
                picker.show()
                assertRadius(picker.context)
                picker.cancel()
                assertFalse(picker.isShowing)
            }
            val custom=activity.getDrawable(R.drawable.filmroom_dialog_background) as GradientDrawable
            assertEquals(activity.resources.getDimension(R.dimen.filmroom_dialog_corner_radius),custom.cornerRadius,0.01f)
            assertEquals(activity.getColor(R.color.filmroom_surface),custom.color!!.defaultColor)
        } finally { controller.pause().stop().destroy() }
    }
    @Test @Config(qualifiers="w320dp-h480dp-notnight")
    fun smallLightDeviceUsesSharedContour()=verifyDialogs()
    @Test @Config(qualifiers="w840dp-h720dp-night")
    fun largeDarkDeviceUsesSharedContour()=verifyDialogs()
}
