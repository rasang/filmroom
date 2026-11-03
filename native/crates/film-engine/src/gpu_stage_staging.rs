//! Owned GPU packets: strict source identity and bounded, opt-in copy fallback.
//! Returned codes are a closed diagnostic vocabulary, never OS prose or paths.
//!
//! Ownership invariant: callers keep the private packet directory and its parent
//! chain fixed until preparation and the helper have stopped. Observed path
//! replacements fail closed. Cleanup removes only confirmed owned inodes; after
//! an unowned rename/replacement it retains uncertain files rather than deleting
//! another owner's data. This helper is not an arbitrary concurrent file mover.
use sha2::{Digest, Sha256};
use std::{fs::{self,Metadata,OpenOptions},io::{Read,Seek,SeekFrom,Write},path::{Path,PathBuf}};
pub type R<T>=Result<T,String>;
const BUFFER_BYTES:usize=64*1024;
#[derive(Clone,Copy,Debug,Default,PartialEq,Eq)]
pub struct SnapshotSummary {pub linked_planes:u8,pub copied_planes:u8,pub copied_bytes:u64}
#[derive(Clone,Copy,Debug)]
pub enum Phase {SourceStat,StageParentStat,StageParentCanonical,SourceOpen,SourceRead,StageMkdir,SourceLink,CopyCreate,CopyWrite,CopySync,CopyVerify,SourceRestat,ManifestCreate,ManifestWrite,ManifestSync,ManifestRename,KernelCreate,KernelWrite,KernelSync,TableCreate,TableWrite,TableSync}
impl Phase {fn tag(self)->&'static str{match self{Self::SourceStat=>"SOURCE_STAT",Self::StageParentStat=>"STAGE_PARENT_STAT",Self::StageParentCanonical=>"STAGE_PARENT_CANONICAL",Self::SourceOpen=>"SOURCE_OPEN",Self::SourceRead=>"SOURCE_READ",Self::StageMkdir=>"STAGE_MKDIR",Self::SourceLink=>"SOURCE_LINK",Self::CopyCreate=>"COPY_CREATE",Self::CopyWrite=>"COPY_WRITE",Self::CopySync=>"COPY_SYNC",Self::CopyVerify=>"COPY_VERIFY",Self::SourceRestat=>"SOURCE_RESTAT",Self::ManifestCreate=>"MANIFEST_CREATE",Self::ManifestWrite=>"MANIFEST_WRITE",Self::ManifestSync=>"MANIFEST_SYNC",Self::ManifestRename=>"MANIFEST_RENAME",Self::KernelCreate=>"KERNEL_CREATE",Self::KernelWrite=>"KERNEL_WRITE",Self::KernelSync=>"KERNEL_SYNC",Self::TableCreate=>"TABLE_CREATE",Self::TableWrite=>"TABLE_WRITE",Self::TableSync=>"TABLE_SYNC"}}}
fn errno_class(e:&std::io::Error)->&'static str {
 match e.raw_os_error(){Some(1)=>"EPERM",Some(2)=>"ENOENT",Some(5)=>"EIO",Some(13)=>"EACCES",Some(17)=>"EEXIST",Some(18)=>"EXDEV",Some(20)=>"ENOTDIR",Some(21)=>"EISDIR",Some(28)=>"ENOSPC",Some(30)=>"EROFS",Some(40)=>"ELOOP",Some(95)=>"EOPNOTSUPP",Some(122)=>"EDQUOT",_=>match e.kind(){std::io::ErrorKind::NotFound=>"ENOENT",std::io::ErrorKind::PermissionDenied=>"EACCES",std::io::ErrorKind::AlreadyExists=>"EEXIST",std::io::ErrorKind::UnexpectedEof=>"EOF",_=>"OTHER"}}
}
pub fn io_error(phase:Phase,e:std::io::Error)->String{format!("GPU_PREPARE_{}_{}",phase.tag(),errno_class(&e))}
pub fn io_at<T>(phase:Phase,value:std::io::Result<T>)->R<T>{value.map_err(|e|io_error(phase,e))}
/// Permit diagnostics into export metrics only if every component is known.
pub fn diagnostic_code(code:&str)->Option<String>{
 const SIMPLE:&[&str]=&["GPU_PREPARE_SOURCE_SHAPE","GPU_PREPARE_SOURCE_IDENTITY","GPU_PREPARE_SOURCE_NONFINITE","GPU_PREPARE_COPY_RESERVE","GPU_PREPARE_STAGE_PATH","GPU_PREPARE_SOURCE_LINK_DESTINATION","GPU_PREPARE_COPY_HASH"];
 if SIMPLE.contains(&code){return Some(code.into());}
 let rest=code.strip_prefix("GPU_PREPARE_")?;
 let phases=[Phase::SourceStat,Phase::StageParentStat,Phase::StageParentCanonical,Phase::SourceOpen,Phase::SourceRead,Phase::StageMkdir,Phase::SourceLink,Phase::CopyCreate,Phase::CopyWrite,Phase::CopySync,Phase::CopyVerify,Phase::SourceRestat,Phase::ManifestCreate,Phase::ManifestWrite,Phase::ManifestSync,Phase::ManifestRename,Phase::KernelCreate,Phase::KernelWrite,Phase::KernelSync,Phase::TableCreate,Phase::TableWrite,Phase::TableSync];
 let classes=["EPERM","ENOENT","EIO","EACCES","EEXIST","EXDEV","ENOTDIR","EISDIR","ENOSPC","EROFS","ELOOP","EOPNOTSUPP","EDQUOT","EOF","OTHER"];
 phases.iter().any(|p|classes.iter().any(|c|rest==format!("{}_{}",p.tag(),c))).then(||code.into())
}
fn cancelled(c:&mut impl FnMut()->bool)->R<()>{if c(){Err("CANCELLED".into())}else{Ok(())}}
fn regular(metadata:&Metadata,bytes:u64)->R<()>{if !metadata.is_file()||metadata.file_type().is_symlink()||metadata.len()!=bytes{Err("GPU_PREPARE_SOURCE_SHAPE".into())}else{Ok(())}}
fn same_file(a:&Metadata,b:&Metadata)->bool {
 #[cfg(unix)] {use std::os::unix::fs::MetadataExt;a.dev()==b.dev()&&a.ino()==b.ino()&&a.len()==b.len()&&a.mtime()==b.mtime()&&a.mtime_nsec()==b.mtime_nsec()}
 #[cfg(not(unix))] {a.len()==b.len()&&a.modified().ok()==b.modified().ok()}
}
pub fn create_stage_dir(dir:&Path)->R<()> {
 let parent=dir.parent().ok_or("GPU_PREPARE_STAGE_PATH")?;
 let meta=io_at(Phase::StageParentStat,fs::symlink_metadata(parent))?;
 if !meta.is_dir()||meta.file_type().is_symlink()||io_at(Phase::StageParentCanonical,parent.canonicalize())?!=parent{return Err("GPU_PREPARE_STAGE_PATH".into());}
 io_at(Phase::StageMkdir,fs::create_dir(dir))
}
// A packet is private to its caller. Reject symlinked/noncanonical directories
// before using a caller-provided path, and retain the directory identity across IO.
fn stage_identity(dir: &Path) -> R<Metadata> {
 let metadata = io_at(Phase::StageParentStat, fs::symlink_metadata(dir))?;
 if !metadata.is_dir() || metadata.file_type().is_symlink()
  || io_at(Phase::StageParentCanonical, dir.canonicalize())? != dir {
  return Err("GPU_PREPARE_STAGE_PATH".into());
 }
 Ok(metadata)
}
fn same_identity(a: &Metadata, b: &Metadata) -> bool {
 #[cfg(unix)] { use std::os::unix::fs::MetadataExt; a.dev() == b.dev() && a.ino() == b.ino() }
 #[cfg(not(unix))] { a.is_file() == b.is_file() && a.created().ok() == b.created().ok() }
}
fn check_stage(dir: &Path, original: &Metadata) -> R<()> {
 if !same_identity(original, &stage_identity(dir)?) { return Err("GPU_PREPARE_STAGE_PATH".into()); }
 Ok(())
}
fn same_source(a: &Metadata, b: &Metadata) -> bool {
 if !same_file(a, b) { return false; }
 // Copy does not change source metadata. Include ctime to detect in-place writes
 // even when their length/mtime are restored; do not use this on hard links.
 #[cfg(unix)] { use std::os::unix::fs::MetadataExt; a.ctime() == b.ctime() && a.ctime_nsec() == b.ctime_nsec() }
 #[cfg(not(unix))] { true }
}
struct Created { paths: Vec<(PathBuf, Metadata)>, keep: bool }
impl Created {
 fn track(&mut self, path: &Path, metadata: Metadata) { self.paths.push((path.to_path_buf(), metadata)); }
}
impl Drop for Created {
 fn drop(&mut self) {
  if !self.keep {
   for (path, original) in &self.paths {
    // Never remove a collision or replacement owned by someone else.
    if fs::symlink_metadata(path).is_ok_and(|current| same_identity(original, &current)) {
     let _ = fs::remove_file(path);
    }
   }
  }
 }
}
fn nofollow(options: &mut OpenOptions) {
 // Resolve O_NOFOLLOW for the target ABI. create_new also rejects final symlinks.
 #[cfg(any(target_os="linux", target_os="android"))] {
  use std::os::unix::fs::OpenOptionsExt;
  options.custom_flags(libc::O_NOFOLLOW);
 }
}
fn copy_source(source: &Path, destination: &Path, original: &Metadata, bytes: u64,
 scalar_bytes: usize, c: &mut impl FnMut() -> bool, owned: &mut Created) -> R<()> {
 cancelled(c)?;
 let dir = destination.parent().ok_or("GPU_PREPARE_STAGE_PATH")?;
 let stage = stage_identity(dir)?;
 let mut options = OpenOptions::new(); options.read(true); nofollow(&mut options);
 #[cfg(test)] if let Some(errno) = TEST_SOURCE_OPEN_ERROR.with(|v| v.get()) {
  return Err(io_error(Phase::SourceOpen, std::io::Error::from_raw_os_error(errno)));
 }
 // A link EACCES is not permission to bypass a source read or destination write
 // denial. These are ordinary OS opens/writes, with no permission changes.
 let mut input = io_at(Phase::SourceOpen, options.open(source))?;
 let opened = io_at(Phase::SourceStat, input.metadata())?; regular(&opened, bytes)?;
 if !same_source(original, &opened) { return Err("GPU_PREPARE_SOURCE_IDENTITY".into()); }
 check_stage(dir, &stage)?;
 let mut options = OpenOptions::new(); options.read(true).write(true).create_new(true); nofollow(&mut options);
 #[cfg(test)] if let Some(errno) = TEST_COPY_CREATE_ERROR.with(|v| v.get()) {
  return Err(io_error(Phase::CopyCreate, std::io::Error::from_raw_os_error(errno)));
 }
 let mut output = io_at(Phase::CopyCreate, options.open(destination))?;
 owned.track(destination, io_at(Phase::CopyCreate, output.metadata())?);
 check_stage(dir, &stage)?;
 let mut buffer = vec![0u8; BUFFER_BYTES]; let mut left = bytes;
 let mut source_hash = Sha256::new();
 while left > 0 {
  cancelled(c)?;
  let count = left.min(buffer.len() as u64) as usize;
  io_at(Phase::SourceRead, input.read_exact(&mut buffer[..count]))?;
  if buffer[..count].chunks_exact(scalar_bytes).any(|b| if scalar_bytes == 8 {
   let x = f64::from_le_bytes(b.try_into().unwrap()); !x.is_finite() || !(x as f32).is_finite()
  } else { !f32::from_le_bytes(b.try_into().unwrap()).is_finite() }) {
   return Err("GPU_PREPARE_SOURCE_NONFINITE".into());
  }
  source_hash.update(&buffer[..count]);
  #[cfg(test)] if let Some((after, errno)) = TEST_COPY_ERROR.with(|v| v.get()) {
   if bytes - left >= after { return Err(io_error(Phase::CopyWrite, std::io::Error::from_raw_os_error(errno))); }
  }
  io_at(Phase::CopyWrite, output.write_all(&buffer[..count]))?;
  left -= count as u64;
 }
 let mut extra = [0u8; 1];
 if io_at(Phase::SourceRead, input.read(&mut extra))? != 0 { return Err("GPU_PREPARE_SOURCE_SHAPE".into()); }
 io_at(Phase::CopySync, output.sync_all())?; cancelled(c)?;
 #[cfg(test)] if TEST_CORRUPT_COPY.with(|v| v.get()) {
  io_at(Phase::CopyVerify, output.seek(SeekFrom::Start(0)))?;
  io_at(Phase::CopyVerify, output.write_all(&[0x5a]))?;
 }
 // Verify the actual destination bytes, not just the buffer passed to write.
 // Reuse the same 64 KiB allocation and open descriptor, so validation cannot
 // accidentally follow a replacement path or allocate a whole image.
 let copied = io_at(Phase::CopyVerify, output.metadata())?; regular(&copied, bytes)?;
 io_at(Phase::CopyVerify, output.seek(SeekFrom::Start(0)))?;
 let mut copied_hash = Sha256::new(); left = bytes;
 while left > 0 {
  cancelled(c)?;
  let count = left.min(buffer.len() as u64) as usize;
  io_at(Phase::CopyVerify, output.read_exact(&mut buffer[..count]))?;
  copied_hash.update(&buffer[..count]); left -= count as u64;
 }
 if io_at(Phase::CopyVerify, output.read(&mut extra))? != 0 { return Err("GPU_PREPARE_SOURCE_SHAPE".into()); }
 if source_hash.finalize() != copied_hash.finalize() { return Err("GPU_PREPARE_COPY_HASH".into()); }
 let final_open = io_at(Phase::SourceRestat, input.metadata())?;
 let final_path = io_at(Phase::SourceRestat, fs::symlink_metadata(source))?;
 regular(&final_open, bytes)?; regular(&final_path, bytes)?;
 if !same_source(original, &final_open) || !same_source(original, &final_path) {
  return Err("GPU_PREPARE_SOURCE_IDENTITY".into());
 }
 let final_copy = io_at(Phase::CopyVerify, output.metadata())?;
 let final_destination = io_at(Phase::CopyVerify, fs::symlink_metadata(destination))?;
 regular(&final_copy, bytes)?; regular(&final_destination, bytes)?;
 if !same_source(&copied, &final_copy) || !same_source(&copied, &final_destination) {
  return Err("GPU_PREPARE_SOURCE_IDENTITY".into());
 }
 check_stage(dir, &stage)?; cancelled(c)?;
 Ok(())
}
fn copy_eligible(error: &std::io::Error) -> bool {
 // Android shared-storage policy can reject links with EACCES while allowing
 // normal reads/writes. Unknown errors never grant a copy retry.
 matches!(error.raw_os_error(), Some(1 | 13 | 18 | 95))
}
fn completed_snapshot(summary: SnapshotSummary) -> SnapshotSummary {
 #[cfg(test)] TEST_SNAPSHOT_SUMMARIES.with(|v| { if let Some(summaries) = v.borrow_mut().as_mut() { summaries.push(summary); } });
 summary
}
/// Caller must reserve `samples*24` extra disk bytes immediately before preparing
/// this packet. This allowance is required even if all three hard links succeed.
/// The owned packet directory already exists; originals stay immutable until the
/// helper is proved dead. This function removes only files that it created.
pub fn snapshot_sources(sources:[&Path;3],dir:&Path,samples:u64,copy_reserve_bytes:u64,mut cancelled:impl FnMut()->bool)->R<SnapshotSummary>{
 snapshot_with(sources,dir,samples,copy_reserve_bytes,&mut cancelled,|s,d|{
  #[cfg(test)] if let Some(errno)=TEST_LINK_ERROR.with(|v|v.get()){return Err(std::io::Error::from_raw_os_error(errno));}
  fs::hard_link(s,d)
 })
}
/// Original interleaved f32 RGB source for stage2. Only this fixed filename is
/// accepted; reserve includes the entire input copy before any fallback is tried.
pub fn snapshot_rgb_source(source:&Path,dir:&Path,pixels:u64,copy_reserve_bytes:u64,c:impl FnMut()->bool)->R<SnapshotSummary>{
 let bytes=pixels.checked_mul(12).ok_or("GPU_PREPARE_SOURCE_SHAPE")?;
 snapshot_single_source(source,dir,bytes,4,"source.rgb.f32le",copy_reserve_bytes,c)
}
/// Fixed source layouts accepted by the finishing-tail packet. The caller cannot
/// choose an arbitrary output filename, scalar width, or channel count.
#[derive(Clone,Copy,Debug)]
pub enum F64SourceKind { LogXyz, Glare }
pub fn snapshot_f64_source(source:&Path,dir:&Path,pixels:u64,kind:F64SourceKind,copy_reserve_bytes:u64,c:impl FnMut()->bool)->R<SnapshotSummary>{
 let (stride,name)=match kind {F64SourceKind::LogXyz=>(24,"source.logxyz.f64le"),F64SourceKind::Glare=>(8,"source.glare.f64le")};
 let bytes=pixels.checked_mul(stride).ok_or("GPU_PREPARE_SOURCE_SHAPE")?;
 snapshot_single_source(source,dir,bytes,8,name,copy_reserve_bytes,c)
}
fn snapshot_single_source(source:&Path,dir:&Path,bytes:u64,scalar_bytes:usize,name:&str,copy_reserve_bytes:u64,mut c:impl FnMut()->bool)->R<SnapshotSummary>{
 if bytes==0||copy_reserve_bytes<bytes{return Err("GPU_PREPARE_COPY_RESERVE".into());}
 let stage=stage_identity(dir)?;
 cancelled(&mut c)?;let original=io_at(Phase::SourceStat,fs::symlink_metadata(source))?;regular(&original,bytes)?;
 let dest=dir.join(name);let mut owned=Created{paths:Vec::new(),keep:false};
 check_stage(dir,&stage)?;
 let linked={
  #[cfg(test)] let injected=TEST_LINK_ERROR.with(|v|v.get());
  #[cfg(not(test))] let injected:Option<i32>=None;
  if let Some(errno)=injected{Err(std::io::Error::from_raw_os_error(errno))}else{fs::hard_link(source,&dest)}
 };
 let result=match linked {
  Ok(())=>{
   owned.track(&dest,io_at(Phase::SourceRestat,fs::symlink_metadata(&dest))?);let current=io_at(Phase::SourceRestat,fs::symlink_metadata(source))?;let target=io_at(Phase::SourceRestat,fs::symlink_metadata(&dest))?;regular(&current,bytes)?;regular(&target,bytes)?;
   if !same_file(&original,&current)||!same_file(&original,&target){return Err("GPU_PREPARE_SOURCE_IDENTITY".into());}
   SnapshotSummary{linked_planes:1,copied_planes:0,copied_bytes:0}
  }
  Err(e) if copy_eligible(&e)=>{
   check_stage(dir,&stage)?;
   if !matches!(fs::symlink_metadata(&dest),Err(e) if e.kind()==std::io::ErrorKind::NotFound){return Err("GPU_PREPARE_SOURCE_LINK_DESTINATION".into());}
   copy_source(source,&dest,&original,bytes,scalar_bytes,&mut c,&mut owned)?;SnapshotSummary{linked_planes:0,copied_planes:1,copied_bytes:bytes}
  }
  Err(e)=>return Err(io_error(Phase::SourceLink,e)),
 };
 cancelled(&mut c)?;check_stage(dir,&stage)?;owned.keep=true;Ok(completed_snapshot(result))
}
fn snapshot_with(sources:[&Path;3],dir:&Path,samples:u64,copy_reserve_bytes:u64,c:&mut impl FnMut()->bool,mut link:impl FnMut(&Path,&Path)->std::io::Result<()>)->R<SnapshotSummary>{
 let bytes=samples.checked_mul(8).ok_or("GPU_PREPARE_SOURCE_SHAPE")?;
 let required=bytes.checked_mul(3).ok_or("GPU_PREPARE_COPY_RESERVE")?;
 if samples==0||copy_reserve_bytes<required{return Err("GPU_PREPARE_COPY_RESERVE".into());}
 let stage=stage_identity(dir)?;
 let metadata= sources.iter().map(|p|{let m=io_at(Phase::SourceStat,fs::symlink_metadata(p))?;regular(&m,bytes)?;Ok(m)}).collect::<R<Vec<_>>>()?;
 let mut owned=Created{paths:Vec::new(),keep:false};let mut result=SnapshotSummary::default();
 for ch in 0..3 {
  cancelled(c)?;check_stage(dir,&stage)?;let dest=dir.join(format!("source-{ch}.f64le"));
  match link(sources[ch],&dest){
   Ok(())=>{
    owned.track(&dest,io_at(Phase::SourceRestat,fs::symlink_metadata(&dest))?);let current=io_at(Phase::SourceRestat,fs::symlink_metadata(sources[ch]))?;let target=io_at(Phase::SourceRestat,fs::symlink_metadata(&dest))?;regular(&current,bytes)?;regular(&target,bytes)?;
    if !same_file(&metadata[ch],&current)||!same_file(&metadata[ch],&target){return Err("GPU_PREPARE_SOURCE_IDENTITY".into());}result.linked_planes+=1;
   }
   Err(e) if copy_eligible(&e)=>{
   check_stage(dir,&stage)?;
    // No retry/copy for unknown errors. A collision is never our owned file.
    if !matches!(fs::symlink_metadata(&dest),Err(e) if e.kind()==std::io::ErrorKind::NotFound){return Err("GPU_PREPARE_SOURCE_LINK_DESTINATION".into());}
    copy_source(sources[ch],&dest,&metadata[ch],bytes,8,c,&mut owned)?;result.copied_planes+=1;result.copied_bytes+=bytes;
   }
   Err(e)=>return Err(io_error(Phase::SourceLink,e)),
  }
 }
 cancelled(c)?;check_stage(dir,&stage)?;owned.keep=true;Ok(completed_snapshot(result))
}

#[cfg(test)] thread_local! {static TEST_LINK_ERROR:std::cell::Cell<Option<i32>>=const{std::cell::Cell::new(None)};static TEST_COPY_ERROR:std::cell::Cell<Option<(u64,i32)>>=const{std::cell::Cell::new(None)};
 static TEST_SOURCE_OPEN_ERROR:std::cell::Cell<Option<i32>>=const{std::cell::Cell::new(None)};
 static TEST_COPY_CREATE_ERROR:std::cell::Cell<Option<i32>>=const{std::cell::Cell::new(None)};
 static TEST_CORRUPT_COPY:std::cell::Cell<bool>=const{std::cell::Cell::new(false)};
 static TEST_SNAPSHOT_SUMMARIES:std::cell::RefCell<Option<Vec<SnapshotSummary>>>=const{std::cell::RefCell::new(None)};}
#[cfg(test)] pub(crate) fn with_test_link_error<T>(errno:i32,work:impl FnOnce()->T)->T {
 struct Reset(Option<i32>);impl Drop for Reset{fn drop(&mut self){TEST_LINK_ERROR.with(|v|v.set(self.0));}}
 let old=TEST_LINK_ERROR.with(|v|v.replace(Some(errno)));let _reset=Reset(old);work()
}

#[cfg(test)] pub(crate) fn with_test_snapshot_summaries<T>(work: impl FnOnce() -> T) -> (T, Vec<SnapshotSummary>) {
 struct Reset(Option<Vec<SnapshotSummary>>);
 impl Drop for Reset { fn drop(&mut self) { TEST_SNAPSHOT_SUMMARIES.with(|v| { v.replace(self.0.take()); }); } }
 let _reset = Reset(TEST_SNAPSHOT_SUMMARIES.with(|v| v.replace(Some(Vec::new()))));
 let result = work();
 let summaries = TEST_SNAPSHOT_SUMMARIES.with(|v| v.borrow_mut().take().unwrap());
 (result, summaries)
}

#[cfg(test)] mod tests {
 use super::*;
 struct Fixture{root:PathBuf,sources:[PathBuf;3],dir:PathBuf}
 impl Fixture{fn new()->Self{let root=std::env::temp_dir().join(format!("gpu-stage-source-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));fs::create_dir(&root).unwrap();let root=root.canonicalize().unwrap();let sources=std::array::from_fn(|ch|{let p=root.join(format!("original-{ch}"));fs::write(&p,(0..9000).flat_map(|i|((i+ch)as f64/9000.).to_le_bytes()).collect::<Vec<_>>()).unwrap();p});let dir=root.join("gpu-stage-1");create_stage_dir(&dir).unwrap();Self{root,sources,dir}}fn refs(&self)->[&Path;3]{std::array::from_fn(|i|self.sources[i].as_path())}fn empty(&self)->bool{fs::read_dir(&self.dir).unwrap().next().is_none()}}
 impl Drop for Fixture{fn drop(&mut self){let _=fs::remove_dir_all(&self.root);}}
 #[test]fn unsupported_links_use_bounded_exact_copy_for_only_four_known_errnos(){for errno in [1,13,18,95]{let f=Fixture::new();let result=with_test_link_error(errno,||snapshot_sources(f.refs(),&f.dir,9000,216000,||false)).unwrap();assert_eq!(result,SnapshotSummary{linked_planes:0,copied_planes:3,copied_bytes:216000});for ch in 0..3{assert_eq!(fs::read(&f.sources[ch]).unwrap(),fs::read(f.dir.join(format!("source-{ch}.f64le"))).unwrap());}}}
 #[test]fn copy_short_reserve_unknown_error_and_collision_fail_closed(){let f=Fixture::new();assert_eq!(snapshot_with(f.refs(),&f.dir,9000,215999,&mut||false,|_,_|panic!("no disk admission")).unwrap_err(),"GPU_PREPARE_COPY_RESERVE");assert!(f.empty());for errno in [2,5,28,30,122,9999]{let code=snapshot_with(f.refs(),&f.dir,9000,216000,&mut||false,|_,_|Err(std::io::Error::from_raw_os_error(errno))).unwrap_err();assert_eq!(code,io_error(Phase::SourceLink,std::io::Error::from_raw_os_error(errno)));assert!(diagnostic_code(&code).is_some());assert!(f.empty());}let collision=f.dir.join("source-0.f64le");fs::write(&collision,b"untouched").unwrap();assert_eq!(snapshot_with(f.refs(),&f.dir,9000,216000,&mut||false,|_,_|Err(std::io::Error::from_raw_os_error(18))).unwrap_err(),"GPU_PREPARE_SOURCE_LINK_DESTINATION");assert_eq!(fs::read(collision).unwrap(),b"untouched");}
 #[test]fn partial_copy_cancellation_and_nonfinite_input_leave_no_candidate(){let f=Fixture::new();let original=fs::read(&f.sources[0]).unwrap();let mut checkpoints=0;let result=snapshot_with(f.refs(),&f.dir,9000,216000,&mut||{checkpoints+=1;checkpoints==4},|_,_|Err(std::io::Error::from_raw_os_error(18)));assert_eq!(result.unwrap_err(),"CANCELLED");assert!(f.empty());assert_eq!(fs::read(&f.sources[0]).unwrap(),original);let mut bytes=original.clone();bytes[..8].copy_from_slice(&f64::NAN.to_le_bytes());fs::write(&f.sources[0],bytes).unwrap();assert_eq!(snapshot_with(f.refs(),&f.dir,9000,216000,&mut||false,|_,_|Err(std::io::Error::from_raw_os_error(1))).unwrap_err(),"GPU_PREPARE_SOURCE_NONFINITE");assert!(f.empty());}
 #[test]fn stage_directory_collision_and_symlink_root_are_preserved(){let f=Fixture::new();assert_eq!(create_stage_dir(&f.dir).unwrap_err(),"GPU_PREPARE_STAGE_MKDIR_EEXIST");assert!(f.dir.exists());#[cfg(unix)]{let alias=f.root.join("alias");std::os::unix::fs::symlink(&f.dir,&alias).unwrap();assert_eq!(create_stage_dir(&alias.join("gpu-stage-2")).unwrap_err(),"GPU_PREPARE_STAGE_PATH");assert!(f.dir.exists());}}
 #[test]fn rgb_copy_preserves_exact_interleaved_f32_and_rejects_nonfinite(){
  for errno in [1,13,18,95] {
  let f=Fixture::new();let source=f.root.join("source.rgb");let bytes=(0..9000*3).flat_map(|i|(i as f32/27000.).to_le_bytes()).collect::<Vec<_>>();fs::write(&source,&bytes).unwrap();
  let result=with_test_link_error(errno,||snapshot_rgb_source(&source,&f.dir,9000,108000,||false)).unwrap();assert_eq!(result.copied_bytes,108000);assert_eq!(fs::read(f.dir.join("source.rgb.f32le")).unwrap(),bytes);
  let other=f.root.join("gpu-stage-2");create_stage_dir(&other).unwrap();let mut invalid=bytes;invalid[4..8].copy_from_slice(&f32::NAN.to_le_bytes());fs::write(&source,&invalid).unwrap();assert_eq!(with_test_link_error(1,||snapshot_rgb_source(&source,&other,9000,108000,||false)).unwrap_err(),"GPU_PREPARE_SOURCE_NONFINITE");assert!(fs::read_dir(other).unwrap().next().is_none());
  }
 }
 #[test]fn disk_full_after_partial_copy_cleans_only_owned_files(){
  let f=Fixture::new();let original=fs::read(&f.sources[0]).unwrap();TEST_COPY_ERROR.with(|v|v.set(Some((65536,28))));
  let result=with_test_link_error(18,||snapshot_sources(f.refs(),&f.dir,9000,216000,||false));TEST_COPY_ERROR.with(|v|v.set(None));
  assert_eq!(result.unwrap_err(),"GPU_PREPARE_COPY_WRITE_ENOSPC");assert!(f.empty());assert_eq!(fs::read(&f.sources[0]).unwrap(),original);
 }
 #[test]fn changed_source_identity_and_symlink_source_fail_closed(){
  let f=Fixture::new();let replacement=f.root.join("replacement");fs::copy(&f.sources[0],&replacement).unwrap();let mut first=true;
  let result=snapshot_with(f.refs(),&f.dir,9000,216000,&mut||false,|src,dst|{if first{first=false;fs::rename(&replacement,src)?;}fs::hard_link(src,dst)});
  assert_eq!(result.unwrap_err(),"GPU_PREPARE_SOURCE_IDENTITY");assert!(f.empty());
  #[cfg(unix)] {let alias=f.root.join("source-alias");std::os::unix::fs::symlink(&f.sources[0],&alias).unwrap();assert_eq!(snapshot_sources([alias.as_path(),f.sources[1].as_path(),f.sources[2].as_path()],&f.dir,9000,216000,||false).unwrap_err(),"GPU_PREPARE_SOURCE_SHAPE");assert!(f.empty());}
 }

 // Test hooks model the exact syscall boundary, including after a full 64 KiB
 // write, so root-run host tests do not accidentally bypass chmod denials.
 impl Fixture {
  fn for_copy(rgb: bool) -> Self {
   let f = Self::new();
   if rgb { fs::write(&f.sources[0], (0..18000).flat_map(|i| (i as f32 / 18000.).to_le_bytes()).collect::<Vec<_>>()).unwrap(); }
   f
  }
 }
 fn copy_fixture(f: &Fixture, rgb: bool, c: impl FnMut() -> bool) -> R<SnapshotSummary> {
  with_test_link_error(13, || if rgb {
   snapshot_rgb_source(&f.sources[0], &f.dir, 6000, 72000, c)
  } else { snapshot_sources(f.refs(), &f.dir, 9000, 216000, c) })
 }
 #[test] fn eacces_link_does_not_bypass_source_open_create_or_write_denials() {
  for rgb in [false, true] {
   for (phase, errno) in [(0, 13), (1, 13), (1, 30), (2, 13), (2, 28), (2, 30)] {
    let f = Fixture::for_copy(rgb); let original = fs::read(&f.sources[0]).unwrap();
    match phase {
     0 => TEST_SOURCE_OPEN_ERROR.with(|v| v.set(Some(errno))),
     1 => TEST_COPY_CREATE_ERROR.with(|v| v.set(Some(errno))),
     _ => TEST_COPY_ERROR.with(|v| v.set(Some((65536, errno)))),
    }
    let result = copy_fixture(&f, rgb, || false);
    TEST_SOURCE_OPEN_ERROR.with(|v| v.set(None));
    TEST_COPY_CREATE_ERROR.with(|v| v.set(None));
    TEST_COPY_ERROR.with(|v| v.set(None));
    let expected = io_error(match phase { 0 => Phase::SourceOpen, 1 => Phase::CopyCreate, _ => Phase::CopyWrite }, std::io::Error::from_raw_os_error(errno));
    assert_eq!(result.unwrap_err(), expected); assert!(f.empty());
    assert_eq!(fs::read(&f.sources[0]).unwrap(), original);
   }
  }
 }
 #[cfg(unix)]
 #[test] fn actual_os_read_and_destination_permissions_are_not_bypassed() {
  use std::os::unix::fs::PermissionsExt;
  for rgb in [false, true] {
   let f = Fixture::for_copy(rgb);
   fs::set_permissions(&f.sources[0], fs::Permissions::from_mode(0)).unwrap();
   // On a privileged test runner, chmod cannot model EACCES. The syscall-boundary
   // injection test above remains required; unprivileged CI exercises real OS denial.
   if fs::File::open(&f.sources[0]).is_err() {
    assert_eq!(copy_fixture(&f, rgb, || false).unwrap_err(), "GPU_PREPARE_SOURCE_OPEN_EACCES"); assert!(f.empty());
   }
   fs::set_permissions(&f.sources[0], fs::Permissions::from_mode(0o400)).unwrap();
   fs::set_permissions(&f.dir, fs::Permissions::from_mode(0o500)).unwrap();
   let probe = f.dir.join("permission-probe");
   if OpenOptions::new().write(true).create_new(true).open(&probe).is_err() {
    assert_eq!(copy_fixture(&f, rgb, || false).unwrap_err(), "GPU_PREPARE_COPY_CREATE_EACCES"); assert!(f.empty());
   } else { fs::remove_file(probe).unwrap(); }
   fs::set_permissions(&f.dir, fs::Permissions::from_mode(0o700)).unwrap();
   // Read-only sources are valid: copying must not require or grant source write.
   copy_fixture(&f, rgb, || false).unwrap();
   assert_eq!(fs::metadata(&f.sources[0]).unwrap().permissions().mode() & 0o777, 0o400);
  }
 }
 #[test] fn copied_destination_corruption_is_hashed_and_cleans_owned_files() {
  for rgb in [false, true] {
   let f = Fixture::for_copy(rgb); let original = fs::read(&f.sources[0]).unwrap();
   TEST_CORRUPT_COPY.with(|v| v.set(true));
   let result = copy_fixture(&f, rgb, || false);
   TEST_CORRUPT_COPY.with(|v| v.set(false));
   assert_eq!(result.unwrap_err(), "GPU_PREPARE_COPY_HASH"); assert!(f.empty());
   assert_eq!(fs::read(&f.sources[0]).unwrap(), original);
  }
 }
 #[test] fn every_copy_and_verify_cancellation_checkpoint_cleans_all_owned_planes() {
  for rgb in [false, true] {
   let f = Fixture::for_copy(rgb); let mut total = 0;
   copy_fixture(&f, rgb, || { total += 1; false }).unwrap();
   assert!(total > 5);
   for abort_at in 1..=total {
    let f = Fixture::for_copy(rgb); let original = fs::read(&f.sources[0]).unwrap(); let mut checkpoint = 0;
    assert_eq!(copy_fixture(&f, rgb, || { checkpoint += 1; checkpoint == abort_at }).unwrap_err(), "CANCELLED");
    assert!(f.empty()); assert_eq!(fs::read(&f.sources[0]).unwrap(), original);
   }
  }
 }
 #[test] fn copy_source_replaced_between_stat_and_open_is_rejected() {
  let f = Fixture::new(); let replacement = f.root.join("replacement"); fs::copy(&f.sources[0], &replacement).unwrap();
  let result = snapshot_with(f.refs(), &f.dir, 9000, 216000, &mut || false, |source, _| {
   fs::rename(&replacement, source)?; Err(std::io::Error::from_raw_os_error(13))
  });
  assert_eq!(result.unwrap_err(), "GPU_PREPARE_SOURCE_IDENTITY"); assert!(f.empty());
 }
 #[test] fn in_place_source_mutation_during_copy_is_rejected() {
  for rgb in [false, true] {
   let f = Fixture::for_copy(rgb); let mut changed = false;
   let destination = f.dir.join(if rgb { "source.rgb.f32le" } else { "source-0.f64le" });
   let result = copy_fixture(&f, rgb, || {
    if !changed && fs::metadata(&destination).is_ok_and(|m| m.len() == 65536) {
     changed = true; let mut source = OpenOptions::new().write(true).open(&f.sources[0]).unwrap();
     source.write_all(&1f64.to_le_bytes()).unwrap(); source.sync_all().unwrap();
    }
    false
   });
   assert!(changed); assert_eq!(result.unwrap_err(), "GPU_PREPARE_SOURCE_IDENTITY"); assert!(f.empty());
  }
 }
 #[test] fn source_truncation_or_growth_during_copy_never_publishes_a_packet() {
  for grow in [false, true] {
   let f = Fixture::new(); let destination = f.dir.join("source-0.f64le"); let mut changed = false;
   let result = copy_fixture(&f, false, || {
    if !changed && fs::metadata(&destination).is_ok_and(|m| m.len() == 65536) {
     changed = true; let source = OpenOptions::new().write(true).open(&f.sources[0]).unwrap();
     source.set_len(if grow { 72008 } else { 65536 }).unwrap();
    }
    false
   });
   assert!(changed); assert_eq!(result.unwrap_err(), if grow { "GPU_PREPARE_SOURCE_SHAPE" } else { "GPU_PREPARE_SOURCE_READ_EOF" }); assert!(f.empty());
  }
 }
 #[cfg(any(target_os="linux", target_os="android"))]
 #[test] fn eacces_copy_rejects_raced_source_and_destination_symlinks() {
  let f = Fixture::new(); let saved = f.root.join("saved");
  let result = snapshot_with(f.refs(), &f.dir, 9000, 216000, &mut || false, |source, _| {
   fs::rename(source, &saved)?; std::os::unix::fs::symlink(&saved, source)?;
   Err(std::io::Error::from_raw_os_error(13))
  });
  assert_eq!(result.unwrap_err(), "GPU_PREPARE_SOURCE_OPEN_ELOOP"); assert!(f.empty());
  let f = Fixture::new(); let destination = f.dir.join("source-0.f64le");
  let original = fs::read(&f.sources[0]).unwrap();
  let result = snapshot_with(f.refs(), &f.dir, 9000, 216000, &mut || false, |_, destination| {
   std::os::unix::fs::symlink(&f.sources[0], destination)?;
   Err(std::io::Error::from_raw_os_error(13))
  });
  assert_eq!(result.unwrap_err(), "GPU_PREPARE_SOURCE_LINK_DESTINATION");
  assert!(fs::symlink_metadata(destination).unwrap().file_type().is_symlink());
  assert_eq!(fs::read(&f.sources[0]).unwrap(), original);
 }
 #[cfg(unix)]
 #[test] fn eacces_copy_rejects_symlinked_or_replaced_packet_directory() {
  for symlink in [false, true] {
   let f = Fixture::new(); let moved = f.root.join("moved"); let target = f.root.join("other"); fs::create_dir(&target).unwrap();
   let result = snapshot_with(f.refs(), &f.dir, 9000, 216000, &mut || false, |_, _| {
    fs::rename(&f.dir, &moved)?;
    if symlink { std::os::unix::fs::symlink(&target, &f.dir)?; } else { fs::create_dir(&f.dir)?; }
    Err(std::io::Error::from_raw_os_error(13))
   });
   assert_eq!(result.unwrap_err(), "GPU_PREPARE_STAGE_PATH");
   assert!(fs::read_dir(&moved).unwrap().next().is_none()); assert!(fs::read_dir(&target).unwrap().next().is_none());
  }
  // Violating the caller-private directory invariant must still fail closed.
  // Do not delete the replacement directory's file or chase an unknown rename.
  let f=Fixture::new();let destination=f.dir.join("source-0.f64le");let moved=f.root.join("moved-during-copy");let mut changed=false;
  let result=copy_fixture(&f,false,||{
   if !changed&&fs::metadata(&destination).is_ok_and(|m|m.len()==65536){
    changed=true;fs::rename(&f.dir,&moved).unwrap();fs::create_dir(&f.dir).unwrap();fs::write(&destination,b"not ours").unwrap();
   }
   false
  });
  assert!(changed);assert_eq!(result.unwrap_err(),"GPU_PREPARE_SOURCE_SHAPE");assert_eq!(fs::read(&destination).unwrap(),b"not ours");assert_eq!(fs::read(moved.join("source-0.f64le")).unwrap(),fs::read(&f.sources[0]).unwrap());
 }
 #[test] fn late_destination_replacement_is_not_removed_as_our_owned_copy() {
  let f = Fixture::new(); let destination = f.dir.join("source-0.f64le"); let moved = f.root.join("moved-copy"); let mut changed = false;
  let result = copy_fixture(&f, false, || {
   if !changed && fs::metadata(&destination).is_ok_and(|m| m.len() == 72000) {
    changed = true; fs::rename(&destination, &moved).unwrap(); fs::write(&destination, b"not ours").unwrap();
   }
   false
  });
  assert!(changed); assert_eq!(result.unwrap_err(), "GPU_PREPARE_SOURCE_SHAPE");
  assert_eq!(fs::read(&destination).unwrap(), b"not ours");
  // The moved file is now caller-owned; the helper must never delete unknown paths.
  assert_eq!(fs::read(&moved).unwrap(), fs::read(&f.sources[0]).unwrap());
 }
 #[test] fn finishing_tail_f64_sources_have_fixed_shapes_and_verified_copy() {
  for (kind,pixels,name) in [(F64SourceKind::LogXyz,3000,"source.logxyz.f64le"),(F64SourceKind::Glare,9000,"source.glare.f64le")] {
   for errno in [1,13,18,95] {
    let f=Fixture::new();let (result,summaries)=with_test_snapshot_summaries(||with_test_link_error(errno,||snapshot_f64_source(&f.sources[0],&f.dir,pixels,kind,72000,||false)));
    let summary=result.unwrap();assert_eq!(summary,SnapshotSummary{linked_planes:0,copied_planes:1,copied_bytes:72000});assert_eq!(summaries,vec![summary]);
    assert_eq!(fs::read(f.dir.join(name)).unwrap(),fs::read(&f.sources[0]).unwrap());
   }
   let f=Fixture::new();assert_eq!(snapshot_f64_source(&f.sources[0],&f.dir,pixels,kind,71999,||false).unwrap_err(),"GPU_PREPARE_COPY_RESERVE");assert!(f.empty());
   let summary=snapshot_f64_source(&f.sources[0],&f.dir,pixels,kind,72000,||false).unwrap();assert_eq!(summary,SnapshotSummary{linked_planes:1,copied_planes:0,copied_bytes:0});
  }
 }
 #[test]fn diagnostics_never_admit_paths_or_free_text(){assert!(diagnostic_code("GPU_PREPARE_SOURCE_LINK_EXDEV").is_some());assert!(diagnostic_code("GPU_PREPARE_COPY_VERIFY_EACCES").is_some());assert!(diagnostic_code("GPU_PREPARE_COPY_HASH").is_some());for text in ["/private/source", "GPU_PREPARE_SOURCE_LINK_secret", "GPU_STAGE_IO", "GPU_PREPARE_SOURCE_LINK_EXDEV /private"]{assert!(diagnostic_code(text).is_none());}}
}
