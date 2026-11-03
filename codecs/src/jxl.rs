//! Official libjxl storage decoder. Raw uint16 samples remain in the DNG camera
//! domain; all normalization, white balance and profile development stay in DNG.
use crate::{dng::Dng, error::{err, io_error, Result}};
use std::{fs::{File, OpenOptions}, path::Path, os::{fd::AsRawFd, unix::fs::OpenOptionsExt}, sync::atomic::{AtomicBool, AtomicU64, Ordering}};
const MIB:u64=1024*1024;
pub const MAX_BUDGET:u64=384*MIB;
unsafe extern "C" {
 fn film_jxl_decode(input:i32,offset:u64,length:u64,output:i32,output_base:u64,width:u32,height:u32,budget:usize,cancel:extern "C" fn(*const AtomicBool)->i32,cancel_data:*const AtomicBool,peak:*mut u64,os_error:*mut i32)->i32;
}
extern "C" fn cancelled(value:*const AtomicBool)->i32 {
 // The borrowed AtomicBool lives for the entire synchronous native call.
 unsafe { (&*value).load(Ordering::Relaxed) as i32 }
}
/// Conservative full-plane reservation plus libjxl/input/transform overhead.
/// The hard custom allocator can still reject unusual transforms within this
/// estimate, rather than allocate beyond the admitted total.
pub fn scratch_bytes(d:&Dng)->u64 {
 let w=(d.width as u64).div_ceil(64)*64;
 let h=d.tile_height as u64+8;
 w*h*12+32*MIB
}
fn call(file:&File,offset:u64,length:u64,output:Option<&File>,base:u64,w:usize,h:usize,budget:u64,cancel:&AtomicBool,stage:&'static str)->Result<u64>{
 let mut peak=0;let mut os_error=0;
 let rc=unsafe{film_jxl_decode(file.as_raw_fd(),offset,length,output.map_or(-1,|f|f.as_raw_fd()),base,w as u32,h as u32,budget as usize,cancelled,cancel,&mut peak,&mut os_error)};
 match rc {0=>Ok(peak),2=>Err(err("UNSUPPORTED_DNG_VARIANT",stage)),3=>Err(err("MEMORY_BUDGET",stage)),4=>Err(err("CANCELLED",stage)),5=>Err(io_error(std::io::Error::from_raw_os_error(os_error),stage)),_=>Err(err("INVALID_INPUT",stage))}
}
pub fn validate_headers(d:&Dng,file:&File)->Result<()> {
 let cancel=AtomicBool::new(false);
 for(i,(&offset,&length))in d.offsets.iter().zip(&d.counts).enumerate(){
  let h=d.rows_per_strip.min(d.height-i*d.rows_per_strip);
  call(file,offset as u64,length as u64,None,0,d.width,h,8*MIB,&cancel,"probe")?;
 }
 Ok(())
}
pub fn spool_bytes(d:&Dng)->u64{d.width as u64*d.height as u64*6}
pub fn decode_to_spool(d:&Dng,input:&File,output:&Path,cancel:&AtomicBool)->Result<File>{
 static NEXT:AtomicU64=AtomicU64::new(0);
 let parent=output.parent().ok_or_else(||err("INVALID_PATH","spool"))?;
 let mut result=None;
 for _ in 0..16 {
  let path=parent.join(format!(".jxl-raw-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));
  match OpenOptions::new().read(true).write(true).create_new(true).mode(0o600).custom_flags(libc::O_NOFOLLOW).open(&path){
   Ok(f)=>{if let Err(e)=std::fs::remove_file(&path){drop(f);let _=std::fs::remove_file(&path);return Err(io_error(e,"spool"));}result=Some(f);break;},
   Err(e)if e.kind()==std::io::ErrorKind::AlreadyExists=>continue,
   Err(e)=>return Err(io_error(e,"spool")),
  }
 }
 let raw=result.ok_or_else(||err("OUTPUT_EXISTS","spool"))?;
 // Anonymous reserved backing store is reclaimed on success, error or cancel.
 let rc=unsafe{libc::posix_fallocate(raw.as_raw_fd(),0,spool_bytes(d)as libc::off_t)};
 if rc!=0{return Err(io_error(std::io::Error::from_raw_os_error(rc),"spool"));}
 for(i,(&offset,&length))in d.offsets.iter().zip(&d.counts).enumerate(){
  if cancel.load(Ordering::Relaxed){return Err(err("CANCELLED","decode"));}
  let y=i*d.rows_per_strip;let h=d.rows_per_strip.min(d.height-y);
  call(input,offset as u64,length as u64,Some(&raw),(y*d.width*6)as u64,d.width,h,scratch_bytes(d),cancel,"decode")?;
 }
 Ok(raw)
}
#[cfg(test)]
mod tests {
 use super::*;
 #[test]fn ffi_allocator_cancel_and_io_fail_closed(){
  let path=Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/linear-u16-18x20.jxl");let input=File::open(path).unwrap();let n=input.metadata().unwrap().len();let no=AtomicBool::new(false);
  assert_eq!(call(&input,0,n,None,0,18,20,1,&no,"probe").unwrap_err().code,"MEMORY_BUDGET");
  assert_eq!(call(&input,0,n,None,0,18,20,8*MIB,&AtomicBool::new(true),"probe").unwrap_err().code,"CANCELLED");
  // Read-only descriptor guarantees a real callback pwrite failure, no disk fill.
  assert_eq!(call(&input,0,n,Some(&input),0,18,20,64*MIB,&no,"decode").unwrap_err().code,"IO_ERROR");
  assert!(call(&input,0,n,None,0,18,20,8*MIB,&no,"probe").is_ok());
 }
}
