//! Exact discrete lens-diffusion convolution with bounded RAM and disk FFT tiles.
//! Overlap-save tiles use the ORIGINAL image's PSF radius and numpy-reflect
//! boundaries. No image resizing, shortened halo, or Gaussian approximation.
use std::{fs::{self,File,OpenOptions},io::{self,Read,Write,Seek,SeekFrom},path::{Path,PathBuf}};
use rustfft::{FftPlanner,num_complex::Complex};
use rayon::prelude::*;
use spektrafilm_model::diffusion::ExactDiffusionKernel;

const CORE:usize=4096;
const TRANSPOSE_TILE:usize=128;
#[derive(Debug,Default)]
pub struct DiffusionMetrics { pub peak_scratch_bytes:u64,pub explicit_buffer_bytes:u64,pub tiles:u64,pub fft_side:usize }
fn check(c:&mut impl FnMut()->bool)->io::Result<()> { if c(){Err(io::Error::new(io::ErrorKind::Interrupted,"Diffusion cancelled"))}else{Ok(())} }
fn fresh(p:&Path)->io::Result<File>{OpenOptions::new().read(true).write(true).create_new(true).open(p)}
fn next_fast(mut n:usize)->usize { loop {let mut m=n;for p in [2,3,5,7,11]{while m%p==0{m/=p;}}if m==1{return n;}n+=1;} }
pub fn fft_side(radius:usize,width:u32,height:u32)->usize{next_fast(2*radius+CORE.min(width.max(height) as usize).max(1))}
/// Extra bytes above the source planes: three complex FFT files plus output.
pub fn estimated_extra_bytes(width:u32,height:u32,radius:usize)->u64 {
    let side=fft_side(radius,width,height) as u64;48*side*side+8*width as u64*height as u64
}
fn reflect(i:isize,n:usize)->usize {if n<=1{return 0;}let period=2*(n-1)as isize;let j=i.rem_euclid(period);if j<n as isize{j as usize}else{(period-j)as usize}}
fn read_complex(f:&mut File,values:&mut [Complex<f64>],bytes:&mut [u8])->io::Result<()> {
 f.read_exact(&mut bytes[..values.len()*16])?;for(v,b)in values.iter_mut().zip(bytes.chunks_exact(16)){*v=Complex::new(f64::from_le_bytes(b[..8].try_into().unwrap()),f64::from_le_bytes(b[8..16].try_into().unwrap()));}Ok(())
}
fn write_complex(f:&mut File,values:&[Complex<f64>],bytes:&mut [u8])->io::Result<()> {
 for(v,b)in values.iter().zip(bytes.chunks_exact_mut(16)){b[..8].copy_from_slice(&v.re.to_le_bytes());b[8..16].copy_from_slice(&v.im.to_le_bytes());}f.write_all(&bytes[..values.len()*16])
}
fn transform_rows(path:&Path,side:usize,inverse:bool,c:&mut impl FnMut()->bool)->io::Result<()> {
 let mut file=OpenOptions::new().read(true).write(true).open(path)?;
 let mut planner=FftPlanner::<f64>::new();let fft=if inverse{planner.plan_fft_inverse(side)}else{planner.plan_fft_forward(side)};
 let mut row=vec![Complex::new(0.,0.);side];let mut scratch=vec![Complex::new(0.,0.);fft.get_inplace_scratch_len()];let mut bytes=vec![0u8;side*16];
 for y in 0..side{check(c)?;file.seek(SeekFrom::Start((y*side*16)as u64))?;read_complex(&mut file,&mut row,&mut bytes)?;fft.process_with_scratch(&mut row,&mut scratch);file.seek(SeekFrom::Start((y*side*16)as u64))?;write_complex(&mut file,&row,&mut bytes)?;}Ok(())
}
// Copy transformed kernel bytes in cancellable, bounded chunks.
fn copy_spectrum(src:&Path,dst:&Path,c:&mut impl FnMut()->bool)->io::Result<()> {
 let mut input=File::open(src)?;let mut output=OpenOptions::new().write(true).open(dst)?;
 let mut bytes=vec![0u8;1024*1024];
 loop {check(c)?;let n=input.read(&mut bytes)?;if n==0{break;}output.write_all(&bytes[..n])?;}
 check(c)
}
fn transpose(src:&Path,dst:&Path,side:usize,c:&mut impl FnMut()->bool)->io::Result<()> {
 let mut input=File::open(src)?;let mut output=OpenOptions::new().read(true).write(true).open(dst)?;output.set_len((side*side*16)as u64)?;
 let mut tile=vec![0u8;TRANSPOSE_TILE*TRANSPOSE_TILE*16];let mut row=vec![0u8;TRANSPOSE_TILE*16];
 for y in(0..side).step_by(TRANSPOSE_TILE){let hh=TRANSPOSE_TILE.min(side-y);for x in(0..side).step_by(TRANSPOSE_TILE){check(c)?;let ww=TRANSPOSE_TILE.min(side-x);
   for dy in 0..hh{input.seek(SeekFrom::Start(((y+dy)*side+x)as u64*16))?;input.read_exact(&mut tile[dy*ww*16..(dy+1)*ww*16])?;}
   for dx in 0..ww{for dy in 0..hh{row[dy*16..(dy+1)*16].copy_from_slice(&tile[(dy*ww+dx)*16..(dy*ww+dx+1)*16]);}output.seek(SeekFrom::Start(((x+dx)*side+y)as u64*16))?;output.write_all(&row[..hh*16])?;}
 }}Ok(())
}
/// Transform and leave transposed (frequency axes exchanged). Using identical
/// axes for both operands avoids an unnecessary transpose in forward FFTs.
fn forward(src:&Path,tmp:&Path,side:usize,c:&mut impl FnMut()->bool)->io::Result<()> {
 transform_rows(src,side,false,c)?;transpose(src,tmp,side,c)?;transform_rows(tmp,side,false,c)?;Ok(())
}
struct DirGuard(PathBuf);impl Drop for DirGuard{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
struct OutputGuard{path:PathBuf,keep:bool}impl Drop for OutputGuard{fn drop(&mut self){if !self.keep{let _=fs::remove_file(&self.path);}}}

/// One channel. `work_dir` and `output` MUST be new, caller-owned paths.
/// Outputs float64 LE, input unchanged. Progress counts completed output pixels.
pub fn apply_plane(input:&Path,output:&Path,work_dir:&Path,width:u32,height:u32,kernel:&ExactDiffusionKernel,channel:usize,
    cancelled:impl FnMut()->bool,progress:impl FnMut(u64,u64))->io::Result<DiffusionMetrics>{
 apply_plane_with_core(input,output,work_dir,width,height,kernel,channel,CORE,cancelled,progress)
}
// Memory FFT is opt-in only through an already reserved export budget. Never
// infer permission from MemAvailable or raise a low budget to a minimum.
fn fft_pool()->Option<&'static rayon::ThreadPool> {
 static POOL:std::sync::OnceLock<Option<rayon::ThreadPool>>=std::sync::OnceLock::new();
 POOL.get_or_init(||rayon::ThreadPoolBuilder::new().num_threads(2).stack_size(2*1024*1024).thread_name(|n|format!("film-fft-{n}")).start_handler(|_|crate::export_execution::lower_export_thread_priority()).build().ok()).as_ref()
}
fn memory_fft_need(side:usize,width:usize,pixels:u64)->Option<u64>{
 // Two complex planes + retained source + planner/twiddles, at most two
 // worker scratches, row IO, and 8MiB covering worker stacks/allocation margin.
 (side as u64).checked_mul(side as u64)?.checked_mul(32)?
  .checked_add(pixels.checked_mul(8)?)?.checked_add(side as u64*1024)?
  .checked_add(width as u64*16)?.checked_add(8*1024*1024)
}
fn choose_in_memory_core(radius:usize,w:usize,h:usize,cap:u64)->Option<usize>{
 let n=(w as u64).checked_mul(h as u64)?;let mut core=CORE.min(w.max(h)).max(1);
 loop{let side=next_fast(radius.checked_mul(2)?.checked_add(core)?);
  if memory_fft_need(side,w,n)?<=cap{return Some(core);}
  if core<=256{return None;}core=(core*3/4).max(256);
 }
}
/// Additional memory cap is supplied by the managed export reservation, after
/// subtracting its pipeline, row, spatial-buffer and stack reserves.
pub(crate) fn apply_plane_budgeted(input:&Path,output:&Path,work_dir:&Path,width:u32,height:u32,kernel:&ExactDiffusionKernel,channel:usize,additional_memory_cap:u64,
 mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->io::Result<DiffusionMetrics>{
 let(w,h)=(width as usize,height as usize);
 if w==0||h==0||channel>=3||fs::metadata(input)?.len()!=w as u64*h as u64*8{return Err(io::Error::new(io::ErrorKind::InvalidInput,"Invalid diffusion source"));}
 check(&mut cancelled)?;
 if let Some(core)=choose_in_memory_core(kernel.radius,w,h,additional_memory_cap).filter(|_|fft_pool().is_some()){
  match apply_plane_in_memory(input,output,width,height,kernel,channel,core,&mut cancelled,&mut progress){
   Ok(metrics)=>return Ok(metrics),
   Err(e) if e.kind()==io::ErrorKind::OutOfMemory=>{},
   Err(e)=>return Err(e),
  }
 }
 apply_plane_with_core(input,output,work_dir,width,height,kernel,channel,CORE,cancelled,progress)
}
fn alloc_complex(len:usize)->io::Result<Vec<Complex<f64>>>{
 let mut v=Vec::new();v.try_reserve_exact(len).map_err(|_|io::Error::new(io::ErrorKind::OutOfMemory,"diffusion plane allocation"))?;v.resize(len,Complex::new(0.,0.));Ok(v)
}
fn alloc_f64(len:usize)->io::Result<Vec<f64>>{
 let mut v=Vec::new();v.try_reserve_exact(len).map_err(|_|io::Error::new(io::ErrorKind::OutOfMemory,"diffusion source allocation"))?;v.resize(len,0.);Ok(v)
}
/// In-place row FFTs over a contiguous `side*xside` plane, parallel across rows.
fn transform_rows_mem(a:&mut [Complex<f64>],side:usize,inverse:bool,c:&mut impl FnMut()->bool)->io::Result<()>{
 let mut planner=FftPlanner::<f64>::new();let fft=if inverse{planner.plan_fft_inverse(side)}else{planner.plan_fft_forward(side)};
 let scratch=fft.get_inplace_scratch_len();const CHUNK:usize=64;
 let pool=fft_pool().ok_or_else(||io::Error::new(io::ErrorKind::OutOfMemory,"Bounded FFT pool unavailable"))?;
 for rows in a.chunks_mut(side*CHUNK){check(c)?;pool.install(||rows.par_chunks_mut(side).try_for_each_init(||alloc_complex(scratch),|s,row|{let s=s.as_mut().map_err(|_|io::Error::new(io::ErrorKind::OutOfMemory,"FFT worker scratch unavailable"))?;fft.process_with_scratch(row,s);Ok::<_,io::Error>(())}))?;}
 Ok(())
}
/// Cache-blocked in-place square transpose (frees the second transient plane).
fn transpose_in_place(a:&mut [Complex<f64>],side:usize,c:&mut impl FnMut()->bool)->io::Result<()>{
 const T:usize=32;
 for bi in (0..side).step_by(T){check(c)?;let imax=(bi+T).min(side);
  for i in bi..imax{for j in (i+1)..imax{a.swap(i*side+j,j*side+i);}}
  let mut bj=bi+T;while bj<side{let jmax=(bj+T).min(side);for i in bi..imax{for j in bj..jmax{a.swap(i*side+j,j*side+i);}}bj+=T;}
 }
 Ok(())
}
fn forward_mem(a:&mut [Complex<f64>],side:usize,c:&mut impl FnMut()->bool)->io::Result<()>{
 transform_rows_mem(a,side,false,c)?;transpose_in_place(a,side,c)?;transform_rows_mem(a,side,false,c)
}
fn inverse_mem(a:&mut [Complex<f64>],side:usize,c:&mut impl FnMut()->bool)->io::Result<()>{
 transform_rows_mem(a,side,true,c)?;transpose_in_place(a,side,c)?;transform_rows_mem(a,side,true,c)
}
/// In-memory original-PSF overlap-save. Numerical agreement is tested, not assumed bit-exact.
fn apply_plane_in_memory(input:&Path,output:&Path,width:u32,height:u32,kernel:&ExactDiffusionKernel,channel:usize,core_max:usize,
    mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->io::Result<DiffusionMetrics>{
 let (w,h)=(width as usize,height as usize);let n=(w as u64)*(h as u64);
 let side=next_fast(2*kernel.radius+core_max.min(w.max(h)).max(1));let core=side-2*kernel.radius;
 check(&mut cancelled)?;
 // Source plane read once into RAM (finite-validated), then gathered by reflection.
 let mut src=alloc_f64(w*h)?;
 {let mut f=File::open(input)?;let mut bytes=vec![0u8;w*8];for y in 0..h{check(&mut cancelled)?;f.read_exact(&mut bytes)?;for(v,b)in src[y*w..(y+1)*w].iter_mut().zip(bytes.chunks_exact(8)){*v=f64::from_le_bytes(b.try_into().unwrap());if !v.is_finite(){return Err(io::Error::new(io::ErrorKind::InvalidData,"Nonfinite diffusion input"));}}}}
 let mut data=alloc_complex(side*side)?;let mut kspec=alloc_complex(side*side)?;
 // Kernel spectrum (transposed orientation, matching the tile forward pass).
 for y in 0..side{check(&mut cancelled)?;let row=&mut data[y*side..(y+1)*side];row.fill(Complex::new(0.,0.));if y<kernel.side(){for(dst,value)in row.iter_mut().zip(kernel.row(channel,y)){dst.re=value;}}}
 forward_mem(&mut data,side,&mut cancelled)?;kspec.copy_from_slice(&data);
 let mut out=fresh(output)?;let mut output_guard=OutputGuard{path:output.to_path_buf(),keep:false};out.set_len(n*8)?;
 let norm=1./(side as f64*side as f64);let mut completed=0u64;let mut tiles=0u64;
 for y0 in(0..h).step_by(core){let hh=core.min(h-y0);for x0 in(0..w).step_by(core){let ww=core.min(w-x0);check(&mut cancelled)?;
  // Entire overlap-save input tile is globally reflected, never tile-reflected.
  fft_pool().unwrap().install(||data.par_chunks_mut(side).enumerate().for_each(|(y,drow)|{let sy=reflect(y0 as isize+y as isize-kernel.radius as isize,h);let srow=&src[sy*w..sy*w+w];for x in 0..side{drow[x]=Complex::new(srow[reflect(x0 as isize+x as isize-kernel.radius as isize,w)],0.);}}));
  check(&mut cancelled)?;
  forward_mem(&mut data,side,&mut cancelled)?;
  fft_pool().unwrap().install(||data.par_iter_mut().zip(kspec.par_iter()).for_each(|(d,k)|*d*=*k));
  inverse_mem(&mut data,side,&mut cancelled)?;
  let mut outbytes=vec![0u8;ww*8];
  for y in 0..hh{check(&mut cancelled)?;let conv=&data[(y+2*kernel.radius)*side+2*kernel.radius..];for x in 0..ww{let original=src[(y0+y)*w+x0+x];let value=(1.-kernel.scatter)*original+kernel.scatter*conv[x].re*norm;if !value.is_finite(){return Err(io::Error::new(io::ErrorKind::InvalidData,"Nonfinite diffusion result"));}outbytes[x*8..(x+1)*8].copy_from_slice(&value.to_le_bytes());}
   out.seek(SeekFrom::Start((((y0+y)*w+x0)*8)as u64))?;out.write_all(&outbytes)?;}
  completed+=(ww*hh)as u64;tiles+=1;progress(completed,n);
 }}
 out.flush()?;check(&mut cancelled)?;output_guard.keep=true;
 Ok(DiffusionMetrics{peak_scratch_bytes:n*8,explicit_buffer_bytes:memory_fft_need(side,w,n).unwrap_or(u64::MAX).max(1024*1024),tiles,fft_side:side})
}
fn apply_plane_with_core(input:&Path,output:&Path,work_dir:&Path,width:u32,height:u32,kernel:&ExactDiffusionKernel,channel:usize,core_max:usize,
    mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->io::Result<DiffusionMetrics>{
 let (w,h)=(width as usize,height as usize);if w==0||h==0||channel>=3{return Err(io::Error::new(io::ErrorKind::InvalidInput,"Invalid diffusion dimensions/channel"));}
 let n=(w as u64)*(h as u64);if fs::metadata(input)?.len()!=n*8{return Err(io::Error::new(io::ErrorKind::InvalidInput,"Invalid diffusion source size"));}
 check(&mut cancelled)?;fs::create_dir(work_dir)?;let _dir=DirGuard(work_dir.to_path_buf());let mut out=fresh(output)?;let mut output_guard=OutputGuard{path:output.to_path_buf(),keep:false};out.set_len(n*8)?;
 let side=next_fast(2*kernel.radius+core_max.min(w.max(h)).max(1));let core=side-2*kernel.radius;let fftbytes=(side as u64)*(side as u64)*16;
 let a=work_dir.join("a.fft");let b=work_dir.join("b.fft");let k=work_dir.join("kernel.fft");for p in [&a,&b,&k]{fresh(p)?.set_len(fftbytes)?;}
 // Generate the kernel into A, transform through B, retain its transposed spectrum K.
 {let mut file=OpenOptions::new().write(true).open(&a)?;let mut row=vec![Complex::new(0.,0.);side];let mut bytes=vec![0u8;side*16];
 for y in 0..side{check(&mut cancelled)?;row.fill(Complex::new(0.,0.));if y<kernel.side(){for(dst,value)in row.iter_mut().zip(kernel.row(channel,y)){dst.re=value;}}write_complex(&mut file,&row,&mut bytes)?;}}
 forward(&a,&b,side,&mut cancelled)?;copy_spectrum(&b,&k,&mut cancelled)?;
 let mut src=File::open(input)?;let mut srcbytes=vec![0u8;w*8];let mut source_row=vec![0f64;w];let mut row=vec![Complex::new(0.,0.);side];let mut other=vec![Complex::new(0.,0.);side];let mut bytes=vec![0u8;side*16];let mut otherbytes=vec![0u8;side*16];let mut completed=0u64;let mut tiles=0u64;
 for y0 in(0..h).step_by(core){let hh=core.min(h-y0);for x0 in(0..w).step_by(core){let ww=core.min(w-x0);check(&mut cancelled)?;
  // Entire overlap-save input tile is globally reflected, never tile-reflected.
  {let mut file=OpenOptions::new().write(true).open(&a)?;
  for y in 0..side{check(&mut cancelled)?;let sy=reflect(y0 as isize+y as isize-kernel.radius as isize,h);src.seek(SeekFrom::Start((sy*w*8)as u64))?;src.read_exact(&mut srcbytes)?;for(v,bs)in source_row.iter_mut().zip(srcbytes.chunks_exact(8)){*v=f64::from_le_bytes(bs.try_into().unwrap());if !v.is_finite(){return Err(io::Error::new(io::ErrorKind::InvalidData,"Nonfinite diffusion input"));}}
   for x in 0..side{row[x]=Complex::new(source_row[reflect(x0 as isize+x as isize-kernel.radius as isize,w)],0.);}write_complex(&mut file,&row,&mut bytes)?;}}
  forward(&a,&b,side,&mut cancelled)?;
  {let mut image=OpenOptions::new().read(true).write(true).open(&b)?;let mut ker=File::open(&k)?;
   for y in 0..side{check(&mut cancelled)?;image.seek(SeekFrom::Start((y*side*16)as u64))?;read_complex(&mut image,&mut row,&mut bytes)?;read_complex(&mut ker,&mut other,&mut otherbytes)?;for(v,q)in row.iter_mut().zip(&other){*v*=*q;}image.seek(SeekFrom::Start((y*side*16)as u64))?;write_complex(&mut image,&row,&mut bytes)?;}}
  // Inverse starts in transposed orientation; transpose back after first axis.
  transform_rows(&b,side,true,&mut cancelled)?;transpose(&b,&a,side,&mut cancelled)?;transform_rows(&a,side,true,&mut cancelled)?;
  let mut conv=File::open(&a)?;let norm=1./(side as f64*side as f64);let mut outbytes=vec![0u8;ww*8];
  for y in 0..hh{check(&mut cancelled)?;conv.seek(SeekFrom::Start((((y+2*kernel.radius)*side+2*kernel.radius)*16)as u64))?;read_complex(&mut conv,&mut row[..ww],&mut bytes[..ww*16])?;
   src.seek(SeekFrom::Start((((y0+y)*w+x0)*8)as u64))?;src.read_exact(&mut srcbytes[..ww*8])?;
   for x in 0..ww{let original=f64::from_le_bytes(srcbytes[x*8..(x+1)*8].try_into().unwrap());let value=(1.-kernel.scatter)*original+kernel.scatter*row[x].re*norm;if !value.is_finite(){return Err(io::Error::new(io::ErrorKind::InvalidData,"Nonfinite diffusion result"));}outbytes[x*8..(x+1)*8].copy_from_slice(&value.to_le_bytes());}
   out.seek(SeekFrom::Start((((y0+y)*w+x0)*8)as u64))?;out.write_all(&outbytes)?;}
  completed+=(ww*hh)as u64;tiles+=1;progress(completed,n);
 }}
 out.flush()?;check(&mut cancelled)?;output_guard.keep=true;
 Ok(DiffusionMetrics{peak_scratch_bytes:3*fftbytes+n*8,explicit_buffer_bytes:((TRANSPOSE_TILE*TRANSPOSE_TILE*16+side*160+w*16)as u64).max(1024*1024),tiles,fft_side:side})
}

#[cfg(test)] mod tests {
 use super::*;
 use spektrafilm_model::diffusion::{DiffusionFilter,apply_diffusion_filter_um};
 use spektrafilm_math::{image::ImageBuf,precision::from_f64};
 fn config(family:&str)->DiffusionFilter<'_>{DiffusionFilter{family,strength:0.5,spatial_scale:1.,halo_warmth:0.,core_intensity:1.,core_size:1.,halo_intensity:1.,halo_size:1.,bloom_intensity:1.,bloom_size:1.}}
 fn folder(label:&str)->PathBuf{let p=std::env::temp_dir().join(format!("film-diffusion-{}-{}",std::process::id(),label));let _=fs::remove_dir_all(&p);fs::create_dir(&p).unwrap();p}
 #[test] fn kernel_spectrum_copy_cancels_between_bounded_chunks(){
  let root=folder("copy-cancel");let _guard=DirGuard(root.clone());let src=root.join("src");let dst=root.join("dst");
  let data=vec![93u8;3*1024*1024];fs::write(&src,&data).unwrap();fs::write(&dst,[]).unwrap();let mut calls=0;
  let result=copy_spectrum(&src,&dst,&mut ||{calls+=1;calls>1});assert_eq!(result.unwrap_err().kind(),io::ErrorKind::Interrupted);
  assert_eq!(fs::metadata(&dst).unwrap().len(),1024*1024);assert_eq!(fs::read(&src).unwrap(),data);
  copy_spectrum(&src,&dst,&mut ||false).unwrap();assert_eq!(fs::read(&dst).unwrap(),data);
 }
 #[test] fn exact_disk_matches_whole_frame(){
  for family in ["glimmerglass","black_pro_mist","pro_mist","cinebloom"]{for(w,h)in[(17,13),(4,4),(3,7),(1,7),(7,1),(1,1),(2,5)]{
   let root=folder(&format!("{family}-{w}-{h}"));let _guard=DirGuard(root.clone());
   let image=ImageBuf::from_data(w,h,(0..w*h*3).map(|i|from_f64(((i*19)%139)as f64/17.)).collect());
   let df=config(family);let expected=apply_diffusion_filter_um(&image,&df,10.);let kernel=ExactDiffusionKernel::new(&df,10.,w as usize,h as usize).unwrap();
   for ch in 0..3{let input=root.join(format!("i-{ch}"));let output=root.join(format!("o-{ch}"));let mut f=File::create(&input).unwrap();for px in image.data.chunks_exact(3){f.write_all(&(px[ch]as f64).to_le_bytes()).unwrap();}drop(f);
    let measured=apply_plane(&input,&output,&root.join(format!("work-{ch}")),w,h,&kernel,ch,||false,|_,_|{}).unwrap();
    assert!(measured.explicit_buffer_bytes>=1024*1024);
    let b=fs::read(&output).unwrap();let got:Vec<f64>=b.chunks_exact(8).map(|v|f64::from_le_bytes(v.try_into().unwrap())).collect();
    let error=got.iter().zip(expected.data.chunks_exact(3)).map(|(a,p)|(a-p[ch]as f64).abs()).fold(0.,f64::max);assert!(error<1e-10,"{family} {w}x{h} ch{ch}: {error:e}");
   }
  }}
 }
 #[test] fn cancellation_cleans_owned_output_and_work(){
  let root=folder("cancel");let _guard=DirGuard(root.clone());let input=root.join("input");let output=root.join("output");let work=root.join("work");fs::write(&input,vec![0u8;32*32*8]).unwrap();let kernel=ExactDiffusionKernel::new(&config("black_pro_mist"),20.,32,32).unwrap();let mut calls=0;
  let err=apply_plane(&input,&output,&work,32,32,&kernel,0,||{calls+=1;calls>20},|_,_|{}).unwrap_err();assert_eq!(err.kind(),io::ErrorKind::Interrupted);assert!(input.exists());assert!(!output.exists());assert!(!work.exists());
 }
 #[test] fn global_reflect_degenerate_and_borders(){assert_eq!(reflect(-99,1),0);assert_eq!(reflect(-1,4),1);assert_eq!(reflect(4,4),2);assert_eq!(reflect(7,4),1);}
 #[test] fn overlap_save_seam_matches_exact_reference(){
  let(w,h)=(47u32,29u32);let root=folder("seam");let _guard=DirGuard(root.clone());let input=root.join("input");let output=root.join("output");
  let image=ImageBuf::from_data(w,h,(0..w*h*3).map(|i|from_f64(((i*37)%251)as f64/13.)).collect());let df=config("black_pro_mist");
  let kernel=ExactDiffusionKernel::new(&df,10.,w as usize,h as usize).unwrap();let expected=apply_diffusion_filter_um(&image,&df,10.);
  let mut f=File::create(&input).unwrap();for px in image.data.chunks_exact(3){f.write_all(&(px[1]as f64).to_le_bytes()).unwrap();}drop(f);
  let metrics=apply_plane_with_core(&input,&output,&root.join("work"),w,h,&kernel,1,16,||false,|_,_|{}).unwrap();assert!(metrics.tiles>=4);
  let bytes=fs::read(output).unwrap();let max=bytes.chunks_exact(8).zip(expected.data.chunks_exact(3)).map(|(v,p)|(f64::from_le_bytes(v.try_into().unwrap())-p[1]as f64).abs()).fold(0.,f64::max);assert!(max<1e-10,"seam {max:e}");
 }

 #[test] fn single_pixel_axis_constant_stays_constant(){
  let root=folder("single");let _guard=DirGuard(root.clone());let input=root.join("input");let output=root.join("output");fs::write(&input,(0..7).flat_map(|_|2.5f64.to_le_bytes()).collect::<Vec<_>>()).unwrap();
  let kernel=ExactDiffusionKernel::new(&config("black_pro_mist"),10.,1,7).unwrap();apply_plane(&input,&output,&root.join("work"),1,7,&kernel,0,||false,|_,_|{}).unwrap();
  assert!(fs::read(output).unwrap().chunks_exact(8).all(|v|(f64::from_le_bytes(v.try_into().unwrap())-2.5).abs()<1e-12));
 }
 #[test] fn in_memory_tiled_matches_whole_frame(){
  let(w,h)=(47u32,29u32);let root=folder("mem-seam");let _guard=DirGuard(root.clone());let input=root.join("input");let output=root.join("output");
  let image=ImageBuf::from_data(w,h,(0..w*h*3).map(|i|from_f64(((i*37)%251)as f64/13.)).collect());let df=config("black_pro_mist");
  let kernel=ExactDiffusionKernel::new(&df,10.,w as usize,h as usize).unwrap();let expected=apply_diffusion_filter_um(&image,&df,10.);
  let mut f=File::create(&input).unwrap();for px in image.data.chunks_exact(3){f.write_all(&(px[2]as f64).to_le_bytes()).unwrap();}drop(f);
  let metrics=apply_plane_in_memory(&input,&output,w,h,&kernel,2,16,||false,|_,_|{}).unwrap();assert!(metrics.tiles>=4);
  let bytes=fs::read(output).unwrap();let max=bytes.chunks_exact(8).zip(expected.data.chunks_exact(3)).map(|(v,p)|(f64::from_le_bytes(v.try_into().unwrap())-p[2]as f64).abs()).fold(0.,f64::max);assert!(max<1e-10,"in-memory seam {max:e}");
 }
 #[test] fn in_memory_matches_disk_path(){
  let(w,h)=(38u32,26u32);let root=folder("mem-vs-disk");let _guard=DirGuard(root.clone());
  let image=ImageBuf::from_data(w,h,(0..w*h*3).map(|i|from_f64(((i*23)%197)as f64/11.)).collect());let df=config("pro_mist");
  let kernel=ExactDiffusionKernel::new(&df,10.,w as usize,h as usize).unwrap();
  let input=root.join("input");let mut f=File::create(&input).unwrap();for px in image.data.chunks_exact(3){f.write_all(&(px[0]as f64).to_le_bytes()).unwrap();}drop(f);
  let mem_out=root.join("mem");apply_plane_in_memory(&input,&mem_out,w,h,&kernel,0,16,||false,|_,_|{}).unwrap();
  let disk_out=root.join("disk");apply_plane_with_core(&input,&disk_out,&root.join("work"),w,h,&kernel,0,16,||false,|_,_|{}).unwrap();
  let a=fs::read(mem_out).unwrap();let b=fs::read(disk_out).unwrap();
  let max=a.chunks_exact(8).zip(b.chunks_exact(8)).map(|(x,y)|(f64::from_le_bytes(x.try_into().unwrap())-f64::from_le_bytes(y.try_into().unwrap())).abs()).fold(0.,f64::max);assert!(max<1e-12,"mem vs disk {max:e}");
 }
 #[test] fn memory_fft_budget_is_never_raised(){assert!(choose_in_memory_core(64,2048,1024,0).is_none());let low=choose_in_memory_core(64,2048,1024,64*1024*1024).unwrap();let high=choose_in_memory_core(64,2048,1024,256*1024*1024).unwrap();assert!(low<high);assert!(memory_fft_need(next_fast(128+low),2048,2048*1024).unwrap()<=64*1024*1024);}
 #[test] fn kernel_normalization_cancels(){let mut calls=0;let result=ExactDiffusionKernel::new_cancellable(&config("black_pro_mist"),5.,8192,6144,||{calls+=1;calls>2});assert!(matches!(result,Err("CANCELLED")));}

}
