package dev.filmroom

import dev.filmroom.effects.EffectSurfaceDecoderPolicy as Policy
import dev.filmroom.effects.EffectSurfaceColorPolicy
import dev.filmroom.effects.EffectSurfaceFrameGate
import org.junit.Assert.*
import org.junit.Test

/** Recovery checks pinned to beta27 DEX constants and control flow. */
class EffectSurfaceDecoderPolicyRecoveryTest {
    @Test fun pixelAndEdgeBoundaries(){
        assertTrue(Policy.withinBudget(4096,3072));assertTrue(Policy.withinBudget(3072,4096))
        assertFalse(Policy.withinBudget(4096,3073));assertFalse(Policy.withinBudget(4097,1))
        assertFalse(Policy.withinBudget(0,100));assertFalse(Policy.withinBudget(-1,100))
        assertFalse(Policy.withinBudget(Int.MAX_VALUE,Int.MAX_VALUE))
    }
    @Test fun softwareIsOnlyAllowedAtBounded1080pArea(){
        val candidates=listOf(Policy.Candidate(3,false,true),Policy.Candidate(5,true,true))
        assertEquals(listOf(5,3),Policy.candidates(1920,1080,candidates))
        assertEquals(listOf(5),Policy.candidates(1920,1081,candidates))
        assertEquals(emptyList<Int>(),Policy.candidates(4097,1,candidates))
    }
    @Test fun hardwareFirstThenRateSupportedUnknownRejected(){
        val candidates=listOf(Policy.Candidate(0,false,true,true),Policy.Candidate(1,true,true,false),Policy.Candidate(2,true,true,null),Policy.Candidate(3,true,true,true),Policy.Candidate(4,true,false,true),Policy.Candidate(5,true,true,true))
        assertEquals(listOf(3,5,2,1,0),Policy.candidates(1280,720,candidates))
    }
    @Test fun feedBatchBound(){var calls=0;assertEquals(4,Policy.feedBatch{calls++;true});assertEquals(4,calls)
        calls=0;assertEquals(2,Policy.feedBatch{calls++;calls<=2});assertEquals(3,calls)}
    @Test fun retriesReleaseAndStopAtFour(){
        val created=mutableListOf<Int>();val released=mutableListOf<Int>();val failed=mutableListOf<Int>()
        val result=Policy.openFirst(listOf(0,1,2,3,4),{false},{i->created.add(i);i},{_,_->error("configure")},{released.add(it)},onFailure={i,_->failed.add(i)})
        assertNull(result);assertEquals(listOf(0,1,2,3),created);assertEquals(created,released);assertEquals(created,failed)
    }
    @Test fun firstSuccessIsRetained(){
        val released=mutableListOf<Int>()
        val result=Policy.openFirst(listOf(2,1,0),{false},{it},{_,i->if(i==2)error("start")},{released.add(it)})
        assertEquals(1 to 1,result);assertEquals(listOf(2),released)
    }
    @Test fun cancelledBeforeCreateDoesNothing(){var called=false;assertNull(Policy.openFirst(listOf(1),{true},{called=true;it},{_,_->},{called=true}));assertFalse(called)}
    @Test fun cancellationAfterCreateReleasesWithoutInitialize(){
        var cancelled=false;var initialized=false;var released=false
        assertNull(Policy.openFirst(listOf(1),{cancelled},{cancelled=true;it},{_,_->initialized=true},{released=true}))
        assertFalse(initialized);assertTrue(released)
    }
    @Test fun cancellationAfterInitializeReleases(){
        var cancelled=false;var released=false
        assertNull(Policy.openFirst(listOf(1),{cancelled},{it},{_,_->cancelled=true},{released=true}));assertTrue(released)
    }
    @Test fun oomIsReportedReleasedAndRethrown(){
        val oom=OutOfMemoryError("test");var released=false;var reported:Throwable?=null
        try{Policy.openFirst(listOf(1),{false},{it},{_,_->throw oom},{released=true},onFailure={_,e->reported=e});fail("must throw")}
        catch(e:OutOfMemoryError){assertSame(oom,e)}
        assertTrue(released);assertSame(oom,reported)
    }
    @Test fun frameGateWaitsForOldFrameAfterReset(){
        val gate=EffectSurfaceFrameGate();assertEquals(0,gate.generation);assertEquals(-1,gate.renderedGeneration)
        assertTrue(gate.offer(0));assertFalse(gate.offer(0));assertEquals(1,gate.reset());assertTrue(gate.inFlight)
        assertFalse(gate.offer(1));gate.consumed();assertFalse(gate.offer(0));assertTrue(gate.offer(1));assertEquals(1,gate.renderedGeneration)
    }
    @Test fun hdrAndWideGamutNeedConversion(){assertTrue(EffectSurfaceColorPolicy.needsConversion(6,1));assertTrue(EffectSurfaceColorPolicy.needsConversion(7,1));assertTrue(EffectSurfaceColorPolicy.needsConversion(3,6));assertFalse(EffectSurfaceColorPolicy.needsConversion(3,1))}
    @Test fun convertedOutputRequiresExplicitSdrEvidence(){
        for(s in listOf(1,2,4))assertTrue(EffectSurfaceColorPolicy.acceptsOutput(true,3,s))
        assertFalse(EffectSurfaceColorPolicy.acceptsOutput(true,-1,1));assertFalse(EffectSurfaceColorPolicy.acceptsOutput(true,3,-1))
        assertTrue(EffectSurfaceColorPolicy.acceptsOutput(false,-1,-1));assertFalse(EffectSurfaceColorPolicy.acceptsOutput(false,6,1));assertFalse(EffectSurfaceColorPolicy.acceptsOutput(false,3,6))
    }
}
