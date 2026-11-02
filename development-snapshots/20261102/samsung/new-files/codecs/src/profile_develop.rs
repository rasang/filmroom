//! New ProfileGainTableMap path: normalized linear camera RGB -> calibrated
//! XYZ D50 -> exposure-adjusted RIMM lookup -> scalar gain -> linear sRGB.
//! No Adobe SDK runtime or code; SDK is an external numerical test oracle.
use crate::{dng::{Rect},dng_color_spec::{self as color,Calibration,Input,Matrix},error::{err,Result},tiff::{Ifd,Value},profile_gain_table::ProfileGainTable};
use std::sync::atomic::{AtomicBool,Ordering};
#[derive(Clone,Debug)]pub struct ProfileDevelopment {pub map:ProfileGainTable,pub camera_to_pcs:Matrix,pub camera_to_rimm:Matrix}
fn bad()->crate::error::CodecError{err("INVALID_COLOR_METADATA","probe")}
fn get<'a>(root:&'a Ifd,raw:&'a Ifd,t:u16)->Option<&'a Value>{raw.get(&t).or_else(||root.get(&t))}
fn matrix(root:&Ifd,raw:&Ifd,t:u16)->Result<Matrix>{let v=get(root,raw,t).ok_or_else(bad)?;if v.typ!=10||v.count!=9{return Err(bad())}let a=v.nums()?;Ok(std::array::from_fn(|r|std::array::from_fn(|c|a[r*3+c])))}
pub fn parse(root:&Ifd,raw:&Ifd)->Result<Option<ProfileDevelopment>>{
 let Some(v)=get(root,raw,52525)else{return Ok(None)};
 if !std::ptr::eq(root,raw)&&raw.contains_key(&52525){return Err(err("UNSUPPORTED_COLOR_CALIBRATION","probe"))}
 // Hue/saturation transforms precede gain-table lookup. Until implemented,
 // reject rather than retaining the legacy approximate-profile warning policy.
 if [50937,50938,50939,50981,50982,50983,52529,52531,52544,52548,52549,52550].iter().any(|&t|get(root,raw,t).is_some()) {return Err(err("UNSUPPORTED_COLOR_CALIBRATION","probe"));}
 let map=ProfileGainTable::parse(v)?;
 let diagonal=|tag|->Result<[f64;3]>{if get(root,raw,tag).is_none(){return Ok([1.;3])}let m=matrix(root,raw,tag)?;if m.iter().enumerate().any(|(r,row)|row.iter().enumerate().any(|(c,v)|r!=c&&*v!=0.)){return Err(err("UNSUPPORTED_COLOR_CALIBRATION","probe"))}Ok([m[0][0],m[1][1],m[2][2]])};
 let nv=get(root,raw,50728).ok_or_else(bad)?;if nv.typ!=5||nv.count!=3{return Err(bad())}let neutral=nv.nums()?;if neutral.len()!=3{return Err(bad())}
 if let Some(v)=get(root,raw,50727){if v.typ!=5||v.count!=3{return Err(bad())}}
 let analog=get(root,raw,50727).map(Value::nums).transpose()?.unwrap_or(vec![1.;3]);if analog.len()!=3{return Err(bad())}
 if !std::ptr::eq(root,raw) && [50931,50932].iter().any(|t|raw.contains_key(t)){return Err(err("UNSUPPORTED_COLOR_CALIBRATION","probe"));}
 let signature=|tag|->Result<&[u8]>{match root.get(&tag){None=>Ok(&[]),Some(v)=>{if !matches!(v.typ,1|2)||v.bytes.len()>1024||v.bytes.last()!=Some(&0){return Err(bad())}Ok(v.bytes.strip_suffix(&[0]).unwrap())}}};
 let cal=|i:u16|->Result<Calibration>{let tag=50778+i;let v=get(root,raw,tag).ok_or_else(bad)?;let il=v.uints()?;if v.typ!=3||v.count!=1||il.len()!=1{return Err(bad())}Ok(Calibration{illuminant:il[0].try_into().map_err(|_|bad())?,color_matrix:matrix(root,raw,50721+i)?,forward_matrix:matrix(root,raw,50964+i)?,camera_calibration:diagonal(50723+i)?})};
 let input=Input{calibrations:[cal(0)?,cal(1)?],analog_balance:analog.try_into().unwrap(),as_shot_neutral:neutral.try_into().unwrap(),calibration_signatures_match:signature(50931)?==signature(50932)?};
 let transform=color::resolve(&input).map_err(|e|err(e.code(),"probe"))?;
 // Standard linear ProPhoto/RIMM primaries, normalized to exact D50 PCS white.
 let mut rimm_to_pcs=[[0.7977,0.1352,0.0313],[0.2880,0.7119,0.0001],[0.,0.,0.8249]];
 let white=color::d50_xyz();for r in 0..3{let sum:f64=rimm_to_pcs[r].iter().sum();for c in 0..3{rimm_to_pcs[r][c]*=white[r]/sum;}}
 let inverse=color::inverse(rimm_to_pcs).map_err(|_|bad())?;
 Ok(Some(ProfileDevelopment{map,camera_to_pcs:transform.camera_to_pcs,camera_to_rimm:color::product(inverse,transform.camera_to_pcs)}))
}
impl ProfileDevelopment{
 pub fn develop(&self,active:Rect,y:usize,h:usize,data:Vec<f32>,baseline:f32,cancel:&AtomicBool)->Result<rawler::pixarray::Color2D<f32,3>>{
  let end=active.y.checked_add(active.h);let tile_end=y.checked_add(h);
  if active.w==0||active.h==0||h==0||y<active.y||end.is_none()||tile_end.is_none()||tile_end>end||active.w.checked_mul(h).and_then(|n|n.checked_mul(3))!=Some(data.len()){return Err(err("INVALID_INPUT","develop"))}
  let exposure=baseline.exp2() as f64;let m=rawler::imgop::xyz::XYZ_TO_SRGB_D50.map(|r|r.map(|x|x as f64));let mut pixels=Vec::with_capacity(active.w*h);
  for (i,p) in data.chunks_exact(3).enumerate(){if i%1024==0&&cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","develop"))}let cam=[p[0]as f64,p[1]as f64,p[2]as f64];let rimm=color::apply(self.camera_to_rimm,cam).map(|x|x*exposure);let x=(i%active.w)as f64+0.5;let yy=(y+i/active.w-active.y)as f64+0.5;let gain=self.map.gain(x/active.w as f64,yy/active.h as f64,rimm)? as f64;let xyz=color::apply(self.camera_to_pcs,cam).map(|x|x*exposure*gain);let rgb=color::apply(m,xyz).map(|x|x as f32);if rgb.iter().any(|x|!x.is_finite()){return Err(err("INVALID_COLOR_METADATA","develop"))}pixels.push(rgb);}
  Ok(rawler::pixarray::Color2D::new_with(pixels,active.w,h))
 }
}
#[cfg(test)]
mod tests {
 use super::*;
 #[test]
 fn direct_development_rejects_invalid_geometry_without_panicking() {
  let mut b=Vec::new();for n in [1u32,1]{b.extend(n.to_le_bytes());}for x in [1f64,1.,0.,0.]{b.extend(x.to_le_bytes());}b.extend(2u32.to_le_bytes());for x in [1f32,0.,0.,0.,0.,1.,1.]{b.extend(x.to_le_bytes());}
  let map=ProfileGainTable::parse(&Value{typ:7,count:b.len() as u32,bytes:b,endian:crate::tiff::Endian::Le}).unwrap();
  let p=ProfileDevelopment{map,camera_to_pcs:[[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]],camera_to_rimm:[[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]]};let c=AtomicBool::new(false);
  for (area,y,h) in [(Rect{x:0,y:1,w:1,h:1},0,1),(Rect{x:0,y:0,w:1,h:1},1,1),(Rect{x:0,y:usize::MAX,w:1,h:1},usize::MAX,1),(Rect{x:0,y:0,w:usize::MAX,h:2},0,2),(Rect{x:0,y:0,w:1,h:1},0,0)] {
   assert!(p.develop(area,y,h,vec![],0.,&c).is_err());
  }
 }
}
