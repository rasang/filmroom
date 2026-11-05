use super::*;
use std::{fs,path::{Path,PathBuf}};

#[test]
fn fifty_megapixel_budget_is_fixed_complete_and_not_a_resident_plan() {
    let p=StreamHighlightPlan::new(8192,6144,2.,0.3,1.).unwrap();let b=p.memory().unwrap();
    assert!(GlobalPlan::highlight(8192,6144,2.,0.3,1.).is_err());
    assert_eq!((p.parameters().width,p.parameters().height),(1,1));
    assert_eq!(b.resident_device_bytes,29_425_664);
    assert_eq!(b.upload_staging_bytes,4_194_304);assert_eq!(b.device_bytes,33_619_968);
    assert_eq!(b.host_bytes,67_108_864);assert_eq!(b.driver_allowance_bytes,109_051_904);
    assert_eq!(b.helper_allowance_bytes,67_108_864);assert_eq!(b.combined_peak_bytes,276_889_600);
    assert_eq!(b.source_snapshot_bytes,1_207_959_552);assert_eq!(b.output_bytes,1_207_959_552);
    assert_eq!(b.disk_bytes,2_418_016_256);assert_eq!(b.pipeline_count,5);
    for (w,h) in [(1,1),(40000,40000)] {
        let other=StreamHighlightPlan::new(w,h,2.,0.3,1.).unwrap().memory().unwrap();
        assert_eq!(other.combined_peak_bytes,b.combined_peak_bytes);
        assert_eq!(other.disk_bytes,u64::from(w)*u64::from(h)*48+2*MIB);
    }
}
#[test]
fn typed_plan_rejects_false_layout_semantics_and_unknown_fields() {
    let p=StreamHighlightPlan::new(8192,6144,2.,0.3,1.).unwrap();
    let base=serde_json::to_value(&p).unwrap();
    for (key,value) in [("layout",serde_json::json!("fullLineFileBackedV1")),("schemaVersion",serde_json::json!(2)),("width",serde_json::json!(0)),("bogus",serde_json::json!(true))] {
        let mut v=base.clone();v[key]=value;assert!(serde_json::from_value::<StreamHighlightPlan>(v).is_err());
    }
    let mut wrong=base;wrong["semantic"]["combinedPeakBytes"]=1.into();
    assert!(serde_json::from_value::<StreamHighlightPlan>(wrong).is_err());
    assert!(StreamHighlightPlan::from_semantic_plan(GlobalPlan::highlight(2,1,2.,0.3,1.).unwrap(),8192,6144).is_err());
    assert!(StreamHighlightPlan::from_semantic_plan(GlobalPlan::log_exposure(1,1,1.).unwrap(),8192,6144).is_err());
}
#[test]
fn chunk_schedule_has_exact_original_block_order_and_only_one_short_tail() {
    for n in [1,1023,1024,1025,65535,65536,1_048_575,1_048_576,1_048_577,50_331_648,1_600_000_000] {
        let mut next=0u64;let mut blocks=0u64;
        for (start,count) in chunks(n,BATCH_SAMPLES).unwrap() {
            assert_eq!(start,next);assert_eq!(start%1024,0);assert!(count>0&&count<=BATCH_SAMPLES);
            for local in 0..count.div_ceil(1024) {
                let global=start+u64::from(local)*1024;
                assert_eq!(global,blocks*1024);
                assert_eq!(u64::from((count-local*1024).min(1024)),(n-global).min(1024));
                blocks+=1;
            }
            next+=u64::from(count);
        }
        assert_eq!(next,n);assert_eq!(blocks,n.div_ceil(1024));
    }
    assert!(chunks(3,1023).is_err());assert!(chunks(0,1024).is_err());
}
#[test]
fn continuation_preserves_max_bits_for_signed_zero_negative_and_hdr_values() {
    // Test oracle only. Production has no CPU maximum or image boost path.
    for mode in 0..5 {
        let input:Vec<[f32;3]>=(0..4099).map(|i|match mode {
            0=>[-0.,0.,-0.],1=>[-9.,-8.,-7.],2=>[0.,0.,0.],
            3=>[(i%77) as f32-38.,(i%29) as f32,0.],_=>[1e12,(i%5) as f32,1e14],
        }).collect();
        let partials:Vec<f32>=input.chunks(1024).map(|chunk|{
            let mut peak=-f32::MAX;for rgb in chunk {for &x in rgb {peak=peak.max(x);}}peak
        }).collect();
        let mut original=-f32::MAX;for &x in &partials{original=original.max(x);}
        for capacity in [1024,2048,4096] {
            let mut continued=-f32::MAX;
            for group in partials.chunks(capacity/1024){for &x in group{continued=continued.max(x);}}
            // Original op12 over the final single value introduces no tie with
            // -MAX because every admitted real value is within +/-1e15.
            let final_peak=(-f32::MAX).max(continued);
            assert_eq!(original.to_bits(),final_peak.to_bits());
        }
    }
}
#[test]
fn original_families_are_unchanged_and_control_retains_exact_max_loop() {
    let reference=super::super::specialized::shaders(&GlobalKind::Highlight{boost_ev:2.,boost_range:0.3,protect_ev:1.}).unwrap();
    let all=families().unwrap();assert_eq!(&all[..4],reference.as_slice());assert_eq!(all[4].0,[17,18,19,20]);
    let original=super::super::SHADER;
    let reduction="for(var j=0u;j<p.u[1].y;j=j+1u){peak=max(peak,gbound(saved.values[j]));}";
    assert!(original.contains(reduction));assert!(all[4].1.contains(reduction));
    assert!(all[4].1.contains("accum.values[1u+k]=probe.values[k]"));
    assert!(all[4].1.contains("probe.values[k]=accum.values[1u+k]"));
    for (_,shader) in all {
        let module=naga::front::wgsl::parse_str(&shader).unwrap();
        naga::valid::Validator::new(naga::valid::ValidationFlags::all(),naga::valid::Capabilities::empty()).validate(&module).unwrap();
        let mut bindings:Vec<_>=module.global_variables.iter().map(|(_,v)|v.binding.as_ref().unwrap().binding).collect();
        bindings.sort_unstable();assert_eq!(bindings,(0..8).collect::<Vec<_>>());
    }
    let runner=include_str!("streaming_gpu.rs");
    assert_eq!(runner.matches("Runner::new_with_shader_family_error_explicit_layout").count(),1);
    assert!(runner.contains("r.dispatch(scalar(19)"));assert!(runner.contains("r.dispatch(scalar(20)"));
    assert!(!runner.contains("coefficients.to_le_bytes"));
}
fn root()->PathBuf{super::super::tests::root()}
fn sources(root:&Path,w:u32,h:u32,mode:u32)->[PathBuf;3]{
    std::array::from_fn(|ch|{
        let path=root.join(format!("source-{w}x{h}-mode{mode}-{ch}"));
        let n=w as usize*h as usize;
        let bytes:Vec<u8>=(0..n).flat_map(|i|{
            let x:f64=match mode{1=>0.,2=>-0.25,3 if i==17&&ch==2=>2e15,
                4 if i==17&&ch==2=>f64::NAN,5 if i==17&&ch==2=>1e14,
                _=>if i+1==n&&ch==2{6.}else{((i*29+ch*11)%1009) as f64/128.-0.04}};
            x.to_le_bytes()
        }).collect();fs::write(&path,bytes).unwrap();path
    })
}
#[test]
fn production_stage_stays_closed_and_input_is_immutable() {
    let root=root();let sources=sources(&root,3,2,0);let before=sources.each_ref().map(|p|fs::read(p).unwrap());
    let p=StreamHighlightPlan::new(3,2,2.,0.3,1.).unwrap();let dir=root.join("gpu-stage-8101");
    let plan=full_stage::prepare_stage(sources.each_ref().map(|p|p.as_path()),&dir,8101,&p,||false).unwrap();
    assert_eq!(full_stage::plan_stage(&dir,8101).unwrap(),plan);
    assert_eq!(plan.operation,OPERATION);assert_eq!(plan.layout,LAYOUT);
    assert_eq!(full_stage::run_stage(&dir,8101,plan.combined_peak_bytes,&plan.manifest_sha256,||false).unwrap_err(),"GPU_GLOBAL_STREAM_PENDING");
    assert!(!dir.join("candidate").exists());
    for ch in 0..3{assert_eq!(fs::read(&sources[ch]).unwrap(),before[ch]);}
    fs::remove_dir_all(root).unwrap();
}
#[test]
fn nonfinite_prepare_and_insufficient_budget_publish_nothing() {
    let root=root();let bad=sources(&root,31,1,4);let p=StreamHighlightPlan::new(31,1,2.,0.3,1.).unwrap();
    let dir=root.join("gpu-stage-8111");assert!(full_stage::prepare_stage(bad.each_ref().map(|p|p.as_path()),&dir,8111,&p,||false).is_err());assert!(!dir.exists());
    let good=sources(&root,31,1,0);let dir=root.join("gpu-stage-8112");let plan=full_stage::prepare_stage(good.each_ref().map(|p|p.as_path()),&dir,8112,&p,||false).unwrap();
    assert_eq!(full_stage::run_stage(&dir,8112,plan.combined_peak_bytes-1,&plan.manifest_sha256,||false).unwrap_err(),"INSUFFICIENT_GPU_GLOBAL_STREAM_MEMORY");
    assert!(!dir.join("candidate").exists());fs::remove_dir_all(root).unwrap();
}

#[cfg(feature="gpu-diffusion-research")]
#[test]
#[ignore="Actual Vulkan full-image byte parity with original resident highlight, across 1024 and 4MiB boundaries"]
fn actual_vulkan_stream_matches_original_resident_all_scalars_and_rejections(){
    let root=root();
    for (index,(w,h,mode,boost,capacity)) in [
        (1,1,0,2.,1024),(1023,1,0,2.,1024),(1024,1,0,2.,1024),(1025,1,0,2.,1024),
        (2051,1,0,2.,2048),(1025,1025,0,2.,BATCH_SAMPLES),
        (2051,1,1,2.,1024),(2051,1,2,0.,1024),(2051,1,2,2.,1024),
        (2051,1,3,2.,1024),(2051,1,5,8.,1024),
    ].into_iter().enumerate(){
        let input=sources(&root,w,h,mode);let before=input.each_ref().map(|p|fs::read(p).unwrap());
        let resident=GlobalPlan::highlight(w,h,boost,0.3,1.).unwrap();
        let token=8200+index as u64*2;let resident_dir=root.join(format!("gpu-stage-{token}"));
        super::super::prepare_plane_stage(input.each_ref().map(|p|p.as_path()),&resident_dir,token,&resident,||false).unwrap();
        let candidate=resident_dir.join("candidate");fs::create_dir(&candidate).unwrap();
        let old=super::super::run_probe_for_test(&resident,&resident_dir,&candidate,&mut||false);
        let token=token+1;let dir=root.join(format!("gpu-stage-{token}"));
        let plan=StreamHighlightPlan::new(w,h,boost,0.3,1.).unwrap();
        let packet=full_stage::prepare_stage(input.each_ref().map(|p|p.as_path()),&dir,token,&plan,||false).unwrap();
        let new=full_stage::run_probe_stage(&dir,token,packet.combined_peak_bytes,&packet.manifest_sha256,||false,capacity);
        let expect_guard=mode==3||mode==5||(mode==2&&boost>0.);
        if expect_guard {
            assert_eq!(old.unwrap_err(),"GPU_GLOBAL_NUMERIC_CHECK","resident case={index}");
            assert_eq!(new.unwrap_err(),"GPU_GLOBAL_NUMERIC_CHECK","stream case={index}");
            assert!(!dir.join("candidate").exists());
        } else {
            let old=old.unwrap_or_else(|e|panic!("resident must execute successfully, case={index}: {e}"));
            let new=new.unwrap_or_else(|e|panic!("stream must execute successfully, case={index}: {e}"));
            assert_eq!(old["gpuUsed"],true);assert_eq!(new["gpuUsed"],true);
            assert_eq!(old["highlightPeak"],new["highlightPeak"]);assert_eq!(new["contextCount"],1);
            let output=full_stage::validate_outputs(&dir,token,&packet.manifest_sha256,||false).unwrap();
            for ch in 0..3{assert_eq!(fs::read(&output[ch]).unwrap(),fs::read(candidate.join(format!("plane-{ch}.f64le"))).unwrap(),"case={index} channel={ch}");}
        }
        for ch in 0..3{assert_eq!(fs::read(&input[ch]).unwrap(),before[ch]);}
    }
    fs::remove_dir_all(root).unwrap();
}
#[cfg(feature="gpu-diffusion-research")]
#[test]
#[ignore="Actual Vulkan mid-second-pass cancellation discards all candidate planes"]
fn actual_vulkan_cancel_second_pass_discards_candidate_not_sources(){
    let root=root();let input=sources(&root,4099,1,0);let before=input.each_ref().map(|p|fs::read(p).unwrap());
    let p=StreamHighlightPlan::new(4099,1,2.,0.3,1.).unwrap();let dir=root.join("gpu-stage-8501");
    let plan=full_stage::prepare_stage(input.each_ref().map(|p|p.as_path()),&dir,8501,&p,||false).unwrap();
    let result=full_stage::run_probe_stage(&dir,8501,plan.combined_peak_bytes,&plan.manifest_sha256,
        ||fs::metadata(dir.join("candidate/plane-0.f64le")).is_ok_and(|m|m.len()>0),1024);
    assert_eq!(result.unwrap_err(),"CANCELLED");assert!(!dir.join("candidate").exists());
    for ch in 0..3{assert_eq!(fs::read(&input[ch]).unwrap(),before[ch]);}fs::remove_dir_all(root).unwrap();
}
#[cfg(feature="gpu-diffusion-research")]
#[test]
#[ignore="Actual Vulkan retained source descriptor rejects replacement between passes"]
fn actual_vulkan_source_replacement_between_passes_discards_candidate(){
    let root=root();let input=sources(&root,4099,1,0);let before=input.each_ref().map(|p|fs::read(p).unwrap());
    let p=StreamHighlightPlan::new(4099,1,2.,0.3,1.).unwrap();let dir=root.join("gpu-stage-8502");
    let plan=full_stage::prepare_stage(input.each_ref().map(|p|p.as_path()),&dir,8502,&p,||false).unwrap();
    let mut replaced=false;
    let result=full_stage::run_probe_stage(&dir,8502,plan.combined_peak_bytes,&plan.manifest_sha256,||{
        if !replaced&&dir.join("candidate/plane-0.f64le").exists(){
            let replacement=dir.join("replacement");fs::write(&replacement,&before[0]).unwrap();
            fs::rename(replacement,dir.join("source-0.f64le")).unwrap();replaced=true;
        }false
    },1024);
    assert!(replaced);assert_eq!(result.unwrap_err(),"INVALID_GLOBAL_STREAM_SOURCE_IDENTITY");assert!(!dir.join("candidate").exists());
    for ch in 0..3{assert_eq!(fs::read(&input[ch]).unwrap(),before[ch]);}fs::remove_dir_all(root).unwrap();
}
#[cfg(feature="gpu-diffusion-research")]
#[test]
#[ignore="Actual Vulkan integer error survives all continuation/coefficient transport operations"]
fn actual_vulkan_control_operations_never_clear_sticky_error(){
    use crate::research_spatial::gpu::{params,Runner};
    let p=StreamHighlightPlan::new(1,1,2.,0.3,1.).unwrap();
    let (r,mut m)=Runner::new_with_shader_family_error_explicit_layout(&p.allocation_plan().unwrap(),true,&families().unwrap()).unwrap();
    // The new reducer rejects a zero block count. The subsequent legal
    // controls must not clear this error, even though they copy safe data.
    let mut v=params(1024,1024,18);v[4]=1;v[5]=0;r.dispatch(v,&mut||false,&mut m).unwrap();
    assert_ne!(r.read_error_flag(&mut||false,&mut m).unwrap(),0);
    for op in [17,18,19,20]{
        let mut v=params(1024,1024,op);v[4]=1;v[5]=1;r.dispatch(v,&mut||false,&mut m).unwrap();
        assert_ne!(r.read_error_flag(&mut||false,&mut m).unwrap(),0,"op={op}");
    }
}
