package dev.filmroom

import dev.filmroom.ui.ExportPreviewSequence
import org.junit.Assert.*
import org.junit.Test

class ExportPreviewSequenceTest {
    @Test fun everyRealImageGetsOnePointFiveSecondsAfterItsFade() {
        val s=ExportPreviewSequence();s.start();s.visibility(true,0)
        s.shown(1,1000,0)
        assertEquals(1L,s.remaining(2499));assertEquals(0L,s.remaining(2500))
        assertEquals(4 to 99,s.constrain(7,100))
        s.shown(2,450,2500)
        assertEquals(1L,s.remaining(4449));assertEquals(0L,s.remaining(4450))
        assertEquals(5 to 99,s.constrain(7,100))
        s.shown(3,450,4450)
        assertFalse(s.finish(6399));assertEquals(6 to 99,s.constrain(7,100))
        assertTrue(s.finish(6400));assertEquals(7 to 100,s.constrain(7,100))
    }
    @Test fun hiddenTimeDoesNotCountAndReentryDoesNotReplayEarlierImages() {
        val s=ExportPreviewSequence();s.start();s.visibility(true,0);s.shown(2,450,0)
        s.visibility(false,500);assertEquals(1450L,s.remaining(99999))
        s.visibility(true,100000);assertEquals(450L,s.remaining(101000))
        s.shown(1,1000,101000);assertEquals(2,s.phase);assertEquals(450L,s.remaining(101000))
        s.shown(2,450,101000);assertEquals(900L,s.remaining(101000))
    }
    @Test fun missingMaterialCanBeSkippedButActualPositiveStillDwellsAndCardsAreIndependent() {
        val a=ExportPreviewSequence();val b=ExportPreviewSequence()
        a.start();b.start();a.visibility(true,0);b.visibility(true,0)
        a.shown(1,0,0);b.shown(3,0,0)
        assertFalse(a.finish(1500));assertTrue(b.finish(1500));assertTrue(a.active)
        a.shown(3,450,1500);assertFalse(a.finish(3449));assertTrue(a.finish(3450))
    }
    @Test fun failureCancellationAndRetryNeverLeaveASuccessTimerRunning() {
        val s=ExportPreviewSequence();s.start();s.visibility(true,0);s.shown(1,1000,0)
        s.stop();assertFalse(s.active);assertEquals(0L,s.remaining(10000))
        s.reset();s.start();assertEquals(0,s.phase);assertTrue(s.active)
        s.shown(3,0,10000);s.visibility(true,10000)
        assertFalse(s.finish(11499));assertTrue(s.finish(11500))
    }
}
