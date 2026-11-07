//! Full-resolution disk-backed physical pipeline. No image-space halo truncation.
//! Pointwise stages use bounded row chunks; every spatial filter spans full rows
//! and columns through disk_spatial's exact FIR/IIR operator. Files remain local.
use std::{fs::{self,File,OpenOptions},io::{Read,Write,Seek,SeekFrom},path::{Path,PathBuf},time::Instant};
use serde::Serialize;
use spektrafilm_core::{params::RuntimeParams,pipeline::Pipeline,profile::load_profile_by_name};
use spektrafilm_gpu::{ComputeBackend,cpu_backend::CpuBackend};
use spektrafilm_math::{image::ImageBuf,precision::{Scalar,from_f64,from_f32}};
use crate::{mobile::{EngineError,Result},disk_spatial};
use crate::streamed_progress::{ExportProgress,ProgressSink,Reporter};

const SPATIAL_MEMORY:usize=16*1024*1024;
const BLOCK_PIXELS:usize=32768;
#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct StageMetrics {pub stage:&'static str,pub elapsed_ms:f64}
#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct GpuTileMetrics {pub core_width:u32,pub core_height:u32,pub expanded_width:u32,pub expanded_height:u32}
#[derive(Debug,Serialize)]
#[serde(rename_all="camelCase")]
pub struct StreamedMetrics {pub export_execution:Option<crate::export_execution::ExportExecutionMetrics>,pub metered_ev:Option<f32>,pub gpu:crate::adreno::GpuDiagnostics,pub width:u32,pub height:u32,pub pixel_count:u64,pub memory_budget_mi_b:u32,pub elapsed_ms:f64,pub setup_ms:f64,pub estimated_scratch_bytes:u64,pub peak_logical_scratch_bytes:u64,pub spatial_peak_explicit_buffer_bytes:u64,pub spatial_bytes_read:u64,pub spatial_bytes_written:u64,pub spatial_pass_count:u64,pub pointwise_chunk_rows:u32,pub gpu_tiles:Option<GpuTileMetrics>,pub lut_storage_bytes:usize,pub stages:Vec<StageMetrics>,pub algorithm:&'static str,pub grain_mode:&'static str,pub memory_policy:&'static str}

fn error(code:&'static str,message:impl Into<String>)->EngineError {EngineError::new(code,message)}
fn checkpoint_error(e:std::io::Error)->EngineError {if e.kind()==std::io::ErrorKind::InvalidData{error("CHECKPOINT_INVALID","Saved export checkpoint is corrupt or does not match this job")}else{io(e)}}
fn io(e:std::io::Error)->EngineError {error(if e.kind()==std::io::ErrorKind::Interrupted {"CANCELLED"} else {"IO_ERROR"},e.to_string())}
fn cancel(c:&mut impl FnMut()->bool)->Result<()> {if c(){Err(error("CANCELLED","Streamed export cancelled"))}else{Ok(())}}
fn new_file(p:&Path)->Result<File> {OpenOptions::new().write(true).create_new(true).open(p).map_err(io)}
fn read64(f:&mut File,n:usize)->Result<Vec<f64>> {let mut b=vec![0u8;n*8];f.read_exact(&mut b).map_err(io)?;let mut v=Vec::with_capacity(n);for x in b.chunks_exact(8){let a=f64::from_le_bytes(x.try_into().unwrap());if !a.is_finite(){return Err(error("INVALID_PIXELS","Nonfinite intermediate"));}v.push(a);}Ok(v)}
fn write64(f:&mut File,v:&[f64])->Result<()> {let mut b=Vec::with_capacity(v.len()*8);for &x in v {if !x.is_finite(){return Err(error("NONFINITE_OUTPUT","Nonfinite intermediate"));}b.extend_from_slice(&x.to_le_bytes());}f.write_all(&b).map_err(io)}
struct OutputGuard {path:PathBuf,keep:bool}
impl Drop for OutputGuard {fn drop(&mut self){if !self.keep {let _=fs::remove_file(&self.path);if let Some(p)=crate::scan_preview::path(&self.path){let _=fs::remove_file(&p);let _=fs::remove_file(p.with_extension("png.tmp"));}}}}
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

fn write_output_rgb(output:&mut File,rgb:&ImageBuf,exposure:f64)->Result<()> {
 let mut bytes=Vec::with_capacity(rgb.data.len()*4);for &value in &rgb.data {let z=(value as f64*exposure)as f32;if !z.is_finite(){return Err(error("NONFINITE_OUTPUT","Nonfinite final output"));}bytes.extend_from_slice(&z.to_le_bytes());}output.write_all(&bytes).map_err(io)
}

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
 if v.back_reflection && v.halation_amount>0. && v.base_thickness_um>0. {
  let components=spektrafilm_math::halation::back_reflection_components(v.base_thickness_um,v.halation_spatial_scale,v.halation_amount,pitch as f64);
  for ch in 0..3 {if strengths[ch]==0. {continue;}let acc=s.path("halation-back-reflection");let f=new_file(&acc)?;f.set_len(n*8).map_err(io)?;drop(f);s.observe()?;
   for component in &components {let blur=s.blur(&paths[ch],w,h,component.sigma_px[ch] as f32,"film_halation",c,p)?;let weight=component.weight[ch];inplace_with(&acc,&[&blur],n,c,|a,x|a+weight*x[0])?;s.remove(&blur)?;}
   let norm=if v.halation_renormalize {1.+strengths[ch]}else{1.};inplace_with(&paths[ch],&[&acc],n,c,|a,x|(a+strengths[ch]*x[0].max(0.))/norm)?;s.remove(&acc)?;
  }
 } else if v.halation_n_bounces>=1&&strengths.iter().any(|&a|a>0.) {let mut weights:Vec<f64>=(0..v.halation_n_bounces).map(|k|v.halation_bounce_decay.powi(k as i32)).collect();let sum:f64=weights.iter().sum();for x in &mut weights{*x/=sum;}
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

fn grain_density(execution:Option<&crate::export_execution::ExportPermit>,mut density:[PathBuf;3],pipe:&Pipeline,pitch:f32,w:u32,h:u32,s:&mut Scratch,c:&mut impl FnMut()->bool,p:&mut impl ProgressSink)->Result<[PathBuf;3]> {
 let g=&pipe.params.film_render.grain;if !g.active||g.amount==0.{return Ok(density);}let n=w as u64*h as u64;let rows=rows_per_chunk(w);let pixel_area=(pitch as f64).powi(2);let norm=spektrafilm_model::density_curves::normalize_density_curves_f64(&pipe.film.density_curves_f64());
 if norm.len()<2 {return Err(error("PROFILE_ERROR","Missing sensitometry curves"));}
 let mut max_layers=[[0f64;3];3];let layers=&pipe.film.data.density_curves_layers;
 if g.sublayers_active {if layers.len()!=norm.len()||layers.iter().any(|r|r.len()<3||r.iter().take(3).any(|v|v.len()<3)){return Err(error("PROFILE_ERROR","Invalid grain sublayer curves"));}for row in layers {for sl in 0..3 {for ch in 0..3 {max_layers[sl][ch]=max_layers[sl][ch].max(row[sl][ch]);}}}}
 let density_max=spektrafilm_model::density_curves::max_density_f64(&norm);
 let count=if g.sublayers_active {3}else{g.n_sub_layers.max(1)as usize};
 let layer_values=|ch:usize,sl:usize| {
   let total:f64=(0..3).map(|layer|max_layers[layer][ch]).sum();
   if g.sublayers_active {let fraction=max_layers[sl][ch]/total;let dmin=fraction*g.density_min[ch];(dmin,max_layers[sl][ch]+dmin,pixel_area*fraction/(g.agx_particle_area_um2*g.agx_particle_scale[ch]*g.agx_particle_scale_layers[sl]))}
   else {(g.density_min[ch],density_max[ch]+g.density_min[ch],pixel_area/(g.agx_particle_area_um2*g.agx_particle_scale[ch])/count as f64)}
 };
 let cloud_sigma=|dmax:f64,particles:f64| (g.blur_dye_clouds_um as f64*(dmax/particles).sqrt())as f32;
 let cloud_active=|sigma:f32| g.sublayers_active&&g.blur_dye_clouds_um>0.4&&sigma>0.1;
 let micro_active=g.sublayers_active && g.micro_structure[1]as f64*0.001/pitch as f64>0.05;
 let needs_blur=if g.sublayers_active{g.blur>0.}else{g.blur>0.4};
 let mut work=3*count as u64;
 for ch in 0..3 {for sl in 0..count {let(_,dmax,particles)=layer_values(ch,sl);if cloud_active(cloud_sigma(dmax,particles)){work+=1;}}}
 work+=3*u64::from(micro_active)*(1+u64::from(g.micro_structure[0]/pitch>0.4))+3*u64::from(needs_blur);
 p.begin_stage(5,work,"grain_sampling");
 for ch in 0..3 {let accum=s.path("grain-sum");let zero=new_file(&accum)?;zero.set_len(n*8).map_err(io)?;drop(zero);s.observe()?;let sign=if pipe.film.is_positive(){-1.}else{1.};let axis:Vec<f64>=norm.iter().map(|r|sign*r[ch]).collect();let count=if g.sublayers_active {3}else{g.n_sub_layers.max(1)as usize};
  for sl in 0..count {let (dmin,dmax,particles)=layer_values(ch,sl);
   if !dmax.is_finite()||dmax<=0.||!particles.is_finite()||particles<=0.{return Err(error("PROFILE_ERROR","Invalid physical grain layer"));}
   let sampled=s.path("grain-layer");let mut out=new_file(&sampled)?;let mut input=File::open(&density[ch]).map_err(io)?;
   p.begin_work("grain_sampling");for y in (0..h).step_by(rows as usize){cancel(c)?;let hh=rows.min(h-y);let len=w as usize*hh as usize;let values=read64(&mut input,len)?;let mut layer=Vec::with_capacity(len);
    for original_q in values {let q=if g.sublayers_active{sign*original_q}else{original_q};let value=if g.sublayers_active {let last=axis.len()-1;if q<=axis[0]{layers[0][sl][ch]}else if q>=axis[last]{layers[last][sl][ch]}else{let hi=axis.partition_point(|&v|v<=q).min(last);let lo=hi-1;let dx=axis[hi]-axis[lo];let t=if dx!=0.{(q-axis[lo])/dx}else{0.};layers[lo][sl][ch]+t*(layers[hi][sl][ch]-layers[lo][sl][ch])}}else{q};layer.push(from_f64(value+dmin));}
    let seed_ch=if g.monochrome {0}else{ch as u64};let seed=g.seed.wrapping_add(seed_ch+sl as u64*10);
    let sampled_values=if let Some(execution)=execution {execution.sample(&layer,w,hh,dmax,particles,g.uniformity[ch],seed,Some([0,y]))}else{spektrafilm_model::grain::layer_particle_model(&layer,w,hh,dmax,particles,g.uniformity[ch],seed,0.,Some([0,y]))};let values:Vec<f64>=sampled_values.iter().map(|&v|v as f64).collect();write64(&mut out,&values)?;p.pass("grain_sampling",(y+hh)as u64,h as u64);
   } drop(out);s.observe()?;p.end_work();
   let sigma=cloud_sigma(dmax,particles);
   let contribution=if cloud_active(sigma) {let blurred=s.blur(&sampled,w,h,sigma,"grain_cloud",c,p)?;s.remove(&sampled)?;blurred}else{sampled};
   inplace_with(&accum,&[&contribution],n,c,|a,x|a+x[0])?;s.remove(&contribution)?;
  }
  if micro_active {
    let field=s.path("grain-clump");p.begin_work("grain_microstructure");crate::streamed_global::write_microstructure_field(&field,w,h,if g.monochrome {0}else{ch},g.micro_structure,pitch,g.seed,&mut *c,|done,total|p.pass("grain_microstructure",done,total)).map_err(io)?;s.observe()?;p.end_work();
    let spread=g.micro_structure[0]/pitch;let field=if spread>0.4 {let b=s.blur(&field,w,h,spread,"grain_microstructure",c,p)?;s.remove(&field)?;b}else{field};
    inplace_with(&accum,&[&field],n,c,|a,v|a*v[0])?;s.remove(&field)?;
  }
  let average=if g.sublayers_active{1.}else{1./count as f64};inplace_with(&accum,&[],n,c,|a,_|a*average-g.density_min[ch])?;
  let needs_blur=if g.sublayers_active{g.blur>0.}else{g.blur>0.4};let final_plane=if needs_blur {let blurred=s.blur(&accum,w,h,g.blur,"grain_blur",c,p)?;s.remove(&accum)?;blurred}else{accum};
  if g.amount!=1. {inplace_with(&final_plane,&[&density[ch]],n,c,|a,x|x[0]+g.amount*(a-x[0]))?;}
  s.remove(&density[ch])?;density[ch]=final_plane;s.observe()?;
 }Ok(density)
}

// Count full-image reported operations; Gaussian row/column passes form one
// operation. Ancillary combines and filesystem finalization finish before the
// final completion unit. These are work fractions, never duration estimates.
fn light_work_count(params:&RuntimeParams,pitch:f32)->u64 {
 let v=&params.film_render.halation;
 let mut count=u64::from(v.boost_ev>0.)+u64::from(v.headroom_active())+u64::from(params.camera.diffusion_filter.active)+3*u64::from(params.camera.lens_blur_um>0.);
 if !v.active {return count;}
 for ch in 0..3 {
  let sigma=v.scatter_core_um[ch]*v.scatter_spatial_scale/pitch as f64;
  let decay=v.scatter_tail_um[ch]*v.scatter_spatial_scale/pitch as f64;
  if v.scatter_amount>0.&&(sigma>0.||decay>0.){count+=4;}
  let strength=v.halation_strength[ch]*v.halation_amount;
  let first=v.halation_first_sigma_um[ch]*v.halation_spatial_scale/pitch as f64;
  if v.back_reflection {if strength>0.&&v.base_thickness_um>0.{count+=4;}} else if v.halation_n_bounces>=1&&v.halation_strength.iter().any(|a|a*v.halation_amount>0.)&&strength!=0.&&first>0.{count+=v.halation_n_bounces as u64;}
 }
 count
}

/// Native-owned scratch budget, additional to input/output spools. Kept
/// conservative until measured across all supported controls; logical file sizes.
pub fn estimated_scratch_bytes(width:u32,height:u32)->Result<u64> {
 (width as u64).checked_mul(height as u64).and_then(|n|n.checked_mul(128)).and_then(|n|n.checked_add(64*1024*1024)).ok_or_else(||error("INVALID_DIMENSIONS","Scratch plan overflow"))
}
/// Conservative active-diffusion reservation uses the full permitted kernel cap,
/// avoiding expensive PSF construction during UI preflight. Includes 3 source planes.
pub fn estimated_scratch_with_diffusion(width:u32,height:u32,active:bool)->Result<u64> {
 let base=estimated_scratch_bytes(width,height)?;if !active{return Ok(base);}
 let radius=((width.min(height)as usize)/2).saturating_sub(1).max(1);
 let diffusion=24*width as u64*height as u64+crate::disk_diffusion::estimated_extra_bytes(width,height,radius)+64*1024*1024;
 Ok(base.max(diffusion))
}
#[allow(clippy::too_many_arguments)]
pub fn export_file(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,cancelled:impl FnMut()->bool,progress:impl FnMut(&'static str,u64,u64))->Result<StreamedMetrics> {
 export_file_with_gpu(data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,width,height,memory_budget_mib,brightness_ev,false,cancelled,progress)
}
#[allow(clippy::too_many_arguments)]
pub fn export_file_with_gpu(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,cancelled:impl FnMut()->bool,mut progress:impl FnMut(&'static str,u64,u64))->Result<StreamedMetrics> {
 export_file_with_progress(data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,cancelled,|v|if v.pass_updated {progress(v.phase,v.done,v.total)})
}
#[allow(clippy::too_many_arguments)]
pub fn export_file_with_progress(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed(data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,None,width,height,memory_budget_mib,brightness_ev,use_gpu,cancelled,progress)
}
#[allow(clippy::too_many_arguments)]
pub fn export_file_checkpointed(data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed_impl(None,data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,checkpoint_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,cancelled,progress)
}

pub(crate) fn export_file_checkpointed_managed(execution:&crate::export_execution::ExportPermit,data_dir:&Path,film_stock:&str,paper_stock:&str,params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 export_file_checkpointed_impl(Some(execution),data_dir,film_stock,paper_stock,params,input_path,output_path,scratch_dir,checkpoint_dir,width,height,memory_budget_mib,brightness_ev,use_gpu,cancelled,progress)
}

fn export_file_checkpointed_impl(execution:Option<&crate::export_execution::ExportPermit>,data_dir:&Path,film_stock:&str,paper_stock:&str,mut params:RuntimeParams,input_path:&Path,output_path:&Path,scratch_dir:&Path,checkpoint_dir:Option<&Path>,width:u32,height:u32,memory_budget_mib:u32,brightness_ev:f64,use_gpu:bool,mut cancelled:impl FnMut()->bool,progress:impl FnMut(ExportProgress))->Result<StreamedMetrics> {
 crate::mobile::gpu_completion_guard()?;
 let mut progress=Reporter::new(progress);
 let start=Instant::now();if !cfg!(feature="precision-f64"){return Err(error("UNSUPPORTED_PRECISION","Streamed physical export requires f64 internal precision"));}
 if width==0||height==0||width>40000||height>40000{return Err(error("INVALID_DIMENSIONS","Dimensions must be1..40000"));}
 if !(128..=512).contains(&memory_budget_mib)||!brightness_ev.is_finite()||!(-2.0..=2.0).contains(&brightness_ev){return Err(error("INVALID_BUDGET","Invalid memory budget or brightness"));}
 if input_path==output_path||fs::symlink_metadata(output_path).is_ok()||fs::symlink_metadata(scratch_dir).is_ok(){return Err(error("INVALID_PATH","Output and owned scratch must be new and distinct"));}
 let n=width as u64*height as u64;if fs::metadata(input_path).map_err(io)?.len()!=n*12{return Err(error("INVALID_INPUT_FILE","Input length must equal width*height*12"));}
 if params.io.crop||params.io.upscale_factor!=1.||params.io.input_cctf_decoding||params.io.output_cctf_encoding||params.settings.rgb_to_raw_method!="hanatos2025" {return Err(error("UNSUPPORTED_EXPORT_OPTIONS","Unsupported scientific option; no effect silently ignored"));}
 for d in [&params.camera.diffusion_filter,&params.enlarger.diffusion_filter] {
   if d.active && (!["glimmerglass","black_pro_mist","pro_mist","cinebloom"].contains(&d.filter_family.as_str()) || !d.strength.is_finite() || !(0.0..=2.0).contains(&d.strength) || !d.spatial_scale.is_finite() || !(0.25..=2.0).contains(&d.spatial_scale)) {return Err(error("INVALID_PARAMETERS","Unsupported diffusion controls"));}
 }
 let [sharp_radius,sharp_amount]=params.scanner.unsharp_mask;
 if !params.camera.lens_blur_um.is_finite() || !(0.0..=100.0).contains(&params.camera.lens_blur_um) || !params.scanner.lens_blur.is_finite() || !(0.0..=3.0).contains(&params.scanner.lens_blur){return Err(error("INVALID_PARAMETERS","Blur range exceeded"));}
 if !sharp_radius.is_finite()||!sharp_amount.is_finite()||sharp_radius<0.||sharp_radius>3.||sharp_amount<0.||sharp_amount>2. {return Err(error("INVALID_PARAMETERS","Sharpening range exceeded"));}
 let pitch=params.camera.pixel_size_um_override.unwrap_or(params.camera.film_format_mm*1000./width.max(height)as f32);if !pitch.is_finite()||pitch<=0.{return Err(error("INVALID_PARAMETERS","Invalid full-image physical pitch"));}

 params.camera.pixel_size_um_override=Some(pitch);params.film_render.grain.coordinate_origin=Some([0,0]);params.settings.use_enlarger_lut=true;params.settings.use_scanner_lut=true;params.settings.lut_resolution=33;
 cancel(&mut cancelled)?;progress.pass("setup",0,1);let film=load_profile_by_name(data_dir,film_stock).map_err(|e|error("PROFILE_ERROR",e.to_string()))?;let paper=load_profile_by_name(data_dir,paper_stock).map_err(|e|error("PROFILE_ERROR",e.to_string()))?;
 if (!film.is_negative()&&!film.is_positive())||film.is_positive()&&!params.io.scan_film{return Err(error("UNSUPPORTED_PROFILE","Unsupported film or reversal print route"));}
 // An existing exact CPU checkpoint wins over a new approximate attempt.
 #[allow(unused_mut)] let mut resident_fallback:Option<&'static str>=None;
 #[cfg(feature="adreno-gpu")]
 let mut early_preview:Option<crate::early_negative_preview::PreviewHandle>=None;
 #[cfg(feature="adreno-gpu")]
 if use_gpu {
   if checkpoint_dir.is_some_and(|root|root.join("manifest.json").exists()) {resident_fallback=Some("cpu_checkpoint_resume");}
   else {
     let gpu_submits_before=crate::mobile::full_chain_gpu().map_or(0,|g|g.thread_submits());
     let attempt=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||crate::gpu_export::export(&params,film_stock,paper_stock,&film,&paper,data_dir,input_path,output_path,checkpoint_dir,width,height,memory_budget_mib,brightness_ev,&mut cancelled,&mut progress,&mut early_preview)));
     if !matches!(&attempt,Ok(Ok(_))) {if let Some(ref preview)=early_preview {preview.cancel.store(true,std::sync::atomic::Ordering::Release);}}
     if let Some(failure)=crate::mobile::full_chain_gpu().filter(|g|g.thread_submits()>gpu_submits_before).and_then(|g|g.thread_resident_failure()) {progress.gpu_failure(failure.stage.as_str(),failure.elapsed_ms);}
     crate::mobile::gpu_completion_guard()?;
     match attempt {
       Ok(Ok(metrics))=>return Ok(metrics),
       Ok(Err(e)) if matches!(e.code,"CANCELLED"|"INVALID_PIXELS"|"NONFINITE_OUTPUT"|"INVALID_OUTPUT_SHAPE"|"CHECKPOINT_INVALID"|"IO_ERROR")=>return Err(e),
       Ok(Err(e))=>resident_fallback=Some(match e.code {"GPU_MEMORY_BUDGET"=>"gpu_memory_budget_fallback","GPU_BINDING_LIMIT"=>"gpu_binding_limit_fallback","GPU_UNAVAILABLE"=>"gpu_unavailable_fallback","GPU_UNSUPPORTED_ADAPTER"=>"unsupported_adapter",_=>"gpu_execution_fallback"}),
       Err(_)=>resident_fallback=Some("gpu_execution_fallback"),
     }
   }
 }
 // Cancel is cooperative inside the optional CPU preview. If it is still
 // releasing its resources, keep its reservation while the real CPU fallback
 // runs rather than waiting for it or exceeding the requested budget.
 #[cfg(feature="adreno-gpu")]
 let memory_budget_mib=if let Some(p)=early_preview.as_ref().filter(|p|p.running.load(std::sync::atomic::Ordering::Acquire)) {
     let reserved=p.reserved_bytes.saturating_add(1024*1024-1)/(1024*1024);
     memory_budget_mib.saturating_sub(reserved.min(u64::from(u32::MAX)) as u32)
 }else{memory_budget_mib};
 if let Some(reason)=resident_fallback {progress.diagnostic(reason);}
 cancel(&mut cancelled)?;
 let mut checkpoints=if let Some(root)=checkpoint_dir {
   let config=serde_json::to_string(&(film_stock,paper_stock,&params,brightness_ev,use_gpu)).map_err(|_|error("INVALID_PARAMETERS","Checkpoint configuration serialization failed"))?;
   let identity=crate::checkpoint::Identity::new(data_dir,input_path,width,height,config,&mut cancelled).map_err(checkpoint_error)?;
   Some(crate::checkpoint::Checkpoints::open(root,identity,&mut cancelled).map_err(checkpoint_error)?)
 }else{None};
 let resume_stage=checkpoints.as_ref().map_or(0,|c|c.stage());
 let pipe=Pipeline::new_with_spectral(film,paper,params,data_dir).map_err(|e|error("PIPELINE_ERROR",e))?;let prepared=pipe.prepare_streamed_finish_spatial(&CpuBackend).map_err(|e|error("PIPELINE_ERROR",e))?;let setup_ms=start.elapsed().as_secs_f64()*1000.;let backend=crate::adreno::AdrenoDensityBackend::with_enabled(use_gpu&&resident_fallback.is_none());cancel(&mut cancelled)?;let mut scratch=Scratch::new(scratch_dir)?;let mut stages=Vec::new();
 let mut output_guard=OutputGuard{path:output_path.to_path_buf(),keep:false};
 let rows=rows_per_chunk(width);
 // Scan sampling fits its original 3 MiB allowance; scientific FFT geometry stays unchanged.
 let fft_memory_cap=(memory_budget_mib as u64*1024*1024).saturating_sub(115*1024*1024+256*(width as u64).max(32768)+crate::export_execution::EXPORT_STACK_RESERVE_BYTES+SPATIAL_MEMORY as u64);
 let print_diffusion= !pipe.params.io.scan_film && pipe.params.enlarger.diffusion_filter.active;
 let mut metered_ev=checkpoints.as_ref().and_then(|c|c.metered_ev());
 if resume_stage<6 {
 let mut logs:[PathBuf;3]=std::array::from_fn(|_|scratch.path("restored"));
 if resume_stage==2||resume_stage==3 {checkpoints.as_ref().unwrap().restore(&logs,&mut cancelled).map_err(checkpoint_error)?;scratch.observe()?;}
 if resume_stage<2 {
 progress.begin_stage(2,1+u64::from(pipe.params.camera.auto_exposure && pipe.params.camera.preview_metered_ev.is_none()),"film_exposure");
 let at=Instant::now();let exposed=expose(input_path,&pipe,width,height,&mut scratch,&mut cancelled,&mut progress)?;logs=exposed.0;metered_ev=exposed.1;stages.push(StageMetrics{stage:"film_exposure",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 if let Some(c)=checkpoints.as_mut(){c.commit(2,&logs,metered_ev,&mut cancelled).map_err(checkpoint_error)?;}
 progress.finish_stage();
 }
 if resume_stage<3 {
 let light_work=light_work_count(&pipe.params,pitch);
 if light_work>0 {progress.begin_stage(3,light_work,"film_scatter_halation");}
 if pipe.params.film_render.halation.boost_ev>0. {let at=Instant::now();let v=&pipe.params.film_render.halation;progress.begin_work("highlight_boost");crate::streamed_global::boost_planes(&logs,n,v.boost_ev as f64,v.boost_range as f64,v.protect_ev as f64,&mut cancelled,|done,total|progress.pass("highlight_boost",done,total)).map_err(io)?;progress.end_work();stages.push(StageMetrics{stage:"highlight_boost",elapsed_ms:at.elapsed().as_secs_f64()*1000.});}
 if pipe.params.film_render.halation.headroom_active() {let at=Instant::now();let v=&pipe.params.film_render.halation;progress.begin_work("highlight_boost");crate::streamed_global::headroom_planes(&logs,n,v.headroom_ev,v.headroom_from_ev,v.headroom_to_ev,&mut cancelled,|done,total|progress.pass("highlight_boost",done,total)).map_err(io)?;progress.end_work();stages.push(StageMetrics{stage:"halation_headroom",elapsed_ms:at.elapsed().as_secs_f64()*1000.});}
 let at_diffusion=Instant::now();
 if pipe.params.camera.diffusion_filter.active {
   let d=&pipe.params.camera.diffusion_filter;
   if !["glimmerglass","black_pro_mist","pro_mist","cinebloom"].contains(&d.filter_family.as_str()) || !d.strength.is_finite() || !(0.0..=2.0).contains(&d.strength) || !d.spatial_scale.is_finite() || !(0.25..=2.0).contains(&d.spatial_scale) {return Err(error("INVALID_PARAMETERS","Unsupported diffusion controls"));}
   progress.begin_work("camera_diffusion");progress.pass("camera_diffusion",0,n*3);
   if let Some(kernel)=spektrafilm_model::diffusion::ExactDiffusionKernel::new_cancellable(&d.to_model(),pitch as f64,width as usize,height as usize,&mut cancelled).map_err(|_|error("CANCELLED","Diffusion kernel cancelled"))? {
     for ch in 0..3 {
       cancel(&mut cancelled)?;let transformed=scratch.path("camera-diffusion");let work=scratch.path("diffusion-work");let before=scratch.live()?;
       let m=crate::disk_diffusion::apply_plane_budgeted(&logs[ch],&transformed,&work,width,height,&kernel,ch,fft_memory_cap,&mut cancelled,|done,total|progress.pass("camera_diffusion",ch as u64*n+done,total*3)).map_err(io)?;
       scratch.peak=scratch.peak.max(before+m.peak_scratch_bytes);scratch.spatial_buffer=scratch.spatial_buffer.max(m.explicit_buffer_bytes);scratch.passes+=m.tiles;scratch.remove(&logs[ch])?;logs[ch]=transformed;
     }
   }
   progress.end_work();stages.push(StageMetrics{stage:"camera_diffusion",elapsed_ms:at_diffusion.elapsed().as_secs_f64()*1000.});
 }
 if pipe.params.camera.lens_blur_um>0. {
   let at=Instant::now();for ch in 0..3 {let b=scratch.blur(&logs[ch],width,height,pipe.params.camera.lens_blur_um/pitch,"camera_blur",&mut cancelled,&mut progress)?;scratch.remove(&logs[ch])?;logs[ch]=b;}stages.push(StageMetrics{stage:"camera_blur",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 }
 let at=Instant::now();halation(&mut logs,&pipe.params,pitch,width,height,&mut scratch,&mut cancelled,&mut progress)?;stages.push(StageMetrics{stage:"film_scatter_halation",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 if let Some(c)=checkpoints.as_mut(){c.commit(3,&logs,metered_ev,&mut cancelled).map_err(checkpoint_error)?;}
 if light_work>0 {progress.finish_stage();}
 }
 let density=if resume_stage>=4 {let paths=std::array::from_fn(|_|scratch.path("restored-density"));checkpoints.as_ref().unwrap().restore(&paths,&mut cancelled).map_err(checkpoint_error)?;scratch.observe()?;paths}else{
 progress.begin_stage(4,2+if pipe.params.film_render.dir_couplers.active {3+if pipe.params.film_render.dir_couplers.diffusion_size_um>0. {12}else{0}}else{0},"film_log");
 progress.begin_work("film_log");for q in &logs {inplace_with(q,&[],n,&mut cancelled,|a,_|((a*prepared.filming_exposure_correction).max(0.)+1e-10).log10())?;}progress.pass("film_log",1,1);progress.end_work();
 let at=Instant::now();let density=density_initial(&logs,&pipe,&backend,width,height,&mut scratch,&mut cancelled,&mut progress)?;let density=dir_density(logs,density,&pipe,&backend,pitch,width,height,&mut scratch,&mut cancelled,&mut progress)?;stages.push(StageMetrics{stage:"film_density_dir",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 if let Some(c)=checkpoints.as_mut(){c.commit(4,&density,metered_ev,&mut cancelled).map_err(checkpoint_error)?;}
 progress.finish_stage();density
 };
 // Stage visualisation from the actual developed film-density planes, never
 // a cosmetic inversion of the original. Preview failure must not invalidate
 // an otherwise durable scientific export; cancellation still propagates.
 if let Some(job_dir)=checkpoint_dir.and_then(|p|p.parent()) {
   if let Err(e)=crate::negative_preview::write_negative_preview(&density,&pipe.film,width,height,job_dir,&mut cancelled) {if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}
 }
 let density=if resume_stage<5 {
 let at=Instant::now();let density=grain_density(execution,density,&pipe,pitch,width,height,&mut scratch,&mut cancelled,&mut progress)?;stages.push(StageMetrics{stage:"grain",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 if let Some(c)=checkpoints.as_mut(){c.commit(5,&density,metered_ev,&mut cancelled).map_err(checkpoint_error)?;}
 if pipe.params.film_render.grain.active&&pipe.params.film_render.grain.amount!=0. {progress.finish_stage();}density
 }else{density};
 let glare=if pipe.params.io.scan_film{&pipe.params.film_render.glare}else{&pipe.params.print_render.glare};
 let has_glare=glare.active&&glare.percent>0.;
 let sharpening=(sharp_radius>0. && sharp_amount>0.) || pipe.params.scanner.lens_blur>0.;
 let finish_work=1+2*u64::from(print_diffusion)+u64::from(has_glare)*(1+u64::from(glare.blur>0.))+
   if sharpening {1+3*u64::from(pipe.params.scanner.lens_blur>0.)+3*u64::from(sharp_radius>0.&&sharp_amount>0.)}else{0};
 progress.begin_stage(6,finish_work,"printing_scanning");
 let density=if print_diffusion {
   let at=Instant::now();let raw:[PathBuf;3]=std::array::from_fn(|_|scratch.path("print-raw"));
   let mut out=raw.iter().map(|p|new_file(p)).collect::<Result<Vec<_>>>()?;
   let mut inputs=density.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;
   let rows=rows_per_chunk(width);
   progress.begin_work("printing_scanning");for y in (0..height).step_by(rows as usize) {cancel(&mut cancelled)?;let hh=rows.min(height-y);let mut planes=Vec::new();for f in &mut inputs{planes.push(read64(f,width as usize*hh as usize)?);}
     let exposed=prepared.expose_print_raw_chunk(&image_from_planes(&planes,width,hh)).map_err(|e|error("PIPELINE_ERROR",e))?;write_image_planes(&mut out,&exposed)?;progress.pass("printing_scanning",(y+hh)as u64,height as u64);
   }
   drop(out);drop(inputs);scratch.observe()?;progress.end_work();for p in &density{scratch.remove(p)?;}
   let mut raw=raw;let d=&pipe.params.enlarger.diffusion_filter;let print_pitch=pipe.params.camera.film_format_mm*1000./width.max(height)as f32;
   progress.begin_work("print_diffusion");if let Some(kernel)=spektrafilm_model::diffusion::ExactDiffusionKernel::new_cancellable(&d.to_model(),print_pitch as f64,width as usize,height as usize,&mut cancelled).map_err(|_|error("CANCELLED","Print diffusion cancelled"))? {
     for ch in 0..3 {cancel(&mut cancelled)?;let transformed=scratch.path("print-diffusion");let work=scratch.path("print-diffusion-work");let before=scratch.live()?;
       let m=crate::disk_diffusion::apply_plane_budgeted(&raw[ch],&transformed,&work,width,height,&kernel,ch,fft_memory_cap,&mut cancelled,|done,total|progress.pass("print_diffusion",ch as u64*n+done,total*3)).map_err(io)?;
       scratch.peak=scratch.peak.max(before+m.peak_scratch_bytes);scratch.spatial_buffer=scratch.spatial_buffer.max(m.explicit_buffer_bytes);scratch.passes+=m.tiles;scratch.remove(&raw[ch])?;raw[ch]=transformed;
     }
   }
   progress.end_work();stages.push(StageMetrics{stage:"print_diffusion",elapsed_ms:at.elapsed().as_secs_f64()*1000.});raw
 }else{density};
 let glare=if pipe.params.io.scan_film{&pipe.params.film_render.glare}else{&pipe.params.print_render.glare};
 let glare_path=if glare.active&&glare.percent>0. {
   let at=Instant::now();let raw=scratch.path("scan-glare");progress.begin_work("scan_glare");crate::streamed_global::write_glare_field(&raw,width,height,glare.percent,glare.roughness,42,&mut cancelled,|done,total|progress.pass("scan_glare",done,total)).map_err(io)?;scratch.observe()?;progress.end_work();
   let field=if glare.blur>0. {let b=scratch.blur(&raw,width,height,glare.blur,"scan_glare",&mut cancelled,&mut progress)?;scratch.remove(&raw)?;b}else{raw};inplace_with(&field,&[],n,&mut cancelled,|a,_|a*0.01)?;stages.push(StageMetrics{stage:"scan_glare",elapsed_ms:at.elapsed().as_secs_f64()*1000.});Some(field)
 }else{None};
 let mut glare_input=glare_path.as_ref().map(|p|File::open(p).map_err(io)).transpose()?;
 let at=Instant::now();cancel(&mut cancelled)?;let mut output=new_file(output_path)?;let mut files=density.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;let rows=rows_per_chunk(width);let exposure=2f64.powf(brightness_ev);
 let sharpening=(sharp_radius>0. && sharp_amount>0.) || pipe.params.scanner.lens_blur>0.;
 let scan_paths:[PathBuf;3]=std::array::from_fn(|_|scratch.path("scan-linear"));
 let mut scan_files=if sharpening {scan_paths.iter().map(|p|new_file(p)).collect::<Result<Vec<_>>>()?}else{Vec::new()};
 let mut scan_preview=if crate::scan_preview::path(output_path).is_some(){crate::scan_preview::Sampler::new(width,height,&pipe.params.io.output_color_space,exposure).ok()}else{None};
 progress.begin_work("printing_scanning");for y in(0..height).step_by(rows as usize){cancel(&mut cancelled)?;let hh=rows.min(height-y);let mut planes=Vec::new();for f in &mut files{planes.push(read64(f,width as usize*hh as usize)?);}let image=image_from_planes(&planes,width,hh);
   let developed=if print_diffusion{Some(prepared.develop_print_raw_chunk(&image,&CpuBackend).map_err(|e|error("PIPELINE_ERROR",e))?)}else{None};
   let mut rgb=if let Some(developed)=developed.as_ref(){
     if let Some(field)=glare_input.as_mut(){let values:Vec<Scalar>=read64(field,width as usize*hh as usize)?.into_iter().map(from_f64).collect();prepared.scan_print_density_chunk_unclipped_with_glare(developed,&values).map_err(|e|error("PIPELINE_ERROR",e))?}else{prepared.scan_print_density_chunk_unclipped(developed).map_err(|e|error("PIPELINE_ERROR",e))?}
   }else if let Some(field)=glare_input.as_mut(){let values:Vec<Scalar>=read64(field,width as usize*hh as usize)?.into_iter().map(from_f64).collect();prepared.process_density_chunk_unclipped_with_glare(&image,&values,&CpuBackend).map_err(|e|error("PIPELINE_ERROR",e))?}else if sharpening {prepared.process_density_chunk_unclipped(&image,&CpuBackend)}else{prepared.process_density_chunk(&image,&CpuBackend)};
   if !sharpening&&(glare_input.is_some()||print_diffusion){for v in &mut rgb.data{*v=(*v).clamp(from_f64(0.),from_f64(1.));}}
   if scan_preview.as_mut().is_some_and(|preview|preview.capture(y,&rgb).is_err()){scan_preview=None;}
   if sharpening {write_image_planes(&mut scan_files,&rgb)?;}else{write_output_rgb(&mut output,&rgb,exposure)?;}
   progress.pass("printing_scanning",(y+hh)as u64,height as u64);
 }
 progress.end_work();
 // Real full-frame scan RGB exists now; later scanner blur/sharpen still belongs to stage6.
 if let Some(preview)=scan_preview {if let Err(e)=preview.publish(output_path,&mut cancelled){if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}}
 drop(scan_files);drop(files);drop(glare_input);if let Some(q)=&glare_path{scratch.remove(q)?;}for q in &density{scratch.remove(q)?;}scratch.observe()?;
 if sharpening {
   let at_sharpen=Instant::now();
   for ch in 0..3 {
     if pipe.params.scanner.lens_blur>0. {let b=scratch.blur(&scan_paths[ch],width,height,pipe.params.scanner.lens_blur,"scan_blur",&mut cancelled,&mut progress)?;scratch.remove(&scan_paths[ch])?;fs::rename(&b,&scan_paths[ch]).map_err(io)?;}
     if sharp_amount>0. && sharp_radius>0. {
       let blurred=scratch.blur(&scan_paths[ch],width,height,sharp_radius,"scan_sharpen",&mut cancelled,&mut progress)?;
       let amount=from_f32(sharp_amount) as f64;
       inplace_with(&scan_paths[ch],&[&blurred],n,&mut cancelled,|original,x|(original+amount*(original-x[0])).clamp(0.,1.))?;
       scratch.remove(&blurred)?;
     }else{inplace_with(&scan_paths[ch],&[],n,&mut cancelled,|original,_|original.clamp(0.,1.))?;}
   }
   let mut scans=scan_paths.iter().map(|p|File::open(p).map_err(io)).collect::<Result<Vec<_>>>()?;
   progress.begin_work(if sharp_amount>0.&&sharp_radius>0.{"scan_sharpen"}else{"scan_blur"});for y in(0..height).step_by(rows as usize){cancel(&mut cancelled)?;let hh=rows.min(height-y);let mut planes=Vec::new();for f in &mut scans{planes.push(read64(f,width as usize*hh as usize)?);}write_output_rgb(&mut output,&image_from_planes(&planes,width,hh),exposure)?;progress.pass(if sharp_amount>0.&&sharp_radius>0.{"scan_sharpen"}else{"scan_blur"},(y+hh)as u64,height as u64);}
   progress.end_work();drop(scans);for q in &scan_paths{scratch.remove(q)?;}
   stages.push(StageMetrics{stage:if sharp_amount>0.&&sharp_radius>0.{"scan_sharpen"}else{"scan_blur"},elapsed_ms:at_sharpen.elapsed().as_secs_f64()*1000.});
 }
 output.flush().map_err(io)?;cancel(&mut cancelled)?;drop(output);if fs::metadata(output_path).map_err(io)?.len()!=n*12{return Err(error("INVALID_OUTPUT_SHAPE","Final output byte count changed"));}stages.push(StageMetrics{stage:"printing_scanning",elapsed_ms:at.elapsed().as_secs_f64()*1000.});
 if let Some(c)=checkpoints.as_mut(){c.commit(6,&[output_path.to_path_buf()],metered_ev,&mut cancelled).map_err(checkpoint_error)?;}
 }else{
   progress.begin_stage(6,1,"printing_scanning");progress.begin_work("printing_scanning");
   checkpoints.as_ref().unwrap().restore(&[output_path.to_path_buf()],&mut cancelled).map_err(checkpoint_error)?;progress.end_work();
   if let Err(e)=crate::scan_preview::publish_restored(output_path,width,height,&pipe.params.io.output_color_space,&mut cancelled){if e.kind()==std::io::ErrorKind::Interrupted{return Err(io(e));}}
 }
 scratch.observe()?;let mut gpu=backend.diagnostics();if let Some(reason)=resident_fallback {gpu.requested=use_gpu;gpu.reason=reason.into();gpu.cpu_fallbacks+=1;}let metrics=StreamedMetrics{export_execution:None,metered_ev,gpu,width,height,pixel_count:n,memory_budget_mi_b:memory_budget_mib,elapsed_ms:start.elapsed().as_secs_f64()*1000.,setup_ms,estimated_scratch_bytes:estimated_scratch_with_diffusion(width,height,pipe.params.camera.diffusion_filter.active||print_diffusion)?,peak_logical_scratch_bytes:scratch.peak,spatial_peak_explicit_buffer_bytes:scratch.spatial_buffer,spatial_bytes_read:scratch.read,spatial_bytes_written:scratch.written,spatial_pass_count:scratch.passes,pointwise_chunk_rows:rows,gpu_tiles:None,lut_storage_bytes:prepared.lut_storage_bytes(),stages,algorithm:"Full-image FIR/IIR and optional exact disk FFT overlap-save; spatial IO counters cover FIR/IIR only",grain_mode:"Absolute-coordinate Poisson-binomial with global dye-cloud/final blur",memory_policy:"Bounded row chunks plus16MiB spatial buffers; budget is admission estimate, not OS guarantee"};
 fs::remove_dir_all(&scratch.dir).map_err(io)?;drop(scratch);output_guard.keep=true;progress.pass("complete",n,n);progress.finish_stage();Ok(metrics)
}
