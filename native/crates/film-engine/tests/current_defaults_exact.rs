//! Current Rust whole-vs-streamed regression using a deterministic synthetic
//! fixture. This is NOT a historical/Python oracle and replaces no fixture.
use std::{fs,path::{Path,PathBuf}};
use film_engine::streamed;
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline,profile::load_profile_by_name};
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::{image::ImageBuf,precision::from_f64};
struct Temp(PathBuf);impl Drop for Temp{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
#[test]
fn current_defaults_synthetic_physical_all_and_effects_off_are_f32_bit_exact_across_chunks(){
 assert!(cfg!(feature="precision-f64"));
 let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let(w,h)=(257,129);
 // Integer-generated f32 linear samples contain black, white, smooth variation,
 // colored edges and bright impulses without platform-dependent RNG/libm.
 let values:Vec<f32>=(0..w*h*3).map(|i|{let pixel=i/3;let x=pixel%w;let y=pixel/w;let c=i%3;if (x+11*y)%97==0{1.}else if (x+3*y)%71==0{0.}else{((x*17+y*31+c*53)%1024)as f32/1024.}}).collect();
 for case in ["physical_all","effects_off"] {
  let mut p=RuntimeParams::default();p.camera.auto_exposure=false;p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.scanner.unsharp_mask=[0.,0.];p.print_render.glare.active=false;
  p.camera.pixel_size_um_override=Some(p.camera.film_format_mm*1000./w.max(h)as f32);p.film_render.grain.coordinate_origin=Some([0,0]);p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.settings.lut_resolution=33;
  if case=="effects_off" {p.film_render.grain.active=false;p.film_render.halation.active=false;p.film_render.dir_couplers.amount=0.;}
  assert!(!p.camera.diffusion_filter.active&&!p.enlarger.diffusion_filter.active);assert_eq!(p.film_render.grain.micro_structure,[0.2,30.]);
  let film="kodak_portra_400";let paper="kodak_portra_endura";let pipe=Pipeline::new_with_spectral(load_profile_by_name(&data,film).unwrap(),load_profile_by_name(&data,paper).unwrap(),p.clone(),&data).unwrap();
  let expected=pipe.process(ImageBuf::from_data(w,h,values.iter().map(|v|from_f64(*v as f64)).collect()),&CpuBackend);
  let root=std::env::temp_dir().join(format!("film-current-defaults-{case}-{}",std::process::id()));fs::create_dir(&root).unwrap();let _guard=Temp(root.clone());let input=root.join("input");let output=root.join("output");let scratch=root.join("scratch");let bytes:Vec<u8>=values.iter().flat_map(|v|v.to_le_bytes()).collect();fs::write(&input,&bytes).unwrap();
  let mut exposure_events=Vec::new();let metrics=streamed::export_file(&data,film,paper,p,&input,&output,&scratch,w,h,384,0.,||false,|stage,done,total|if stage=="projection"{exposure_events.push((done,total));}).unwrap();
  let raw=fs::read(&output).unwrap();assert_eq!(raw.len(),values.len()*4);let actual:Vec<f32>=raw.chunks_exact(4).map(|v|f32::from_le_bytes(v.try_into().unwrap())).collect();
  assert_eq!(metrics.pointwise_chunk_rows,127);assert!(h>metrics.pointwise_chunk_rows);assert!(!scratch.exists());assert_eq!(fs::read(&input).unwrap(),bytes);
  assert!(actual.iter().all(|v|v.is_finite()));assert!(expected.data.iter().all(|v|v.is_finite()));
  let different=actual.iter().zip(&expected.data).filter(|(a,b)|a.to_bits()!=(**b as f32).to_bits()).count();
  let first=actual.iter().zip(&expected.data).enumerate().find(|(_, (a,b))|a.to_bits()!=(**b as f32).to_bits()).map(|(i,(a,b))|(i,a.to_bits(),(*b as f32).to_bits()));
  assert_eq!(different,0,"Current Rust synthetic {case} differs in {different} samples; first={first:?}. This is not a Python oracle.");
 }
}
