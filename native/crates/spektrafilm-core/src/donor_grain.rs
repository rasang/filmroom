//! Independent Portra 400 grain field for the digital (no-film) route.
//! A fixed middle-density field is used, not the image's tonal response. The
//! expected clean density is removed before perturbing original log RGB. This
//! deliberately does not simulate Portra colour, sensitometry, or image blur.
use crate::{profile::{Profile,load_profile_by_name},params::GrainParams};
use std::path::Path;
pub const STOCK:&str="kodak_portra_400";
#[derive(Clone)]
pub struct DonorGrain {pub profile:Profile,pub density:[f64;3],pub layers_baseline:[f64;3]}
impl DonorGrain {
 pub fn load(data:&Path)->Result<Self,String>{
  let profile=load_profile_by_name(data,STOCK).map_err(|e|format!("Portra grain profile: {e}"))?;
  let curves=spektrafilm_model::density_curves::normalize_density_curves_f64(&profile.density_curves_f64());
  let layers=&profile.data.density_curves_layers;
  if curves.len()<2||layers.len()!=curves.len()||layers.iter().any(|r|r.len()!=3||r.iter().any(|s|s.len()!=3||s.iter().any(|v|!v.is_finite()))) {return Err("Invalid Portra grain layers".into());}
  let max=spektrafilm_model::density_curves::max_density_f64(&curves);
  if max.iter().any(|v|!v.is_finite()||*v<=0.) {return Err("Invalid Portra grain range".into());}
  let density=max.map(|v|v*0.5);
  let mut baseline=[0.;3];
  for c in 0..3 {
   if curves.iter().any(|w|!w[c].is_finite()) {return Err("Invalid Portra grain axis".into());}
   let hi=curves.partition_point(|r|r[c]<=density[c]).clamp(1,curves.len()-1);let lo=hi-1;
   let dx=curves[hi][c]-curves[lo][c];if dx<=0. || density[c]<curves[lo][c] || density[c]>curves[hi][c] {return Err("Invalid Portra reference bracket".into());}let t=if dx>0.{(density[c]-curves[lo][c])/dx}else{0.};
   baseline[c]=(0..3).map(|sl|layers[lo][sl][c]+t*(layers[hi][sl][c]-layers[lo][sl][c])).sum();
  }
  Ok(Self{profile,density,layers_baseline:baseline})
 }
 pub fn baseline(&self,g:&GrainParams)->[f64;3]{if g.sublayers_active{self.layers_baseline}else{self.density}}
}
/// Optical density residual only; bound extreme stochastic outliers to two
/// decades to keep downstream light finite. Zero amount is an exact bypass.
pub fn restore(original:f64,noisy:f64,baseline:f64,amount:f64)->f64 {
 if amount==0. {original}else{original-((noisy-baseline)*amount).clamp(-2.,2.)}
}
#[cfg(test)] mod tests {
 use super::*;use crate::{pipeline::Pipeline,params::RuntimeParams};
 use spektrafilm_math::{image::ImageBuf,precision::from_f64};use spektrafilm_gpu::cpu_backend::CpuBackend;
 fn root()->std::path::PathBuf{Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
 fn params()->RuntimeParams{let mut p=RuntimeParams::default();p.camera.auto_exposure=false;p.camera.pixel_size_um_override=Some(5.);p.io.input_color_space="sRGB".into();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.io.output_gamut_compress.algorithm="off".into();p.scanner.unsharp_mask=[0.,0.];p.scanner.lens_blur=0.;p.film_render.grain.coordinate_origin=Some([0,0]);p}
 #[test] fn portra_donor_zero_is_exact_bypass_and_does_not_load_donor(){
  let mut p=params();p.film_render.grain.amount=0.;
  let a=Pipeline::new_with_spectral(Profile::omitted(),Profile::omitted(),p.clone(),&root()).unwrap();assert!(a.donor_grain.is_none());
  p.film_render.grain.active=false;let b=Pipeline::new_with_spectral(Profile::omitted(),Profile::omitted(),p,&root()).unwrap();
  let image=ImageBuf::from_data(2,1,vec![from_f64(0.18);6]);assert_eq!(a.process(image.clone(),&CpuBackend).data,b.process(image,&CpuBackend).data);
 }
 #[test] fn portra_donor_residual_is_scene_independent_deterministic_and_nonzero(){
  for sublayers in [false,true]{let mut p=params();p.film_render.grain.sublayers_active=sublayers;p.film_render.grain.amount=0.5;
   let pipe=Pipeline::new_with_spectral(Profile::omitted(),Profile::omitted(),p,&root()).unwrap();assert_eq!(pipe.donor_grain.as_ref().unwrap().profile.info.stock.as_deref(),Some(STOCK));
   let a=ImageBuf::from_data(32,24,vec![from_f64(0.18);32*24*3]);let b=ImageBuf::from_data(32,24,(0..32*24*3).map(|i|from_f64(if i%2==0 {0.09}else{0.36})).collect());
   let mut clean_p=pipe.params.clone();clean_p.film_render.grain.active=false;let clean=pipe.clone().with_params(clean_p);let clean_a=clean.process(a.clone(),&CpuBackend);let clean_b=clean.process(b.clone(),&CpuBackend);
   let out=pipe.process(a.clone(),&CpuBackend);let repeat=pipe.process(a,&CpuBackend);let edges=pipe.process(b.clone(),&CpuBackend);assert_eq!(out.data,repeat.data);
   assert!(out.data.iter().any(|v|(*v as f64-0.18).abs()>1e-5));
   for i in 0..out.data.len(){assert!(out.data[i].is_finite());let first=(out.data[i] as f64+1e-10)/(clean_a.data[i] as f64+1e-10);let second=(edges.data[i] as f64+1e-10)/(clean_b.data[i] as f64+1e-10);assert!((first-second).abs()<2e-5,"no source colour curve or edge blur may leak into residual at {i}: ratio {first}/{second}, out {}/{}, input {}, sublayers={sublayers}",out.data[i],edges.data[i],b.data[i]);}
  }
 }
 #[test] fn portra_donor_restore_zero_and_outlier_safety(){assert_eq!(restore(0.25,1.,2.,0.),0.25);assert_eq!(restore(0.,100.,0.,2.),-2.);assert_eq!(restore(0.,-100.,0.,2.),2.);}
}
