//! Native adapter for the separately owned, bounded two-pass highlight graph.
//! This layer owns routing and the Android wire, not scientific arithmetic.
use crate::research_global::{GlobalKind,GlobalPlan,streaming::{self,full_stage as stage}};
use crate::research_spatial::{MEMORY_CAP,MIB,TRANSFER_BYTES};
use serde_json::Value;
use std::path::Path;
type R<T> = Result<T,String>;

// Independent of the six full-line routes. Both approvals are required; opening
// an earlier route may never silently admit this newer graph.
pub(crate) const ROUTING_ADMITTED:bool=false;
pub(crate) fn routing_admitted()->bool {ROUTING_ADMITTED&&crate::research_streaming_route::ROUTING_ADMITTED}
pub(crate) const SAMPLE_SCOPE:&str="original bounded f64 boost witnesses on GPU f32 inputs and full-image GPU peak; complete scalar finite/hash validation";

pub(crate) fn needs_streaming(semantic:&GlobalPlan,width:u32,height:u32)->R<bool> {
    semantic.validate()?;
    if semantic.width!=1||semantic.height!=1||!matches!(semantic.kind,GlobalKind::Highlight{..}) {
        return Err("INVALID_GLOBAL_STREAM_PLAN".into());
    }
    if width==0||height==0||width>40000||height>40000{return Err("INVALID_GLOBAL_DIMENSIONS".into());}
    // Original GlobalPlan::new/spatial_plan and FilmOpticsPlan::validate limits.
    // Highlight has no extra resident pipeline allowance in its original plan.
    // AE deliberately stays on its original fixed 256x256 allocation.
    let plane=u64::from(width)*u64::from(height)*4;
    let device=5*plane+2*TRANSFER_BYTES+65536;
    Ok(plane>128*MIB||device+144*MIB>MEMORY_CAP)
}
pub(crate) fn selected(semantic:&GlobalPlan,width:u32,height:u32)->R<bool> {
    Ok(needs_streaming(semantic,width,height)?&&routing_admitted())
}

#[derive(Debug,PartialEq)]
pub(crate) struct Counters {pub samples:u64,pub dispatches:u64,pub upload:u64,pub readback:u64}
pub(crate) fn expected_counters(width:u32,height:u32)->R<Counters> {
    if width==0||height==0||width>40000||height>40000{return Err("INVALID_GLOBAL_DIMENSIONS".into());}
    let n=u64::from(width)*u64::from(height);
    let batches=n.div_ceil(u64::from(streaming::BATCH_SAMPLES));
    let chunks=n.div_ceil(crate::research_global::CHUNK as u64);
    let samples=1+3*(8*(chunks-1)+(n-(chunks-1)*crate::research_global::CHUNK as u64).min(8));
    // Original op10 uploads in both passes, op11/18 and op20/13 per
    // production batch, three op15 gathers per IO chunk, and four fixed ops.
    let dispatches=4+4*batches+9*chunks;
    // Every dispatch contributes the original 160-byte uniform upload.
    // Readback includes both coefficient/witness blocks and sticky-flag reads.
    Ok(Counters{samples,dispatches,upload:24*n+160*dispatches,readback:12*n+80+4*batches})
}
pub(crate) fn validate_summary_metrics(v:&Value,width:u32,height:u32)->R<()> {
    let c=expected_counters(width,height)?;
    if v["passCount"]!=2||v["passes"]!=2||v["chunkCapacitySamples"]!=streaming::BATCH_SAMPLES
        ||v["sampleCount"].as_u64()!=Some(c.samples)||v["dispatches"].as_u64()!=Some(c.dispatches)
        ||v["uploadBytes"].as_u64()!=Some(c.upload)||v["readbackBytes"].as_u64()!=Some(c.readback)
        ||v["sampleScope"]!=SAMPLE_SCOPE||v["sampleToleranceAbsolute"].as_f64()!=Some(crate::research_global::NUMERIC_TOLERANCE)
        ||v["sampleToleranceRelative"].as_f64()!=Some(crate::research_global::NUMERIC_TOLERANCE)
        ||!v["highlightPeak"].as_f64().is_some_and(|x|x.is_finite()&&x.abs()<=1e15)
        ||["sampleMaxAbs","sampleRms","elapsedMs"].into_iter().any(|key|!v[key].as_f64().is_some_and(|x|x.is_finite()&&x>=0.))
    {return Err("GPU_GLOBAL_STREAM_REPORT".into());}
    Ok(())
}
/// Consume the genuine graph report, never synthesize measured counters.
pub(crate) fn normalize(raw:Value,p:&stage::StagePlan)->R<Value> {
    if raw["ok"]!=true||raw["schemaVersion"]!=1||raw["fullImage"]!=true||raw["fullResolution"]!=true||raw["operation"]!=streaming::OPERATION||raw["backend"]!=streaming::BACKEND
        ||raw["token"].as_u64()!=Some(p.token)||raw["manifestSha256"]!=p.manifest_sha256
        ||raw["width"]!=p.width||raw["height"]!=p.height
        ||raw["deviceBytes"]!=p.device_bytes||raw["combinedPeakBytes"]!=p.combined_peak_bytes
    {return Err("GPU_GLOBAL_STREAM_REPORT".into());}
    validate_summary_metrics(&raw,p.width,p.height)?;
    let mut safe=crate::research_streaming_route::summary(raw.clone(),p,streaming::BACKEND)?;
    for key in ["highlightPeak","passes","chunkCapacitySamples"] {safe[key]=raw[key].clone();}
    Ok(safe)
}
#[cfg(feature="gpu-diffusion-research")]
pub(crate) fn run_stage(dir:&Path,token:u64,budget:u64,sha:&str,mut cancel:impl FnMut()->bool)->R<Value> {
    let plan=stage::plan_stage_cancellable(dir,token,&mut cancel)?;
    normalize(stage::run_stage(dir,token,budget,sha,&mut cancel)?,&plan)
}

#[cfg(test)]
#[path="research_streaming_highlight_tests.rs"]
mod tests;
