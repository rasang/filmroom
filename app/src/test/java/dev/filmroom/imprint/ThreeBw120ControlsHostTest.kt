package dev.filmroom.imprint

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.widget.Switch
import android.widget.TextView
import dev.filmroom.border.*
import dev.filmroom.io.PhotoMetadata
import dev.filmroom.model.EditorState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ThreeBw120ControlsHostTest {
    @Test fun everyCurrent120FormatHasARegisteredRendererLayout() {
        for(stock in MediumFormatStocks.profiles.keys)for(format in MediumFormat.entries) {
            val route=if(FrameProfileCatalog.kind(stock)==FrameAppearanceContext.FilmKind.REVERSAL)"direct" else "print"
            val b=BorderCatalog.snapshot(stock,1280,960,1280,960,dev.filmroom.model.CompositionTransform(),true,route,format,false)
            assertNotNull("Missing 120 imprint for $stock/$format",MediumImprintMarks.layout(b))
            assertTrue(ImprintPlan.exposureSupported(b))
        }
    }
    @Test fun allFiveNewFilmDialogsMatchActual135And120RendererCapabilities() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        val metadata=PhotoMetadata.fromAttributes(mapOf("PhotographicSensitivity" to "400","DateTimeOriginal" to "2026:11:07 12:34:56"))
        try {
            for(stock in dev.filmroom.model.FilmProfileCapabilities.spektraLabFilms)
                for(format in listOf(null)+MediumFormat.entries)for(route in listOf("print","direct")) {
                    val p=EditorState(filmStock=stock,filmBorder=true,mediumFormat=format,outputRoute=route,
                        imprint=ImprintSettings("contax_t2",exposure=true,datePlacement="edge"))
                    var saved:ImprintSettings?=null
                    val dialog=ImprintDialog.show(host.get(),p,metadata){saved=it}
                    shadowOf(Looper.getMainLooper()).idle()
                    val root=dialog.window!!.decorView
                    assertTrue(root.findViewWithTag<Switch>("imprint-exposure-switch").isEnabled)
                    assertEquals(if(format==null)View.GONE else View.VISIBLE,root.findViewWithTag<Switch>("imprint-placement").visibility)
                    assertTrue(root.findViewWithTag<TextView>("imprint-exposure-preview").text.contains("400"))
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(p.imprint,saved)
                }
        } finally {host.pause().stop().destroy()}
    }
    @Test fun unsupportedFrameKeepsLatentPreferenceWithoutOfferingADeadControl() {
        val host=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            for(p in listOf(EditorState(filmStock="kodak_trix",outputRoute="direct",filmBorder=true),EditorState(filmBorder=false))) {
                val old=ImprintSettings("contax_t2",exposure=true,datePlacement="edge")
                var saved:ImprintSettings?=null
                val dialog=ImprintDialog.show(host.get(),p.copy(imprint=old),PhotoMetadata.fromAttributes(mapOf("DateTimeOriginal" to "2026:11:07 12:34:56"))){saved=it}
                shadowOf(Looper.getMainLooper()).idle()
                    val root=dialog.window!!.decorView
                val toggle=root.findViewWithTag<Switch>("imprint-exposure-switch")
                assertFalse(toggle.isEnabled);assertTrue(toggle.isChecked)
                assertEquals(View.GONE,root.findViewWithTag<Switch>("imprint-placement").visibility)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                assertEquals(old,saved)
            }
        } finally {host.pause().stop().destroy()}
    }
}
