use film_engine::streamed::export_file_checkpointed;
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline,profile::load_profile_by_name};
use spektrafilm_gpu::cpu_backend::CpuBackend;
use spektrafilm_math::{image::ImageBuf,precision::from_f64};
use std::{fs,path::{Path,PathBuf}};
 use std::sync::atomic::{AtomicU64,Ordering};
 static NEXT:AtomicU64=AtomicU64::new(0);
 struct Temp(PathBuf);
 impl Temp {fn new()->Self {let p=std::env::temp_dir().join(format!("filmroom-omitted-stream-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));fs::create_dir(&p).unwrap();Self(p)}}
 impl Drop for Temp {fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
 fn parameters()->RuntimeParams {
  let mut p=RuntimeParams::default();p.camera.auto_exposure=false;p.camera.exposure_compensation_ev=0.;
  p.camera.diffusion_filter.active=false;p.camera.lens_blur_um=0.;p.film_render.halation.active=false;p.film_render.halation.boost_ev=0.;
  p.film_render.grain.active=false;p.film_render.dir_couplers.active=false;
  p.enlarger.diffusion_filter.active=false;p.print_render.glare.active=false;p.film_render.glare.active=false;
  p.scanner.lens_blur=0.;p.scanner.unsharp_mask=[0.,0.];p.scanner.white_correction=false;p.scanner.black_correction=false;
  p.io.crop=false;p.io.upscale_factor=1.;p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.io.output_color_space="sRGB".into();p.io.output_gamut_compress.algorithm="off".into();
  p.settings.rgb_to_raw_method="hanatos2025".into();p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.settings.lut_resolution=33;
  p
 }
 #[test]
 fn all_four_media_combinations_match_full_cpu_pointwise_output() {
  let root=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let (w,h)=(8,6);
  let pixels:Vec<f32>=(0..w*h*3).map(|i|0.025+((i*17)%113) as f32/140.).collect();
  for color_space in ["sRGB","ProPhoto RGB"] { for request_gpu in [false,true] { for film_id in ["none","kodak_portra_400"] {for paper_id in ["none","kodak_portra_endura"] {
   let temp=Temp::new();let input=temp.0.join("input.rgb");let output=temp.0.join("output.rgb");let scratch=temp.0.join("scratch");
   fs::write(&input,pixels.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
   let mut p=parameters();p.io.input_color_space=color_space.into();if film_id=="none"||paper_id=="none"{p.io.scan_film=paper_id=="none";}
   let film=if film_id=="none"{spektrafilm_core::profile::Profile::omitted()}else{load_profile_by_name(&root,film_id).unwrap()};
   let paper=if paper_id=="none"{spektrafilm_core::profile::Profile::omitted()}else{load_profile_by_name(&root,paper_id).unwrap()};
   let mut expected_params=p.clone();
   if request_gpu {expected_params.settings.use_enlarger_lut=false;expected_params.settings.use_scanner_lut=false;}
   let pipe=Pipeline::new_with_spectral(film,paper,expected_params,&root).unwrap();
   let expected=pipe.process(ImageBuf::from_data(w,h,pixels.iter().map(|v|from_f64(*v as f64)).collect()),&CpuBackend);
   let metrics=export_file_checkpointed(&root,film_id,paper_id,p,&input,&output,&scratch,None,w,h,384,0.,request_gpu,||false,|_|{}).unwrap();
   if request_gpu && std::env::var("VK_ICD_FILENAMES").unwrap_or_default().contains("swiftshader") {
    assert!(metrics.gpu.active,"host Vulkan export must actually execute GPU work");
    assert!(metrics.gpu.gpu_batches>0);
   }
   let bytes=fs::read(&output).unwrap();assert_eq!(bytes.len(),pixels.len()*4);
   for (index,(raw,want)) in bytes.chunks_exact(4).zip(expected.data).enumerate() {
    let actual=f32::from_le_bytes(raw.try_into().unwrap());assert!(actual.is_finite());
    assert!((actual as f64-want as f64).abs()<2e-5,"{color_space}/gpu_requested={request_gpu}/{film_id}/{paper_id} sample {index}: streamed={actual} full={want}");
   }
  }}}}
 }

