//! Bounded process-local reuse of an already-required split FilmLog spool.
//! No whole-frame route is split for this cache. Publication renames the owned
//! spool: there is no additional image copy or full-image integrity scan.
//! Proofs are SHA-256 of each prefix core row, generated from existing writes.
//! Every cached suffix read verifies the complete intersecting row segments.
use crate::checkpoint::{Identity, Sha256};
use std::{fs::{self, File, OpenOptions}, io::{self, Read, Seek, SeekFrom, Write}, path::{Path,PathBuf}, sync::{Mutex,MutexGuard,OnceLock}};
#[cfg(unix)] use std::os::unix::{ffi::OsStrExt,fs::OpenOptionsExt};

const OWNER:crate::cache_root_owner::Layout=crate::cache_root_owner::Layout{marker:b"Filmroom owned split FilmLog cache v1\n",files:&["image.bin","proof.bin","pending.proof"]};

pub(crate) const MAX_IMAGE_BYTES:u64=640*1024*1024;
pub(crate) const MAX_PROOF_BYTES:u64=4*1024*1024;
/// Proof vector + one <=40,000-pixel RGB32 row + <=1MiB row/hash/key overhead.
/// Added to the existing 256B/pixel + 96MiB planner, never taken from it.
pub(crate) const WORKING_BYTES:u64=MAX_PROOF_BYTES+1024*1024;
/// Deterministic optional-cache admission reasons, not physical-memory evidence.
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub(crate) enum CapacityRejection {
    InvalidShape, ArithmeticOverflow, ImageLimit, ProofLimit,
    MissingReserve, FreeSpaceUnknown, InsufficientFreeSpace,
}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub(crate) struct Shape {pub width:u32,pub height:u32,pub core_width:u32,pub core_rows:u32}
impl Shape {
    fn image_bytes(self)->Option<u64>{u64::from(self.width).checked_mul(u64::from(self.height))?.checked_mul(12)}
    fn proof_bytes(self)->Option<u64>{self.proof_capacity().ok()}
    fn proof_capacity(self)->Result<u64,CapacityRejection>{
        if self.width==0||self.height==0||self.width>40_000||self.height>40_000||self.core_width==0||self.core_rows==0||self.core_width>self.width||self.core_rows>self.height{return Err(CapacityRejection::InvalidShape);}
        let n=u64::from(self.height).checked_mul(u64::from(self.width.div_ceil(self.core_width))).and_then(|v|v.checked_mul(32)).ok_or(CapacityRejection::ArithmeticOverflow)?;
        // Preserve the existing proof-first rejection order. No cap is relaxed.
        if n>MAX_PROOF_BYTES{return Err(CapacityRejection::ProofLimit);}
        let image=self.image_bytes().ok_or(CapacityRejection::ArithmeticOverflow)?;
        if image>MAX_IMAGE_BYTES{return Err(CapacityRejection::ImageLimit);}
        Ok(n)
    }
    fn index(self,x:u32,y:u32)->u64 {
        let band=y/self.core_rows*self.core_rows;
        u64::from(band)*u64::from(self.width.div_ceil(self.core_width))+u64::from(x/self.core_width)*u64::from(self.core_rows.min(self.height-band))+u64::from(y-band)
    }
}
#[derive(Clone)] struct Entry {key:Identity,shape:Shape,proof_digest:String}
#[derive(Default)] struct State {root:Option<PathBuf>,entry:Option<Entry>}
fn global()->&'static Mutex<State>{static CACHE:OnceLock<Mutex<State>>=OnceLock::new();CACHE.get_or_init(||Mutex::new(State::default()))}
fn invalid()->io::Error{io::Error::new(io::ErrorKind::InvalidData,"Invalid reusable FilmLog")}
fn check(c:&mut impl FnMut()->bool)->io::Result<()>{if c(){Err(io::ErrorKind::Interrupted.into())}else{Ok(())}}
fn regular(path:&Path,bytes:u64)->io::Result<()>{let m=fs::symlink_metadata(path)?;if !m.is_file()||m.len()!=bytes{return Err(invalid());}Ok(())}
fn remove(path:&Path)->io::Result<()>{OWNER.remove(path.parent().ok_or_else(invalid)?,path.file_name().and_then(|n|n.to_str()).ok_or_else(invalid)?)}
fn retire(state:&mut State)->io::Result<()>{state.entry=None;if let Some(root)=&state.root{OWNER.validate(root)?;for name in ["image.bin","proof.bin","pending.proof"]{remove(&root.join(name))?;}}Ok(())}
#[cfg(unix)] fn free(root:&Path)->Option<u64>{let path=std::ffi::CString::new(root.as_os_str().as_bytes()).ok()?;let mut result=std::mem::MaybeUninit::<libc::statvfs>::uninit();if unsafe{libc::statvfs(path.as_ptr(),result.as_mut_ptr())}!=0{return None;}let r=unsafe{result.assume_init()};(r.f_bavail as u64).checked_mul(r.f_frsize as u64)}
#[cfg(not(unix))] fn free(_: &Path)->Option<u64>{None}
fn disk_capacity(available:Option<u64>,reserve:u64,additional:u64)->Result<(),CapacityRejection>{
    if reserve==0{return Err(CapacityRejection::MissingReserve);}
    let need=reserve.checked_add(additional).ok_or(CapacityRejection::ArithmeticOverflow)?;
    let free=available.ok_or(CapacityRejection::FreeSpaceUnknown)?;
    if free<need{return Err(CapacityRejection::InsufficientFreeSpace);}
    Ok(())
}
fn admitted(available:Option<u64>,reserve:u64,additional:u64)->bool{disk_capacity(available,reserve,additional).is_ok()}
#[cfg(unix)] fn owned_open(path:&Path,write:bool,create:bool)->io::Result<File>{OpenOptions::new().read(!write).write(write).create_new(create).custom_flags(libc::O_NOFOLLOW|libc::O_CLOEXEC).open(path)}
#[cfg(not(unix))] fn owned_open(_: &Path,_:bool,_:bool)->io::Result<File>{Err(invalid())}
fn process_lock(root:&Path)->io::Result<File>{OWNER.open(root,false)}
fn cache_root(data:&Path)->Option<PathBuf>{let app=data.parent()?.parent()?;let cache=app.join("cache").canonicalize().ok()?;(cache.parent()==Some(app)).then(||cache.join("film-export-filmlog-reuse-v1"))}
/// Called under queue storageLock before capture and cold-export admission.
/// Never removes an actively borrowed artifact. A busy lease remains counted by
/// actual free space; normal admission remains authoritative.
pub(crate) fn trim_for_reserve(data:&Path,reserve:u64){let Some(root)=cache_root(data)else{return};trim_in(global(),&root,reserve);}
fn trim_in(state:&Mutex<State>,root:&Path,reserve:u64){let Ok(mut s)=state.try_lock()else{return};if !fs::symlink_metadata(&root).is_ok_and(|m|m.is_dir()){return;}let Ok(_lock)=process_lock(&root)else{return};if s.root.as_ref().is_some_and(|old|old!=root){return;}s.root=Some(root.to_owned());if s.entry.is_none()||!admitted(free(&root),reserve,0){let _=retire(&mut s);}}
struct Writer {file:File,hash:Sha256,count:u64,failed:bool}
struct Reader {file:File,proofs:Vec<u8>,row:Vec<u8>}
pub(crate) struct Lease<'a>{state:MutexGuard<'a,State>,_lock:File,key:Identity,shape:Shape,reserve:u64,writer:Option<Writer>,reader:Option<Reader>,pending:Option<String>,complete:bool,read_bytes:u64,write_bytes:u64}
impl Lease<'static>{
    pub(crate) fn open(data:&Path,key:Identity,shape:Shape)->Option<Self>{let reserve=crate::export_reuse::disk_reserve()?;Self::open_in(global(),&cache_root(data)?,key,shape,reserve).ok()}
}
impl<'a> Lease<'a>{
    fn open_in(state:&'a Mutex<State>,root:&Path,key:Identity,shape:Shape,reserve:u64)->io::Result<Self>{
        shape.proof_bytes().ok_or_else(invalid)?;
        let mut state=state.try_lock().map_err(|_|io::Error::from(io::ErrorKind::WouldBlock))?;
        let lock=OWNER.open(root,true)?;
        if state.root.as_deref()!=Some(root){
            // Switching test/app roots must not delete another process's lease.
            if let Some(old)=state.root.as_deref(){match fs::symlink_metadata(old){
                Ok(m) if m.is_dir()=>{let _old_lock=process_lock(old)?;retire(&mut state)?;},
                Err(e) if e.kind()==io::ErrorKind::NotFound=>{state.entry=None;},
                _=>return Err(invalid()),
            }}
            state.root=Some(root.to_owned());state.entry=None;
        }
        if state.entry.as_ref().is_none_or(|e|e.key!=key||e.shape!=shape){retire(&mut state)?;}
        if !admitted(free(root),reserve,0){retire(&mut state)?;return Err(io::ErrorKind::StorageFull.into());}
        Ok(Self{state,_lock:lock,key,shape,reserve,writer:None,reader:None,pending:None,complete:false,read_bytes:0,write_bytes:0})
    }
    fn root(&self)->&Path{self.state.root.as_deref().expect("owned cache root")}
    pub(crate) fn path(&self)->PathBuf{self.root().join("image.bin")}
    pub(crate) fn is_hit(&self)->bool{self.reader.is_some()}
    pub(crate) fn has_pending(&self)->bool{self.pending.is_some()}
    pub(crate) fn load(&mut self,c:&mut impl FnMut()->bool)->bool{
        let Some(entry)=self.state.entry.clone()else{return false};
        let result:io::Result<Reader>=(||{
            check(c)?;OWNER.validate(self.root())?;let n=self.shape.proof_bytes().ok_or_else(invalid)?;regular(&self.path(),self.shape.image_bytes().ok_or_else(invalid)?)?;
            let proof=self.root().join("proof.bin");regular(&proof,n)?;let mut file=owned_open(&proof,false,false)?;
            let mut proofs=Vec::new();proofs.try_reserve_exact(n as usize).map_err(|_|invalid())?;
            if proofs.capacity()as u64>MAX_PROOF_BYTES{return Err(invalid());}proofs.resize(n as usize,0);
            let mut hash=Sha256::new();for chunk in proofs.chunks_mut(128*1024){check(c)?;file.read_exact(chunk)?;hash.update(chunk);self.read_bytes+=chunk.len()as u64;}
            if hash.finish()!=entry.proof_digest{return Err(invalid());}
            let file=owned_open(&self.path(),false,false)?;if file.metadata()?.len()!=self.shape.image_bytes().unwrap(){return Err(invalid());}
            let len=self.shape.core_width as usize*12;let mut row=Vec::new();row.try_reserve_exact(len).map_err(|_|invalid())?;
            if row.capacity()>480_000{return Err(invalid());}row.resize(len,0);check(c)?;
            #[cfg(feature="resource-audit")]{crate::resource_probe::observe_vec("filmlog_proof_bytes",&proofs);crate::resource_probe::observe_vec("filmlog_verify_row_bytes",&row);}
            Ok(Reader{file,proofs,row})
        })();
        match result{Ok(reader)=>{self.reader=Some(reader);true},Err(_)=>{self.invalidate();false}}
    }
    pub(crate) fn invalidate(&mut self){self.reader=None;self.writer=None;self.pending=None;let _=retire(&mut self.state);}
    pub(crate) fn begin_prefix(&mut self,c:&mut impl FnMut()->bool){
        if self.reader.is_some(){return;}
        let result:io::Result<Writer>=(||{check(c)?;retire(&mut self.state)?;let bytes=self.shape.proof_bytes().ok_or_else(invalid)?;if !admitted(free(self.root()),self.reserve,bytes){return Err(io::ErrorKind::StorageFull.into());}let file=owned_open(&self.root().join("pending.proof"),true,true)?;Ok(Writer{file,hash:Sha256::new(),count:0,failed:false})})();
        self.writer=result.ok();
    }
    /// Called only after the exact little-endian finite core bytes were written.
    /// An optional proof write failure never fails the user's cold export.
    pub(crate) fn record(&mut self,x:u32,y:u32,bytes:&[u8]){
        let Some(w)=self.writer.as_mut().filter(|w|!w.failed)else{return};
        if x%self.shape.core_width!=0||x>=self.shape.width||y>=self.shape.height||self.shape.index(x,y)!=w.count||bytes.len()!=(self.shape.core_width.min(self.shape.width-x)as usize*12){w.failed=true;return;}
        let digest=digest(bytes);if w.file.write_all(&digest).is_err(){w.failed=true;return;}w.hash.update(&digest);w.count+=1;self.write_bytes+=32;
    }
    pub(crate) fn finish_prefix(&mut self,c:&mut impl FnMut()->bool){
        if let Some(w)=self.writer.take(){if !w.failed&&Some(w.count*32)==self.shape.proof_bytes()&&check(c).is_ok()&&w.file.sync_all().is_ok()&&check(c).is_ok(){self.pending=Some(w.hash.finish());return;}}
        let _=remove(&self.root().join("pending.proof"));
    }
    pub(crate) fn read_rect(&mut self,x:u32,y:u32,width:u32,height:u32,c:&mut impl FnMut()->bool)->io::Result<Vec<f32>>{
        OWNER.validate(self.root())?;
        if width==0||height==0||x.checked_add(width).is_none_or(|n|n>self.shape.width)||y.checked_add(height).is_none_or(|n|n>self.shape.height){return Err(invalid());}
        let reader=self.reader.as_mut().ok_or_else(invalid)?;
        let n=(width as usize).checked_mul(height as usize).and_then(|n|n.checked_mul(3)).ok_or_else(invalid)?;
        let mut values=Vec::new();values.try_reserve_exact(n).map_err(|_|invalid())?;values.resize(n,0f32);
        for row in y..y+height {for left in (x/self.shape.core_width*self.shape.core_width..x+width).step_by(self.shape.core_width as usize){
            check(c)?;let right=(left+self.shape.core_width).min(self.shape.width);let size=(right-left)as usize*12;let bytes=&mut reader.row[..size];
            reader.file.seek(SeekFrom::Start((u64::from(row)*u64::from(self.shape.width)+u64::from(left))*12))?;reader.file.read_exact(bytes)?;self.read_bytes+=size as u64;
            let index=self.shape.index(left,row)as usize*32;
            if digest(bytes).as_slice()!=&reader.proofs[index..index+32]{return Err(invalid());}
            let begin=x.max(left);let end=(x+width).min(right);let source=&bytes[(begin-left)as usize*12..(end-left)as usize*12];let offset=((row-y)as usize*width as usize+(begin-x)as usize)*3;
            for (destination,chunk) in values[offset..offset+source.len()/4].iter_mut().zip(source.chunks_exact(4)){let v=f32::from_le_bytes(chunk.try_into().unwrap());if !v.is_finite(){return Err(invalid());}*destination=v;}
        }}check(c)?;
        #[cfg(feature="resource-audit")] crate::resource_probe::observe_vec("export_cached_filmlog_f32",&values);
        Ok(values)
    }
    /// After successful output/checkpoint completion and a live healthy backend.
    /// The caller still owns/removes `spool` on every unsuccessful promotion.
    pub(crate) fn complete(&mut self,spool:Option<&Path>,c:&mut impl FnMut()->bool)->bool{
        let result:io::Result<bool>=(||{
            check(c)?;OWNER.validate(self.root())?;
            if self.reader.is_some(){self.complete=true;return Ok(false);}
            let Some(digest)=self.pending.take()else{self.complete=true;return Ok(false)};
            let source=spool.ok_or_else(invalid)?;regular(source,self.shape.image_bytes().ok_or_else(invalid)?)?;
            regular(&self.root().join("pending.proof"),self.shape.proof_bytes().ok_or_else(invalid)?)?;
            // These bytes already exist within the active cold export reservation.
            // Retention is optional and must leave the entire remaining ledger.
            if !admitted(free(self.root()),self.reserve,0){return Err(io::ErrorKind::StorageFull.into());}
            fs::rename(source,self.path())?;fs::rename(self.root().join("pending.proof"),self.root().join("proof.bin"))?;
            File::open(self.root())?.sync_all()?;check(c)?;
            self.state.entry=Some(Entry{key:self.key.clone(),shape:self.shape,proof_digest:digest});self.complete=true;Ok(true)
        })();
        match result{Ok(value)=>value,Err(_)=>{self.invalidate();false}}
    }
    pub(crate) fn disk_io(&self)->(u64,u64){(self.read_bytes,self.write_bytes)}
}
impl Drop for Lease<'_>{fn drop(&mut self){self.reader=None;self.writer=None;if !self.complete{let _=retire(&mut self.state);}let _=remove(&self.root().join("pending.proof"));}}
fn digest(bytes:&[u8])->[u8;32]{let mut hash=Sha256::new();hash.update(bytes);let hex=hash.finish();let mut result=[0;32];for (i,pair)in hex.as_bytes().chunks_exact(2).enumerate(){fn digit(v:u8)->u8{match v{b'0'..=b'9'=>v-b'0',b'a'..=b'f'=>v-b'a'+10,_=>unreachable!("SHA-256 hex")}}result[i]=digit(pair[0])*16+digit(pair[1]);}result}

#[cfg(test)] mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64,Ordering};
    struct Temp(PathBuf);
    impl Temp{
        fn new()->Self{static NEXT:AtomicU64=AtomicU64::new(0);let root=std::env::temp_dir().join(format!("filmlog-cache-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));fs::create_dir(&root).unwrap();fs::create_dir(root.join("data")).unwrap();fs::write(root.join("data/profile"),b"verified profile").unwrap();fs::write(root.join("input.rgb"),bytes()).unwrap();Self(root)}
        fn root(&self)->PathBuf{self.0.join("cache")}
        fn key(&self,config:&str)->Identity{Identity::new(&self.0.join("data"),&self.0.join("input.rgb"),13,11,config.into(),&mut||false).unwrap()}
        fn spool(&self)->PathBuf{self.0.join("log.rgb")}
    }
    impl Drop for Temp{fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
    fn shape()->Shape{Shape{width:13,height:11,core_width:5,core_rows:4}}
    fn bytes()->Vec<u8>{(0..13*11*3).flat_map(|i|{let v=if i==0{-0.0}else{(i as f32-200.)/17.};v.to_le_bytes()}).collect()}
    fn stage(lease:&mut Lease<'_>,t:&Temp){
        let bytes=bytes();fs::write(t.spool(),&bytes).unwrap();lease.begin_prefix(&mut||false);
        for y in(0..11).step_by(4){for x in(0..13).step_by(5){for row in y..(y+4).min(11){lease.record(x,row,&bytes[(row*13+x)as usize*12..(row*13+(x+5).min(13))as usize*12]);}}}
        lease.finish_prefix(&mut||false);assert!(lease.has_pending());
    }
    fn publish(state:&Mutex<State>,t:&Temp){let mut lease=Lease::open_in(state,&t.root(),t.key("prefix"),shape(),1).unwrap();stage(&mut lease,t);assert!(lease.complete(Some(&t.spool()),&mut||false));}
    #[test] fn shape_bounds_cover_50mp_and_reject_unbounded_proofs(){
        let large=Shape{width:6144,height:8192,core_width:1024,core_rows:512};assert_eq!(large.image_bytes(),Some(603_979_776));assert_eq!(large.proof_bytes(),Some(1_572_864));
        assert!(Shape{width:10_000,height:6000,core_width:10_000,core_rows:6000}.proof_bytes().is_none());
        assert!(Shape{width:6144,height:8192,core_width:1,core_rows:512}.proof_bytes().is_none());
        for bad in[Shape{core_width:0,..shape()},Shape{core_rows:0,..shape()},Shape{width:0,..shape()},Shape{height:40_001,..shape()}]{assert!(bad.proof_bytes().is_none());}
    }
    #[test] fn proof_indices_cover_short_tails_exactly_once(){let s=shape();let mut indices=Vec::new();for y in(0..s.height).step_by(s.core_rows as usize){for x in(0..s.width).step_by(s.core_width as usize){for row in y..(y+s.core_rows).min(s.height){indices.push(s.index(x,row));}}}assert_eq!(indices,(0..s.proof_bytes().unwrap()/32).collect::<Vec<_>>());}
    #[test] fn fresh_job_lease_reads_exact_signed_finite_regions(){
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);
        let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();assert!(lease.load(&mut||false));
        for(x,y,w,h)in[(0,0,13,11),(3,2,8,8),(12,10,1,1),(4,3,2,2)]{let actual=lease.read_rect(x,y,w,h,&mut||false).unwrap();let source=bytes();let expected=(y..y+h).flat_map(|row|source[(row*13+x)as usize*12..(row*13+x+w)as usize*12].to_vec()).collect::<Vec<_>>();assert_eq!(actual.iter().flat_map(|v|v.to_le_bytes()).collect::<Vec<_>>(),expected);}
        assert!(!lease.complete(None,&mut||false));assert!(lease.is_hit());
    }
    #[cfg(unix)] #[test] fn publication_is_zero_copy_inode_preserving(){
        use std::os::unix::fs::MetadataExt;
        let t=Temp::new();let state=Mutex::new(State::default());let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();stage(&mut lease,&t);let before=fs::metadata(t.spool()).unwrap().ino();assert!(lease.complete(Some(&t.spool()),&mut||false));assert!(!t.spool().exists());assert_eq!(before,fs::metadata(lease.path()).unwrap().ino());assert_eq!(lease.disk_io(),(0,shape().proof_bytes().unwrap()));
    }
    #[test] fn source_profile_config_and_geometry_changes_miss(){
        for change in 0..4{let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);
            if change==0{let mut b=bytes();b[3]^=1;fs::write(t.0.join("input.rgb"),b).unwrap();}if change==1{fs::write(t.0.join("data/profile"),b"changed").unwrap();}
            let key=t.key(if change==2{"other backend or scientific key"}else{"prefix"});let geometry=if change==3{Shape{core_width:6,..shape()}}else{shape()};let mut lease=Lease::open_in(&state,&t.root(),key,geometry,1).unwrap();assert!(!lease.load(&mut||false));assert!(!lease.path().exists());}
    }
    #[test] fn corrupted_proofs_are_a_miss_before_any_pixel_read(){let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let path=t.root().join("proof.bin");let mut b=fs::read(&path).unwrap();b[0]^=1;fs::write(path,b).unwrap();let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();assert!(!lease.load(&mut||false));assert!(!lease.path().exists());}
    #[test] fn finite_pixel_corruption_is_rejected_at_read_boundary(){let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let path=t.root().join("image.bin");let mut b=fs::read(&path).unwrap();b[13*10*12]^=1;fs::write(path,b).unwrap();let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();assert!(lease.load(&mut||false));assert!(lease.read_rect(0,0,13,1,&mut||false).is_ok());assert!(lease.read_rect(0,10,13,1,&mut||false).is_err());lease.invalidate();assert!(!lease.path().exists());}
    #[test] fn truncated_image_or_proof_is_a_miss(){for name in["image.bin","proof.bin"]{let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let path=t.root().join(name);let file=OpenOptions::new().write(true).open(path).unwrap();file.set_len(1).unwrap();drop(file);let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();assert!(!lease.load(&mut||false));}}
    #[test] fn cancellation_during_load_invalidates_optional_entry(){let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();assert!(!lease.load(&mut||true));assert!(!lease.path().exists());}
    #[test] fn cancellation_during_verified_read_stops_without_returning_pixels(){let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();assert!(lease.load(&mut||false));assert_eq!(lease.read_rect(0,0,13,11,&mut||true).unwrap_err().kind(),io::ErrorKind::Interrupted);}
    #[test] fn cancellation_after_rename_never_publishes(){let t=Temp::new();let state=Mutex::new(State::default());let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();stage(&mut lease,&t);let mut n=0;assert!(!lease.complete(Some(&t.spool()),&mut||{n+=1;n==2}));assert!(lease.state.entry.is_none());assert!(!lease.path().exists());assert!(!t.root().join("proof.bin").exists());}
    #[test] fn incomplete_or_out_of_order_prefix_cannot_publish(){for wrong in[false,true]{let t=Temp::new();let state=Mutex::new(State::default());let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();lease.begin_prefix(&mut||false);lease.record(if wrong{5}else{0},0,&bytes()[..60]);lease.finish_prefix(&mut||false);assert!(!lease.has_pending());assert!(!lease.complete(Some(&t.spool()),&mut||false));assert!(!lease.path().exists());}}
    #[test] fn proof_io_failure_does_not_fail_or_consume_cold_spool(){let t=Temp::new();let state=Mutex::new(State::default());let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();fs::create_dir(t.root().join("pending.proof")).unwrap();fs::write(t.spool(),bytes()).unwrap();lease.begin_prefix(&mut||false);assert!(lease.writer.is_none());lease.finish_prefix(&mut||false);assert!(!lease.complete(Some(&t.spool()),&mut||false));assert_eq!(fs::read(t.spool()).unwrap(),bytes());}
    #[test] fn dropping_failed_or_quarantined_attempt_retires_entry(){let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);{let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();assert!(lease.load(&mut||false));/* no healthy completion */}assert!(state.lock().unwrap().entry.is_none());assert!(!t.root().join("image.bin").exists());}
    #[test] fn process_restart_discards_untrusted_leftovers(){let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);drop(state);let state=Mutex::new(State::default());let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();assert!(!lease.load(&mut||false));assert!(!lease.path().exists());assert_eq!(fs::read(t.root().join("owner-v1")).unwrap(),OWNER.marker);}
    #[test] fn active_lease_cannot_be_stolen_or_retired(){let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();assert!(lease.load(&mut||false));assert!(state.try_lock().is_err());trim_in(&state,&t.root(),u64::MAX);assert!(Lease::open_in(&state,&t.root(),t.key("different"),shape(),1).is_err());assert!(lease.path().exists());assert!(!lease.complete(None,&mut||false));drop(lease);trim_in(&state,&t.root(),u64::MAX);assert!(!t.root().join("image.bin").exists());}
    #[test] fn cold_reserve_pressure_retires_idle_cache(){let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);assert!(Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),u64::MAX).is_err());assert!(!t.root().join("image.bin").exists());}
    #[test] fn optional_retention_pressure_preserves_successful_cold_spool(){let t=Temp::new();let state=Mutex::new(State::default());let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();stage(&mut lease,&t);lease.reserve=u64::MAX;assert!(!lease.complete(Some(&t.spool()),&mut||false));assert_eq!(fs::read(t.spool()).unwrap(),bytes());}
    #[test] fn disk_admission_is_checked_and_never_borrows_reserved_bytes(){assert!(!admitted(Some(100),100,1));assert!(admitted(Some(101),100,1));assert!(!admitted(None,100,0));assert!(!admitted(Some(u64::MAX),u64::MAX,1));assert!(!admitted(Some(100),0,0));}
    #[cfg(unix)] #[test] fn symlink_cache_root_or_image_is_never_followed(){
        let t=Temp::new();let state=Mutex::new(State::default());std::os::unix::fs::symlink(&t.0,t.root()).unwrap();assert!(Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).is_err());fs::remove_file(t.root()).unwrap();publish(&state,&t);fs::remove_file(t.root().join("image.bin")).unwrap();std::os::unix::fs::symlink(t.0.join("input.rgb"),t.root().join("image.bin")).unwrap();assert!(Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).is_err());assert_eq!(fs::read(t.0.join("input.rgb")).unwrap(),bytes());
    }
    #[test] fn unmarked_root_is_never_claimed_by_open_or_trim() {
        for populated in [false,true] {
            let t=Temp::new();let state=Mutex::new(State::default());fs::create_dir(t.root()).unwrap();
            if populated {fs::write(t.root().join("image.bin"),b"unknown user data").unwrap();fs::write(t.root().join("pending.proof"),b"unknown pending name").unwrap();}
            assert!(Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).is_err());trim_in(&state,&t.root(),u64::MAX);
            assert!(!t.root().join("owner-v1").exists());assert!(!t.root().join("owner.lock").exists());
            if populated {assert_eq!(fs::read(t.root().join("image.bin")).unwrap(),b"unknown user data");assert_eq!(fs::read(t.root().join("pending.proof")).unwrap(),b"unknown pending name");}
            else {assert_eq!(fs::read_dir(t.root()).unwrap().count(),0);}
        }
    }
    #[test] fn unknown_entry_in_marked_root_blocks_all_cleanup() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);
        let original=fs::read(t.root().join("image.bin")).unwrap();fs::write(t.root().join("pending.proof"),b"keep pending").unwrap();fs::write(t.root().join("foreign-document"),b"keep foreign").unwrap();
        assert!(Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).is_err());trim_in(&state,&t.root(),u64::MAX);
        assert_eq!(fs::read(t.root().join("image.bin")).unwrap(),original);assert_eq!(fs::read(t.root().join("pending.proof")).unwrap(),b"keep pending");assert_eq!(fs::read(t.root().join("foreign-document")).unwrap(),b"keep foreign");
    }
    #[test] fn missing_corrupt_or_wrong_version_marker_preserves_every_payload() {
        for marker in [None,Some(b"wrong owner version".as_slice()),Some(b"".as_slice())] {
            let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let original=fs::read(t.root().join("image.bin")).unwrap();
            fs::remove_file(t.root().join("owner-v1")).unwrap();fs::remove_file(t.root().join("owner.lock")).unwrap();if let Some(marker)=marker{fs::write(t.root().join("owner-v1"),marker).unwrap();}
            assert!(Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).is_err());trim_in(&state,&t.root(),u64::MAX);
            assert_eq!(fs::read(t.root().join("image.bin")).unwrap(),original);assert!(!t.root().join("owner.lock").exists());
            match marker{Some(marker)=>assert_eq!(fs::read(t.root().join("owner-v1")).unwrap(),marker),None=>assert!(!t.root().join("owner-v1").exists())}
        }
    }
    #[test] fn root_that_loses_ownership_during_lease_is_not_cleaned_on_drop() {
        let t=Temp::new();let state=Mutex::new(State::default());
        {let mut lease=Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).unwrap();stage(&mut lease,&t);fs::write(t.root().join("owner-v1"),b"foreign now").unwrap();}
        assert_eq!(fs::read(t.root().join("owner-v1")).unwrap(),b"foreign now");assert!(t.root().join("pending.proof").is_file());
    }
    #[cfg(unix)] #[test] fn symlink_marker_is_not_followed_replaced_or_cleaned() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let original=fs::read(t.root().join("image.bin")).unwrap();
        let foreign=t.0.join("foreign-marker");fs::write(&foreign,OWNER.marker).unwrap();fs::remove_file(t.root().join("owner-v1")).unwrap();std::os::unix::fs::symlink(&foreign,t.root().join("owner-v1")).unwrap();
        assert!(Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).is_err());trim_in(&state,&t.root(),u64::MAX);
        assert_eq!(fs::read(t.root().join("image.bin")).unwrap(),original);assert_eq!(fs::read(foreign).unwrap(),OWNER.marker);assert!(fs::symlink_metadata(t.root().join("owner-v1")).unwrap().file_type().is_symlink());
    }
    #[test] fn unknown_lock_contents_are_never_truncated() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let original=fs::read(t.root().join("image.bin")).unwrap();fs::write(t.root().join("owner.lock"),b"foreign lock contents").unwrap();
        assert!(Lease::open_in(&state,&t.root(),t.key("prefix"),shape(),1).is_err());trim_in(&state,&t.root(),u64::MAX);
        assert_eq!(fs::read(t.root().join("image.bin")).unwrap(),original);assert_eq!(fs::read(t.root().join("owner.lock")).unwrap(),b"foreign lock contents");
    }

    #[test] fn native_optional_growth_fits_kotlin_intake_margin_and_cold_scratch() {
        // Cross-language source contract, not a claim of atomic statvfs checks.
        let planning=include_str!("../../../../app/src/main/java/dev/filmroom/engine/ExportPlanning.kt").chars().filter(|c|!c.is_whitespace()).collect::<String>();
        let queue=include_str!("../../../../app/src/main/java/dev/filmroom/export/ExportQueue.kt").chars().filter(|c|!c.is_whitespace()).collect::<String>();
        assert!(planning.contains("privateconstvalAPP_RESERVE=64L*1024*1024"));
        assert!(queue.contains("valcaptureReserve=bytes+64L*1024*1024+reservedBytes.get()"));
        let optional_growth=crate::export_reuse::MAX_DISK_BYTES.checked_add(MAX_PROOF_BYTES).unwrap();
        assert_eq!(optional_growth,20*1024*1024);assert!(optional_growth<64*1024*1024);
        let n=6144u64*8192;let scratch=crate::streamed::estimated_scratch_bytes(6144,8192).unwrap();
        assert_eq!(scratch,n*128+64*1024*1024);assert!(n*12+optional_growth<=scratch);
        let plan=crate::mobile::plan_export(6144,8192,&crate::mobile::MobileParams::default()).unwrap();
        assert_eq!(plan.checkpoint_reserve_bytes,n*48+1024*1024);
    }

}

#[cfg(test)]
mod capacity_boundary_tests {
    use super::*;
    #[test]
    fn image_limit_accepts_last_row_and_rejects_next() {
        let width=4096;
        let height=(MAX_IMAGE_BYTES/(width as u64*12)) as u32;
        let s=Shape{width,height,core_width:width,core_rows:1};
        assert!(s.image_bytes().unwrap()<=MAX_IMAGE_BYTES);
        assert_eq!(s.proof_capacity(),Ok(height as u64*32));
        let over=Shape{height:height+1,..s};
        assert!(over.image_bytes().unwrap()>MAX_IMAGE_BYTES);
        assert_eq!(over.proof_capacity(),Err(CapacityRejection::ImageLimit));
        assert_eq!(over.proof_bytes(),None);
    }
    #[test]
    fn proof_limit_is_inclusive_and_independent_of_core_rows() {
        let s=Shape{width:8,height:16384,core_width:1,core_rows:1};
        for rows in [1,3,1024,16384] {
            assert_eq!(Shape{core_rows:rows,..s}.proof_capacity(),Ok(MAX_PROOF_BYTES));
        }
        assert_eq!(Shape{height:16385,..s}.proof_capacity(),Err(CapacityRejection::ProofLimit));
        assert_eq!(Shape{width:9,..s}.proof_capacity(),Err(CapacityRejection::ProofLimit));
        assert_eq!(Shape{core_width:2,..s}.proof_capacity(),Ok(MAX_PROOF_BYTES/2));
    }
    #[test]
    fn invalid_shapes_have_explicit_reason_before_size_caps() {
        let s=Shape{width:13,height:11,core_width:5,core_rows:4};
        for bad in [Shape{width:0,..s},Shape{height:0,..s},Shape{core_width:0,..s},
            Shape{core_rows:0,..s},Shape{width:40001,..s},Shape{height:40001,..s},
            Shape{core_width:14,..s},Shape{core_rows:12,..s},Shape{width:u32::MAX,height:u32::MAX,..s}] {
            assert_eq!(bad.proof_capacity(),Err(CapacityRejection::InvalidShape));
            assert_eq!(bad.proof_bytes(),None);
        }
        assert_eq!(Shape{width:u32::MAX,height:u32::MAX,..s}.image_bytes(),None);
    }
    #[test]
    fn disk_capacity_boundary_and_overflow_reasons_are_stable() {
        assert_eq!(disk_capacity(Some(100),100,0),Ok(()));
        assert_eq!(disk_capacity(Some(101),100,1),Ok(()));
        assert_eq!(disk_capacity(Some(100),100,1),Err(CapacityRejection::InsufficientFreeSpace));
        assert_eq!(disk_capacity(None,100,0),Err(CapacityRejection::FreeSpaceUnknown));
        assert_eq!(disk_capacity(Some(u64::MAX),u64::MAX,0),Ok(()));
        assert_eq!(disk_capacity(Some(u64::MAX),u64::MAX,1),Err(CapacityRejection::ArithmeticOverflow));
        assert_eq!(disk_capacity(Some(100),0,0),Err(CapacityRejection::MissingReserve));
        for available in [None,Some(0),Some(100),Some(u64::MAX)] {
            for reserve in [0,1,100,u64::MAX] {for extra in [0,1,100,u64::MAX] {
                let old=reserve>0&&reserve.checked_add(extra).zip(available).is_some_and(|(need,free)|free>=need);
                assert_eq!(admitted(available,reserve,extra),old);
            }}
        }
    }
    #[test]
    fn proof_indices_cover_every_small_shape_tail_without_aliasing() {
        for width in 1..=9 {for height in 1..=7 {for core_width in 1..=width {for core_rows in 1..=height {
            let s=Shape{width,height,core_width,core_rows};let mut next=0;
            for y in (0..height).step_by(core_rows as usize) {
                for x in (0..width).step_by(core_width as usize) {
                    for row in y..(y+core_rows).min(height) {assert_eq!(s.index(x,row),next);next+=1;}
                }
            }
            assert_eq!(next*32,s.proof_capacity().unwrap());
        }}}}
    }
    #[test]
    fn reader_capacity_reservation_still_covers_full_proof_and_max_row() {
        assert_eq!(MAX_IMAGE_BYTES,640*1024*1024);
        assert_eq!(MAX_PROOF_BYTES,4*1024*1024);
        assert_eq!(WORKING_BYTES,5*1024*1024);
        assert!(MAX_PROOF_BYTES+40000*12<WORKING_BYTES);
    }
}
