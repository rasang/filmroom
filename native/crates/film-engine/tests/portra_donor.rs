#![cfg(all(feature="adreno-gpu",feature="precision-f64"))]
use film_engine::mobile;
use spektrafilm_core::params::RuntimeParams;
use std::path::{Path,PathBuf};
fn data()->PathBuf{Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
fn required()->bool{std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref()==Ok("1")}
#[path="omitted_media_stream.rs"] mod stream;
#[test]fn portra_donor_gpu_residual_does_not_copy_stock_colour_or_blur_source(){
 let Some(gpu)=spektrafilm_gpu::wgpu_backend::WgpuBackend::new() else {assert!(!required(),"GPU required");return};
 use spektrafilm_core::{pipeline::Pipeline,profile::Profile};
 let (w,h)=(64,48);let mut p=RuntimeParams::default();p.camera.auto_exposure=false;p.camera.pixel_size_um_override=Some(5.);p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.io.input_color_space="sRGB".into();p.io.output_gamut_compress.algorithm="off".into();p.scanner.lens_blur=0.;p.scanner.unsharp_mask=[0.,0.];p.film_render.grain.coordinate_origin=Some([0,0]);
 for sublayers in [false,true]{p.film_render.grain.sublayers_active=sublayers;p.film_render.grain.amount=0.5;
  let pipe=Pipeline::new_with_spectral(Profile::omitted(),Profile::omitted(),p.clone(),&data()).unwrap();
  let a=vec![0.18;w as usize*h as usize*3];let b:Vec<f32>=(0..a.len()).map(|i|if i%2==0{0.09}else{0.36}).collect();
  let mut clean_p=p.clone();clean_p.film_render.grain.active=false;let clean=pipe.clone().with_params(clean_p);let clean_a=clean.process_gpu_resident_cpu_gamut_f32_controlled(&a,w,h,&gpu,||true).unwrap();let clean_b=clean.process_gpu_resident_cpu_gamut_f32_controlled(&b,w,h,&gpu,||true).unwrap();
  let out=pipe.process_gpu_resident_cpu_gamut_f32_controlled(&a,w,h,&gpu,||true).unwrap();let edges=pipe.process_gpu_resident_cpu_gamut_f32_controlled(&b,w,h,&gpu,||true).unwrap();
  assert!(out.data.iter().any(|v|(*v as f64-0.18).abs()>1e-5));
  for i in 0..a.len(){assert!(out.data[i].is_finite());let first=(out.data[i]as f64+1e-10)/(clean_a.data[i]as f64+1e-10);let second=(edges.data[i]as f64+1e-10)/(clean_b.data[i]as f64+1e-10);assert!((first-second).abs()<1e-4,"source signal leaked into grain {first} {second}");}
  assert!(pipe.process_gpu_resident_cpu_gamut_f32_controlled(&a,w,h,&gpu,||false).is_none());
  assert!(pipe.process_gpu_resident_cpu_gamut_f32_controlled(&a,w,h,&gpu,||true).is_some());
 }
}

#[test]fn portra_donor_mobile_toggle_rebuilds_cache(){
 let id=mobile::create(data().to_str().unwrap()).unwrap();let e=mobile::get(id).unwrap();
 let mut p=mobile::MobileParams::default();p.film_stock="none".into();p.paper_stock="none".into();p.output_route="direct".into();p.grain_amount=0.;p.output_gamut=false;
 let input=vec![0.18f32;64*48*3];
 for gpu in [false,true]{p.use_gpu=gpu;p.grain_amount=0.;let off=mobile::render(&e,0,&input,64,48,&p).unwrap();
  p.grain_amount=1.;let legacy=mobile::render(&e,0,&input,64,48,&p).unwrap();assert!(off.pixels.iter().zip(&legacy.pixels).any(|(a,b)|(a-b).abs()>1e-6),"no-film uses ordinary grain amount without a separate opt-in");
  for old_flag in [false,true] { let mut json=serde_json::to_value(&p).unwrap();json["noFilmGrainEnabled"]=serde_json::json!(old_flag);let old=mobile::MobileParams::parse(&json.to_string()).unwrap();assert_eq!(legacy.pixels,mobile::render(&e,0,&input,64,48,&old).unwrap().pixels,"obsolete switch must not override grain amount"); }
  p.grain_amount=0.8;let on=mobile::render(&e,0,&input,64,48,&p).unwrap();let again=mobile::render(&e,0,&input,64,48,&p).unwrap();
  assert_eq!(on.pixels,again.pixels);assert!(off.pixels.iter().zip(&on.pixels).any(|(a,b)|(a-b).abs()>1e-6));
  if gpu&&required(){assert!(on.gpu.active);}
  p.grain_amount=0.;assert_eq!(off.pixels,mobile::render(&e,0,&input,64,48,&p).unwrap().pixels);
 }
 let parsed=mobile::MobileParams::parse(r#"{"filmStock":"none","paperStock":"none","grainAmount":1}"#).unwrap();assert_eq!(parsed.grain_amount,1.);
 for legacy in [false,true] { let old=mobile::MobileParams::parse(&format!(r#"{{"filmStock":"none","paperStock":"none","grainAmount":1,"noFilmGrainEnabled":{legacy}}}"#)).unwrap();assert_eq!(old.grain_amount,parsed.grain_amount); }
 mobile::destroy(id);
}
#[test]fn portra_donor_stream_crosses_chunks_and_cancel_reentry_is_clean(){
 use std::{fs,cell::Cell};use spektrafilm_core::{pipeline::Pipeline,profile::Profile};use spektrafilm_math::{image::ImageBuf,precision::from_f64};
 let root=std::env::temp_dir().join(format!("portra-stream-cancel-{}",std::process::id()));let _=fs::remove_dir_all(&root);fs::create_dir(&root).unwrap();
 let (w,h)=(257,129);let pixels=vec![0.18f32;w as usize*h as usize*3];let input=root.join("input");let output=root.join("output");let scratch=root.join("scratch");fs::write(&input,pixels.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
 let mut p=RuntimeParams::default();p.camera.auto_exposure=false;p.camera.pixel_size_um_override=Some(5.);p.io.input_color_space="sRGB".into();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.io.output_gamut_compress.algorithm="off".into();p.scanner.unsharp_mask=[0.,0.];p.scanner.lens_blur=0.;p.film_render.grain.coordinate_origin=Some([0,0]);p.film_render.grain.amount=0.5;
 let stop=Cell::new(false);let result=film_engine::streamed::export_file(&data(),"none","none",p.clone(),&input,&output,&scratch,w,h,384,0.,||stop.get(),|stage,done,_|{if stage=="grain_sampling"&&done>0{stop.set(true)}});assert_eq!(result.unwrap_err().code,"CANCELLED");assert!(!output.exists());assert!(!scratch.exists());
 let pipe=Pipeline::new_with_spectral(Profile::omitted(),Profile::omitted(),p.clone(),&data()).unwrap();let expected=pipe.process(ImageBuf::from_data(w,h,pixels.iter().map(|v|from_f64(*v as f64)).collect()),&spektrafilm_gpu::cpu_backend::CpuBackend);
 let metrics=film_engine::streamed::export_file(&data(),"none","none",p,&input,&output,&scratch,w,h,384,0.,||false,|_,_,_|{}).unwrap();assert!(metrics.peak_logical_scratch_bytes<=metrics.estimated_scratch_bytes);
 let bytes=fs::read(&output).unwrap();for (b,e) in bytes.chunks_exact(4).zip(expected.data){assert!((f32::from_le_bytes(b.try_into().unwrap())as f64-e as f64).abs()<3e-5);}
 fs::remove_dir_all(root).unwrap();
}
