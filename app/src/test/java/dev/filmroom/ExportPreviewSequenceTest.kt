package dev.filmroom

import dev.filmroom.ui.ExportPreviewSequence
import org.junit.Assert.*
import org.junit.Test

class ExportPreviewSequenceTest {
    @Test fun measuredSavingAdvancesWithoutSkippingImageDwellOrPrematureCompletion() {
        val s=dev.filmroom.ui.ExportPreviewSequence();s.start();s.shown(1,0)
        assertEquals(7 to 42,s.constrainProgress(7,42,true))
        assertEquals(4 to null,s.constrain(7,42)) // Image path still follows ritual.
        assertEquals(7 to 99,s.constrainProgress(7,100,true))
        s.shown(2,500);s.shown(3,1000)
        assertEquals(7 to 99,s.constrainProgress(7,100,true))
        assertTrue(s.finish(1500));assertEquals(7 to 100,s.constrainProgress(7,100,true))
    }
    @Test fun sharperNegativeReplacementDoesNotRestartTheStageClock() {
        val s=ExportPreviewSequence();s.start();s.visibility(true,0);s.shown(2,0)
        s.rememberNegative("negative-preview-early-current.png")
        s.rememberNegative("negative-preview-current.png");s.shown(2,200)
        assertEquals("negative-preview-current.png",s.negativeName)
        assertEquals(300L,s.remaining(200));assertEquals(0L,s.remaining(500))
    }
    @Test fun everyRealImagePhaseIncludesFadeInside500Milliseconds() {
        assertEquals(500L,ExportPreviewSequence.MIN_STAGE_MS)
        val s=ExportPreviewSequence();s.start();s.visibility(true,0)
        s.shown(1,0)
        assertEquals(1L,s.remaining(499));assertEquals(0L,s.remaining(500))
        assertEquals(4 to null,s.constrain(7,100))
        s.shown(2,500)
        assertEquals(1L,s.remaining(999));assertEquals(0L,s.remaining(1000))
        assertEquals(5 to null,s.constrain(7,100))
        s.shown(3,1000)
        assertFalse(s.finish(1499));assertEquals(6 to null,s.constrain(7,100))
        assertTrue(s.finish(1500));assertEquals(7 to 100,s.constrain(7,100))
    }
    @Test fun backgroundCountsPartialDwellAndReentryDoesNotResetIt() {
        val s=ExportPreviewSequence();s.start();s.timeline(0,0,0);s.visibility(true,0);s.shown(2,0)
        s.visibility(false,100);assertEquals(250L,s.remaining(250))
        s.visibility(true,250);s.shown(2,250)
        assertEquals(250L,s.remaining(250));assertEquals(1L,s.remaining(499))
        s.visibility(false,500);assertEquals(3,s.run{remaining(750);phase})
        assertEquals(250L,s.remaining(750))
        assertFalse(s.finish(999));assertTrue(s.finish(1000))
    }
    @Test fun backgroundSkipsExpiredPhasesIncludingAnUnloadedFirstFrame() {
        val s=ExportPreviewSequence();s.start();s.timeline(100,100,100);s.visibility(false,100)
        assertEquals(1,s.run{remaining(599);phase});assertEquals(1L,s.remaining(599))
        assertEquals(2,s.run{remaining(600);phase});assertEquals(500L,s.remaining(600))
        assertEquals(3,s.run{remaining(1100);phase});assertEquals(500L,s.remaining(1100))
        assertFalse(s.finish(1599));assertTrue(s.finish(1600));assertFalse(s.active)
    }
    @Test fun hiddenCardsHaveIndependentClocksAndNeverClaimRealSave() {
        val a=ExportPreviewSequence();val b=ExportPreviewSequence()
        a.start();a.timeline(0,0,0);b.start();b.timeline(750,750,750)
        assertTrue(a.finish(1500));assertFalse(b.finish(1500))
        assertEquals(2,b.phase);assertEquals(250L,b.remaining(1500))
        assertEquals(7 to null,a.constrain(7,null)) // Presentation completion is not real success.
        assertFalse(b.finish(2249));assertTrue(b.finish(2250))
    }
    @Test fun lateNegativeAndPositiveDoNotConsumeTimeBeforeRealAvailability() {
        val s=ExportPreviewSequence();s.start();s.timeline(0,5000,9000)
        assertFalse(s.finish(3000));assertEquals(1,s.phase)
        assertEquals(250L,s.remaining(5250));assertEquals(2,s.phase)
        assertFalse(s.finish(8000));assertEquals(2,s.phase)
        assertEquals(250L,s.remaining(9250));assertEquals(3,s.phase)
        assertFalse(s.finish(9499));assertTrue(s.finish(9500))
    }
    @Test fun newlyDiscoveredBackgroundArtifactsUseTheirOwnReadyTime() {
        val s=ExportPreviewSequence();s.start();s.timeline(0,null,null)
        assertFalse(s.finish(10000));assertEquals(1,s.phase)
        s.timeline(0,11000,13000)
        assertEquals(250L,s.remaining(11250));assertEquals(2,s.phase)
        assertFalse(s.finish(13000));assertEquals(3,s.phase)
        assertFalse(s.finish(13499));assertTrue(s.finish(13500))
    }
    @Test fun missingOptionalNegativeNeverFabricatesAPhaseOrSkipsPositiveDwell() {
        val s=ExportPreviewSequence();s.start();s.timeline(0,null,5000,true)
        assertFalse(s.finish(3000));assertEquals(1,s.phase)
        assertEquals(250L,s.remaining(5250));assertEquals(3,s.phase)
        assertFalse(s.finish(5499));assertTrue(s.finish(5500))
    }
    @Test fun latestArtifactsArrivingAfterResumeStillReceiveTheirBackgroundTime() {
        val s=ExportPreviewSequence();s.start();s.visibility(true,0);s.shown(1,0)
        s.timeline(0,null,null);s.visibility(false,500)
        s.visibility(true,10000);assertEquals(1,s.phase)
        s.timeline(0,3000,5000)
        assertEquals(3,s.phase);assertTrue(s.finish(10000))
    }
    @Test fun absentOriginalBeginsAtTheGenuineNegativeReadyTime() {
        val negativeFirst=ExportPreviewSequence();negativeFirst.start();negativeFirst.timeline(null,5000,9000)
        assertFalse(negativeFirst.finish(4500));assertEquals(0,negativeFirst.phase)
        assertEquals(250L,negativeFirst.remaining(5250));assertEquals(2,negativeFirst.phase)
        assertFalse(negativeFirst.finish(9499));assertTrue(negativeFirst.finish(9500))
    }
    @Test fun absentOriginalAndNegativeUseOnlyTheGenuinePositiveReadyTime() {
        val positiveOnly=ExportPreviewSequence();positiveOnly.start();positiveOnly.timeline(null,null,5000,true)
        assertEquals(250L,positiveOnly.remaining(5250));assertEquals(3,positiveOnly.phase)
        assertFalse(positiveOnly.finish(5499));assertTrue(positiveOnly.finish(5500))
    }
    @Test fun missingMaterialCanBeSkippedButActualPositiveStillDwellsAndCardsAreIndependent() {
        val a=ExportPreviewSequence();val b=ExportPreviewSequence()
        a.start();b.start();a.visibility(true,0);b.visibility(true,0)
        a.shown(1,0);b.shown(3,0)
        assertFalse(a.finish(500));assertFalse(b.finish(499));assertTrue(b.finish(500));assertTrue(a.active)
        a.shown(3,500);assertFalse(a.finish(999));assertTrue(a.finish(1000))
    }
    @Test fun failureCancellationAndRetryNeverLeaveASuccessTimerRunning() {
        val s=ExportPreviewSequence();s.start();s.visibility(true,0);s.shown(1,0)
        s.stop();assertFalse(s.active);assertEquals(0L,s.remaining(10000))
        s.reset();s.start();assertEquals(0,s.phase);assertTrue(s.active)
        s.reset();s.start();s.visibility(true,10000);s.shown(3,10000)
        assertFalse(s.finish(10499));assertTrue(s.finish(10500))
    }
}
