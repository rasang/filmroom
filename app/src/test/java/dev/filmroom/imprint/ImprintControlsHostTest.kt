package dev.filmroom.imprint

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import dev.filmroom.R
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w400dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImprintControlsHostTest {
    private val metadata=PhotoMetadata.fromAttributes(mapOf(
        "DateTimeOriginal" to "2026:11:05 12:34:56", "PhotographicSensitivity" to "400", "ExposureTime" to "1/250"))
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    private fun AlertDialog.toggle(tag:String)=window!!.decorView.findViewWithTag<Switch>(tag)
    private fun AlertDialog.view(tag:String)=window!!.decorView.findViewWithTag<View>(tag)
    private inline fun host(block:(Activity)->Unit) {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        try { block(host.get()) } finally { host.pause().stop().destroy() }
    }

    @Test fun openingAndApplyingDisabledDefaultsNeverInventsDateOrChangesPreferences()=host { a ->
        var preview:ImprintSettings?=null
        var applied:ImprintSettings?=null
        val old=ImprintSettings()
        val d=ImprintDialog.show(a,EditorState(imprint=old),metadata,onPreview={preview=it}) { applied=it }
        idle()
        assertEquals(old,preview)
        assertTrue(ImprintPlan.create(preview!!,metadata,400,300).isEmpty)
        assertEquals(View.GONE,d.view("imprint-date-settings").visibility)
        assertEquals(View.GONE,d.view("imprint-exposure-preview").visibility)
        assertNull(d.window!!.decorView.findViewWithTag<View>("imprint-content"))
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertEquals(old,applied);assertNull(preview)
    }

    @Test fun topSwitchesHaveIndependentConfigurationsAndCommitTogether()=host { a ->
        var preview:ImprintSettings?=null
        var applied:ImprintSettings?=null
        val old=ImprintSettings()
        val state=EditorState(filmBorder=true,imprint=old)
        val d=ImprintDialog.show(a,state,metadata,onPreview={preview=it}) { applied=it };idle()
        val exposure=d.toggle("imprint-exposure-switch");val date=d.toggle("imprint-date-switch")
        val root=exposure.parent as ViewGroup
        assertSame(root,date.parent);assertEquals(0,root.indexOfChild(exposure))
        assertTrue(root.indexOfChild(exposure)<root.indexOfChild(date))
        exposure.performClick();idle()
        assertTrue(preview!!.exposure);assertFalse(preview!!.dateEnabled)
        assertEquals(View.VISIBLE,d.view("imprint-exposure-preview").visibility)
        assertEquals(View.GONE,d.view("imprint-date-settings").visibility)
        date.performClick();idle()
        assertTrue(preview!!.dateEnabled)
        assertEquals(View.VISIBLE,d.view("imprint-date-settings").visibility)
        exposure.performClick();idle()
        assertFalse(preview!!.exposure);assertTrue(preview!!.dateEnabled)
        assertEquals(View.GONE,d.view("imprint-exposure-preview").visibility)
        assertNull(applied);assertEquals(old,state.imprint)
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertTrue(applied!!.dateEnabled);assertFalse(applied!!.exposure)
    }

    @Test fun cancelAndBackRestoreBothSwitchesAndAllExistingPreferences()=host { a ->
        val old=ImprintSettings("canon_luna","top_left",1.31,.29,true,"edge",dateEnabled=false,manualDateTime="2001-02-03 04:05:06")
        val state=EditorState(filmBorder=true,mediumFormat=MediumFormat.MF66,imprint=old)
        repeat(2) { pass ->
            var applied:ImprintSettings?=null;var preview:ImprintSettings?=null
            val d=ImprintDialog.show(a,state,metadata,onPreview={preview=it}){applied=it};idle()
            d.toggle("imprint-exposure-switch").performClick()
            d.toggle("imprint-date-switch").performClick()
            val group=d.window!!.decorView.findViewWithTag<RadioGroup>("imprint-style")
            group.check(group.getChildAt(1).id)
            d.toggle("imprint-placement").performClick()
            d.window!!.decorView.findViewWithTag<SeekBar>("imprint-size").progress=17
            assertNotEquals(old,preview)
            if(pass==0)d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() else d.onBackPressed()
            idle();assertNull(preview);assertNull(applied);assertEquals(old,state.imprint)
        }
    }

    @Test fun draftSwitchesAndHiddenSettingsSurviveRecreationWithoutCommitting()=host { a ->
        val old=ImprintSettings("yashica_t4","top_left",1.43,.27,true,"edge",dateEnabled=true,manualDateTime="2001-02-03 04:05:06")
        val state=EditorState(filmBorder=true,mediumFormat=MediumFormat.MF66,imprint=old)
        var applied:ImprintSettings?=null
        val d=ImprintDialog.show(a,state,metadata){applied=it};idle()
        d.toggle("imprint-date-switch").performClick();d.toggle("imprint-exposure-switch").performClick()
        val draft=ImprintDialog.capture(d);d.dismiss();assertNull(applied)
        var preview:ImprintSettings?=null
        val restored=ImprintDialog.show(a,state,metadata,draft,onPreview={preview=it}){applied=it};idle()
        assertFalse(restored.toggle("imprint-date-switch").isChecked)
        assertFalse(restored.toggle("imprint-exposure-switch").isChecked)
        assertEquals(old.copy(dateEnabled=false,exposure=false),preview)
        assertTrue(ImprintPlan.create(preview!!,metadata,400,300).isEmpty)
        restored.toggle("imprint-date-switch").performClick();idle()
        assertEquals(View.VISIBLE,restored.view("imprint-date-settings").visibility)
        assertTrue(restored.toggle("imprint-placement").isChecked)
        assertFalse(restored.view("imprint-size").isEnabled)
        assertFalse(restored.view("imprint-corner").isEnabled)
        assertEquals(old.copy(exposure=false),preview)
        restored.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertEquals(old.copy(exposure=false),applied)
    }

    @Test fun legacyPartialDateDraftCanBeDisabledWithoutBlockingExposureEdits()=host { a ->
        val state=EditorState(filmBorder=true,imprint=ImprintSettings("contax_t2",dateEnabled=true))
        val draft=Bundle().apply { putString("imprint-date","invalid");putBoolean("imprint-manual",true) }
        var applied:ImprintSettings?=null;var preview:ImprintSettings?=null
        val d=ImprintDialog.show(a,state,metadata,draft,onPreview={preview=it}){applied=it};idle()
        assertFalse(preview!!.dateEnabled)
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle();assertTrue(d.isShowing)
        d.toggle("imprint-date-switch").performClick()
        d.toggle("imprint-exposure-switch").performClick()
        assertTrue(preview!!.exposure);assertFalse(preview!!.dateEnabled)
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();idle()
        assertFalse(d.isShowing);assertTrue(applied!!.exposure);assertFalse(applied!!.dateEnabled)
    }

    @Test fun exposureOffHidesDataEvenWhenDateEnabledAndSourceHasNoCaptureData()=host { a ->
        val d=ImprintDialog.show(a,EditorState(filmBorder=true,imprint=ImprintSettings(dateEnabled=true)),PhotoMetadata.unavailable()){};idle()
        assertEquals(View.GONE,d.view("imprint-exposure-preview").visibility)
        d.toggle("imprint-exposure-switch").performClick();idle()
        assertEquals(ImprintText.NO_EXPOSURE.text(a),(d.view("imprint-exposure-preview") as android.widget.TextView).text)
        assertNull(d.window!!.decorView.findViewWithTag<View>("imprint-content"));d.dismiss()
    }

    @Test fun chosenCalendarGearIconIsNativeTintableAndBoundedAtSmallSizes()=host { a ->
        val drawable=a.getDrawable(R.drawable.ic_time_date_settings)!!
        assertTrue(drawable is android.graphics.drawable.VectorDrawable)
        assertEquals(24,drawable.intrinsicWidth);assertEquals(24,drawable.intrinsicHeight)
        for(size in listOf(18,20,24,48)) {
            val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            drawable.setBounds(0,0,size,size);drawable.setTint(0xffe6aa65.toInt());drawable.draw(Canvas(bitmap))
            val pixels=IntArray(size*size);bitmap.getPixels(pixels,0,size,0,0,size,size)
            assertTrue(pixels.any{it ushr 24>0})
            assertTrue("The outline stays inside its target",(0 until size).all { pixels[it]==0 && pixels[(size-1)*size+it]==0 })
            System.getProperty("filmroom.imprint.evidenceDir")?.let { dir ->
                File(dir).mkdirs();File(dir,"time-date-icon-${size}px.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            bitmap.recycle()
        }
    }
}
