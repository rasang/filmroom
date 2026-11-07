package dev.filmroom.diagnostics

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
class DetailTimingHostTest {
    private fun native(gpu:Boolean,reason:String=if(gpu)"approximate_resident_detail" else "gpu_not_built")=JSONObject()
        .put("quality",if(gpu)"ApproximateSameWorkingGrid" else "ExactSameWorkingGrid")
        .put("identity",JSONObject().put("width",1152).put("height",1536))
        .put("backend",JSONObject().put("actualBackend",if(gpu)"wgpu" else "cpu").put("requestedGpu",true).put("cpuFallback",!gpu).put("reason",reason).put("gpuBatches",if(gpu)12 else 0))
    @Test fun timestampsSeparateQueueReservationNativeAndDrawAndFinishOnce(){
        var time=100L;val out=mutableListOf<DetailTimingSnapshot>()
        val t=DetailTiming(DetailAttemptKind.REGION,8,now={time},record=out::add)
        time=105;t.mark(DetailTimingPoint.RENDER_QUEUED);time=111;t.mark(DetailTimingPoint.WORKER_STARTED)
        time=120;t.mark(DetailTimingPoint.RESERVATION_REQUESTED);time=160;t.mark(DetailTimingPoint.RESERVATION_ACQUIRED)
        time=162;t.mark(DetailTimingPoint.JNI_ENTER);time=190;t.mark(DetailTimingPoint.JNI_EXIT)
        time=200;t.mark(DetailTimingPoint.CONVERSION_END);time=203;t.mark(DetailTimingPoint.ADOPTED);time=210;t.drawn()
        time=300;t.mark(DetailTimingPoint.JNI_ENTER);t.drawn();t.finish(DetailTimingTerminal.CANCELLED)
        assertEquals(1,out.size);val s=out.single();assertEquals(8L,s.context);assertEquals(62L,s.offsets[DetailTimingPoint.JNI_ENTER]);assertEquals(110L,s.offsets[DetailTimingPoint.FIRST_DRAW]);assertEquals(DetailTimingTerminal.DRAW_CALLBACK,s.terminal)
    }
    @Test fun actualCpuFallbackIsNotInferredAsGpu(){
        val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.OPEN,record=out::add)
        t.validatedBackend(native(false));t.finish(DetailTimingTerminal.OPEN_READY)
        val s=out.single();assertEquals(DetailActualBackend.CPU,s.backend);assertEquals(DetailActualQuality.EXACT_SAME_WORKING_GRID,s.quality);assertEquals(true,s.requestedGpu);assertEquals(true,s.cpuFallback);assertEquals(0L,s.confirmedSubmits)
    }
    @Test fun actualGpuCarriesApproximateQualityAndCumulativeConfirmedCount(){
        val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.REGION,record=out::add)
        t.validatedBackend(native(true));t.drawn();val s=out.single()
        assertEquals(DetailActualBackend.WGPU,s.backend);assertEquals(DetailActualQuality.APPROXIMATE_SAME_WORKING_GRID,s.quality);assertEquals(false,s.cpuFallback);assertEquals(12L,s.confirmedSubmits)
    }
    @Test fun unknownReasonAndExceptionTextNeverEnterFields(){
        val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.OPEN,record=out::add)
        t.validatedBackend(native(false,"/private/photos/person@example.com"));t.failed(IllegalStateException("SECRET filename location"))
        val f=out.single().fields().toString();assertTrue(f.contains("unknown"));assertFalse(f.contains("private"));assertFalse(f.contains("SECRET"));assertFalse(f.contains("@"));assertEquals(DetailDiagnosticFailure.OTHER,out.single().failure)
    }
    @Test fun unobservedBackendIsAbsentRatherThanInventedCpu(){
        val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.OPEN,record=out::add)
        t.failed(CancellationException("secret"));assertNull(out.single().backend);assertFalse(out.single().fields().containsKey("detailRequestedGpu"));assertEquals(DetailTimingTerminal.CANCELLED,out.single().terminal)
    }
    @Test fun sourceRouteDimensionsAndOpenPhasesRoundTripTyped(){
        var time=0L;val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.OPEN,now={time},record=out::add)
        time=180;t.mark(DetailTimingPoint.SETTLE_END);t.source(false,1152,1536)
        time=200;t.mark(DetailTimingPoint.DECODE_START);time=400;t.mark(DetailTimingPoint.DECODE_END);t.finish(DetailTimingTerminal.OPEN_READY)
        val restored=SafeInfo.parse(SafeInfo(detailTiming=out.single()).fields()).detailTiming!!
        assertEquals(out.single(),restored);assertEquals(DetailSourceRoute.DECODE_ORIGINAL,restored.sourceRoute)
        assertEquals(180L,restored.offsets[DetailTimingPoint.SETTLE_END])
    }
    @Test fun originalLinearRouteDoesNotClaimDecode(){
        val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.OPEN,record=out::add)
        t.source(true,8,7);t.finish(DetailTimingTerminal.OPEN_READY)
        assertEquals(DetailSourceRoute.EXISTING_LINEAR,out.single().sourceRoute);assertFalse(out.single().fields().containsKey("detailTimingDECODE_START"))
    }
    @Test fun diskParserDropsUntrustedKeysOutOfRangeAndMalformedTypes(){
        val f=mapOf("detailAttempt" to "1","detailContext" to "1","detailKind" to "REGION","detailTerminal" to "DRAW_CALLBACK","detailTimingJNI_ENTER" to "-1","detailTimingJNI_EXIT" to "604800001","detailGridWidth" to "9000","detailConfirmedSubmits" to "1.5","detailRequestedGpu" to "yes","detailActualBackend" to "secret","detailPhotoPath" to "/private/a","detailTimingUNKNOWN" to "7","detailBackendReason" to "secret")
        val s=DetailTimingSnapshot.parse(f)!!;val fields=s.fields();assertEquals(5,fields.size);assertEquals(DetailBackendReason.UNKNOWN,s.reason);assertTrue(s.offsets.isEmpty());assertNull(s.width);assertNull(s.backend);assertNull(s.requestedGpu);assertNull(s.confirmedSubmits)
    }
    @Test fun parserRequiresKnownKindTerminalAndPositiveLocalTokens(){
        val f=mapOf("detailAttempt" to "1","detailContext" to "2","detailKind" to "OPEN","detailTerminal" to "OPEN_READY")
        assertNotNull(DetailTimingSnapshot.parse(f));for((key,value)in listOf("detailAttempt" to "0","detailContext" to "-1","detailKind" to "PRIVATE","detailTerminal" to "PRIVATE"))assertNull(DetailTimingSnapshot.parse(f+(key to value)))
    }
    @Test fun jsonRoundTripKeepsBooleansAndNumbersTyped(){
        val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.OPEN,record=out::add);t.validatedBackend(native(true));t.finish(DetailTimingTerminal.OPEN_READY)
        val j=JSONObject(SafeInfo(detailTiming=out.single()).typedFields())
        assertEquals(true,j.get("detailRequestedGpu"));assertEquals(12L,(j.get("detailConfirmedSubmits")as Number).toLong());assertEquals("WGPU",j.getString("detailActualBackend"))
    }
    @Test fun presentationPreservesOriginalClockAndContextButHasDistinctAttempt(){
        var time=100L;val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.OPEN,now={time},record=out::add)
        time=300;t.mark(DetailTimingPoint.JNI_EXIT);t.validatedBackend(native(true));t.finish(DetailTimingTerminal.OPEN_READY)
        val p=t.presentation();time=450;p.drawn();assertEquals(2,out.size);assertEquals(out[0].context,out[1].context);assertNotEquals(out[0].attempt,out[1].attempt);assertEquals(350L,out[1].offsets[DetailTimingPoint.FIRST_DRAW]);assertEquals(200L,out[1].offsets[DetailTimingPoint.JNI_EXIT]);assertEquals(DetailActualBackend.WGPU,out[1].backend)
    }
    @Test fun phaseMarkIsFirstObservationAndMonotonicClockIsOnlySource(){
        var time=50L;val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.OPEN,now={time},record=out::add)
        time=45;t.mark(DetailTimingPoint.SOURCE_START);time=70;t.mark(DetailTimingPoint.SOURCE_START);t.finish(DetailTimingTerminal.FAILED)
        assertEquals(0L,out.single().offsets[DetailTimingPoint.SOURCE_START])
    }
    @Test fun fixedFailureMappingOmitsRawNativeErrorCodes(){
        assertEquals(DetailDiagnosticFailure.GPU_UNCERTAIN,detailDiagnosticFailure(dev.filmroom.engine.DetailUnavailable("GPU_COMPLETION_UNCERTAIN")))
        assertEquals(DetailDiagnosticFailure.OTHER,detailDiagnosticFailure(dev.filmroom.engine.DetailUnavailable("secret")))
        assertEquals(DetailDiagnosticFailure.SOURCE_IO,detailDiagnosticFailure(java.io.IOException("secret")))
        assertEquals(DetailDiagnosticFailure.ALLOCATION,detailDiagnosticFailure(OutOfMemoryError("secret")))
    }
    @Test fun reusedRateGateIsBoundedAndReportsSuppressedRecords(){
        val gate=PreviewDiagnosticRateGate();repeat(8){assertNotNull(gate.accept(100))};repeat(12){assertNull(gate.accept(200))};assertEquals(12L,gate.accept(1000))
    }
    @Test fun missedDrawDeadlineDoesNotEraseLaterSlowMainThreadAdoption(){
        var time=0L;val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.REGION,now={time},record=out::add)
        time=10;t.mark(DetailTimingPoint.RETURNED);time=1010;t.finish(DetailTimingTerminal.DRAW_NOT_OBSERVED);t.finish(DetailTimingTerminal.DRAW_NOT_OBSERVED)
        time=1500;t.mark(DetailTimingPoint.ADOPTED);time=1700;t.drawn();t.drawn()
        assertEquals(2,out.size);assertEquals(DetailTimingTerminal.DRAW_NOT_OBSERVED,out[0].terminal);assertEquals(DetailTimingTerminal.DRAW_CALLBACK,out[1].terminal)
        assertEquals(out[0].attempt,out[1].attempt);assertEquals(1500L,out[1].offsets[DetailTimingPoint.ADOPTED]);assertEquals(1700L,out[1].offsets[DetailTimingPoint.FIRST_DRAW])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationBeforeCoroutineBodyStillRecordsTerminalOnce()=runTest {
        val records=mutableListOf<DetailTimingSnapshot>();val timing=DetailTiming(DetailAttemptKind.OPEN,record=records::add);var entered=false
        val job=launch(StandardTestDispatcher(testScheduler)){entered=true}
        timing.monitor(job);job.cancel();runCurrent();timing.failed(CancellationException())
        assertFalse(entered);assertEquals(1,records.size);assertEquals(DetailTimingTerminal.CANCELLED,records.single().terminal)
    }

    @Test fun deadlineDrawRaceNeverLabelsObservedDrawAsUnobserved(){
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try{repeat(40){
            val records=java.util.concurrent.CopyOnWriteArrayList<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.REGION,record=records::add)
            val gate=java.util.concurrent.CyclicBarrier(2)
            val a=pool.submit{gate.await();t.finish(DetailTimingTerminal.DRAW_NOT_OBSERVED)}
            val b=pool.submit{gate.await();t.drawn()};a.get();b.get()
            assertEquals(1,records.count{it.terminal==DetailTimingTerminal.DRAW_CALLBACK})
            assertTrue(records.filter{it.terminal==DetailTimingTerminal.DRAW_NOT_OBSERVED}.all{!it.offsets.containsKey(DetailTimingPoint.FIRST_DRAW)})
        }}finally{pool.shutdownNow()}
    }
    @Test fun realNativeSetupPanicReasonRemainsRecognizable(){
        assertEquals(DetailBackendReason.SETUP_PANICKED,DetailBackendReason.fromId("gpu_setup_panicked"))
    }

    @Test fun nativeOperationMetricsAreTypedWhitelistedAndRoundTrip(){
        val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.REGION,record=out::add)
        val response=native(true).put("detailPerformance",JSONObject().put("tileCacheHits",2).put("tileCacheMisses",19).put("gpuSubmitDelta",399).put("sourceReadMs",1.25).put("chainMs",1400.5).put("resultCopyMs",6.75).put("sourcePath","PRIVATE"))
        t.validatedBackend(response);t.sourceCache(true);t.retiring(DetailRetirementReason.SCIENCE_CHANGED);t.drawn()
        val snapshot=out.single();assertEquals(snapshot,SafeInfo.parse(SafeInfo(detailTiming=snapshot).fields()).detailTiming)
        assertEquals(399.0,snapshot.nativeMetrics[DetailNativeMetric.GPU_SUBMIT_DELTA]!!,0.0)
        assertTrue(snapshot.fields()["detailNativeGPU_SUBMIT_DELTA"] is Long)
        assertEquals(true,snapshot.sourceCacheHit);assertEquals(DetailRetirementReason.SCIENCE_CHANGED,snapshot.retirementReason)
        assertFalse(snapshot.fields().toString().contains("PRIVATE"));assertFalse(snapshot.fields().toString().contains("sourcePath"))
    }
    @Test fun malformedNativeMetricsCannotBecomeCountersOrPrivateFields(){
        val out=mutableListOf<DetailTimingSnapshot>();val t=DetailTiming(DetailAttemptKind.REGION,record=out::add)
        t.validatedBackend(native(true).put("detailPerformance",JSONObject().put("tileCacheHits",-1).put("tileCacheMisses",1.5).put("gpuSubmitDelta","399").put("sourceReadMs",604800001).put("chainMs","/private/file")))
        t.drawn();assertTrue(out.single().nativeMetrics.isEmpty())
        val fields=out.single().fields().mapValues{it.value.toString()}+mapOf("detailNativeCHAIN_MS" to "NaN","detailNativeGPU_SUBMIT_DELTA" to "2.5","detailRetirementReason" to "PRIVATE","detailSourceCacheHit" to "maybe")
        val restored=DetailTimingSnapshot.parse(fields)!!;assertTrue(restored.nativeMetrics.isEmpty());assertNull(restored.retirementReason);assertNull(restored.sourceCacheHit)
    }
    @Test fun firstDrawUsesActualRegionCounterAndMapsRegionClockToOpenStart(){
        var time=100L;val out=mutableListOf<DetailTimingSnapshot>()
        val open=DetailTiming(DetailAttemptKind.OPEN,now={time},record=out::add)
        val opened=native(true).also{it.getJSONObject("backend").put("gpuBatches",105)}
        time=1100;open.validatedBackend(opened);open.mark(DetailTimingPoint.JNI_EXIT);open.finish(DetailTimingTerminal.OPEN_READY)
        val presentation=open.presentation()
        time=1200;val region=DetailTiming(DetailAttemptKind.REGION,open.context,now={time},record=out::add)
        region.mark(DetailTimingPoint.RENDER_QUEUED);time=1210;region.mark(DetailTimingPoint.JNI_ENTER)
        time=2600;region.mark(DetailTimingPoint.JNI_EXIT);region.validatedBackend(native(true).also{it.getJSONObject("backend").put("gpuBatches",504)}.put("detailPerformance",JSONObject().put("gpuSubmitDelta",399)))
        time=2610;region.mark(DetailTimingPoint.ADOPTED);time=2620;region.drawn();presentation.drawnOutput(region);presentation.drawn()
        val drawn=out.last();assertEquals(504L,drawn.confirmedSubmits);assertEquals(399.0,drawn.nativeMetrics[DetailNativeMetric.GPU_SUBMIT_DELTA]!!,0.0)
        assertEquals(1110L,drawn.offsets[DetailTimingPoint.FIRST_REGION_JNI_ENTER]);assertEquals(2500L,drawn.offsets[DetailTimingPoint.FIRST_REGION_JNI_EXIT]);assertEquals(2520L,drawn.offsets[DetailTimingPoint.FIRST_DRAW])
        assertEquals(1000L,drawn.offsets[DetailTimingPoint.JNI_EXIT])
    }
    @Test fun differentContextCannotOverwriteFirstPresentationMetrics(){
        val out=mutableListOf<DetailTimingSnapshot>();val open=DetailTiming(DetailAttemptKind.OPEN,record=out::add)
        open.validatedBackend(native(true));val p=open.presentation()
        val other=DetailTiming(DetailAttemptKind.REGION,open.context+1,record=out::add)
        other.validatedBackend(native(false));other.mark(DetailTimingPoint.JNI_ENTER);p.drawnOutput(other);p.drawn()
        assertEquals(DetailActualBackend.WGPU,out.last().backend);assertEquals(12L,out.last().confirmedSubmits);assertFalse(out.last().offsets.containsKey(DetailTimingPoint.FIRST_REGION_JNI_ENTER))
    }

    @Test fun completeDiagnosticRecordRemainsWithinStorageLimitAndRoundTrips() {
        val detail=DetailTimingSnapshot(
            Long.MAX_VALUE,Long.MAX_VALUE,DetailAttemptKind.FIRST_PRESENTATION,
            DetailTimingPoint.entries.associateWith{604_800_000L},DetailTimingTerminal.DRAW_NOT_OBSERVED,
            DetailActualBackend.WGPU,DetailActualQuality.APPROXIMATE_SAME_WORKING_GRID,
            DetailBackendReason.FIELD_CAPACITY,false,false,1_800_000_000L,
            DetailSourceRoute.DECODE_ORIGINAL,1536,1536,DetailDiagnosticFailure.MEMORY_ADMISSION,1_000_000L,
            false,DetailRetirementReason.EXTERNAL_INVALIDATION,
            DetailNativeMetric.entries.associateWith{if(it.count)1_800_000_000.0 else 604_799_999.1234567},
        )
        val event=DiagnosticEvent(253402300799999L,Long.MAX_VALUE,"f".repeat(32),Long.MAX_VALUE,Long.MAX_VALUE,
            Operation.PREVIEW_PRESENTATION,Stage.END,Outcome.FAILED,info=SafeInfo(detailTiming=detail))
        val line=event.line()
        assertTrue("Complete bounded detail record must fit storage: ${line.toByteArray().size}",line.toByteArray().size<=4096)
        assertEquals(event,DiagnosticEvent.parse(line))
    }

}
