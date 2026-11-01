//! Actual public mobile APIs, not manually matched test-only Pipeline settings.
//! HDR input remains linear until physical processing; no Bitmap transfer here.
use film_engine::mobile::{self,MobileParams};
use std::{fs,path::{Path,PathBuf},sync::atomic::{AtomicU64,Ordering}};
static NEXT:AtomicU64=AtomicU64::new(0);
struct Temp(PathBuf);impl Drop for Temp{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
fn copy(src:&Path,dst:&Path){fs::create_dir_all(dst).unwrap();for e in fs::read_dir(src).unwrap(){let e=e.unwrap();let to=dst.join(e.file_name());if e.file_type().unwrap().is_dir(){copy(&e.path(),&to)}else{fs::copy(e.path(),to).unwrap();}}}
fn setup()->(Temp,i64){let root=std::env::temp_dir().join(format!("mobile-hdr-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));fs::create_dir(&root).unwrap();fs::create_dir(root.join("cache")).unwrap();let data=root.join("files/engine-data");copy(&Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data"),&data);let id=mobile::create(data.to_str().unwrap()).unwrap();(Temp(root),id)}
fn pixels(w:u32,h:u32)->Vec<f32>{(0..w*h).flat_map(|i|{let x=i%w;let y=i/w;let base=0.006+(x as f32/w as f32)*0.24;let high=if x>w/3&&x<w/2&&y>h/4&&y<3*h/4{5.5}else{base};[high,high*0.8,if i%113==0{-0.002}else{high*0.6}]}).collect()}
fn read(p:&Path)->Vec<f32>{fs::read(p).unwrap().chunks_exact(4).map(|v|f32::from_le_bytes(v.try_into().unwrap())).collect()}
#[test]fn actual_mobile_hdr_preview_and_export_match_for_ae_and_output_spaces(){
 let(root,id)=setup();let engine=mobile::get(id).unwrap();let(w,h)=(129u32,97u32);let values=pixels(w,h);assert!(values.iter().any(|&v|v>1.)&&values.iter().any(|&v|v<0.));let input=root.0.join("cache/input.f32");let bytes:Vec<u8>=values.iter().flat_map(|v|v.to_le_bytes()).collect();fs::write(&input,&bytes).unwrap();
 for (index,(space,ae)) in ["sRGB","Display P3","Adobe RGB (1998)"].into_iter().flat_map(|s|[false,true].into_iter().map(move|ae|(s,ae))).enumerate(){
  let mut params=MobileParams::default();params.use_gpu=false;params.grain_amount=0.;params.auto_exposure=ae;params.metering="partial".into();params.output_color_space=space.into();params.output_gamut=space=="sRGB";
  let preview=mobile::render(&engine,0,&values,w,h,&params).unwrap();let output=root.0.join(format!("cache/out{index}.f32"));let metrics=mobile::export(&engine,0,input.to_str().unwrap(),output.to_str().unwrap(),w as i32,h as i32,&params).unwrap();
  assert_eq!(preview.metered_ev,metrics.metered_ev);assert_eq!(preview.metered_ev.is_some(),ae);
  let exported=read(&output);let expected:Vec<f32>=exported.chunks_exact(3).flat_map(|v|spektrafilm_core::output_color::to_linear_srgb([v[0]as f64,v[1]as f64,v[2]as f64],space).map(|x|x as f32)).collect();
  let max=preview.pixels.iter().zip(&expected).map(|(a,b)|(a-b).abs()).fold(0f32,f32::max);assert!(max<2e-6,"space={space} ae={ae} max={max}");assert!(preview.pixels.iter().all(|v|v.is_finite()));
 }
 assert_eq!(fs::read(input).unwrap(),bytes);assert!(fs::read_dir(root.0.join("cache")).unwrap().all(|e|!e.unwrap().file_name().to_string_lossy().starts_with("film-native-")));mobile::destroy(id);
}
#[test]fn hdr_clipping_reproduces_exposure_fault_and_float_input_avoids_it(){
 let(root,id)=setup();let engine=mobile::get(id).unwrap();let(w,h)=(129,97);let values=pixels(w,h);let clipped:Vec<f32>=values.iter().map(|v|v.clamp(0.,1.)).collect();let mut p=MobileParams::default();p.use_gpu=false;p.grain_amount=0.;p.auto_exposure=true;p.metering="partial".into();
 let linear=mobile::render(&engine,0,&values,w,h,&p).unwrap();let bitmap=mobile::render(&engine,0,&clipped,w,h,&p).unwrap();assert!(bitmap.metered_ev.unwrap()-linear.metered_ev.unwrap()>0.5,"fixture must detect legacy HDR clipping exposure fault");
 assert!(bitmap.pixels.iter().sum::<f32>()>linear.pixels.iter().sum::<f32>()*1.1);mobile::destroy(id);drop(root);
}
