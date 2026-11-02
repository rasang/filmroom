//! Nikon HE / ordinary Bayer row-spool candidate; no preview or guessed metadata.
use crate::{camera_raw::{CameraRaw,NikonStream},develop::{Admission,Budget},dng::Probe,error::{Result,err,io_error},he_spool::RawSpool};
use std::{fs::{File,OpenOptions,DirBuilder},os::unix::fs::{OpenOptionsExt,DirBuilderExt,MetadataExt},os::fd::AsRawFd,path::{Path,PathBuf},sync::atomic::{AtomicBool,AtomicU64,Ordering}};
#[derive(PartialEq)]
pub(crate) struct Identity {dev:u64,ino:u64,pub bytes:u64,mtime:i64,mtime_ns:i64,ctime:i64,ctime_ns:i64}
impl Identity {pub fn read(f:&File)->Result<Self>{let m=f.metadata().map_err(|e|io_error(e,"probe"))?;if !m.is_file(){return Err(err("INVALID_INPUT","probe"));}Ok(Self{dev:m.dev(),ino:m.ino(),bytes:m.len(),mtime:m.mtime(),mtime_ns:m.mtime_nsec(),ctime:m.ctime(),ctime_ns:m.ctime_nsec()})}}
const MIB:u64=1024*1024;const BASE:u64=32*MIB;const SCRATCH:u64=21*MIB;const HALO:usize=8;
unsafe extern "C" {fn fr_he_probe_v1(p:*const u8,n:usize,w:u32,h:u32)->i32;}
pub(crate) fn validate_header(bytes:&[u8],w:u32,h:u32)->Result<()>{match unsafe{fr_he_probe_v1(bytes.as_ptr(),bytes.len(),w,h)}{0=>Ok(()),1=>Err(err("UNSUPPORTED_CAMERA_RAW_VARIANT","probe")),5=>Err(err("MEMORY_BUDGET","probe")),_=>Err(err("INVALID_INPUT","probe"))}}
pub(crate) fn admit(c:&CameraRaw,b:&Budget)->Result<Admission>{
 let memory=b.memory_budget_mi_b.checked_mul(MIB).filter(|v|*v>=BASE&&*v<=crate::camera_raw::CAMERA_RAW_MAX_MEMORY_MIB*MIB).ok_or_else(||err("MEMORY_BUDGET","admission"))?;
 let he=c.he.as_ref().ok_or_else(||err("INVALID_INPUT","admission"))?;let d=&c.development;
 let scratch=match he.kind {NikonStream::He(_)=>{if he.bytes>32*MIB||d.width>8280||d.height>5520{return Err(err("MEMORY_BUDGET","admission"));}SCRATCH},NikonStream::OrdinaryRows=>4*MIB};
 let decode_peak=BASE.checked_add(c.input_bytes).and_then(|v|v.checked_add(scratch)).ok_or_else(||err("MEMORY_BUDGET","admission"))?;
 // Input mapping is dropped before development. HE reserves21MiB; ordinary
 // rows reserve4MiB (<=2MiB serializer, fixed curve/Huffman tables, one u16 row).
 if decode_peak>memory{return Err(err("MEMORY_BUDGET","admission"));}
 let capacity=((memory.min(128*MIB)-BASE)/(d.active.w as u64*96)).saturating_sub(16) as usize;
 let rows=b.raw_batch.camera_rows(capacity.min(80),capacity,160) as u64;
 if rows<8{return Err(err("MEMORY_BUDGET","admission"));}
 let disk=(d.width as u64).checked_mul(d.height as u64).and_then(|v|v.checked_mul(2)).and_then(|v|v.checked_add(c.probe().output_bytes)).ok_or_else(||err("DISK_BUDGET","admission"))?;
 if disk>b.disk_budget_bytes.min(1024*MIB){return Err(err("DISK_BUDGET","admission"));}
 Ok(Admission{memory_budget_bytes:memory,estimated_peak_bytes:decode_peak.max(BASE+96*d.active.w as u64*(rows+16)),required_disk_bytes:disk,strip_rows:rows as u32})
}
struct OutputGuard<'a>(&'a Path,bool);impl Drop for OutputGuard<'_>{fn drop(&mut self){if !self.1{let _=std::fs::remove_file(self.0);}}}
struct DirGuard(PathBuf);impl Drop for DirGuard{fn drop(&mut self){let _=std::fs::remove_dir(&self.0);}}
static SEQUENCE:AtomicU64=AtomicU64::new(0);
fn cancelled(c:&AtomicBool)->Result<()>{if c.load(Ordering::Relaxed){Err(err("CANCELLED","decode"))}else{Ok(())}}
fn spool_error(e:std::io::Error)->crate::error::CodecError {match e.kind(){std::io::ErrorKind::OutOfMemory=>err("MEMORY_BUDGET","decode"),std::io::ErrorKind::InvalidData=>err("INVALID_INPUT","decode"),std::io::ErrorKind::Interrupted=>err("CANCELLED","decode"),std::io::ErrorKind::Unsupported=>err("UNSUPPORTED_CAMERA_RAW_VARIANT","decode"),_=>io_error(e,"decode")}}
pub(crate) fn decode(output:&Path,mut c:CameraRaw,a:Admission,cancel:&AtomicBool)->Result<(Probe,Admission)>{
 cancelled(cancel)?;let mut h=c.he.take().ok_or_else(||err("INVALID_INPUT","decode"))?;let d=&c.development;
 if matches!(h.kind,NikonStream::He(v) if !matches!(v,13|14))||h.identity!=Identity::read(&h.file)?{return Err(err("INVALID_INPUT","decode"));}
 let out=OpenOptions::new().write(true).create_new(true).mode(0o600).custom_flags(libc::O_NOFOLLOW).open(output).map_err(|e|io_error(e,"spool"))?;
 let mut guard=OutputGuard(output,false);
 let rc=unsafe{libc::posix_fallocate(out.as_raw_fd(),0,c.probe().output_bytes as libc::off_t)};
 if rc!=0{return Err(io_error(std::io::Error::from_raw_os_error(rc),"spool"));}
 let mut dir=output.as_os_str().to_os_string();dir.push(format!(".he-private-{}-{}",std::process::id(),SEQUENCE.fetch_add(1,Ordering::Relaxed)));
 let dir=PathBuf::from(dir);DirBuilder::new().mode(0o700).create(&dir).map_err(|e|io_error(e,"spool"))?;let _dir=DirGuard(dir.clone());
 let mut raw=match h.kind {NikonStream::He(_)=>RawSpool::create(&dir.join("raw"),d.width as u32,d.height as u32),NikonStream::OrdinaryRows=>RawSpool::create_bayer(&dir.join("raw"),d.width as u32,d.height as u32)}.map_err(spool_error)?;
 {
  let source=h.source.take().ok_or_else(||err("INVALID_INPUT","decode"))?;
  if source.buf().len() as u64!=c.input_bytes{return Err(err("INVALID_INPUT","decode"));}
  let bytes=source.subview(h.offset,h.bytes).map_err(|e|io_error(e,"decode"))?;
  match h.kind {
   NikonStream::He(_)=>{validate_header(bytes,d.width as u32,d.height as u32)?;raw.decode(bytes,cancel).map_err(spool_error)?;}
   NikonStream::OrdinaryRows=>{
    let decoder=crate::camera_raw::bounded("decode",||rawler::get_decoder(&source))?;
    raw.decode_rows(&*decoder,&source,cancel).map_err(spool_error)?;
   }
  }
  if h.identity!=Identity::read(&h.file)?{return Err(err("INVALID_INPUT","decode"));}
 } // whole input mapping and C++ scratch released before any float development.
 let pool=rayon::ThreadPoolBuilder::new().num_threads(2).stack_size(1024*1024).build().map_err(|_|err("MEMORY_BUDGET","decode"))?;
 let mut first=d.crop.y;
 while first<d.crop.y+d.crop.h {
  let last=(first+a.strip_rows as usize).min(d.crop.y+d.crop.h);let y=first.saturating_sub(HALO).max(d.active.y);let end=(last+HALO).min(d.active.y+d.active.h);
  let normalized=raw.normalized(d.active.x as u32,y as u32,d.active.w as u32,(end-y) as u32,h.black,h.white,cancel).map_err(spool_error)?;
  let developed=pool.install(||crate::develop::develop_tile_cancel(d,y,end-y,normalized,cancel))?;
  crate::develop::emit(d,&out,&developed,y,first,last,cancel)?;first=last;
 }
 cancelled(cancel)?;out.sync_all().map_err(|e|io_error(e,"spool"))?;cancelled(cancel)?;guard.1=true;Ok((c.probe(),a))
}
