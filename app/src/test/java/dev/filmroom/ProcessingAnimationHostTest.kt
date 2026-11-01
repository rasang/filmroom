package dev.filmroom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.filmroom.ui.ProcessingAnimationView
import dev.filmroom.ui.ProcessingVisualStage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProcessingAnimationHostTest {
    @Test fun usesBorrowedPhotoAndNeverRecyclesIt() {
        val view = ProcessingAnimationView(ApplicationProvider.getApplicationContext<Context>())
        val photo = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val out = Bitmap.createBitmap(280, 160, Bitmap.Config.ARGB_8888)
        view.layout(0, 0, 280, 160)
        view.setPhoto(photo)
        view.updateStage("保存照片")
        view.draw(Canvas(out))
        assertEquals("The actual preview, not a drawn landscape", Color.BLUE, out.getPixel(140, 70))
        view.setPhoto(null)
        assertFalse(photo.isRecycled)
        view.draw(Canvas(out))
        assertNotEquals(Color.BLUE, out.getPixel(140, 70))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.importantForAccessibility)
        photo.recycle(); out.recycle()
    }

    @Test fun recycledPreviewSafelyFallsBackAndDrawingNeverAdvancesStage() {
        val view = ProcessingAnimationView(ApplicationProvider.getApplicationContext<Context>())
        val photo = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        view.setPhoto(photo); photo.recycle()
        val out = Bitmap.createBitmap(280, 160, Bitmap.Config.ARGB_8888)
        view.layout(0, 0, 280, 160)
        view.updateStage("显影扩散 · 本遍 99% · 可取消")
        repeat(5) { view.draw(Canvas(out)) }
        assertEquals(ProcessingVisualStage.DEVELOPING, view.stage)
        view.updateStage("正在取消并清理临时文件")
        view.draw(Canvas(out))
        assertEquals(ProcessingVisualStage.CLEANING, view.stage)
        out.recycle()
    }
}
