package dev.filmroom.effects

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class HomeMotionGesturesHostTest {
    private class Fixture {
        val viewport=HomeMotionViewport().apply { configure(1000,1000,1000,1000) }
        val events=mutableListOf<Pair<Float,Boolean>>()
        val gestures=HomeMotionGestures(RuntimeEnvironment.getApplication(),viewport) { z,m -> events.add(z to m) }
        fun touch(action:Int,time:Long,x:Float=500f,y:Float=500f,down:Long=0) {
            val event=MotionEvent.obtain(down,time,action,x,y,0)
            try { gestures.touch(event) } finally { event.recycle() }
        }
    }
    @Test fun doubleTapZoomsAndSecondDoubleTapResets() {
        val f=Fixture()
        f.touch(MotionEvent.ACTION_DOWN,100,down=100);f.touch(MotionEvent.ACTION_UP,130,down=100)
        f.touch(MotionEvent.ACTION_DOWN,220,down=220);f.touch(MotionEvent.ACTION_UP,250,down=220)
        assertEquals(2f,f.viewport.scale(),0f)
        f.touch(MotionEvent.ACTION_DOWN,1000,down=1000);f.touch(MotionEvent.ACTION_UP,1030,down=1000)
        f.touch(MotionEvent.ACTION_DOWN,1120,down=1120);f.touch(MotionEvent.ACTION_UP,1150,down=1120)
        assertEquals(1f,f.viewport.scale(),0f);assertFalse(f.events.last().second)
    }
    @Test fun cancelSettlesMovingWithoutResettingScale() {
        val f=Fixture();f.viewport.zoomBy(2f,500f,500f)
        f.touch(MotionEvent.ACTION_DOWN,100);f.touch(MotionEvent.ACTION_MOVE,150,700f)
        assertTrue(f.events.last().second)
        f.gestures.cancel();assertFalse(f.events.last().second);assertEquals(2f,f.viewport.scale(),0f)
    }
    @Test fun multitouchAndEndAlwaysReportIdle() {
        val f=Fixture();f.touch(MotionEvent.ACTION_DOWN,100)
        f.touch(MotionEvent.ACTION_POINTER_DOWN,120)
        assertTrue(f.events.last().second)
        f.touch(MotionEvent.ACTION_CANCEL,140)
        assertFalse(f.events.last().second)
    }
    @Test fun largeDragRemainsInsideScene() {
        val f=Fixture();f.viewport.zoomBy(2f,500f,500f)
        f.touch(MotionEvent.ACTION_DOWN,100);f.touch(MotionEvent.ACTION_MOVE,150,10000f,10000f)
        f.touch(MotionEvent.ACTION_UP,200,10000f,10000f)
        assertEquals(HomeMotionViewport.Frame(0,-1000,2000,2000),f.viewport.frame())
        assertFalse(f.events.last().second)
    }
}
