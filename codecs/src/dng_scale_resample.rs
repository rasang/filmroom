//! Bounded, camera-space DefaultScale resampling. Original implementation of
//! separable cubic convolution (A=-3/4), with the DNG reference model's
//! 1/128-pixel coordinate grid and float32 normalized weights. SDK is used only
//! as an external test oracle; no SDK code is included.
use crate::{dng::Rect,error::{err,io_error,Result}};
use std::{fs::File,os::unix::fs::FileExt,sync::atomic::{AtomicBool,Ordering}};
const PHASES:usize=128;
struct Axis { radius:usize, taps:usize, weights:Vec<f32> }
fn radius(source:usize,target:usize)->Result<usize>{
    if source==0||target==0{return Err(err("INVALID_INPUT","develop"));}
    let r=(2.0/(target as f64/source as f64).min(1.)).ceil();
    if !r.is_finite()||r>usize::MAX as f64/2.{return Err(err("MEMORY_BUDGET","admission"));}
    Ok(r as usize)
}
fn validate(width:usize,height:usize,crop:Rect,target:[usize;2])->Result<()> {
    if [width,height,crop.w,crop.h,target[0],target[1]].contains(&0)
        ||crop.x.checked_add(crop.w).is_none_or(|v|v>width)
        ||crop.y.checked_add(crop.h).is_none_or(|v|v>height) {return Err(err("INVALID_INPUT","develop"));}
    if [width,height,target[0],target[1]].iter().any(|&v|v>32768){return Err(err("EDGE_LIMIT","develop"));}
    Ok(())
}
/// Peak owned storage: two 128-phase weight tables, one source byte row, one
/// vertical RGB row, and one emitted RGB row. Caller must additionally account
/// for its callback's allocations and decoder/backing state.
pub(crate) fn scratch_bytes(width:usize,height:usize,crop:Rect,target:[usize;2])->Result<u64>{
    validate(width,height,crop,target)?;
    let rx=radius(crop.w,target[0])? as u64;let ry=radius(crop.h,target[1])? as u64;
    (rx+ry).checked_mul(2*PHASES as u64*4)
        .and_then(|n|n.checked_add(width as u64*24))
        .and_then(|n|n.checked_add(target[0] as u64*12))
        .ok_or_else(||err("MEMORY_BUDGET","admission"))
}
fn zeros<T:Default+Clone>(count:usize)->Result<Vec<T>> {
    let mut v=Vec::new();v.try_reserve_exact(count).map_err(|_|err("MEMORY_BUDGET","admission"))?;
    v.resize(count,T::default());Ok(v)
}
fn cubic(x:f64)->f64 {
    let t=x.abs();let a=-0.75;
    if t>=2. {0.}else if t>=1. {a*(t*t*t-5.*t*t+8.*t-4.)}
    else {(a+2.)*t*t*t-(a+3.)*t*t+1.}
}
impl Axis {
    fn new(source:usize,target:usize,cancel:&AtomicBool)->Result<Self>{
        let radius=radius(source,target)?;let taps=radius*2;
        let count=taps.checked_mul(PHASES).ok_or_else(||err("MEMORY_BUDGET","admission"))?;
        let mut weights=zeros::<f32>(count)?;let scale=(target as f64/source as f64).min(1.);
        for (phase,w) in weights.chunks_exact_mut(taps).enumerate(){
            cancelled(cancel)?;
            let f=phase as f64/PHASES as f64;
            for (k,v) in w.iter_mut().enumerate(){*v=cubic((k as f64-radius as f64+1.-f)*scale) as f32;}
            let sum=w.iter().map(|&x|x as f64).sum::<f64>();
            if !sum.is_finite()||sum<=0.{return Err(err("INVALID_INPUT","develop"));}
            let reciprocal=(1./sum)as f32;for v in w{*v*=reciprocal;}
        }
        Ok(Self{radius,taps,weights})
    }
    fn at(&self,index:usize,origin:usize,source:usize,target:usize)->(i64,&[f32]){
        let center=(index as f64+0.5)*(source as f64/target as f64)-0.5+origin as f64;
        let fixed=(center*PHASES as f64).round()as i64;
        let phase=fixed.rem_euclid(PHASES as i64)as usize;
        (fixed.div_euclid(PHASES as i64)-self.radius as i64+1,
         &self.weights[phase*self.taps..(phase+1)*self.taps])
    }
}
fn cancelled(cancel:&AtomicBool)->Result<()>{if cancel.load(Ordering::Relaxed){Err(err("CANCELLED","develop"))}else{Ok(())}}
/// Source is interleaved little-endian float32 RGB for the complete active
/// image, with origin (0,0). Crop is relative to that backing, before orientation.
/// Does not perform color processing; the callback receives unclipped-camera
/// interpolation with the reference [0,1] clipping after each separable pass.
pub(crate) fn resample(source:&File,width:usize,height:usize,crop:Rect,target:[usize;2],cancel:&AtomicBool,
    mut emit:impl FnMut(usize,Vec<[f32;3]>)->Result<()>)->Result<()> {
    scratch_bytes(width,height,crop,target)?;cancelled(cancel)?;
    let needed=width as u64*height as u64*12;
    if source.metadata().map_err(|e|io_error(e,"develop"))?.len()<needed{return Err(err("INVALID_INPUT","develop"));}
    let horizontal=Axis::new(crop.w,target[0],cancel)?;let vertical=Axis::new(crop.h,target[1],cancel)?;
    let mut bytes=zeros::<u8>(width*12)?;let mut row=zeros::<[f32;3]>(width)?;
    for y in 0..target[1]{
        cancelled(cancel)?;let (top,weights)=vertical.at(y,crop.y,crop.h,target[1]);
        for (k,&weight) in weights.iter().enumerate(){
            cancelled(cancel)?;let sy=(top+k as i64).clamp(0,height as i64-1)as usize;
            source.read_exact_at(&mut bytes,sy as u64*width as u64*12).map_err(|e|io_error(e,"develop"))?;
            for (x,p) in bytes.chunks_exact(12).enumerate(){
                for c in 0..3 {
                    let value=f32::from_le_bytes(p[c*4..c*4+4].try_into().unwrap());
                    if !value.is_finite(){return Err(err("INVALID_COLOR_METADATA","develop"));}
                    let term=value*weight;
                    if k==0{row[x][c]=term;}else{row[x][c]+=term;}
                }
            }
        }
        for p in &mut row {for v in p {if !v.is_finite(){return Err(err("INVALID_COLOR_METADATA","develop"));}*v=v.clamp(0.,1.);}}
        let mut output=zeros::<[f32;3]>(target[0])?;
        for (x,pixel) in output.iter_mut().enumerate(){
            if x%256==0{cancelled(cancel)?;}
            let (left,weights)=horizontal.at(x,crop.x,crop.w,target[0]);
            for c in 0..3 {
                let mut value=0.;
                for (k,&w) in weights.iter().enumerate(){let sx=(left+k as i64).clamp(0,width as i64-1)as usize;
                    let term=row[sx][c]*w;if k==0{value=term;}else{value+=term;}}
                if !value.is_finite(){return Err(err("INVALID_COLOR_METADATA","develop"));}
                pixel[c]=value.clamp(0.,1.);
            }
        }
        emit(y,output)?;
    }
    Ok(())
}

#[cfg(test)]mod tests{
 use super::*;use std::io::Write;use std::sync::atomic::AtomicU64;
 fn run(width:usize,height:usize,crop:Rect,target:[usize;2],values:&[f32])->Vec<[f32;3]>{
  static NEXT:AtomicU64=AtomicU64::new(0);
  let path=std::env::temp_dir().join(format!("film-scale-test-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));
  let mut f=std::fs::OpenOptions::new().read(true).write(true).create_new(true).open(&path).unwrap();std::fs::remove_file(path).unwrap();
  for v in values{f.write_all(&v.to_le_bytes()).unwrap();}
  let mut out=Vec::new();resample(&f,width,height,crop,target,&AtomicBool::new(false),|_,row|{out.extend(row);Ok(())}).unwrap();out
 }
 #[test]fn sdk_nonzero_crop_overshoot_golden(){
  // Generated by Adobe DNG SDK 1.7.1 ResampleImage float32; source hashes
  // and reproducible harness: tests/dng_scale_sdk_oracle.py.
  // 5x4 -> crop(1,1,3,2) -> 4x3. Source samples straddle [0,1].
  // This catches both-pass clipping, whole-image edge support, and phase rules.
  let source:Vec<f32>=(0..60).map(|i|((((i*37)%101)as f64-15.)/80.)as f32).collect();
  let output=run(5,4,Rect{x:1,y:1,w:3,h:2},[4,3],&source);
  let expected=[0x3efdd90d,0x3f800000,0x3e74de9f,0x3f1c5cde,0x3eb871f6,0x3eafbff9,0x3f3467e9,0x0,0x3ed2c51f,0x3f4d1beb,0x3d85915f,0x3eeadd43,0x3e999f73,0x3f3ac232,0x3f03f59a,0x3e89a02c,0x3ec0fd99,0x3f2e6a99,0x3ed6accc,0x3e3988ce,0x3ef89c00,0x3f040a67,0x3eb1ae66,0x3e082a67,0x3dc9c041,0x3ecebc58,0x3f4ab38c,0x0,0x3ee6d47a,0x3f800000,0x3e04635c,0x3f04ecd0,0x3f1d3fff,0x3e6be38f,0x3f1f9525,0x0];
  for (&actual,&bits) in output.iter().flatten().zip(&expected){assert!((actual-f32::from_bits(bits)).abs()<=2e-7);}
 }
 #[test]fn constant_and_identity_crop(){
  for target in [[1,1],[17,13],[3,2]]{
   let out=run(7,5,Rect{x:1,y:1,w:5,h:3},target,&vec![0.4;7*5*3]);
   assert!(out.iter().flatten().all(|v|(*v-0.4).abs()<3e-7));
  }
  let source:Vec<f32>=(0..7*5*3).map(|i|i as f32/105.).collect();let out=run(7,5,Rect{x:1,y:1,w:5,h:3},[5,3],&source);
  for y in 0..3{for x in 0..5{for c in 0..3{assert_eq!(out[y*5+x][c],source[((y+1)*7+x+1)*3+c]);}}}
 }
 #[test]fn kernel_scale_and_budget_accounting(){
  let crop=Rect{x:0,y:0,w:100,h:80};
  assert_eq!(radius(100,25).unwrap(),8);assert_eq!(radius(80,20).unwrap(),8);
  assert_eq!(scratch_bytes(100,80,crop,[25,20]).unwrap(),16*2*128*4+100*24+25*12);
  assert!(scratch_bytes(100,80,Rect{x:99,y:0,w:2,h:80},[1,1]).is_err());
  assert!(scratch_bytes(100,80,crop,[0,1]).is_err());
 }
 #[test]fn negative_half_phase_rounds_away_from_zero(){
  let a=Axis::new(1,128,&AtomicBool::new(false)).unwrap();let (start,_)=a.at(0,0,1,128);
  // center=-127/256, quantized -64/128, not -63/128.
  assert_eq!(start,-2);
  assert_eq!(a.at(0,0,1,128).1,&a.weights[64*4..65*4]);
 }
}
