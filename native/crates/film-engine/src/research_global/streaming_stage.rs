//! Separate immutable packet and closed admission for bounded highlight boost.
//! A complete marker is private candidate validation, never an Android death or
//! native-session seal. The transport owner must still prove safe adoption.
use super::*;
use sha2::{Digest, Sha256};
use std::{fs::{self, File, Metadata, OpenOptions},io::{Read,Write},path::{Path,PathBuf}};
pub const SOURCE_ADMITTED: bool = false;

#[derive(Clone,Debug,Serialize,Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct Manifest {
    schema_version:u32,operation:String,layout:String,token:u64,
    source_sha256:[String;3],highlight:StreamHighlightPlan,
}
#[derive(Clone,Debug,PartialEq,Serialize)]
#[serde(rename_all="camelCase")]
pub struct StagePlan {
    pub schema_version:u32,pub operation:String,pub layout:String,pub token:u64,
    pub manifest_sha256:String,pub width:u32,pub height:u32,pub pass_count:u64,
    pub batch_bytes:u64,pub pipeline_count:u32,pub resident_device_bytes:u64,
    pub upload_staging_bytes:u64,pub device_bytes:u64,pub host_bytes:u64,
    pub driver_allowance_bytes:u64,pub helper_allowance_bytes:u64,pub combined_peak_bytes:u64,
    pub source_snapshot_bytes:u64,pub output_bytes:u64,pub metadata_disk_bytes:u64,pub disk_bytes:u64,
}
#[derive(Serialize,Deserialize)]
#[serde(rename_all="camelCase",deny_unknown_fields)]
struct Complete {
    schema_version:u32,operation:String,layout:String,backend:String,ok:bool,token:u64,
    manifest_sha256:String,width:u32,height:u32,output_sha256:[String;3],
}
fn io<T>(x:std::io::Result<T>)->R<T>{x.map_err(|_|"GPU_GLOBAL_IO".into())}
fn hex(bytes:&[u8])->String{bytes.iter().map(|b|format!("{b:02x}")).collect()}
fn hash_valid(s:&str)->bool{s.len()==64&&s.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b))}
fn identity(a:&Metadata,b:&Metadata)->bool{
    #[cfg(unix)]{use std::os::unix::fs::MetadataExt;a.dev()==b.dev()&&a.ino()==b.ino()}
    #[cfg(not(unix))]{a.is_dir()==b.is_dir()&&a.created().ok()==b.created().ok()}
}
struct OwnedDir{path:PathBuf,original:Metadata,keep:bool}
impl OwnedDir{
    fn new(path:&Path)->R<Self>{Ok(Self{path:path.into(),original:io(fs::symlink_metadata(path))?,keep:false})}
    fn verify(&self)->R<()>{let current=io(fs::symlink_metadata(&self.path))?;if !current.is_dir()||current.file_type().is_symlink()||!identity(&self.original,&current){return Err("INVALID_GLOBAL_STREAM_OWNER".into());}Ok(())}
}
impl Drop for OwnedDir{fn drop(&mut self){if !self.keep&&self.verify().is_ok(){let _=fs::remove_dir_all(&self.path);}}}
fn owner(dir:&Path,token:u64,exists:bool)->R<()>{
    if token==0||token>i64::MAX as u64||dir.file_name().and_then(|x|x.to_str())!=Some(format!("gpu-stage-{token}").as_str()){
        return Err("INVALID_GLOBAL_STREAM_OWNER".into());
    }
    if exists{let meta=io(fs::symlink_metadata(dir))?;if !meta.is_dir()||meta.file_type().is_symlink()||io(dir.canonicalize())?!=dir{return Err("INVALID_GLOBAL_STREAM_OWNER".into());}}
    Ok(())
}
fn regular(path:&Path,bytes:u64)->R<()>{let m=io(fs::symlink_metadata(path))?;if !m.is_file()||m.file_type().is_symlink()||m.len()!=bytes{Err("INVALID_GLOBAL_FILE".into())}else{Ok(())}}
fn stable(a:&Metadata,b:&Metadata)->bool{
    if !identity(a,b)||a.len()!=b.len(){return false;}
    #[cfg(unix)]{use std::os::unix::fs::MetadataExt;a.mtime()==b.mtime()&&a.mtime_nsec()==b.mtime_nsec()&&a.ctime()==b.ctime()&&a.ctime_nsec()==b.ctime_nsec()}
    #[cfg(not(unix))]{a.modified().ok()==b.modified().ok()}
}
fn open_read(path:&Path)->R<(File,Metadata)>{
    let before=io(fs::symlink_metadata(path))?;
    if !before.is_file()||before.file_type().is_symlink(){return Err("INVALID_GLOBAL_FILE".into());}
    let mut options=OpenOptions::new();options.read(true);
    #[cfg(any(target_os="linux",target_os="android"))]{use std::os::unix::fs::OpenOptionsExt;options.custom_flags(libc::O_NOFOLLOW);}
    let file=io(options.open(path))?;
    if !stable(&before,&io(file.metadata())?){return Err("INVALID_GLOBAL_STREAM_SOURCE_IDENTITY".into());}
    Ok((file,before))
}
fn stable_read(path:&Path,file:&File,before:&Metadata)->R<()>{
    let after=io(fs::symlink_metadata(path))?;
    if after.file_type().is_symlink()||!stable(before,&after)||!stable(before,&io(file.metadata())?){return Err("INVALID_GLOBAL_STREAM_SOURCE_IDENTITY".into());}
    Ok(())
}
fn read_json<T:serde::de::DeserializeOwned>(path:&Path)->R<(T,Vec<u8>)>{
    let (mut file,meta)=open_read(path)?;
    if meta.len()>65536{return Err("INVALID_GLOBAL_STREAM_MANIFEST".into());}
    let mut bytes=vec![0u8;meta.len() as usize];io(file.read_exact(&mut bytes))?;
    stable_read(path,&file,&meta)?;
    let value=serde_json::from_slice(&bytes).map_err(|_|"INVALID_GLOBAL_STREAM_MANIFEST")?;Ok((value,bytes))
}
fn plane_hash(path:&Path,pixels:u64,c:&mut impl FnMut()->bool)->R<String>{
    regular(path,pixels*8)?;let (mut input,meta)=open_read(path)?;
    if meta.len()!=pixels*8{return Err("INVALID_GLOBAL_FILE".into());}
    let mut hash=Sha256::new();
    let mut bytes=vec![0u8;65536];let mut remaining=pixels*8;
    while remaining>0{
        super::super::check(c)?;let count=remaining.min(bytes.len() as u64) as usize;
        io(input.read_exact(&mut bytes[..count]))?;
        if bytes[..count].chunks_exact(8).any(|x|!super::super::finite(f64::from_le_bytes(x.try_into().unwrap()))){return Err("NONFINITE_GLOBAL_INPUT".into());}
        hash.update(&bytes[..count]);remaining-=count as u64;
    }
    stable_read(path,&input,&meta)?;Ok(hex(&hash.finalize()))
}
fn make_plan(m:&Manifest,sha:String)->R<StagePlan>{
    if m.schema_version!=1||m.operation!=OPERATION||m.layout!=LAYOUT||m.token==0||m.token>i64::MAX as u64
        ||m.source_sha256.iter().any(|s|!hash_valid(s))||!hash_valid(&sha){return Err("INVALID_GLOBAL_STREAM_MANIFEST".into());}
    let p=&m.highlight;p.validate()?;let b=p.memory()?;
    Ok(StagePlan{schema_version:1,operation:OPERATION.into(),layout:LAYOUT.into(),token:m.token,
        manifest_sha256:sha,width:p.width(),height:p.height(),pass_count:2,batch_bytes:BATCH_BYTES,
        pipeline_count:b.pipeline_count,resident_device_bytes:b.resident_device_bytes,upload_staging_bytes:b.upload_staging_bytes,
        device_bytes:b.device_bytes,host_bytes:b.host_bytes,driver_allowance_bytes:b.driver_allowance_bytes,
        helper_allowance_bytes:b.helper_allowance_bytes,combined_peak_bytes:b.combined_peak_bytes,
        source_snapshot_bytes:b.source_snapshot_bytes,output_bytes:b.output_bytes,metadata_disk_bytes:b.metadata_disk_bytes,disk_bytes:b.disk_bytes})
}
fn load(dir:&Path,token:u64,hashes:bool,c:&mut impl FnMut()->bool)->R<(Manifest,StagePlan)>{
    super::super::check(c)?;owner(dir,token,true)?;
    let (manifest,bytes):(Manifest,_) = read_json(&dir.join("manifest.json"))?;
    if manifest.token!=token{return Err("INVALID_GLOBAL_STREAM_IDENTITY".into());}
    let plan=make_plan(&manifest,hex(&Sha256::digest(&bytes)))?;
    for ch in 0..3{
        let path=dir.join(format!("source-{ch}.f64le"));regular(&path,manifest.highlight.pixels()*8)?;
        if hashes&&plane_hash(&path,manifest.highlight.pixels(),c)?!=manifest.source_sha256[ch]{return Err("INVALID_GLOBAL_STREAM_SOURCE_DIGEST".into());}
    }
    Ok((manifest,plan))
}
pub fn estimated_disk_bytes(p:&StreamHighlightPlan)->R<u64>{Ok(p.memory()?.disk_bytes)}
pub fn prepare_stage(sources:[&Path;3],dir:&Path,token:u64,p:&StreamHighlightPlan,mut c:impl FnMut()->bool)->R<StagePlan>{
    p.validate()?;super::super::check(&mut c)?;owner(dir,token,false)?;
    crate::gpu_stage_staging::create_stage_dir(dir)?;let mut owned=OwnedDir::new(dir)?;
    let bytes=p.memory()?.source_snapshot_bytes;
    crate::gpu_stage_staging::snapshot_sources(sources,dir,p.pixels(),bytes,&mut c)?;
    let mut hashes=std::array::from_fn(|_|String::new());
    for ch in 0..3{hashes[ch]=plane_hash(&dir.join(format!("source-{ch}.f64le")),p.pixels(),&mut c)?;}
    let manifest=Manifest{schema_version:1,operation:OPERATION.into(),layout:LAYOUT.into(),token,source_sha256:hashes,highlight:p.clone()};
    let bytes=serde_json::to_vec(&manifest).map_err(|_|"INVALID_GLOBAL_STREAM_MANIFEST")?;
    if bytes.len()>65536{return Err("INVALID_GLOBAL_STREAM_MANIFEST".into());}
    let plan=make_plan(&manifest,hex(&Sha256::digest(&bytes)))?;
    let mut file=io(OpenOptions::new().write(true).create_new(true).open(dir.join(".manifest.partial")))?;
    io(file.write_all(&bytes))?;io(file.sync_all())?;drop(file);super::super::check(&mut c)?;owned.verify()?;
    io(fs::rename(dir.join(".manifest.partial"),dir.join("manifest.json")))?;owned.keep=true;Ok(plan)
}
pub fn plan_stage(dir:&Path,token:u64)->R<StagePlan>{plan_stage_cancellable(dir,token,||false)}
pub fn plan_stage_cancellable(dir:&Path,token:u64,mut c:impl FnMut()->bool)->R<StagePlan>{load(dir,token,false,&mut c).map(|(_,p)|p)}
fn publish(dir:&Path,m:&Manifest,p:&StagePlan,c:&mut impl FnMut()->bool)->R<()>{
    let candidate=dir.join("candidate");let mut hashes=std::array::from_fn(|_|String::new());
    for ch in 0..3{hashes[ch]=plane_hash(&candidate.join(format!("plane-{ch}.f64le")),m.highlight.pixels(),c)?;}
    let done=Complete{schema_version:1,operation:OPERATION.into(),layout:LAYOUT.into(),backend:BACKEND.into(),ok:true,
        token:p.token,manifest_sha256:p.manifest_sha256.clone(),width:p.width,height:p.height,output_sha256:hashes};
    let bytes=serde_json::to_vec(&done).map_err(|_|"INVALID_GLOBAL_STREAM_OUTPUT")?;
    let mut file=io(OpenOptions::new().write(true).create_new(true).open(candidate.join(".complete.partial")))?;
    io(file.write_all(&bytes))?;io(file.sync_all())?;drop(file);super::super::check(c)?;
    io(fs::rename(candidate.join(".complete.partial"),candidate.join("complete.json")))?;Ok(())
}
pub fn validate_outputs(dir:&Path,token:u64,sha:&str,mut c:impl FnMut()->bool)->R<[PathBuf;3]>{
    let (m,p)=load(dir,token,true,&mut c)?;
    if p.manifest_sha256!=sha{return Err("INVALID_GLOBAL_STREAM_IDENTITY".into());}
    let candidate=dir.join("candidate");let meta=io(fs::symlink_metadata(&candidate))?;
    if !meta.is_dir()||meta.file_type().is_symlink()||io(candidate.canonicalize())?!=candidate{return Err("INVALID_GLOBAL_STREAM_OUTPUT".into());}
    let (done,_):(Complete,_)=read_json(&candidate.join("complete.json"))?;
    if done.schema_version!=1||done.operation!=OPERATION||done.layout!=LAYOUT||done.backend!=BACKEND||!done.ok
        ||done.token!=token||done.manifest_sha256!=sha||done.width!=p.width||done.height!=p.height
        ||done.output_sha256.iter().any(|s|!hash_valid(s)){return Err("INVALID_GLOBAL_STREAM_OUTPUT".into());}
    let paths:[PathBuf;3]=std::array::from_fn(|ch|candidate.join(format!("plane-{ch}.f64le")));
    for ch in 0..3{if plane_hash(&paths[ch],m.highlight.pixels(),&mut c)?!=done.output_sha256[ch]{return Err("INVALID_GLOBAL_STREAM_OUTPUT".into());}}
    super::super::check(&mut c)?;Ok(paths)
}
fn run_inner(dir:&Path,token:u64,budget:u64,sha:&str,mut c:impl FnMut()->bool,probe:bool,capacity:u32)->R<serde_json::Value>{
    let (m,p)=load(dir,token,true,&mut c)?;
    if p.manifest_sha256!=sha{return Err("INVALID_GLOBAL_STREAM_IDENTITY".into());}
    if budget<p.combined_peak_bytes||budget>MEMORY_CAP{return Err("INSUFFICIENT_GPU_GLOBAL_STREAM_MEMORY".into());}
    // Only cfg(test) entry points below can set probe=true. Public production
    // entry point is closed independently of adapter eligibility.
    if !SOURCE_ADMITTED&&!probe{return Err("GPU_GLOBAL_STREAM_PENDING".into());}
    #[cfg(not(feature="gpu-diffusion-research"))]
    {let _=(m,p,capacity);Err("GPU_GLOBAL_UNSUPPORTED".into())}
    #[cfg(feature="gpu-diffusion-research")]
    {
        let candidate=dir.join("candidate");io(fs::create_dir(&candidate))?;let mut owned=OwnedDir::new(&candidate)?;
        let mut report=super::gpu::run_files(&m.highlight,dir,&candidate,&m.source_sha256,&mut c,probe,capacity)?;
        // Revalidate manifest and all immutable snapshots before any complete
        // marker. Both GPU-consumed passes were also hashed independently.
        let (_,after)=load(dir,token,true,&mut c)?;
        if after!=p{return Err("INVALID_GLOBAL_STREAM_IDENTITY".into());}
        owned.verify()?;publish(dir,&m,&p,&mut c)?;super::super::check(&mut c)?;
        for (key,value) in [
            ("ok",serde_json::json!(true)),("schemaVersion",serde_json::json!(1)),("operation",serde_json::json!(OPERATION)),
            ("layout",serde_json::json!(LAYOUT)),("backend",serde_json::json!(BACKEND)),("token",serde_json::json!(token)),
            ("manifestSha256",serde_json::json!(sha)),("width",serde_json::json!(p.width)),("height",serde_json::json!(p.height)),
            ("deviceBytes",serde_json::json!(p.device_bytes)),("combinedPeakBytes",serde_json::json!(p.combined_peak_bytes)),
            ("gpuUsed",serde_json::json!(true)),("fullImage",serde_json::json!(true)),("nativeStageSealed",serde_json::json!(false)),
        ]{report[key]=value;}
        owned.verify()?;owned.keep=true;Ok(report)
    }
}
pub fn run_stage(dir:&Path,token:u64,budget:u64,sha:&str,c:impl FnMut()->bool)->R<serde_json::Value>{run_inner(dir,token,budget,sha,c,false,BATCH_SAMPLES)}
#[cfg(test)]
pub(crate) fn run_probe_stage(dir:&Path,token:u64,budget:u64,sha:&str,c:impl FnMut()->bool,capacity:u32)->R<serde_json::Value>{run_inner(dir,token,budget,sha,c,true,capacity)}

/// Test-only raw plan fixture. The source digests below are deliberately
/// synthetic: this constructs no packet/files and proves no image execution or
/// output adoption. Memory and wire fields come from the real typed constructor
/// and private Manifest/make_plan path, not a separately maintained JSON budget.
#[cfg(test)]
pub(crate) fn plan_fixture_for_test(width:u32,height:u32,token:u64)->R<StagePlan>{
    let source_sha256=std::array::from_fn(|ch|{
        let label=format!("SYNTHETIC_TEST_ONLY_HIGHLIGHT_SOURCE_{ch}");
        hex(&Sha256::digest(label.as_bytes()))
    });
    let manifest=Manifest{
        schema_version:1,operation:OPERATION.into(),layout:LAYOUT.into(),token,
        source_sha256,highlight:StreamHighlightPlan::new(width,height,2.,0.3,1.)?,
    };
    let bytes=serde_json::to_vec(&manifest).map_err(|_|"INVALID_GLOBAL_STREAM_MANIFEST")?;
    make_plan(&manifest,hex(&Sha256::digest(&bytes)))
}
