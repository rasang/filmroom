//! New ProfileGainTableMap path: normalized linear camera RGB -> calibrated
//! XYZ D50 -> exposure-adjusted RIMM lookup -> scalar gain -> linear sRGB.
//! No Adobe SDK runtime or code; SDK is an external numerical test oracle.
use crate::{dng::{Rect},dng_color_spec::{self as color,Calibration,Input,Matrix},error::{err,Result,Reason},tiff::{Ifd,Value},profile_gain_table::ProfileGainTable};
use std::sync::atomic::{AtomicBool,Ordering};
#[derive(Clone,Debug)]pub struct ProfileDevelopment {pub map:Option<ProfileGainTable>,pub camera_to_pcs:Matrix,pub camera_to_rimm:Matrix}
#[track_caller]
fn bad()->crate::error::CodecError{err("INVALID_COLOR_METADATA","probe")}
fn get<'a>(root:&'a Ifd,raw:&'a Ifd,t:u16)->Option<&'a Value>{raw.get(&t).or_else(||root.get(&t))}
fn matrix(root:&Ifd,raw:&Ifd,t:u16)->Result<Matrix>{let v=get(root,raw,t).ok_or_else(|| bad().detail(Reason::MissingTag,Some(t)))?;if v.typ!=10||v.count!=9{return Err(bad().detail(Reason::MatrixShape,Some(t)))}let a=v.nums().map_err(|e|e.detail(Reason::TagValue,Some(t)))?;Ok(std::array::from_fn(|r|std::array::from_fn(|c|a[r*3+c])))}
/// The two standard white-balance encodings are mutually exclusive. Do not
/// silently prefer one if a file supplies conflicting metadata. For XY the
/// neutral placeholder is never used: the resolver receives the explicit xy.
pub(crate) fn white_balance(root:&Ifd,raw:&Ifd)->Result<([f64;3],Option<[f64;2]>)> {
 let neutral=get(root,raw,50728);let xy=get(root,raw,50729);
 if neutral.is_some() && xy.is_some(){return Err(bad().detail(Reason::TagValue,Some(50729)))}
 if let Some(v)=xy {
  if v.typ!=5||v.count!=2{return Err(bad().detail(Reason::TagShape,Some(50729)))}
  let a=v.nums().map_err(|e|e.detail(Reason::TagValue,Some(50729)))?;
  if a.len()!=2||a.iter().any(|x|!x.is_finite()||*x<=0.)||a[0]+a[1]>=1. {return Err(bad().detail(Reason::TagValue,Some(50729)))}
  return Ok(([1.;3],Some([a[0],a[1]])));
 }
 let v=neutral.ok_or_else(||bad().detail(Reason::MissingTag,Some(50728)))?;
 if !matches!(v.typ,3|5)||v.count!=3{return Err(bad().detail(Reason::TagShape,Some(50728)))}
 let a=v.nums().map_err(|e|e.detail(Reason::TagValue,Some(50728)))?;
 if a.len()!=3||a.iter().any(|x|!x.is_finite()||*x<=0.){return Err(bad().detail(Reason::TagValue,Some(50728)))}
 Ok((a.try_into().unwrap(),None))
}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum ColorRoute { Legacy, NewSof3LinearPrecision }
pub fn parse(root:&Ifd,raw:&Ifd,route:ColorRoute)->Result<Option<ProfileDevelopment>>{
 let v=get(root,raw,52525);
 if v.is_none() && route==ColorRoute::Legacy{return Ok(None)};
 if !std::ptr::eq(root,raw)&&raw.contains_key(&52525){return Err(err("UNSUPPORTED_DNG_CALIBRATION","probe"))}
 // Hue/saturation transforms precede gain-table lookup. Until implemented,
 // reject rather than retaining the legacy approximate-profile warning policy.
 if [50937,50938,50939,50981,50982,50983,52529,52531,52544].iter().any(|&t|get(root,raw,t).is_some()) {return Err(err("UNSUPPORTED_DNG_CALIBRATION","probe"));}
 let map=v.map(ProfileGainTable::parse).transpose()?;
 let camera_to_pcs=if get(root,raw,50964).is_none() && get(root,raw,50965).is_none(){
  crate::dng_cfa_color::parse_complete(root,raw)?.ok_or_else(||err("UNSUPPORTED_DNG_CALIBRATION","probe"))?.camera_to_pcs
 }else{
 let camera_cc=|tag|->Result<Matrix>{if get(root,raw,tag).is_none(){return Ok([[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]])} matrix(root,raw,tag)};
 let (neutral,white_xy)=white_balance(root,raw)?;
 if let Some(v)=get(root,raw,50727){if v.typ!=5||v.count!=3{return Err(bad().detail(Reason::TagShape,Some(50727)))}}
 let analog=get(root,raw,50727).map(Value::nums).transpose().map_err(|e|e.detail(Reason::TagValue,Some(50727)))?.unwrap_or(vec![1.;3]);if analog.len()!=3{return Err(bad())}
 if !std::ptr::eq(root,raw) && [50931,50932].iter().any(|t|raw.contains_key(t)){return Err(err("UNSUPPORTED_DNG_CALIBRATION","probe"));}
 let signature=|tag|->Result<&[u8]>{match root.get(&tag){None=>Ok(&[]),Some(v)=>{if !matches!(v.typ,1|2)||v.bytes.len()>1024||v.bytes.last()!=Some(&0){return Err(bad())}Ok(v.bytes.strip_suffix(&[0]).unwrap())}}};
 let cal=|i:u16|->Result<Calibration>{let tag=50778+i;let v=get(root,raw,tag).ok_or_else(|| bad().detail(Reason::MissingTag,Some(tag)))?;let il=v.uints().map_err(|e|e.detail(Reason::TagValue,Some(tag)))?;if v.typ!=3||v.count!=1||il.len()!=1{return Err(bad())}Ok(Calibration{illuminant:il[0].try_into().map_err(|_|bad())?,color_matrix:matrix(root,raw,50721+i)?,forward_matrix:matrix(root,raw,50964+i)?,camera_calibration:camera_cc(50723+i)?})};
 let single=get(root,raw,50722).is_none() && get(root,raw,50779).is_none();
 if get(root,raw,50722).is_some()!=get(root,raw,50779).is_some(){return Err(bad().detail(Reason::MissingTag,Some(if get(root,raw,50722).is_none(){50722}else{50779})))}
 if single && [50724,50965].iter().any(|&tag|get(root,raw,tag).is_some()){return Err(err("UNSUPPORTED_DNG_CALIBRATION","probe").detail(Reason::UnsupportedValue,Some(if get(root,raw,50965).is_some(){50965}else{50724})))}
 if !single && (get(root,raw,50964).is_none() || get(root,raw,50965).is_none()){return Err(err("UNSUPPORTED_DNG_CALIBRATION","probe").detail(Reason::MissingTag,Some(if get(root,raw,50964).is_none(){50964}else{50965})))}
 let first=cal(0)?;let second=if single{first}else{cal(1)?};
 let input=Input{calibrations:[first,second],analog_balance:analog.try_into().unwrap(),as_shot_neutral:neutral,calibration_signatures_match:signature(50931)?==signature(50932)?};
 let transform=match white_xy{Some(xy)=>color::resolve_white_xy(&input,xy),None=>color::resolve(&input)}.map_err(|e|e.into_codec())?;
 transform.camera_to_pcs
 };
 // Standard linear ProPhoto/RIMM primaries, normalized to exact D50 PCS white.
 let mut rimm_to_pcs=[[0.7977,0.1352,0.0313],[0.2880,0.7119,0.0001],[0.,0.,0.8249]];
 let white=color::d50_xyz();for r in 0..3{let sum:f64=rimm_to_pcs[r].iter().sum();for c in 0..3{rimm_to_pcs[r][c]*=white[r]/sum;}}
 let inverse=color::inverse(rimm_to_pcs).map_err(|e|e.into_codec())?;
 Ok(Some(ProfileDevelopment{map,camera_to_pcs,camera_to_rimm:color::product(inverse,camera_to_pcs)}))
}
// DNG 1.7.1 §5, p99: clip normalized raw above 1 BEFORE camera-color
// mapping. §ProfileGainTableMap p72 separately preserves post-gain overrange.
// Existing non-PGT development policy is deliberately untouched.
fn normalized_camera(p:&[f32])->Result<[f64;3]>{
 if p.len()!=3||p.iter().any(|v|!v.is_finite()){return Err(err("INVALID_COLOR_METADATA","develop"))}
 Ok(std::array::from_fn(|c|p[c].clamp(0.,1.)as f64))
}
impl ProfileDevelopment{
 pub fn develop(&self,active:Rect,y:usize,h:usize,data:Vec<f32>,baseline:f32,cancel:&AtomicBool)->Result<rawler::pixarray::Color2D<f32,3>>{
  let end=active.y.checked_add(active.h);let tile_end=y.checked_add(h);
  if active.w==0||active.h==0||h==0||y<active.y||end.is_none()||tile_end.is_none()||tile_end>end||active.w.checked_mul(h).and_then(|n|n.checked_mul(3))!=Some(data.len()){return Err(err("INVALID_INPUT","develop"))}
  let exposure=baseline.exp2() as f64;let m=rawler::imgop::xyz::XYZ_TO_SRGB_D50.map(|r|r.map(|x|x as f64));let mut pixels=Vec::with_capacity(active.w*h);
  for (i,p) in data.chunks_exact(3).enumerate(){if i%1024==0&&cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","develop"))}let cam=normalized_camera(p)?;let rimm=color::apply(self.camera_to_rimm,cam).map(|x|x*exposure);let x=(i%active.w)as f64+0.5;let yy=(y+i/active.w-active.y)as f64+0.5;let gain=match &self.map{Some(map)=>map.gain(x/active.w as f64,yy/active.h as f64,rimm)? as f64,None=>1.};let xyz=color::apply(self.camera_to_pcs,cam).map(|x|x*exposure*gain);let rgb=color::apply(m,xyz).map(|x|x as f32);if rgb.iter().any(|x|!x.is_finite()){return Err(err("INVALID_COLOR_METADATA","develop"))}pixels.push(rgb);}
  Ok(rawler::pixarray::Color2D::new_with(pixels,active.w,h))
 }
}
#[cfg(test)]
mod tests {
 use super::*;
 #[test]
 fn new_linear_color_route_fails_closed_without_required_calibration(){
  let mut root=Ifd::new();
  assert!(parse(&root,&root,ColorRoute::Legacy).unwrap().is_none());
  assert!(parse(&root,&root,ColorRoute::NewSof3LinearPrecision).is_err());
  // Scientific calibration values from CC0 raw.pixls.us sample6734; no image or EXIF personal fields.
  root.insert(50721,Value{typ:10,count:9,bytes:vec![27, 3, 0, 0, 0, 4, 0, 0, 60, 255, 255, 255, 0, 4, 0, 0, 136, 255, 255, 255, 0, 4, 0, 0, 170, 253, 255, 255, 0, 4, 0, 0, 211, 5, 0, 0, 0, 4, 0, 0, 93, 0, 0, 0, 0, 4, 0, 0, 81, 255, 255, 255, 0, 4, 0, 0, 75, 1, 0, 0, 0, 4, 0, 0, 201, 1, 0, 0, 0, 4, 0, 0],endian:crate::tiff::Endian::Le});
  root.insert(50722,Value{typ:10,count:9,bytes:vec![103, 6, 0, 0, 0, 4, 0, 0, 86, 252, 255, 255, 0, 4, 0, 0, 163, 255, 255, 255, 0, 4, 0, 0, 177, 254, 255, 255, 0, 4, 0, 0, 103, 5, 0, 0, 0, 4, 0, 0, 27, 0, 0, 0, 0, 4, 0, 0, 239, 255, 255, 255, 0, 4, 0, 0, 188, 0, 0, 0, 0, 4, 0, 0, 19, 2, 0, 0, 0, 4, 0, 0],endian:crate::tiff::Endian::Le});
  root.insert(50723,Value{typ:10,count:9,bytes:vec![243, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 4, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 232, 3, 0, 0, 0, 4, 0, 0],endian:crate::tiff::Endian::Le});
  root.insert(50724,Value{typ:10,count:9,bytes:vec![243, 3, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 4, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 232, 3, 0, 0, 0, 4, 0, 0],endian:crate::tiff::Endian::Le});
  root.insert(50728,Value{typ:5,count:3,bytes:vec![19, 2, 0, 0, 0, 4, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 9, 0, 0, 0, 16, 0, 0, 0],endian:crate::tiff::Endian::Le});
  root.insert(50778,Value{typ:3,count:1,bytes:vec![21, 0],endian:crate::tiff::Endian::Le});
  root.insert(50779,Value{typ:3,count:1,bytes:vec![17, 0],endian:crate::tiff::Endian::Le});
  root.insert(50964,Value{typ:10,count:9,bytes:vec![143, 2, 0, 0, 0, 4, 0, 0, 163, 0, 0, 0, 0, 4, 0, 0, 169, 0, 0, 0, 0, 4, 0, 0, 5, 1, 0, 0, 0, 4, 0, 0, 18, 3, 0, 0, 0, 4, 0, 0, 233, 255, 255, 255, 0, 4, 0, 0, 43, 0, 0, 0, 0, 4, 0, 0, 129, 254, 255, 255, 0, 4, 0, 0, 161, 4, 0, 0, 0, 4, 0, 0],endian:crate::tiff::Endian::Le});
  root.insert(50965,Value{typ:10,count:9,bytes:vec![249, 1, 0, 0, 0, 4, 0, 0, 15, 1, 0, 0, 0, 4, 0, 0, 211, 0, 0, 0, 0, 4, 0, 0, 90, 0, 0, 0, 0, 4, 0, 0, 116, 3, 0, 0, 0, 4, 0, 0, 50, 0, 0, 0, 0, 4, 0, 0, 197, 255, 255, 255, 0, 4, 0, 0, 7, 253, 255, 255, 0, 4, 0, 0, 130, 6, 0, 0, 0, 4, 0, 0],endian:crate::tiff::Endian::Le});
  assert!(parse(&root,&root,ColorRoute::NewSof3LinearPrecision).unwrap().is_some());
  assert!(parse(&root,&root,ColorRoute::Legacy).unwrap().is_none());
  for tag in [50721,50722,50728,50778,50779,50964,50965]{let mut missing=root.clone();missing.remove(&tag);assert!(parse(&missing,&missing,ColorRoute::NewSof3LinearPrecision).is_err());}
  root.insert(50728,Value{typ:5,count:3,bytes:vec![0;24],endian:crate::tiff::Endian::Le});
  assert!(parse(&root,&root,ColorRoute::NewSof3LinearPrecision).is_err());
 }
 #[test]
 fn normalized_raw_clips_before_color_mapping_and_rejects_nonfinite(){
  assert_eq!(normalized_camera(&[-0.1,0.5,1.1]).unwrap(),[0.,0.5,1.]);
  for bad in [f32::NAN,f32::INFINITY,f32::NEG_INFINITY]{assert!(normalized_camera(&[bad,0.,1.]).is_err());}
 }
 #[test]
 fn direct_development_rejects_invalid_geometry_without_panicking() {
  let mut b=Vec::new();for n in [1u32,1]{b.extend(n.to_le_bytes());}for x in [1f64,1.,0.,0.]{b.extend(x.to_le_bytes());}b.extend(2u32.to_le_bytes());for x in [1f32,0.,0.,0.,0.,2.,2.]{b.extend(x.to_le_bytes());}
  let map=ProfileGainTable::parse(&Value{typ:7,count:b.len() as u32,bytes:b,endian:crate::tiff::Endian::Le}).unwrap();
  let p=ProfileDevelopment{map:Some(map),camera_to_pcs:[[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]],camera_to_rimm:[[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]]};let c=AtomicBool::new(false);
  let bright=p.develop(Rect{x:0,y:0,w:1,h:1},0,1,vec![2.,2.,2.],0.,&c).unwrap();
  assert!(bright.pixels().iter().flatten().any(|v|*v>1.));

  for (area,y,h) in [(Rect{x:0,y:1,w:1,h:1},0,1),(Rect{x:0,y:0,w:1,h:1},1,1),(Rect{x:0,y:usize::MAX,w:1,h:1},usize::MAX,1),(Rect{x:0,y:0,w:usize::MAX,h:2},0,2),(Rect{x:0,y:0,w:1,h:1},0,0)] {
   assert!(p.develop(area,y,h,vec![],0.,&c).is_err());
  }
 }
}

#[cfg(test)]
mod wb_metadata_tests {
 use super::*;
 use crate::tiff::Endian;
 fn rational(values:&[u32])->Value {Value{typ:5,count:values.len() as u32,bytes:values.iter().flat_map(|&x|[x.to_le_bytes(),10000u32.to_le_bytes()].concat()).collect(),endian:Endian::Le}}
 #[test]
 fn xy_and_neutral_are_mutually_exclusive_and_shape_checked() {
  let mut r=Ifd::new();r.insert(50729,rational(&[3457,3585]));
  assert_eq!(white_balance(&r,&r).unwrap(),([1.;3],Some([0.3457,0.3585])));
  r.insert(50728,rational(&[5000,10000,7000]));
  assert_eq!(white_balance(&r,&r).unwrap_err().tag,Some(50729));
  r.remove(&50729);assert_eq!(white_balance(&r,&r).unwrap().0,[0.5,1.,0.7]);
  r.insert(50728,Value{typ:3,count:3,bytes:[1u16,2,1].into_iter().flat_map(u16::to_le_bytes).collect(),endian:Endian::Le});
  assert_eq!(white_balance(&r,&r).unwrap().0,[1.,2.,1.]);
  r.remove(&50728);r.insert(50729,rational(&[5000,5000]));assert!(white_balance(&r,&r).is_err());
  r.insert(50729,rational(&[3457]));assert!(white_balance(&r,&r).is_err());
 }
}
