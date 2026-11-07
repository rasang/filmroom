//! Bounded whole-image reductions and stochastic fields for the streamed path.
//! These operators preserve original sampling/order; they do not meter a tile
//! independently or reseed RNGs at chunk boundaries.
use std::{fs::{self,File,OpenOptions},io::{self,Read,Write,Seek,SeekFrom},path::{Path,PathBuf}};
use spektrafilm_math::{image::ImageBuf,precision::from_f64};
const BLOCK:usize=32768;
fn check(c:&mut impl FnMut()->bool)->io::Result<()>{if c(){Err(io::Error::new(io::ErrorKind::Interrupted,"Global stage cancelled"))}else{Ok(())}}
fn invalid(s:&str)->io::Error{io::Error::new(io::ErrorKind::InvalidInput,s)}
fn read64(file:&mut File,n:usize)->io::Result<Vec<f64>>{let mut b=vec![0u8;n*8];file.read_exact(&mut b)?;b.chunks_exact(8).map(|x|{let v=f64::from_le_bytes(x.try_into().unwrap());if v.is_finite(){Ok(v)}else{Err(invalid("Nonfinite plane"))}}).collect()}
fn write64(file:&mut File,values:&[f64])->io::Result<()>{let mut b=Vec::with_capacity(values.len()*8);for &v in values{if !v.is_finite(){return Err(invalid("Nonfinite generated value"));}b.extend_from_slice(&v.to_le_bytes());}file.write_all(&b)}
struct Output{path:PathBuf,keep:bool}impl Drop for Output{fn drop(&mut self){if !self.keep{let _=fs::remove_file(&self.path);}}}

/// Meter linear interleaved float32 RGB with the original ≤256 nearest grid.
/// Pass the same input-color-space matrix as filming::expose. The returned EV
/// must be applied as its own f32 powf scale BEFORE exposure compensation.
pub fn auto_exposure_ev(input:&Path,width:u32,height:u32,method:&str,rgb_to_xyz:&[[f32;3];3],mut cancelled:impl FnMut()->bool)->io::Result<f32>{
    if width==0||height==0||width>40000||height>40000{return Err(invalid("Invalid metering dimensions"));}
    let(w,h)=(width as usize,height as usize);if fs::metadata(input)?.len()!=w as u64*h as u64*12{return Err(invalid("Invalid metering input size"));}
    let max_dim=w.max(h);let scale=256./max_dim as f64;
    let(sw,sh)=if max_dim>256{(((w as f64*scale).round()as usize).max(1),((h as f64*scale).round()as usize).max(1))}else{(w,h)};
    let map=|o:usize,n:usize,s:usize|->usize{let axis_scale=n as f64/s as f64;(((o as f64+0.5)*axis_scale-0.5).round()as isize).clamp(0,n as isize-1)as usize};
    let mut file=File::open(input)?;let mut row=vec![0u8;w*12];let mut samples=Vec::with_capacity(sw*sh*3);
    for y in 0..sh{check(&mut cancelled)?;let sy=map(y,h,sh);file.seek(SeekFrom::Start((sy*w*12)as u64))?;file.read_exact(&mut row)?;
      for x in 0..sw{let sx=map(x,w,sw);for ch in 0..3{let at=(sx*3+ch)*4;let v=f32::from_le_bytes(row[at..at+4].try_into().unwrap());if !v.is_finite(){return Err(invalid("Nonfinite metering pixel"));}samples.push(from_f64(v as f64));}}
    }
    let ev=spektrafilm_core::stages::filming::measure_autoexposure_ev(&ImageBuf::from_data(sw as u32,sh as u32,samples),rgb_to_xyz,method);
    if !ev.is_finite(){return Err(invalid("Nonfinite metering result"));}Ok(ev)
}

/// Global peak reduction, then exact highlight curve on three private f64 planes.
/// These planes are scratch owned by the caller; cancellation may leave them
/// partly transformed, so the caller must discard them (never original input).
pub fn boost_planes(paths:&[PathBuf;3],pixels:u64,boost_ev:f64,boost_range:f64,protect_ev:f64,mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->io::Result<()>{
    if pixels==0||!boost_ev.is_finite()||!boost_range.is_finite()||!protect_ev.is_finite()||!(0.0..=8.0).contains(&boost_ev)||!(0.0..=1.0).contains(&boost_range)||!(-16.0..=16.0).contains(&protect_ev){return Err(invalid("Invalid highlight parameters"));}
    if boost_ev==0.{return Ok(());}let mut maximum=f64::NEG_INFINITY;let mut done=0;
    for path in paths{if fs::metadata(path)?.len()!=pixels*8{return Err(invalid("Invalid highlight plane size"));}let mut f=File::open(path)?;let mut at=0;while at<pixels{check(&mut cancelled)?;let len=(pixels-at).min(BLOCK as u64)as usize;for v in read64(&mut f,len)?{maximum=maximum.max(v);}at+=len as u64;done+=len as u64;progress(done,6*pixels);}}
    if maximum<0.{return Err(invalid("Negative highlight peak"));}
    let x0=(0.184*2f64.powf(protect_ev)).clamp(0.,maximum);
    if x0==maximum && maximum!=0.{progress(6*pixels,6*pixels);return Ok(());}
    let a=28f64.powf(1.-boost_range);let xn=if maximum>0.{x0/maximum}else{0.};let denom=(a*(1.-xn)).exp()-a*(1.-xn)-1.;
    if denom<=0.{progress(6*pixels,6*pixels);return Ok(());}
    let k=(2f64.powf(boost_ev)-1.)/denom;let inv=if maximum>0.{1./maximum}else{0.};let scale=k*maximum;
    for path in paths{let mut f=OpenOptions::new().read(true).write(true).open(path)?;let mut at=0;while at<pixels{check(&mut cancelled)?;let len=(pixels-at).min(BLOCK as u64)as usize;let mut values=read64(&mut f,len)?;for v in &mut values{*v=if maximum==0.{0.}else if *v<=x0{*v}else{let dx=(*v-x0)*inv;*v+scale*((a*dx).exp()-a*dx-1.)};}f.seek(SeekFrom::Start(at*8))?;write64(&mut f,&values)?;at+=len as u64;done+=len as u64;progress(done,6*pixels);}}
    Ok(())
}

/// Exact full-frame headroom reduction and channel-shared pointwise gain.
/// Operates on owned scratch after legacy boost, before spatial filtering.
pub fn headroom_planes(paths:&[PathBuf;3],pixels:u64,ev:f64,from_ev:f64,to_ev:f64,mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->io::Result<()> {
    if pixels==0 || !ev.is_finite() || !(0.0..=8.0).contains(&ev) || !from_ev.is_finite() || !to_ev.is_finite() || to_ev<=from_ev {return Err(invalid("Invalid halation headroom"));}
    if ev==0. {return Ok(());}
    let mut maximum=f64::NEG_INFINITY;let mut done=0;
    for path in paths {
        if fs::metadata(path)?.len()!=pixels*8 {return Err(invalid("Invalid headroom plane size"));}
        let mut f=File::open(path)?;let mut at=0;
        while at<pixels {check(&mut cancelled)?;let len=(pixels-at).min(BLOCK as u64)as usize;for v in read64(&mut f,len)? {maximum=maximum.max(v);}at+=len as u64;done+=len as u64;progress(done,6*pixels);}
    }
    if maximum<=0. {progress(6*pixels,6*pixels);return Ok(());}
    let mut files=paths.iter().map(|p|OpenOptions::new().read(true).write(true).open(p)).collect::<io::Result<Vec<_>>>()?;
    let mut at=0;
    while at<pixels {
        check(&mut cancelled)?;let len=(pixels-at).min(BLOCK as u64)as usize;
        let mut planes=files.iter_mut().map(|f|read64(f,len)).collect::<io::Result<Vec<_>>>()?;
        for i in 0..len {let m=planes[0][i].max(planes[1][i]).max(planes[2][i]);let gain=spektrafilm_math::halation::headroom_gain(m,maximum,ev,from_ev,to_ev);for ch in 0..3 {planes[ch][i]*=gain;}}
        for ch in 0..3 {files[ch].seek(SeekFrom::Start(at*8))?;write64(&mut files[ch],&planes[ch])?;}
        at+=len as u64;done+=3*len as u64;progress(done,6*pixels);
    }
    Ok(())
}

/// Sequential lognormal percentages BEFORE blur and division by100. Caller
/// applies exact global Gaussian blur (if >0), then *0.01, then scanner offset.
/// One field is shared by all channels and must be generated only once.
pub fn write_glare_field(output:&Path,width:u32,height:u32,percent:f32,roughness:f32,seed:u64,mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->io::Result<()>{
    if width==0||height==0{return Err(invalid("Empty glare field"));}
    let mut sampler=spektrafilm_model::glare::GlareSamples::new(percent,roughness,seed).map_err(invalid)?;
    let mut f=OpenOptions::new().write(true).create_new(true).open(output)?;let mut guard=Output{path:output.to_path_buf(),keep:false};let n=width as u64*height as u64;let mut at=0;
    while at<n{check(&mut cancelled)?;let len=(n-at).min(BLOCK as u64)as usize;let v:Vec<f64>=(0..len).map(|_|sampler.sample()).collect();write64(&mut f,&v)?;at+=len as u64;progress(at,n);}f.flush()?;guard.keep=true;Ok(())
}

/// Coordinate-seeded clumping multiplier BEFORE its global blur. Only active
/// when micro[1]*.001/pitch>.05. micro[0] is blur size in µm, micro[1] is
/// model's strength-length parameter in nm. Existing model defaults [.2,30].
/// Caller multiplies the sublayer SUM by this field before density-min removal.
pub fn write_microstructure_field(output:&Path,width:u32,height:u32,channel:usize,micro:[f32;2],pitch:f32,seed:u64,mut cancelled:impl FnMut()->bool,mut progress:impl FnMut(u64,u64))->io::Result<()>{
    if width==0||height==0||channel>=3||!pitch.is_finite()||pitch<=0.||micro.iter().any(|v|!v.is_finite()||*v<0.){return Err(invalid("Invalid microstructure parameters"));}
    let sigma=micro[1]as f64*0.001/pitch as f64;let sigma_log=(1.+sigma*sigma).ln().sqrt();let mu=-0.5*sigma_log*sigma_log;
    let mut f=OpenOptions::new().write(true).create_new(true).open(output)?;let mut guard=Output{path:output.to_path_buf(),keep:false};let n=width as u64*height as u64;
    for y in 0..height{check(&mut cancelled)?;let row:Vec<f64>=(0..width).map(|x|if sigma>0.05{spektrafilm_model::grain::microstructure_coordinate_value(seed,x,y,channel,sigma_log,mu)}else{1.}).collect();write64(&mut f,&row)?;progress((y as u64+1)*width as u64,n);}
    f.flush()?;guard.keep=true;Ok(())
}

#[cfg(test)]mod tests{
 use super::*;
 fn root(label:&str)->PathBuf{let p=std::env::temp_dir().join(format!("global-field-{}-{label}",std::process::id()));let _=fs::remove_dir_all(&p);fs::create_dir(&p).unwrap();p}
 fn values(path:&Path)->Vec<f64>{fs::read(path).unwrap().chunks_exact(8).map(|b|f64::from_le_bytes(b.try_into().unwrap())).collect()}
 #[test]fn metering_matches_original_all_methods(){
  let root=root("meter");let matrix=spektrafilm_math::colorspace::SRGB_TO_XYZ;
  for(w,h)in[(307u32,181u32),(35,27),(513,1)]{
   let data:Vec<f32>=(0..w*h*3).map(|i|((i*29%201)as f32+1.)/101.).collect();let image=ImageBuf::from_data(w,h,data.iter().map(|&x|from_f64(x as f64)).collect());let input=root.join("input");fs::write(&input,data.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>()).unwrap();
   for method in ["average","median","center_weighted","partial","matrix","multi_zone","highlight_weighted"]{
    let want=spektrafilm_core::stages::filming::measure_autoexposure_ev(&image,&matrix,method);let got=auto_exposure_ev(&input,w,h,method,&matrix,||false).unwrap();assert_eq!(got.to_bits(),want.to_bits(),"{method} {w}x{h}");
   }
  }fs::remove_dir_all(root).unwrap();
 }
 #[test]fn highlight_global_peak_matches_original(){
  let root=root("boost");let paths=std::array::from_fn(|i|root.join(format!("p{i}")));let(w,h)=(29u32,17u32);let data:Vec<_>=(0..w*h*3).map(|i|from_f64((i*11%257)as f64/31.)).collect();let image=ImageBuf::from_data(w,h,data);
  for params in [(2.,0.3,1.),(0.5,1.,4.),(3.,0.,0.)]{for ch in 0..3{fs::write(&paths[ch],image.data.chunks_exact(3).flat_map(|p|(p[ch]as f64).to_le_bytes()).collect::<Vec<_>>()).unwrap();}
   let want=spektrafilm_model::diffusion::boost_highlights(&image,params.0,params.1,params.2);boost_planes(&paths,w as u64*h as u64,params.0,params.1,params.2,||false,|_,_|{}).unwrap();for ch in 0..3{for(a,p)in values(&paths[ch]).iter().zip(want.data.chunks_exact(3)){assert_eq!(a.to_bits(),(p[ch]as f64).to_bits());}}
  }fs::remove_dir_all(root).unwrap();
 }
 #[test]fn cinestill_headroom_planes_match_whole_frame_after_legacy_boost(){
  let root=root("headroom");let paths=std::array::from_fn(|i|root.join(format!("p{i}")));let(w,h)=(257u32,129u32);
  let data:Vec<_>=(0..w*h*3).map(|i|from_f64(0.001+(i*17%997)as f64/97.)).collect();let input=ImageBuf::from_data(w,h,data);
  for boost in [0.,2.] {
   let image=spektrafilm_model::diffusion::boost_highlights(&input,boost,0.3,4.);
   for ch in 0..3{fs::write(&paths[ch],image.data.chunks_exact(3).flat_map(|p|(p[ch]as f64).to_le_bytes()).collect::<Vec<_>>()).unwrap();}
   let want=spektrafilm_model::diffusion::restore_halation_headroom(&image,4.,1.5,2.5);
   headroom_planes(&paths,w as u64*h as u64,4.,1.5,2.5,||false,|_,_|{}).unwrap();
   for ch in 0..3{for(a,p)in values(&paths[ch]).iter().zip(want.data.chunks_exact(3)){assert_eq!(a.to_bits(),(p[ch]as f64).to_bits());}}
  }
  fs::remove_dir_all(root).unwrap();
 }
 #[test]fn glare_field_and_global_blur_match_original(){
  let root=root("glare");let(w,h)=(31u32,23u32);let raw=root.join("raw");let blurred=root.join("blurred");write_glare_field(&raw,w,h,1.2,0.7,42,||false,|_,_|{}).unwrap();let want=spektrafilm_model::glare::compute_random_glare_amount(w,h,1.2,0.7,2.,42);
  crate::disk_spatial::gaussian_file(&raw,&blurred,w,h,2.,&root,16*1024*1024,&mut ||false,&mut |_|{}).unwrap();for(a,b)in values(&blurred).iter().zip(want){assert_eq!((a*0.01).to_bits(),(b as f64).to_bits());}fs::remove_dir_all(root).unwrap();
 }
 #[test]fn microstructure_matches_origin_aware_grain(){
  let root=root("micro");let(w,h)=(17u32,11u32);let image=ImageBuf::from_data(w,h,(0..w*h*3).map(|i|from_f64(0.1+(i%17)as f64/20.)).collect());
  let curves=vec![[0.,0.,0.],[1.,1.,1.],[2.,2.,2.]];let layers:Vec<Vec<Vec<f64>>>=curves.iter().map(|r|vec![r.iter().map(|v|v/3.).collect();3]).collect();let minimum=[0.1,0.2,0.3];
  let render=|micro|spektrafilm_model::grain::apply_grain_layers(&image,10.,&curves,&layers,false,0.2,[1.;3],[1.;3],minimum,[0.97;3],0.,0.,micro,false,42,Some([0,0]));
  let base=render([0.,0.]);let want=render([0.,1000.]);
  for ch in 0..3{let output=root.join(format!("field{ch}"));write_microstructure_field(&output,w,h,ch,[0.,1000.],10.,42,||false,|_,_|{}).unwrap();for((factor,b),p)in values(&output).iter().zip(base.data.chunks_exact(3)).zip(want.data.chunks_exact(3)){let got=(b[ch]as f64+minimum[ch])*factor-minimum[ch];assert!((got-p[ch]as f64).abs()<1e-12);}}
  fs::remove_dir_all(root).unwrap();
 }
 #[test]fn field_cancellation_removes_output(){let root=root("cancel");let output=root.join("field");let mut calls=0;let error=write_glare_field(&output,300,300,1.,1.,42,||{calls+=1;calls>1},|_,_|{}).unwrap_err();assert_eq!(error.kind(),io::ErrorKind::Interrupted);assert!(!output.exists());fs::remove_dir_all(root).unwrap();}
}
