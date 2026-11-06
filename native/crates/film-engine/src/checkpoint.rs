//! Durable stage-boundary snapshots. Working planes are never aliases of snapshots.
//! A failed/current stage is replayed; a manifest never refers to half-written files.
use serde::{Deserialize, Serialize};
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read, Write},
    path::{Path, PathBuf},
};
const VERSION: &str = "filmroom-streamed-f64-checkpoint-v1";
const BUFFER: usize = 128 * 1024;
fn invalid() -> io::Error {
    io::Error::new(
        io::ErrorKind::InvalidData,
        "Invalid or incompatible export checkpoint",
    )
}
fn regular(path: &Path, size: u64) -> io::Result<()> {
    let m = fs::symlink_metadata(path)?;
    if !m.is_file() || m.len() != size {
        return Err(invalid());
    }
    Ok(())
}
fn sync_dir(path: &Path) -> io::Result<()> {
    File::open(path)?.sync_all()
}
fn cancelled(c: &mut (impl FnMut() -> bool + ?Sized)) -> io::Result<()> {
    if c() {
        Err(io::Error::new(
            io::ErrorKind::Interrupted,
            "Export cancelled",
        ))
    } else {
        Ok(())
    }
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Identity {
    version: String,
    width: u32,
    height: u32,
    configuration: String,
    source: String,
    profiles: String,
}
impl Identity {
    /// Reuse the CURRENT export's freshly verified content/data hashes, never
    /// historical path/mtime stamps. Only dependency configuration is replaced.
    pub(crate) fn with_configuration(&self, configuration:String)->io::Result<Self> {
        if configuration.len()>64*1024 {return Err(invalid());}
        let mut identity=self.clone();identity.configuration=configuration;Ok(identity)
    }
    pub fn new(
        data: &Path,
        input: &Path,
        width: u32,
        height: u32,
        configuration: String,
        c: &mut impl FnMut() -> bool,
    ) -> io::Result<Self> {
        if !(1..=40_000).contains(&width)
            || !(1..=40_000).contains(&height)
            || configuration.len() > 64 * 1024
        {
            return Err(invalid());
        }
        regular(input, width as u64 * height as u64 * 12)?;
        let source = hash_file(input, c)?;
        let mut hash = Sha256::new();
        hash_tree(data, data, &mut hash, c)?;
        Ok(Self {
            version: VERSION.into(),
            width,
            height,
            configuration,
            source,
            profiles: hash.finish(),
        })
    }
}
fn hash_tree(
    root: &Path,
    dir: &Path,
    hash: &mut Sha256,
    c: &mut impl FnMut() -> bool,
) -> io::Result<()> {
    let mut entries = fs::read_dir(dir)?.collect::<io::Result<Vec<_>>>()?;
    entries.sort_by_key(|e| e.file_name());
    for e in entries {
        cancelled(c)?;
        let kind = e.file_type()?;
        if kind.is_dir() {
            hash_tree(root, &e.path(), hash, c)?;
        } else if kind.is_file() {
            let name = e
                .path()
                .strip_prefix(root)
                .map_err(|_| invalid())?
                .to_string_lossy()
                .into_owned();
            hash.update(&(name.len() as u64).to_le_bytes());
            hash.update(name.as_bytes());
            hash.update(hash_file(&e.path(), c)?.as_bytes());
        } else {
            return Err(invalid());
        }
    }
    Ok(())
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Plane {
    bytes: u64,
    sha256: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Manifest {
    identity: Identity,
    stage: u8,
    metered_ev: Option<f32>,
    planes: Vec<Plane>,
}
pub struct Checkpoints {
    root: PathBuf,
    identity: Identity,
    manifest: Option<Manifest>,
    commit_failed: bool,
}
impl Checkpoints {
    pub fn open(root: &Path, identity: Identity, c: &mut impl FnMut() -> bool) -> io::Result<Self> {
        if root.exists() {
            if !fs::symlink_metadata(root)?.is_dir() {
                return Err(invalid());
            }
        } else {
            fs::create_dir(root)?;
            sync_dir(root.parent().ok_or_else(invalid)?)?;
        }
        let path = root.join("manifest.json");
        let manifest = if path.exists() {
            let m = fs::symlink_metadata(&path)?;
            if !m.is_file() || m.len() > 128 * 1024 {
                return Err(invalid());
            }
            let manifest: Manifest =
                serde_json::from_slice(&fs::read(&path)?).map_err(|_| invalid())?;
            if manifest.identity != identity
                || !(2..=6).contains(&manifest.stage)
                || manifest.metered_ev.is_some_and(|x| !x.is_finite())
            {
                return Err(invalid());
            }
            let count = if manifest.stage == 6 { 1 } else { 3 };
            let bytes = identity.width as u64
                * identity.height as u64
                * if manifest.stage == 6 { 12 } else { 8 };
            if manifest.planes.len() != count {
                return Err(invalid());
            }
            let dir = root.join(format!("stage-{}", manifest.stage));
            if !fs::symlink_metadata(&dir)?.is_dir() {
                return Err(invalid());
            }
            for (i, p) in manifest.planes.iter().enumerate() {
                let file = dir.join(format!("{i}.bin"));
                if p.bytes != bytes {
                    return Err(invalid());
                }
                regular(&file, bytes)?;
                if hash_file(&file, c)? != p.sha256 {
                    return Err(invalid());
                }
            }
            Some(manifest)
        } else {
            None
        };
        let result = Self {
            root: root.into(),
            identity,
            manifest,
            commit_failed: false,
        };
        result.remove_obsolete()?;
        Ok(result)
    }
    pub fn stage(&self) -> u8 {
        self.manifest.as_ref().map_or(0, |m| m.stage)
    }
    pub fn metered_ev(&self) -> Option<f32> {
        self.manifest.as_ref().and_then(|m| m.metered_ev)
    }
    fn remove_obsolete(&self) -> io::Result<()> {
        // Only our fixed namespace; never walk arbitrary paths from the manifest.
        for stage in 2..=6 {
            if stage != self.stage() {
                let p = self.root.join(format!("stage-{stage}"));
                if p.exists() {
                    fs::remove_dir_all(p)?;
                }
            }
        }
        for name in ["pending", "manifest.tmp"] {
            let p = self.root.join(name);
            if p.exists() {
                if p.is_dir() {
                    fs::remove_dir_all(p)?;
                } else {
                    fs::remove_file(p)?;
                }
            }
        }
        Ok(())
    }
    pub fn restore(
        &self,
        destinations: &[PathBuf],
        c: &mut impl FnMut() -> bool,
    ) -> io::Result<()> {
        if self.commit_failed {
            return Err(invalid());
        }
        let manifest = self.manifest.as_ref().ok_or_else(invalid)?;
        if destinations.len() != manifest.planes.len() {
            return Err(invalid());
        }
        for (i, dest) in destinations.iter().enumerate() {
            copy_synced(
                &self.root.join(format!("stage-{}/{i}.bin", manifest.stage)),
                dest,
                c,
            )?;
        }
        Ok(())
    }
    pub fn commit(
        &mut self,
        stage: u8,
        sources: &[PathBuf],
        metered_ev: Option<f32>,
        c: &mut impl FnMut() -> bool,
    ) -> io::Result<()> {
        self.commit_using(stage, sources, metered_ev, c, |src, dst, cancel| {
            copy_synced(src, dst, cancel)
        })
    }
    fn commit_using(
        &mut self,
        stage: u8,
        sources: &[PathBuf],
        metered_ev: Option<f32>,
        c: &mut impl FnMut() -> bool,
        mut copy: impl FnMut(&Path, &Path, &mut dyn FnMut() -> bool) -> io::Result<String>,
    ) -> io::Result<()> {
        if self.commit_failed
            || !(2..=6).contains(&stage)
            || stage <= self.stage()
            || sources.len() != if stage == 6 { 1 } else { 3 }
        {
            return Err(invalid());
        }
        // On any write/sync/rename failure the object must be reopened. In
        // particular a failed directory fsync may follow a successful manifest
        // swap; retrying against stale in-memory state must not delete it.
        self.commit_failed = true;
        self.remove_obsolete()?;
        let pending = self.root.join("pending");
        fs::create_dir(&pending)?;
        let expected = self.identity.width as u64
            * self.identity.height as u64
            * if stage == 6 { 12 } else { 8 };
        let mut planes = Vec::new();
        for (i, src) in sources.iter().enumerate() {
            regular(src, expected)?;
            let dst = pending.join(format!("{i}.bin"));
            let sha256 = copy(src, &dst, c)?;
            planes.push(Plane {
                bytes: expected,
                sha256,
            });
        }
        sync_dir(&pending)?;
        cancelled(c)?;
        let manifest = Manifest {
            identity: self.identity.clone(),
            stage,
            metered_ev,
            planes,
        };
        let bytes = serde_json::to_vec(&manifest).map_err(|_| invalid())?;
        #[cfg(test)]
        test_commit_fault("manifest_write", &self.root)?;
        let mut f = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(self.root.join("manifest.tmp"))?;
        f.write_all(&bytes)?;
        #[cfg(test)]
        test_commit_fault("manifest_sync", &self.root)?;
        f.sync_all()?;
        drop(f);
        #[cfg(test)]
        test_commit_fault("generation_rename", &self.root)?;
        fs::rename(&pending, self.root.join(format!("stage-{stage}")))?;
        sync_dir(&self.root)?;
        #[cfg(test)]
        test_commit_fault("generation_renamed", &self.root)?;
        #[cfg(test)]
        test_commit_fault("manifest_rename", &self.root)?;
        fs::rename(
            self.root.join("manifest.tmp"),
            self.root.join("manifest.json"),
        )?;
        #[cfg(test)]
        test_commit_fault("manifest_directory_sync", &self.root)?;
        sync_dir(&self.root)?;
        #[cfg(test)]
        test_commit_fault("manifest_swapped", &self.root)?;
        self.manifest = Some(manifest);
        self.remove_obsolete()?;
        sync_dir(&self.root)?;
        self.commit_failed = false;
        Ok(())
    }
}
fn copy_synced(
    src: &Path,
    dst: &Path,
    c: &mut (impl FnMut() -> bool + ?Sized),
) -> io::Result<String> {
    let mut input = File::open(src)?;
    let mut output = OpenOptions::new().write(true).create_new(true).open(dst)?;
    let mut hash = Sha256::new();
    let mut buf = vec![0; BUFFER];
    loop {
        cancelled(c)?;
        let n = input.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hash.update(&buf[..n]);
        output.write_all(&buf[..n])?;
    }
    output.sync_all()?;
    Ok(hash.finish())
}
pub fn hash_file(path: &Path, c: &mut impl FnMut() -> bool) -> io::Result<String> {
    let mut input = File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0; BUFFER];
    loop {
        cancelled(c)?;
        let n = input.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.finish())
}
// SHA-256 (FIPS 180-4), incremental and bounded. Used for corruption/configuration
// integrity, not authenticity: app-private files remain the trust boundary.
pub(crate) struct Sha256 {
    state: [u32; 8],
    block: [u8; 64],
    used: usize,
    len: u64,
}
impl Sha256 {
    pub(crate) fn new() -> Self {
        Self {
            state: [
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
                0x5be0cd19,
            ],
            block: [0; 64],
            used: 0,
            len: 0,
        }
    }
    pub(crate) fn update(&mut self, mut bytes: &[u8]) {
        self.len += bytes.len() as u64;
        while !bytes.is_empty() {
            let n = (64 - self.used).min(bytes.len());
            self.block[self.used..self.used + n].copy_from_slice(&bytes[..n]);
            self.used += n;
            bytes = &bytes[n..];
            if self.used == 64 {
                self.compress();
                self.used = 0;
            }
        }
    }
    fn compress(&mut self) {
        const K: [u32; 64] = [
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
            0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
            0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
            0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
            0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
            0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
            0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
            0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
            0xc67178f2,
        ];
        let mut w = [0u32; 64];
        for (i, b) in self.block.chunks_exact(4).enumerate() {
            w[i] = u32::from_be_bytes(b.try_into().unwrap());
        }
        for i in 16..64 {
            let a = w[i - 15];
            let b = w[i - 2];
            let s0 = a.rotate_right(7) ^ a.rotate_right(18) ^ (a >> 3);
            let s1 = b.rotate_right(17) ^ b.rotate_right(19) ^ (b >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = self.state;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ (!e & g);
            let t1 = h
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }
        for (s, v) in self.state.iter_mut().zip([a, b, c, d, e, f, g, h]) {
            *s = s.wrapping_add(v);
        }
    }
    pub(crate) fn finish(mut self) -> String {
        let bits = self.len * 8;
        self.update(&[0x80]);
        while self.used != 56 {
            self.update(&[0]);
        }
        self.update(&bits.to_be_bytes());
        self.state.iter().map(|x| format!("{x:08x}")).collect()
    }
}
// Fault controls exist only in the Rust unit-test binary, never Android builds.
#[cfg(test)]
fn test_commit_fault(phase: &str, root: &Path) -> io::Result<()> {
    let Ok(control) = std::env::var("FILMROOM_COMMIT_FAULT") else {
        return Ok(());
    };
    if control == format!("error:{phase}") {
        return Err(io::Error::from_raw_os_error(28));
    }
    if control == format!("kill:{phase}") {
        fs::write(root.join("test-ready"), b"ready")?;
        loop {
            std::thread::sleep(std::time::Duration::from_secs(1));
        }
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn sha256_vectors() {
        for (input, expected) in [
            (
                "",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
            (
                "abc",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
            (
                "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            ),
        ] {
            let mut h = Sha256::new();
            for b in input.bytes() {
                h.update(&[b]);
            }
            assert_eq!(h.finish(), expected);
        }
    }
    #[test]
    fn disk_full_during_next_snapshot_preserves_previous_commit() {
        let root = std::env::temp_dir().join(format!("checkpoint-enospc-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        fs::create_dir(&root).unwrap();
        let id = Identity {
            version: VERSION.into(),
            width: 1,
            height: 1,
            configuration: "seed42".into(),
            source: "synthetic".into(),
            profiles: "synthetic".into(),
        };
        let paths: [PathBuf; 3] = std::array::from_fn(|i| {
            let p = root.join(format!("src{i}"));
            fs::write(&p, 0f64.to_le_bytes()).unwrap();
            p
        });
        let dir = root.join("checkpoint");
        let mut c = Checkpoints::open(&dir, id.clone(), &mut || false).unwrap();
        c.commit(2, &paths, None, &mut || false).unwrap();
        let before = fs::read(dir.join("manifest.json")).unwrap();
        let mut calls = 0;
        let result = c.commit_using(3, &paths, None, &mut || false, |src, dst, cancel| {
            calls += 1;
            if calls == 2 {
                Err(io::Error::from_raw_os_error(28))
            } else {
                copy_synced(src, dst, cancel)
            }
        });
        assert_eq!(result.unwrap_err().raw_os_error(), Some(28));
        assert_eq!(before, fs::read(dir.join("manifest.json")).unwrap());
        assert_eq!(
            Checkpoints::open(&dir, id, &mut || false).unwrap().stage(),
            2
        );
        assert!(!dir.join("pending").exists());
        fs::remove_dir_all(root).unwrap();
    }
    fn fault_identity() -> Identity {
        Identity {
            version: VERSION.into(),
            width: 1,
            height: 1,
            configuration: "seed42".into(),
            source: "synthetic".into(),
            profiles: "synthetic".into(),
        }
    }
    #[test]
    fn commit_fault_worker() {
        let Ok(root) = std::env::var("FILMROOM_COMMIT_ROOT") else {
            return;
        };
        let root = Path::new(&root);
        let sources: [PathBuf; 3] = std::array::from_fn(|i| root.join(format!("src{i}")));
        let mut c =
            Checkpoints::open(&root.join("checkpoint"), fault_identity(), &mut || false).unwrap();
        let error = c.commit(3, &sources, None, &mut || false).unwrap_err();
        assert_eq!(error.raw_os_error(), Some(28));
        // Failed commit state must not be reused after a possibly successful
        // manifest swap followed by an fsync error. Reopen is authoritative.
        assert!(c.commit(3, &sources, None, &mut || false).is_err());
    }
    #[test]
    fn crash_and_enospc_at_manifest_transition_boundaries() {
        use std::{
            process::{Command, Stdio},
            time::{Duration, Instant},
        };
        for (control, expected) in [
            ("kill:generation_renamed", 2),
            ("kill:manifest_swapped", 3),
            ("error:manifest_write", 2),
            ("error:manifest_sync", 2),
            ("error:generation_rename", 2),
            ("error:manifest_rename", 2),
            ("error:manifest_directory_sync", 3),
        ] {
            let root = std::env::temp_dir().join(format!(
                "checkpoint-switch-{}-{}",
                std::process::id(),
                control.replace(':', "-")
            ));
            let _ = fs::remove_dir_all(&root);
            fs::create_dir(&root).unwrap();
            let sources: [PathBuf; 3] = std::array::from_fn(|i| {
                let p = root.join(format!("src{i}"));
                fs::write(&p, 0f64.to_le_bytes()).unwrap();
                p
            });
            let dir = root.join("checkpoint");
            let mut c = Checkpoints::open(&dir, fault_identity(), &mut || false).unwrap();
            c.commit(2, &sources, None, &mut || false).unwrap();
            for p in &sources {
                fs::write(p, 1f64.to_le_bytes()).unwrap();
            }
            let mut child = Command::new(std::env::current_exe().unwrap())
                .args([
                    "--exact",
                    "checkpoint::tests::commit_fault_worker",
                    "--nocapture",
                ])
                .env("FILMROOM_COMMIT_ROOT", &root)
                .env("FILMROOM_COMMIT_FAULT", control)
                .stdout(Stdio::null())
                .stderr(Stdio::inherit())
                .spawn()
                .unwrap();
            if control.starts_with("kill:") {
                let start = Instant::now();
                while !dir.join("test-ready").exists() {
                    if let Some(status) = child.try_wait().unwrap() {
                        panic!("worker exited before kill point: {status}");
                    }
                    if start.elapsed() > Duration::from_secs(30) {
                        let _ = child.kill();
                        panic!("fault worker timeout");
                    }
                    std::thread::sleep(Duration::from_millis(10));
                }
                child.kill().unwrap();
                assert!(!child.wait().unwrap().success());
            } else {
                assert!(child.wait().unwrap().success());
            }
            let c = Checkpoints::open(&dir, fault_identity(), &mut || false).unwrap();
            assert_eq!(c.stage(), expected, "{control}");
            let restored: [PathBuf; 3] = std::array::from_fn(|i| root.join(format!("restored{i}")));
            c.restore(&restored, &mut || false).unwrap();
            for p in restored {
                assert_eq!(
                    fs::read(p).unwrap(),
                    if expected == 2 { 0f64 } else { 1f64 }.to_le_bytes()
                );
            }
            fs::remove_dir_all(root).unwrap();
        }
    }
}
