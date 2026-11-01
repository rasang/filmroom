//! Independent streamed-vs-whole regression: same quantized input, physical
//! geometry, coordinate RNG and 33^3 pointwise stage LUTs on both paths.
use std::{cell::Cell,fs,path::{Path,PathBuf},sync::atomic::{AtomicU64,Ordering}};
use film_engine::streamed;
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline,profile::load_profile_by_name};
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::{image::ImageBuf,precision::from_f64};
static NEXT:AtomicU64=AtomicU64::new(0);
const CASES:[&str;5]=["effects_off","halation_only","grain_only","physical_all","grain_single_layer"];
struct TestDir(PathBuf);
impl TestDir {fn new(label:&str)->Self {let path=std::env::temp_dir().join(format!("film-v5-streamed-{label}-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));fs::create_dir(&path).unwrap();Self(path)}}
impl Drop for TestDir {fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
// V6 deterministic synthetic fixtures. The archived V5 external Python-named
// fixture directory was not included in the source archive; see recovery note.
// This is a NEW Rust whole-vs-streamed regression, NOT a Python output oracle.
const FIXTURE_PROVENANCE: &str = "synthetic-spatial-v6-integer-grid-20261031";
fn data()->PathBuf {Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
fn params(case:&str,w:u32,h:u32)->(RuntimeParams,String,String) {
 assert!(CASES.contains(&case));
 let mut p=RuntimeParams::default();
 p.camera.auto_exposure=false;
 p.camera.film_format_mm=0.56; // Diagnostic physical pitch 8.75um at64px.
 p.io.input_color_space="sRGB".into();p.io.output_color_space="sRGB".into();
 p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;
 p.print_render.glare.active=false;p.film_render.glare.active=false;
 p.scanner.lens_blur=0.;p.scanner.unsharp_mask=[0.,0.];
 p.film_render.grain.active=matches!(case,"grain_only"|"physical_all"|"grain_single_layer");
 p.film_render.grain.sublayers_active=case!="grain_single_layer";
 p.film_render.grain.seed=42;p.film_render.grain.micro_structure=[0.,0.];
 p.film_render.halation.active=matches!(case,"halation_only"|"physical_all");
 p.film_render.dir_couplers.active=case=="physical_all";
 p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.settings.lut_resolution=33;
 p.camera.pixel_size_um_override=Some(p.camera.film_format_mm*1000./w.max(h)as f32);p.film_render.grain.coordinate_origin=Some([0,0]);
 (p,"kodak_portra_400".into(),"kodak_portra_endura".into())
}
fn input()->Vec<f32> {
 // Integer-grid ramps, fine checker and bright impulse; binary-exact fractions,
 // no platform RNG, image decoder, external photographs or hidden oracle file.
 (0..64*64*3).map(|i|{let c=i%3;let x=(i/3)%64;let y=i/(64*3);
  if x==31&&y==31 {4.0} else if x<8 {((y*3+c*17)%256) as f32/256.0}
  else {(((x*19+y*37+c*71)%1024)+1) as f32/1024.0}
 }).collect()
}
fn write32(p:&Path,v:&[f32]) {fs::write(p,v.iter().flat_map(|x|x.to_le_bytes()).collect::<Vec<_>>()).unwrap();}
fn read32(p:&Path)->Vec<f32> {let b=fs::read(p).unwrap();assert_eq!(b.len()%4,0);b.chunks_exact(4).map(|v|f32::from_le_bytes(v.try_into().unwrap())).collect()}
fn report(name:&str,v:&serde_json::Value) {let mut record=v.clone();record["fixtureProvenance"]=serde_json::json!(FIXTURE_PROVENANCE);let v=&record;println!("STREAMED_FIXTURE {FIXTURE_PROVENANCE}");println!("STREAMED_TEST_RESULT {}",serde_json::to_string(v).unwrap());if let Some(dir)=std::env::var_os("FILM_STREAMED_TEST_REPORT_DIR") {fs::create_dir_all(&dir).unwrap();fs::write(Path::new(&dir).join(format!("{name}.json")),serde_json::to_vec_pretty(v).unwrap()).unwrap();}}
#[test]
fn five_physical_cases_match_whole_pipeline_f32_exactly() {
 report("comparison",&serde_json::json!({"status":"running"}));
 assert!(cfg!(feature="precision-f64"));let source=input();let mut reports=Vec::new();let mut mismatch=Vec::new();
 for case in CASES {
  let temp=TestDir::new(case);let input_path=temp.0.join("input.f32le");let output=temp.0.join("output.f32le");let scratch=temp.0.join("scratch");write32(&input_path,&source);
  let (p,film,paper)=params(case,64,64);let pipeline=Pipeline::new_with_spectral(load_profile_by_name(&data(),&film).unwrap(),load_profile_by_name(&data(),&paper).unwrap(),p.clone(),&data()).unwrap();
  let whole=pipeline.process(ImageBuf::from_data(64,64,source.iter().map(|&v|from_f64(v as f64)).collect()),&CpuBackend);assert_eq!((whole.width,whole.height,whole.data.len()),(64,64,source.len()));
  let expected:Vec<f32>=whole.data.iter().map(|&v|v as f32).collect();let mut events=Vec::new();
  let metrics=streamed::export_file(&data(),&film,&paper,p,&input_path,&output,&scratch,64,64,384,0.,||false,|stage,done,total|events.push((stage,done,total))).unwrap();
  let actual=read32(&output);assert_eq!(fs::metadata(&output).unwrap().len(),64*64*12);assert_eq!(actual.len(),expected.len());assert!(!scratch.exists());assert_eq!(read32(&input_path),source);assert_eq!((metrics.width,metrics.height,metrics.pixel_count),(64,64,4096));assert!(metrics.peak_logical_scratch_bytes<=metrics.estimated_scratch_bytes);assert!(metrics.spatial_peak_explicit_buffer_bytes<=16*1024*1024);
  assert!(actual.iter().chain(&expected).all(|v|v.is_finite()));let different=actual.iter().zip(&expected).filter(|(a,e)|a.to_bits()!=e.to_bits()).count();let errors:Vec<f64>=actual.iter().zip(&expected).map(|(&a,&e)|(a as f64-e as f64).abs()).collect();let max=errors.iter().copied().fold(0.,f64::max);let mean=errors.iter().sum::<f64>()/errors.len()as f64;
  let first=actual.iter().zip(&expected).enumerate().find(|(_, (a,e))|a.to_bits()!=e.to_bits()).map(|(i,(&a,&e))|serde_json::json!({"sample":i,"actual":a,"expected":e,"actualBits":a.to_bits(),"expectedBits":e.to_bits()}));
  let entry=serde_json::json!({"case":case,"film":film,"paper":paper,"width":64,"height":64,"samples":actual.len(),"differingSamples":different,"maxAbs":max,"meanAbs":mean,"firstDifference":first,"finite":true,"scratchRemoved":true,"inputUnchanged":true,"metrics":metrics,"progressEvents":events.len()});println!("STREAMED_CASE {}",serde_json::to_string(&entry).unwrap());
  if let Some(dir)=std::env::var_os("FILM_STREAMED_TEST_REPORT_DIR") {fs::create_dir_all(&dir).unwrap();write32(&Path::new(&dir).join(format!("{case}.streamed.f32le")),&actual);write32(&Path::new(&dir).join(format!("{case}.whole.f32le")),&expected);}
  if different!=0{mismatch.push(case);}reports.push(entry);
 }
 report("comparison",&serde_json::json!({"status":if mismatch.is_empty(){"passed"}else{"failed"},"reference":"whole Pipeline::process, same V6 source with documented synthetic physical settings","precision":"f64 internals, f32 input/output","matchingSettings":{"lutResolution":33,"useEnlargerLut":true,"useScannerLut":true,"coordinateOrigin":[0,0],"fullImagePitchUm":8.75},"cases":reports}));
 assert!(mismatch.is_empty(),"Strict f32 bitwise differences: {mismatch:?}; inspect report, do not weaken tolerance");
}
#[test]
fn cancellation_after_spatial_or_output_progress_removes_partial_files() {
 report("cancellation",&serde_json::json!({"status":"running"}));
 let source=input();let mut results=Vec::new();
 for target in ["film_scatter","printing_scanning"] {
  let temp=TestDir::new("cancel");let input_path=temp.0.join("input.f32le");let output=temp.0.join("output.f32le");let scratch=temp.0.join("scratch");write32(&input_path,&source);let stop=Cell::new(false);let hit=Cell::new(false);let(p,film,paper)=params("physical_all",64,64);
  let r=streamed::export_file(&data(),&film,&paper,p,&input_path,&output,&scratch,64,64,384,0.,||stop.get(),|stage,done,_|{if stage==target&&done>0 {hit.set(true);stop.set(true);}});
  let e=r.unwrap_err();assert!(hit.get(),"cancel target was not reached: {target}");assert_eq!(e.code,"CANCELLED");assert!(!output.exists());assert!(!scratch.exists());assert_eq!(read32(&input_path),source);results.push(serde_json::json!({"cancelAfter":target,"errorCode":e.code,"outputRemoved":true,"scratchRemoved":true,"inputUnchanged":true}));
 }
 report("cancellation",&serde_json::json!({"status":"passed","cases":results}));
}
#[test]
fn multi_chunk_physical_case_matches_whole_pipeline_f32_exactly() {
 report("multi-chunk",&serde_json::json!({"status":"running"}));
 let (w,h)=(257,129);let seed=input();let source:Vec<f32>=(0..w as usize*h as usize*3).map(|i|seed[(i*13+i/771*7)%seed.len()]).collect();let temp=TestDir::new("multi-chunk");let input_path=temp.0.join("input.f32le");let output=temp.0.join("output.f32le");let scratch=temp.0.join("scratch");write32(&input_path,&source);let(p,film,paper)=params("physical_all",w,h);
 let pipeline=Pipeline::new_with_spectral(load_profile_by_name(&data(),&film).unwrap(),load_profile_by_name(&data(),&paper).unwrap(),p.clone(),&data()).unwrap();let whole=pipeline.process(ImageBuf::from_data(w,h,source.iter().map(|&v|from_f64(v as f64)).collect()),&CpuBackend);assert_eq!((whole.width,whole.height,whole.data.len()),(w,h,source.len()));let expected:Vec<f32>=whole.data.iter().map(|&v|v as f32).collect();let mut sampling_events=Vec::new();
 let metrics=streamed::export_file(&data(),&film,&paper,p,&input_path,&output,&scratch,w,h,384,0.,||false,|stage,done,total|{if stage=="grain_sampling"{sampling_events.push((done,total));}}).unwrap();let actual=read32(&output);assert_eq!(actual.len(),expected.len());assert_eq!(fs::metadata(&output).unwrap().len(),w as u64*h as u64*12);assert!(actual.iter().chain(&expected).all(|v|v.is_finite()));assert!(!scratch.exists());assert_eq!(read32(&input_path),source);assert_eq!(metrics.pointwise_chunk_rows,127);assert!(sampling_events.contains(&(127,129))&&sampling_events.contains(&(129,129)));
 let differing=actual.iter().zip(&expected).filter(|(a,e)|a.to_bits()!=e.to_bits()).count();let errors:Vec<_>=actual.iter().zip(&expected).map(|(&a,&e)|(a as f64-e as f64).abs()).collect();let entry=serde_json::json!({"status":if differing==0{"passed"}else{"failed"},"case":"physical_all_chunk_boundary","width":w,"height":h,"film":film,"paper":paper,"samples":actual.len(),"pointwiseRows":[127,2],"differingSamples":differing,"maxAbs":errors.iter().copied().fold(0.,f64::max),"meanAbs":errors.iter().sum::<f64>()/errors.len()as f64,"finite":true,"scratchRemoved":true,"inputUnchanged":true,"metrics":metrics});report("multi-chunk",&entry);
 if let Some(dir)=std::env::var_os("FILM_STREAMED_TEST_REPORT_DIR"){fs::create_dir_all(&dir).unwrap();write32(&Path::new(&dir).join("multi-chunk.streamed.f32le"),&actual);write32(&Path::new(&dir).join("multi-chunk.whole.f32le"),&expected);}
 assert_eq!(differing,0,"Strict multi-chunk f32 parity failed; inspect recorded errors");
}


/// Independent test-side transcription of the *public MobileParams* mapping.
/// This deliberately uses no scientific controls beyond the mobile preset;
/// compare with mobile.rs runtime/runtime_for_film whenever that mapping changes.
fn mobile_runtime_for_test(m:&film_engine::mobile::MobileParams,w:u32,h:u32)->RuntimeParams {
 m.validate().unwrap();let film=load_profile_by_name(&data(),&m.film_stock).unwrap();let mut p=RuntimeParams::default();
 p.camera.auto_exposure=false;p.camera.exposure_compensation_ev=m.film_exposure_ev as f32;
 p.io.input_color_space="sRGB".into();p.io.output_color_space="sRGB".into();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;
 p.scanner.unsharp_mask=[0.,0.];p.print_render.glare.active=false;
 p.print_render.density_curves_morph.active=(m.contrast-1.).abs()>f64::EPSILON;p.print_render.density_curves_morph.gamma_factor=m.contrast;
 p.enlarger.y_filter_shift=(-m.warmth*15.)as f32;p.film_render.dir_couplers.amount=m.saturation;
 p.film_render.grain.active=m.grain_amount>0.;p.film_render.grain.amount=m.grain_amount;p.film_render.grain.seed=m.seed as u64;p.film_render.grain.agx_particle_area_um2*=m.grain_size*m.grain_size;
 p.film_render.halation.active=m.halation_amount>0.;p.film_render.halation.halation_amount=m.halation_amount;p.film_render.halation.halation_spatial_scale=m.halation_size;
 if film.is_film()&&["still","cine"].contains(&film.info.usage.as_str()) {
  let strength=match film.info.antihalation.as_str(){"strong"=>Some([0.015,0.005,0.]),"weak"=>Some([0.08,0.02,0.]),"no"=>Some([0.30,0.10,0.015]),_=>None};
  if let Some(strength)=strength{p.film_render.halation.halation_strength=strength;p.film_render.halation.halation_first_sigma_um=[if film.info.usage=="cine"{50.}else{65.};3];}
 }
 p.camera.pixel_size_um_override=Some(p.camera.film_format_mm*1000./w.max(h)as f32);p.film_render.grain.coordinate_origin=Some([0,0]);p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.settings.lut_resolution=33;p
}
#[test]
fn portrait_mobile_profile_and_control_extremes_match_whole_f32_exactly() {
 report("mobile-portrait-controls",&serde_json::json!({"status":"running"}));
 use film_engine::mobile::MobileParams;
 let cases=[
  ("gold-default",r#"{"filmStock":"kodak_gold_200","paperStock":"kodak_portra_endura"}"#),
  ("fuji-default",r#"{"filmStock":"fujifilm_c200","paperStock":"fujifilm_crystal_archive_typeii"}"#),
  ("gold-upper-controls",r#"{"filmStock":"kodak_gold_200","paperStock":"kodak_supra_endura","filmExposureEv":4,"brightnessEv":-2,"contrast":1.5,"saturation":2,"grainAmount":2,"grainSize":3,"halationAmount":2,"halationSize":3}"#),
  ("fuji-lower-controls",r#"{"filmStock":"fujifilm_c200","paperStock":"fujifilm_crystal_archive_typeii","filmExposureEv":-4,"brightnessEv":2,"contrast":0.5,"saturation":0,"grainAmount":0,"halationAmount":0}"#),
  ("gold-small-grain-halo-warm",r#"{"filmStock":"kodak_gold_200","paperStock":"fujifilm_crystal_archive_typeii","grainAmount":2,"grainSize":0.25,"halationAmount":2,"halationSize":0.25,"warmth":1}"#),
  ("fuji-blended-grain-halo-cool",r#"{"filmStock":"fujifilm_c200","paperStock":"kodak_portra_endura","grainAmount":0.5,"grainSize":3,"halationAmount":0.5,"halationSize":3,"warmth":-1}"#),
 ];
 let(w,h)=(129,257);let seed=input();let source:Vec<f32>=(0..w as usize*h as usize*3).map(|i|seed[(i*17+i/387*11)%seed.len()]).collect();let mut reports=Vec::new();let mut mismatches=Vec::new();
 for(name,json)in cases {
  let mut mobile=MobileParams::parse(json).unwrap();mobile.mode="export".into();let p=mobile_runtime_for_test(&mobile,w,h);let pitch=p.camera.pixel_size_um_override.unwrap();let temp=TestDir::new(name);let input_path=temp.0.join("input.f32le");let output=temp.0.join("output.f32le");let scratch=temp.0.join("scratch");write32(&input_path,&source);
  let pipeline=Pipeline::new_with_spectral(load_profile_by_name(&data(),&mobile.film_stock).unwrap(),load_profile_by_name(&data(),&mobile.paper_stock).unwrap(),p.clone(),&data()).unwrap();let whole=pipeline.process(ImageBuf::from_data(w,h,source.iter().map(|&v|from_f64(v as f64)).collect()),&CpuBackend);assert_eq!((whole.width,whole.height,whole.data.len()),(w,h,source.len()));
  // Both routes apply output brightness in f64 BEFORE ABI f32 narrowing.
  let brightness=2f64.powf(mobile.brightness_ev);let expected:Vec<f32>=whole.data.iter().map(|&v|(v as f64*brightness)as f32).collect();let mut exposure_events=Vec::new();
  let metrics=streamed::export_file(&data(),&mobile.film_stock,&mobile.paper_stock,p,&input_path,&output,&scratch,w,h,mobile.memory_budget_mi_b,mobile.brightness_ev,||false,|stage,done,total|{if stage=="film_exposure"{exposure_events.push((done,total));}}).unwrap();
  let actual=read32(&output);assert_eq!(actual.len(),expected.len());assert_eq!(fs::metadata(&output).unwrap().len(),w as u64*h as u64*12);assert!(actual.iter().chain(&expected).all(|v|v.is_finite()));assert!(!scratch.exists());assert_eq!(read32(&input_path),source);assert_eq!(metrics.pointwise_chunk_rows,254);assert_eq!(exposure_events,vec![(254,257),(257,257)]);assert!(metrics.peak_logical_scratch_bytes<=88*w as u64*h as u64);
  let differing=actual.iter().zip(&expected).filter(|(a,e)|a.to_bits()!=e.to_bits()).count();let errors:Vec<f64>=actual.iter().zip(&expected).map(|(&a,&e)|(a as f64-e as f64).abs()).collect();let first=actual.iter().zip(&expected).enumerate().find(|(_, (a,e))|a.to_bits()!=e.to_bits()).map(|(i,(&a,&e))|serde_json::json!({"sample":i,"actual":a,"expected":e,"actualBits":a.to_bits(),"expectedBits":e.to_bits()}));
  let entry=serde_json::json!({"case":name,"width":w,"height":h,"samples":actual.len(),"mobileParams":mobile,"fullImagePitchUm":pitch,"coordinateOrigin":[0,0],"lutResolution":33,"pointwiseRows":[254,3],"differingSamples":differing,"maxAbs":errors.iter().copied().fold(0.,f64::max),"meanAbs":errors.iter().sum::<f64>()/errors.len()as f64,"firstDifference":first,"finite":true,"scratchRemoved":true,"inputUnchanged":true,"metrics":metrics});println!("STREAMED_MOBILE_CASE {}",serde_json::to_string(&entry).unwrap());
  if let Some(dir)=std::env::var_os("FILM_STREAMED_TEST_REPORT_DIR"){fs::create_dir_all(&dir).unwrap();write32(&Path::new(&dir).join(format!("{name}.streamed.f32le")),&actual);write32(&Path::new(&dir).join(format!("{name}.whole.f32le")),&expected);}
  if differing!=0{mismatches.push(name);}reports.push(entry);
 }
 report("mobile-portrait-controls",&serde_json::json!({"status":if mismatches.is_empty(){"passed"}else{"failed"},"reference":"whole Pipeline::process with identical test-side MobileParams mapping; not a JNI/API mapping test","cases":reports}));assert!(mismatches.is_empty(),"Strict supported-control/profile f32 parity failed: {mismatches:?}");
}
