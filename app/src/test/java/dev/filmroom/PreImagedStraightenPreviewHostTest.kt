package dev.filmroom

import android.graphics.*
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.model.CompositionTransform
import dev.filmroom.ui.PhotoView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreImagedStraightenPreviewHostTest {
    private fun frame(s:BorderSnapshot)=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888).apply{
        val c=Canvas(this);c.drawColor(Color.RED);val p=Paint().apply{color=Color.rgb(20,140,100)}
        c.drawRect(s.apertureLeft.toFloat(),s.apertureTop.toFloat(),(s.apertureLeft+s.photoWidth).toFloat(),(s.apertureTop+s.photoHeight).toFloat(),p)
        p.color=Color.rgb(30,60,160);c.drawRect(s.apertureLeft.toFloat(),(s.apertureTop+s.photoHeight*.5).toFloat(),(s.apertureLeft+s.photoWidth).toFloat(),(s.apertureTop+s.photoHeight).toFloat(),p)
        p.color=Color.YELLOW;c.drawCircle((s.apertureLeft+s.photoWidth*.3).toFloat(),(s.apertureTop+s.photoHeight*.3).toFloat(),12f,p)
    }
    @Test fun imagedDeltaMovesPhotoKeepsFrameAndWaitsForExactAdoption(){
        val before=CompositionTransform();val snapshot=BorderSnapshot.fixed135("fixture","v",240,160,composition=before)
        val source=frame(snapshot);val view=PhotoView(RuntimeEnvironment.getApplication());view.layout(0,0,500,500)
        view.preImagingCompositionPreview=true;view.bitmap=source
        val events=mutableListOf<Pair<CompositionTransform,Boolean>>()
        val change:(CompositionTransform,Boolean)->Unit={t,c->events+=t to c}
        view.bindComposition(snapshot,source,before,true,null,change)
        fun draw()=Bitmap.createBitmap(500,500,Bitmap.Config.ARGB_8888).also{view.draw(Canvas(it))}
        val first=draw();view.straighten(12.0,false);assertTrue(view.isLiveComposition)
        val live=draw();val aperture=view.compositionAperture()!!;var changed=0
        for(y in 0 until 500)for(x in 0 until 500){
            if(!aperture.contains(x+.5f,y+.5f))assertEquals("Frame changed at $x,$y",first.getPixel(x,y),live.getPixel(x,y))
            else if(x>aperture.left+5 && x<aperture.right-5 && y>aperture.top+5 && y<aperture.bottom-5){
                assertNotEquals("Frame art entered photo",Color.RED,live.getPixel(x,y))
                if(first.getPixel(x,y)!=live.getPixel(x,y))changed++
            }
        }
        assertTrue("No live feedback",changed>1000)
        view.finishCompositionGesture();assertEquals(1,events.count{it.second});val requested=events.last().first
        view.bindComposition(snapshot,source,requested,false,null,change);assertTrue("Keep delta while exact render is pending",view.isLiveComposition)
        val adopted=BorderSnapshot.fixed135("fixture","v",240,160,composition=requested);val exact=frame(adopted)
        view.bitmap=exact;view.bindComposition(adopted,exact,requested,false,null,change);assertFalse(view.isLiveComposition)
        view.preImagingCompositionPreview=false;view.bindComposition(null,null,before,false,null,change);assertFalse(view.isLiveComposition)
        first.recycle();live.recycle();view.close();source.recycle();exact.recycle()
    }
}
