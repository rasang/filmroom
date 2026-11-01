package dev.filmroom.ui

import android.app.Application
import android.graphics.*
import android.view.MotionEvent
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.model.CompositionTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveCompositionPhotoViewHostTest {
    private class Fixture:AutoCloseable {
        val s=BorderSnapshot.fixed135("fixture_only","live",300,300)
        val source=Bitmap.createBitmap(300,300,Bitmap.Config.ARGB_8888).apply {
            val pixels=IntArray(90000){i->Color.rgb(i%300*255/299,i/300*255/299,30)}
            setPixels(pixels,0,300,0,0,300,300)
        }
        val exact=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        val overlay=Bitmap.createBitmap(s.outputWidth,s.outputHeight,Bitmap.Config.ARGB_8888).apply{
            eraseColor(Color.MAGENTA)
            Canvas(this).drawRect(s.apertureLeft.toFloat(),s.apertureTop.toFloat(),(s.apertureLeft+s.photoWidth).toFloat(),(s.apertureTop+s.photoHeight).toFloat(),Paint().apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.CLEAR)})
        }
        val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,s.outputWidth,s.outputHeight);bitmap=exact}
        var latest=CompositionTransform();var changes=0;var commits=0
        fun bind(snapshot:BorderSnapshot=s,editing:Boolean=true,src:Bitmap?=source,frame:Bitmap?=overlay,requested:CompositionTransform=latest){
            view.bindComposition(snapshot,src,requested,editing,frame){t,end->latest=t;changes++;if(end)commits++}
        }
        fun draw()=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also{view.draw(Canvas(it))}
        fun touch(action:Int,x:Float,y:Float){val e=MotionEvent.obtain(0,20,action,x,y,0);try{view.onTouchEvent(e)}finally{e.recycle()}}
        override fun close(){view.close();source.recycle();overlay.recycle();exact.recycle()}
    }
    @Test fun moveDrawsImmediatelyWithoutExactRenderAndFrameIsFixed(){Fixture().use{f->
        f.bind();f.view.pinch(2.0,150.0,150.0)
        val before=f.draw();val originalExact=f.view.bitmap
        f.touch(MotionEvent.ACTION_DOWN,150f,150f);f.touch(MotionEvent.ACTION_MOVE,180f,150f)
        val after=f.draw();assertTrue(f.view.isLiveComposition)
        val a=f.view.compositionAperture()!!;val x=a.centerX().toInt();val y=a.centerY().toInt()
        assertNotEquals(before.getPixel(x,y),after.getPixel(x,y))
        assertEquals(Color.MAGENTA,after.getPixel(0,0));assertEquals(before.getPixel(0,0),after.getPixel(0,0))
        assertSame("MOVE never allocates/replaces canonical bitmap",originalExact,f.view.bitmap)
        assertEquals(0,f.commits);before.recycle();after.recycle()
    }}
    @Test fun pendingUpLockAndStaleCompletionDoNotSnapBackThenExactHandsOff(){Fixture().use{f->
        f.bind();f.view.pinch(2.0,150.0,150.0);f.view.pan(20.0,0.0)
        f.touch(MotionEvent.ACTION_UP,170f,150f);assertTrue(f.view.isLiveComposition)
        val pending=f.draw();f.bind(editing=false)
        val locked=f.draw();assertTrue(f.view.isLiveComposition)
        assertEquals(pending.getPixel(150,150),locked.getPixel(150,150))
        val old=f.latest;f.bind(editing=true);f.view.pan(-30.0,10.0);f.touch(MotionEvent.ACTION_UP,140f,160f)
        f.bind(snapshot=f.s.copy(composition=old));assertTrue(f.view.isLiveComposition)
        f.bind(snapshot=f.s.copy(composition=f.latest));assertFalse(f.view.isLiveComposition)
        val settled=f.draw();assertEquals(Color.BLUE,settled.getPixel(150,150))
        assertEquals(2,f.commits);pending.recycle();locked.recycle();settled.recycle()
    }}
    @Test fun staleParameterBindDoesNotUndoLocalMoveAndLiveRoiTracksCrop(){Fixture().use{f->
        f.bind();val key=dev.filmroom.roi.RenderKey("s","p","a","d","o",1536,1536)
        val full=f.view.compositionVisibleTiles(key)
        f.view.pinch(16.0,150.0,150.0);f.view.pan(1e6,-1e6)
        val rect=f.view.compositionPhotoRect();val demand=f.view.compositionVisibleTiles(key)
        assertTrue(demand.size<full.size)
        f.bind(requested=CompositionTransform());assertEquals(rect,f.view.compositionPhotoRect())
        assertTrue(f.view.isLiveComposition);assertEquals(demand,f.view.compositionVisibleTiles(key))
    }}
    @Test fun sourceAssetOffAndMissingOverlayClearLiveStateWithoutOwningBitmaps(){Fixture().use{f->
        f.bind();f.view.pinch(2.0,150.0,150.0);assertTrue(f.view.isLiveComposition)
        val replacement=f.source.copy(Bitmap.Config.ARGB_8888,false)
        f.bind(src=replacement);assertFalse(f.view.isLiveComposition);assertFalse(f.source.isRecycled)
        f.view.pan(5.0,0.0);assertTrue(f.view.isLiveComposition)
        f.bind(snapshot=f.s.copy(assetVersion="other"));assertFalse(f.view.isLiveComposition)
        f.view.pan(5.0,0.0);f.bind(frame=null);assertFalse(f.view.isLiveComposition)
        f.bind();f.view.pan(5.0,0.0)
        f.view.bindComposition(null,null,f.latest,false,null){_,_->}
        assertFalse(f.view.isLiveComposition);assertFalse(f.view.isCompositionEditing)
        assertFalse(f.overlay.isRecycled);replacement.recycle()
    }}
    @Test fun recreatedViewRestoresPendingCropImmediatelyWithoutRenderOrGestureCallback(){Fixture().use{f->
        f.bind();f.view.pinch(2.0,150.0,150.0);f.view.pan(20.0,10.0)
        f.touch(MotionEvent.ACTION_UP,170f,160f);assertTrue(f.view.isLiveComposition)
        for(editing in listOf(true,false)){
            f.bind(editing=editing)
            val expected=f.draw();var callbacks=0
            val recreated=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,f.view.width,f.view.height);bitmap=f.exact}
            try{
                recreated.bindComposition(f.s,f.source,f.latest,editing,f.overlay){_,_->callbacks++}
                assertTrue(recreated.isLiveComposition);assertEquals(editing,recreated.isCompositionEditing)
                assertEquals(f.view.compositionPhotoRect(),recreated.compositionPhotoRect())
                val actual=Bitmap.createBitmap(recreated.width,recreated.height,Bitmap.Config.ARGB_8888)
                try{recreated.draw(Canvas(actual));assertTrue("Pending pixels survive recreation without MOVE",expected.sameAs(actual))}finally{actual.recycle()}
                assertEquals(0,callbacks)
                recreated.bindComposition(f.s,f.source,f.latest.copy(targetAspect=2.0/3),editing,f.overlay){_,_->callbacks++}
                assertFalse(recreated.compositionGeometryReady);assertFalse(recreated.isLiveComposition);assertEquals(0,callbacks)
            }finally{recreated.close();expected.recycle()}
        }
    }}

}
