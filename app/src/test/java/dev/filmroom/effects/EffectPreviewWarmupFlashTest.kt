package dev.filmroom.effects

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guards the Activity integration, not a substitute for a device render test. */
class EffectPreviewWarmupFlashTest {
    private fun source():String {
        val path="src/main/java/dev/filmroom/effects/PhotoEffectActivity.kt"
        return listOf(File(path),File("app/$path")).first{it.isFile}.readText()
    }
    @Test fun bothWarmupWaitsHoldOpeningInsteadOfShowingCompletedPhoto(){
        val code=source().substringAfter("override fun onDrawFrame").substringBefore("fun isFrameReady")
        assertEquals(2,Regex("drawPreparingFrame\\(\\);return").findAll(code).count())
        assertFalse(code.contains("renderer.draw(widthPx,heightPx,duration"))
        assertTrue(code.contains("elapsed=0"))
    }
    @Test fun waitingFrameRendersOpeningCompositionWithoutShowingCompletedPhoto(){
        val code=source().substringAfter("private fun drawPreparingFrame(){").substringBefore("fun isFrameReady")
        assertTrue(code.contains("renderer.draw(widthPx,heightPx,0,"))
        assertFalse(code.contains("glClear("))
        assertFalse(code.contains("duration"))
    }
    @Test fun warmupNeverConsumesThreeSecondPlayback(){
        val p=EffectPreviewPlayback(true)
        // Activity keeps resetting start while waiting and starts at the readiness timestamp.
        val readyAt=3000L
        assertEquals(0L,p.elapsed(readyAt,readyAt,3000))
        assertEquals(1500L,p.elapsed(readyAt+1500,readyAt,3000))
        p.rendered(2999,3000);assertFalse(p.stopped)
        p.rendered(3000,3000);assertTrue(p.stopped)
    }
    @Test fun replayStillStartsAtZeroWhileIdleRemainsFinished(){
        val p=EffectPreviewPlayback(false)
        assertEquals(3000L,p.elapsed(100,100,3000))
        p.replay();assertEquals(0L,p.elapsed(2000,2000,3000))
    }
}
