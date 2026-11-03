package dev.filmroom

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.Button
import dev.filmroom.model.EditorState
import dev.filmroom.ui.FilmroomPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NeutralPaletteHostTest {
    @Test fun chromeIsNeutralAndOpaqueEnabledTextRetainsContrast() {
        val p=FilmroomPalette
        listOf(p.background,p.surface,p.raised,p.inset,p.ink,p.muted,p.ruler,p.divider,p.track,p.onAccent).forEach {
            assertEquals(Color.red(it),Color.green(it));assertEquals(Color.green(it),Color.blue(it))
        }
        assertEquals(Color.rgb(24,24,24),p.background)
        assertEquals(Color.rgb(34,34,34),p.surface)
        assertEquals(Color.rgb(44,44,44),p.raised)
        assertEquals(Color.rgb(229,163,93),p.accent)
        fun luminance(c:Int):Double {
            fun linear(v:Int):Double {val x=v/255.0;return if(x<=.04045)x/12.92 else ((x+.055)/1.055).pow(2.4)}
            return .2126*linear(Color.red(c))+.7152*linear(Color.green(c))+.0722*linear(Color.blue(c))
        }
        fun ratio(a:Int,b:Int):Double {val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05)}
        for(fg in listOf(p.ink,p.muted,p.accent))for(bg in listOf(p.background,p.surface,p.raised))assertTrue(ratio(fg,bg)>=4.5)
        assertTrue(ratio(p.onAccent,p.accent)>=4.5)
    }
    @Test fun settingsAndQueueUseTheSameNeutralBackground() {
        CameraLayoutRenderHostTest().chosenLanguageForLegacyFixture()
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { c ->
            val root=c.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            assertEquals(FilmroomPalette.background,(root.background as ColorDrawable).color)
        }
        Robolectric.buildActivity(ExportQueueActivity::class.java).setup().use { c ->
            val root=c.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            assertEquals(FilmroomPalette.background,(root.background as ColorDrawable).color)
        }
    }
    @Test fun compareIconUsesDarkOnOrangeAndRestoresLightOnNeutral() {
        val helpers=CameraLayoutRenderHostTest();helpers.chosenLanguageForLegacyFixture()
        val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        Robolectric.buildActivity(MainActivity::class.java).setup().use { c ->
            val a=c.get()
            CameraLayoutRenderHostTest::class.java.getDeclaredMethod("installFixture",MainActivity::class.java,Bitmap::class.java,EditorState::class.java).apply{isAccessible=true}.invoke(helpers,a,bitmap,EditorState())
            val compare=MainActivity::class.java.getDeclaredField("compare").apply{isAccessible=true}.get(a) as Button
            fun assertTint(expected:Int) {
                val d=compare.compoundDrawables.filterNotNull().single()
                val bounds=d.bounds
                val pixels=Bitmap.createBitmap(bounds.width(),bounds.height(),Bitmap.Config.ARGB_8888)
                val canvas=Canvas(pixels);canvas.translate(-bounds.left.toFloat(),-bounds.top.toFloat());d.draw(canvas)
                val values=IntArray(pixels.width*pixels.height);pixels.getPixels(values,0,pixels.width,0,0,pixels.width,pixels.height);pixels.recycle()
                val solid=values.filter{Color.alpha(it)==255};assertTrue("Icon has opaque stroke pixels",solid.isNotEmpty())
                assertTrue("Every opaque icon pixel has the expected tint",solid.all{it==expected})
            }
            assertFalse(compare.isSelected);assertTint(FilmroomPalette.ink)
            repeat(2){
                compare.performClick();shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(compare.isSelected);assertTint(FilmroomPalette.onAccent)
                compare.performClick();shadowOf(android.os.Looper.getMainLooper()).idle()
                assertFalse(compare.isSelected);assertTint(FilmroomPalette.ink)
            }
        }
    }
}
