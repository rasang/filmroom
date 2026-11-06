//! Authentication of an app-private disposable cache namespace, before any
//! cleanup or replacement. A reserved pathname by itself is NOT ownership.
use std::{fs::{self,File,OpenOptions},io::{self,Read,Write},path::Path};
#[cfg(unix)] use std::os::unix::{fs::OpenOptionsExt,io::AsRawFd};
const MARKER:&str="owner-v1";
const LOCK:&str="owner.lock";
#[derive(Clone,Copy)]
pub(crate) struct Layout {pub marker:&'static [u8],pub files:&'static [&'static str]}
fn invalid()->io::Error{io::Error::new(io::ErrorKind::InvalidData,"Unrecognized export cache owner")}
#[cfg(unix)] fn open_file(path:&Path,write:bool,create_new:bool)->io::Result<File>{OpenOptions::new().read(!write).write(write).create_new(create_new).custom_flags(libc::O_NOFOLLOW|libc::O_CLOEXEC).open(path)}
#[cfg(not(unix))] fn open_file(_: &Path,_:bool,_:bool)->io::Result<File>{Err(invalid())}
impl Layout {
    /// Read-only, complete allowlist validation. Failure authorizes no mutation.
    pub(crate) fn validate(self,root:&Path)->io::Result<()>{
        if self.marker.is_empty()||self.marker.len()>128||!fs::symlink_metadata(root)?.is_dir(){return Err(invalid());}
        for entry in fs::read_dir(root)?{
            let entry=entry?;let name=entry.file_name();let name=name.to_str().ok_or_else(invalid)?;
            if (name!=MARKER&&name!=LOCK&&!self.files.contains(&name))||!entry.file_type()?.is_file(){return Err(invalid());}
            if name==LOCK&&entry.metadata()?.len()!=0{return Err(invalid());}
        }
        let path=root.join(MARKER);let metadata=fs::symlink_metadata(&path)?;
        if !metadata.is_file()||metadata.len()!=self.marker.len()as u64{return Err(invalid());}
        let mut marker=open_file(&path,false,false)?;let mut bytes=[0;128];marker.read_exact(&mut bytes[..self.marker.len()])?;
        if &bytes[..self.marker.len()]!=self.marker||marker.read(&mut [0])?!=0{return Err(invalid());}Ok(())
    }
    /// Only a successful exclusive mkdir licenses creating the marker. Existing
    /// empty/unmarked/unknown roots are never claimed, cleaned or overwritten.
    pub(crate) fn open(self,root:&Path,create:bool)->io::Result<File>{
        if self.marker.is_empty()||self.marker.len()>128{return Err(invalid());}
        if create {match fs::create_dir(root){
            Ok(())=>{
                let mut marker=open_file(&root.join(MARKER),true,true)?;
                marker.write_all(self.marker)?;marker.sync_all()?;drop(marker);
                File::open(root)?.sync_all()?;
                File::open(root.parent().ok_or_else(invalid)?)?.sync_all()?;
            },
            Err(e)if e.kind()==io::ErrorKind::AlreadyExists=>{},Err(e)=>return Err(e),
        }}
        self.validate(root)?;
        #[cfg(unix)] {
            let file=OpenOptions::new().read(true).write(true).create(true).truncate(false).custom_flags(libc::O_NOFOLLOW|libc::O_CLOEXEC).open(root.join(LOCK))?;
            if !file.metadata()?.is_file()||file.metadata()?.len()!=0{return Err(invalid());}
            if unsafe{libc::flock(file.as_raw_fd(),libc::LOCK_EX|libc::LOCK_NB)}!=0{return Err(io::Error::last_os_error());}
            self.validate(root)?;Ok(file)
        }
        #[cfg(not(unix))] {Err(invalid())}
    }
    pub(crate) fn remove(self,root:&Path,name:&str)->io::Result<()>{
        self.validate(root)?;
        if !self.files.contains(&name){return Err(invalid());}
        let path=root.join(name);
        match fs::symlink_metadata(&path){Ok(m)if m.is_file()=>fs::remove_file(path),Ok(_)=>Err(invalid()),Err(e)if e.kind()==io::ErrorKind::NotFound=>Ok(()),Err(e)=>Err(e)}
    }
}
