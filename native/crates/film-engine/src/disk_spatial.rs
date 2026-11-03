//! Exact full-image Gaussian passes on local planar little-endian f64 files.
//!
//! The physical operator is the existing FIR/Young-van Vliet IIR, including its
//! whole-image boundary initialization. Only storage/layout changes. A row pass
//! writes one full-plane temporary; full-column strips then produce the output.
//! No finite halo, downsampling or per-pixel filesystem seek is involved.
use std::{fs::{File, OpenOptions}, io::{self, Read, Seek, SeekFrom, Write}, path::{Path, PathBuf}, sync::atomic::{AtomicU64, Ordering}};
use spektrafilm_math::{gaussian::gaussian_blur_rows, precision::Scalar};

#[derive(Clone, Copy, Debug, serde::Serialize)]
pub struct DiskProgress { pub pass: &'static str, pub completed: u64, pub total: u64 }
#[derive(Clone, Copy, Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiskMetrics {
    /// Extra intermediate storage; excludes caller input and final output.
    pub scratch_bytes: u64,
    pub input_bytes: u64,
    pub output_bytes: u64,
    /// Maximum explicitly owned sample/byte buffers; not process RSS. Small
    /// filter kernels, Rayon scheduling and filesystem cache are not included.
    pub peak_buffer_bytes: u64,
    pub bytes_read: u64,
    pub bytes_written: u64,
    pub row_batch_rows: u32,
    pub column_strip_columns: u32,
}

static NEXT_TEMP: AtomicU64 = AtomicU64::new(0);
struct Cleanup { path: PathBuf, keep: bool }
impl Cleanup { fn new(path: &Path) -> Self { Self { path: path.to_path_buf(), keep: false } } }
impl Drop for Cleanup { fn drop(&mut self) { if !self.keep { let _ = std::fs::remove_file(&self.path); } } }
fn invalid(message: &'static str) -> io::Error { io::Error::new(io::ErrorKind::InvalidInput, message) }
fn cancelled_error() -> io::Error { io::Error::new(io::ErrorKind::Interrupted, "Spatial pass cancelled") }
fn check_cancel(cancelled: &mut impl FnMut() -> bool) -> io::Result<()> { if cancelled() { Err(cancelled_error()) } else { Ok(()) } }
fn decode(src: &[u8], dst: &mut [Scalar]) -> io::Result<()> {
    debug_assert_eq!(src.len(), dst.len() * 8);
    for (bytes, value) in src.chunks_exact(8).zip(dst) {
        let x = f64::from_le_bytes(bytes.try_into().unwrap());
        if !x.is_finite() { return Err(io::Error::new(io::ErrorKind::InvalidData, "Nonfinite spatial input")); }
        *value = x as Scalar;
    }
    Ok(())
}
fn encode(src: &[Scalar], dst: &mut [u8]) -> io::Result<()> {
    debug_assert_eq!(dst.len(), src.len() * 8);
    for (&value, bytes) in src.iter().zip(dst.chunks_exact_mut(8)) {
        if !value.is_finite() { return Err(io::Error::new(io::ErrorKind::InvalidData, "Nonfinite spatial output")); }
        bytes.copy_from_slice(&(value as f64).to_le_bytes());
    }
    Ok(())
}
fn new_temp(scratch_dir: &Path) -> io::Result<(File, Cleanup)> {
    for _ in 0..64 {
        let serial = NEXT_TEMP.fetch_add(1, Ordering::Relaxed);
        let path = scratch_dir.join(format!("spatial-{}-{serial}.f64le", std::process::id()));
        match OpenOptions::new().read(true).write(true).create_new(true).open(&path) {
            Ok(file) => return Ok((file, Cleanup::new(&path))),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e),
        }
    }
    Err(io::Error::new(io::ErrorKind::AlreadyExists, "Spatial scratch names exhausted"))
}

/// Process one planar f64 image. Input/output/scratch are trusted app-private
/// paths validated by the caller. Output must not exist. On error, cancellation
/// or unwind, all newly-created output/scratch files are removed; input remains.
///
/// `memory_bytes` limits the explicit sample/byte buffers (typically16MiB).
/// Positive sigma requires one extra `width*height*8` scratch file. Total live
/// storage including input and output is3 planes, not an unbounded tile cache.
#[allow(clippy::too_many_arguments)]
pub fn gaussian_file(
    input_path: &Path, output_path: &Path, width: u32, height: u32, sigma: f32,
    scratch_dir: &Path, memory_bytes: usize,
    cancelled: &mut impl FnMut() -> bool,
    progress: &mut impl FnMut(DiskProgress),
) -> io::Result<DiskMetrics> {
    if !cfg!(feature = "precision-f64") { return Err(io::Error::new(io::ErrorKind::Unsupported, "Disk spatial passes require precision-f64")); }
    if width == 0 || height == 0 || width > 40000 || height > 40000 { return Err(invalid("Spatial dimensions must be1..40000")); }
    if !sigma.is_finite() || sigma < 0.0 { return Err(invalid("Sigma must be finite and nonnegative")); }
    if input_path == output_path { return Err(invalid("Spatial input and output must differ")); }
    let w = width as usize; let h = height as usize;
    let samples = (width as u64).checked_mul(height as u64).ok_or_else(|| invalid("Spatial size overflow"))?;
    let plane_bytes = samples.checked_mul(8).ok_or_else(|| invalid("Spatial byte count overflow"))?;
    let row_work_bytes = w.checked_mul(24).ok_or_else(|| invalid("Spatial row budget overflow"))?;
    let column_work_bytes = h.checked_mul(16).and_then(|n|n.checked_add(8)).ok_or_else(|| invalid("Spatial column budget overflow"))?;
    if memory_bytes > 64*1024*1024 { return Err(invalid("Spatial explicit buffer budget must not exceed64MiB")); }
    if memory_bytes < row_work_bytes || (sigma > 0.0 && memory_bytes < column_work_bytes) { return Err(invalid("Spatial working budget cannot fit one complete row/column")); }
    check_cancel(cancelled)?;
    let mut input = File::open(input_path)?;
    if !input.metadata()?.is_file() || input.metadata()?.len() != plane_bytes { return Err(invalid("Spatial input size must equal width*height*8")); }
    let mut output = OpenOptions::new().write(true).create_new(true).open(output_path)?;
    let mut output_guard = Cleanup::new(output_path);
    output.set_len(plane_bytes)?;
    let row_batch = (memory_bytes / row_work_bytes).min(h).max(1);
    let row_samples = row_batch * w;
    let mut byte_buffer = vec![0u8; row_samples * 8];
    let mut src = vec![0.0 as Scalar; row_samples];
    let mut dst = vec![0.0 as Scalar; row_samples];
    let mut metrics = DiskMetrics {
        scratch_bytes: if sigma > 0.0 { plane_bytes } else { 0 },
        input_bytes: plane_bytes, output_bytes: plane_bytes,
        peak_buffer_bytes: (row_samples * 24) as u64,
        bytes_read: 0, bytes_written: 0,
        row_batch_rows: row_batch as u32, column_strip_columns: 0,
    };
    if sigma == 0.0 {
        progress(DiskProgress { pass: "identity", completed: 0, total: height as u64 });
        for y in (0..h).step_by(row_batch) {
            check_cancel(cancelled)?;
            let rows = row_batch.min(h-y); let len = rows*w;
            input.read_exact(&mut byte_buffer[..len*8])?;
            decode(&byte_buffer[..len*8], &mut src[..len])?;
            output.write_all(&byte_buffer[..len*8])?;
            metrics.bytes_read += (len*8) as u64; metrics.bytes_written += (len*8) as u64;
            progress(DiskProgress { pass: "identity", completed: (y+rows) as u64, total: height as u64 });
        }
        check_cancel(cancelled)?; output.flush()?; drop(output); output_guard.keep = true;
        return Ok(metrics);
    }
    let (mut horizontal, horizontal_guard) = new_temp(scratch_dir)?;
    horizontal.set_len(plane_bytes)?;
    progress(DiskProgress { pass: "horizontal", completed: 0, total: height as u64 });
    for y in (0..h).step_by(row_batch) {
        check_cancel(cancelled)?;
        let rows = row_batch.min(h-y); let len = rows*w;
        input.read_exact(&mut byte_buffer[..len*8])?;
        decode(&byte_buffer[..len*8], &mut src[..len])?;
        gaussian_blur_rows(&src[..len], &mut dst[..len], w, sigma);
        encode(&dst[..len], &mut byte_buffer[..len*8])?;
        horizontal.write_all(&byte_buffer[..len*8])?;
        metrics.bytes_read += (len*8) as u64; metrics.bytes_written += (len*8) as u64;
        progress(DiskProgress { pass: "horizontal", completed: (y+rows) as u64, total: height as u64 });
    }
    horizontal.flush()?;
    drop(input); drop(src); drop(dst); drop(byte_buffer);
    let strip_columns = (memory_bytes / column_work_bytes).min(w).max(1);
    let strip_samples = strip_columns * h;
    let mut columns = vec![0.0 as Scalar; strip_samples];
    let mut filtered = vec![0.0 as Scalar; strip_samples];
    let mut row_bytes = vec![0u8; strip_columns*8];
    metrics.column_strip_columns = strip_columns as u32;
    metrics.peak_buffer_bytes = metrics.peak_buffer_bytes.max((strip_samples*16+strip_columns*8) as u64);
    progress(DiskProgress { pass: "vertical", completed: 0, total: width as u64 });
    for left in (0..w).step_by(strip_columns) {
        check_cancel(cancelled)?;
        let cols = strip_columns.min(w-left);
        for y in 0..h {
            check_cancel(cancelled)?;
            horizontal.seek(SeekFrom::Start(((y as u64)*(width as u64)+left as u64)*8))?;
            horizontal.read_exact(&mut row_bytes[..cols*8])?;
            for x in 0..cols { columns[x*h+y] = f64::from_le_bytes(row_bytes[x*8..x*8+8].try_into().unwrap()) as Scalar; }
        }
        metrics.bytes_read += (cols*h*8) as u64;
        // Each contiguous row is a complete ORIGINAL image column, so the
        // IIR forward/backward state is initialized at true image boundaries.
        gaussian_blur_rows(&columns[..cols*h], &mut filtered[..cols*h], h, sigma);
        for y in 0..h {
            check_cancel(cancelled)?;
            for x in 0..cols {
                let value = filtered[x*h+y];
                if !value.is_finite() { return Err(io::Error::new(io::ErrorKind::InvalidData, "Nonfinite spatial output")); }
                row_bytes[x*8..x*8+8].copy_from_slice(&(value as f64).to_le_bytes());
            }
            output.seek(SeekFrom::Start(((y as u64)*(width as u64)+left as u64)*8))?;
            output.write_all(&row_bytes[..cols*8])?;
        }
        metrics.bytes_written += (cols*h*8) as u64;
        progress(DiskProgress { pass: "vertical", completed: (left+cols) as u64, total: width as u64 });
    }
    check_cancel(cancelled)?; output.flush()?;
    drop(horizontal); drop(horizontal_guard); drop(output); output_guard.keep = true;
    Ok(metrics)
}

#[derive(Debug)]
struct FusedAccumulationNonfinite;
impl std::fmt::Display for FusedAccumulationNonfinite{fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{write!(f,"Nonfinite intermediate")}}
impl std::error::Error for FusedAccumulationNonfinite{}
pub(crate) fn fused_accumulation_nonfinite(e:&io::Error)->bool{e.get_ref().map_or(false,|v|v.is::<FusedAccumulationNonfinite>())}
fn fused_accumulate(a:f64,amp:f64,v:f64)->io::Result<f64>{let z=a+amp*v;if z.is_finite(){Ok(z)}else{Err(io::Error::new(io::ErrorKind::InvalidData,FusedAccumulationNonfinite))}}

/// Opt-in exact: identical three Gaussian branches and ordered accumulation, with
/// only the horizontal branch planes materialized. Explicit buffers <= budget.
#[allow(clippy::too_many_arguments)]
pub fn exponential_file_fused(
 input_path:&Path,output_path:&Path,width:u32,height:u32,decay:f32,
 scratch_dir:&Path,memory_bytes:usize,cancelled:&mut impl FnMut()->bool,
 progress:&mut impl FnMut(DiskProgress),
)->io::Result<DiskMetrics>{
 if !cfg!(feature="precision-f64"){return Err(io::Error::new(io::ErrorKind::Unsupported,"Requires f64"));}
 if width==0||height==0||width>40000||height>40000{return Err(invalid("Invalid dimensions"));}
 if !decay.is_finite()||decay<0. {return Err(invalid("Invalid decay"));}
 if input_path==output_path{return Err(invalid("Paths must differ"));}
 let(w,h)=(width as usize,height as usize);let plane=width as u64*height as u64*8;
 let row_work=w*24;let column_work=h*24+8;
 if memory_bytes>64*1024*1024||memory_bytes<row_work||memory_bytes<column_work{return Err(invalid("Invalid fused spatial memory budget"));}
 let fits=[(0.1633f64,0.5360f64),(0.6496,1.5236),(0.1870,2.7684)];
 // Same cast sites and expression as Scratch::exponential.
 let sigmas=fits.map(|(_,ratio)|(ratio*decay as f64)as f32);
 if sigmas.iter().any(|s|!s.is_finite()){return Err(invalid("Nonfinite component sigma"));}
 check_cancel(cancelled)?;let mut input=File::open(input_path)?;
 if !input.metadata()?.is_file()||input.metadata()?.len()!=plane{return Err(invalid("Invalid source size"));}
 let mut output=OpenOptions::new().write(true).create_new(true).open(output_path)?;
 let mut guard=Cleanup::new(output_path);output.set_len(plane)?;
 let mut intermediates=Vec::new();
 for _ in 0..3{check_cancel(cancelled)?;let(f,g)=new_temp(scratch_dir)?;f.set_len(plane)?;intermediates.push((f,g));}
 let row_batch=(memory_bytes/row_work).min(h).max(1);let samples=row_batch*w;
 let mut bytes=vec![0u8;samples*8];let mut src=vec![0. as Scalar;samples];let mut dst=vec![0. as Scalar;samples];
 let mut m=DiskMetrics{scratch_bytes:3*plane,input_bytes:plane,output_bytes:plane,peak_buffer_bytes:(samples*24)as u64,bytes_read:0,bytes_written:0,row_batch_rows:row_batch as u32,column_strip_columns:0};
 progress(DiskProgress{pass:"horizontal",completed:0,total:height as u64});
 for y in(0..h).step_by(row_batch){check_cancel(cancelled)?;let rows=row_batch.min(h-y);let len=rows*w;
  input.read_exact(&mut bytes[..len*8])?;decode(&bytes[..len*8],&mut src[..len])?;m.bytes_read+=(len*8)as u64;
  for j in 0..3{check_cancel(cancelled)?;gaussian_blur_rows(&src[..len],&mut dst[..len],w,sigmas[j]);encode(&dst[..len],&mut bytes[..len*8])?;intermediates[j].0.write_all(&bytes[..len*8])?;m.bytes_written+=(len*8)as u64;}
  progress(DiskProgress{pass:"horizontal",completed:(y+rows)as u64,total:height as u64});
 }
 for(f,_)in &mut intermediates{f.flush()?;}
 drop(input);drop(bytes);drop(src);drop(dst);
 let strip=(memory_bytes/column_work).min(w).max(1);let samples=strip*h;
 let mut columns=vec![0. as Scalar;samples];let mut filtered=vec![0. as Scalar;samples];let mut accum=vec![0f64;samples];let mut row_bytes=vec![0u8;strip*8];
 m.column_strip_columns=strip as u32;m.peak_buffer_bytes=m.peak_buffer_bytes.max((samples*24+strip*8)as u64);
 progress(DiskProgress{pass:"vertical",completed:0,total:width as u64});
 for left in(0..w).step_by(strip){check_cancel(cancelled)?;let cols=strip.min(w-left);let len=cols*h;accum[..len].fill(0.);
  // Keep original component order and per-pixel a + amp * value expression.
  for j in 0..3{
   for y in 0..h{check_cancel(cancelled)?;let f=&mut intermediates[j].0;f.seek(SeekFrom::Start(((y*w+left)*8)as u64))?;f.read_exact(&mut row_bytes[..cols*8])?;
    for x in 0..cols{columns[x*h+y]=f64::from_le_bytes(row_bytes[x*8..x*8+8].try_into().unwrap())as Scalar;}
   }m.bytes_read+=(len*8)as u64;
   gaussian_blur_rows(&columns[..len],&mut filtered[..len],h,sigmas[j]);
   for block in(0..len).step_by(32768){check_cancel(cancelled)?;let end=(block+32768).min(len);
    for i in block..end{let v=filtered[i]as f64;if !v.is_finite(){return Err(io::Error::new(io::ErrorKind::InvalidData,"Nonfinite spatial output"));}
     accum[i]=fused_accumulate(accum[i],fits[j].0,v)?;
    }
   }
  }
  for y in 0..h{check_cancel(cancelled)?;for x in 0..cols{row_bytes[x*8..x*8+8].copy_from_slice(&accum[x*h+y].to_le_bytes());}output.seek(SeekFrom::Start(((y*w+left)*8)as u64))?;output.write_all(&row_bytes[..cols*8])?;}
  m.bytes_written+=(len*8)as u64;progress(DiskProgress{pass:"vertical",completed:(left+cols)as u64,total:width as u64});
 }
 check_cancel(cancelled)?;output.flush()?;drop(intermediates);drop(output);guard.keep=true;Ok(m)
}

#[cfg(test)] mod fused_tests{
 use super::*;
 fn folder()->PathBuf{let p=std::env::temp_dir().join(format!("film-fused-{}-{}",std::process::id(),NEXT_TEMP.fetch_add(1,Ordering::Relaxed)));std::fs::create_dir(&p).unwrap();p}
 fn values(path:&Path)->Vec<f64>{std::fs::read(path).unwrap().chunks_exact(8).map(|v|f64::from_le_bytes(v.try_into().unwrap())).collect()}
 #[test] fn fused_exponential_same_bits_and_budget(){
  for(w,h)in[(1u32,1u32),(1,31),(43,1),(47,29),(131,97)]{for decay in [0f32,0.1,1.,3./0.5360,3./1.5236,3./2.7684,20.,100.] {
   let root=folder();let input=root.join("input");let output=root.join("output");let n=(w*h)as usize;
   let source:Vec<f64>=(0..n).map(|i|if i%41==0{1e5}else if i%19==0{-0.}else{((i*37)%251)as f64/13.-3.}).collect();std::fs::write(&input,source.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
   // Tiny budget forces multiple row batches/column strips, not whole frame.
   let budget=(w as usize*24).max(h as usize*24+8)*2;
   let mut expected=vec![0f64;n];
   for(j,(amp,ratio))in [(0.1633f64,0.5360f64),(0.6496,1.5236),(0.1870,2.7684)].into_iter().enumerate(){let p=root.join(format!("reference-{j}"));gaussian_file(&input,&p,w,h,(ratio*decay as f64)as f32,&root,budget,&mut||false,&mut|_|{}).unwrap();for(a,v)in expected.iter_mut().zip(values(&p)){*a=*a+amp*v;}std::fs::remove_file(p).unwrap();}
   let m=exponential_file_fused(&input,&output,w,h,decay,&root,budget,&mut||false,&mut|_|{}).unwrap();assert!(m.peak_buffer_bytes<=budget as u64);assert_eq!(m.bytes_read,4*n as u64*8);assert_eq!(m.bytes_written,4*n as u64*8);
   for(i,(a,b))in values(&output).into_iter().zip(expected).enumerate(){assert_eq!(a.to_bits(),b.to_bits(),"{w}x{h} decay={decay} i={i}");}
   assert_eq!(std::fs::read_dir(&root).unwrap().count(),2);std::fs::remove_dir_all(root).unwrap();
  }}
 }
 #[test] fn cancellation_cleans_both_phases_and_keeps_source(){
  for phase in ["horizontal","vertical"] {let root=folder();let input=root.join("input");let output=root.join("output");let data=vec![0u8;31*19*8];std::fs::write(&input,&data).unwrap();let armed=std::cell::Cell::new(false);
   let e=exponential_file_fused(&input,&output,31,19,4.,&root,4096,&mut||armed.get(),&mut|p|{if p.pass==phase&&p.completed>0{armed.set(true);}}).unwrap_err();assert_eq!(e.kind(),io::ErrorKind::Interrupted);assert!(!output.exists());assert_eq!(std::fs::read(&input).unwrap(),data);assert_eq!(std::fs::read_dir(&root).unwrap().count(),1);std::fs::remove_dir_all(root).unwrap();
  }
 }
}

#[cfg(test)] mod fused_edge_tests{
 use super::*;
 fn folder()->PathBuf{let p=std::env::temp_dir().join(format!("film-fused-edge-{}-{}",std::process::id(),NEXT_TEMP.fetch_add(1,Ordering::Relaxed)));std::fs::create_dir(&p).unwrap();p}
 #[test] fn signed_zero_subnormal_and_sigma_boundary_bits(){
  let mut decays=vec![0f32];for ratio in [0.5360f64,1.5236,2.7684]{let d=(3./ratio)as f32;decays.extend([f32::from_bits(d.to_bits()-1),d,f32::from_bits(d.to_bits()+1)]);}
  for value in [0f64,-0.,f64::from_bits(1),-f64::from_bits(1),f64::MIN_POSITIVE,-f64::MIN_POSITIVE] {for &decay in &decays{
   let root=folder();let input=root.join("input");let output=root.join("output");let n=13*7;let source=vec![value;n];std::fs::write(&input,source.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();let mut expected=vec![0f64;n];
   for(j,(amp,ratio))in [(0.1633f64,0.5360f64),(0.6496,1.5236),(0.1870,2.7684)].into_iter().enumerate(){let p=root.join(format!("ref-{j}"));gaussian_file(&input,&p,13,7,(ratio*decay as f64)as f32,&root,1024,&mut||false,&mut|_|{}).unwrap();for(a,v)in expected.iter_mut().zip(std::fs::read(&p).unwrap().chunks_exact(8)){*a=*a+amp*f64::from_le_bytes(v.try_into().unwrap());}std::fs::remove_file(p).unwrap();}
   exponential_file_fused(&input,&output,13,7,decay,&root,1024,&mut||false,&mut|_|{}).unwrap();let actual=std::fs::read(&output).unwrap();for(a,b)in actual.chunks_exact(8).zip(expected){assert_eq!(u64::from_le_bytes(a.try_into().unwrap()),b.to_bits(),"value={value:?} decay={decay}");}std::fs::remove_dir_all(root).unwrap();
  }}
 }
 #[test] fn input_nonfinite_cleanup_and_distinct_accumulation_error(){
  for value in [f64::NAN,f64::INFINITY,f64::NEG_INFINITY]{let root=folder();let input=root.join("input");let output=root.join("output");let data=value.to_le_bytes();std::fs::write(&input,data).unwrap();let e=exponential_file_fused(&input,&output,1,1,1.,&root,1024,&mut||false,&mut|_|{}).unwrap_err();assert_eq!(e.kind(),io::ErrorKind::InvalidData);assert!(!fused_accumulation_nonfinite(&e));assert!(!output.exists());assert_eq!(std::fs::read(&input).unwrap(),data);assert_eq!(std::fs::read_dir(&root).unwrap().count(),1);std::fs::remove_dir_all(root).unwrap();}
  let e=fused_accumulate(f64::MAX,1.,f64::MAX).unwrap_err();assert!(fused_accumulation_nonfinite(&e));
 }
 #[test] fn finite_extreme_preserves_gaussian_error_and_cleanup(){
  for decay in [0f32,1.,20.] {let root=folder();let input=root.join("input");std::fs::write(&input,(0..17*9).flat_map(|i|if i%2==0{f64::MAX.to_le_bytes()}else{(-f64::MAX).to_le_bytes()}).collect::<Vec<_>>()).unwrap();let output=root.join("output");let mut expected=vec![0f64;17*9];let mut reference_error=None;
   for(j,(amp,ratio))in [(0.1633f64,0.5360f64),(0.6496,1.5236),(0.1870,2.7684)].into_iter().enumerate(){let p=root.join(format!("reference-{j}"));match gaussian_file(&input,&p,17,9,(ratio*decay as f64)as f32,&root,4096,&mut||false,&mut|_|{}){Err(e)=>{reference_error=Some((e.kind(),false));break;},Ok(_)=>{for(a,v)in expected.iter_mut().zip(std::fs::read(&p).unwrap().chunks_exact(8)){*a=*a+amp*f64::from_le_bytes(v.try_into().unwrap());if !a.is_finite(){reference_error=Some((io::ErrorKind::InvalidData,true));}}std::fs::remove_file(p).unwrap();if reference_error.is_some(){break;}}}}
   let result=exponential_file_fused(&input,&output,17,9,decay,&root,4096,&mut||false,&mut|_|{});
   if let Some((kind,accumulation))=reference_error{let e=result.unwrap_err();assert_eq!(e.kind(),kind);assert_eq!(fused_accumulation_nonfinite(&e),accumulation);assert!(!output.exists());}else{result.unwrap();for(a,b)in std::fs::read(&output).unwrap().chunks_exact(8).zip(expected){assert_eq!(u64::from_le_bytes(a.try_into().unwrap()),b.to_bits());}std::fs::remove_file(output).unwrap();}assert_eq!(std::fs::read_dir(&root).unwrap().count(),1);std::fs::remove_dir_all(root).unwrap();
  }
 }
 #[test] fn maximum_width_budget_and_progress(){
  let root=folder();let input=root.join("input");let output=root.join("output");std::fs::write(&input,vec![0u8;40000*8]).unwrap();let budget=16*1024*1024;let mut previous=0;
  let m=exponential_file_fused(&input,&output,40000,1,2.,&root,budget,&mut||false,&mut|p|{let done=if p.pass=="horizontal"{p.completed*40000}else{40000+p.completed};assert!(done>=previous);previous=done;}).unwrap();assert_eq!(previous,80000);assert!(m.peak_buffer_bytes<=budget as u64);std::fs::remove_dir_all(root).unwrap();
 }
}
