//! Run with FILMROOM_REQUIRE_GPU_SMOKE=1 on a validated Vulkan test adapter.
//! Missing adapters are explicitly reported as skipped, never GPU success.
#![cfg(all(feature="adreno-gpu",feature="precision-f64"))]
use film_engine::{mobile::{self,MobileParams},streamed};
use spektrafilm_core::params::RuntimeParams;
use std::{fs,path::{Path,PathBuf},sync::atomic::{AtomicU64,Ordering}};
static NEXT:AtomicU64=AtomicU64::new(0);
struct Temp(PathBuf);impl Drop for Temp{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
fn temp()->Temp{let p=std::env::temp_dir().join(format!("gpu-app-smoke-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));fs::create_dir(&p).unwrap();Temp(p)}
fn data()->PathBuf{Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data")}
fn required()->bool{std::env::var("FILMROOM_REQUIRE_GPU_SMOKE").as_deref()==Ok("1")}
fn input(w:u32,h:u32)->Vec<f32>{(0..w as usize*h as usize*3).map(|i|{let x=i/3%w as usize;let y=i/3/w as usize;0.01+((x*13+y*17+i%3*29)%251)as f32/180.}).collect()}
fn runtime()->RuntimeParams{let mut p=RuntimeParams::default();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.camera.auto_exposure=true;p.camera.auto_exposure_method="center_weighted".into();p.camera.diffusion_filter.active=true;p.camera.diffusion_filter.filter_family="glimmerglass".into();p.enlarger.diffusion_filter.active=true;p.enlarger.diffusion_filter.filter_family="black_pro_mist".into();p.film_render.halation.boost_ev=0.4;p.print_render.glare.active=true;p.print_render.glare.percent=0.8;p.scanner.unsharp_mask=[0.7,0.4];p}
#[test]fn gpu_density_tap_is_pregrain_and_does_not_change_output(){
 let Some(gpu)=mobile::full_chain_gpu() else {assert!(!required(),"GPU required");return};
 let film=spektrafilm_core::profile::load_profile_by_name(&data(),"kodak_portra_400").unwrap();
 let paper=spektrafilm_core::profile::load_profile_by_name(&data(),"kodak_portra_endura").unwrap();
 let mut p=RuntimeParams::default();p.camera.auto_exposure=false;p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;
 let pipe=spektrafilm_core::pipeline::Pipeline::new_with_spectral(film,paper,p.clone(),&data()).unwrap();
 let (w,h)=(32,24);let values=input(w,h);
 let plain=pipe.process_gpu_resident_cpu_gamut_f32_controlled(&values,w,h,gpu,||true).unwrap();
 let points=vec![spektrafilm_gpu::FilmDensitySample{index:0,x:0,y:0},spektrafilm_gpu::FilmDensitySample{index:1,x:17,y:13},spektrafilm_gpu::FilmDensitySample{index:2,x:31,y:23}];
 let mut tap=spektrafilm_gpu::FilmDensityCapture{samples:points.clone(),result:None};
 let captured=pipe.process_gpu_resident_cpu_gamut_f32_capture_controlled(&values,w,h,gpu,&mut tap,||true).unwrap();
 assert_eq!(plain.data,captured.data,"optional density capture changed final output");
 let density=tap.result.take().expect("required GPU density tap missing");assert_eq!(density.len(),3);assert!(density.iter().all(|(_,v)|v.iter().all(|v|v.is_finite())));
 p.film_render.grain.active=false;
 let no_grain=pipe.clone().with_params(p);
 let mut second=spektrafilm_gpu::FilmDensityCapture{samples:points,result:None};
 no_grain.process_gpu_resident_cpu_gamut_f32_capture_controlled(&values,w,h,gpu,&mut second,||true).unwrap();
 assert_eq!(density,second.result.unwrap(),"tap must occur before grain");
 eprintln!("GPU_DENSITY_TAP_PASS pre-grain exact output and actual sampled density");
}
#[test]fn queue_gpu_negative_is_real_and_does_not_change_render(){
 let dir=temp();let (w,h)=(64,48);let src=dir.0.join("input.rgb");
 fs::write(&src,input(w,h).iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
 let mut p=RuntimeParams::default();p.camera.auto_exposure=false;p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;
 let baseline=dir.0.join("baseline.rgb");
 let a=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p.clone(),&src,&baseline,&dir.0.join("scratch-a"),None,w,h,384,0.,true,||false,|_|{}).unwrap();
 if !a.gpu.active {assert!(!required(),"GPU required");return;}
 let token="12345678-1234-1234-1234-123456789abc";fs::write(dir.0.join("scan-preview-attempt"),token).unwrap();
 let output=dir.0.join("output.rgb");let checkpoint=dir.0.join("checkpoint");
 let b=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p,&src,&output,&dir.0.join("scratch-b"),Some(&checkpoint),w,h,384,0.,true,||false,|_|{}).unwrap();
 assert!(b.gpu.active);assert_eq!(fs::read(&baseline).unwrap(),fs::read(&output).unwrap());
 let deadline=std::time::Instant::now()+std::time::Duration::from_secs(10);
 let pngs=[dir.0.join(format!("negative-preview-early-{token}.png")),dir.0.join(format!("negative-preview-{token}.png"))];
 while !pngs.iter().any(|p|p.is_file()) && std::time::Instant::now()<deadline {std::thread::sleep(std::time::Duration::from_millis(10));}
 let image=pngs.iter().find_map(|p|fs::read(p).ok()).expect("real negative preview not produced");
 assert!(image.starts_with(b"\x89PNG\r\n\x1a\n"));
 while crate::early_negative_preview::active_reservation_bytes()!=0 && std::time::Instant::now()<deadline {std::thread::sleep(std::time::Duration::from_millis(10));}
 assert_eq!(crate::early_negative_preview::active_reservation_bytes(),0);
 eprintln!("GPU_QUEUE_NEGATIVE_PASS true density-derived PNG and identical final RGB");
}
#[test]
#[ignore="Actual software/device Vulkan validation; run serially with explicit GPU requirement"]
fn queue_gpu_sharper_capture_preserves_tile_geometry_and_output(){
 let dir=temp();let(w,h)=(512,512);let src=dir.0.join("input.rgb");
 fs::write(&src,input(w,h).iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
 let mut p=RuntimeParams::default();p.camera.auto_exposure=false;p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;
 p.film_render.grain.active=false;p.film_render.halation.active=false;p.film_render.dir_couplers.active=false;
 let baseline=dir.0.join("baseline.rgb");
 let a=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p.clone(),&src,&baseline,&dir.0.join("scratch-a"),None,w,h,384,0.,true,||false,|_|{}).unwrap();
 if !a.gpu.active {assert!(!required(),"GPU required");return;}
 let token="12345678-1234-1234-1234-123456789abc";fs::write(dir.0.join("scan-preview-attempt"),token).unwrap();
 let output=dir.0.join("output.rgb");
 let b=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p,&src,&output,&dir.0.join("scratch-b"),Some(&dir.0.join("checkpoint")),w,h,384,0.,true,||false,|_|{}).unwrap();
 assert!(b.gpu.active);assert_eq!(fs::read(&baseline).unwrap(),fs::read(&output).unwrap());
 let (a,b)=(a.gpu_tiles.unwrap(),b.gpu_tiles.unwrap());
 assert_eq!((a.core_width,a.core_height),(b.core_width,b.core_height));
 let status=dir.0.join(format!("negative-preview-{token}.status"));
 let deadline=std::time::Instant::now()+std::time::Duration::from_secs(30);
 while fs::read_to_string(&status).ok().as_deref()!=Some("ready") && std::time::Instant::now()<deadline {std::thread::sleep(std::time::Duration::from_millis(10));}
 assert_eq!(fs::read_to_string(&status).unwrap(),"ready");
 let png=fs::read(dir.0.join(format!("negative-preview-{token}.png"))).unwrap();
 assert_eq!(u32::from_be_bytes(png[16..20].try_into().unwrap()),384);
 assert_eq!(u32::from_be_bytes(png[20..24].try_into().unwrap()),384);
 while crate::early_negative_preview::active_reservation_bytes()!=0 && std::time::Instant::now()<deadline {std::thread::sleep(std::time::Duration::from_millis(10));}
 assert_eq!(crate::early_negative_preview::active_reservation_bytes(),0);
 eprintln!("GPU_SHARPER_NEGATIVE_PASS exact384x384 samples589824 output_bytes_identical tile_geometry_identical");
}
#[test]fn real_mobile_preview_uses_gpu_and_switch_off_is_cpu(){
 let id=mobile::create(data().to_str().unwrap()).unwrap();let engine=mobile::get(id).unwrap();let values=input(96,64);let mut p=MobileParams::default();p.diffusion_filter="glimmerglass".into();p.print_diffusion_filter="black_pro_mist".into();p.highlight_boost_ev=0.4;p.scan_glare_percent=0.8;p.auto_exposure=true;p.scan_sharpen_amount=0.4;
 let gpu=mobile::render(&engine,0,&values,96,64,&p).unwrap();assert_eq!(gpu.pixels.len(),96*64*3);assert!(gpu.pixels.iter().all(|v|v.is_finite()));
 if !gpu.gpu.active {eprintln!("GPU_SMOKE_SKIPPED preview reason={}",gpu.gpu.reason);assert!(!required(),"GPU adapter/chain required but not active");mobile::destroy(id);return;}
 assert_eq!(gpu.gpu.backend,"wgpu");assert!(gpu.gpu.gpu_batches>0);assert!(gpu.gpu.numeric_guard_passed);assert!(!gpu.gpu.startup_self_test_passed);assert!(gpu.metered_ev.is_some());
 let warm=mobile::render(&engine,0,&values,96,64,&p).unwrap();assert!(warm.gpu.active);assert_eq!(warm.pixels,gpu.pixels,"Warm same-parameter cache changed scientific output");
 let mut changed=p.clone();changed.seed=43;let seeded=mobile::render(&engine,0,&values,96,64,&changed).unwrap();assert!(seeded.gpu.active);assert!(seeded.pixels.iter().zip(&gpu.pixels).any(|(a,b)|(a-b).abs()>1e-7),"Seed did not affect enabled grain");
 changed=p.clone();changed.grain_amount=0.;let no_grain=mobile::render(&engine,0,&values,96,64,&changed).unwrap();assert!(no_grain.gpu.active);assert!(no_grain.pixels.iter().zip(&gpu.pixels).any(|(a,b)|(a-b).abs()>1e-7),"Enabled grain was silently omitted");
 changed=p.clone();changed.diffusion_filter="off".into();changed.print_diffusion_filter="off".into();let no_diffusion=mobile::render(&engine,0,&values,96,64,&changed).unwrap();assert!(no_diffusion.gpu.active);assert!(no_diffusion.pixels.iter().zip(&gpu.pixels).any(|(a,b)|(a-b).abs()>1e-7),"Both diffusion controls were silently omitted");
 eprintln!("GPU_SMOKE_CONTROLS warmBatches={} seedBatches={} noGrainBatches={} noDiffusionBatches={}",warm.gpu.gpu_batches,seeded.gpu.gpu_batches,no_grain.gpu.gpu_batches,no_diffusion.gpu.gpu_batches);
 p.use_gpu=false;let cpu=mobile::render(&engine,0,&values,96,64,&p).unwrap();assert!(!cpu.gpu.requested);assert!(!cpu.gpu.active);assert_eq!(cpu.gpu.gpu_batches,0);assert_eq!(cpu.pixels.len(),gpu.pixels.len());mobile::destroy(id);
 eprintln!("GPU_SMOKE_PASS preview native entry with diffusion, halation, DIR, grain, highlight, glare, sharpen; switch-off CPU");
}
#[test]fn real_streamed_gpu_export_preserves_resolution_checkpoint_and_cancel(){
 let dir=temp();let(w,h)=(96,71);let values=input(w,h);let source=dir.0.join("input.rgb");fs::write(&source,values.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();let output=dir.0.join("out.rgb");let checkpoint=dir.0.join("checkpoint");let p=runtime();
 let legacy=checkpoint.join("gpu-v41-approx-tiles-v2/manifest.json");fs::create_dir_all(legacy.parent().unwrap()).unwrap();fs::write(&legacy,b"foreign legacy GPU checkpoint").unwrap();
 let metrics=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p.clone(),&source,&output,&dir.0.join("scratch"),Some(&checkpoint),w,h,128,0.2,true,||false,|_|{}).unwrap();
 assert_eq!(fs::metadata(&output).unwrap().len(),w as u64*h as u64*12);assert!(fs::read(&output).unwrap().chunks_exact(4).all(|b|f32::from_le_bytes(b.try_into().unwrap()).is_finite()));
 if !metrics.gpu.active {eprintln!("GPU_SMOKE_SKIPPED export reason={}",metrics.gpu.reason);assert!(!required(),"GPU adapter/chain required but not active");return;}
 assert_eq!(metrics.gpu.backend,"wgpu");assert!(checkpoint.join("gpu-v41-approx-filmlog-ae-v4/manifest.json").is_file());assert_eq!(fs::read(&legacy).unwrap(),b"foreign legacy GPU checkpoint");assert!(!checkpoint.join("manifest.json").exists());
 let restored=dir.0.join("restored.rgb");let resumed=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p.clone(),&source,&restored,&dir.0.join("scratch2"),Some(&checkpoint),w,h,128,0.2,true,||false,|_|{}).unwrap();assert_eq!(resumed.gpu.reason,"gpu_checkpoint_restored");assert_eq!(fs::read(&output).unwrap(),fs::read(&restored).unwrap());
 let cancelled=dir.0.join("cancel.rgb");let e=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p,&source,&cancelled,&dir.0.join("scratch3"),None,w,h,128,0.2,true,||true,|_|{}).unwrap_err();assert_eq!(e.code,"CANCELLED");assert!(!cancelled.exists());assert!(source.exists());
 eprintln!("GPU_SMOKE_PASS export real entry dimensions={w}x{h}, checkpoint byte-identical restore and cancellation");
}

#[test]fn actual_budget_bands_match_resident_global_coordinates(){
 let dir=temp();let(w,h)=(64,2049);let values=input(w,h);let source=dir.0.join("input.rgb");fs::write(&source,values.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
 let mut p=runtime();p.camera.auto_exposure=false;p.camera.film_format_mm=8.196;p.film_render.grain.micro_structure=[10.,800.];
 let prep=spektrafilm_core::pipeline::Pipeline::new_with_spectral(spektrafilm_core::profile::load_profile_by_name(&data(),"kodak_portra_400").unwrap(),spektrafilm_core::profile::load_profile_by_name(&data(),"kodak_portra_endura").unwrap(),p.clone(),&data()).unwrap();
 let pitch=p.camera.film_format_mm*1000./w.max(h) as f32;let halo=prep.banded_halo_pixels(pitch);let fields=prep.gpu_diffusion_working_bytes(w,h).unwrap();let fixed=96*1024*1024+fields;let low_rows=(128u64*1024*1024).checked_sub(fixed).unwrap()/(w as u64*256);let high_rows=(256u64*1024*1024).checked_sub(fixed).unwrap()/(w as u64*256);
 eprintln!("GPU_BAND_PLAN fixture={w}x{h} pitch={pitch} fields={fields} halo={halo} lowExpandedRows={low_rows} highExpandedRows={high_rows}");assert!(low_rows>2*halo as u64&&low_rows<h as u64);assert!(high_rows>=h as u64);drop(prep);
 let low=dir.0.join("low.rgb");let high=dir.0.join("high.rgb");
 let lo=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p.clone(),&source,&low,&dir.0.join("scratch-low"),None,w,h,128,0.,true,||false,|_|{}).unwrap();
 if !lo.gpu.active {eprintln!("GPU_SMOKE_SKIPPED banded reason={}",lo.gpu.reason);assert!(!required(),"Banded GPU required but not active");return;}
 let hi=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p.clone(),&source,&high,&dir.0.join("scratch-high"),None,w,h,256,0.,true,||false,|_|{}).unwrap();
 assert!(hi.gpu.active);assert_eq!(lo.gpu.accelerated_stage,"tiled-chain");assert_eq!(hi.gpu.accelerated_stage,"full-resident-chain");assert!(lo.pointwise_chunk_rows<hi.pointwise_chunk_rows);
 let a:Vec<f32>=fs::read(low).unwrap().chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect();let b:Vec<f32>=fs::read(high).unwrap().chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect();assert_eq!(a.len(),b.len());
 let max=a.iter().zip(&b).map(|(a,b)|(a-b).abs()).fold(0f32,f32::max);let rms=(a.iter().zip(&b).map(|(a,b)|((a-b)as f64).powi(2)).sum::<f64>()/a.len()as f64).sqrt();
 eprintln!("GPU_BAND_ALIGNMENT max={max} rms={rms} lowRows={} highRows={} lowBatches={} highBatches={}",lo.pointwise_chunk_rows,hi.pointwise_chunk_rows,lo.gpu.gpu_batches,hi.gpu.gpu_batches);
 // Engineering regression checks, not calibrated perceptual/industry quality limits.
 // Camera diffusion uses f64 global accumulation for bands and f32 resident math.
 // Tiny input differences can cross a discrete grain-count threshold. Report the
 // largest stochastic pixel delta instead of treating it as a quality verdict.
 let seam_max=a.iter().zip(&b).enumerate().filter(|(i,_)|{let y=i/3/w as usize;[0usize,1024,2048].iter().any(|&edge|y.abs_diff(edge)<=4)}).map(|(_,(&a,&b))|(a-b).abs()).fold(0f32,f32::max);
 eprintln!("GPU_STOCHASTIC_MONITOR max={max} rms={rms} seamMax={seam_max}");
 assert!(rms<0.0002&&seam_max<0.0001,"Aggregate or seam regression on this fixed engineering fixture");
 for (budget,expected,label) in [(128,&a,"low-repeat"),(256,&b,"high-repeat")] {
  let out=dir.0.join(format!("{label}.rgb"));let m=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p.clone(),&source,&out,&dir.0.join(format!("scratch-{label}")),None,w,h,budget,0.,true,||false,|_|{}).unwrap();assert!(m.gpu.active);
  let repeat:Vec<f32>=fs::read(out).unwrap().chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect();assert_eq!(&repeat,expected,"Fixed-seed same-path export must be exactly reproducible");
 }
 let mut deterministic=p.clone();deterministic.film_render.grain.active=false;let mut images=Vec::new();
 for budget in [128,256] {
  let out=dir.0.join(format!("no-grain-{budget}.rgb"));let m=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",deterministic.clone(),&source,&out,&dir.0.join(format!("scratch-no-grain-{budget}")),None,w,h,budget,0.,true,||false,|_|{}).unwrap();assert!(m.gpu.active);
  images.push(fs::read(out).unwrap().chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect::<Vec<_>>());
 }
 let deterministic_max=images[0].iter().zip(&images[1]).map(|(&a,&b)|(a-b).abs()).fold(0f32,f32::max);
 assert!(deterministic_max<0.00002,"Deterministic whole/band engineering regression, not a perceptual quality standard");
 eprintln!("GPU_SMOKE_PASS fixed-seed same-path repeats exact; grain-off whole/band max={deterministic_max}");
}
#[test] fn real_banded_gpu_cancel_removes_partial_after_completed_band(){
 let dir=temp();let(w,h)=(64,2049);let values=input(w,h);let source=dir.0.join("input.rgb");fs::write(&source,values.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
 let mut p=runtime();p.camera.auto_exposure=false;p.camera.film_format_mm=8.196;p.film_render.grain.micro_structure=[10.,800.];
 let stop=std::cell::Cell::new(false);let completed_band=std::cell::Cell::new(false);let partial=dir.0.join("inflight-cancel.rgb");
 let cancelled=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p,&source,&partial,&dir.0.join("scratch-cancel"),None,w,h,128,0.,true,||stop.get(),|event|{if event.phase=="gpu_tiled_chain"&&event.done>0&&event.done<event.total{completed_band.set(true);stop.set(true);}}).unwrap_err();
 assert!(completed_band.get(),"Cancellation must follow an actually completed GPU band");assert_eq!(cancelled.code,"CANCELLED");assert!(!partial.exists());assert!(source.exists());eprintln!("GPU_SMOKE_PASS in-flight cancel after completed band, owned partial removed");

}

#[test]fn actual_two_dimensional_export_has_short_tails_and_no_grain_seams(){
 let dir=temp();let(w,h)=(769,771);let source=dir.0.join("tile-input.rgb");fs::write(&source,input(w,h).iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
 let mut p=runtime();p.camera.auto_exposure=false;p.camera.pixel_size_um_override=Some(10.);p.camera.lens_blur_um=20.;p.camera.diffusion_filter.active=false;p.enlarger.diffusion_filter.active=false;p.film_render.halation.active=false;p.film_render.halation.boost_ev=0.;p.film_render.dir_couplers.active=false;p.film_render.grain.active=false;p.print_render.glare.active=false;p.film_render.glare.active=false;
 let mut images=Vec::new();let mut core=(0,0);
 for(budget,label)in[(128,"tiles"),(256,"whole"),(128,"repeat")] {
  let output=dir.0.join(format!("{label}.rgb"));let m=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p.clone(),&source,&output,&dir.0.join(format!("scratch-{label}")),None,w,h,budget,0.25,true,||false,|_|{}).unwrap();
  if !m.gpu.active{assert!(!required(),"Required actual2D export fell back: {}",m.gpu.reason);eprintln!("GPU_SMOKE_SKIPPED actual2D {}",m.gpu.reason);return;}
  assert!(m.gpu.gpu_batches>0);assert_eq!(fs::metadata(&output).unwrap().len(),w as u64*h as u64*12);
  let tile=m.gpu_tiles.as_ref().unwrap();
  if budget==128{assert_eq!(m.gpu.accelerated_stage,"tiled-chain");assert!(tile.core_width<w&&tile.core_height<h);assert_ne!(w%tile.core_width,0);assert_ne!(h%tile.core_height,0);core=(tile.core_width,tile.core_height);}else{assert_eq!(m.gpu.accelerated_stage,"full-resident-chain");}
  eprintln!("GPU_2D_EXPORT {label} core={}x{} expanded={}x{} batches={} plannedBytes={}",tile.core_width,tile.core_height,tile.expanded_width,tile.expanded_height,m.gpu.gpu_batches,m.spatial_peak_explicit_buffer_bytes);
  images.push(fs::read(output).unwrap().chunks_exact(4).map(|b|f32::from_le_bytes(b.try_into().unwrap())).collect::<Vec<_>>());
 }
 assert_eq!(images[0],images[2],"Same-path2D deterministic export must repeat exactly");
 let max=images[0].iter().zip(&images[1]).map(|(a,b)|(a-b).abs()).fold(0f32,f32::max);
 let seam=images[0].iter().zip(&images[1]).enumerate().filter(|(i,_)|{let x=(*i/3%w as usize)as u32;let y=(*i/3/w as usize)as u32;x.abs_diff(core.0)<=4||y.abs_diff(core.1)<=4}).map(|(_,(&a,&b))|(a-b).abs()).fold(0f32,f32::max);
 assert!(max<0.00002&&seam<0.00002,"No-grain2D seam regression max={max} seam={seam}");
 let stopped=std::cell::Cell::new(false);let partial=dir.0.join("tile-cancel.rgb");
 let e=streamed::export_file_checkpointed(&data(),"kodak_portra_400","kodak_portra_endura",p,&source,&partial,&dir.0.join("scratch-cancel-2d"),None,w,h,128,0.,true,||stopped.get(),|event|{if event.phase=="gpu_tiled_chain"&&event.done>0&&event.done<event.total{stopped.set(true);}}).unwrap_err();
 assert!(stopped.get());assert_eq!(e.code,"CANCELLED");assert!(!partial.exists());assert!(source.exists());
 eprintln!("GPU_SMOKE_PASS actual2D no-grain seams={seam} max={max}, short x/y tails, exact repeat, completed-tile cancellation cleanup");
}
