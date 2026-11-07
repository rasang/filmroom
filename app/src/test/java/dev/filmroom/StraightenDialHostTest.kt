package dev.filmroom

import android.view.MotionEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.filmroom.ui.StraightenDialView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="mdpi")
class StraightenDialHostTest {
    @Test fun boundsCancelKeyboardAndAccessibilityUseSameAngleContract(){
        val wheel=StraightenDialView(RuntimeEnvironment.getApplication(),-1,-1,-1)
        val events=mutableListOf<Pair<Double,Boolean>>();wheel.onAngleChanged={v,done->events+=v to done}
        fun touch(action:Int,x:Float){val e=MotionEvent.obtain(0,0,action,x,20f,0);wheel.onTouchEvent(e);e.recycle()}
        touch(MotionEvent.ACTION_DOWN,0f);touch(MotionEvent.ACTION_MOVE,10000f);assertEquals(15.0,wheel.angle,0.0)
        touch(MotionEvent.ACTION_UP,10000f);assertEquals(15.0 to true,events.last())
        touch(MotionEvent.ACTION_DOWN,0f);touch(MotionEvent.ACTION_MOVE,-10000f);assertEquals(-15.0,wheel.angle,0.0)
        touch(MotionEvent.ACTION_CANCEL,-10000f);assertEquals(15.0 to true,events.last())
        wheel.angle=0.0;wheel.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));assertEquals(.1,wheel.angle,0.0)
        wheel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null);assertEquals(1.1,wheel.angle,0.0)
        wheel.isEnabled=false;val count=events.size;touch(MotionEvent.ACTION_DOWN,0f);touch(MotionEvent.ACTION_MOVE,100f);touch(MotionEvent.ACTION_UP,100f);assertEquals(count,events.size)
    }
}
