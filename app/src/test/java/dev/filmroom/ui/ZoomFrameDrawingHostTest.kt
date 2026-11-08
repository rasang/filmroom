package dev.filmroom.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZoomFrameDrawingHostTest {
    @Test fun zoomFrameUsesFullFrameBoundsAndNeverLeaksIntoOrdinaryView() {
        val base=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        val high=Bitmap.createBitmap(1800,1200,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.GREEN)}
        val view=PhotoView(ApplicationProvider.getApplicationContext())
        val output=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888)
        try {
            view.layout(0,0,300,200);view.bitmap=base;view.zoomFrame=high
            view.preImagingCompositionPreview=true;view.draw(Canvas(output))
            assertEquals(Color.GREEN,output.getPixel(150,100));assertEquals(Color.GREEN,output.getPixel(2,2))
            view.preImagingCompositionPreview=false;view.draw(Canvas(output))
            assertEquals(Color.BLUE,output.getPixel(150,100))
            view.preImagingCompositionPreview=true;view.zoomFrame=null;view.draw(Canvas(output))
            assertEquals(Color.BLUE,output.getPixel(150,100))
        } finally {view.close();base.recycle();high.recycle();output.recycle()}
    }
}
