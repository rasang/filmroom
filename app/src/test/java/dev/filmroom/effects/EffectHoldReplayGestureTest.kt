package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class EffectHoldReplayGestureTest {
    private class Harness {
        val tasks=mutableListOf<Runnable>();var delay=0L;var ready=true;var plays=0;var covers=0
        val gesture=EffectHoldReplayGesture(8f,{task,ms->tasks.add(task);delay=ms},{tasks.remove(it)},
            {ready},{plays++},{covers++})
        fun fire(){tasks.firstOrNull()?.run()}
    }
    @Test fun holdsAfter350AndReleaseShowsCoverOnce(){
        val h=Harness();h.gesture.down(20f,30f);assertEquals(350L,h.delay);assertEquals(0,h.plays)
        h.fire();assertTrue(h.gesture.held);assertEquals(1,h.plays)
        assertTrue(h.gesture.finish());assertEquals(1,h.covers);assertFalse(h.gesture.finish());assertEquals(1,h.covers)
    }
    @Test fun shortDragRemainsNavigationAndStaleTimerCannotReplay(){
        val h=Harness();h.gesture.down(0f,0f);val old=h.tasks.single();h.gesture.move(9f,0f)
        old.run();assertFalse(h.gesture.finish());assertEquals(0,h.plays);assertEquals(0,h.covers)
    }
    @Test fun multiTouchOrLifecycleCancellationInvalidatesPendingTimer(){
        val h=Harness();h.gesture.down(0f,0f);val old=h.tasks.single();h.gesture.cancel();old.run()
        assertEquals(0,h.plays);assertFalse(h.gesture.held)
    }
    @Test fun activeCancellationCoversAndDraggingWhileHeldDoesNotNavigate(){
        val h=Harness();h.gesture.down(0f,0f);h.fire();h.gesture.move(100f,100f)
        assertTrue(h.gesture.held);h.gesture.cancel();assertEquals(1,h.covers);assertFalse(h.gesture.held)
    }
    @Test fun readinessRecheckedAtDeadlineAndOldDownCannotActivateNewGesture(){
        val h=Harness();h.gesture.down(0f,0f);val old=h.tasks.single();h.gesture.down(10f,10f)
        old.run();assertEquals(0,h.plays);h.ready=false;h.tasks.last().run();assertEquals(0,h.plays)
    }
    @Test fun smallJitterAndShortTapDoNotReplay(){
        val h=Harness();h.gesture.down(0f,0f);h.gesture.move(8f,-8f);assertEquals(1,h.tasks.size)
        assertFalse(h.gesture.finish());assertEquals(0,h.plays);assertEquals(0,h.covers)
    }
}
