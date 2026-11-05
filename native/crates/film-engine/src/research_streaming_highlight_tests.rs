use super::*;
use std::{fs,path::PathBuf};
use serde_json::json;

#[test]
fn highlight_selector_matches_original_allocation_and_never_uses_semantic_failure() {
    let semantic=GlobalPlan::highlight(1,1,2.,0.3,1.).unwrap();
    for (w,h) in [(1,1),(1025,1025),(6000,4000),(8192,3942)] {
        assert!(!needs_streaming(&semantic,w,h).unwrap());
        assert!(GlobalPlan::highlight(w,h,2.,0.3,1.).is_ok());
        assert!(!selected(&semantic,w,h).unwrap());
    }
    for (w,h) in [(8192,3943),(8192,4096),(8192,6144),(40000,40000)] {
        assert!(needs_streaming(&semantic,w,h).unwrap());
        assert!(GlobalPlan::highlight(w,h,2.,0.3,1.).is_err());
        assert!(!selected(&semantic,w,h).unwrap());
    }
    let meter=GlobalPlan::meter(1,1,"average",[[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]]).unwrap();
    assert!(needs_streaming(&meter,8192,6144).is_err());
    let ae=GlobalPlan::meter(8192,6144,"average",[[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]]).unwrap();
    assert_eq!(ae.device_bytes,5*256*256*4+2*TRANSFER_BYTES+65536);
    let log=GlobalPlan::log_exposure(1,1,1.).unwrap();assert!(needs_streaming(&log,8192,6144).is_err());
    let mut bad=semantic.clone();bad.kind=GlobalKind::Highlight{boost_ev:f32::NAN,boost_range:0.3,protect_ev:1.};
    assert!(needs_streaming(&bad,8192,6144).is_err());
    for (w,h) in [(0,1),(1,0),(40001,1),(u32::MAX,u32::MAX)]{assert!(needs_streaming(&semantic,w,h).is_err());}
    assert!(!ROUTING_ADMITTED);assert!(!stage::SOURCE_ADMITTED);
}
#[test]
fn highlight_counter_formulas_cover_reduction_io_and_batch_edges() {
    for (w,h) in [(1,1),(7,1),(8,1),(1023,1),(1024,1),(1025,1),(255,257),(256,256),(257,256),(1023,1025),(1024,1024),(1025,1025)] {
        let n=u64::from(w)*u64::from(h);
        let counters=expected_counters(w,h).unwrap();
        let c=n.div_ceil(65536);let b=n.div_ceil(1048576);
        assert_eq!(counters.samples,1+3*(8*(c-1)+(n-65536*(c-1)).min(8)));
        assert_eq!(counters.dispatches,4+4*b+9*c);
        assert_eq!(counters.upload,24*n+160*counters.dispatches);
        assert_eq!(counters.readback,12*n+80+4*b);
    }
    for(w,h)in[(257,257),(1025,1025),(8192,6144)]{let c=expected_counters(w,h).unwrap();assert!(c.samples>1);assert!(c.upload>u64::from(w)*u64::from(h)*24);}
}
#[test]
fn native_stream_highlight_plan_fixture_uses_real_50mp_geometry_and_exact_budgets() {
    let p=stage::plan_fixture_for_test(8192,6144,7542).unwrap();
    assert_eq!(p.width,8192);assert_eq!(p.height,6144);assert_eq!(p.pass_count,2);
    assert_eq!(p.device_bytes,33_619_968);assert_eq!(p.combined_peak_bytes,276_889_600);
    assert_eq!(p.host_bytes+p.helper_allowance_bytes,128*MIB);
    assert_eq!(p.disk_bytes,48*8192*6144+2*MIB);
    let envelope=crate::research_streaming_route::plan_envelope(&p).unwrap();
    if let Some(root)=std::env::var_os("FILMROOM_STREAM_PLAN_FIXTURE_DIR") {
        fs::create_dir_all(&root).unwrap();
        fs::write(PathBuf::from(root).join("highlight-stream-v1.json"),serde_json::to_vec_pretty(&envelope).unwrap()).unwrap();
    }
    // This is native make_plan serialization with synthetic source digests,
    // not evidence of 50MP image allocation, I/O, Vulkan or Android execution.
}
#[test]
fn highlight_report_rejects_raw_failure_missing_work_and_unknown_sanitized_fields() {
    let p=stage::plan_fixture_for_test(257,257,7542).unwrap();let counters=expected_counters(p.width,p.height).unwrap();
    // Shape-only negative fixture; actual GPU producer below is separate.
    let raw=json!({"ok":true,"schemaVersion":1,"operation":streaming::OPERATION,"layout":streaming::LAYOUT,
        "backend":streaming::BACKEND,"token":p.token,"manifestSha256":p.manifest_sha256,"width":p.width,"height":p.height,
        "deviceBytes":p.device_bytes,"combinedPeakBytes":p.combined_peak_bytes,"gpuUsed":true,"fullImage":true,
        "fullResolution":true,"nativeStageSealed":false,"contextCount":1,"vendor":0,"passCount":2,"passes":2,
        "sampleCount":counters.samples,"dispatches":counters.dispatches,"uploadBytes":counters.upload,"readbackBytes":counters.readback,
        "sampleMaxAbs":0.,"sampleRms":0.,"elapsedMs":1.,"sampleToleranceAbsolute":0.002,"sampleToleranceRelative":0.002,
        "sampleScope":SAMPLE_SCOPE,"highlightPeak":-0.1,"chunkCapacitySamples":streaming::BATCH_SAMPLES});
    let normalized=normalize(raw.clone(),&p).unwrap();
    for replacement in [Value::Bool(false),Value::Null] {let mut bad=raw.clone();bad["ok"]=replacement;assert!(normalize(bad,&p).is_err());}
    for (key,value) in [("manifestSha256",json!("b".repeat(64))),("layout",json!("resident")),("backend",json!("gpu_highlight_f32_v1")),("width",json!(1)),("token",json!(9)),("fullImage",json!(false)),("schemaVersion",json!(2))] {
        let mut bad=raw.clone();bad[key]=value;assert!(normalize(bad,&p).is_err());
    }
    let mut bad=raw.clone();bad.as_object_mut().unwrap().remove("ok");assert!(normalize(bad,&p).is_err());
    for key in ["sampleCount","dispatches","uploadBytes","readbackBytes","chunkCapacitySamples","passes","passCount"] {
        let mut bad=raw.clone();bad[key]=0.into();assert!(normalize(bad,&p).is_err());
    }
    let mut safe=normalized.clone();safe.as_object_mut().unwrap().remove("ok");
    assert!(crate::research_streaming_route::valid_summary_identity(&safe,streaming::OPERATION,p.token,&p.manifest_sha256,p.width,p.height));
    for extra in ["ok","unknown","hostWork"]{let mut bad=safe.clone();bad[extra]=true.into();assert!(!crate::research_streaming_route::valid_summary_identity(&bad,streaming::OPERATION,p.token,&p.manifest_sha256,p.width,p.height));}
}

#[cfg(feature="gpu-diffusion-research")]
#[test]
#[ignore="requires isolated process, real Vulkan and fresh raw output directory"]
fn actual_vulkan_stream_highlight_emits_android_raw_fixture_after_native_seal() {
    use crate::{gpu_session as session,gpu_session_contract::StageTicket};
    let root=PathBuf::from(std::env::var_os("FILMROOM_STREAM_RAW_SUMMARY_FIXTURE_DIR").expect("set raw dir"));
    fs::create_dir_all(&root).unwrap();let root=root.canonicalize().unwrap();
    let base=root.join("highlight-stream-v1.stage");fs::create_dir(&base).expect("fresh fixture directory required");
    let(w,h)=(1025,1025);let n=w as usize*h as usize;
    let inputs:[PathBuf;3]=std::array::from_fn(|ch|base.join(format!("input-{ch}.f64le")));
    for(ch,path)in inputs.iter().enumerate(){fs::write(path,(0..n).flat_map(|i|(0.1+(i%97)as f64/101.+ch as f64*0.01).to_le_bytes()).collect::<Vec<_>>()).unwrap();}
    let typed=streaming::StreamHighlightPlan::new(w,h,2.,0.3,1.).unwrap();let dir=base.join("gpu-stage-7542");
    let p=stage::prepare_stage(inputs.each_ref().map(|v|v.as_path()),&dir,7542,&typed,||false).unwrap();
    assert_eq!(crate::research_streaming_route::plan(streaming::OPERATION,&dir,p.token).unwrap(),serde_json::to_value(&p).unwrap());
    let _software=session::software_probe_scope();let ticket=StageTicket{epoch:9542,generation:1,sequence:1};
    session::activate(ticket.epoch,768*MIB,p.host_bytes+p.helper_allowance_bytes,64).unwrap();
    session::begin_frame(ticket.epoch,ticket.generation).unwrap();session::begin_stage(ticket,p.driver_allowance_bytes).unwrap();
    // Test-only probe uses the genuine full_stage execution/publication path;
    // default 4MiB batch crosses its real boundary at 1025x1025.
    let raw=stage::run_probe_stage(&dir,p.token,p.combined_peak_bytes,&p.manifest_sha256,||false,streaming::BATCH_SAMPLES).unwrap();
    let raw=normalize(raw,&p).unwrap();session::complete_stage(ticket).unwrap();session::finish_frame(ticket.epoch,1).unwrap();
    stage::validate_outputs(&dir,p.token,&p.manifest_sha256,||false).unwrap();
    fs::write(root.join("highlight-stream-v1-raw.json"),serde_json::to_vec_pretty(&json!({"plan":crate::research_streaming_route::plan_envelope(&p).unwrap(),"nativeResult":raw})).unwrap()).unwrap();
}
#[test]
#[ignore="requires actual Vulkan raw packet and actual Android parse/encode/parse safe output"]
fn actual_android_stream_highlight_summary_adopts_only_sealed_complete_hashed_packet() {
    let raw=PathBuf::from(std::env::var_os("FILMROOM_STREAM_RAW_SUMMARY_FIXTURE_DIR").expect("set raw dir"));
    let safe=PathBuf::from(std::env::var_os("FILMROOM_STREAM_SAFE_SUMMARY_FIXTURE_DIR").expect("set safe dir"));
    let packet:Value=serde_json::from_slice(&fs::read(safe.join("highlight-stream-v1-safe.json")).unwrap()).unwrap();
    assert_eq!(packet.as_object().unwrap().keys().map(String::as_str).collect::<std::collections::BTreeSet<_>>(),["plan","result"].into_iter().collect());
    let dir=raw.canonicalize().unwrap().join("highlight-stream-v1.stage/gpu-stage-7542");let p=stage::plan_stage(&dir,7542).unwrap();
    assert_eq!(packet["plan"],crate::research_streaming_route::plan_envelope(&p).unwrap());
    let result=&packet["result"];assert_eq!(result["schemaVersion"],1);assert_eq!(result["code"],"OK");assert_eq!(result.as_object().unwrap().len(),3);
    let total=u64::from(p.width)*u64::from(p.height)*3;
    let poll=json!({"schemaVersion":2,"epoch":9542,"frameGeneration":1,"sequence":1,"token":p.token,"status":"success_sealed","completedPixels":total,"totalPixels":total,"code":result["code"],"summary":result["summary"]});
    let accepted=|v:&Value|crate::streamed::session_success_summary_for_test(&v.to_string(),9542,1,1,p.token,total)
        .filter(|summary|crate::research_streaming_route::valid_summary_identity(summary,streaming::OPERATION,p.token,&p.manifest_sha256,p.width,p.height))
        .filter(|summary|validate_summary_metrics(summary,p.width,p.height).is_ok());
    assert!(accepted(&poll).is_some());
    for code in ["CANCELLED","NATIVE_FAILED","COMPLETE"]{let mut bad=poll.clone();bad["code"]=code.into();assert!(accepted(&bad).is_none());}
    for key in ["epoch","frameGeneration","sequence","token"]{let mut bad=poll.clone();bad[key]=99999.into();assert!(accepted(&bad).is_none());}
    for value in [Value::Bool(false),Value::Bool(true),Value::Null]{let mut bad=poll.clone();bad["summary"]["ok"]=value;assert!(accepted(&bad).is_none());}
    for status in ["failed_dead","cancelled_dead","running"]{let mut bad=poll.clone();bad["status"]=status.into();assert!(accepted(&bad).is_none());}
    stage::validate_outputs(&dir,p.token,&p.manifest_sha256,||false).unwrap();
    let complete=dir.join("candidate/complete.json");let original=fs::read(&complete).unwrap();
    for key in ["ok","backend"]{let mut done:Value=serde_json::from_slice(&original).unwrap();done[key]=if key=="ok"{false.into()}else{"gpu_highlight_f32_v1".into()};fs::write(&complete,serde_json::to_vec(&done).unwrap()).unwrap();assert!(stage::validate_outputs(&dir,p.token,&p.manifest_sha256,||false).is_err());fs::write(&complete,&original).unwrap();}
    // Restore bytes after adversarial output modification; complete's original
    // digest must reject any changed candidate, independent of IPC metadata.
    use std::io::{Read,Seek,SeekFrom,Write};
    let output=dir.join("candidate/plane-2.f64le");let mut file=fs::OpenOptions::new().read(true).write(true).open(&output).unwrap();
    let mut first=[0u8;8];file.read_exact(&mut first).unwrap();file.seek(SeekFrom::Start(0)).unwrap();file.write_all(&123.25f64.to_le_bytes()).unwrap();file.sync_all().unwrap();
    assert!(stage::validate_outputs(&dir,p.token,&p.manifest_sha256,||false).is_err());file.seek(SeekFrom::Start(0)).unwrap();file.write_all(&first).unwrap();file.sync_all().unwrap();drop(file);
    stage::validate_outputs(&dir,p.token,&p.manifest_sha256,||false).unwrap();
}

#[cfg(feature="gpu-diffusion-research")]
fn small_packet(token:u64)->(PathBuf,PathBuf,stage::StagePlan,[PathBuf;3]) {
    let root=std::env::temp_dir().join(format!("filmroom-highlight-route-{}-{}-{token}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
    fs::create_dir(&root).unwrap();let root=root.canonicalize().unwrap();
    let inputs=std::array::from_fn(|ch|{let p=root.join(format!("input-{ch}.f64le"));fs::write(&p,[0.1f64;6].into_iter().flat_map(f64::to_le_bytes).collect::<Vec<_>>()).unwrap();p});
    let typed=streaming::StreamHighlightPlan::new(3,2,2.,0.3,1.).unwrap();let dir=root.join(format!("gpu-stage-{token}"));
    let p=stage::prepare_stage(inputs.each_ref().map(|p|p.as_path()),&dir,token,&typed,||false).unwrap();(root,dir,p,inputs)
}
#[cfg(feature="gpu-diffusion-research")]
#[test]
fn closed_native_highlight_dispatch_cancellation_and_digest_never_publish() {
    let(root,dir,p,inputs)=small_packet(7543);let before=inputs.each_ref().map(|p|fs::read(p).unwrap());
    let run=|sha:&str,cancelled:bool|crate::research_streaming_route::run(streaming::OPERATION,&dir,p.token,p.combined_peak_bytes,sha,||cancelled);
    assert_eq!(run(&p.manifest_sha256,false).unwrap_err(),"GPU_GLOBAL_STREAM_PENDING");
    assert_eq!(run(&p.manifest_sha256,true).unwrap_err(),"CANCELLED");
    assert_eq!(run(&"b".repeat(64),false).unwrap_err(),"INVALID_STREAM_IDENTITY");
    assert!(!dir.join("candidate").exists());assert_eq!(inputs.each_ref().map(|p|fs::read(p).unwrap()),before);
    fs::remove_dir_all(root).unwrap();
}
#[cfg(feature="gpu-diffusion-research")]
#[test]
#[ignore="isolated native session registry process; closed source path requires no Vulkan execution"]
fn closed_native_highlight_session_failure_retires_without_a_success_seal() {
    use crate::{gpu_session as session,gpu_session_contract::StageTicket};
    let(root,dir,p,inputs)=small_packet(7544);let before=inputs.each_ref().map(|p|fs::read(p).unwrap());
    let ticket=StageTicket{epoch:9544,generation:1,sequence:1};
    session::activate(ticket.epoch,768*MIB,p.host_bytes+p.helper_allowance_bytes,64).unwrap();session::begin_frame(ticket.epoch,1).unwrap();
    let result=crate::gpu_session_jni::run_stage_for_test(ticket,streaming::OPERATION,&dir,p.combined_peak_bytes,p.token,&p.manifest_sha256);
    assert_eq!(result.unwrap_err(),"GPU_GLOBAL_STREAM_PENDING");
    assert!(session::complete_stage(ticket).is_err());assert!(session::finish_frame(ticket.epoch,1).is_err());
    assert!(!dir.join("candidate").exists());assert_eq!(inputs.each_ref().map(|p|fs::read(p).unwrap()),before);
    fs::remove_dir_all(root).unwrap();
}
