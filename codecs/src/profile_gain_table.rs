//! DNG 1.6/1.7 ProfileGainTableMap. Typed parser and interpolation only;
//! caller MUST supply exposure-adjusted RIMM (D50), not camera RGB or sRGB.
//! Adobe DNG 1.7.1 pp71-73: half-pixel ActiveArea coordinates, table index t*N.
use crate::{error::{err,Result},tiff::{Endian,Value}};
#[track_caller]
fn bad()->crate::error::CodecError{err("INVALID_COLOR_METADATA","probe")}
pub const MAX_BYTES:usize=256*1024;
#[derive(Clone,Debug)]pub struct ProfileGainTable{v:usize,h:usize,n:usize,spacing:[f64;2],origin:[f64;2],weights:[f64;5],gains:Vec<f32>}
impl ProfileGainTable{
 pub fn parse(value:&Value)->Result<Self>{
  let b=&value.bytes;if value.typ!=7||b.len()!=value.count as usize||b.len()<68||b.len()>MAX_BYTES{return Err(bad())}
  let u=|o|value.endian.u32(&b[o..]);
  let f=|o|f32::from_bits(u(o));
  let d=|o|{let a:[u8;8]=b[o..o+8].try_into().unwrap();match value.endian{Endian::Le=>f64::from_le_bytes(a),Endian::Be=>f64::from_be_bytes(a)}};
  let (v,h,n)=(u(0) as usize,u(4) as usize,u(40) as usize);
  if v==0||h==0||n<2||v.checked_mul(h).and_then(|x|x.checked_mul(n)).and_then(|x|x.checked_mul(4)).and_then(|x|x.checked_add(64))!=Some(b.len()){return Err(bad())}
  let spacing=[d(8),d(16)];let origin=[d(24),d(32)];let weights=std::array::from_fn(|i|f(44+4*i) as f64);
  if spacing.iter().any(|x|!x.is_finite()||*x<=0.||*x<1e-12||*x>1e12)||origin.iter().any(|x|!x.is_finite()||x.abs()>1e12)||weights.iter().any(|x|!x.is_finite()||x.abs()>1e6){return Err(bad())}
  let gains=(64..b.len()).step_by(4).map(f).collect::<Vec<_>>();if gains.iter().any(|x|!x.is_finite()||*x<0.||*x>65536.){return Err(bad())}
  Ok(Self{v,h,n,spacing,origin,weights,gains})
 }
 pub fn storage_bytes(&self)->u64{self.gains.len() as u64*4+std::mem::size_of::<Self>() as u64}
 /// x/y are normalized ActiveArea pixel centers, already orientation-independent.
 pub fn gain(&self,x:f64,y:f64,rimm:[f64;3])->Result<f32>{
  if !x.is_finite()||!y.is_finite()||rimm.iter().any(|x|!x.is_finite()){return Err(err("INVALID_COLOR_METADATA","develop"))}
  let gx=((x-self.origin[1])/self.spacing[1]).clamp(0.,(self.h-1) as f64);let gy=((y-self.origin[0])/self.spacing[0]).clamp(0.,(self.v-1) as f64);
  let x0=gx.floor() as usize;let y0=gy.floor() as usize;let x1=(x0+1).min(self.h-1);let y1=(y0+1).min(self.v-1);let fx=gx-x0 as f64;let fy=gy-y0 as f64;
  let values=[rimm[0],rimm[1],rimm[2],rimm.iter().copied().fold(f64::INFINITY,f64::min),rimm.iter().copied().fold(f64::NEG_INFINITY,f64::max)];
  let s=values.iter().zip(self.weights).map(|(a,b)|a*b).sum::<f64>().clamp(0.,1.)*self.n as f64;let i0=(s.floor() as usize).min(self.n-1);let i1=(i0+1).min(self.n-1);let ft=s-i0 as f64;
  let at=|xx,yy|{let k=(yy*self.h+xx)*self.n;self.gains[k+i0] as f64*(1.-ft)+self.gains[k+i1] as f64*ft};
  let a=at(x0,y0)*(1.-fx)+at(x1,y0)*fx;let b=at(x0,y1)*(1.-fx)+at(x1,y1)*fx;let out=a*(1.-fy)+b*fy;
  if !out.is_finite()||out<0.{return Err(err("INVALID_COLOR_METADATA","develop"))}Ok(out as f32)
 }
}
/// DNG linearization precedes black subtraction; values beyond the table use
/// its final entry (DNG 1.7.1 chapter5), not a bit shift or modulo operation.
#[derive(Clone,Debug)]pub struct LinearizationTable(Vec<u16>);
impl LinearizationTable{
 pub fn parse(v:&Value)->Result<Self>{if v.typ!=3||v.count==0||v.count>65536||v.bytes.len()!=v.count as usize*2{return Err(bad())}Ok(Self(v.bytes.chunks_exact(2).map(|x|v.endian.u16(x)).collect()))}
 pub fn apply(&self,code:u16)->u16{self.0[(code as usize).min(self.0.len()-1)]}
 pub fn storage_bytes(&self)->u64{self.0.len() as u64*2}
}
#[cfg(test)]mod tests{
 use super::*;
 fn value()->Value{let mut b=Vec::new();for x in [1u32,1]{b.extend(x.to_le_bytes());}for x in [1f64,1.,0.,0.]{b.extend(x.to_le_bytes());}b.extend(4u32.to_le_bytes());for x in [1f32,0.,0.,0.,0.]{b.extend(x.to_le_bytes());}for x in [1f32,2.,4.,8.]{b.extend(x.to_le_bytes());}Value{typ:7,count:b.len() as u32,bytes:b,endian:Endian::Le}}
 #[test]fn table_uses_n_and_clamps_last(){let p=ProfileGainTable::parse(&value()).unwrap();assert_eq!(p.gain(0.5,0.5,[0.25,0.,0.]).unwrap(),2.);assert_eq!(p.gain(0.5,0.5,[1.,0.,0.]).unwrap(),8.);assert_eq!(p.gain(-1.,2.,[0.,0.,0.]).unwrap(),1.);}
 #[test]fn rejects_nan_and_overflow_shape(){let mut v=value();v.bytes[64..68].copy_from_slice(&f32::NAN.to_le_bytes());assert!(ProfileGainTable::parse(&v).is_err());let mut v=value();v.bytes[0..4].copy_from_slice(&u32::MAX.to_le_bytes());assert!(ProfileGainTable::parse(&v).is_err());}
 #[test]fn linearization_last_entry_not_wrap(){let v=Value{typ:3,count:3,bytes:vec![0,0,10,0,255,255],endian:Endian::Le};let t=LinearizationTable::parse(&v).unwrap();assert_eq!(t.apply(1),10);assert_eq!(t.apply(4000),65535);}
}
