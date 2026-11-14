package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class HomeMotionViewportTest {
    @Test fun recreationRestoresZoomAndBothOffsetsAfterSurfaceConfigure() {
        val before=HomeMotionViewport();before.configure(1000,1400,1000,1000)
        before.zoomBy(3f,450f,750f);before.pan(120f,-70f)
        val after=HomeMotionViewport();after.restore(before.save())
        assertEquals(before.save(),after.save()) // pause again before first GL frame
        after.configure(1000,1400,1000,1000)
        assertEquals(before.scale(),after.scale(),0f);assertEquals(before.frame(),after.frame())
    }
    @Test fun restoredStateHonorsNewHardwareBoundsAndCanBeReset() {
        val v=HomeMotionViewport();v.restore(HomeMotionViewport.State(4f,10f,-10f))
        v.configure(1000,1000,1000,1000,2000,2000)
        assertEquals(2f,v.scale(),0f);assertEquals(HomeMotionViewport.Frame(0,0,2000,2000),v.frame())
        v.restore(HomeMotionViewport.State(3f,.1f,.1f));v.reset();v.configure(1000,1000,1000,1000)
        assertEquals(1f,v.scale(),0f)
    }
    @Test fun baseSceneIsCenteredWithoutStretching() {
        val v=HomeMotionViewport();v.configure(1080,1600,1080,720)
        assertEquals(HomeMotionViewport.Frame(0,440,1080,720),v.frame())
    }
    @Test fun doubleTapAndResetTransformWholeViewport() {
        val v=HomeMotionViewport();v.configure(1000,1000,1000,800)
        v.doubleTap(500f,500f)
        assertEquals(HomeMotionViewport.Frame(-500,-300,2000,1600),v.frame())
        v.doubleTap(500f,500f);assertEquals(1f,v.scale(),0f)
        assertEquals(HomeMotionViewport.Frame(0,100,1000,800),v.frame())
    }
    @Test fun panCannotExposeOutsideTheScene() {
        val v=HomeMotionViewport();v.configure(1000,1000,1000,500)
        v.zoomBy(2f,500f,500f);v.pan(10000f,10000f)
        assertEquals(HomeMotionViewport.Frame(0,0,2000,1000),v.frame())
        v.pan(-20000f,-20000f)
        assertEquals(HomeMotionViewport.Frame(-1000,0,2000,1000),v.frame())
    }
    @Test fun zoomClampedToFourAndHardwareWithoutAspectDistortion() {
        val v=HomeMotionViewport();v.configure(1000,1000,1000,500,2500,2500)
        v.zoomBy(100f,500f,500f);assertEquals(2.5f,v.scale(),0f)
        assertEquals(2500,v.frame().width);assertEquals(1250,v.frame().height)
        v.configure(1000,1000,1000,500);v.zoomBy(100f,500f,500f)
        assertEquals(4f,v.scale(),0f)
    }
    @Test fun sceneOrLayoutChangeReclampsOffsets() {
        val v=HomeMotionViewport();v.configure(1000,1000,1000,1000)
        v.zoomBy(2f,500f,500f);v.pan(400f,400f)
        v.configure(1000,1000,1000,200)
        assertEquals(300,v.frame().y)
        assertEquals(400,v.frame().height)
    }
    @Test fun focalPointRemainsFixedUntilBoundsRequireClamping() {
        val v=HomeMotionViewport();v.configure(1000,1000,1000,1000)
        v.zoomBy(2f,250f,250f)
        assertEquals(HomeMotionViewport.Frame(-250,-750,2000,2000),v.frame())
    }
    @Test fun malformedInputCannotPoisonViewport() {
        val v=HomeMotionViewport();v.configure(100,100,100,100)
        v.zoomBy(Float.NaN,50f,50f);v.pan(Float.POSITIVE_INFINITY,0f)
        assertEquals(HomeMotionViewport.Frame(0,0,100,100),v.frame())
    }
}
