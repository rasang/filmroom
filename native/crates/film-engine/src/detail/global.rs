//! Preview-owned exact pre-grain context. Numerical helpers are copied from
//! streamed.rs (same GPL license); export code and checkpoint lifecycle unchanged.
//! Input f32 RGB, retained scientific planes f64, full-image FIR/IIR, bounded IO.
use std::{fs::{self,File,OpenOptions},io::{Read,Write,Seek,SeekFrom},path::{Path,PathBuf},time::Instant};
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline};
use spektrafilm_gpu::{ComputeBackend,cpu_backend::CpuBackend};
use spektrafilm_math::{image::ImageBuf,precision::{Scalar,from_f64,from_f32}};
use crate::{mobile::{EngineError,Result},disk_spatial};
use crate::streamed_progress::ProgressSink;
use super::{Rect,DetailError,CancellationToken};
const SPATIAL_MEMORY:usize=16*1024*1024;
const BLOCK_PIXELS:usize=32768;
struct SilentProgress;
impl ProgressSink for SilentProgress {
 fn begin_stage(&mut self,_:u8,_:u64,_:&'static str){} fn finish_stage(&mut self){}
 fn pass_fraction(&mut self,_:&'static str,_:u64,_:u64,_:u64,_:u64){}
 fn begin_work(&mut self,_:&'static str){} fn end_work(&mut self){}
}
fn error(code:&'static str,message:impl Into<String>)->EngineError {EngineError::new(code,message)}
fn io(e:std::io::Error)->EngineError {error(if e.kind()==std::io::ErrorKind::Interrupted {"CANCELLED"} else {"IO_ERROR"},e.to_string())}
fn cancel(c:&mut impl FnMut()->bool)->Result<()> {if c(){Err(error("CANCELLED","Preview context cancelled"))}else{Ok(())}}
fn new_file(p:&Path)->Result<File> {OpenOptions::new().write(true).create_new(true).open(p).map_err(io)}
fn read64(f:&mut File,n:usize)->Result<Vec<f64>> {let mut b=vec![0u8;n*8];f.read_exact(&mut b).map_err(io)?;let mut v=Vec::with_capacity(n);for x in b.chunks_exact(8){let a=f64::from_le_bytes(x.try_into().unwrap());if !a.is_finite(){return Err(error("INVALID_PIXELS","Nonfinite intermediate"));}v.push(a);}Ok(v)}
fn write64(f:&mut File,v:&[f64])->Result<()> {let mut b=Vec::with_capacity(v.len()*8);for &x in v {if !x.is_finite(){return Err(error("NONFINITE_OUTPUT","Nonfinite intermediate"));}b.extend_from_slice(&x.to_le_bytes());}f.write_all(&b).map_err(io)}
struct Scratch {dir:PathBuf,seq:u64,peak:u64,spatial_buffer:u64,read:u64,written:u64,passes:u64}
impl Scratch {
 fn new(dir:&Path)->Result<Self>{fs::create_dir(dir).map_err(io)?;Ok(Self{dir:dir.to_path_buf(),seq:0,peak:0,spatial_buffer:0,read:0,written:0,passes:0})}
 fn path(&mut self,label:&str)->PathBuf {self.seq+=1;self.dir.join(format!("stage-{:05}-{label}.f64le",self.seq))}
 fn live(&self)->Result<u64>{let mut n=0u64;for e in fs::read_dir(&self.dir).map_err(io)? {let e=e.map_err(io)?;let m=e.metadata().map_err(io)?;if m.is_file(){n=n.checked_add(m.len()).ok_or_else(||error("INVALID_DIMENSIONS","Scratch size overflow"))?;}}Ok(n)}
 fn observe(&mut self)->Result<()> {self.peak=self.peak.max(self.live()?);Ok(())}
 fn remove(&self,p:&Path)->Result<()> {fs::remove_file(p).map_err(io)}
 fn blur(&mut self,input:&Path,w:u32,h:u32,sigma:f32,stage:&'static str,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<PathBuf>{
   p.begin_work(stage);cancel(c)?;let out=self.path("gaussian");let before=self.live()?;
   let m=disk_spatial::gaussian_file(input,&out,w,h,sigma,&self.dir,SPATIAL_MEMORY,c,&mut |v|{let (done,total)=if v.pass=="identity" {(v.completed*w as u64,w as u64*h as u64)}else if v.pass=="horizontal" {(v.completed*w as u64,2*w as u64*h as u64)}else{(w as u64*h as u64+v.completed*h as u64,2*w as u64*h as u64)};p.pass_fraction(stage,v.completed,v.total,done,total)}).map_err(io)?;
   // gaussian_file sets full logical lengths for output and temporary at once;
   // this counts their actual live logical bytes, not RSS/filesystem cache.
   self.peak=self.peak.max(before+m.output_bytes+m.scratch_bytes);self.spatial_buffer=self.spatial_buffer.max(m.peak_buffer_bytes);self.read+=m.bytes_read;self.written+=m.bytes_written;self.passes+=1;p.end_work();Ok(out)
 }
 fn exponential(&mut self,input:&Path,w:u32,h:u32,decay:f32,stage:&'static str,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<PathBuf>{
   let n=w as u64*h as u64;let accum=self.path("exponential");let f=new_file(&accum)?;f.set_len(n*8).map_err(io)?;drop(f);self.observe()?;
   for (amp,ratio) in [(0.1633f64,0.5360f64),(0.6496,1.5236),(0.1870,2.7684)] {
     let component=self.blur(input,w,h,(ratio*decay as f64) as f32,stage,c,p)?;
     inplace_with(&accum,&[component.as_path()],n,c,|a,src|a+amp*src[0])?;self.remove(&component)?;
   } Ok(accum)
 }
}
impl Drop for Scratch {fn drop(&mut self){let _=fs::remove_dir_all(&self.dir);}}
fn inplace_with(path:&Path,others:&[&Path],n:u64,c:&mut impl FnMut()->bool,op:impl Fn(f64,&[f64])->f64)->Result<()> {
 let mut f=OpenOptions::new().read(true).write(true).open(path).map_err(io)?;let mut sources=others.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;
 let mut at=0;while at<n {cancel(c)?;let len=(n-at).min(BLOCK_PIXELS as u64) as usize;let mut a=read64(&mut f,len)?;let mut v=Vec::new();for src in &mut sources {v.push(read64(src,len)?);}let mut values=vec![0.;v.len()];for i in 0..len {for(k,s)in v.iter().enumerate(){values[k]=s[i];}a[i]=op(a[i],&values);}f.seek(SeekFrom::Start(at*8)).map_err(io)?;write64(&mut f,&a)?;at+=len as u64;}Ok(())
}
fn combine_new(path:&Path,inputs:&[&Path],n:u64,c:&mut impl FnMut()->bool,op:impl Fn(&[f64])->f64)->Result<()> {
 let mut out=new_file(path)?;let mut ins=inputs.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;let mut at=0;while at<n {cancel(c)?;let len=(n-at).min(BLOCK_PIXELS as u64) as usize;let mut v=Vec::new();for f in &mut ins {v.push(read64(f,len)?);}let mut values=vec![0.;v.len()];let mut z=vec![0.;len];for i in 0..len{for(k,s)in v.iter().enumerate(){values[k]=s[i];}z[i]=op(&values);}write64(&mut out,&z)?;at+=len as u64;}Ok(())
}
fn rows_per_chunk(width:u32)->u32 {(BLOCK_PIXELS/(width as usize)).max(1) as u32}
fn image_from_planes(v:&[Vec<f64>],w:u32,h:u32)->ImageBuf {let mut a=Vec::with_capacity(w as usize*h as usize*3);for i in 0..v[0].len(){for ch in 0..3 {a.push(from_f64(v[ch][i]));}}ImageBuf::from_data(w,h,a)}
fn write_image_planes(files:&mut [File],img:&ImageBuf)->Result<()> {for ch in 0..3 {let v:Vec<f64>=img.data.chunks_exact(3).map(|p|p[ch] as f64).collect();write64(&mut files[ch],&v)?;}Ok(())}


fn expose(input:&Path,pipe:&Pipeline,w:u32,h:u32,s:&mut Scratch,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<([PathBuf;3],Option<f32>)> {
 let paths=std::array::from_fn(|_|s.path("film-raw"));let mut out=paths.iter().map(|p|new_file(p)).collect::<Result<Vec<_>>>()?;let mut f=File::open(input).map_err(io)?;let rows=rows_per_chunk(w);let lut=pipe.tc_lut().ok_or_else(||error("UNSUPPORTED_EXPORT_OPTIONS","Streamed exposure requires Hanatos spectral upsampling"))?;
 let exposure=from_f32(2.0f32.powf(pipe.params.camera.exposure_compensation_ev));
 let metered_ev=if !pipe.params.camera.auto_exposure {None}else if let Some(ev)=pipe.params.camera.preview_metered_ev {Some(ev)}else {p.begin_work("auto_exposure");p.pass("auto_exposure",0,1);let matrix=spektrafilm_core::stages::filming::input_colorspace_to_xyz(&pipe.params.io.input_color_space);let ev=crate::streamed_global::auto_exposure_ev(input,w,h,&pipe.params.camera.auto_exposure_method,&matrix,&mut *c).map_err(io)?;p.pass("auto_exposure",1,1);p.end_work();Some(ev)};
 let ae=metered_ev.map(|ev|from_f32(2.0f32.powf(ev))).unwrap_or(from_f64(1.));
 p.begin_work("film_exposure");for y in (0..h).step_by(rows as usize){cancel(c)?;let hh=rows.min(h-y);let mut bytes=vec![0u8;w as usize*hh as usize*12];f.read_exact(&mut bytes).map_err(io)?;let mut v=Vec::with_capacity(bytes.len()/4);for b in bytes.chunks_exact(4){let x=f32::from_le_bytes(b.try_into().unwrap());if !x.is_finite(){return Err(error("INVALID_PIXELS","Nonfinite input spool"));}v.push((from_f64(x as f64)*ae)*exposure);}let rgb=ImageBuf::from_data(w,hh,v);let raw=CpuBackend.hanatos2025_rgb_to_raw(&rgb,lut,&pipe.params.io.input_color_space,pipe.front_illuminant_spd(),pipe.params.settings.use_cat16);write_image_planes(&mut out,&raw)?;p.pass("film_exposure",(y+hh) as u64,h as u64);}
 drop(out);s.observe()?;p.end_work();Ok((paths,metered_ev))
}
fn halation(paths:&mut [PathBuf;3],params:&RuntimeParams,pitch:f32,w:u32,h:u32,s:&mut Scratch,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<()> {
 let v=&params.film_render.halation;if !v.active{return Ok(());}let n=w as u64*h as u64;
 for ch in 0..3 {if v.scatter_amount>0. {
   let sigma=v.scatter_core_um[ch]*v.scatter_spatial_scale/pitch as f64;let decay=v.scatter_tail_um[ch]*v.scatter_spatial_scale/pitch as f64;
   if sigma>0.||decay>0. {let core=s.blur(&paths[ch],w,h,sigma.max(1e-6) as f32,"film_scatter",c,p)?;let tail=s.exponential(&paths[ch],w,h,decay.max(1e-6) as f32,"film_scatter",c,p)?;let sa=v.scatter_amount;let wt=v.scatter_tail_weight[ch];inplace_with(&paths[ch],&[&core,&tail],n,c,|a,x|(1.-sa)*a+sa*((1.-wt)*x[0]+wt*x[1]))?;s.remove(&core)?;s.remove(&tail)?;}
 }}
 let strengths=v.halation_strength.map(|a|a*v.halation_amount);
 if v.halation_n_bounces>=1&&strengths.iter().any(|&a|a>0.) {let mut weights:Vec<f64>=(0..v.halation_n_bounces).map(|k|v.halation_bounce_decay.powi(k as i32)).collect();let sum:f64=weights.iter().sum();for x in &mut weights{*x/=sum;}
  for ch in 0..3 {let first=v.halation_first_sigma_um[ch]*v.halation_spatial_scale/pitch as f64;if strengths[ch]==0.||first<=0.{continue;}let acc=s.path("halation-sum");let f=new_file(&acc)?;f.set_len(n*8).map_err(io)?;drop(f);s.observe()?;
   for(k,&weight)in weights.iter().enumerate(){let blur=s.blur(&paths[ch],w,h,(first*((k+1)as f64).sqrt()).max(1e-6) as f32,"film_halation",c,p)?;inplace_with(&acc,&[&blur],n,c,|a,x|a+weight*x[0])?;s.remove(&blur)?;}
   inplace_with(&paths[ch],&[&acc],n,c,|a,x|a+strengths[ch]*x[0])?;s.remove(&acc)?;
  }
  if v.halation_renormalize {for ch in 0..3 {inplace_with(&paths[ch],&[],n,c,|a,_|a/(1.+strengths[ch]))?;}}
 } Ok(())
}
fn density_initial(logs:&[PathBuf;3],pipe:&Pipeline,backend:&dyn ComputeBackend,w:u32,h:u32,s:&mut Scratch,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<[PathBuf;3]> {
 let paths=std::array::from_fn(|_|s.path("film-density"));let mut out=paths.iter().map(|p|new_file(p)).collect::<Result<Vec<_>>>()?;let mut ins=logs.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;let axis=pipe.film.log_exposure_f64();let curves=spektrafilm_model::density_curves::normalize_density_curves_f64(&pipe.film.density_curves_f64());let rows=rows_per_chunk(w);
 p.begin_work("film_density");for y in (0..h).step_by(rows as usize){cancel(c)?;let hh=rows.min(h-y);let mut v=Vec::new();for f in &mut ins{v.push(read64(f,w as usize*hh as usize)?);}let img=image_from_planes(&v,w,hh);let z=backend.density_curve_interp(&img,&axis,&curves,pipe.params.film_render.density_curve_gamma as f64);write_image_planes(&mut out,&z)?;p.pass("film_density",(y+hh)as u64,h as u64);}
 drop(out);s.observe()?;p.end_work();Ok(paths)
}
fn dir_density(logs:[PathBuf;3],density:[PathBuf;3],pipe:&Pipeline,backend:&dyn ComputeBackend,pitch:f32,w:u32,h:u32,s:&mut Scratch,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<[PathBuf;3]> {
 let d=&pipe.params.film_render.dir_couplers;let n=w as u64*h as u64;if !d.active {for q in logs{s.remove(&q)?;}return Ok(density);}
 let mut matrix=spektrafilm_model::couplers::compute_dir_couplers_matrix(d.gamma_samelayer_rgb,d.gamma_interlayer_r_to_gb,d.gamma_interlayer_g_to_rb,d.gamma_interlayer_b_to_rg,d.inhibition_samelayer,d.inhibition_interlayer);for row in &mut matrix{for x in row{*x*=d.amount;}}
 let rawcurves=pipe.film.density_curves_f32();let norm=spektrafilm_model::density_curves::normalize_density_curves(&rawcurves);let le=pipe.film.log_exposure_f32();let curves0=spektrafilm_model::couplers::compute_curves_before_dir(&norm,&le,&matrix.map(|r|r.map(|v|v as f32)),pipe.film.is_positive());let axis:Vec<f64>=le.iter().map(|&x|x as f64).collect();let curves:Vec<[f64;3]>=curves0.iter().map(|r|r.map(|x|x as f64)).collect();let positive=pipe.film.is_positive();let dmax=spektrafilm_model::density_curves::max_density(&norm).map(|v|v as f64);let mut result:Vec<PathBuf>=Vec::new();
 for ch in 0..3 {let corr=s.path("dir-correction");combine_new(&corr,&[&density[0],&density[1],&density[2]],n,c,|x|if positive{(dmax[0]-x[0])*matrix[0][ch]+(dmax[1]-x[1])*matrix[1][ch]+(dmax[2]-x[2])*matrix[2][ch]}else{x[0]*matrix[0][ch]+x[1]*matrix[1][ch]+x[2]*matrix[2][ch]})?;s.observe()?;
  if d.diffusion_size_um>0. {let core=s.blur(&corr,w,h,(d.diffusion_size_um/pitch as f64)as f32,"dir_diffusion",c,p)?;let tail=s.exponential(&corr,w,h,(d.diffusion_tail_um/pitch as f64)as f32,"dir_diffusion",c,p)?;let wt=d.diffusion_tail_weight;inplace_with(&corr,&[&core,&tail],n,c,|_,x|(1.-wt)*x[0]+wt*x[1])?;s.remove(&core)?;s.remove(&tail)?;}
  let output=s.path("dir-density");let mut out=new_file(&output)?;let mut log=File::open(&logs[ch]).map_err(io)?;let mut correction=File::open(&corr).map_err(io)?;let rows=rows_per_chunk(w);
  p.begin_work("dir_develop");for y in(0..h).step_by(rows as usize){cancel(c)?;let hh=rows.min(h-y);let len=w as usize*hh as usize;let mut v=read64(&mut log,len)?;let correction=read64(&mut correction,len)?;for(a,b)in v.iter_mut().zip(correction){*a-=b;}let data:Vec<Scalar>=v.iter().flat_map(|&x|[from_f64(x);3]).collect();let z=backend.density_curve_interp(&ImageBuf::from_data(w,hh,data),&axis,&curves,pipe.params.film_render.density_curve_gamma as f64);let values:Vec<f64>=z.data.chunks_exact(3).map(|a|a[ch]as f64).collect();write64(&mut out,&values)?;p.pass("dir_develop",(y+hh)as u64,h as u64);}
  drop(out);s.observe()?;p.end_work();s.remove(&corr)?;s.remove(&logs[ch])?;result.push(output);
 }
 for q in density.iter(){s.remove(q)?;}Ok(result.try_into().unwrap())
}

/// Numerical work and owned logical disk usage, not a process RSS estimate.
#[derive(Debug,Clone)]
pub struct PregrainMetrics {
 pub elapsed_ms:f64,
 pub peak_logical_scratch_bytes:u64,
 pub spatial_peak_buffer_bytes:u64,
 pub spatial_bytes_read:u64,
 pub spatial_bytes_written:u64,
}
/// Immutable f64 planes. Dropping the final owner deletes only the directory
/// created by this builder. No export checkpoint/job paths are consulted.
pub struct PregrainPlanes {
 planes:[PathBuf;3],width:u32,height:u32,_scratch:Scratch,
 pub metered_ev:Option<f32>,pub pitch:f32,pub metrics:PregrainMetrics,
}
fn detail_error(e:EngineError)->DetailError {
 match e.code {
  "CANCELLED"=>DetailError::Cancelled,
  "INVALID_DIMENSIONS"|"INVALID_INPUT_FILE"=>DetailError::InvalidGeometry(e.message),
  "UNSUPPORTED_EXPORT_OPTIONS"|"UNSUPPORTED_DETAIL_OPTIONS"=>DetailError::Unsupported(e.message),
  _=>DetailError::Processing(format!("{}: {}",e.code,e.message)),
 }
}
impl PregrainPlanes {
 pub fn disk_bytes(&self)->u64{self.width as u64*self.height as u64*24}
 pub fn read_rect(&self,rect:Rect,cancel:&CancellationToken)->std::result::Result<ImageBuf,DetailError>{
  cancel.check()?;
  if !rect.valid(self.width as usize,self.height as usize){return Err(DetailError::InvalidGeometry("Pre-grain read is outside full virtual image".into()));}
  let count=rect.w.checked_mul(rect.h).ok_or_else(||DetailError::InvalidGeometry("Read size overflow".into()))?;
  const MAX_READ_PIXELS:usize=1_048_576;
  if count>MAX_READ_PIXELS{return Err(DetailError::BudgetExceeded{required:count.saturating_mul(24),budget:MAX_READ_PIXELS*24});}
  let mut out=ImageBuf::new(rect.w as u32,rect.h as u32);
  let mut input=self.planes.iter().map(File::open).collect::<std::io::Result<Vec<_>>>()?;
  for y in 0..rect.h {cancel.check()?;for(ch,f)in input.iter_mut().enumerate(){
   f.seek(SeekFrom::Start((((rect.y+y)*self.width as usize+rect.x)*8)as u64))?;
   let values=read64(f,rect.w).map_err(detail_error)?;
   for(x,value)in values.into_iter().enumerate(){out.data[(y*rect.w+x)*3+ch]=from_f64(value);}
  }}
  cancel.check()?;Ok(out)
 }
}

/// Convenience wrapper. Existing calibration construction is bounded but not
/// internally cancellable; token checks surround it. Call the correction-taking
/// variant when a prepared printing context already exists to avoid duplication.
pub fn build_pregrain(pipe:&Pipeline,input:&Path,width:u32,height:u32,scratch_dir:&Path,token:&CancellationToken)->std::result::Result<PregrainPlanes,DetailError>{
 token.check()?;let prepared=pipe.prepare_streamed_finish_spatial(&CpuBackend).map_err(DetailError::Processing)?;token.check()?;
 build_pregrain_with_correction(pipe,input,width,height,scratch_dir,prepared.filming_exposure_correction,token)
}
pub fn build_pregrain_with_correction(pipe:&Pipeline,input:&Path,width:u32,height:u32,scratch_dir:&Path,correction:f64,token:&CancellationToken)->std::result::Result<PregrainPlanes,DetailError>{
 token.check()?;
 build_inner(pipe,input,width,height,scratch_dir,correction,&mut ||token.cancelled()).map_err(detail_error)
}
fn build_inner(pipe:&Pipeline,input:&Path,width:u32,height:u32,scratch_dir:&Path,correction:f64,cancelled:&mut impl FnMut()->bool)->Result<PregrainPlanes>{
 cancel(cancelled)?;
 if width==0||height==0||width>40000||height>40000{return Err(error("INVALID_DIMENSIONS","Invalid virtual context dimensions"));}
 if pipe.params.io.crop||pipe.params.io.upscale_factor!=1.{return Err(error("UNSUPPORTED_DETAIL_OPTIONS","Context requires already-oriented explicit working-grid input"));}
 let pitch=pipe.params.camera.pixel_size_um_override.unwrap_or(pipe.params.camera.film_format_mm*1000./width.max(height)as f32);
 if !pitch.is_finite()||pitch<=0.||!correction.is_finite(){return Err(error("INVALID_PARAMETERS","Invalid physical pitch/calibration correction"));}
 let n=width as u64*height as u64;
 if fs::metadata(input).map_err(io)?.len()!=n*12{return Err(error("INVALID_INPUT_FILE","Expected full-grid f32 RGB spool"));}
 let started=Instant::now();let mut scratch=Scratch::new(scratch_dir)?;let mut progress=SilentProgress;
 let (mut logs,metered_ev)=expose(input,pipe,width,height,&mut scratch,cancelled,&mut progress)?;
 let boost=&pipe.params.film_render.halation;
 if boost.boost_ev>0.{crate::streamed_global::boost_planes(&logs,n,boost.boost_ev as f64,boost.boost_range as f64,boost.protect_ev as f64,&mut *cancelled,|_,_|{}).map_err(io)?;}
 if pipe.params.camera.diffusion_filter.active{
  let d=&pipe.params.camera.diffusion_filter;
  if !["glimmerglass","black_pro_mist","pro_mist","cinebloom"].contains(&d.filter_family.as_str())||!d.strength.is_finite()||!(0.0..=2.0).contains(&d.strength)||!d.spatial_scale.is_finite()||!(0.25..=2.0).contains(&d.spatial_scale){return Err(error("INVALID_PARAMETERS","Unsupported camera diffusion controls"));}
  if let Some(kernel)=spektrafilm_model::diffusion::ExactDiffusionKernel::new_cancellable(&d.to_model(),pitch as f64,width as usize,height as usize,&mut *cancelled).map_err(|_|error("CANCELLED","Camera kernel cancelled"))?{
   for ch in 0..3{cancel(cancelled)?;let transformed=scratch.path("camera-diffusion");let work=scratch.path("camera-fft-work");let before=scratch.live()?;
    let m=crate::disk_diffusion::apply_plane(&logs[ch],&transformed,&work,width,height,&kernel,ch,&mut *cancelled,|_,_|{}).map_err(io)?;
    scratch.peak=scratch.peak.max(before+m.peak_scratch_bytes);scratch.spatial_buffer=scratch.spatial_buffer.max(m.explicit_buffer_bytes);scratch.remove(&logs[ch])?;logs[ch]=transformed;
   }
  }
 }
 if pipe.params.camera.lens_blur_um>0.{for ch in 0..3{let out=scratch.blur(&logs[ch],width,height,pipe.params.camera.lens_blur_um/pitch,"camera_blur",cancelled,&mut progress)?;scratch.remove(&logs[ch])?;logs[ch]=out;}}
 halation(&mut logs,&pipe.params,pitch,width,height,&mut scratch,cancelled,&mut progress)?;
 for path in &logs{inplace_with(path,&[],n,cancelled,|a,_|((a*correction).max(0.)+1e-10).log10())?;}
 let initial=density_initial(&logs,pipe,&CpuBackend,width,height,&mut scratch,cancelled,&mut progress)?;
 let planes=dir_density(logs,initial,pipe,&CpuBackend,pitch,width,height,&mut scratch,cancelled,&mut progress)?;
 scratch.observe()?;cancel(cancelled)?;
 let metrics=PregrainMetrics{elapsed_ms:started.elapsed().as_secs_f64()*1000.,peak_logical_scratch_bytes:scratch.peak,spatial_peak_buffer_bytes:scratch.spatial_buffer,spatial_bytes_read:scratch.read,spatial_bytes_written:scratch.written};
 Ok(PregrainPlanes{planes,width,height,_scratch:scratch,metered_ev,pitch,metrics})
}

/// Exact finite support of the existing CPU grain implementation. Nonlocal IIR
/// or sequential RNG requires global-stage escalation, never a truncated halo.
pub fn grain_support(pipe:&Pipeline,pitch:f32)->std::result::Result<usize,DetailError>{
 let g=&pipe.params.film_render.grain;
 if !g.active||g.amount<=0.{return Ok(0)}
 if !pitch.is_finite()||pitch<=0.||!g.amount.is_finite(){return Err(DetailError::Processing("Invalid grain pitch/amount".into()));}
 if g.coordinate_origin.is_none(){return Err(DetailError::Unsupported("Sequential grain RNG requires a global grain stage".into()));}
 fn support(sigma:f32)->std::result::Result<usize,DetailError>{
  if !sigma.is_finite()||sigma<0.{return Err(DetailError::Processing("Invalid grain blur sigma".into()));}
  if sigma>=3.{return Err(DetailError::Unsupported("Grain IIR requires exact global grain-stage escalation".into()));}
  Ok((from_f64(3.)*from_f32(sigma)+from_f64(0.5))as usize)
 }
 let final_radius=if (g.sublayers_active&&g.blur>0.)||(!g.sublayers_active&&g.blur>0.4){support(g.blur)?}else{0};
 if !g.sublayers_active{return Ok(final_radius)}
 let layers=&pipe.film.data.density_curves_layers;
 if layers.len()!=pipe.film.data.density_curves.len()||layers.is_empty()||layers.iter().any(|r|r.len()<3||r.iter().take(3).any(|v|v.len()<3)){return Err(DetailError::Processing("Invalid grain sensitometry layers".into()));}
 let mut max_layers=[[0f64;3];3];for row in layers{for sl in 0..3{for ch in 0..3{max_layers[sl][ch]=max_layers[sl][ch].max(row[sl][ch]);}}}
 let mut dye_radius=0;
 if g.blur_dye_clouds_um>0.4{for ch in 0..3{let total:f64=(0..3).map(|sl|max_layers[sl][ch]).sum();for sl in 0..3{
  let fraction=max_layers[sl][ch]/total;let dmax=max_layers[sl][ch]+fraction*g.density_min[ch];
  let particles=(pitch as f64).powi(2)*fraction/(g.agx_particle_area_um2*g.agx_particle_scale[ch]*g.agx_particle_scale_layers[sl]);
  if !dmax.is_finite()||dmax<=0.||!particles.is_finite()||particles<=0.{return Err(DetailError::Processing("Invalid grain particle calibration".into()));}
  let sigma=(g.blur_dye_clouds_um as f64*(dmax/particles).sqrt())as f32;
  if sigma>0.1{dye_radius=dye_radius.max(support(sigma)?);}
 }}}
 let mut micro_radius=0;
 let ms=g.micro_structure[1]as f64*0.001/pitch as f64;
 if ms>0.05{let spread=g.micro_structure[0]/pitch;if spread>0.4{micro_radius=support(spread)?;}}
 Ok(dye_radius.max(micro_radius)+final_radius)
}

#[cfg(test)]
mod tests {
 use super::*;
 use spektrafilm_core::{profile::load_profile_by_name,stages::filming};
 use std::sync::atomic::{AtomicU64,Ordering};
 static IDS:AtomicU64=AtomicU64::new(0);
 struct TestRoot(PathBuf);impl Drop for TestRoot{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
 fn root()->TestRoot{let p=std::env::temp_dir().join(format!("owned-detail-global-{}-{}",std::process::id(),IDS.fetch_add(1,Ordering::Relaxed)));fs::create_dir(&p).unwrap();TestRoot(p)}
 fn pipeline()->Pipeline{
  let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let film=load_profile_by_name(&data,"kodak_portra_400").unwrap();let paper=load_profile_by_name(&data,"kodak_2383").unwrap();
  let mut p=RuntimeParams::default();p.settings.use_enlarger_lut=true;p.settings.use_scanner_lut=true;p.print_render.glare.active=false;p.io.input_color_space="sRGB".into();p.camera.pixel_size_um_override=Some(20.);p.camera.auto_exposure=true;p.camera.exposure_compensation_ev=0.25;
  p.camera.diffusion_filter.active=true;p.camera.diffusion_filter.filter_family="black_pro_mist".into();p.camera.lens_blur_um=2.;p.film_render.halation.boost_ev=0.5;p.film_render.grain.coordinate_origin=Some([0,0]);
  Pipeline::new_with_spectral(film,paper,p,&data).unwrap()
 }
 fn source(root:&Path,w:u32,h:u32)->(PathBuf,ImageBuf){let v:Vec<f32>=(0..w*h*3).map(|i|if i<12{100.}else{0.01+(i*37%211)as f32/150.}).collect();let p=root.join("source.f32le");fs::write(&p,v.iter().flat_map(|x|x.to_le_bytes()).collect::<Vec<_>>()).unwrap();(p,ImageBuf::from_data(w,h,v.into_iter().map(|x|from_f64(x as f64)).collect()))}
 #[test]
 fn owned_pregrain_matches_full_reference_ae_and_owned_cleanup(){
  let root=root();let(w,h)=(31,23);let(input,image)=source(&root.0,w,h);let base=pipeline();
  for method in ["average","median","center_weighted","partial","matrix","multi_zone","highlight_weighted"]{
   let mut p=base.params.clone();p.camera.auto_exposure_method=method.into();let pipe=base.clone().with_params(p);let prepared=pipe.prepare_streamed_finish_spatial(&CpuBackend).unwrap();let dir=root.0.join(method);
   let token=CancellationToken::new();let planes=build_pregrain_with_correction(&pipe,&input,w,h,&dir,prepared.filming_exposure_correction,&token).unwrap();
   let got=planes.read_rect(Rect{x:0,y:0,w:w as usize,h:h as usize},&token).unwrap();
   let log=filming::expose(&image,&pipe.film,&pipe.params,&CpuBackend,pipe.tc_lut(),None,pipe.front_illuminant_spd(),prepared.filming_exposure_correction);let mut p=pipe.params.clone();p.film_render.grain.active=false;let expected=filming::develop(&log,&pipe.film,&p,&CpuBackend);
   let max=got.data.iter().zip(expected.data).map(|(a,b)|(*a-b).abs()as f64).fold(0.,f64::max);assert!(max<1e-8,"{method}: {max}");
   assert_eq!(planes.disk_bytes(),w as u64*h as u64*24);assert_eq!(fs::read_dir(&dir).unwrap().count(),3);assert!(planes.metrics.peak_logical_scratch_bytes>=planes.disk_bytes());
   token.cancel();assert!(matches!(planes.read_rect(Rect{x:0,y:0,w:1,h:1},&token),Err(DetailError::Cancelled)));
   drop(planes);assert!(!dir.exists());assert!(input.exists());
  }
 }
 #[cfg(feature="precision-f64")]
 #[test]
 fn shared_ev_detail_planes_match_single_scale_and_ordinary_metadata_without_remetering(){
  use crate::mobile::{self,MobileParams,PreviewMetering,PREVIEW_METERING_ALGORITHM};
  let root=root();let(w,h)=(31u32,23u32);let data=Path::new(env!("CARGO_MANIFEST_DIR")).join("../../data");
  let mut controls=MobileParams::default();controls.use_gpu=false;controls.auto_exposure=true;controls.film_exposure_ev=0.5;controls.grain_amount=0.;
  controls.preview_metering=Some(PreviewMetering{algorithm:PREVIEW_METERING_ALGORITHM.into(),source_revision:"owned-source-test".into(),mapping_revision:"oriented-original-v1:snapshot=owned-source-test:meter-grid:test".into(),data_revision:"fixture".into(),owner:"detail-test".into(),method:controls.metering.clone(),width:w,height:h,ev:1.});
  controls.validate().unwrap();
  let film=load_profile_by_name(&data,&controls.film_stock).unwrap();let paper=load_profile_by_name(&data,&controls.paper_stock).unwrap();
  let runtime=controls.preview_runtime_for_film(&film);
  assert_eq!(runtime.camera.exposure_compensation_ev,0.5);
  let pipe=Pipeline::new_with_spectral(film,paper,runtime,&data).unwrap();
  let prepared=pipe.prepare_streamed_finish_spatial(&CpuBackend).unwrap();
  let mut reference_params=pipe.params.clone();reference_params.camera.auto_exposure=false;reference_params.camera.preview_metered_ev=None;
  let reference=pipe.clone().with_params(reference_params);
  let reference_prepared=reference.prepare_streamed_finish_spatial(&CpuBackend).unwrap();
  assert_eq!(prepared.filming_exposure_correction,reference_prepared.filming_exposure_correction);
  struct Handle(i64);impl Drop for Handle{fn drop(&mut self){mobile::destroy(self.0);}}
  let handle=Handle(mobile::create(data.to_str().unwrap()).unwrap());let engine=mobile::get(handle.0).unwrap();
  // Different full working inputs deliberately meter differently. Both must
  // consume the canonical owner EV rather than measuring their own grid.
  for variant in 0..2 {
   let input_values:Vec<f32>=(0..w*h*3).map(|i|{let x=i/3%w;let base=if x<w/2{0.01}else{0.2};(base+(i%3)as f32*0.005)*if variant==0{1.}else{0.125}}).collect();
   assert!((mobile::measure_preview(&input_values,w,h,&controls.metering).unwrap()-1.).abs()>0.1);
   let input=root.0.join(format!("shared-input-{variant}.f32le"));fs::write(&input,input_values.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
   let token=CancellationToken::new();let dir=root.0.join(format!("shared-planes-{variant}"));
   let planes=build_pregrain_with_correction(&pipe,&input,w,h,&dir,prepared.filming_exposure_correction,&token).unwrap();
   let ordinary=mobile::render(&engine,0,&input_values,w,h,&controls).unwrap();
   assert_eq!(ordinary.metered_ev,Some(1.));assert_eq!(planes.metered_ev,ordinary.metered_ev);
   assert!(!ordinary.gpu.active);
   let scaled=ImageBuf::from_data(w,h,input_values.iter().map(|v|from_f64((*v*2.)as f64)).collect());
   let logs=filming::expose(&scaled,&reference.film,&reference.params,&CpuBackend,reference.tc_lut(),None,reference.front_illuminant_spd(),reference_prepared.filming_exposure_correction);
   let expected=filming::develop(&logs,&reference.film,&reference.params,&CpuBackend);
   let actual=planes.read_rect(Rect{x:0,y:0,w:w as usize,h:h as usize},&token).unwrap();
   assert_eq!(actual.data.len(),expected.data.len());
   let error=actual.data.iter().zip(&expected.data).map(|(a,b)|(a-b).abs()as f64).fold(0.,f64::max);
   assert!(error<1e-8,"detail shared AE applied differently from one input scale: {error}");
   for rect in [Rect{x:0,y:0,w:9,h:11},Rect{x:22,y:12,w:9,h:11}] {
    let tile=planes.read_rect(rect,&token).unwrap();
    for y in 0..rect.h {for x in 0..rect.w {for ch in 0..3 {
     assert_eq!(tile.data[(y*rect.w+x)*3+ch],actual.data[((y+rect.y)*w as usize+x+rect.x)*3+ch]);
    }}}
    assert_eq!(planes.metered_ev,Some(1.),"region read cannot remeter");
   }
   drop(planes);assert!(!dir.exists());assert!(input.exists());
   eprintln!("SHARED_AE_DETAIL_PASS variant={variant} metered_ev=1 max_pregrain_abs={error}");
  }
 }
 #[test]
 fn cancellation_during_build_preserves_source_and_removes_owned_work(){
  let root=root();let(input,_)=source(&root.0,64,48);let pipe=pipeline();let prepared=pipe.prepare_streamed_finish_spatial(&CpuBackend).unwrap();
  for limit in [2,25,150,500]{let dir=root.0.join(format!("cancel-{limit}"));let mut checks=0;
   let result=build_inner(&pipe,&input,64,48,&dir,prepared.filming_exposure_correction,&mut ||{checks+=1;checks>limit});
   assert!(matches!(result,Err(EngineError{code:"CANCELLED",..})),"limit {limit}");assert!(!dir.exists());assert!(input.exists());
  }
  let existing=root.0.join("existing");fs::create_dir(&existing).unwrap();fs::write(existing.join("sentinel"),b"keep").unwrap();
  assert!(build_inner(&pipe,&input,64,48,&existing,prepared.filming_exposure_correction,&mut ||false).is_err());assert_eq!(fs::read(existing.join("sentinel")).unwrap(),b"keep");
 }
 fn make_planes(s:&mut Scratch,label:&str,w:u32,h:u32,value:f64)->[PathBuf;3]{std::array::from_fn(|_|{let p=s.path(label);let mut f=new_file(&p).unwrap();write64(&mut f,&vec![value;w as usize*h as usize]).unwrap();p})}
 #[test]
 fn numerical_halation_and_dir_cancel_inside_work(){
  let root=root();let pipe=pipeline();let(w,h)=(64,48);
  {let dir=root.0.join("halation");let mut scratch=Scratch::new(&dir).unwrap();let mut planes=make_planes(&mut scratch,"raw",w,h,0.5);let mut checks=0;
   let r=halation(&mut planes,&pipe.params,20.,w,h,&mut scratch,&mut ||{checks+=1;checks>6},&mut SilentProgress);assert!(matches!(r,Err(EngineError{code:"CANCELLED",..})));assert!(checks>6);drop(scratch);assert!(!dir.exists());}
  {let dir=root.0.join("dir");let mut scratch=Scratch::new(&dir).unwrap();let logs=make_planes(&mut scratch,"log",w,h,-0.2);let density=make_planes(&mut scratch,"density",w,h,0.5);let mut checks=0;
   let r=dir_density(logs,density,&pipe,&CpuBackend,20.,w,h,&mut scratch,&mut ||{checks+=1;checks>6},&mut SilentProgress);assert!(matches!(r,Err(EngineError{code:"CANCELLED",..})));assert!(checks>6);drop(scratch);assert!(!dir.exists());}
 }
 #[test]
 fn grain_support_classifies_fir_and_global_modes(){
  let pipe=pipeline();assert_eq!(grain_support(&pipe,22.786459).unwrap(),2);
  let mut p=pipe.params.clone();p.film_render.grain.coordinate_origin=None;assert!(matches!(grain_support(&pipe.clone().with_params(p),22.),Err(DetailError::Unsupported(_))));
  let mut p=pipe.params.clone();p.film_render.grain.blur=3.;assert!(matches!(grain_support(&pipe.clone().with_params(p),22.),Err(DetailError::Unsupported(_))));
  let mut p=pipe.params.clone();p.film_render.grain.blur_dye_clouds_um=1000.;assert!(matches!(grain_support(&pipe.clone().with_params(p),22.),Err(DetailError::Unsupported(_))));
  let mut p=pipe.params.clone();p.film_render.grain.micro_structure=[66.,10000.];assert!(matches!(grain_support(&pipe.clone().with_params(p),22.),Err(DetailError::Unsupported(_))));
  let mut p=pipe.params.clone();p.film_render.grain.active=false;p.film_render.grain.coordinate_origin=None;assert_eq!(grain_support(&pipe.clone().with_params(p),22.).unwrap(),0);
  let mut p=pipe.params.clone();p.film_render.grain.sublayers_active=false;p.film_render.grain.blur=0.2;assert_eq!(grain_support(&pipe.clone().with_params(p),22.).unwrap(),0);
 }
}
