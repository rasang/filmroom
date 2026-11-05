//! Versioned file-backed route selection and truthful Android-facing reports.
//! Selection is not admission: each stage's SOURCE_ADMITTED gate stays closed.
use serde_json::{Map, Value};

// Opening this route is a separate end-to-end admission decision. Merely
// compiling or reviewing a low-level graph must not reroute existing requests.
pub(crate) const ROUTING_ADMITTED: bool = false;
pub(crate) fn selected(width:u32,height:u32)->bool {ROUTING_ADMITTED && requires_streaming(width,height)}
pub(crate) fn requires_streaming(width: u32, height: u32) -> bool {
    // This is the original common resident allocation gate, not the streaming
    // batch capacity or a scientific/numeric error. Every affected resident
    // graph constructs FilmOpticsPlan and must pass this gate first. Stricter
    // stage-specific limits continue to fail in the original route until they
    // receive an independently reviewed, parameter-aware admission selector.
    if width==0 || height==0 || width>40000 || height>40000 {return false;}
    const MIB:u64=1024*1024;
    let plane=u64::from(width)*u64::from(height)*4;
    plane>128*MIB || 5*plane+2*4*MIB+65536+144*MIB>768*MIB
}

#[cfg(feature="diffusion-research")]
fn resident_allocation(width:u32,height:u32)->Result<(u64,u64,bool),String> {
    use crate::research_spatial::{MIB,MEMORY_CAP,TRANSFER_BYTES};
    if width==0||height==0||width>40000||height>40000{return Err("INVALID_STREAM_GEOMETRY".into());}
    let plane=width as u64*height as u64*4;
    let device=5*plane+2*TRANSFER_BYTES+65536;
    Ok((plane,device,plane>128*MIB||device+144*MIB>MEMORY_CAP))
}
#[cfg(feature="diffusion-research")]
pub(crate) fn needs_streaming_optics(semantic:&crate::research_spatial::FilmOpticsPlan,width:u32,height:u32)->Result<bool,String> {
    use crate::research_spatial::{MIB,MEMORY_CAP,PIPELINE_RESERVATION_BYTES};
    semantic.validate()?;if semantic.width!=1||semantic.height!=1{return Err("INVALID_STREAM_SEMANTIC_PLAN".into());}
    let(_,device,base)=resident_allocation(width,height)?;
    Ok(base||device+144*MIB+PIPELINE_RESERVATION_BYTES>MEMORY_CAP)
}
#[cfg(feature="diffusion-research")]
pub(crate) fn needs_streaming_dir(semantic:&crate::research_dir::DirPlan,width:u32,height:u32,correction:Option<f64>)->Result<bool,String> {
    use crate::research_spatial::{MIB,MEMORY_CAP,PIPELINE_RESERVATION_BYTES};
    semantic.validate()?;if semantic.width!=1||semantic.height!=1{return Err("INVALID_STREAM_SEMANTIC_PLAN".into());}
    let extra=if let Some(v)=correction {crate::research_log_dir::log_prefix::validate_correction(1,1,v)?;crate::research_log_dir::log_prefix::PIPELINE_BYTES+crate::research_log_dir::log_prefix::HOST_BYTES}else{0};
    let(_,device,base)=resident_allocation(width,height)?;
    Ok(base||device+144*MIB+PIPELINE_RESERVATION_BYTES+extra>MEMORY_CAP)
}
#[cfg(feature="diffusion-research")]
pub(crate) fn needs_streaming_grain(semantic:&crate::research_grain::GrainPlan,width:u32,height:u32)->Result<bool,String> {
    use crate::research_spatial::{MIB,MEMORY_CAP};
    use crate::research_grain::guarded_graph as graph;
    graph::validate(semantic,semantic.rng_mode)?;if semantic.width!=1||semantic.height!=1{return Err("INVALID_STREAM_SEMANTIC_PLAN".into());}
    let(_,device,base)=resident_allocation(width,height)?;
    let combined=device+144*MIB+graph::BATCH_RESERVATION_BYTES+graph::PIPELINE_RESERVATION_BYTES+graph::scratch_bytes(semantic.rng_mode)?+graph::CODE_HOST_RESERVATION_BYTES;
    Ok(base||combined>MEMORY_CAP)
}
#[cfg(feature="diffusion-research")]
pub(crate) fn needs_streaming_finish(semantic:&crate::research_finish_tail::TailPlan,width:u32,height:u32,prefix_table_scalars:Option<u64>)->Result<bool,String> {
    use crate::research_spatial::{MIB,MEMORY_CAP};
    semantic.validate()?;if semantic.width!=1||semantic.height!=1{return Err("INVALID_STREAM_SEMANTIC_PLAN".into());}
    let(_,device,base)=resident_allocation(width,height)?;
    let driver=semantic.expected_driver_allowance();
    let combined=if let Some(s)=prefix_table_scalars {
        // Same immutable prefix allocation/host formula as research_finish::fused::plan.
        // Table limits are common to both routes, never a reason to switch layout.
        if s==0||s>u32::MAX as u64-64||4*s+256>128*MIB{return Err("GPU_FINISH_TABLE_LIMIT".into());}
        device+20*s+256+96*MIB+driver
    }else{device+88*MIB+driver};
    Ok(base||combined>MEMORY_CAP)
}

pub(crate) fn stage_code(operation:&str)->u8 {match operation {"fullFilmOpticsStreamV1"=>15,"fullFilmGrainStreamV1"=>16,"fullFilmDirDensityStreamV1"=>17,"fullExposureLogDirStreamV1"=>18,"fullScanTailStreamV1"=>19,"fullFinishTailStreamV1"=>20,"fullStreamingHighlightBoost"=>21,_=>0}}

pub(crate) fn backend(operation: &str) -> Option<&'static str> {
    Some(match operation {
        "fullFilmOpticsStreamV1" => "gpu_spatial_stream_f32_v1",
        "fullFilmGrainStreamV1" => "gpu_grain_stream_guarded64_spatial_f32_v1",
        "fullFilmDirDensityStreamV1" => "gpu_dir_stream_f32_v1",
        "fullExposureLogDirStreamV1" => "gpu_exposure_log_dir_stream_f32_v1",
        "fullScanTailStreamV1" => "gpu_scan_tail_stream_f32_v1",
        "fullFinishTailStreamV1" => "gpu_finish_tail_stream_f32_v1",
        "fullStreamingHighlightBoost" => "gpu_global_highlight_stream_f32_v1",
        _ => return None,
    })
}

fn sanitized_keys(operation:&str)->Option<Vec<&'static str>> {
    backend(operation)?;
    let mut keys=vec!["schemaVersion","operation","token","manifestSha256","width","height","channels","gpuUsed","fullImage","fullResolution","layout","backend","deviceBytes","combinedPeakBytes","passCount","sampleCount","sampleMaxAbs","sampleRms","elapsedMs","vendor","uploadBytes","readbackBytes","dispatches","nativeStageSealed","contextCount","sampleToleranceAbsolute","sampleToleranceRelative","sampleScope"];
    match operation {
        "fullStreamingHighlightBoost"=>keys.extend(["highlightPeak","passes","chunkCapacitySamples"]),
        "fullFilmDirDensityStreamV1"|"fullExposureLogDirStreamV1"=>{
            keys.extend(["dirActive","chunkCount","pointwiseToleranceAbsolute","pointwiseToleranceRelative"]);
            if operation=="fullExposureLogDirStreamV1" {keys.extend(["logSampleCount","logSampleMaxAbs","logSampleRms"]);}
        }
        "fullScanTailStreamV1"|"fullFinishTailStreamV1"=>keys.extend(["prefixSampleCount","glareGenerationGpu","glareRandomWords","glareChunks","glarePredicateScope","glareAmbiguityBound"]),
        "fullFilmGrainStreamV1"=>keys.extend(["grainActive","sublayers","layerCount","chunkCount","rngMode","distributionArithmetic","microActive","finalBlurActive","amountBlendActive","poissonCountMismatches","binomialCountMismatches","randomWordCountMismatches","samplerTupleMismatches","samplerSampleCount","exactRngWordsChecked","softwareArithmeticChecks","numericFlagReads","samplerMaxAbs","samplerRms","stochasticScope","microstructureArithmetic"]),
        _=>{},
    }
    Some(keys)
}

pub(crate) fn valid_summary_identity(summary:&Value, operation:&str, token:u64, sha:&str, width:u32, height:u32)->bool {
    let Some(expected_backend)=backend(operation) else {return false;};
    let Some(object)=summary.as_object() else {return false;};
    let Some(keys)=sanitized_keys(operation) else {return false;};
    if object.len()!=keys.len()||object.keys().any(|key| !keys.contains(&key.as_str())) {return false;}
    // Raw ok (including false/null), test markers and unknown fields are not
    // admitted here. Only Android's closed sanitized payload reaches adoption.
    let layout=match operation {"fullFilmOpticsStreamV1"=>"fullLineFileBackedV1","fullFilmGrainStreamV1"=>"fullLineCoordinateGrainV1","fullFilmDirDensityStreamV1"|"fullExposureLogDirStreamV1"=>"fullLineDirV1","fullStreamingHighlightBoost"=>"twoPassFileBackedHighlightV1",_=>"fullLineFileBackedFinishV1"};
    // Android authenticates raw ok then intentionally removes it from the sealed summary.
    summary["gpuUsed"]==true && summary["nativeStageSealed"]==false && summary["contextCount"]==1
        && summary["fullImage"]==true && summary["fullResolution"]==true && summary["schemaVersion"]==1 && summary["channels"]==3
        && summary["operation"].as_str()==Some(operation) && summary["backend"].as_str()==Some(expected_backend)
        && summary["token"].as_u64()==Some(token) && summary["manifestSha256"].as_str()==Some(sha)
        && summary["width"].as_u64()==Some(width as u64) && summary["height"].as_u64()==Some(height as u64)
        && summary["layout"].as_str()==Some(layout)
}

/// Normalize only values actually returned by the typed graph. In particular,
/// elapsed/transfer counters are never fabricated, and a graph is never a seal.
pub(crate) fn summary(raw: Value, plan: &impl serde::Serialize, actual_backend: &str) -> Result<Value, String> {
    let p = serde_json::to_value(plan).map_err(|_| "GPU_STREAM_REPORT")?;
    if raw["gpuUsed"] != true || raw["nativeStageSealed"] != false || raw["contextCount"] != 1
        || raw["layout"] != p["layout"] || backend(p["operation"].as_str().unwrap_or("")) != Some(actual_backend)
    { return Err("GPU_STREAM_REPORT".into()); }
    for key in ["vendor","uploadBytes","readbackBytes","dispatches","passCount","sampleCount"] {
        if raw.get(key).and_then(Value::as_u64).is_none() {return Err("GPU_STREAM_REPORT".into());}
    }
    for key in ["sampleMaxAbs","sampleRms","elapsedMs","sampleToleranceAbsolute","sampleToleranceRelative"] {
        if !raw.get(key).and_then(Value::as_f64).is_some_and(|v|v.is_finite()&&v>=0.) {return Err("GPU_STREAM_REPORT".into());}
    }
    if raw.get("sampleScope").and_then(Value::as_str).is_none() {return Err("GPU_STREAM_REPORT".into());}
    let mut o = Map::new();
    for key in ["vendor", "uploadBytes", "readbackBytes", "dispatches", "passCount", "sampleCount", "sampleMaxAbs", "sampleRms", "elapsedMs",
        "sampleToleranceAbsolute", "sampleToleranceRelative", "sampleScope",
        "dirActive", "chunkCount", "pointwiseToleranceAbsolute", "pointwiseToleranceRelative",
        "logSampleCount", "logSampleMaxAbs", "logSampleRms", "prefixSampleCount", "glareGenerationGpu", "glareRandomWords", "glareChunks", "glarePredicateScope", "glareAmbiguityBound"] {
        if let Some(v) = raw.get(key) {
            if v.is_number() && !v.as_f64().is_some_and(|n| n.is_finite() && n >= 0.) { return Err("GPU_STREAM_REPORT".into()); }
            o.insert(key.into(), v.clone());
        }
    }
    for key in ["schemaVersion", "operation", "token", "manifestSha256", "width", "height", "layout", "deviceBytes", "combinedPeakBytes"] {
        o.insert(key.into(), p.get(key).ok_or("GPU_STREAM_REPORT")?.clone());
    }
    for (key, value) in [("ok", true.into()), ("gpuUsed", true.into()), ("fullImage", true.into()), ("fullResolution", true.into()),
        ("channels", 3.into()), ("backend", actual_backend.into()), ("nativeStageSealed", false.into()), ("contextCount", 1.into())] { o.insert(key.into(), value); }
    Ok(Value::Object(o))
}

pub(crate) fn file_hash(path: &std::path::Path, expected_bytes: u64, c: &mut impl FnMut()->bool) -> Result<String,String> {
    use std::io::Read;
    use sha2::{Digest,Sha256};
    let meta=std::fs::symlink_metadata(path).map_err(|_| "GPU_STREAM_IO")?;
    if !meta.is_file() || meta.file_type().is_symlink() || meta.len()!=expected_bytes {return Err("INVALID_STREAM_FILE".into());}
    let mut file=std::fs::File::open(path).map_err(|_| "GPU_STREAM_IO")?;
    let mut hash=Sha256::new(); let mut left=expected_bytes; let mut bytes=[0u8;65536];
    while left>0 {if c(){return Err("CANCELLED".into());}let count=left.min(bytes.len() as u64) as usize;file.read_exact(&mut bytes[..count]).map_err(|_|"GPU_STREAM_IO")?;hash.update(&bytes[..count]);left-=count as u64;}
    if c(){return Err("CANCELLED".into());}
    Ok(hash.finalize().iter().map(|b|format!("{b:02x}")).collect())
}

/// One production JNI envelope for an already validated typed native plan.
/// Fixture writers reuse this function; they never invent a parallel wrapper.
pub(crate) fn plan_envelope(plan:&impl serde::Serialize)->Result<Value,String> {
    let plan=serde_json::to_value(plan).map_err(|_|"INVALID_STREAM_PLAN")?;
    Ok(serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuStreamingStage","plan":plan}))
}

#[cfg(feature = "diffusion-research")]
pub(crate) fn plan(operation: &str, dir: &std::path::Path, token: u64) -> Result<Value, String> {
    let p = match operation {
        "fullFilmOpticsStreamV1" => serde_json::to_value(crate::research_streaming_spatial::full_stage::plan_stage(dir, token)?),
        "fullFilmGrainStreamV1" => serde_json::to_value(crate::research_streaming_grain::full_stage::plan_stage(dir, token)?),
        "fullFilmDirDensityStreamV1" | "fullExposureLogDirStreamV1" => serde_json::to_value(crate::research_streaming_dir::full_stage::plan_stage(dir, token)?),
        "fullScanTailStreamV1" => serde_json::to_value(crate::research_streaming_finish::full_stage::plan_stage(dir, token)?),
        "fullFinishTailStreamV1" => serde_json::to_value(crate::research_finish::streaming::plan_stage(dir, token)?),
        "fullStreamingHighlightBoost" => serde_json::to_value(crate::research_global::streaming::full_stage::plan_stage(dir, token)?),
        _ => return Err("INVALID_SCIENCE_OPERATION".into()),
    }.map_err(|_| "INVALID_STREAM_PLAN")?;
    if p["operation"].as_str() != Some(operation) || p["token"].as_u64() != Some(token) { return Err("INVALID_STREAM_IDENTITY".into()); }
    Ok(p)
}

#[cfg(feature = "gpu-diffusion-research")]
pub(crate) fn run(operation: &str, dir: &std::path::Path, token: u64, budget: u64, sha: &str, c: impl FnMut() -> bool) -> Result<Value, String> {
    // Validate the exact requested operation before any device or file write.
    let p = plan(operation, dir, token)?;
    if p["manifestSha256"].as_str() != Some(sha) { return Err("INVALID_STREAM_IDENTITY".into()); }
    match operation {
        "fullFilmOpticsStreamV1" => crate::research_streaming_spatial::full_stage::run_stage(dir, token, budget, sha, c),
        "fullFilmGrainStreamV1" => crate::research_streaming_grain::full_stage::run_stage(dir, token, budget, sha, c),
        "fullFilmDirDensityStreamV1" | "fullExposureLogDirStreamV1" => crate::research_streaming_dir::full_stage::run_stage(dir, token, budget, sha, c),
        "fullScanTailStreamV1" => crate::research_streaming_finish::full_stage::run_stage(dir, token, budget, sha, c),
        "fullFinishTailStreamV1" => crate::research_finish::streaming::run_stage(dir, token, budget, sha, c),
        "fullStreamingHighlightBoost" => crate::research_streaming_highlight::run_stage(dir, token, budget, sha, c),
        _ => Err("INVALID_SCIENCE_OPERATION".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn selection_uses_original_resident_allocation_boundary_not_batch_capacity() {
        for (w,h) in [(1024,1024),(1025,1024),(6000,4000),(8192,3942)] {assert!(!requires_streaming(w,h));}
        for (w,h) in [(8192,3943),(8192,6144),(40000,40000)] {assert!(requires_streaming(w,h));}
        for (w,h) in [(0,1),(1,0),(40001,1),(u32::MAX,u32::MAX)] {assert!(!requires_streaming(w,h));}
    }
    #[test] fn pending_stream_admission_does_not_reroute_any_resident_request() { assert!(!ROUTING_ADMITTED); for (w,h) in [(1,1),(1024,1024),(1025,1024),(8192,6144),(40000,40000)] {assert!(!selected(w,h));} }
    #[test] fn versioned_stream_stage_codes_do_not_reuse_resident_codes() {for (op,code) in [("fullFilmOpticsStreamV1",15),("fullFilmGrainStreamV1",16),("fullFilmDirDensityStreamV1",17),("fullExposureLogDirStreamV1",18),("fullScanTailStreamV1",19),("fullFinishTailStreamV1",20),("fullStreamingHighlightBoost",21)] {assert_eq!(stage_code(op),code);}assert_eq!(stage_code("fullScanTail"),0);}
    #[test] fn resident_and_unknown_operations_are_not_streamed() { assert!(backend("fullScanTail").is_none()); assert!(backend("fullScanTailStreamV2").is_none()); }
    #[test] fn plan_envelope_preserves_the_native_plan_and_exact_jni_wrapper() {
        let plan=serde_json::json!({"operation":"fullFilmOpticsStreamV1","schemaVersion":1,"token":7});
        let wrapped=plan_envelope(&plan).unwrap();
        assert_eq!(wrapped,serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuStreamingStage","plan":plan}));
        assert_eq!(wrapped.as_object().unwrap().len(),4);
    }
    #[test] fn report_does_not_invent_timing_or_accept_a_seal() {
        let p = serde_json::json!({"schemaVersion":1,"operation":"fullScanTailStreamV1","token":1,"manifestSha256":"a".repeat(64),"width":2,"height":3,"layout":"fullLineFileBackedFinishV1","deviceBytes":1,"combinedPeakBytes":2});
        let raw = serde_json::json!({"gpuUsed":true,"nativeStageSealed":false,"contextCount":1,"layout":"fullLineFileBackedFinishV1"});
        assert!(summary(raw.clone(), &p, "gpu_scan_tail_stream_f32_v1").is_err()); // Missing measured counters never become fabricated zeros.
        let mut bad=raw; bad["nativeStageSealed"]=true.into(); assert!(summary(bad,&p,"gpu_scan_tail_stream_f32_v1").is_err());
    }
    #[test] fn parent_adoption_accepts_android_sanitized_summary_without_raw_ok() {
        let mut v=serde_json::json!({"schemaVersion":1,"operation":"fullScanTailStreamV1","token":7,"manifestSha256":"a".repeat(64),"width":8192,"height":6144,"channels":3,"layout":"fullLineFileBackedFinishV1","backend":"gpu_scan_tail_stream_f32_v1","gpuUsed":true,"fullImage":true,"fullResolution":true,"nativeStageSealed":false,"contextCount":1});
        // A shape-only unit fixture. The ignored cross-language test below consumes real Android output.
        for key in sanitized_keys("fullScanTailStreamV1").unwrap() {if v.get(key).is_none(){v[key]=0.into();}}
        assert!(v.get("ok").is_none());assert!(valid_summary_identity(&v,"fullScanTailStreamV1",7,&"a".repeat(64),8192,6144));
        for value in [Value::Bool(false),Value::Bool(true),Value::Null] {let mut bad=v.clone();bad["ok"]=value;assert!(!valid_summary_identity(&bad,"fullScanTailStreamV1",7,&"a".repeat(64),8192,6144));}
        let mut unknown=v.clone();unknown["extra"]=true.into();assert!(!valid_summary_identity(&unknown,"fullScanTailStreamV1",7,&"a".repeat(64),8192,6144));
        v["backend"]="gpu_scan_tail_f32_v1".into();assert!(!valid_summary_identity(&v,"fullScanTailStreamV1",7,&"a".repeat(64),8192,6144));
    }

}

#[cfg(all(test,feature="diffusion-research"))]
mod allocation_tests {
    use super::*;
    fn tail(glare:bool)->crate::research_finish_tail::TailPlan {
        use crate::research_finish_tail::{TailPlan,TailOptions,GlarePlan};
        let id=[[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]];
        let d=spektrafilm_core::pipeline::GpuScanTailDescriptor{adapt:id,xyz_to_rgb:id,xyz_remap:None,glare_rgb_offset:[1.;3],gamut:None,rgb_identity:id,output_cctf_encoding:false};
        TailPlan::new(&d,1,1,TailOptions{glare:glare.then_some(GlarePlan{percent:0.3,roughness:0.7,blur:0.8}),lens_sigma:0.,sharp_radius:0.,sharp_amount:0.,brightness_ev:0.,finalize_cctf:!glare,preview_output_space:None}).unwrap()
    }
    #[test] fn finish_driver_and_prefix_make_24mp_selector_stage_specific() {
        let mut invalid=tail(true);invalid.options.finalize_cctf=true;
        assert_eq!(invalid.validate().unwrap_err(),"INVALID_TAIL_PLAN");
        assert!(!needs_streaming_finish(&tail(false),6000,4000,None).unwrap());
        assert!(needs_streaming_finish(&tail(true),6000,4000,None).unwrap());
        assert!(!needs_streaming_finish(&tail(false),6000,4000,Some(1536)).unwrap());
        assert!(needs_streaming_finish(&tail(true),6000,4000,Some(1536)).unwrap());
        let mut bad=tail(false);bad.width=2;assert!(needs_streaming_finish(&bad,6000,4000,None).is_err());
        assert!(needs_streaming_finish(&tail(false),0,4000,None).is_err());
        assert!(needs_streaming_finish(&tail(false),6000,4000,Some(u64::MAX)).is_err());
    }
    #[test] fn optics_and_grain_use_exact_resident_overheads_without_numeric_fallback() {
        use crate::research_spatial::FilmOpticsPlan;
        let optics=FilmOpticsPlan::new(&spektrafilm_core::params::RuntimeParams::default(),1.,1,1).unwrap();
        assert!(!needs_streaming_optics(&optics,6000,4000).unwrap());assert!(needs_streaming_optics(&optics,8192,6144).unwrap());
        let grain=crate::research_grain::GrainPlan{width:1,height:1,active:false,sublayers:false,positive:false,monochrome:false,seed:42,origin:[0,0],rng_mode:crate::research_grain::RngMode::CoordinateSplitMix64,layers:std::array::from_fn(|_|vec![]),amount:1.,density_min:vec![0.;3],final_sigma:0.,micro_active:false,micro_spread:0.,micro_sigma_log:0.,micro_mu:0.};
        assert!(!needs_streaming_grain(&grain,6000,4000).unwrap());assert!(needs_streaming_grain(&grain,8192,6144).unwrap());
        let mut bad=grain;bad.amount=f64::NAN;assert!(needs_streaming_grain(&bad,8192,6144).is_err());
    }
}
