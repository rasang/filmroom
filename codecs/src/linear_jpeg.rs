//! Bounded SOF3 raster adapter for Linear DNG. Predictor-1/restart behavior is
//! checked against LibRaw 0.22.2 predictor structure; uint16 reconstruction
//! is verified against Adobe SDK 1.7.1 Stage1, including over-nominal codes.
//! No padded input pump.
//! Deliberately rejects subsampling, multiple scans, nonzero point transforms,
//! and non-row-aligned restart intervals until independently validated.
use crate::error::{err,io_error,Result};
use std::{fs::File,io::{BufReader,Read,Seek,SeekFrom,Take},sync::atomic::{AtomicBool,Ordering}};
fn invalid()->crate::error::CodecError{err("INVALID_INPUT","decode")}
fn unsupported()->crate::error::CodecError{err("UNSUPPORTED_COMPRESSION","decode")}
struct Input<'a>{r:BufReader<Take<&'a mut File>>, byte:u8,left:u8}
impl Input<'_>{
 fn byte(&mut self)->Result<u8>{let mut b=[0];self.r.read_exact(&mut b).map_err(|e|if e.kind()==std::io::ErrorKind::UnexpectedEof{invalid()}else{io_error(e,"decode")})?;Ok(b[0])}
 fn u16(&mut self)->Result<u16>{Ok(u16::from_be_bytes([self.byte()?,self.byte()?]))}
 fn marker(&mut self)->Result<u8>{if self.byte()?!=255{return Err(invalid())}self.byte()}
 fn bits(&mut self,n:u8)->Result<u32>{let mut v=0;for _ in 0..n{if self.left==0{self.byte=self.byte()?;if self.byte==255&&self.byte()?!=0{return Err(invalid())}self.left=8;}self.left-=1;v=(v<<1)|u32::from((self.byte>>self.left)&1);}Ok(v)}
 fn align(&mut self)->Result<()>{if self.left>0&&self.byte&((1u8<<self.left)-1)!=(1u8<<self.left)-1{return Err(invalid())}self.left=0;Ok(())}
}
#[derive(Clone)]struct Huff{first:[u32;16],count:[u32;16],index:[usize;16],values:Vec<u8>}
impl Huff{
 fn parse(s:&[u8])->Result<(Self,usize)>{if s.len()<16{return Err(invalid())}let n=s[..16].iter().map(|&x|usize::from(x)).sum::<usize>();if n==0||n>17||s.len()<16+n{return Err(invalid())}let mut code=0u32;let mut at=0;let mut first=[0;16];let mut count=[0;16];let mut index=[0;16];for i in 0..16{first[i]=code;count[i]=s[i] as u32;index[i]=at;for _ in 0..s[i]{if code>=(1u32<<(i+1))-1||s[16+at]>16{return Err(invalid())}at+=1;code+=1;}code<<=1;}Ok((Self{first,count,index,values:s[16..16+n].to_vec()},16+n))}
 fn diff(&self,input:&mut Input)->Result<i32>{let mut code=0u32;for i in 0..16{code=(code<<1)|input.bits(1)?;if code>=self.first[i]&&code-self.first[i]<self.count[i]{let n=self.values[self.index[i]+(code-self.first[i]) as usize];if n==0{return Ok(0)}if n==16{return Ok(-32768)}let v=input.bits(n)? as i32;return Ok(if v<(1<<(n-1)){v-((1<<n)-1)}else{v})}}Err(invalid())}
}
/// Calls sink only for complete rows. Failure invalidates ALL previously emitted
/// rows; the caller must retain its output guard until EOI and exact extent pass.
pub fn rows(file:&mut File,offset:u64,bytes:u64,width:usize,height:usize,precision:u8,cancel:&AtomicBool,mut sink:impl FnMut(usize,&[u16])->Result<()>)->Result<()>{
 if width==0||height==0{return Err(invalid())}
 if width>32768||height>32768{return Err(err("EDGE_LIMIT","decode"))}
 crate::validate_dimensions(width as u32,height as u32)?;
 if !matches!(precision,12|14|16){return Err(unsupported())}
 if offset.checked_add(bytes).is_none_or(|e|file.metadata().map(|m|e>m.len()).unwrap_or(true)){return Err(invalid())}
 file.seek(SeekFrom::Start(offset)).map_err(|e|io_error(e,"decode"))?;
 let mut input=Input{r:BufReader::with_capacity(65536,file.take(bytes)),byte:0,left:0};
 if input.marker()?!=0xd8{return Err(invalid())}
 let mut tables:[Option<Huff>;4]=[None,None,None,None];let mut ids=Vec::new();let mut selectors=[0usize;3];let mut restart=0usize;let mut saw_restart=false;let mut header_bytes=2usize;
 loop{
  if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","decode"))}
  let marker=input.marker()?;let len=input.u16()? as usize;if len<2{return Err(invalid())}header_bytes=header_bytes.checked_add(len+2).ok_or_else(invalid)?;if header_bytes>65536{return Err(invalid())}
  let mut s=vec![0;len-2];input.r.read_exact(&mut s).map_err(|_|invalid())?;
  match marker{
   0xc3=>{if !ids.is_empty()||s.len()!=15||s[0]!=precision||u16::from_be_bytes([s[1],s[2]]) as usize!=height||u16::from_be_bytes([s[3],s[4]]) as usize!=width||s[5]!=3{return Err(unsupported())}for c in s[6..].chunks_exact(3){if c[1]!=0x11||c[2]!=0||ids.contains(&c[0]){return Err(unsupported())}ids.push(c[0]);}},
   0xc4=>{let mut p=0;while p<s.len(){let id=s[p] as usize;p+=1;if id>3||tables[id].is_some(){return Err(unsupported())}let (h,n)=Huff::parse(&s[p..])?;tables[id]=Some(h);p+=n;}},
   0xdd=>{if saw_restart||s.len()!=2{return Err(invalid())}saw_restart=true;restart=u16::from_be_bytes([s[0],s[1]]) as usize;if restart!=0&&restart%width!=0{return Err(unsupported())}},
   0xda=>{if ids.len()!=3||s.len()!=10||s[0]!=3||s[7]!=1||s[8]!=0||s[9]!=0{return Err(unsupported())}for c in 0..3{if s[1+c*2]!=ids[c]||s[2+c*2]&15!=0{return Err(unsupported())}let id=(s[2+c*2]>>4) as usize;if id>3||tables[id].is_none(){return Err(invalid())}selectors[c]=id;}break;},
   _=>return Err(unsupported()),
  }
 }
 let mut row=vec![0u16;width*3];let mut first=[1i32<<(precision-1);3];let mut rst=0u8;
 for y in 0..height{
  if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","decode"))}
  if restart!=0&&y*width%restart==0{if y!=0{input.align()?;let marker=input.marker()?;if marker!=0xd0+rst{return Err(invalid())}rst=(rst+1)&7;}first=[1i32<<(precision-1);3];}
  let mut left=first;
  for x in 0..width{if x%1024==0&&cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","decode"))}for c in 0..3{let diff=tables[selectors[c]].as_ref().unwrap().diff(&mut input)?;let value=left[c]+diff;// Adobe DNG SDK 1.7.1 dng_lossless_jpeg_shared.cpp stores the
        // predictor result in uint16 for ALL precisions (not masked to Pr).
        // Real Samsung Linear DNG preserves over-white highlight codes >2^Pr.
        // Cross-checked against complete SDK Stage1 output; do not clip here.
        let value=value as u16;row[x*3+c]=value;left[c]=i32::from(value);}if x==0{first=left;}}
  sink(y,&row)?;
 }
 if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","decode"))}input.align()?;if input.marker()?!=0xd9{return Err(invalid())}let mut tail=[0];if input.r.read(&mut tail).map_err(|e|io_error(e,"decode"))?!=0{return Err(invalid())}Ok(())
}
#[cfg(test)]mod tests{
 use super::*;use std::{fs,io::Write};
 fn fixture(w:u16,h:u16,precision:u8,restart_rows:u16)->Vec<u8>{
  let mut b=vec![255,216,255,195,0,17,precision];b.extend(h.to_be_bytes());b.extend(w.to_be_bytes());b.extend([3,0,17,0,1,17,0,2,17,0]);
  // one canonical 1-bit code, value category0
  b.extend([255,196,0,20,0,1]);b.extend([0;15]);b.push(0);
  if restart_rows!=0{b.extend([255,221,0,4]);b.extend((w*restart_rows).to_be_bytes());}
  b.extend([255,218,0,12,3,0,0,1,0,2,0,1,0,0]);
  let mut bits=Vec::new();let mut rst=0;
  for y in 0..h{bits.extend(std::iter::repeat_n(0u8,w as usize*3));if y+1==h||(restart_rows!=0&&(y+1)%restart_rows==0){while bits.len()%8!=0{bits.push(1)}for a in bits.chunks(8){let v=a.iter().fold(0,|v,x|v*2+x);b.push(v);if v==255{b.push(0)}}bits.clear();if y+1!=h{b.extend([255,208+rst]);rst=(rst+1)&7;}}}b.extend([255,217]);b
 }
 fn run(b:&[u8],cancel:&AtomicBool,sink:impl FnMut(usize,&[u16])->Result<()>)->Result<()>{
  static NEXT:std::sync::atomic::AtomicUsize=std::sync::atomic::AtomicUsize::new(0);
  let p=std::env::temp_dir().join(format!("linear-jpeg-test-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));let mut f=std::fs::OpenOptions::new().read(true).write(true).create_new(true).open(&p).unwrap();fs::remove_file(&p).unwrap();f.write_all(b).unwrap();rows(&mut f,0,b.len() as u64,9,5,12,cancel,sink)
 }
 #[test]fn reconstructed_u16_codes_preserve_over_nominal_and_wrapped_feedback(){
  for (category,payload,diff) in [(12u8,4095u32,4095i32),(16u8,0u32,-32768i32)] {
   let mut b=fixture(9,5,12,0);let dht=b.windows(2).position(|v|v==[255,196]).unwrap();b[dht+21]=category;
   let sos=b.windows(2).position(|v|v==[255,218]).unwrap();b.truncate(sos+14);
   let mut bits=Vec::new();for _ in 0..9*5*3{bits.push(0u8);if category!=16{for shift in (0..category).rev(){bits.push(((payload>>shift)&1)as u8)}}}
   while bits.len()%8!=0{bits.push(1)}for chunk in bits.chunks(8){let byte=chunk.iter().fold(0u8,|v,b|(v<<1)|b);b.push(byte);if byte==255{b.push(0)}}b.extend([255,217]);
   run(&b,&AtomicBool::new(false),|y,row|{for x in 0..9{let expected=(2048+(y as i32+x as i32+1)*diff) as u16;assert_eq!(&row[x*3..x*3+3],&[expected;3]);}Ok(())}).unwrap();
  }
 }
 #[test]fn predictor_restart_rows_match_handcomputed(){for ri in [0,1,2]{let b=fixture(9,5,12,ri);let mut count=0;run(&b,&AtomicBool::new(false),|y,row|{assert_eq!(y,count);assert_eq!(row,&[2048;27]);count+=1;Ok(())}).unwrap();assert_eq!(count,5);}}
 #[test]fn exact_extent_truncation_marker_and_restart_rejected(){let b=fixture(9,5,12,1);for n in 0..b.len(){assert!(run(&b[..n],&AtomicBool::new(false),|_,_|Ok(())).is_err(),"truncation {n}")}let mut extra=b.clone();extra.push(0);assert!(run(&extra,&AtomicBool::new(false),|_,_|Ok(())).is_err());let mut bad=b.clone();let at=bad.windows(2).position(|x|x==[255,208]).unwrap();bad[at+1]=209;assert!(run(&bad,&AtomicBool::new(false),|_,_|Ok(())).is_err());}
 #[test]fn cancellation_and_sink_failure_stop_before_next_row(){let b=fixture(9,5,12,1);assert_eq!(run(&b,&AtomicBool::new(true),|_,_|panic!()).unwrap_err().code,"CANCELLED");let c=AtomicBool::new(false);let mut count=0;let e=run(&b,&c,|_,_|{count+=1;c.store(true,Ordering::Relaxed);Ok(())}).unwrap_err();assert_eq!(count,1);assert_eq!(e.code,"CANCELLED");assert_eq!(run(&b,&AtomicBool::new(false),|_,_|Err(err("NO_SPACE","spool"))).unwrap_err().code,"NO_SPACE");}
}
