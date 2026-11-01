package dev.filmroom

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.filmroom.ui.CenteredChoiceRail
import dev.filmroom.ui.LegacyQuietWindowRail
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w360dp-h780dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RailReleaseTimelineHostTest {
    @org.junit.Before fun clock(){org.robolectric.shadows.ShadowChoreographer.setPaused(true);org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))}
    private fun tick(ms:Long){var left=ms;while(left>0){val step=minOf(16L,left);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step));left-=step}}
    private fun layout(v:View){v.layoutParams=android.widget.FrameLayout.LayoutParams(-1,90);val host=v.parent as View;host.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(90,View.MeasureSpec.EXACTLY));host.layout(0,0,360,90)}
    private fun event(v:View,action:Int,x:Float,down:Long){MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,40f,0).also{v.dispatchTouchEvent(it);it.recycle()}}
    private fun fill(items:LinearLayout,a:Activity){repeat(8){i->items.addView(TextView(a).apply{text="Choice $i"},LinearLayout.LayoutParams(100,90))}}
    data class Timeline(val release:Int,val positions:List<Int>,val commits:List<Pair<Long,Int>>){val firstMove get()=positions.indexOfFirst{it!=release}.let{if(it<0)Long.MAX_VALUE else (it+1)*16L}}
    private fun releaseTrace(legacy:Boolean,fast:Boolean):Timeline{
        Robolectric.buildActivity(Activity::class.java).setup().use{c->
            val commits=mutableListOf<Pair<Long,Int>>();var released=0L
            val v:HorizontalScrollView=if(legacy)LegacyQuietWindowRail(c.get()).also{fill(it.items,c.get());it.configure(100,0){index->commits.add(SystemClock.uptimeMillis()-released to index)}}
                else CenteredChoiceRail(c.get()).also{fill(it.items,c.get());it.configure(100,0){index->commits.add(SystemClock.uptimeMillis()-released to index)}}
            c.get().setContentView(v);layout(v)
            val down=SystemClock.uptimeMillis();event(v,MotionEvent.ACTION_DOWN,280f,down)
            repeat(6){i->tick(if(fast)10 else 48);event(v,MotionEvent.ACTION_MOVE,280f-(i+1)*28f,down)}
            if(!fast)repeat(3){tick(120);event(v,MotionEvent.ACTION_MOVE,112f,down)}
            val x=v.scrollX;assertTrue("Real MOVE changed scroll without injected scrollTo",x>0)
            released=SystemClock.uptimeMillis();event(v,MotionEvent.ACTION_UP,112f,down)
            val positions=mutableListOf<Int>();repeat(90){tick(16);positions.add(v.scrollX)}
            return Timeline(x,positions,commits.toList())
        }
    }
    @Test(timeout=30000) fun slowReleaseRemovesOldQuietWindowAndCommitsOnlyAtAnimationEnd(){
        val old=releaseTrace(true,false);val now=releaseTrace(false,false)
        val out=File("build/rail-release-timelines").apply{mkdirs()}
        File(out,"slow-before-after.txt").writeText("OLD firstMoveMs=${old.firstMove} commits=${old.commits} positions=${old.positions}\nNEW firstMoveMs=${now.firstMove} commits=${now.commits} positions=${now.positions}\n")
        assertTrue("Baseline reproduces deliberate idle gap: $old",old.firstMove>=160)
        assertTrue("Release starts moving within the next display frames: $now",now.firstMove<=48)
        assertEquals(1,now.commits.size);assertTrue(now.commits.single().first>now.firstMove)
        assertEquals(now.commits.single().second*100,now.positions.last())
        assertTrue(now.positions.any{it!=now.release && it!=now.positions.last()})
    }
    @Test(timeout=30000) fun fastFlingHasOneContinuousDecelerationAndOneCommit(){
        val now=releaseTrace(false,true);assertTrue(now.firstMove<=48);assertEquals(1,now.commits.size)
        assertTrue(now.commits.single().first>now.firstMove)
        assertEquals(now.commits.single().second*100,now.positions.last());assertTrue(now.positions.last() in 0..700)
        val moving=now.positions.takeWhile{it<now.positions.last()-2}
        assertTrue(moving.zipWithNext().all{(a,b)->b>=a})
        assertFalse("No idle block before another phase",moving.windowed(5).any{it.distinct().size==1})
        File("build/rail-release-timelines").apply{mkdirs()}.resolve("fast.txt").writeText(now.toString())
    }
    @Test(timeout=30000) fun regrabReverseCancelAndEdgeNeverCommitAStaleTarget(){
        Robolectric.buildActivity(Activity::class.java).setup().use{c->
            val changes=mutableListOf<Int>();val v=CenteredChoiceRail(c.get());fill(v.items,c.get());v.configure(100,0){changes.add(it)};c.get().setContentView(v);layout(v)
            v.fling(6000);tick(64);val interrupted=v.scrollX;assertTrue(interrupted in 1..699)
            val down=SystemClock.uptimeMillis();event(v,MotionEvent.ACTION_DOWN,180f,down);tick(64)
            assertEquals(interrupted,v.scrollX);assertTrue(changes.isEmpty())
            event(v,MotionEvent.ACTION_CANCEL,180f,down);tick(1200);assertEquals(0,v.scrollX);assertTrue(changes.isEmpty())
            v.fling(6000);tick(64);v.fling(-6000);tick(1400)
            assertEquals(0,v.scrollX);assertTrue(changes.isEmpty())
            v.fling(-6000);tick(1400);assertEquals(0,v.scrollX);assertTrue(changes.isEmpty())
            v.select(7);tick(400);assertEquals(listOf(7),changes)
            v.fling(6000);tick(1400);assertEquals(700,v.scrollX);assertEquals(listOf(7),changes)
        }
    }    @Test(timeout=30000) fun disableConsumesNativeScrollerAndNeverMovesOrCommitsLater(){
        Robolectric.buildActivity(Activity::class.java).setup().use{c->
            val changes=mutableListOf<Int>();val v=CenteredChoiceRail(c.get());fill(v.items,c.get());v.configure(100,2){changes.add(it)};c.get().setContentView(v);layout(v)
            for(useFling in listOf(false,true)){
                v.isEnabled=true
                if(useFling)v.fling(6000)else v.select(7)
                tick(48);assertTrue(v.scrollX>200)
                v.isEnabled=false
                repeat(90){v.computeScroll();tick(16);assertEquals("Disabled remains at committed position",200,v.scrollX)}
                assertTrue(changes.isEmpty())
            }
        }
    }
    @Test(timeout=30000) fun accessibilitySetProgressClampsInterruptsAndCommitsExactlyOnce(){
        Robolectric.buildActivity(Activity::class.java).setup().use{c->
            val changes=mutableListOf<Int>();val selector=dev.filmroom.ui.SettledChoiceSelector(c.get())
            selector.bind("Exposure",(0..7).map{"Item $it"},0){changes.add(it)};c.get().setContentView(selector);layout(selector)
            val rail=selector.getChildAt(1) as CenteredChoiceRail
            val action=android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
            fun request(v:Float)=selector.performAccessibilityAction(action,android.os.Bundle().apply{putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,v)})
            val info=android.view.accessibility.AccessibilityNodeInfo.obtain()
            selector.onInitializeAccessibilityNodeInfo(info)
            assertTrue(info.actionList.any{it.id==action});info.recycle()
            val down=SystemClock.uptimeMillis();event(rail,MotionEvent.ACTION_DOWN,200f,down);tick(48);event(rail,MotionEvent.ACTION_MOVE,100f,down)
            assertTrue(request(99f));assertTrue(changes.isEmpty());tick(32)
            event(rail,MotionEvent.ACTION_CANCEL,100f,down);event(rail,MotionEvent.ACTION_UP,100f,down)
            tick(1200);assertEquals(listOf(7),changes)
            // UP from the cancelled old pointer must not schedule a second selection.
            event(rail,MotionEvent.ACTION_UP,100f,down);tick(1200);assertEquals(listOf(7),changes)
            assertTrue(request(-10f));tick(1200);assertEquals(listOf(7,0),changes)
            val freshDown=SystemClock.uptimeMillis();event(rail,MotionEvent.ACTION_DOWN,200f,freshDown)
            assertFalse(CenteredChoiceRail::class.java.getDeclaredField("ignoreTouchUntilDown").apply{isAccessible=true}.getBoolean(rail))
            // The first MOVE transfers a clickable child's gesture to its scroll parent.
            tick(48);event(rail,MotionEvent.ACTION_MOVE,100f,freshDown)
            tick(48);event(rail,MotionEvent.ACTION_MOVE,60f,freshDown)
            assertTrue("A new DOWN re-enables real drag after takeover",rail.scrollX>0)
            event(rail,MotionEvent.ACTION_CANCEL,100f,freshDown);tick(1200);assertEquals(listOf(7,0),changes)
            assertFalse(request(Float.NaN));selector.isEnabled=false;assertFalse(request(3f));tick(1200)
            assertEquals(listOf(7,0),changes)
        }
    }

}
