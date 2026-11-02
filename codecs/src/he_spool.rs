//! Bounded anonymous Bayer spool for the isolated Nikon integration candidate; Linux/Android.
use std::{fs::{File,OpenOptions},io::{self,Write},os::unix::{fs::{OpenOptionsExt,FileExt},io::AsRawFd},path::Path,sync::atomic::{AtomicBool,Ordering}};
use std::ffi::c_void;
#[repr(C)] pub struct DecodeResult {pub abi_version:u32,pub width:u32,pub height:u32,pub rows_written:u32,pub status:i32}
unsafe extern "C" {fn posix_fallocate(fd:i32,offset:i64,len:i64)->i32;
 fn fr_he_decode_v1(p:*const u8,n:usize,w:u32,h:u32,sink:extern "C" fn(*mut c_void,*const u16,u32,u32,u32)->i32,sc:*mut c_void,cancel:extern "C" fn(*mut c_void)->i32,cc:*mut c_void,result:*mut DecodeResult)->i32;}
#[derive(PartialEq)] enum State {Created,Decoding,Complete,Failed}
pub struct RawSpool {state:State,file:File,width:u32,height:u32,next:u32,buffer:Vec<u8>,error:Option<io::Error>}
impl RawSpool {
 /// Caller supplies unique leaf in operation-private0700 directory. Anonymous before pixels.
 pub fn create(path:&Path,w:u32,h:u32)->io::Result<Self>{
  if !(((64..=6064).contains(&w)&&w%8==0&&(64..=4040).contains(&h)&&h%4==0)||(w==8280&&h==5520))||u64::from(w)*u64::from(h)>52_000_000{return Err(io::Error::new(io::ErrorKind::InvalidInput,"pixel cap"));}
  Self::create_bayer(path,w,h)
 }
 pub fn create_bayer(path:&Path,w:u32,h:u32)->io::Result<Self>{
  if w==0 || w>16384 || w%2!=0 || h==0 || h%2!=0 || u64::from(w)*u64::from(h)>52_000_000 {return Err(io::Error::new(io::ErrorKind::InvalidInput,"pixel cap"));}
  let f=OpenOptions::new().read(true).write(true).create_new(true).mode(0o600).custom_flags(0x20000/*O_NOFOLLOW*/).open(path)?;
  // If unlink fails, do not write; caller owns cleanup of this newly created path.
  std::fs::remove_file(path)?;
  if !f.metadata()?.is_file(){return Err(io::Error::new(io::ErrorKind::InvalidInput,"not regular"));}
  let code=unsafe{posix_fallocate(f.as_raw_fd(),0,i64::from(w)*i64::from(h)*2)};
  if code!=0{return Err(io::Error::from_raw_os_error(code));}
  let mut buffer=Vec::new();buffer.try_reserve_exact(w as usize*64*2).map_err(|_|io::Error::new(io::ErrorKind::OutOfMemory,"allocation"))?;buffer.resize(w as usize*64*2,0);
  Ok(Self{state:State::Created,file:f,width:w,height:h,next:0,buffer,error:None})
 }
 pub fn decode(&mut self,encoded:&[u8],cancel:&AtomicBool)->io::Result<()> {
  if self.state!=State::Created{return Err(io::Error::new(io::ErrorKind::InvalidInput,"spool state"));}self.state=State::Decoding;
  let mut r=DecodeResult{abi_version:0,width:0,height:0,rows_written:0,status:-1};
  let status=unsafe{fr_he_decode_v1(encoded.as_ptr(),encoded.len(),self.width,self.height,sink,self as *mut _ as *mut c_void,cancelled,cancel as *const _ as *mut c_void,&mut r)};
  self.state=State::Failed;
  if let Some(e)=self.error.take(){return Err(e)}
  if r.abi_version!=1||r.status!=status||r.width!=self.width||r.height!=self.height{return Err(io::Error::new(io::ErrorKind::InvalidData,"ABI result"));}
  if status!=0||self.next!=self.height||r.rows_written!=self.height{return Err(io::Error::new(if status==3{io::ErrorKind::Interrupted}else if status==1{io::ErrorKind::Unsupported}else if status==5{io::ErrorKind::OutOfMemory}else if status==2{io::ErrorKind::InvalidData}else{io::ErrorKind::Other},format!("HE status {status}")));}
  if cancel.load(Ordering::Relaxed){return Err(io::Error::new(io::ErrorKind::Interrupted,"cancelled"));}
  self.file.sync_all()?;if cancel.load(Ordering::Relaxed){return Err(io::Error::new(io::ErrorKind::Interrupted,"cancelled"));}self.buffer=Vec::new();self.state=State::Complete;Ok(())
 }
 pub fn decode_rows(&mut self,decoder:&dyn rawler::decoders::Decoder,source:&rawler::rawsource::RawSource,cancel:&AtomicBool)->io::Result<()> {
  if self.state!=State::Created {return Err(io::Error::new(io::ErrorKind::InvalidInput,"spool state"));}
  self.state=State::Decoding;
  let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||decoder.bayer_rows(source,&mut |row,pixels| {
    if row!=self.next as usize || pixels.len()!=self.width as usize || self.next>=self.height {
      return Err(rawler::RawlerError::DecoderFailed("row contract".into()));
    }
    for (i,v) in pixels.iter().enumerate(){self.buffer[2*i..2*i+2].copy_from_slice(&v.to_le_bytes());}
    if let Err(e)=self.file.write_all(&self.buffer[..pixels.len()*2]) {self.error=Some(e);return Err(rawler::RawlerError::DecoderFailed("row sink".into()));}
    self.next+=1;Ok(())
  },&||cancel.load(Ordering::Relaxed))));
  self.state=State::Failed;
  if let Some(e)=self.error.take(){return Err(e);}
  if cancel.load(Ordering::Relaxed){return Err(io::Error::new(io::ErrorKind::Interrupted,"cancelled"));}
  if matches!(&result,Ok(Err(rawler::RawlerError::RowAllocationFailed))) {return Err(io::Error::new(io::ErrorKind::OutOfMemory,"row allocation"));}
  if !matches!(result,Ok(Ok(()))) || self.next!=self.height {return Err(io::Error::new(io::ErrorKind::InvalidData,"NEF row decode"));}
  self.file.sync_all()?;
  if cancel.load(Ordering::Relaxed){return Err(io::Error::new(io::ErrorKind::Interrupted,"cancelled"));}
  self.buffer=Vec::new();self.state=State::Complete;Ok(())
 }
 /// Mirrors baseline normalized_tile expression; absolute sensor coordinates, no orientation.
 pub fn normalized(&self,x:u32,y:u32,width:u32,rows:u32,black:[f32;4],white:[f32;4],cancel:&AtomicBool)->io::Result<Vec<f32>>{
  if self.state!=State::Complete||width==0||rows==0||rows>176||x>self.width||width>self.width-x||y>self.height||rows>self.height-y||black.iter().zip(white).any(|(b,w)|!b.is_finite()||*b<0.||!w.is_finite()||w<=*b){return Err(io::Error::new(io::ErrorKind::InvalidInput,"metadata/bounds"));}
  let mut out=Vec::new();out.try_reserve_exact(width as usize*rows as usize).map_err(|_|io::Error::new(io::ErrorKind::OutOfMemory,"allocation"))?;let mut raw=Vec::new();raw.try_reserve_exact(width as usize*2).map_err(|_|io::Error::new(io::ErrorKind::OutOfMemory,"allocation"))?;raw.resize(width as usize*2,0);
  for sy in y..y+rows{
   if cancel.load(Ordering::Relaxed){return Err(io::Error::new(io::ErrorKind::Interrupted,"cancelled"));}
   self.file.read_exact_at(&mut raw,(u64::from(sy)*u64::from(self.width)+u64::from(x))*2)?;
   for (i,sx) in (x..x+width).enumerate(){let ch=((sy%2)*2+sx%2) as usize;let v=u16::from_le_bytes([raw[2*i],raw[2*i+1]]);out.push((v as f32-black[ch]).max(0.)/(white[ch]-black[ch]));}
  }Ok(out)
 }
}
extern "C" fn cancelled(p:*mut c_void)->i32{unsafe{(*(p as *const AtomicBool)).load(Ordering::Relaxed) as i32}}
extern "C" fn sink(p:*mut c_void,data:*const u16,first:u32,rows:u32,w:u32)->i32 {
 // Never unwind across C: preserve I/O error, convert panic to explicit failure.
 let result=std::panic::catch_unwind(std::panic::AssertUnwindSafe(||{let s=unsafe{&mut*(p as *mut RawSpool)};
  if first!=s.next||w!=s.width||rows==0||rows>64||rows>s.height-s.next{return Err(io::Error::new(io::ErrorKind::InvalidData,"row contract"));}
  let pixels=unsafe{std::slice::from_raw_parts(data,w as usize*rows as usize)};
  for(i,v)in pixels.iter().enumerate(){s.buffer[2*i..2*i+2].copy_from_slice(&v.to_le_bytes());}
  s.file.write_all(&s.buffer[..pixels.len()*2])?;s.next+=rows;Ok(())}));
 match result{Ok(Ok(()))=>0,Ok(Err(e))=>{unsafe{(*(p as *mut RawSpool)).error=Some(e)};1},Err(_)=>{unsafe{(*(p as *mut RawSpool)).error=Some(io::Error::new(io::ErrorKind::Other,"sink panic"))};1}}
}
