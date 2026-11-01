use std::{fs,path::{Path,PathBuf},sync::atomic::{AtomicU64,Ordering}};
use film_engine::{mobile::MobileParams,streamed};
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline,profile::load_profile_by_name};
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::{image::ImageBuf,precision::from_f64};
static NEXT:AtomicU64=AtomicU64::new(0);
struct Temp(PathBuf);impl Drop for Temp{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
fn temp()->Temp{let p=std::env::temp_dir().join(format!("film-new-effects-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));fs::create_dir(&p).unwrap();Temp(p)}
fn data()->PathBuf{Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
fn runtime()->RuntimeParams{let mut p=RuntimeParams::default();p.camera.auto_exposure=false;p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.scanner.unsharp_mask=[0.,0.];p.print_render.glare.active=false;p.film_render.grain.active=false;p.film_render.halation.active=false;p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.settings.lut_resolution=33;p}
fn compare(mut p:RuntimeParams,w:u32,h:u32)->Vec<f32>{
 let dir=temp();let input=dir.0.join("input");let output=dir.0.join("output");let scratch=dir.0.join("scratch");
 let values:Vec<f32>=(0..w as usize*h as usize*3).map(|i| {let x=i/3%w as usize; let y=i/3/w as usize; ((x*13+y*7+i%3*29)%251)as f32/250.}).collect();
 fs::write(&input,values.iter().flat_map(|x|x.to_le_bytes()).collect::<Vec<_>>()).unwrap();
 p.camera.pixel_size_um_override=Some(p.camera.film_format_mm*1000./w.max(h)as f32);p.film_render.grain.coordinate_origin=Some([0,0]);
 let pipe=Pipeline::new_with_spectral(load_profile_by_name(&data(),"kodak_portra_400").unwrap(),load_profile_by_name(&data(),"kodak_portra_endura").unwrap(),p.clone(),&data()).unwrap();
 let full=pipe.process(ImageBuf::from_data(w,h,values.iter().map(|x|from_f64(*x as f64)).collect()),&CpuBackend);
 let metrics=streamed::export_file(&data(),"kodak_portra_400","kodak_portra_endura",p,&input,&output,&scratch,w,h,384,0.,||false,|_,_,_|{}).unwrap();
 let actual:Vec<f32>=fs::read(&output).unwrap().chunks_exact(4).map(|x|f32::from_le_bytes(x.try_into().unwrap())).collect();
 let expected:Vec<f32>=full.data.iter().map(|x|*x as f32).collect();let max=actual.iter().zip(&expected).map(|(a,b)|(*a-*b).abs()).fold(0f32,f32::max);
 assert_eq!(actual.len(),expected.len());assert!(max<=2e-6,"max difference {max}");assert!(!scratch.exists());assert!(metrics.peak_logical_scratch_bytes<=metrics.estimated_scratch_bytes);
 actual
}
#[test]fn print_filter_and_preflash_match_reference_and_change_output(){
 let base=compare(runtime(),37,29);
 for mode in 0..3{let mut p=runtime();match mode{0=>{p.enlarger.y_filter_shift=20.;p.enlarger.m_filter_shift=-17.;},1=>p.enlarger.preflash_exposure=0.1,_=>{p.enlarger.preflash_exposure=0.2;p.enlarger.y_filter_shift=-30.;p.enlarger.m_filter_shift=30.;}}
 let changed=compare(p,37,29);assert!(changed.iter().zip(&base).any(|(a,b)|(a-b).abs()>0.001));}
}
#[test]fn sharpen_matches_reference_across_row_chunks(){let mut p=runtime();p.scanner.unsharp_mask=[1.7,1.2];compare(p,257,129);}
#[test]fn extra_params_are_validated(){
 assert!(MobileParams::parse(r#"{"diffusionFilter":"black_pro_mist","diffusionStrength":2,"diffusionSize":2,"printYellow":30,"printMagenta":-30,"preflash":0.2,"scanSharpenAmount":2,"scanSharpenRadius":3}"#).is_ok());
 for bad in [r#"{"diffusionFilter":"unknown"}"#,r#"{"diffusionStrength":-1}"#,r#"{"diffusionSize":3}"#,r#"{"preflash":1}"#,r#"{"scanSharpenRadius":0}"#,r#"{"printYellow":31}"#,r#"{"printMagenta":-31}"#]{assert!(MobileParams::parse(bad).is_err(),"{bad}");}
}
#[test]fn cancel_sharpen_cleans_scratch_and_output(){
 use std::cell::Cell;let dir=temp();let(w,h)=(40,30);let input=dir.0.join("input");let output=dir.0.join("output");let scratch=dir.0.join("scratch");fs::write(&input,vec![0u8;w*h*12]).unwrap();let mut p=runtime();p.scanner.unsharp_mask=[1.,1.];let stop=Cell::new(false);
 let e=streamed::export_file(&data(),"kodak_portra_400","kodak_portra_endura",p,&input,&output,&scratch,w as u32,h as u32,384,0.,||stop.get(),|stage,done,_|{if stage=="scan_sharpen"&&done>0{stop.set(true);}}).unwrap_err();assert_eq!(e.code,"CANCELLED");assert!(!output.exists());assert!(!scratch.exists());assert!(input.exists());
}

#[test]fn camera_filters_match_whole_pipeline_with_other_effects(){
 for family in ["glimmerglass","black_pro_mist","pro_mist","cinebloom"] {let mut p=runtime();p.camera.diffusion_filter.active=true;p.camera.diffusion_filter.filter_family=family.into();p.camera.diffusion_filter.strength=0.75;p.camera.diffusion_filter.spatial_scale=1.3;p.scanner.unsharp_mask=[0.9,0.8];p.enlarger.preflash_exposure=0.05;p.enlarger.m_filter_shift=7.;compare(p,67,53);}
}
#[test]fn cancel_diffusion_cleans_scratch_and_preserves_input(){
 use std::cell::Cell;let dir=temp();let(w,h)=(47,31);let input=dir.0.join("input");let output=dir.0.join("output");let scratch=dir.0.join("scratch");fs::write(&input,vec![0u8;w*h*12]).unwrap();let mut p=runtime();p.camera.diffusion_filter.active=true;let stop=Cell::new(false);
 let e=streamed::export_file(&data(),"kodak_portra_400","kodak_portra_endura",p,&input,&output,&scratch,w as u32,h as u32,384,0.,||stop.get(),|stage,done,_|{if stage=="camera_diffusion"&&done>0{stop.set(true);}}).unwrap_err();assert_eq!(e.code,"CANCELLED");assert!(!output.exists());assert!(!scratch.exists());assert!(input.exists());
}
#[test]fn frame_blur_scatter_dir_and_grain_controls_match_reference(){
 let mut p=runtime();p.camera.film_format_mm=8.;p.camera.lens_blur_um=40.;p.scanner.lens_blur=1.3;p.scanner.unsharp_mask=[0.7,0.5];p.film_render.halation.active=true;p.film_render.halation.scatter_amount=0.7;p.film_render.halation.scatter_spatial_scale=2.;p.film_render.halation.halation_n_bounces=5;p.film_render.halation.halation_bounce_decay=0.;p.film_render.halation.halation_renormalize=false;
 p.film_render.dir_couplers.amount=1.3;p.film_render.dir_couplers.inhibition_interlayer=0.6;p.film_render.dir_couplers.inhibition_samelayer=0.8;p.film_render.dir_couplers.diffusion_size_um=80.;
 p.film_render.grain.active=true;p.film_render.grain.sublayers_active=false;p.film_render.grain.n_sub_layers=4;p.film_render.grain.blur=0.8;p.film_render.grain.blur_dye_clouds_um=1.4;for v in &mut p.film_render.grain.uniformity{*v*=0.7;}
 compare(p,129,257);
}
#[test]fn global_meter_boost_glare_and_microstructure_match_whole(){
 let mut p=runtime();p.camera.auto_exposure=true;p.camera.auto_exposure_method="highlight_weighted".into();p.camera.exposure_compensation_ev=0.7;p.camera.film_format_mm=8.;
 p.film_render.halation.boost_ev=1.2;p.film_render.halation.boost_range=0.4;p.film_render.halation.protect_ev=0.;p.film_render.grain.active=true;p.film_render.grain.micro_structure=[20.,1000.];
 p.print_render.glare.active=true;p.print_render.glare.percent=2.;p.print_render.glare.roughness=0.6;p.print_render.glare.blur=2.;p.scanner.unsharp_mask=[1.,0.5];compare(p,513,65);
}

#[test]fn print_diffusion_all_families_match_combined_whole_pipeline(){
 let mut base=runtime();base.camera.film_format_mm=8.;base.enlarger.print_exposure=1.7;base.enlarger.preflash_exposure=0.07;base.enlarger.preflash_y_filter_shift=9.;base.enlarger.preflash_m_filter_shift=-5.;
 base.print_render.glare.active=true;base.print_render.glare.percent=1.5;base.print_render.glare.roughness=0.4;base.print_render.glare.blur=1.3;base.scanner.unsharp_mask=[0.8,0.6];
 let neutral=compare(base.clone(),257,129);
 for family in ["glimmerglass","black_pro_mist","pro_mist","cinebloom"] {
  let mut p=base.clone();p.enlarger.diffusion_filter.active=true;p.enlarger.diffusion_filter.filter_family=family.into();p.enlarger.diffusion_filter.strength=1.2;p.enlarger.diffusion_filter.spatial_scale=1.6;
  let changed=compare(p,257,129);assert!(changed.iter().zip(&neutral).any(|(a,b)|(a-b).abs()>1e-5),"print {family} silently inactive");
 }
}
#[test]fn camera_and_print_diffusion_together_match_reference(){
 let mut p=runtime();p.camera.film_format_mm=8.;p.camera.diffusion_filter.active=true;p.camera.diffusion_filter.filter_family="pro_mist".into();p.camera.diffusion_filter.strength=0.8;p.enlarger.diffusion_filter.active=true;p.enlarger.diffusion_filter.filter_family="cinebloom".into();p.enlarger.diffusion_filter.strength=0.9;p.enlarger.preflash_exposure=0.03;p.enlarger.print_exposure=0.6;p.print_render.glare.active=true;p.print_render.glare.percent=0.7;p.scanner.unsharp_mask=[0.9,0.4];compare(p,129,257);
}
#[test]fn cancel_print_diffusion_cleans_scratch_and_preserves_input(){
 use std::cell::Cell;let dir=temp();let(w,h)=(47,31);let input=dir.0.join("input");let output=dir.0.join("output");let scratch=dir.0.join("scratch");let original=vec![0u8;w*h*12];fs::write(&input,&original).unwrap();let mut p=runtime();p.enlarger.diffusion_filter.active=true;let stop=Cell::new(false);
 let e=streamed::export_file(&data(),"kodak_portra_400","kodak_portra_endura",p,&input,&output,&scratch,w as u32,h as u32,384,0.,||stop.get(),|stage,done,total|{if stage=="print_diffusion"&&total==(w*h*3)as u64&&done>0{stop.set(true);}}).unwrap_err();assert_eq!(e.code,"CANCELLED");assert!(stop.get(),"cancellation never reached print diffusion");assert!(!output.exists());assert!(!scratch.exists());assert_eq!(fs::read(&input).unwrap(),original);
}
#[test]fn four_family_mobile_contract_and_print_bounds(){
 for family in ["off","glimmerglass","black_pro_mist","pro_mist","cinebloom"] {
  assert!(MobileParams::parse(&format!(r#"{{"diffusionFilter":"{family}","printDiffusionFilter":"{family}","printDiffusionStrength":1.2,"printDiffusionSize":1.5}}"#)).is_ok(),"{family}");
 }
 for bad in [r#"{"printDiffusionFilter":"unknown"}"#,r#"{"printDiffusionStrength":-1}"#,r#"{"printDiffusionStrength":2.01}"#,r#"{"printDiffusionSize":0.24}"#,r#"{"printDiffusionSize":2.01}"#]{assert!(MobileParams::parse(bad).is_err(),"{bad}");}
}
#[test]fn fifty_megapixel_diffusion_reserve_covers_sequential_spatial_work(){
 let(w,h)=(8192,6144);let n=w as u64*h as u64;let cap=(w.min(h)as usize/2).saturating_sub(1).max(1);
 let estimate=streamed::estimated_scratch_with_diffusion(w,h,true).unwrap();
 let bound=24*n+film_engine::disk_diffusion::estimated_extra_bytes(w,h,cap)+64*1024*1024;
 assert!(estimate>=bound);assert!(estimate>=streamed::estimated_scratch_bytes(w,h).unwrap());assert!(estimate<12_000_000_000,"Unexpected unbounded scratch reserve {estimate}");
}

#[test]fn scanner_blur_without_sharpen_matches_whole_and_reports_only_blur(){
 let mut p=runtime();p.scanner.lens_blur=2.3;p.scanner.unsharp_mask=[0.7,0.];compare(p.clone(),137,129);
 let dir=temp();let(w,h)=(43,31);let input=dir.0.join("input");let output=dir.0.join("output");let scratch=dir.0.join("scratch");fs::write(&input,vec![0u8;(w*h*12)as usize]).unwrap();let mut stages=Vec::new();
 let metrics=streamed::export_file(&data(),"kodak_portra_400","kodak_portra_endura",p,&input,&output,&scratch,w,h,384,0.,||false,|stage,_,_|stages.push(stage)).unwrap();assert!(stages.contains(&"scan_blur"));assert!(!stages.contains(&"scan_sharpen"));assert!(metrics.stages.iter().any(|s|s.stage=="scan_blur"));assert!(!metrics.stages.iter().any(|s|s.stage=="scan_sharpen"));assert!(!scratch.exists());
}
#[test]fn maximal_combined_spatial_controls_remain_finite_and_match_reference(){
 let mut p=runtime();p.camera.film_format_mm=8.;p.camera.lens_blur_um=100.;p.scanner.lens_blur=3.;p.scanner.unsharp_mask=[3.,2.];
 p.camera.diffusion_filter.active=true;p.camera.diffusion_filter.filter_family="pro_mist".into();p.camera.diffusion_filter.strength=2.;p.camera.diffusion_filter.spatial_scale=2.;p.enlarger.diffusion_filter=p.camera.diffusion_filter.clone();p.enlarger.diffusion_filter.filter_family="cinebloom".into();
 p.film_render.halation.active=true;p.film_render.halation.scatter_amount=2.;p.film_render.halation.scatter_spatial_scale=3.;p.film_render.halation.halation_amount=2.;p.film_render.halation.halation_spatial_scale=3.;p.film_render.halation.halation_n_bounces=5;p.film_render.halation.halation_bounce_decay=1.;
 p.film_render.dir_couplers.amount=2.;p.film_render.dir_couplers.diffusion_size_um=100.;p.film_render.dir_couplers.inhibition_samelayer=2.;p.film_render.dir_couplers.inhibition_interlayer=2.;
 p.enlarger.preflash_exposure=0.2;p.enlarger.print_exposure=16.;p.print_render.glare.active=true;p.print_render.glare.percent=5.;p.print_render.glare.roughness=1.;p.print_render.glare.blur=20.;
 let values=compare(p,73,67);assert!(values.iter().all(|v|v.is_finite()&&*v>=0.&&*v<=1.));
}
