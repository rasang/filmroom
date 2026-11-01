//! Display-only stage-6 RGB sampling. No inversion, exposure metering or scientific re-render.
use spektrafilm_math::image::ImageBuf;
use std::{fs::{self,File,OpenOptions},io::{self,Read,Seek,SeekFrom,Write},path::{Path,PathBuf}};
pub const MAX_EDGE:u32=256;
const RESUME_READ_BUDGET:u64=16*1024*1024;
fn invalid()->io::Error{io::Error::new(io::ErrorKind::InvalidData,"Invalid scan preview")}
fn active(cancel:&mut impl FnMut()->bool)->io::Result<()>{if cancel(){Err(io::Error::new(io::ErrorKind::Interrupted,"Scan preview cancelled"))}else{Ok(())}}
fn coordinate(p:u32,s:u32,source:u32,target:u32)->u32{(((p as u64*4+s as u64*2+1)*source as u64)/(target as u64*4)).min(source as u64-1) as u32}
pub fn path(output:&Path)->Option<PathBuf>{let stem=output.file_stem()?.to_str()?;let id=stem.strip_prefix("film-result-")?;if id.len()!=36||!id.bytes().all(|b|b.is_ascii_hexdigit()||b==b'-'){return None}Some(output.parent()?.join(format!("scan-source-{id}.png")))}
pub struct Sampler {width:u32,height:u32,w:u32,h:u32,sums:Vec<[f64;3]>,rows:Vec<u8>}
impl Sampler {
 pub fn new(width:u32,height:u32)->io::Result<Self>{Self::edge(width,height,MAX_EDGE)}
 fn edge(width:u32,height:u32,edge:u32)->io::Result<Self>{
  if width==0||height==0||width>40000||height>40000||edge==0{return Err(invalid())}
  let edge=edge.min(width.max(height));let w=(width as u64*edge as u64/width.max(height) as u64).max(1) as u32;let h=(height as u64*edge as u64/width.max(height) as u64).max(1) as u32;
  Ok(Self{width,height,w,h,sums:vec![[0.;3];(w*h)as usize],rows:vec![0;h as usize]})
 }
 /// Reads only already-materialized chunk pixels; zero source file I/O.
 pub fn capture(&mut self,start:u32,image:&ImageBuf)->io::Result<()> {
  if image.width!=self.width||start+image.height>self.height||image.data.len()!=image.width as usize*image.height as usize*3{return Err(invalid())}
  for y in 0..self.h{for sy in 0..2{let iy=coordinate(y,sy,self.height,self.h);if iy<start||iy>=start+image.height{continue}
   if self.rows[y as usize]>=2{return Err(invalid())}self.rows[y as usize]+=1;
   for x in 0..self.w{for sx in 0..2{let ix=coordinate(x,sx,self.width,self.w);let off=(((iy-start)*self.width+ix)*3)as usize;
    for ch in 0..3 {let v=image.data[off+ch] as f64;if !v.is_finite(){return Err(invalid())}self.sums[(y*self.w+x)as usize][ch]+=v*0.25;}
   }}
  }}Ok(())
 }
 pub fn publish(&self,output:&Path,space:&str,exposure:f64,cancel:&mut impl FnMut()->bool)->io::Result<Option<PathBuf>>{
  active(cancel)?;let Some(target)=path(output)else{return Ok(None)};
  if self.rows.iter().any(|n|*n!=2)||!exposure.is_finite(){return Err(invalid())}
  let mut pixels=Vec::with_capacity((self.w*self.h*3)as usize);
  for row in self.sums.chunks(self.w as usize){active(cancel)?;for rgb in row {
   let rgb=spektrafilm_core::output_color::to_linear_srgb(rgb.map(|v|v*exposure),space);
   for v in rgb {if !v.is_finite(){return Err(invalid())}let v=v.clamp(0.,1.);let s=if v<=0.0031308{12.92*v}else{1.055*v.powf(1./2.4)-0.055};pixels.push((s*255.).round().clamp(0.,255.)as u8);}
  }}
  let png=crate::negative_preview::png_rgb(self.w,self.h,&pixels);
  let temp=target.with_extension("png.tmp");struct Guard(PathBuf);impl Drop for Guard{fn drop(&mut self){let _=fs::remove_file(&self.0);}}
  if temp.exists(){fs::remove_file(&temp)?}let _guard=Guard(temp.clone());let mut f=OpenOptions::new().create_new(true).write(true).open(&temp)?;f.write_all(&png)?;f.sync_all()?;drop(f);active(cancel)?;fs::rename(&temp,&target)?;Ok(Some(target))
 }
}
/// Stage-6 checkpoint contains finalized output RGB; restore needs no new science.
/// Limit row reads to16MiB by reducing thumbnail dimensions for exceptionally wide images.
pub fn publish_restored(output:&Path,width:u32,height:u32,space:&str,cancel:&mut impl FnMut()->bool)->io::Result<Option<PathBuf>>{
 if width==0||height==0||width>40000||height>40000{return Err(invalid())}
 let edge=MAX_EDGE.min((RESUME_READ_BUDGET/(width as u64*12*2)).max(1)as u32);
 let mut sampler=Sampler::edge(width,height,edge)?;let mut f=File::open(output)?;if f.metadata()?.len()!=width as u64*height as u64*12{return Err(invalid())}
 let mut row=vec![0u8;width as usize*12];
 for y in 0..sampler.h{for sy in 0..2{active(cancel)?;let iy=coordinate(y,sy,height,sampler.h);f.seek(SeekFrom::Start(iy as u64*width as u64*12))?;f.read_exact(&mut row)?;sampler.rows[y as usize]+=1;
  for x in 0..sampler.w{for sx in 0..2{let ix=coordinate(x,sx,width,sampler.w)as usize;for ch in 0..3{let i=ix*12+ch*4;let v=f32::from_le_bytes(row[i..i+4].try_into().unwrap())as f64;if !v.is_finite(){return Err(invalid())}sampler.sums[(y*sampler.w+x)as usize][ch]+=v*0.25;}}}
 }}sampler.publish(output,space,1.,cancel)
}

#[cfg(test)] mod tests {
 use super::*;use spektrafilm_math::precision::from_f64;
 struct Temp(PathBuf);impl Drop for Temp{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
 fn temp()->Temp{let p=std::env::temp_dir().join(format!("scan-preview-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));fs::create_dir(&p).unwrap();Temp(p)}
 fn output(t:&Temp)->PathBuf{t.0.join("film-result-12345678-1234-1234-1234-123456789abc.rgb")}
 #[test]fn chunk_sampling_is_read_only_and_srgb_is_encoded_once(){
  let t=temp();let out=output(&t);let image=ImageBuf::from_data(8,4,vec![from_f64(0.18);8*4*3]);let before=image.data.clone();let mut s=Sampler::new(8,4).unwrap();s.capture(0,&image).unwrap();assert_eq!(before,image.data);
  let p=s.publish(&out,"sRGB",1.,&mut||false).unwrap().unwrap();assert_eq!(fs::read(p).unwrap(),crate::negative_preview::png_rgb(8,4,&vec![118;8*4*3]));
  for space in ["Display P3","Adobe RGB (1998)"]{let p=s.publish(&out,space,1.,&mut||false).unwrap().unwrap();assert_eq!(fs::read(p).unwrap(),crate::negative_preview::png_rgb(8,4,&vec![118;8*4*3]));}
 }
 #[test]fn incomplete_or_cancelled_preview_never_replaces_publication(){
  let t=temp();let out=output(&t);let target=path(&out).unwrap();fs::write(&target,b"old-valid").unwrap();let mut s=Sampler::new(8,4).unwrap();
  assert!(s.publish(&out,"sRGB",1.,&mut||false).is_err());assert_eq!(fs::read(&target).unwrap(),b"old-valid");
  s.capture(0,&ImageBuf::from_data(8,4,vec![from_f64(0.18);96])).unwrap();let mut calls=0;
  assert!(s.publish(&out,"sRGB",1.,&mut||{calls+=1;calls>5}).is_err());assert_eq!(fs::read(&target).unwrap(),b"old-valid");assert!(!target.with_extension("png.tmp").exists());
 }
 #[test]fn resumed_output_uses_same_transfer_without_exposure_twice(){
  let t=temp();let out=output(&t);fs::write(&out,(0..96).flat_map(|_|0.18f32.to_le_bytes()).collect::<Vec<_>>()).unwrap();let p=publish_restored(&out,8,4,"sRGB",&mut||false).unwrap().unwrap();assert_eq!(fs::read(p).unwrap(),crate::negative_preview::png_rgb(8,4,&vec![118;96]));
  assert!(publish_restored(&out,0,4,"sRGB",&mut||false).is_err());
 }
 #[test]fn chunk_partition_and_four_strata_match_whole_capture(){
  let values:Vec<_>=(0..63*37*3).map(|i|from_f64((i%91)as f64/100.)).collect();let mut a=Sampler::new(63,37).unwrap();a.capture(0,&ImageBuf::from_data(63,37,values.clone())).unwrap();let mut b=Sampler::new(63,37).unwrap();
  for y in (0..37).step_by(5){let h=5.min(37-y);b.capture(y,&ImageBuf::from_data(63,h,values[(y*63*3)as usize..((y+h)*63*3)as usize].to_vec())).unwrap();}
  assert_eq!(a.sums,b.sums);assert_eq!(a.rows,b.rows);assert!(a.sums.len()<=65536);
 }
#[test]fn actual_route_output_is_bit_exact_and_preview_precedes_scanner_sharpen(){
 let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let(w,h)=(41,29);
 for (index,(film,direct)) in [("kodak_portra_400",false),("kodak_portra_400",true),("kodak_ektachrome_100",true)].into_iter().enumerate(){
  let root=std::env::temp_dir().join(format!("scan-integration-{}-{index}",std::process::id()));fs::create_dir_all(&root).unwrap();let _guard=Temp(root.clone());let input=root.join("input.rgb");fs::write(&input,(0..w*h*3).flat_map(|i|(0.02+((i*17)%91)as f32/110.).to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let mut p=spektrafilm_core::params::RuntimeParams::default();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.io.scan_film=direct;p.camera.auto_exposure=false;p.film_render.grain.active=false;p.film_render.halation.active=false;p.film_render.dir_couplers.active=false;p.print_render.glare.active=false;p.film_render.glare.active=false;p.scanner.unsharp_mask=[0.7,0.6];
  let baseline=root.join("baseline.rgb");crate::test_baseline_streamed16::export_file(&data,film,"kodak_portra_endura",p.clone(),&input,&baseline,&root.join("scratch-a"),w,h,384,0.,||false,|_,_,_|{}).unwrap();
  let actual=root.join("film-result-12345678-1234-1234-1234-123456789abc.rgb");let preview=path(&actual).unwrap();let observed=std::cell::Cell::new(false);
  crate::streamed::export_file(&data,film,"kodak_portra_endura",p,&input,&actual,&root.join("scratch-b"),w,h,384,0.,||false,|phase,_,_|{if phase=="scan_sharpen" && preview.is_file(){observed.set(true)}}).unwrap();
  assert_eq!(fs::read(&baseline).unwrap(),fs::read(&actual).unwrap(),"Presentation must not alter {film}/{direct} scientific output bytes");assert!(observed.get(),"Full-color source must exist while scanner sharpening still runs");assert!(preview.is_file());
 }
}

 #[test]fn nonneutral_output_primaries_and_half_exposure_have_independent_encoded_oracle(){
  let t=temp();let out=output(&t);let image=ImageBuf::from_data(1,1,vec![from_f64(0.2),from_f64(0.4),from_f64(0.6)]);let mut s=Sampler::new(1,1).unwrap();s.capture(0,&image).unwrap();
  // Independently calculated D65 RGB-primary matrices + IEC sRGB transfer, rounded8-bit.
  for(space,expected)in [("sRGB",[89,124,149]),("Display P3",[79,125,152]),("Adobe RGB (1998)",[69,124,150])]{let p=s.publish(&out,space,0.5,&mut||false).unwrap().unwrap();assert_eq!(fs::read(p).unwrap(),crate::negative_preview::png_rgb(1,1,&expected));}
 }
 #[test]fn stage_six_checkpoint_restore_cancel_and_optional_preview_failure_preserve_science(){
  let t=temp();let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");let(w,h)=(19,13);let input=t.0.join("input.rgb");fs::write(&input,(0..w*h*3).flat_map(|i|(0.1+(i%31)as f32/50.).to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let mut p=spektrafilm_core::params::RuntimeParams::default();p.io.input_cctf_decoding=false;p.io.output_cctf_encoding=false;p.camera.auto_exposure=false;p.film_render.grain.active=false;p.film_render.halation.active=false;p.film_render.dir_couplers.active=false;
  let cp=t.0.join("checkpoint");let first=output(&t);
  crate::streamed::export_file_checkpointed(&data,"kodak_portra_400","kodak_portra_endura",p.clone(),&input,&first,&t.0.join("scratch1"),Some(&cp),w,h,384,0.5,false,||false,|_|{}).unwrap();
  let expected=fs::read(&first).unwrap();let manifest=fs::read(cp.join("manifest.json")).unwrap();let cp_data:Vec<_>=fs::read_dir(cp.join("stage-6")).unwrap().map(|entry|{let path=entry.unwrap().path();(path.clone(),fs::read(path).unwrap())}).collect();
  let second=t.0.join("film-result-22345678-1234-1234-1234-123456789abc.rgb");let thumb=path(&second).unwrap();
  crate::streamed::export_file_checkpointed(&data,"kodak_portra_400","kodak_portra_endura",p.clone(),&input,&second,&t.0.join("scratch2"),Some(&cp),w,h,384,0.5,false,||false,|_|{}).unwrap();
  assert_eq!(expected,fs::read(&second).unwrap());assert!(thumb.is_file());
  let third=t.0.join("film-result-32345678-1234-1234-1234-123456789abc.rgb");let stop=std::cell::Cell::new(false);
  assert!(crate::streamed::export_file_checkpointed(&data,"kodak_portra_400","kodak_portra_endura",p.clone(),&input,&third,&t.0.join("scratch3"),Some(&cp),w,h,384,0.5,false,||stop.get(),|v|{if v.display_stage==6&&v.stage_done>0{stop.set(true)}}).is_err());
  assert!(!third.exists());assert!(!path(&third).unwrap().exists());
  let fourth=t.0.join("film-result-42345678-1234-1234-1234-123456789abc.rgb");fs::create_dir(path(&fourth).unwrap()).unwrap();
  crate::streamed::export_file_checkpointed(&data,"kodak_portra_400","kodak_portra_endura",p,&input,&fourth,&t.0.join("scratch4"),Some(&cp),w,h,384,0.5,false,||false,|_|{}).unwrap();
  assert_eq!(expected,fs::read(fourth).unwrap());assert_eq!(manifest,fs::read(cp.join("manifest.json")).unwrap());for(path,bytes)in cp_data{assert_eq!(bytes,fs::read(path).unwrap());}
 }
 #[test]fn native_admission_plan_reserves_exactly_three_extra_preview_mib(){
  let p=crate::mobile::plan_export(6000,4000,&crate::mobile::MobileParams::default()).unwrap();let baseline=112*1024*1024+256*32768+crate::export_execution::EXPORT_STACK_RESERVE_BYTES;
  assert_eq!(p.estimated_native_working_bytes,baseline+3*1024*1024);
 }

}
