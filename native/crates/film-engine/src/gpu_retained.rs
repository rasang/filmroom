//! Exact native-created root registry. Caller must prove helper death and hold
//! the parent operation lock before cleanup. Unknown/replaced roots are retained.
use crate::mobile::{EngineError, Result};
use std::{
    collections::BTreeMap,
    fs::{self,File,OpenOptions},
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
};
// Android ARM64 does not use the x86 Linux open-flag ABI. Keep this
// target-compiled assertion in release builds, not just host test fixtures.
#[cfg(all(target_os = "android", target_arch = "aarch64"))]
const _: () = {
    assert!(libc::O_NOFOLLOW == 0x8000);
    assert!(libc::O_DIRECTORY == 0x4000);
    assert!((libc::O_NOFOLLOW | libc::O_DIRECTORY) & libc::O_DIRECT == 0);
};
struct Root {
    path: PathBuf,
    dev: u64,
    ino: u64,
    held:File,
    parent:PathBuf,
    parent_held:File,
}
static ROOTS: OnceLock<Mutex<BTreeMap<i64, Vec<Root>>>> = OnceLock::new();
fn registry() -> &'static Mutex<BTreeMap<i64, Vec<Root>>> {
    ROOTS.get_or_init(|| Mutex::new(BTreeMap::new()))
}
fn err() -> EngineError {
    EngineError::new(
        "GPU_CLEANUP_IDENTITY",
        "Private GPU resource identity unavailable",
    )
}
fn identity(path: &Path) -> Result<(u64, u64)> {
    let m = fs::symlink_metadata(path).map_err(|_| err())?;
    if !m.is_dir()
        || m.file_type().is_symlink()
        || path.canonicalize().ok().as_deref() != Some(path)
    {
        return Err(err());
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        Ok((m.dev(), m.ino()))
    }
    #[cfg(not(unix))]
    {
        Err(err())
    }
}
fn held_identity(file:&File)->Result<(u64,u64)>{let m=file.metadata().map_err(|_|err())?;if !m.is_dir(){return Err(err());}#[cfg(unix)]{use std::os::unix::fs::MetadataExt;Ok((m.dev(),m.ino()))}#[cfg(not(unix))]{Err(err())}}
fn unlinked(file:&File)->bool{#[cfg(unix)]{use std::os::unix::fs::MetadataExt;file.metadata().is_ok_and(|m|m.nlink()==0)}#[cfg(not(unix))]{false}}
fn hold(path:&Path)->Result<File>{let before=identity(path)?;let mut options=OpenOptions::new();options.read(true);#[cfg(any(target_os="android",target_os="linux"))]{use std::os::unix::fs::OpenOptionsExt;options.custom_flags(libc::O_NOFOLLOW | libc::O_DIRECTORY);}let file=options.open(path).map_err(|_|err())?;if held_identity(&file)?!=before||identity(path)?!=before{return Err(err());}Ok(file)}
pub(crate) fn register(handle: i64, path: &Path) -> Result<()> {
    if handle <= 0 {
        return Ok(());
    }
    let held=hold(path)?;let(dev,ino)=held_identity(&held)?;let parent=path.parent().ok_or_else(err)?.to_owned();let parent_held=hold(&parent)?;
    let mut all = registry().lock().map_err(|_| err())?;
    let roots = all.entry(handle).or_default();
    roots.retain(|r| !unlinked(&r.held));
    if roots.iter().any(|r| r.path == path) {
        return Err(err());
    }
    if roots.len() >= 64 {
        return Err(EngineError::new(
            "GPU_RETAINED_LIMIT",
            "Retained GPU resource limit reached",
        ));
    }
    roots.push(Root {
        path: path.to_owned(),
        dev,
        ino,held,parent,parent_held,
    });
    Ok(())
}
pub(crate) fn cleanup_after_drain(handle: i64) -> Result<bool> {
    let mut all = registry().lock().map_err(|_| err())?;
    let Some(roots) = all.get_mut(&handle) else {
        return Ok(false);
    };
    let mut cleaned = false;
    let mut failed = false;
    roots.retain(|r| match fs::symlink_metadata(&r.path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {if unlinked(&r.held){false}else{failed=true;true}},
        Err(_) => {
            failed = true;
            true
        }
        Ok(_) => {
            if identity(&r.path).ok() != Some((r.dev, r.ino)) || held_identity(&r.held).ok()!=Some((r.dev,r.ino)) || !matches!((identity(&r.parent),held_identity(&r.parent_held)),(Ok(a),Ok(b)) if a==b) {
                failed = true;
                true
            } else {
                match fs::remove_dir_all(&r.path) {
                    Ok(()) => {
                        cleaned = true;
                        false
                    }
                    Err(_) => {
                        failed = true;
                        true
                    }
                }
            }
        }
    });
    if roots.is_empty() {
        all.remove(&handle);
    }
    if failed {
        Err(err())
    } else {
        Ok(cleaned)
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[cfg(any(target_os = "android", target_os = "linux"))]
    #[test]
    fn target_open_flags_do_not_request_direct_io() {
        assert_eq!((libc::O_NOFOLLOW | libc::O_DIRECTORY) & libc::O_DIRECT, 0);
        let base = std::env::temp_dir().join(format!("gpu-target-flags-{}", std::process::id()));
        fs::create_dir(&base).unwrap();
        let held = hold(&base).unwrap();
        assert_eq!(held_identity(&held).unwrap(), identity(&base).unwrap());
        let file = base.join("unaligned");
        use std::io::{Read, Write};
        use std::os::unix::fs::{symlink, OpenOptionsExt};
        let mut writer = OpenOptions::new().write(true).create_new(true)
            .custom_flags(libc::O_NOFOLLOW).open(&file).unwrap();
        writer.write_all(b"unaligned-data").unwrap();
        drop(writer);
        let mut reader = OpenOptions::new().read(true).custom_flags(libc::O_NOFOLLOW).open(&file).unwrap();
        let mut bytes = Vec::new(); reader.read_to_end(&mut bytes).unwrap();
        assert_eq!(bytes, b"unaligned-data");
        let link = base.join("link"); symlink(&file, &link).unwrap();
        assert!(OpenOptions::new().read(true).custom_flags(libc::O_NOFOLLOW).open(&link).is_err());
        fs::remove_dir_all(base).unwrap();
    }
    #[test]
    fn cleanup_only_registered_identity() {
        let base = std::env::temp_dir().join(format!("gpu-root-registry-{}", std::process::id()));
        fs::create_dir(&base).unwrap();
        let root = base.join("owned");
        fs::create_dir(&root).unwrap();
        register(112358, &root).unwrap();
        let moved = base.join("old");
        fs::rename(&root, &moved).unwrap();
        fs::create_dir(&root).unwrap();
        assert!(cleanup_after_drain(112358).is_err());
        assert!(root.exists());
        assert!(moved.exists());
        fs::remove_dir(&root).unwrap();
        assert!(cleanup_after_drain(112358).is_err());
        fs::remove_dir(&moved).unwrap();
        assert!(!cleanup_after_drain(112358).unwrap());
        fs::remove_dir_all(&base).unwrap();
    }
    #[test]fn parent_replacement_never_authorizes_foreign_cleanup(){
      let base=std::env::temp_dir().join(format!("gpu-parent-registry-{}",std::process::id()));fs::create_dir(&base).unwrap();let parent=base.join("parent");fs::create_dir(&parent).unwrap();let root=parent.join("owned");fs::create_dir(&root).unwrap();register(112359,&root).unwrap();let moved=base.join("moved");fs::rename(&parent,&moved).unwrap();fs::create_dir(&parent).unwrap();fs::create_dir(&root).unwrap();fs::write(root.join("foreign"),b"keep").unwrap();assert!(cleanup_after_drain(112359).is_err());assert_eq!(fs::read(root.join("foreign")).unwrap(),b"keep");fs::remove_dir_all(&root).unwrap();assert!(cleanup_after_drain(112359).is_err());fs::remove_dir_all(&moved).unwrap();assert!(!cleanup_after_drain(112359).unwrap());fs::remove_dir_all(base).unwrap();
    }
    #[cfg(unix)]#[test]fn replaced_symlink_and_recreated_inode_are_not_followed(){
      use std::os::unix::fs::symlink;
      let base=std::env::temp_dir().join(format!("gpu-symlink-registry-{}",std::process::id()));fs::create_dir(&base).unwrap();let root=base.join("owned");fs::create_dir(&root).unwrap();register(112360,&root).unwrap();fs::remove_dir(&root).unwrap();let foreign=base.join("foreign");fs::create_dir(&foreign).unwrap();fs::write(foreign.join("keep"),b"keep").unwrap();symlink(&foreign,&root).unwrap();assert!(cleanup_after_drain(112360).is_err());assert!(foreign.join("keep").exists());fs::remove_file(&root).unwrap();assert!(!cleanup_after_drain(112360).unwrap());fs::remove_dir_all(base).unwrap();
    }

}
