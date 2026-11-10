package dev.filmroom.effects

import org.junit.Assert.*
import org.junit.Test

class HomeMotionLayoutTest {
    @Test fun fitKeepsFinalCompositionAspectInsideStage() {
        for(kind in EffectKind.entries) for((w,h) in listOf(600 to 400,400 to 600,1 to 1)) {
            val size=HomeMotionLayout.fit(w,h,kind,300,450)
            assertTrue(size.width in 1..w); assertTrue(size.height in 1..h)
            if(w>1) assertEquals(EffectGeometry.sceneAspect(kind,300f/450),size.width.toFloat()/size.height,.01f)
        }
    }
    @Test fun coverUsesSettledRevealFrame() { assertEquals(2900L,HomeMotionLayout.COVER_TIME_MS) }
    @Test fun cancelledAndSupersededUploadsCannotAdopt() {
        val gate=HomeMotionReplacementGate(); var applied=0
        val first=gate.next(); gate.cancel()
        assertFalse(gate.commit(first){applied++;true})
        val second=gate.next(); val latest=gate.next()
        assertFalse(gate.commit(second){applied++;true})
        assertTrue(gate.commit(latest){applied++;true}); assertEquals(1,applied)
    }
    @Test fun invalidationWaitsUntilAtomicAdoptionCompletes() {
        val gate=HomeMotionReplacementGate(); val token=gate.next()
        val entered=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
        val worker=Thread { gate.commit(token){entered.countDown();release.await();true} }
        worker.start(); assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        val canceller=Thread { gate.cancel() }; canceller.start()
        release.countDown(); worker.join(2000); canceller.join(2000)
        assertFalse(worker.isAlive);assertFalse(canceller.isAlive);assertFalse(gate.current(token))
    }
}
