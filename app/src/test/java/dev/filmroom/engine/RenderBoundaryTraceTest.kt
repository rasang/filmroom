package dev.filmroom.engine

import dev.filmroom.diagnostics.GpuLifecyclePhase
import dev.filmroom.diagnostics.RenderMemoryInfo
import dev.filmroom.diagnostics.RenderMemoryWaitReason
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RenderBoundaryTraceTest {
    @Test fun durableEntryPrecedesNativeAndReturnIsNotSuccess()=runBlocking {
        val events=mutableListOf<Pair<GpuLifecyclePhase,Boolean>>()
        val trace=RenderBoundaryTrace.forTesting {phase,_,_,durable->events.add(phase to durable)}
        withContext(trace) {
            val value=previewNativeBoundary(mark={},readProgress={null},recordProgress={},now={0}) {
                assertEquals(GpuLifecyclePhase.JNI_ENTER to false,events.last())
                "native error response"
            }
            assertEquals("native error response",value)
        }
        assertEquals(listOf(GpuLifecyclePhase.JNI_PREPARE to true,GpuLifecyclePhase.JNI_ENTER to false,GpuLifecyclePhase.JNI_RETURN to false),events)
    }
    @Test fun telemetryFailureCannotPreventRendering()=runBlocking {
        val trace=RenderBoundaryTrace.forTesting {_,_,_,_->throw IllegalStateException("sink unavailable")}
        val result=withContext(trace){previewNativeBoundary(mark={},readProgress={null},recordProgress={},now={0}){42}}
        assertEquals(42,result)
    }
    @Test fun unchangedWaitAndHeartbeatAreThrottledNotPromotedToProgress() {
        var clock=0L;val events=mutableListOf<GpuLifecyclePhase>()
        val trace=RenderBoundaryTrace.forTesting(now={clock}){phase,_,_,_->events.add(phase)}
        repeat(20){trace.event(GpuLifecyclePhase.PROGRESS_WAIT)}
        assertEquals(listOf(GpuLifecyclePhase.PROGRESS_WAIT),events)
        clock=1000;trace.event(GpuLifecyclePhase.PROGRESS_WAIT)
        trace.event(GpuLifecyclePhase.CANCEL_REQUEST,GpuRenderCode.CANCELLED)
        assertEquals(listOf(GpuLifecyclePhase.PROGRESS_WAIT,GpuLifecyclePhase.PROGRESS_WAIT,GpuLifecyclePhase.CANCEL_REQUEST),events)
    }
    @Test fun thrownNativeCallRecordsFailureNotReturn()=runBlocking {
        val events=mutableListOf<GpuLifecyclePhase>()
        val trace=RenderBoundaryTrace.forTesting {phase,_,_,_->events.add(phase)}
        try {withContext(trace){previewNativeBoundary(mark={},readProgress={null},recordProgress={},now={0}){throw IllegalStateException("synthetic")}}}
        catch(_:IllegalStateException){}
        assertEquals(listOf(GpuLifecyclePhase.JNI_PREPARE,GpuLifecyclePhase.JNI_ENTER,GpuLifecyclePhase.JNI_FAILED),events)
    }
    @Test fun cancellationDuringPrepareNeverClaimsJniEntry()=runBlocking {
        val events=mutableListOf<GpuLifecyclePhase>()
        val trace=RenderBoundaryTrace.forTesting {phase,_,_,_->events.add(phase)}
        var called=false
        try {withContext(trace){previewNativeBoundary(mark={throw CancellationException("cancel before JNI")},readProgress={null},recordProgress={},now={0}){called=true;42}}}
        catch(_:CancellationException){}
        assertFalse(called)
        assertEquals(listOf(GpuLifecyclePhase.JNI_PREPARE,GpuLifecyclePhase.NOT_ENTERED),events)
    }
    @Test fun cancellationAtEvictionCheckpointDoesNotStrandEvictingLease()=runBlocking {
        val mib=1024L*1024
        val ample=NativeMemorySnapshot(4L*1024*mib,256*mib,false)
        val constrained=NativeMemorySnapshot(400*mib,128*mib,false)
        val limiter=NativeWorkLimiter();var evictions=0
        lateinit var passive:AutoCloseable
        passive=limiter.retain(120*mib,ample){evictions++;passive.close()}
        val job=Job()
        val trace=RenderBoundaryTrace.forTesting {phase,_,_,_->if(phase==GpuLifecyclePhase.MEMORY_EVICTION)job.cancel()}
        try {withContext(job+trace){limiter.run(120*mib,{constrained}){fail("cancelled admission must not execute")}}}
        catch(_:CancellationException){}
        assertEquals(1,evictions)
        assertEquals(0L,limiter.reservedBytes())
        assertEquals(0,limiter.activeCount())
        assertEquals(7,limiter.run(120*mib,{ample}){7})
    }

    @Test fun memoryMetricsMatchAdmittedAndReleasedLedger()=runBlocking {
        val mib=1024L*1024;val available=4L*1024*mib
        val metrics=mutableListOf<RenderMemoryInfo>()
        val trace=RenderBoundaryTrace.forTesting(memorySink={metrics.add(it)}){_,_,_,_->}
        val limiter=NativeWorkLimiter()
        withContext(trace){limiter.run(120*mib,{NativeMemorySnapshot(available,256*mib,false)}){42}}
        val admitted=metrics.single{it.waitReason==RenderMemoryWaitReason.ADMITTED}
        assertEquals(120*mib,admitted.requestedBytes)
        assertEquals(136*mib,admitted.chargedBytes)
        assertEquals(available,admitted.availableBytes)
        assertEquals(136*mib,admitted.reservedBytes)
        assertEquals(1,admitted.activeWorkers)
        val released=metrics.single{it.waitReason==RenderMemoryWaitReason.BYTES_RELEASED}
        assertEquals(0L,released.reservedBytes);assertEquals(0,released.activeWorkers)
        assertNull(released.availableBytes) // No fabricated fresh OS snapshot on release.
    }
    @Test fun rejectionMetricsKeepActualLowMemoryReason()=runBlocking {
        val metrics=mutableListOf<RenderMemoryInfo>()
        val trace=RenderBoundaryTrace.forTesting(memorySink={metrics.add(it)}){_,_,_,_->}
        try{withContext(trace){NativeWorkLimiter().run(120L*1024*1024,{NativeMemorySnapshot(0,0,true)}){fail("must reject")}}}
        catch(_:NativeWorkUnavailable){}
        val rejected=metrics.single{it.waitReason==RenderMemoryWaitReason.LOW_MEMORY}
        assertEquals(0L,rejected.availableBytes);assertEquals(0L,rejected.reservedBytes);assertEquals(0,rejected.activeWorkers)
    }

}
