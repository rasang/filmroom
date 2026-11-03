//! Private immutable manifests and all-three-plane atomic candidate validation.
//! Main process publishes only after confirmed helper death. No source mutation.
use super::*;
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
const OPERATION: &str = "fullFilmOptics";
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema_version: u32,
    operation: String,
    token: u64,
    optics: FilmOpticsPlan,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StagePlan {
    pub schema_version: u32,
    pub operation: String,
    pub token: u64,
    pub manifest_sha256: String,
    pub width: u32,
    pub height: u32,
    pub pass_count: u64,
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub disk_bytes: u64,
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Complete {
    schema_version: u32,
    operation: String,
    token: u64,
    manifest_sha256: String,
    width: u32,
    height: u32,
    plane_sha256: [String; 3],
}
struct OwnedDir(PathBuf, bool);
impl Drop for OwnedDir {
    fn drop(&mut self) {
        if !self.1 {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
}
fn io<T>(x: std::io::Result<T>) -> R<T> {
    x.map_err(|_| "GPU_OPTICS_IO".into())
}
fn hex(x: &[u8]) -> String {
    x.iter().map(|b| format!("{b:02x}")).collect()
}
fn valid_hash(x: &str) -> bool {
    x.len() == 64
        && x.bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}
fn regular(p: &Path, size: u64) -> R<()> {
    let m = io(fs::symlink_metadata(p))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() != size {
        return Err("INVALID_OPTICS_FILE".into());
    }
    Ok(())
}
fn owner(dir: &Path, token: u64) -> R<()> {
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|v| v.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return Err("INVALID_OPTICS_OWNER".into());
    }
    let m = io(fs::symlink_metadata(dir))?;
    if !m.is_dir() || m.file_type().is_symlink() || io(dir.canonicalize())? != dir {
        return Err("INVALID_OPTICS_OWNER".into());
    }
    Ok(())
}
fn read_json<T: serde::de::DeserializeOwned>(p: &Path) -> R<(T, Vec<u8>)> {
    let m = io(fs::symlink_metadata(p))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() > 32768 {
        return Err("INVALID_OPTICS_MANIFEST".into());
    }
    let b = io(fs::read(p))?;
    let value = serde_json::from_slice(&b).map_err(|_| "INVALID_OPTICS_MANIFEST")?;
    Ok((value, b))
}
fn make_plan(m: &Manifest, hash: String) -> R<StagePlan> {
    if m.schema_version != 1
        || m.operation != OPERATION
        || m.token == 0
        || m.token > i64::MAX as u64
        || !valid_hash(&hash)
    {
        return Err("INVALID_OPTICS_MANIFEST".into());
    }
    m.optics.validate()?;
    let p = &m.optics;
    let count = p
        .channels
        .iter()
        .map(|c| {
            u64::from(c.lens_sigma > 0.)
                + if c.scatter.is_some() { 4 } else { 0 }
                + c.bounce_sigmas.len() as u64
        })
        .sum();
    Ok(StagePlan {
        schema_version: 1,
        operation: OPERATION.into(),
        token: m.token,
        manifest_sha256: hash,
        width: p.width,
        height: p.height,
        pass_count: count,
        device_bytes: p.device_bytes,
        host_bytes: p.host_bytes,
        driver_allowance_bytes: p.driver_allowance_bytes,
        helper_allowance_bytes: p.helper_allowance_bytes,
        combined_peak_bytes: p.combined_peak_bytes,
        disk_bytes: p.disk_bytes,
    })
}
fn load(dir: &Path, token: u64) -> R<(Manifest, StagePlan)> {
    owner(dir, token)?;
    let (m, b): (Manifest, _) = read_json(&dir.join("manifest.json"))?;
    if m.token != token {
        return Err("INVALID_OPTICS_MANIFEST".into());
    }
    let p = make_plan(&m, hex(&Sha256::digest(b)))?;
    for ch in 0..3 {
        regular(
            &dir.join(format!("source-{ch}.f64le")),
            p.width as u64 * p.height as u64 * 8,
        )?;
    }
    Ok((m, p))
}
pub fn estimated_disk_bytes(width: u32, height: u32, optics: &FilmOpticsPlan) -> R<u64> {
    optics.validate()?;
    if optics.width != width || optics.height != height {
        return Err("INVALID_OPTICS_PLAN".into());
    }
    Ok(optics.disk_bytes)
}
#[allow(clippy::too_many_arguments)]
pub fn prepare_stage(
    sources: [&Path; 3],
    dir: &Path,
    token: u64,
    width: u32,
    height: u32,
    optics: &FilmOpticsPlan,
    mut cancelled: impl FnMut() -> bool,
) -> R<StagePlan> {
    estimated_disk_bytes(width, height, optics)?;
    check(&mut cancelled)?;
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|v| v.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return Err("INVALID_OPTICS_OWNER".into());
    }
    crate::gpu_stage_staging::create_stage_dir(dir)?;
    let mut guard = OwnedDir(dir.to_path_buf(), false);
    let n = width as u64 * height as u64;
    crate::gpu_stage_staging::snapshot_sources(sources, dir, n, 24 * n, &mut cancelled)?;
    let m = Manifest {
        schema_version: 1,
        operation: OPERATION.into(),
        token,
        optics: optics.clone(),
    };
    let b = serde_json::to_vec(&m).map_err(|_| "INVALID_OPTICS_MANIFEST")?;
    let p = make_plan(&m, hex(&Sha256::digest(&b)))?;
    use crate::gpu_stage_staging::{Phase, io_at};
    let temp = dir.join("manifest.tmp");
    let mut f = io_at(
        Phase::ManifestCreate,
        OpenOptions::new().write(true).create_new(true).open(&temp),
    )?;
    io_at(Phase::ManifestWrite, f.write_all(&b))?;
    io_at(Phase::ManifestSync, f.sync_all())?;
    drop(f);
    check(&mut cancelled)?;
    io_at(
        Phase::ManifestRename,
        fs::rename(temp, dir.join("manifest.json")),
    )?;
    guard.1 = true;
    Ok(p)
}
pub fn plan_stage(dir: &Path, token: u64) -> R<StagePlan> {
    load(dir, token).map(|(_, p)| p)
}
fn plane_hash(path: &Path, n: u64, c: &mut impl FnMut() -> bool) -> R<String> {
    regular(path, n * 8)?;
    let mut f = io(File::open(path))?;
    let mut b = vec![0u8; 65536];
    let mut left = n * 8;
    let mut hash = Sha256::new();
    while left > 0 {
        check(c)?;
        let len = left.min(b.len() as u64) as usize;
        io(f.read_exact(&mut b[..len]))?;
        for x in b[..len].chunks_exact(8) {
            if !f64::from_le_bytes(x.try_into().unwrap()).is_finite() {
                return Err("NONFINITE_OPTICS_OUTPUT".into());
            }
        }
        hash.update(&b[..len]);
        left -= len as u64;
    }
    Ok(hex(&hash.finalize()))
}
fn publish(dir: &Path, m: &Manifest, p: &StagePlan, c: &mut impl FnMut() -> bool) -> R<()> {
    let candidate = dir.join("candidate");
    let n = p.width as u64 * p.height as u64;
    let mut hashes = std::array::from_fn(|_| String::new());
    for ch in 0..3 {
        hashes[ch] = plane_hash(&candidate.join(format!("plane-{ch}.f64le")), n, c)?;
    }
    let complete = Complete {
        schema_version: 1,
        operation: OPERATION.into(),
        token: m.token,
        manifest_sha256: p.manifest_sha256.clone(),
        width: p.width,
        height: p.height,
        plane_sha256: hashes,
    };
    let b = serde_json::to_vec(&complete).map_err(|_| "INVALID_OPTICS_COMPLETE")?;
    let temp = candidate.join("complete.tmp");
    let mut f = io(OpenOptions::new().write(true).create_new(true).open(&temp))?;
    io(f.write_all(&b))?;
    io(f.sync_all())?;
    drop(f);
    check(c)?;
    io(fs::rename(temp, candidate.join("complete.json")))?;
    Ok(())
}
pub fn validate_outputs(
    dir: &Path,
    token: u64,
    manifest_sha256: &str,
    mut cancelled: impl FnMut() -> bool,
) -> R<[PathBuf; 3]> {
    check(&mut cancelled)?;
    let (_, p) = load(dir, token)?;
    if p.manifest_sha256 != manifest_sha256 {
        return Err("INVALID_OPTICS_MANIFEST".into());
    }
    let candidate = dir.join("candidate");
    let meta = io(fs::symlink_metadata(&candidate))?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return Err("INVALID_OPTICS_COMPLETE".into());
    }
    let (complete, _): (Complete, _) = read_json(&candidate.join("complete.json"))?;
    if complete.schema_version != 1
        || complete.operation != OPERATION
        || complete.token != token
        || complete.manifest_sha256 != manifest_sha256
        || complete.width != p.width
        || complete.height != p.height
        || complete.plane_sha256.iter().any(|x| !valid_hash(x))
    {
        return Err("INVALID_OPTICS_COMPLETE".into());
    }
    let paths = std::array::from_fn(|ch| candidate.join(format!("plane-{ch}.f64le")));
    for ch in 0..3 {
        if plane_hash(&paths[ch], p.width as u64 * p.height as u64, &mut cancelled)?
            != complete.plane_sha256[ch]
        {
            return Err("INVALID_OPTICS_COMPLETE".into());
        }
    }
    check(&mut cancelled)?;
    Ok(paths)
}
pub fn run_stage(
    dir: &Path,
    token: u64,
    budget_bytes: u64,
    manifest_sha256: &str,
    mut cancelled: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    let stage_started = std::time::Instant::now();
    check(&mut cancelled)?;
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != manifest_sha256 {
        return Err("INVALID_OPTICS_MANIFEST".into());
    }
    if budget_bytes < p.combined_peak_bytes || budget_bytes > MEMORY_CAP {
        return Err("INSUFFICIENT_GPU_OPTICS_MEMORY".into());
    }
    #[cfg(not(feature = "gpu-diffusion-research"))]
    {
        let _ = (m, p, stage_started);
        Err("GPU_OPTICS_UNSUPPORTED".into())
    }
    #[cfg(feature = "gpu-diffusion-research")]
    {
        let candidate = dir.join("candidate");
        io(fs::create_dir(&candidate))?;
        let mut guard = OwnedDir(candidate.clone(), false);
        let sources: [PathBuf; 3] =
            std::array::from_fn(|ch| dir.join(format!("source-{ch}.f64le")));
        let outputs: [PathBuf; 3] =
            std::array::from_fn(|ch| candidate.join(format!("plane-{ch}.f64le")));
        let metrics = super::gpu::run_files(
            &m.optics,
            sources.each_ref().map(|x| x.as_path()),
            outputs.each_ref().map(|x| x.as_path()),
            &mut cancelled,
            &mut |_, _| {},
        )?;
        if metrics.pass_count != p.pass_count
            || metrics.sample_count != p.pass_count * (p.width as u64 + p.height as u64)
        {
            return Err("GPU_OPTICS_NUMERIC_CHECK".into());
        }
        publish(dir, &m, &p, &mut cancelled)?;
        check(&mut cancelled)?;
        let mut result = serde_json::to_value(metrics).map_err(|_| "INVALID_OPTICS_COMPLETE")?;
        let obj = result.as_object_mut().ok_or("INVALID_OPTICS_COMPLETE")?;
        obj.insert(
            "elapsedMs".into(),
            serde_json::json!(stage_started.elapsed().as_secs_f64() * 1000.),
        );
        for (k, v) in [
            ("ok", serde_json::json!(true)),
            ("schemaVersion", serde_json::json!(1)),
            ("operation", serde_json::json!(OPERATION)),
            ("token", serde_json::json!(token)),
            ("manifestSha256", serde_json::json!(manifest_sha256)),
            ("gpuUsed", serde_json::json!(true)),
            ("fullImage", serde_json::json!(true)),
            ("width", serde_json::json!(p.width)),
            ("height", serde_json::json!(p.height)),
            ("channels", serde_json::json!(3)),
            (
                "backend",
                serde_json::json!("Vulkan f32 resident original FIR/IIR optics"),
            ),
        ] {
            obj.insert(k.into(), v);
        }
        guard.1 = true;
        Ok(result)
    }
}
#[cfg(test)]
pub fn run_stage_cpu_mirror_for_test(
    dir: &Path,
    token: u64,
    manifest_sha256: &str,
    mut cancelled: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != manifest_sha256 {
        return Err("INVALID_OPTICS_MANIFEST".into());
    }
    m.optics.numerical_preflight(&mut cancelled)?;
    let candidate = dir.join("candidate");
    io(fs::create_dir(&candidate))?;
    let mut guard = OwnedDir(candidate.clone(), false);
    for ch in 0..3 {
        check(&mut cancelled)?;
        let b = io(fs::read(dir.join(format!("source-{ch}.f64le"))))?;
        let source: Vec<f64> = b
            .chunks_exact(8)
            .map(|x| f64::from_le_bytes(x.try_into().unwrap()))
            .collect();
        let output = channel_cpu_mirror(
            &source,
            p.width,
            p.height,
            &m.optics.channels[ch],
            &mut cancelled,
        )?;
        let mut f = io(OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(candidate.join(format!("plane-{ch}.f64le"))))?;
        for x in output {
            io(f.write_all(&x.to_le_bytes()))?;
        }
        io(f.sync_all())?;
    }
    publish(dir, &m, &p, &mut cancelled)?;
    guard.1 = true;
    Ok(
        serde_json::json!({"ok":true,"gpuUsed":false,"operation":OPERATION,"hostArithmeticMirror":true,"passCount":p.pass_count}),
    )
}
