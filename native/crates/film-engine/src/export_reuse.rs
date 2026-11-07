//! Cross-job reuse of immutable FULL-SOURCE GPU diffusion fields only.
//! No FilmLog or encoded-result cache. No pixels survive in RAM between jobs.
//! Process-local identity means app/engine upgrades cannot read old artifacts.
use crate::checkpoint::{Identity, Sha256};
use spektrafilm_core::pipeline::{DiffusionScattered, DiffusionScatteredFields};
use std::{cell::Cell, fs::{self, File, OpenOptions}, io::{self, Read, Write}, path::{Path, PathBuf}, sync::{Mutex, MutexGuard, OnceLock}};
#[cfg(unix)] use std::os::unix::ffi::OsStrExt;

const OWNER:crate::cache_root_owner::Layout=crate::cache_root_owner::Layout{marker:b"Filmroom owned GPU global fields cache v2\n",files:&["fields.bin","pending.bin"]};

pub(crate) const MAX_DISK_BYTES:u64=16*1024*1024;
const MAGIC:&[u8;8]=b"FRGF0001";
const BUFFER:usize=128*1024;
thread_local! { static DISK_RESERVE:Cell<Option<u64>>=const { Cell::new(None) }; }
/// Installed INSIDE the export's private-pool closure, not on its caller thread.
/// No queue job can inherit another job's allowance, including during unwind.
pub(crate) fn disk_reserve()->Option<u64>{DISK_RESERVE.get()}
pub(crate) fn with_disk_reserve<T>(reserve:Option<u64>,work:impl FnOnce()->T)->T {
    struct Reset(Option<u64>);
    impl Drop for Reset {fn drop(&mut self){DISK_RESERVE.set(self.0);}}
    let _reset=Reset(DISK_RESERVE.replace(reserve.filter(|v|*v>0)));
    work()
}
#[derive(Clone)]
struct Entry {key:Identity, bytes:u64, digest:String}
#[derive(Default)]
struct State {root:Option<PathBuf>, entry:Option<Entry>}
fn global()->&'static Mutex<State> {static CACHE:OnceLock<Mutex<State>>=OnceLock::new();CACHE.get_or_init(||Mutex::new(State::default()))}
fn invalid()->io::Error {io::Error::new(io::ErrorKind::InvalidData,"Invalid reusable global fields")}
fn check(c:&mut impl FnMut()->bool)->io::Result<()> {if c(){Err(io::ErrorKind::Interrupted.into())}else{Ok(())}}
fn regular(path:&Path,bytes:u64)->io::Result<()> {let m=fs::symlink_metadata(path)?;if !m.is_file()||m.len()!=bytes {return Err(invalid());}Ok(())}
fn remove_owned(path:&Path)->io::Result<()> {
    OWNER.remove(path.parent().ok_or_else(invalid)?,path.file_name().and_then(|n|n.to_str()).ok_or_else(invalid)?)
}
fn retire(state:&mut State)->io::Result<()> {
    state.entry=None;
    if let Some(root)=&state.root {OWNER.validate(root)?;remove_owned(&root.join("fields.bin"))?;remove_owned(&root.join("pending.bin"))?;}
    Ok(())
}
#[cfg(unix)]
fn free_bytes(root:&Path)->Option<u64> {
    let path=std::ffi::CString::new(root.as_os_str().as_bytes()).ok()?;
    let mut result=std::mem::MaybeUninit::<libc::statvfs>::uninit();
    // Correct platform layout comes from the already-vendored libc crate.
    if unsafe{libc::statvfs(path.as_ptr(),result.as_mut_ptr())}!=0{return None;}
    let result=unsafe{result.assume_init()};
    (result.f_bavail as u64).checked_mul(result.f_frsize as u64)
}
#[cfg(not(unix))] fn free_bytes(_: &Path)->Option<u64>{None}
fn admitted(available:Option<u64>,reserve:u64,bytes:u64)->bool {reserve>0 && bytes<=MAX_DISK_BYTES && reserve.checked_add(bytes).zip(available).is_some_and(|(need,free)|free>=need)}
fn process_lock(root:&Path)->io::Result<File>{OWNER.open(root,false)}
/// Queue invokes before its existing disk-space rejection. Never removes a leased
/// entry: a busy cache is left alone, and the normal storage check remains final.
fn cache_root(data:&Path)->Option<PathBuf> {
    let app=data.parent()?.parent()?;
    let cache=app.join("cache").canonicalize().ok()?;
    (cache.parent()==Some(app)).then(||cache.join("film-export-global-reuse-v2"))
}
pub(crate) fn trim_for_reserve(data:&Path,reserve:u64) {
    crate::film_log_reuse::trim_for_reserve(data,reserve);
    let Some(root)=cache_root(data) else{return};
    trim_in(global(),&root,reserve);
}
fn trim_in(cache:&Mutex<State>,root:&Path,reserve:u64){
    let Ok(mut state)=cache.try_lock() else{return};
    let Ok(_lock)=process_lock(root) else{return};
    if state.root.as_ref().is_some_and(|old|old!=root){return;}
    state.root=Some(root.to_owned());
    if state.entry.is_none()||!admitted(free_bytes(root),reserve,MAX_DISK_BYTES) {let _=retire(&mut state);}
}

pub(crate) struct Lease<'a> {
    state:MutexGuard<'a,State>, _process_lock:File, key:Identity, reserve:u64,
    pending:Option<Entry>, hit:bool, writable:bool, bytes_read:u64, bytes_written:u64,
}
impl Lease<'static> {
    pub(crate) fn open(data:&Path,key:Identity)->Option<Self> {
        let reserve=DISK_RESERVE.get()?;
        Self::open_in(global(),&cache_root(data)?,key,reserve).ok()
    }
}
impl<'a> Lease<'a> {
    fn open_in(state:&'a Mutex<State>,root:&Path,key:Identity,reserve:u64)->io::Result<Self> {
        let mut state=state.try_lock().map_err(|_|io::Error::from(io::ErrorKind::WouldBlock))?;
        let lock=OWNER.open(root,true)?;
        if state.root.as_deref()!=Some(root) {
            if let Some(old)=state.root.as_deref(){match fs::symlink_metadata(old){
                Ok(m)if m.is_dir()=>{let _old_lock=process_lock(old)?;retire(&mut state)?;},
                Err(e)if e.kind()==io::ErrorKind::NotFound=>{state.entry=None;},
                _=>return Err(invalid()),
            }}
            state.root=Some(root.to_owned());state.entry=None;
        }
        if state.entry.as_ref().is_none_or(|entry|entry.key!=key) {retire(&mut state)?;}
        // A process restart has no Entry/digest and removes, never imports, leftovers.
        if !admitted(free_bytes(root),reserve,0) {retire(&mut state)?;return Err(io::ErrorKind::StorageFull.into());}
        Ok(Self{state,_process_lock:lock,key,reserve,pending:None,hit:false,writable:true,bytes_read:0,bytes_written:0})
    }
    fn root(&self)->&Path {self.state.root.as_deref().expect("lease owns root")}
    pub(crate) fn load(&mut self,max_working_bytes:u64,c:&mut impl FnMut()->bool)->Option<DiffusionScatteredFields> {
        let entry=self.state.entry.clone()?;
        let path=self.root().join("fields.bin");
        let result:io::Result<DiffusionScatteredFields>=(||{
            check(c)?;OWNER.validate(self.root())?;regular(&path,entry.bytes)?;
            if entry.bytes>MAX_DISK_BYTES{return Err(invalid());}
            let (fields,digest)=read_fields(&path,entry.bytes,max_working_bytes,c)?;
            self.bytes_read+=entry.bytes;
            if digest!=entry.digest{return Err(invalid());}
            if fields.retained_working_bytes().is_none_or(|bytes|bytes>max_working_bytes){return Err(invalid());}
            check(c)?;Ok(fields)
        })();
        match result {Ok(fields)=>{self.hit=true;Some(fields)},Err(_)=>{if retire(&mut self.state).is_err(){self.writable=false;}None}}
    }
    /// Streams fields into one owned pending artifact, replacing the old entry
    /// first so committed + pending bytes never exceed the ONE-entry budget.
    pub(crate) fn stage(&mut self,fields:&DiffusionScatteredFields,c:&mut impl FnMut()->bool)->u64 {
        if self.hit || !self.writable{return 0;}
        let result:io::Result<u64>=(||{
            let bytes=encoded_size(fields)?;
            check(c)?;
            if !admitted(free_bytes(self.root()),self.reserve,bytes){return Err(io::ErrorKind::StorageFull.into());}
            retire(&mut self.state)?;
            let path=self.root().join("pending.bin");
            let digest=write_fields(&path,fields,c)?;regular(&path,bytes)?;
            self.bytes_written+=bytes;check(c)?;
            self.pending=Some(Entry{key:self.key.clone(),bytes,digest});Ok(bytes)
        })();
        match result {Ok(bytes)=>bytes,Err(_)=>{self.pending=None;self.writable=false;let _=remove_owned(&self.root().join("pending.bin"));0}}
    }
    pub(crate) fn disk_io(&self)->(u64,u64) {(self.bytes_read,self.bytes_written)}
    /// Only after full GPU export and its checkpoint/output complete, with GPU
    /// health and cancellation checked by the caller. Rename is the publication.
    pub(crate) fn commit(&mut self,c:&mut impl FnMut()->bool)->bool {
        let Some(entry)=self.pending.take() else{return false};
        let result:io::Result<()>=(||{
            check(c)?;OWNER.validate(self.root())?;
            if !admitted(free_bytes(self.root()),self.reserve,0){return Err(io::ErrorKind::StorageFull.into());}
            fs::rename(self.root().join("pending.bin"),self.root().join("fields.bin"))?;
            File::open(self.root())?.sync_all()?;check(c)?;
            self.state.entry=Some(entry);Ok(())
        })();
        if result.is_err(){let _=retire(&mut self.state);false}else{true}
    }
}
impl Drop for Lease<'_> {
    fn drop(&mut self){let _=remove_owned(&self.root().join("pending.bin"));}
}
fn encoded_size(fields:&DiffusionScatteredFields)->io::Result<u64> {
    if fields.highlight_max_raw.is_some_and(|v|!v.is_finite()){return Err(invalid());}
    let mut bytes=20u64; // magic, highlight-present/value, two field-present flags
    for field in [&fields.camera,&fields.enlarger].into_iter().flatten() {
        let n=u64::from(field.small_w).checked_mul(u64::from(field.small_h)).and_then(|v|v.checked_mul(3)).ok_or_else(invalid)?;
        if field.crop.is_some()||field.small_w==0||field.small_h==0||field.d==0||!field.p_s.is_finite()||n!=field.field.len()as u64||field.field.iter().any(|v|!v.is_finite()){return Err(invalid());}
        bytes=bytes.checked_add(24).and_then(|v|n.checked_mul(4).and_then(|n|v.checked_add(n))).ok_or_else(invalid)?;
    }
    if bytes>MAX_DISK_BYTES{return Err(invalid());}Ok(bytes)
}
struct DigestIo<T> {inner:T,hash:Sha256}
impl<T:Read> Read for DigestIo<T> {
    fn read(&mut self,bytes:&mut [u8])->io::Result<usize> {let n=self.inner.read(bytes)?;self.hash.update(&bytes[..n]);Ok(n)}
}
impl<T:Write> Write for DigestIo<T> {
    fn write(&mut self,bytes:&[u8])->io::Result<usize> {let n=self.inner.write(bytes)?;self.hash.update(&bytes[..n]);Ok(n)}
    fn flush(&mut self)->io::Result<()> {self.inner.flush()}
}
fn write_fields(path:&Path,fields:&DiffusionScatteredFields,c:&mut impl FnMut()->bool)->io::Result<String> {
    encoded_size(fields)?;
    let mut file=DigestIo{inner:OpenOptions::new().write(true).create_new(true).open(path)?,hash:Sha256::new()};
    file.write_all(MAGIC)?;
    file.write_all(&u32::from(fields.highlight_max_raw.is_some()).to_le_bytes())?;
    file.write_all(&fields.highlight_max_raw.unwrap_or(0.).to_le_bytes())?;
    // Flags are u16 so fixed header is 20 bytes, all values explicit little-endian.
    for field in [&fields.camera,&fields.enlarger] {
        file.write_all(&u16::from(field.is_some()).to_le_bytes())?;
        if let Some(field)=field {
            for v in [field.small_w,field.small_h,field.d,field.p_s.to_bits()] {file.write_all(&v.to_le_bytes())?;}
            file.write_all(&(field.field.len()as u64).to_le_bytes())?;
            let mut buffer=Vec::with_capacity(BUFFER);
            for chunk in field.field.chunks(BUFFER/4) {
                check(c)?;buffer.clear();for value in chunk {buffer.extend_from_slice(&value.to_le_bytes());}file.write_all(&buffer)?;
            }
        }
    }
    check(c)?;file.inner.sync_all()?;check(c)?;Ok(file.hash.finish())
}
fn read_fields(path:&Path,bytes:u64,max_working_bytes:u64,c:&mut impl FnMut()->bool)->io::Result<(DiffusionScatteredFields,String)> {
    if !(20..=MAX_DISK_BYTES).contains(&bytes){return Err(invalid());}
    let mut file=DigestIo{inner:File::open(path)?,hash:Sha256::new()};let mut magic=[0;8];file.read_exact(&mut magic)?;if &magic!=MAGIC{return Err(invalid());}
    fn word(file:&mut impl Read)->io::Result<u32>{let mut bytes=[0;4];file.read_exact(&mut bytes)?;Ok(u32::from_le_bytes(bytes))}
    let has=word(&mut file)?;let maximum=f32::from_bits(word(&mut file)?);
    if has>1||!maximum.is_finite(){return Err(invalid());}
    let mut remaining=bytes-20;
    let mut working=std::mem::size_of::<DiffusionScatteredFields>()as u64;
    if working>max_working_bytes{return Err(invalid());}
    let mut read=||->io::Result<Option<DiffusionScattered>> {
        check(c)?;let mut flag=[0;2];file.read_exact(&mut flag)?;
        match u16::from_le_bytes(flag){0=>return Ok(None),1=>{},_=>return Err(invalid())}
        remaining=remaining.checked_sub(24).ok_or_else(invalid)?;
        let (w,h,d,p_s)=(word(&mut file)?,word(&mut file)?,word(&mut file)?,f32::from_bits(word(&mut file)?));
        let mut raw=[0;8];file.read_exact(&mut raw)?;let n=u64::from_le_bytes(raw);
        if w==0||h==0||d==0||!p_s.is_finite()||u64::from(w).checked_mul(u64::from(h)).and_then(|v|v.checked_mul(3))!=Some(n){return Err(invalid());}
        remaining=remaining.checked_sub(n.checked_mul(4).ok_or_else(invalid)?).ok_or_else(invalid)?;
        working=working.checked_add(n.checked_mul(12).ok_or_else(invalid)?).ok_or_else(invalid)?;
        if working>max_working_bytes{return Err(invalid());}
        let n=usize::try_from(n).map_err(|_|invalid())?;
        let mut values=Vec::new();values.try_reserve_exact(n).map_err(|_|invalid())?;
        let mut buffer=vec![0;BUFFER];
        while values.len()<n {
            check(c)?;let count=(n-values.len()).min(BUFFER/4);file.read_exact(&mut buffer[..count*4])?;
            for bytes in buffer[..count*4].chunks_exact(4) {let value=f32::from_le_bytes(bytes.try_into().unwrap());if !value.is_finite(){return Err(invalid());}values.push(value);}
        }
        Ok(Some(DiffusionScattered{crop:None,field:values,small_w:w,small_h:h,d,p_s}))
    };
    let camera=read()?;let enlarger=read()?;
    if remaining!=0||file.read(&mut [0])?!=0 {return Err(invalid());}
    check(c)?;Ok((DiffusionScatteredFields{highlight_max_raw:(has==1).then_some(maximum),camera,enlarger},file.hash.finish()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64,Ordering};
    struct Temp(PathBuf);
    impl Temp {
        fn new()->Self {
            static NEXT:AtomicU64=AtomicU64::new(1);
            let root=std::env::temp_dir().join(format!("global-reuse-test-{}-{}",std::process::id(),NEXT.fetch_add(1,Ordering::Relaxed)));
            fs::create_dir(&root).unwrap();fs::create_dir(root.join("data")).unwrap();
            fs::write(root.join("data/profile"),b"exact data").unwrap();
            fs::write(root.join("source.rgb"),[0;24]).unwrap();Self(root)
        }
        fn key(&self,config:&str)->Identity {Identity::new(&self.0.join("data"),&self.0.join("source.rgb"),2,1,config.into(),&mut||false).unwrap()}
        fn cache(&self)->PathBuf {self.0.join("cache")}
    }
    impl Drop for Temp {fn drop(&mut self){let _=fs::remove_dir_all(&self.0);}}
    fn fields()->DiffusionScatteredFields {
        let one=DiffusionScattered{crop:None,field:vec![-2.,0.,f32::from_bits(0x80000000),1.,0.0001,4.],small_w:2,small_h:1,d:4,p_s:0.25};
        DiffusionScatteredFields{highlight_max_raw:Some(12.25),camera:Some(one.clone()),enlarger:Some(one)}
    }
    fn assert_fields(a:&DiffusionScatteredFields,b:&DiffusionScatteredFields) {
        assert_eq!(a.highlight_max_raw.map(f32::to_bits),b.highlight_max_raw.map(f32::to_bits));
        for (a,b) in [&a.camera,&a.enlarger].into_iter().zip([&b.camera,&b.enlarger]) {
            assert_eq!(a.is_some(),b.is_some());if let(Some(a),Some(b))=(a,b) {
                assert_eq!((a.small_w,a.small_h,a.d,a.p_s.to_bits()),(b.small_w,b.small_h,b.d,b.p_s.to_bits()));
                assert_eq!(a.field.iter().map(|v|v.to_bits()).collect::<Vec<_>>(),b.field.iter().map(|v|v.to_bits()).collect::<Vec<_>>());
            }
        }
    }
    fn publish(state:&Mutex<State>,t:&Temp) {
        let mut lease=Lease::open_in(state,&t.cache(),t.key("runtime"),1).unwrap();
        assert_eq!(lease.stage(&fields(),&mut||false),encoded_size(&fields()).unwrap());
        assert!(lease.commit(&mut||false));
    }
    #[test] fn codec_preserves_signed_finite_fields_and_all_optional_combinations() {
        let t=Temp::new();
        for mask in 0..8 {
            let mut original=fields();if mask&1==0{original.camera=None;}if mask&2==0{original.enlarger=None;}if mask&4==0{original.highlight_max_raw=None;}
            let path=t.0.join(format!("roundtrip-{mask}"));let digest=write_fields(&path,&original,&mut||false).unwrap();
            let bytes=encoded_size(&original).unwrap();assert_eq!(fs::metadata(&path).unwrap().len(),bytes);
            let (restored,actual)=read_fields(&path,bytes,u64::MAX,&mut||false).unwrap();assert_eq!(digest,actual);assert_fields(&original,&restored);
        }
    }
    #[test] fn repeated_export_owns_fields_after_source_and_producer_drop() {
        let t=Temp::new();let state=Mutex::new(State::default());let key=t.key("runtime");publish(&state,&t);
        // Actual repeated jobs can have different filenames with identical bytes.
        let other=t.0.join("other.rgb");fs::copy(t.0.join("source.rgb"),&other).unwrap();
        let same=Identity::new(&t.0.join("data"),&other,2,1,"runtime".into(),&mut||false).unwrap();assert_eq!(key,same);
        fs::remove_file(t.0.join("source.rgb")).unwrap();
        let mut lease=Lease::open_in(&state,&t.cache(),same,1).unwrap();let restored=lease.load(u64::MAX,&mut||false).unwrap();
        assert_fields(&fields(),&restored);assert_eq!(lease.disk_io(),(encoded_size(&fields()).unwrap(),0));
        drop(lease);fs::remove_file(t.cache().join("fields.bin")).unwrap();assert_fields(&fields(),&restored);
    }
    #[test] fn content_data_dimensions_and_configuration_each_invalidate_identity() {
        let t=Temp::new();let original=t.key("runtime");
        fs::write(t.0.join("source.rgb"),[1;24]).unwrap();assert_ne!(original,t.key("runtime"));
        fs::write(t.0.join("source.rgb"),[0;24]).unwrap();fs::write(t.0.join("data/profile"),b"different!").unwrap();assert_ne!(original,t.key("runtime"));
        fs::write(t.0.join("data/profile"),b"exact data").unwrap();
        let changed=Identity::new(&t.0.join("data"),&t.0.join("source.rgb"),1,2,"runtime".into(),&mut||false).unwrap();assert_ne!(original,changed);
        assert_ne!(original,t.key("different runtime"));assert_eq!(original,original.with_configuration("runtime".into()).unwrap());
    }
    #[test] fn cache_key_mismatch_retires_before_any_new_payload() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);
        let mut lease=Lease::open_in(&state,&t.cache(),t.key("changed"),1).unwrap();assert!(lease.load(u64::MAX,&mut||false).is_none());assert!(!t.cache().join("fields.bin").exists());
    }
    #[test] fn uncommitted_stage_is_never_reused_and_drop_cleans_it() {
        let t=Temp::new();let state=Mutex::new(State::default());
        {let mut lease=Lease::open_in(&state,&t.cache(),t.key("runtime"),1).unwrap();assert!(lease.stage(&fields(),&mut||false)>0);assert!(lease.state.entry.is_none());assert!(!t.cache().join("fields.bin").exists());}
        assert!(!t.cache().join("pending.bin").exists());assert!(state.lock().unwrap().entry.is_none());
    }
    #[test] fn cancel_during_staging_and_at_commit_never_publishes() {
        let t=Temp::new();let state=Mutex::new(State::default());
        {let mut lease=Lease::open_in(&state,&t.cache(),t.key("runtime"),1).unwrap();assert_eq!(lease.stage(&fields(),&mut||true),0);}
        {let mut lease=Lease::open_in(&state,&t.cache(),t.key("runtime"),1).unwrap();assert!(lease.stage(&fields(),&mut||false)>0);assert!(!lease.commit(&mut||true));}
        assert!(!t.cache().join("pending.bin").exists());assert!(!t.cache().join("fields.bin").exists());
    }
    #[test] fn finite_same_length_corruption_is_a_miss() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let path=t.cache().join("fields.bin");
        let mut bytes=fs::read(&path).unwrap();*bytes.last_mut().unwrap()^=1;fs::write(&path,bytes).unwrap();
        let mut lease=Lease::open_in(&state,&t.cache(),t.key("runtime"),1).unwrap();assert!(lease.load(u64::MAX,&mut||false).is_none());assert!(!path.exists());
        assert!(lease.stage(&fields(),&mut||false)>0);assert!(lease.commit(&mut||false));
    }
    #[test] fn truncated_payload_and_corrupt_length_are_misses() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);fs::write(t.cache().join("fields.bin"),b"short").unwrap();
        let mut lease=Lease::open_in(&state,&t.cache(),t.key("runtime"),1).unwrap();assert!(lease.load(u64::MAX,&mut||false).is_none());
        let path=t.0.join("bad-length");write_fields(&path,&fields(),&mut||false).unwrap();let mut bytes=fs::read(&path).unwrap();bytes[34..42].copy_from_slice(&u64::MAX.to_le_bytes());fs::write(&path,&bytes).unwrap();assert!(read_fields(&path,bytes.len()as u64,u64::MAX,&mut||false).is_err());
    }
    #[test] fn unknown_directory_is_not_deleted_or_overwritten() {
        let t=Temp::new();fs::create_dir(t.cache()).unwrap();fs::create_dir(t.cache().join("pending.bin")).unwrap();fs::write(t.cache().join("pending.bin/foreign"),b"keep").unwrap();
        let state=Mutex::new(State::default());assert!(Lease::open_in(&state,&t.cache(),t.key("runtime"),1).is_err());assert_eq!(fs::read(t.cache().join("pending.bin/foreign")).unwrap(),b"keep");
    }
    #[test] fn active_lease_prevents_retirement_and_new_acquisition() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);
        let lease=Lease::open_in(&state,&t.cache(),t.key("runtime"),1).unwrap();assert!(state.try_lock().is_err());assert!(Lease::open_in(&state,&t.cache(),t.key("changed"),1).is_err());assert!(t.cache().join("fields.bin").exists());drop(lease);
    }
    #[test] fn restart_never_imports_old_disk_artifact() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);drop(state);
        let restarted=Mutex::new(State::default());let mut lease=Lease::open_in(&restarted,&t.cache(),t.key("runtime"),1).unwrap();assert!(lease.load(u64::MAX,&mut||false).is_none());assert!(!t.cache().join("fields.bin").exists());assert_eq!(fs::read(t.cache().join("owner-v1")).unwrap(),OWNER.marker);
    }
    #[test] fn budget_charge_includes_restored_vec_capacity_and_gpu_field_uploads() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);
        let mut lease=Lease::open_in(&state,&t.cache(),t.key("runtime"),1).unwrap();assert!(lease.load(0,&mut||false).is_none());
        assert!(lease.stage(&fields(),&mut||false)>0);assert_eq!(lease.disk_io().1,encoded_size(&fields()).unwrap());
    }
    #[test] fn pressure_arithmetic_never_consumes_output_or_checkpoint_reserve() {
        assert!(!admitted(Some(100),100,1));assert!(admitted(Some(101),100,1));
        assert!(!admitted(None,1,1));assert!(!admitted(Some(u64::MAX),u64::MAX,1));
        assert!(!admitted(Some(u64::MAX),1,MAX_DISK_BYTES+1));assert!(!admitted(Some(100),0,1));
    }
    #[test] fn queue_reservation_scope_resets_on_success_nested_calls_and_unwind() {
        assert_eq!(DISK_RESERVE.get(),None);
        with_disk_reserve(Some(123),||{assert_eq!(DISK_RESERVE.get(),Some(123));with_disk_reserve(None,||assert_eq!(DISK_RESERVE.get(),None));assert_eq!(DISK_RESERVE.get(),Some(123));});
        assert_eq!(DISK_RESERVE.get(),None);let _=std::panic::catch_unwind(||with_disk_reserve(Some(99),||panic!("scope unwind")));assert_eq!(DISK_RESERVE.get(),None);
    }
    #[test] fn nonfinite_cropped_and_oversized_fields_are_never_staged() {
        let mut value=fields();value.highlight_max_raw=Some(f32::NAN);assert!(encoded_size(&value).is_err());
        let mut value=fields();value.camera.as_mut().unwrap().crop=Some([0,0,2,1]);assert!(encoded_size(&value).is_err());
        let mut value=fields();value.camera.as_mut().unwrap().field[0]=f32::INFINITY;assert!(encoded_size(&value).is_err());
        // Shape overflow is rejected before any vector allocation.
        let mut value=fields();value.camera.as_mut().unwrap().small_w=u32::MAX;value.camera.as_mut().unwrap().small_h=u32::MAX;assert!(encoded_size(&value).is_err());
    }
    #[test] fn cancellation_after_atomic_rename_retires_instead_of_publishing() {
        let t=Temp::new();let state=Mutex::new(State::default());
        let mut lease=Lease::open_in(&state,&t.cache(),t.key("runtime"),1).unwrap();assert!(lease.stage(&fields(),&mut||false)>0);
        let mut checks=0;assert!(!lease.commit(&mut||{checks+=1;checks>1}));assert!(lease.state.entry.is_none());assert!(!t.cache().join("fields.bin").exists());
    }
    #[test] fn failed_atomic_publication_cleans_only_its_own_pending_file() {
        let t=Temp::new();let state=Mutex::new(State::default());
        {let mut lease=Lease::open_in(&state,&t.cache(),t.key("runtime"),1).unwrap();assert!(lease.stage(&fields(),&mut||false)>0);
        fs::create_dir(t.cache().join("fields.bin")).unwrap();fs::write(t.cache().join("fields.bin/foreign"),b"keep").unwrap();assert!(!lease.commit(&mut||false));assert!(lease.state.entry.is_none());}
        // A directory that ceased matching the allowlist permits NO cleanup,
        // even of our earlier pending file. Normal owned pending cleanup remains tested above.
        assert!(t.cache().join("pending.bin").exists());assert_eq!(fs::read(t.cache().join("fields.bin/foreign")).unwrap(),b"keep");
    }
    #[cfg(unix)]
    #[test] fn cache_symlink_is_not_followed_or_deleted() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);
        let target=t.0.join("foreign");fs::copy(t.cache().join("fields.bin"),&target).unwrap();fs::remove_file(t.cache().join("fields.bin")).unwrap();
        std::os::unix::fs::symlink(&target,t.cache().join("fields.bin")).unwrap();
        assert!(Lease::open_in(&state,&t.cache(),t.key("runtime"),1).is_err());
        assert!(target.is_file());assert!(fs::symlink_metadata(t.cache().join("fields.bin")).unwrap().file_type().is_symlink());
    }

    #[test] fn unmarked_root_is_never_claimed_by_open_or_trim() {
        for populated in [false,true] {
            let t=Temp::new();let state=Mutex::new(State::default());fs::create_dir(t.cache()).unwrap();
            if populated {fs::write(t.cache().join("fields.bin"),b"unknown user data").unwrap();fs::write(t.cache().join("pending.bin"),b"unknown pending name").unwrap();}
            assert!(Lease::open_in(&state,&t.cache(),t.key("runtime"),1).is_err());trim_in(&state,&t.cache(),u64::MAX);
            assert!(!t.cache().join("owner-v1").exists());assert!(!t.cache().join("owner.lock").exists());
            if populated {assert_eq!(fs::read(t.cache().join("fields.bin")).unwrap(),b"unknown user data");assert_eq!(fs::read(t.cache().join("pending.bin")).unwrap(),b"unknown pending name");}
            else {assert_eq!(fs::read_dir(t.cache()).unwrap().count(),0);}
        }
    }
    #[test] fn unknown_entry_in_marked_root_blocks_all_cleanup() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);
        let original=fs::read(t.cache().join("fields.bin")).unwrap();fs::write(t.cache().join("pending.bin"),b"keep pending").unwrap();fs::write(t.cache().join("foreign-document"),b"keep foreign").unwrap();
        assert!(Lease::open_in(&state,&t.cache(),t.key("runtime"),1).is_err());trim_in(&state,&t.cache(),u64::MAX);
        assert_eq!(fs::read(t.cache().join("fields.bin")).unwrap(),original);assert_eq!(fs::read(t.cache().join("pending.bin")).unwrap(),b"keep pending");assert_eq!(fs::read(t.cache().join("foreign-document")).unwrap(),b"keep foreign");
    }
    #[test] fn missing_corrupt_or_wrong_version_marker_preserves_every_payload() {
        for marker in [None,Some(b"wrong owner version".as_slice()),Some(b"".as_slice())] {
            let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let original=fs::read(t.cache().join("fields.bin")).unwrap();
            fs::remove_file(t.cache().join("owner-v1")).unwrap();fs::remove_file(t.cache().join("owner.lock")).unwrap();if let Some(marker)=marker{fs::write(t.cache().join("owner-v1"),marker).unwrap();}
            assert!(Lease::open_in(&state,&t.cache(),t.key("runtime"),1).is_err());trim_in(&state,&t.cache(),u64::MAX);
            assert_eq!(fs::read(t.cache().join("fields.bin")).unwrap(),original);assert!(!t.cache().join("owner.lock").exists());
            match marker{Some(marker)=>assert_eq!(fs::read(t.cache().join("owner-v1")).unwrap(),marker),None=>assert!(!t.cache().join("owner-v1").exists())}
        }
    }
    #[test] fn root_that_loses_ownership_during_lease_is_not_cleaned_on_drop() {
        let t=Temp::new();let state=Mutex::new(State::default());
        {let mut lease=Lease::open_in(&state,&t.cache(),t.key("runtime"),1).unwrap();assert!(lease.stage(&fields(),&mut||false)>0);fs::write(t.cache().join("owner-v1"),b"foreign now").unwrap();}
        assert_eq!(fs::read(t.cache().join("owner-v1")).unwrap(),b"foreign now");assert!(t.cache().join("pending.bin").is_file());
    }
    #[cfg(unix)] #[test] fn symlink_marker_is_not_followed_replaced_or_cleaned() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let original=fs::read(t.cache().join("fields.bin")).unwrap();
        let foreign=t.0.join("foreign-marker");fs::write(&foreign,OWNER.marker).unwrap();fs::remove_file(t.cache().join("owner-v1")).unwrap();std::os::unix::fs::symlink(&foreign,t.cache().join("owner-v1")).unwrap();
        assert!(Lease::open_in(&state,&t.cache(),t.key("runtime"),1).is_err());trim_in(&state,&t.cache(),u64::MAX);
        assert_eq!(fs::read(t.cache().join("fields.bin")).unwrap(),original);assert_eq!(fs::read(foreign).unwrap(),OWNER.marker);assert!(fs::symlink_metadata(t.cache().join("owner-v1")).unwrap().file_type().is_symlink());
    }
    #[test] fn unknown_lock_contents_are_never_truncated() {
        let t=Temp::new();let state=Mutex::new(State::default());publish(&state,&t);let original=fs::read(t.cache().join("fields.bin")).unwrap();fs::write(t.cache().join("owner.lock"),b"foreign lock contents").unwrap();
        assert!(Lease::open_in(&state,&t.cache(),t.key("runtime"),1).is_err());trim_in(&state,&t.cache(),u64::MAX);
        assert_eq!(fs::read(t.cache().join("fields.bin")).unwrap(),original);assert_eq!(fs::read(t.cache().join("owner.lock")).unwrap(),b"foreign lock contents");
    }

    #[test] fn markerless_v1_is_untouched_while_v2_publishes_and_hits() {
        let t=Temp::new();fs::create_dir(t.0.join("files")).unwrap();let data=t.0.join("files/engine-data");fs::create_dir(&data).unwrap();fs::write(data.join("profile"),b"exact data").unwrap();
        fs::create_dir(t.cache()).unwrap();let legacy=t.cache().join("film-export-global-reuse-v1");fs::create_dir(&legacy).unwrap();
        fs::write(legacy.join("fields.bin"),b"unmarked old fields, do not import or delete").unwrap();fs::write(legacy.join("pending.bin"),b"unmarked old pending").unwrap();
        let current=cache_root(&data).unwrap();assert_eq!(current,t.cache().join("film-export-global-reuse-v2"));assert_ne!(current,legacy);
        let state=Mutex::new(State::default());trim_in(&state,&current,u64::MAX);assert!(!current.exists());
        let key=Identity::new(&data,&t.0.join("source.rgb"),2,1,"runtime".into(),&mut||false).unwrap();
        {let mut lease=Lease::open_in(&state,&current,key.clone(),1).unwrap();assert!(lease.load(u64::MAX,&mut||false).is_none());assert!(lease.stage(&fields(),&mut||false)>0);assert!(lease.commit(&mut||false));}
        {let mut lease=Lease::open_in(&state,&current,key,1).unwrap();let hit=lease.load(u64::MAX,&mut||false).unwrap();assert_fields(&hit,&fields());}
        assert_eq!(fs::read(current.join("owner-v1")).unwrap(),OWNER.marker);
        trim_in(&state,&current,u64::MAX);assert!(!current.join("fields.bin").exists());
        assert_eq!(fs::read(legacy.join("fields.bin")).unwrap(),b"unmarked old fields, do not import or delete");assert_eq!(fs::read(legacy.join("pending.bin")).unwrap(),b"unmarked old pending");
        assert_eq!(fs::read_dir(&legacy).unwrap().count(),2);assert!(!legacy.join("owner-v1").exists());assert!(!legacy.join("owner.lock").exists());
    }

}
