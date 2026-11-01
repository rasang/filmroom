package dev.filmroom

import dev.filmroom.lifecycle.PrivateWorkRecovery
import dev.filmroom.lifecycle.StartupRecoveryGate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class StartupRecoveryGateTest {
    @Test fun creationReturnsWithoutWaitingAndWorkersCannotRaceJanitor()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val started=CountDownLatch(1);val finish=CountDownLatch(1);val workers=AtomicInteger()
        try {
            val gate=StartupRecoveryGate(scope){started.countDown();finish.await();PrivateWorkRecovery.Report(1,0)}
            assertTrue(started.await(2,TimeUnit.SECONDS));assertTrue(gate.state.value is StartupRecoveryGate.State.Running)
            val jobs=(1..12).map{launch{gate.state.first{it is StartupRecoveryGate.State.Ready};workers.incrementAndGet()}}
            yield();assertEquals(0,workers.get());assertFalse(gate.retry())
            finish.countDown();withTimeout(2000){jobs.joinAll()};assertEquals(12,workers.get())
            assertFalse(gate.retry()) // live workers can never trigger another janitor
        }finally{finish.countDown();scope.cancel()}
    }
    @Test fun exceptionsBecomeExplicitFailureAndRetryIsSingleFlight()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val attempts=AtomicInteger();val release=CompletableDeferred<Unit>()
        try{
            val gate=StartupRecoveryGate(scope){if(attempts.incrementAndGet()==1)throw AssertionError("simulated failure");release.await();PrivateWorkRecovery.Report(2,0)}
            assertTrue(withTimeout(2000){gate.awaitAttempt()} is StartupRecoveryGate.State.Failed)
            val results=coroutineScope{(1..20).map{async(Dispatchers.Default){gate.retry()}}.awaitAll()}
            assertEquals(1,results.count{it});assertTrue(gate.state.value is StartupRecoveryGate.State.Running)
            release.complete(Unit);assertTrue(withTimeout(2000){gate.awaitAttempt()} is StartupRecoveryGate.State.Ready)
            assertEquals(2,attempts.get());assertFalse(gate.retry())
        }finally{scope.cancel()}
    }
    @Test fun failureDoesNotAdmitWorkersAndFreshProcessMustRecoverAgain()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try{
            val failed=StartupRecoveryGate(scope){PrivateWorkRecovery.Report(3,2)}
            val result=withTimeout(2000){failed.awaitAttempt()};assertEquals(2,(result as StartupRecoveryGate.State.Failed).report.failed)
            assertNull(withTimeoutOrNull(50){failed.state.first{it is StartupRecoveryGate.State.Ready}})
            val pending=CompletableDeferred<Unit>()
            val fresh=StartupRecoveryGate(scope){pending.await();PrivateWorkRecovery.Report(0,0)}
            assertTrue(fresh.state.value is StartupRecoveryGate.State.Running)
            pending.complete(Unit);assertTrue(withTimeout(2000){fresh.awaitAttempt()} is StartupRecoveryGate.State.Ready)
        }finally{scope.cancel()}
    }
}
