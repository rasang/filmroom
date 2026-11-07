package dev.filmroom

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.io.PhotoSource
import dev.filmroom.model.CompositionTransform
import dev.filmroom.model.EditorState
import dev.filmroom.ui.PreviewHistogramInput
import dev.filmroom.ui.PreviewHistogramSampler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
class PreImagingHistogramHostTest {
    @Test fun framedScientificPixelsUseFixedApertureNotZoomedSourceCrop()=runBlocking {
        val params=EditorState(filmBorder=true,filmBorderStage=1,composition=CompositionTransform(.65,.35,2.0))
        val border=BorderSnapshot.fixed135(params.frameStock,"histogram-fixture",96,64,96,64,params.composition)
        val frame=Bitmap.createBitmap(border.outputWidth,border.outputHeight,Bitmap.Config.ARGB_8888)
        val original=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888)
        try {
            frame.eraseColor(Color.WHITE);original.eraseColor(Color.BLACK)
            val left=floor(border.apertureLeft).toInt();val top=floor(border.apertureTop).toInt()
            val right=ceil(border.apertureLeft+border.photoWidth).toInt()
            val bottom=ceil(border.apertureTop+border.photoHeight).toInt()
            for(y in top until bottom)for(x in left until right)frame.setPixel(x,y,Color.rgb(80,80,80))
            val state=EditorUiState(photo=PhotoSource(File("unused"),"histogram","image/jpeg",96,64,0),
                original=original,rendered=frame,compositionSource=frame,presentation=border,
                parameters=params,renderedParameters=params)
            val input=checkNotNull(PreviewHistogramInput.from(state))
            assertSame(frame,input.bitmap)
            assertEquals(left,input.left);assertEquals(top,input.top)
            assertEquals(right,input.right);assertEquals(bottom,input.bottom)
            val sampled=checkNotNull(PreviewHistogramSampler.sample(input))
            assertEquals(sampled.samples,sampled.bins[40]);assertEquals(0,sampled.bins[127])
            // Pending stage transitions must not publish statistics for the old frame.
            assertNull(PreviewHistogramInput.from(state.copy(parameters=params.copy(filmBorderStage=0))))
            val compare=checkNotNull(PreviewHistogramInput.from(state.copy(compare=true)))
            assertSame(original,compare.bitmap)
            assertEquals(0,compare.left);assertEquals(0,compare.top)
            assertEquals(96,compare.right);assertEquals(64,compare.bottom)
            assertEquals(0,checkNotNull(PreviewHistogramSampler.sample(compare)).bins[127])
        } finally {frame.recycle();original.recycle()}
    }
}
