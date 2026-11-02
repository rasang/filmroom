//! Explicit row I/O; no mmap and no global temporary directory.
use crate::Result;
use std::{
    fs::{File, OpenOptions},
    os::unix::{
        fs::{FileExt, OpenOptionsExt},
        io::AsRawFd,
    },
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};
static NEXT: AtomicU64 = AtomicU64::new(0);
#[derive(Default)]
struct Account {
    live: u64,
    peak: u64,
    planned: u64,
}
struct Context {
    directory: PathBuf,
    limit: u64,
    account: Mutex<Account>,
    cancel: Arc<AtomicBool>,
}
#[derive(Clone)]
pub struct Scratch(Arc<Context>);
impl Scratch {
    pub fn new(directory: &Path, limit: u64, cancel: Arc<AtomicBool>) -> Result<Self> {
        if !directory.is_dir() {
            return Err("scratch directory missing".into());
        }
        Ok(Self(Arc::new(Context {
            directory: directory.into(),
            limit,
            account: Mutex::new(Account::default()),
            cancel,
        })))
    }
    pub fn check(&self) -> Result<()> {
        if self.0.cancel.load(Ordering::Relaxed) {
            Err("cancelled".into())
        } else {
            Ok(())
        }
    }
    pub fn usage(&self) -> (u64, u64) {
        let a = self.0.account.lock().unwrap();
        (a.live, a.peak)
    }
    pub fn planned_peak(&self) -> u64 {
        self.0.account.lock().unwrap().planned
    }
    pub fn admit_peak(&self, bytes: u64) -> Result<()> {
        self.check()?;
        let mut a = self.0.account.lock().unwrap();
        if a.live.checked_add(bytes).is_none_or(|v| v > self.0.limit) {
            return Err("scratch phase peak budget".into());
        }
        a.planned = a.planned.max(a.live + bytes);
        Ok(())
    }
    pub fn reserve(&self, bytes: u64) -> Result<AnonymousFile> {
        self.check()?;
        {
            let mut a = self.0.account.lock().unwrap();
            let n = a
                .live
                .checked_add(bytes)
                .filter(|n| *n <= self.0.limit)
                .ok_or("scratch disk budget")?;
            a.live = n;
            a.peak = a.peak.max(n);
        }
        let result = (|| -> Result<File> {
            let path = self.0.directory.join(format!(
                ".film-arw6-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            let file = OpenOptions::new()
                .read(true)
                .write(true)
                .create_new(true)
                .mode(0o600)
                .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC)
                .open(&path)?;
            // Unlink before allocation or any pixel data; FD lifetime is the only owner.
            if let Err(e) = std::fs::remove_file(&path) {
                drop(file);
                let _ = std::fs::remove_file(&path);
                return Err(e.into());
            }
            let length = libc::off_t::try_from(bytes)?;
            let rc = unsafe { libc::posix_fallocate(file.as_raw_fd(), 0, length) };
            if rc != 0 {
                return Err(std::io::Error::from_raw_os_error(rc).into());
            }
            self.check()?;
            Ok(file)
        })();
        match result {
            Ok(file) => Ok(AnonymousFile {
                file: Some(file),
                bytes,
                scratch: self.clone(),
            }),
            Err(e) => {
                self.0.account.lock().unwrap().live -= bytes;
                Err(e)
            }
        }
    }
}
pub struct AnonymousFile {
    pub(crate) file: Option<File>,
    bytes: u64,
    scratch: Scratch,
}
impl Drop for AnonymousFile {
    fn drop(&mut self) {
        drop(self.file.take());
        self.scratch.0.account.lock().unwrap().live -= self.bytes;
    }
}
impl AnonymousFile {
    pub fn len(&self) -> u64 {
        self.bytes
    }
    pub fn read(&self, offset: u64, out: &mut [u8]) -> Result<()> {
        self.scratch.check()?;
        self.bounds(offset, out.len())?;
        self.file
            .as_ref()
            .ok_or("closed scratch")?
            .read_exact_at(out, offset)?;
        Ok(())
    }
    pub fn write(&self, offset: u64, data: &[u8]) -> Result<()> {
        self.scratch.check()?;
        self.bounds(offset, data.len())?;
        self.file
            .as_ref()
            .ok_or("closed scratch")?
            .write_all_at(data, offset)?;
        Ok(())
    }
    fn bounds(&self, offset: u64, n: usize) -> Result<()> {
        if offset
            .checked_add(n as u64)
            .is_none_or(|end| end > self.bytes)
        {
            Err("scratch range".into())
        } else {
            Ok(())
        }
    }
}
pub struct Plane {
    pub w: usize,
    pub h: usize,
    file: AnonymousFile,
}
impl Plane {
    pub fn new(scratch: &Scratch, w: usize, h: usize) -> Result<Self> {
        if w == 0 || h == 0 || w > 32768 || h > 32768 {
            return Err("plane dimensions".into());
        }
        let bytes = w
            .checked_mul(h)
            .and_then(|v| v.checked_mul(4))
            .ok_or("plane overflow")?;
        Ok(Self {
            w,
            h,
            file: scratch.reserve(bytes as u64)?,
        })
    }
    pub fn row(&self, r: usize) -> Result<Vec<i32>> {
        if r >= self.h {
            return Err("plane row".into());
        }
        let mut bytes = vec![0; self.w * 4];
        self.file.read((r * self.w * 4) as u64, &mut bytes)?;
        Ok(bytes
            .chunks_exact(4)
            .map(|b| i32::from_le_bytes(b.try_into().unwrap()))
            .collect())
    }
    pub fn put(&self, r: usize, row: &[i32]) -> Result<()> {
        if r >= self.h || row.len() != self.w {
            return Err("plane write row".into());
        }
        let bytes: Vec<u8> = row.iter().flat_map(|v| v.to_le_bytes()).collect();
        self.file.write((r * self.w * 4) as u64, &bytes)
    }
}
