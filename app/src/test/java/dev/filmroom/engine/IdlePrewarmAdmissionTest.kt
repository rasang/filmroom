package dev.filmroom.engine

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IdlePrewarmAdmissionTest {
    private val roomy=NativeMemorySnapshot(4L*1024*1024*1024,0,false)
    @Test fun idleUsesSamePassiveAndActiveBudgetWithoutEvicting()=runBlocking {
        val limiter=NativeWorkLimiter();var pressure=0
        val retained=limiter.retain(8L*NativeMemoryPolicy.MIB,roomy){pressure++}
        val before=limiter.reservedBytes()
        assertEquals("ready",limiter.tryRunIdle(64L*NativeMemoryPolicy.MIB,roomy,{}){
            assertEquals(1,limiter.activeCount());assertEquals(before+80L*NativeMemoryPolicy.MIB,limiter.reservedBytes());"ready"
        })
        assertEquals(before,limiter.reservedBytes());assertEquals(0,pressure);retained.close();assertEquals(0L,limiter.reservedBytes())
    }
    @Test fun lowMemoryOrInsufficientBudgetSkipsWithoutCallingNativeOrPressure()=runBlocking {
        val limiter=NativeWorkLimiter();var pressure=0
        val retained=limiter.retain(8L*NativeMemoryPolicy.MIB,roomy){pressure++}
        for(snapshot in listOf(roomy.copy(lowMemory=true),NativeMemorySnapshot(128L*NativeMemoryPolicy.MIB,0,false))){
            assertNull(limiter.tryRunIdle(64L*NativeMemoryPolicy.MIB,snapshot,{}){error("not admitted")})
        }
        assertEquals(0,pressure);assertEquals(8L*NativeMemoryPolicy.MIB,limiter.reservedBytes());retained.close()
    }
    @Test fun activeForegroundMakesPrewarmNonWaiting()=runBlocking {
        val limiter=NativeWorkLimiter()
        limiter.run(32L*NativeMemoryPolicy.MIB,{roomy}){
            assertNull(limiter.tryRunIdle(64L*NativeMemoryPolicy.MIB,roomy,{}){error("must not run")})
        }
        assertEquals(0,limiter.activeCount())
    }
    @Test fun realWorkPreemptsIdleAndKeepsBytesUntilTheNativeBodyReturns()=runBlocking {
        val limiter=NativeWorkLimiter();val started=CompletableDeferred<Unit>();val stop=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val idle=launch(Dispatchers.Default){limiter.tryRunIdle(64L*NativeMemoryPolicy.MIB,roomy,{stop.complete(Unit)}){started.complete(Unit);release.await();"done"}}
        started.await()
        limiter.run(32L*NativeMemoryPolicy.MIB,{roomy}){
            stop.await();assertEquals(2,limiter.activeCount());assertEquals(128L*NativeMemoryPolicy.MIB,limiter.reservedBytes())
            assertNull(limiter.tryRunIdle(64L*NativeMemoryPolicy.MIB,roomy,{}){error("second prewarm")})
        }
        assertEquals(80L*NativeMemoryPolicy.MIB,limiter.reservedBytes());release.complete(Unit);idle.join()
        assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
    }
    @Test fun exceptionAndCancellationReleaseExactlyOneIdleCharge()=runBlocking {
        val limiter=NativeWorkLimiter()
        try{limiter.tryRunIdle(64L*NativeMemoryPolicy.MIB,roomy,{}){throw IllegalStateException("fixture")};fail()}catch(_:IllegalStateException){}
        val started=CompletableDeferred<Unit>();val job=launch{limiter.tryRunIdle(64L*NativeMemoryPolicy.MIB,roomy,{}){started.complete(Unit);awaitCancellation()}}
        started.await();job.cancelAndJoin();assertEquals(0,limiter.activeCount());assertEquals(0L,limiter.reservedBytes())
    }
    @Test fun everyUnsafeEnvironmentSkips(){
        assertTrue(IdlePrewarmPolicy.eligible(true,false,true,0,false,false,false))
        assertFalse(IdlePrewarmPolicy.eligible(false,false,true,0,false,false,false))
        assertFalse(IdlePrewarmPolicy.eligible(true,true,true,0,false,false,false))
        assertFalse(IdlePrewarmPolicy.eligible(true,false,false,0,false,false,false))
        for(thermal in 1..6)assertFalse(IdlePrewarmPolicy.eligible(true,false,true,thermal,false,false,false))
        assertFalse(IdlePrewarmPolicy.eligible(true,false,true,0,true,false,false))
        assertFalse(IdlePrewarmPolicy.eligible(true,false,true,0,false,true,false))
        assertFalse(IdlePrewarmPolicy.eligible(true,false,true,0,false,false,true))
    }
}
