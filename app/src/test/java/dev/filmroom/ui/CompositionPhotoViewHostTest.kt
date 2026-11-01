package dev.filmroom.ui

import android.app.Application
import android.graphics.Bitmap
import android.view.MotionEvent
import dev.filmroom.border.BorderSnapshot
import dev.filmroom.model.CompositionTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], application=Application::class)
class CompositionPhotoViewHostTest {
    private class Fixture(w:Int=600,h:Int=600):AutoCloseable {
        val snapshot=BorderSnapshot.fixed135("fixture_only","ui-135",w,h)
        val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val frame=Bitmap.createBitmap(snapshot.outputWidth,snapshot.outputHeight,Bitmap.Config.ARGB_8888)
        val view=PhotoView(RuntimeEnvironment.getApplication()).apply{layout(0,0,500,600);bitmap=frame}
        val events=mutableListOf<Pair<CompositionTransform,Boolean>>()
        var composition=CompositionTransform()
        fun bind(editing:Boolean){view.bindComposition(snapshot.copy(composition=composition.clamped(snapshot.canonicalWidth,snapshot.canonicalHeight)),source,composition,editing){t,commit->composition=t;events+=t to commit}}
        fun end(cancel:Boolean=false){val e=MotionEvent.obtain(0,1,if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,0f,0f,0);try{view.onTouchEvent(e)}finally{e.recycle()}}
        override fun close(){view.close();source.recycle();frame.recycle()}
    }
    @Test fun editFrameStaysFixedAndLockRestoresIndependentViewer(){Fixture().use{f->
        f.view.pinch(2.0,250.0,300.0);f.view.pan(45.0,-30.0)
        val viewer=f.view.viewerState();f.bind(true)
        assertEquals(1.0,f.view.viewport.zoom,0.0)
        val aperture=f.view.compositionAperture()!!
        f.view.pinch(2.0,aperture.centerX().toDouble(),aperture.centerY().toDouble())
        f.view.pan(30.0,-20.0)
        assertEquals(aperture,f.view.compositionAperture())
        assertEquals(2.0,f.composition.zoom,0.0)
        assertArrayEquals(viewer,f.view.viewerState(),1e-6f)
        assertEquals(0,f.events.count{it.second})
        f.bind(false)
        assertEquals(1,f.events.count{it.second})
        assertArrayEquals(viewer,f.view.viewerState(),1e-6f)
        val crop=f.composition;f.view.pan(-10.0,12.0);f.view.pinch(1.1,100.0,200.0)
        assertEquals(crop,f.composition)
    }}
    @Test fun cancelEndAndRepeatedLockCommitOnlyOnce(){Fixture().use{f->
        f.bind(true);f.view.pinch(2.0,250.0,300.0);f.view.pan(10.0,20.0)
        f.end(cancel=true);f.view.finishCompositionGesture();f.bind(false);f.bind(false)
        assertEquals(1,f.events.count{it.second})
        f.bind(true);f.end();assertEquals(1,f.events.count{it.second})
        f.view.pan(20.0,10.0);f.end();assertEquals(2,f.events.count{it.second})
        f.view.pan(10.0,10.0);f.view.setForeground(false);f.view.close()
        assertEquals(3,f.events.count{it.second})
    }}
    @Test fun normalizedViewerRestoresAcrossResizeAndRecreationWhileEditing(){Fixture().use{f->
        f.view.pinch(3.0,250.0,300.0);f.view.pan(20.0,40.0);val saved=f.view.viewerState()
        f.bind(true);f.view.layout(0,0,800,400);assertArrayEquals(saved,f.view.viewerState(),1e-6f)
        Fixture().use{g->
            g.view.layout(0,0,800,400);g.view.restoreViewerState(saved);g.bind(true)
            assertEquals(1.0,g.view.viewport.zoom,0.0);assertArrayEquals(saved,g.view.viewerState(),1e-6f)
            g.bind(false);assertArrayEquals(saved,g.view.viewerState(),1e-6f)
        }
    }}
    @Test fun portraitSquareAndExtremeSourceRemainCoveringApertureAtLimits(){
        for((w,h) in listOf(600 to 600,400 to 600,600 to 400,3000 to 100,100 to 3000))Fixture(w,h).use{f->
            f.bind(true)
            for(factor in listOf(100.0,.001,16.0)){
                f.view.pinch(factor,0.0,0.0);f.view.pan(1e6,-1e6)
                val a=f.view.compositionAperture()!!;val p=f.view.compositionPhotoRect()!!
                assertTrue(p.left<=a.left+.01);assertTrue(p.top<=a.top+.01)
                assertTrue(p.right>=a.right-.01);assertTrue(p.bottom>=a.bottom-.01)
                assertTrue(f.composition.zoom in 1.0..16.0)
                assertEquals(if(w>=h)1.5 else 2.0/3.0,a.width().toDouble()/a.height(),.01)
            }
        }
    }
    @Test fun apertureDemandUsesCanonicalCropBeyondViewerZoomCeiling(){Fixture().use{f->
        val key=dev.filmroom.roi.RenderKey("source","params","algorithm","data","oriented",1536,1536)
        f.bind(true)
        val fit=f.view.compositionVisibleTiles(key)
        assertTrue(fit.isNotEmpty())
        f.view.pinch(16.0,250.0,300.0);f.view.pan(1e6,-1e6)
        assertEquals("ROI must track displayed composition, not a pending gesture",fit,f.view.compositionVisibleTiles(key))
        f.bind(true)
        val demand=f.view.compositionVisibleTiles(key)
        assertTrue(demand.isNotEmpty());assertTrue(demand.size<fit.size)
        assertTrue(f.view.compositionDetailZoom()>6.0)
        assertEquals(1.0,f.view.viewport.zoom,0.0)
        for(tile in demand){assertTrue(tile.x in 0..5);assertTrue(tile.y in 0..5)}
        // Crop moved to source's left/bottom, not frame-art coordinates.
        assertTrue(demand.all{it.x==0 && it.y==5})
    }}
    @Test fun framedRoiUsesCropDemandAndReusesPresentationFreeSession(){Fixture().use{f->
        val seen=mutableListOf<dev.filmroom.model.EditorState>()
        val sessions=mutableListOf<dev.filmroom.roi.EditorDetailControllerTest.FakeSession>()
        f.view.configureDetail(dev.filmroom.roi.EditorDetailProvider{source,params->
            seen+=params
            dev.filmroom.roi.EditorDetailControllerTest.FakeSession(dev.filmroom.roi.RenderKey(source.cacheKey,params.json(),"a","d","oriented",1536,1536)).also{sessions+=it}
        })
        val source=dev.filmroom.io.PhotoSource(java.io.File("unused"),"ui-crop-fixture","image/jpeg",600,600,0)
        f.view.bindDetail(source,dev.filmroom.model.EditorState(filmBorder=true),true,presentedBorder=true)
        f.bind(true);f.view.pinch(16.0,250.0,300.0);f.view.pan(1e6,-1e6);f.end();f.bind(true)
        fun await(done:()->Boolean){repeat(1000){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10));if(done())return;Thread.sleep(2)};error("ROI timed out")}
        await{f.view.detailCacheBytes>0}
        assertEquals(1,seen.size);assertFalse(seen.single().filmBorder);assertEquals(CompositionTransform(),seen.single().composition)
        assertTrue(sessions.single().calls.all{it.first==0 && it.second==5})
        f.view.pan(-20.0,10.0);f.end()
        f.view.bindDetail(source,dev.filmroom.model.EditorState(filmBorder=true,composition=f.composition),true,presentedBorder=true)
        f.bind(true)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        repeat(12){
            f.bind(false);f.bind(true);f.view.pan(if(it%2==0)-3.0 else 3.0,0.0);f.end();f.bind(true)
            f.view.bindDetail(source,dev.filmroom.model.EditorState(filmBorder=true,composition=f.composition),true,presentedBorder=true)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        assertEquals("Composition/mode changes must not reopen scientific context",1,seen.size)
        assertEquals("Composition/mode changes must not close scientific context",0,sessions.count{it.closed})
        val science=dev.filmroom.model.EditorState(filmBorder=true,composition=f.composition,brightness=.1f)
        f.view.bindDetail(source,science,true,presentedBorder=true)
        await{seen.size==2 && sessions.first().closed && f.view.detailCacheBytes>0}
        assertEquals(1,sessions.count{it.closed})
        f.view.bindDetail(source,science.copy(filmStock="kodak_portra_160"),true,presentedBorder=true)
        await{seen.size==3 && sessions[1].closed && f.view.detailCacheBytes>0}
        assertEquals(2,sessions.count{it.closed})
        f.view.bindDetail(source,science.copy(filmStock="kodak_portra_160"),true,compareOriginal=true,presentedBorder=false)
        assertNull(f.view.detailKey)
    }}
    @Test fun compareOrMissingPresentationExitsEditWithoutMutatingViewerGestures(){Fixture().use{f->
        f.bind(true);f.view.pinch(2.0,200.0,200.0)
        f.view.bindComposition(null,f.source,f.composition,true){t,commit->f.events+=t to commit}
        assertFalse(f.view.isCompositionEditing);assertEquals(1,f.events.count{it.second})
        val crop=f.composition;f.view.pan(5.0,5.0);assertEquals(crop,f.composition)
    }}
}
