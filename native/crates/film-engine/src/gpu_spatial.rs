//! Bounded directional f32 spool passes. No algorithm/kernel is chosen here:
//! the callback must implement the same full-support GPU convolution as the
//! resident chain. Only the convolution axis has a halo. Cropping happens
//! after the callback, so an interior tile edge is never an image boundary.
//! The export coordinator supplies budget admission and the resident endpoints.
use std::{fs::{File, OpenOptions}, io::{self,Read,Write,Seek,SeekFrom},path::{Path,PathBuf}};

#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Axis { Horizontal, Vertical }
#[derive(Clone,Copy,Debug)]
pub struct Plan { pub core_width:u32,pub core_height:u32,pub max_expanded_pixels:u64,pub expanded_pixels:u64,pub tiles:u64 }
fn invalid(s:&'static str)->io::Error {io::Error::new(io::ErrorKind::InvalidInput,s)}
fn axis_work(n:u32,core:u32,halo:u32)->u64 {(0..n).step_by(core as usize).map(|x|u64::from(x.saturating_add(core).saturating_add(halo).min(n)-x.saturating_sub(halo))).sum()}
/// The caller computes `max_pixels` from ALL live host/device/scratch buffers,
/// retained global fields, kernels and binding limits. This is NOT a new budget.
pub fn plan(width:u32,height:u32,radius:u32,axis:Axis,max_pixels:u64)->io::Result<Plan>{
 if width==0||height==0||width>40000||height>40000||max_pixels==0{return Err(invalid("Invalid spatial geometry/budget"));}
 let (hx,hy)=match axis{Axis::Horizontal=>(radius,0),Axis::Vertical=>(0,radius)};
 let mut best:Option<Plan>=None;
 for w in 1..=width {
  let ew=u64::from(w).saturating_add(2*u64::from(hx)).min(u64::from(width));
  let fit=(max_pixels/ew).min(u64::from(height));
  let h=if fit==u64::from(height){height}else{fit.saturating_sub(2*u64::from(hy)) as u32};
  if h==0{continue;}
  let eh=(u64::from(h)+2*u64::from(hy)).min(u64::from(height));
  let candidate=Plan{core_width:w,core_height:h,max_expanded_pixels:ew*eh,
    expanded_pixels:axis_work(width,w,hx)*axis_work(height,h,hy),tiles:u64::from(width.div_ceil(w))*u64::from(height.div_ceil(h))};
  if best.map_or(true,|b|(candidate.expanded_pixels,candidate.tiles)<(b.expanded_pixels,b.tiles)){best=Some(candidate);}
 }
 best.ok_or_else(||invalid("Complete convolution support does not fit budget"))
}
/// Bound shader arithmetic per submission independently of memory admission.
/// Never shrinks kernel support; only the perpendicular strip gets smaller.
/// This is a scheduling policy, not a guarantee of phone GPU completion time.
pub fn directional_pixel_cap(radius:u32,max_pixels:u64)->u64 {
    max_pixels.min(64_000_000/(2*u64::from(radius)+1))
}

struct OwnedOutput(PathBuf,bool);
impl Drop for OwnedOutput {fn drop(&mut self){if !self.1 {let _=std::fs::remove_file(&self.0);}}}
fn cancelled(check:&mut impl FnMut()->bool)->io::Result<()> {if check(){Err(io::Error::new(io::ErrorKind::Interrupted,"Spatial pass cancelled"))}else{Ok(())}}
/// Execute a scalar plane, with file bytes explicitly little endian. Callback
/// result must have identical dimensions and finite samples. At most one tile
/// is in flight; GPU completion/uncertainty belongs to the callback's backend.
/// `max_pixels` includes memory for BOTH callback input and returned output.
pub fn pass(input:&Path,output:&Path,width:u32,height:u32,radius:u32,axis:Axis,max_pixels:u64,
 check:&mut impl FnMut()->bool,
 convolve:&mut impl FnMut(&[f32],u32,u32,Axis)->io::Result<Vec<f32>>)->io::Result<Plan>{
 let p=plan(width,height,radius,axis,max_pixels)?;
 let bytes=u64::from(width)*u64::from(height)*4;
 let mut src=File::open(input)?;
 if src.metadata()?.len()!=bytes{return Err(invalid("Spatial input length mismatch"));}
 cancelled(check)?;
 let mut dst=OpenOptions::new().write(true).create_new(true).open(output)?;
 // Ownership is acquired only after successful create_new, never before it.
 let mut owner=OwnedOutput(output.to_owned(),false);
 dst.set_len(bytes)?;
 let (hx,hy)=match axis{Axis::Horizontal=>(radius,0),Axis::Vertical=>(0,radius)};
 for y in (0..height).step_by(p.core_height as usize){for x in (0..width).step_by(p.core_width as usize){
  cancelled(check)?;
  let right=x.saturating_add(p.core_width).min(width);let bottom=y.saturating_add(p.core_height).min(height);
  let left=x.saturating_sub(hx);let top=y.saturating_sub(hy);
  let ew=right.saturating_add(hx).min(width)-left;let eh=bottom.saturating_add(hy).min(height)-top;
  let mut values=vec![0f32;ew as usize*eh as usize];let mut row=vec![0u8;ew as usize*4];
  for iy in 0..eh {cancelled(check)?;src.seek(SeekFrom::Start((u64::from(top+iy)*u64::from(width)+u64::from(left))*4))?;src.read_exact(&mut row)?;
   for (v,b) in values[iy as usize*ew as usize..(iy+1) as usize*ew as usize].iter_mut().zip(row.chunks_exact(4)){*v=f32::from_le_bytes(b.try_into().unwrap());if !v.is_finite(){return Err(invalid("Nonfinite spatial input"));}}
  }
  let result=convolve(&values,ew,eh,axis)?;cancelled(check)?;
  if result.len()!=values.len()||result.iter().any(|v|!v.is_finite()){return Err(invalid("Invalid spatial callback output"));}
  for iy in y..bottom {cancelled(check)?;let start=((iy-top)*ew+x-left) as usize;let len=(right-x) as usize;
   for (v,b) in result[start..start+len].iter().zip(row[..len*4].chunks_exact_mut(4)){b.copy_from_slice(&v.to_le_bytes());}
   dst.seek(SeekFrom::Start((u64::from(iy)*u64::from(width)+u64::from(x))*4))?;dst.write_all(&row[..len*4])?;
  }
 }}
 cancelled(check)?;dst.flush()?;owner.1=true;Ok(p)
}

#[cfg(test)] mod tests {
 use super::*;
 fn convolution(v:&[f32],w:u32,h:u32,axis:Axis)->Vec<f32>{
  let mut out=vec![0.;v.len()];
  for y in 0..h {for x in 0..w {let mut sum=0.;for d in -2i32..=2 {let (xx,yy)=match axis{Axis::Horizontal=>((x as i32+d).clamp(0,w as i32-1) as u32,y),Axis::Vertical=>(x,(y as i32+d).clamp(0,h as i32-1) as u32)};sum+=v[(yy*w+xx)as usize]*0.2;}out[(y*w+x)as usize]=sum;}}out
 }
 #[test]fn directional_geometry_does_not_repeat_two_axis_halo(){
  for (w,h,r) in [(2000,3000,297),(3072,4096,405),(4000,6000,591),(6000,8000,787)] {
   for axis in [Axis::Horizontal,Axis::Vertical] {let p=plan(w,h,r,axis,directional_pixel_cap(r,1_500_000)).unwrap();assert!(p.max_expanded_pixels<=1_500_000);assert_eq!(p.expanded_pixels,u64::from(w)*u64::from(h));}
  }
 }
 #[test]fn no_truncated_support_or_invalid_budget(){assert!(plan(100,100,40,Axis::Horizontal,20).is_err());assert!(plan(0,20,1,Axis::Vertical,100).is_err());}
 #[test]fn both_axis_spools_equal_whole_image_at_seams_and_preserve_unowned_output(){
  let root=std::env::temp_dir().join(format!("gpu-spatial-test-{}",std::process::id()));std::fs::create_dir_all(&root).unwrap();
  let input=root.join("input");let horizontal=root.join("horizontal");let output=root.join("output");
  let w=31;let h=23;let data:Vec<f32>=(0..w*h).map(|i|((i*31%127)as f32-63.)/63.).collect();
  std::fs::write(&input,data.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
  pass(&input,&horizontal,w,h,2,Axis::Horizontal,61,&mut||false,&mut|v,w,h,a|Ok(convolution(v,w,h,a))).unwrap();
  pass(&horizontal,&output,w,h,2,Axis::Vertical,61,&mut||false,&mut|v,w,h,a|Ok(convolution(v,w,h,a))).unwrap();
  let expected=convolution(&convolution(&data,w,h,Axis::Horizontal),w,h,Axis::Vertical);
  let bytes=std::fs::read(&output).unwrap();let got:Vec<f32>=bytes.chunks_exact(4).map(|v|f32::from_le_bytes(v.try_into().unwrap())).collect();assert_eq!(got,expected);
  assert!(pass(&input,&output,w,h,2,Axis::Horizontal,61,&mut||false,&mut|v,_,_,_|Ok(v.to_vec())).is_err());assert_eq!(std::fs::read(&output).unwrap(),bytes);
  let cancel_path=root.join("cancel");let mut calls=0;
  assert!(pass(&input,&cancel_path,w,h,2,Axis::Horizontal,61,&mut||{calls+=1;calls>4},&mut|v,_,_,_|Ok(v.to_vec())).is_err());assert!(!cancel_path.exists());
  std::fs::remove_dir_all(root).unwrap();
 }
}

/// Logical payload I/O issued by this invocation, not filesystem/device traffic.
#[derive(Clone,Copy,Debug,Default)]
pub struct PhysicalMetrics {pub bytes_read:u64,pub bytes_written:u64,pub directional_tiles:u64,pub expanded_pixels:u64}

/// Assemble the four signed physical PSF components, preserving per-channel
/// component order, final-only clamp, and optional normalization. All images
/// remain disk-backed; each channel reuses three scalar scratch planes.
/// This routine does not perform the front/scatter or log operations.
pub fn physical(input:&Path,output:&Path,width:u32,height:u32,
 components:&[spektrafilm_gpu::HalationComponentGpu;4],scale:[f32;3],renormalize:bool,
 max_pixels:u64,check:&mut impl FnMut()->bool,
 blur:&mut impl FnMut(&[f32],u32,u32,f32,Axis,u32,u32)->io::Result<Vec<f32>>)->io::Result<PhysicalMetrics> {
 let mut metrics=PhysicalMetrics::default();
 use std::sync::atomic::{AtomicU64,Ordering};static NEXT:AtomicU64=AtomicU64::new(0);
 if width==0||height==0||width>40000||height>40000||scale.iter().any(|v|!v.is_finite()||*v<=-1.) {return Err(invalid("Invalid physical shape or scale"));}
 let count=u64::from(width)*u64::from(height);let parent=output.parent().ok_or_else(||invalid("Missing output parent"))?;
 let root=parent.join(format!(".gpu-spatial-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));
 std::fs::create_dir(&root)?;
 struct Directory(PathBuf);impl Drop for Directory{fn drop(&mut self){let _=std::fs::remove_dir_all(&self.0);}}
 let _directory=Directory(root.clone());
 let mut src=File::open(input)?;if src.metadata()?.len()!=count*12{return Err(invalid("Physical RGB input length mismatch"));}
 // Output opens only once, so errors cannot delete another writer's artifact.
 let mut dst=OpenOptions::new().create_new(true).read(true).write(true).open(output)?;
 let mut owner=OwnedOutput(output.to_owned(),false);dst.set_len(count*12)?;
 let plane=root.join("source");let mid=root.join("horizontal");let term=root.join("vertical");let acc=root.join("accumulator");
 // Keep row conversion memory explicitly bounded and independent of megapixels.
 const CHUNK:usize=4096;
 let mut rgb=vec![0u8;CHUNK*12];let mut scalar=vec![0u8;CHUNK*4];let mut accum=vec![0u8;CHUNK*4];
 for channel in 0..3 {
  cancelled(check)?;src.seek(SeekFrom::Start(0))?;
  let mut plane_file=OpenOptions::new().create_new(true).write(true).open(&plane)?;
  let mut accumulator=OpenOptions::new().create_new(true).read(true).write(true).open(&acc)?;accumulator.set_len(count*4)?;
  let mut pos=0;while pos<count{cancelled(check)?;let n=(count-pos).min(CHUNK as u64)as usize;src.read_exact(&mut rgb[..n*12])?;
   for i in 0..n {scalar[i*4..i*4+4].copy_from_slice(&rgb[i*12+channel*4..i*12+channel*4+4]);}
   plane_file.write_all(&scalar[..n*4])?;metrics.bytes_read+=n as u64*12;metrics.bytes_written+=n as u64*4;pos+=n as u64;
  }drop(plane_file);
  for (component_index,component) in components.iter().enumerate() {
   let sigma=component.sigma_px[channel];let weight=component.weight[channel];
   if !weight.is_finite(){return Err(invalid("Nonfinite physical component"));}
   let radius=spektrafilm_gpu::back_reflection_fir_radius(sigma).ok_or_else(||invalid("Invalid physical sigma"))?;
   for (from,to,axis) in [(&plane,&mid,Axis::Horizontal),(&mid,&term,Axis::Vertical)] {
    let p=pass(from,to,width,height,radius,axis,directional_pixel_cap(radius,max_pixels),check,&mut|v,w,h,a|blur(v,w,h,sigma,a,component_index as u32,channel as u32))?;
    metrics.bytes_read+=p.expanded_pixels*4;metrics.bytes_written+=count*4;
    metrics.directional_tiles+=p.tiles;metrics.expanded_pixels+=p.expanded_pixels;
   }
   let mut component_file=File::open(&term)?;accumulator.seek(SeekFrom::Start(0))?;let mut pos=0;
   while pos<count {cancelled(check)?;let n=(count-pos).min(CHUNK as u64)as usize;component_file.read_exact(&mut scalar[..n*4])?;accumulator.read_exact(&mut accum[..n*4])?;
    for (a,b) in accum[..n*4].chunks_exact_mut(4).zip(scalar[..n*4].chunks_exact(4)) {
     let prev=f32::from_le_bytes(a.try_into().unwrap());let value=f32::from_le_bytes(b.try_into().unwrap());
     let next=prev+weight*value;if !next.is_finite(){return Err(invalid("Nonfinite physical accumulator"));}a.copy_from_slice(&next.to_le_bytes());
    }
    accumulator.seek(SeekFrom::Start(pos*4))?;accumulator.write_all(&accum[..n*4])?;metrics.bytes_read+=n as u64*8;metrics.bytes_written+=n as u64*4;pos+=n as u64;
   }
   drop(component_file);std::fs::remove_file(&mid)?;std::fs::remove_file(&term)?;
  }
  src.seek(SeekFrom::Start(0))?;accumulator.seek(SeekFrom::Start(0))?;dst.seek(SeekFrom::Start(0))?;let mut pos=0;
  while pos<count {cancelled(check)?;let n=(count-pos).min(CHUNK as u64)as usize;
   if channel==0{src.read_exact(&mut rgb[..n*12])?;}else{dst.read_exact(&mut rgb[..n*12])?;}
   accumulator.read_exact(&mut accum[..n*4])?;
   for i in 0..n {let slot=&mut rgb[i*12+channel*4..i*12+channel*4+4];let original=f32::from_le_bytes(slot.try_into().unwrap());let sum=f32::from_le_bytes(accum[i*4..i*4+4].try_into().unwrap());
    let mut value=original+scale[channel]*sum.max(0.);if renormalize{value*=1./(1.+scale[channel]);}if !value.is_finite(){return Err(invalid("Nonfinite physical output"));}slot.copy_from_slice(&value.to_le_bytes());
   }
   dst.seek(SeekFrom::Start(pos*12))?;dst.write_all(&rgb[..n*12])?;metrics.bytes_read+=n as u64*16;metrics.bytes_written+=n as u64*12;pos+=n as u64;
  }
  drop(accumulator);std::fs::remove_file(&plane)?;std::fs::remove_file(&acc)?;
 }
 cancelled(check)?;dst.flush()?;owner.1=true;Ok(metrics)
}

#[cfg(test)]mod physical_tests{
 use super::*;
 #[test]fn signed_components_accumulate_before_clamp_and_renormalize(){
  let root=std::env::temp_dir().join(format!("gpu-physical-test-{}",std::process::id()));std::fs::create_dir_all(&root).unwrap();
  let input=root.join("input");let output=root.join("output");let values:Vec<f32>=(0..7*9*3).map(|i|i as f32*0.01).collect();
  std::fs::write(&input,values.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let components=[1.,-2.,3.,-1.].map(|weight|spektrafilm_gpu::HalationComponentGpu{weight:[weight;3],sigma_px:[1.;3]});
  let metrics=physical(&input,&output,7,9,&components,[0.2,0.3,0.4],true,25,&mut||false,&mut|v,_,_,_,_,_,_|Ok(v.to_vec())).unwrap();
  assert_eq!(metrics.bytes_read,7*9*276);assert_eq!(metrics.bytes_written,7*9*192);assert_eq!(metrics.expanded_pixels,7*9*24);
  let bytes=std::fs::read(&output).unwrap();for (i,b) in bytes.chunks_exact(4).enumerate(){let got=f32::from_le_bytes(b.try_into().unwrap());assert!((got-values[i]).abs()<1e-6,"{i}: {got} != {}",values[i]);}
  assert_eq!(std::fs::read_dir(&root).unwrap().count(),2);
  let failing=root.join("failing");let mut calls=0;
  let result=physical(&input,&failing,7,9,&components,[0.2,0.3,0.4],true,25,&mut||false,
    &mut|v,_,_,_,_,_,_|{calls+=1;if calls>2{Err(invalid("injected backend failure"))}else{Ok(v.to_vec())}});
  assert!(result.is_err());assert!(!failing.exists());assert_eq!(std::fs::read_dir(&root).unwrap().count(),2);
  assert!(physical(&input,&output,7,9,&components,[0.2,0.3,0.4],true,25,&mut||false,&mut|v,_,_,_,_,_,_|Ok(v.to_vec())).is_err());
  assert_eq!(std::fs::read(&output).unwrap(),bytes);assert_eq!(std::fs::read_dir(&root).unwrap().count(),2);
  std::fs::remove_dir_all(root).unwrap();
 }
}

#[cfg(test)]mod gpu_tests {
 use super::*;
 #[test] #[ignore="requires real Vulkan adapter"]
 fn gpu_directional_spools_match_whole_axis_including_edges(){
  let gpu=spektrafilm_gpu::wgpu_backend::WgpuBackend::new().expect("Vulkan adapter required");
  let root=std::env::temp_dir().join(format!("gpu-spatial-real-{}",std::process::id()));std::fs::create_dir_all(&root).unwrap();
  let input=root.join("input");let mid=root.join("mid");let output=root.join("output");let(w,h)=(31,23);
  let data:Vec<f32>=(0..w*h).map(|i|((i*31%127)as f32-63.)/63.).collect();
  std::fs::write(&input,data.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
  for sigma in [0.7,2.9,3.,5.] {
   let radius=spektrafilm_gpu::back_reflection_fir_radius(sigma).unwrap();
   let expected_h=gpu.physical_blur_axis(&data,w,h,sigma,true,1<<20,&mut||true).unwrap();
   let expected=gpu.physical_blur_axis(&expected_h,w,h,sigma,false,1<<20,&mut||true).unwrap();
   let mut callback=|v:&[f32],w,h,a|gpu.physical_blur_axis(v,w,h,sigma,a==Axis::Horizontal,1<<20,&mut||true).ok_or_else(||invalid("GPU failed"));
   pass(&input,&mid,w,h,radius,Axis::Horizontal,93,&mut||false,&mut callback).unwrap();
   pass(&mid,&output,w,h,radius,Axis::Vertical,93,&mut||false,&mut callback).unwrap();
   let bytes=std::fs::read(&output).unwrap();let got:Vec<f32>=bytes.chunks_exact(4).map(|v|f32::from_le_bytes(v.try_into().unwrap())).collect();
   assert_eq!(got,expected,"sigma={sigma}");std::fs::remove_file(&mid).unwrap();std::fs::remove_file(&output).unwrap();
  }
  std::fs::remove_dir_all(root).unwrap();
 }
}
