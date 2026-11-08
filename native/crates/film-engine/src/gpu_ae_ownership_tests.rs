//! Regression for automatic EV being mistaken for manual print compensation.
#![cfg(all(feature="adreno-gpu", feature="precision-f64"))]
use crate::{mobile::{self, MobileParams}, streamed};
use std::{fs, path::{Path,PathBuf},sync::atomic::{AtomicU64,Ordering}};
static NEXT:AtomicU64=AtomicU64::new(0);
struct Temp(PathBuf);
impl Drop for Temp { fn drop(&mut self){let _=fs::remove_dir_all(&self.0);} }
fn temp()->Temp{let p=std::env::temp_dir().join(format!("ae-ownership-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));fs::create_dir(&p).unwrap();Temp(p)}
fn data()->PathBuf{Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
fn gpu_required()->bool{std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref()==Ok("1")}
fn values(w:u32,h:u32,scale:f32)->Vec<f32>{(0..w as usize*h as usize*3).map(|i|scale*(0.015+((i/3*13+i%3*29)%251)as f32/2000.)).collect()}
fn write(path:&Path,v:&[f32]){fs::write(path,v.iter().flat_map(|x|x.to_le_bytes()).collect::<Vec<_>>()).unwrap();}
fn read(path:&Path)->Vec<f32>{fs::read(path).unwrap().chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect()}
#[test]
fn gpu_export_ae_is_separate_from_manual_print_compensation(){
 // Exact identity composition: AE ownership, the resident approximation,
 // then optical-model and donor-grain revisions which invalidate older payloads.
 let resident_and_optics=crate::gpu_export::ALGORITHM.strip_prefix("export-ae-owned-v2-")
  .expect("GPU export must retain the AE-owned v2 identity");
 let resident=resident_and_optics.strip_suffix("-cinestill4g-v1-donor-portra-v1")
  .expect("GPU export must invalidate pre-CineStill and pre-donor-grain payloads");
 assert_eq!(resident,spektrafilm_gpu::RESIDENT_APPROXIMATION_VERSION,
  "The complete resident approximation identity must remain unchanged");
 let dir=temp();let(w,h)=(64,48);let id=mobile::create(data().to_str().unwrap()).unwrap();let engine=mobile::get(id).unwrap();
 let mut p=MobileParams::default();p.film_stock="kodak_ultramax_400".into();p.auto_exposure=true;p.metering="average".into();p.grain_amount=0.;p.halation_amount=0.;p.scatter_amount=0.;p.dir_amount=0.;p.output_gamut=false;
 for (i,(scale,manual,auto,comp,normalize)) in [(1.,0.,true,true,true),(8.,0.,true,true,true),(1.,1.,true,true,true),(8.,-1.,true,true,true),(1.,0.,false,true,true),(1.,0.,true,false,true),(1.,0.,true,true,false),(1.,0.,true,false,false)].into_iter().enumerate(){
  p.film_exposure_ev=manual;p.auto_exposure=auto;p.print_compensation=comp;p.normalize_print_exposure=normalize;
  let v=values(w,h,scale);let preview=mobile::render(&engine,0,&v,w,h,&p).unwrap();
  if !preview.gpu.active {assert!(!gpu_required(),"GPU required: {:?}",preview.gpu);mobile::destroy(id);return;}
  let src=dir.0.join(format!("source-{i}.rgb"));let out=dir.0.join(format!("out-{i}.rgb"));write(&src,&v);
  let mut export=p.clone();export.mode="export".into();mobile::plan_export(w as i32,h as i32,&export).unwrap();let film=spektrafilm_core::profile::load_profile_by_name(&data(),&p.film_stock).unwrap();let runtime=export.runtime_for_film(&film);
  let metrics=streamed::export_file_checkpointed(&data(),&p.film_stock,&p.paper_stock,runtime,&src,&out,&dir.0.join(format!("scratch-{i}")),None,w,h,384,p.brightness_ev,true,||false,|_|{}).unwrap();
  assert!(metrics.gpu.active);assert_eq!(metrics.gpu.cpu_fallbacks,0);let result=read(&out);
  let mean_a=preview.pixels.iter().map(|&v|v as f64).sum::<f64>()/preview.pixels.len()as f64;
  let mean_b=result.iter().map(|&v|v as f64).sum::<f64>()/result.len()as f64;
  let delta=(mean_b/mean_a).log2();let max=result.iter().zip(&preview.pixels).map(|(a,b)|(a-b).abs()).fold(0f32,f32::max);
  eprintln!("AE_OWNERSHIP case={i} scale={scale} manual={manual} auto={auto} comp={comp} normalize={normalize} metered={:?} deltaEV={delta} max={max}",metrics.metered_ev);
  assert!(delta.abs()<0.0001 && max<0.0001,"Automatic EV changed manual print compensation");
 }
 mobile::destroy(id);
}
#[test]
fn gpu_export_resolved_ae_keeps_early_negative_and_ignores_legacy_checkpoint(){
 let dir=temp();let(w,h)=(64,48);let src=dir.0.join("source.rgb");write(&src,&values(w,h,1.));
 let mut p=spektrafilm_core::params::RuntimeParams::default();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.camera.auto_exposure=true;p.camera.exposure_compensation_ev=0.75;p.film_render.halation.boost_ev=0.;
 let token="12345678-1234-1234-1234-123456789abc";fs::write(dir.0.join("scan-preview-attempt"),token).unwrap();
 let checkpoint=dir.0.join("checkpoint");let legacy=checkpoint.join("gpu-v41-approx-filmlog-v3/manifest.json");fs::create_dir_all(legacy.parent().unwrap()).unwrap();fs::write(&legacy,b"legacy brightness-bug checkpoint").unwrap();
 let out=dir.0.join("out.rgb");let metrics=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p.clone(),&src,&out,&dir.0.join("scratch"),Some(&checkpoint),w,h,384,0.,true,||false,|_|{}).unwrap();
 if !metrics.gpu.active {assert!(!gpu_required(),"GPU required");return;}
 assert_ne!(metrics.gpu.reason,"gpu_checkpoint_restored");assert_eq!(fs::read(&legacy).unwrap(),b"legacy brightness-bug checkpoint");assert!(checkpoint.join("gpu-v41-approx-filmlog-ae-v4/manifest.json").is_file());
 let early=dir.0.join(format!("negative-preview-early-{token}.png"));let until=std::time::Instant::now()+std::time::Duration::from_secs(15);
 while (!early.exists()||crate::early_negative_preview::active_reservation_bytes()!=0)&&std::time::Instant::now()<until{std::thread::sleep(std::time::Duration::from_millis(10));}
 assert!(fs::read(&early).unwrap().starts_with(b"\x89PNG\r\n\x1a\n"));assert_eq!(crate::early_negative_preview::active_reservation_bytes(),0);
 let restored=dir.0.join("restored.rgb");let again=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p,&src,&restored,&dir.0.join("scratch2"),Some(&checkpoint),w,h,384,0.,true,||false,|_|{}).unwrap();
 assert_eq!(again.gpu.reason,"gpu_checkpoint_restored");assert_eq!(fs::read(out).unwrap(),fs::read(restored).unwrap());
}
#[test]
fn early_negative_resolved_ae_requires_finite_scale_without_remetering(){
 use spektrafilm_core::{pipeline::Pipeline,profile::load_profile_by_name,params::RuntimeParams};
 use spektrafilm_math::{image::ImageBuf,precision::from_f64};
 let(w,h)=(32,24);let mut p=RuntimeParams::default();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.camera.auto_exposure=true;p.camera.exposure_compensation_ev=0.75;
 let film=load_profile_by_name(&data(),"kodak_portra_400").unwrap();let paper=load_profile_by_name(&data(),"kodak_portra_endura").unwrap();
 let input=ImageBuf::from_data(w,h,values(w,h,1.).iter().map(|&v|from_f64(v as f64)).collect());
 for ev in [None,Some(f32::NAN),Some(f32::INFINITY),Some(1000.)]{
  p.camera.preview_metered_ev=ev;let pipe=Pipeline::new_with_spectral(film.clone(),paper.clone(),p.clone(),&data()).unwrap();
  assert!(pipe.low_resolution_pre_grain_density_cpu(&input,w,h,&mut||true).is_err(),"Unresolved/nonfinite-scale AE must not enter the thumbnail simulation");
 }
 p.camera.preview_metered_ev=Some(0.5);let pipe=Pipeline::new_with_spectral(film.clone(),paper.clone(),p.clone(),&data()).unwrap();
 let actual=pipe.low_resolution_pre_grain_density_cpu(&input,w,h,&mut||true).unwrap();
 p.camera.auto_exposure=false;p.camera.preview_metered_ev=None;
 let reference=Pipeline::new_with_spectral(film,paper,p,&data()).unwrap();let mut scaled=input.clone();for v in &mut scaled.data{*v*=from_f64(2.0f32.powf(0.5)as f64);}
 let expected=reference.low_resolution_pre_grain_density_cpu(&scaled,w,h,&mut||true).unwrap();
 assert_eq!(actual.data,expected.data,"Early negative must use exactly the already-resolved full-source EV");
}
