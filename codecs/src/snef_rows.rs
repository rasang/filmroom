// Nikon small-NEF row conversion, derived from official LibRaw 0.22.2:
// src/decoders/decoders_libraw.cpp::nikon_load_sraw and
// src/utils/open.cpp Nikon sRAW inverse curve initialization.
// Original decoder copyright 2019-2022 LibRaw LLC (info@libraw.org).
// Original inverse-curve initialization copyright 2019-2025 LibRaw LLC.
// Redistribution under LGPL-2.1 (or the upstream alternative CDDL-1.0).
// See NOTICE and the upstream license copies accompanying this candidate.
// This is not a newly fitted transfer curve. Coefficients/operation order are
// preserved; raw u16 output must match the independent official-wheel oracle.
use std::{io,sync::atomic::{AtomicBool,Ordering}};
fn invalid()->io::Error {io::Error::new(io::ErrorKind::InvalidData,"Nikon small RAW structure")}
fn cancelled(c:&AtomicBool)->io::Result<()> {if c.load(Ordering::Relaxed){Err(io::Error::new(io::ErrorKind::Interrupted,"cancelled"))}else{Ok(())}}
pub fn curve()->[u16;3073] {
 let (b1,b2,b3,b4)=(-5.79342238397656E-02f64,3.28163551282665f64,-8.43136004842678f64,1.03533181861023E+01f64);
 let mut curve=[0u16;3073];
 for (i,out) in curve.iter_mut().enumerate(){let x=i as f64/3072.;let y=1.-(-b1*x-b2*x*x-b3*x*x*x-b4*x*x*x*x).exp();*out=(y.max(0.)*16383.) as u16;}
 curve
}
fn unpack(p:&[u8])->(u16,u16,u16,u16){
 (u16::from(p[0])|((u16::from(p[1])&15)<<8),(u16::from(p[2])<<4)|(u16::from(p[1])>>4),u16::from(p[3])|((u16::from(p[4])&15)<<8),(u16::from(p[5])<<4)|(u16::from(p[4])>>4))
}
fn rgb(y:u16,cb:u16,cr:u16,curve:&[u16;3073])->[u16;3]{
 let mut y=y as f32/2549.;let mut ch2=(i32::from(cb)-1280) as f32/1536.;let mut ch3=(i32::from(cr)-1280) as f32/1536.;
 if y>1. {y=1.;}if y>0.803 {ch2=0.5;ch3=0.5;}
 let r=(y+1.40200*(ch3-0.5)).clamp(0.,1.);
 let g=(y-0.34414*(ch2-0.5)-0.71414*(ch3-0.5)).clamp(0.,1.);
 let b=(y+1.77200*(ch2-0.5)).clamp(0.,1.);
 [curve[(r*3072.)as usize],curve[(g*3072.)as usize],curve[(b*3072.)as usize]]
}
/// Input is precisely width*height*3 packed bytes; each6bytes encodes2Y+Cb+Cr.
/// The sink borrows exactly one complete RGB row and must not retain its slice.
/// Camera WB has already been applied; output is the LibRaw inverse-curve RGB
/// domain, black0/white16383. Domain/render contract is explicit in the adapter.
pub fn decode_rows(input:&[u8],width:usize,height:usize,cancel:&AtomicBool,sink:&mut dyn FnMut(usize,&[[u16;3]])->io::Result<()>)->io::Result<()> {
 if width<2 || width>16384 || width%2!=0 || height==0 || width.checked_mul(height).is_none_or(|n|n>52_000_000) || width.checked_mul(height).and_then(|n|n.checked_mul(3))!=Some(input.len()){return Err(invalid());}
 cancelled(cancel)?;let table=curve();let mut out=Vec::new();out.try_reserve_exact(width).map_err(|_|io::Error::new(io::ErrorKind::OutOfMemory,"row allocation"))?;out.resize(width,[0;3]);
 for (y,row) in input.chunks_exact(width*3).enumerate(){
  cancelled(cancel)?;
  for col in (0..width).step_by(2){
   if col%256==0 {cancelled(cancel)?;}
   let at=col*3;let(y0,y1,cb,cr)=unpack(&row[at..at+6]);
   let (_,_,next_cb,next_cr)=if col+2<width {unpack(&row[at+6..at+12])}else{(0,0,cb,cr)};
   out[col]=rgb(y0,cb,cr,&table);
   out[col+1]=rgb(y1,((u32::from(cb)+u32::from(next_cb))/2) as u16,((u32::from(cr)+u32::from(next_cr))/2) as u16,&table);
  }
  sink(y,&out)?;
 }
 cancelled(cancel)
}
#[cfg(test)] mod tests {
 use super::*;
 fn packed(y0:u16,y1:u16,cb:u16,cr:u16)->[u8;6]{[y0 as u8,((y0>>8)|((y1&15)<<4))as u8,(y1>>4)as u8,cb as u8,((cb>>8)|((cr&15)<<4))as u8,(cr>>4)as u8]}
 #[test]fn nibble_roundtrip(){for v in [0,1,15,16,255,256,2048,4095]{assert_eq!(unpack(&packed(v,4095-v,4095-v,v)),(v,4095-v,4095-v,v));}}
 #[test]fn odd_chroma_and_right_edge(){let mut data=Vec::new();data.extend(packed(1000,1000,1001,2001));data.extend(packed(1000,1000,3000,3000));let mut rows=Vec::new();decode_rows(&data,4,1,&AtomicBool::new(false),&mut|y,row|{assert_eq!(y,0);rows.extend_from_slice(row);Ok(())}).unwrap();let c=curve();assert_eq!(rows,[rgb(1000,1001,2001,&c),rgb(1000,2000,2500,&c),rgb(1000,3000,3000,&c),rgb(1000,3000,3000,&c)]);}
 #[test]fn bounds_cancel_sink(){let cancel=AtomicBool::new(false);let mut calls=0;assert!(decode_rows(&[0;5],2,1,&cancel,&mut|_,_|{calls+=1;Ok(())}).is_err());assert_eq!(calls,0);cancel.store(true,Ordering::Relaxed);assert_eq!(decode_rows(&[0;6],2,1,&cancel,&mut|_,_|{calls+=1;Ok(())}).unwrap_err().kind(),io::ErrorKind::Interrupted);assert_eq!(calls,0);cancel.store(false,Ordering::Relaxed);assert_eq!(decode_rows(&[0;6],2,1,&cancel,&mut|_,_|Err(io::Error::new(io::ErrorKind::WriteZero,"test"))).unwrap_err().kind(),io::ErrorKind::WriteZero);}
 #[test]fn highlight_rule_and_curve_extremes(){let c=curve();assert_eq!(c[0],0);assert!(c[3072]<=16383);for y in [2047,2549,4095]{let a=rgb(y,0,4095,&c);let b=rgb(y,2048,2048,&c);if y as f32/2549.>0.803 {assert_eq!(a,b);assert_eq!(a[0],a[1]);assert_eq!(a[1],a[2]);}}}
}
