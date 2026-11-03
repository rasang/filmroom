//! Immutable bounded exposure packets and all-three-plane atomic publication.
//! The caller reserves disk before prepare and proves helper death before validate.
use super::ExposureDescriptor;
use crate::gpu_stage_staging::{io_at, Phase};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
pub(super) type R<T> = Result<T, String>;
pub const MIB: u64 = 1024 * 1024;
pub const MEMORY_CAP: u64 = 384 * MIB;
pub const OPERATION: &str = "fullFilmExposure";
pub const BACKEND: &str = "Vulkan f32 resident two-matrix bicubic exposure";
pub(super) fn err<T>(code: &str) -> R<T> {
    Err(code.into())
}
pub(super) fn io<T>(value: std::io::Result<T>) -> R<T> {
    value.map_err(|_| "GPU_EXPOSURE_IO".into())
}
pub(super) fn check(cancel: &mut impl FnMut() -> bool) -> R<()> {
    if cancel() {
        err("CANCELLED")
    } else {
        Ok(())
    }
}
pub(super) fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}
fn valid_hash(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}
fn bits(v: f64) -> String {
    format!("{:016x}", v.to_bits())
}
fn from_bits(s: &str) -> R<f64> {
    if s.len() != 16
        || !s
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return err("INVALID_EXPOSURE_MANIFEST");
    }
    let v = f64::from_bits(u64::from_str_radix(s, 16).map_err(|_| "INVALID_EXPOSURE_MANIFEST")?);
    if !v.is_finite() || !(v as f32).is_finite() {
        return err("GPU_EXPOSURE_NONFINITE");
    }
    Ok(v)
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct Manifest {
    schema_version: u32,
    operation: String,
    token: u64,
    width: u32,
    height: u32,
    side: u32,
    rgb_to_xyz_bits: [[String; 3]; 3],
    adaptation_bits: [[String; 3]; 3],
    auto_exposure_bits: String,
    exposure_bits: String,
    table_sha256: String,
}
impl Manifest {
    fn descriptor(&self, table: Vec<f64>) -> R<ExposureDescriptor> {
        let mut rgb_to_xyz = [[0.; 3]; 3];
        let mut adaptation = [[0.; 3]; 3];
        for r in 0..3 {
            for c in 0..3 {
                rgb_to_xyz[r][c] = from_bits(&self.rgb_to_xyz_bits[r][c])?;
                adaptation[r][c] = from_bits(&self.adaptation_bits[r][c])?;
            }
        }
        Ok(ExposureDescriptor {
            size: self.side as usize,
            table,
            rgb_to_xyz,
            adaptation,
            auto_exposure: from_bits(&self.auto_exposure_bits)?,
            exposure: from_bits(&self.exposure_bits)?,
        })
    }
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExposurePlan {
    pub schema_version: u32,
    pub operation: String,
    pub token: u64,
    pub manifest_sha256: String,
    pub width: u32,
    pub height: u32,
    pub chunk_pixels: u32,
    pub chunk_count: u64,
    pub table_scalars: u64,
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub disk_bytes: u64,
}
fn make_plan(m: &Manifest, hash: String) -> R<ExposurePlan> {
    if m.schema_version != 1
        || m.operation != OPERATION
        || m.token == 0
        || m.token > i64::MAX as u64
        || m.width == 0
        || m.height == 0
        || m.width > 40000
        || m.height > 40000
        || !(2..=1024).contains(&m.side)
        || !valid_hash(&hash)
        || !valid_hash(&m.table_sha256)
    {
        return err("INVALID_EXPOSURE_MANIFEST");
    }
    let d = m.descriptor(vec![])?;
    if d.auto_exposure <= 0.
        || d.exposure <= 0.
        || d.auto_exposure as f32 <= 0.
        || d.exposure as f32 <= 0.
    {
        return err("GPU_EXPOSURE_NONFINITE");
    }
    let n = m.width as u64 * m.height as u64;
    let c = n.min(65536);
    let t = m.side as u64 * m.side as u64 * 3;
    // Four-byte sticky flag plus four extra bytes in the existing readback.
    let device = 4 * t + 36 * c + 4096 + 8;
    // f64 oracle, f32 packing and table-initialization staging overlap, plus bounded chunks.
    let host = 16 * t + 128 * c + 2 * MIB;
    let combined = device + host + 128 * MIB;
    if combined > MEMORY_CAP {
        return err("GPU_EXPOSURE_MEMORY_LIMIT");
    }
    Ok(ExposurePlan {
        schema_version: 1,
        operation: OPERATION.into(),
        token: m.token,
        manifest_sha256: hash,
        width: m.width,
        height: m.height,
        chunk_pixels: c as u32,
        chunk_count: n.div_ceil(c),
        table_scalars: t,
        device_bytes: device,
        host_bytes: host,
        driver_allowance_bytes: 64 * MIB,
        helper_allowance_bytes: 64 * MIB,
        combined_peak_bytes: combined,
        disk_bytes: 36 * n + 8 * t + 65536,
    })
}
fn manifest(d: &ExposureDescriptor, token: u64, width: u32, height: u32) -> R<Manifest> {
    d.validate()?;
    Ok(Manifest {
        schema_version: 1,
        operation: OPERATION.into(),
        token,
        width,
        height,
        side: d.size as u32,
        rgb_to_xyz_bits: d.rgb_to_xyz.map(|r| r.map(bits)),
        adaptation_bits: d.adaptation.map(|r| r.map(bits)),
        auto_exposure_bits: bits(d.auto_exposure),
        exposure_bits: bits(d.exposure),
        table_sha256: "0".repeat(64),
    })
}
pub fn estimated_disk_bytes(width: u32, height: u32, d: &ExposureDescriptor) -> R<u64> {
    Ok(make_plan(&manifest(d, 1, width, height)?, "0".repeat(64))?.disk_bytes)
}
pub(super) struct OwnedDir(pub PathBuf, pub bool);
impl Drop for OwnedDir {
    fn drop(&mut self) {
        if !self.1 {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
}
fn owner_name(dir: &Path, token: u64) -> R<()> {
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|n| n.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return err("INVALID_EXPOSURE_OWNER");
    }
    Ok(())
}
fn owner(dir: &Path, token: u64) -> R<()> {
    owner_name(dir, token)?;
    let m = io(fs::symlink_metadata(dir))?;
    if !m.is_dir() || m.file_type().is_symlink() || io(dir.canonicalize())? != dir {
        return err("INVALID_EXPOSURE_OWNER");
    }
    Ok(())
}
pub(super) fn regular(path: &Path, bytes: u64) -> R<()> {
    let m = io(fs::symlink_metadata(path))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() != bytes {
        return err("INVALID_EXPOSURE_FILE");
    }
    Ok(())
}
fn read_json<T: serde::de::DeserializeOwned>(path: &Path) -> R<(T, Vec<u8>)> {
    let m = io(fs::symlink_metadata(path))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() > 8192 {
        return err("INVALID_EXPOSURE_MANIFEST");
    }
    let mut bytes = Vec::new();
    io(io(File::open(path))?.take(8193).read_to_end(&mut bytes))?;
    if bytes.len() > 8192 {
        return err("INVALID_EXPOSURE_MANIFEST");
    }
    let value = serde_json::from_slice(&bytes).map_err(|_| "INVALID_EXPOSURE_MANIFEST")?;
    Ok((value, bytes))
}
pub(super) fn load(dir: &Path, token: u64) -> R<(Manifest, ExposurePlan)> {
    owner(dir, token)?;
    let mut names = Vec::new();
    for entry in io(fs::read_dir(dir))?.take(5) {
        let entry = io(entry)?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if name == "candidate" {
            let m = io(entry.file_type())?;
            if !m.is_dir() || m.is_symlink() {
                return err("INVALID_EXPOSURE_FILE");
            }
        } else {
            names.push(name);
        }
    }
    names.sort();
    if names != ["manifest.json", "source.rgb.f32le", "tables.f64le"] {
        return err("INVALID_EXPOSURE_FILES");
    }
    let (m, bytes): (Manifest, _) = read_json(&dir.join("manifest.json"))?;
    if m.token != token {
        return err("INVALID_EXPOSURE_OWNER");
    }
    let p = make_plan(&m, hex(&Sha256::digest(bytes)))?;
    regular(
        &dir.join("source.rgb.f32le"),
        p.width as u64 * p.height as u64 * 12,
    )?;
    regular(&dir.join("tables.f64le"), p.table_scalars * 8)?;
    Ok((m, p))
}
pub fn plan_stage(dir: &Path, token: u64) -> R<ExposurePlan> {
    load(dir, token).map(|(_, p)| p)
}
/// CPU global exposure factors must already match the original full-image metering.
/// Reserve `estimated_disk_bytes` even if the source hard link will usually succeed.
pub fn prepare_stage(
    source: &Path,
    dir: &Path,
    token: u64,
    width: u32,
    height: u32,
    d: &ExposureDescriptor,
    mut cancel: impl FnMut() -> bool,
) -> R<ExposurePlan> {
    check(&mut cancel)?;
    let mut m = manifest(d, token, width, height)?;
    make_plan(&m, "0".repeat(64))?;
    owner_name(dir, token)?;
    crate::gpu_stage_staging::create_stage_dir(dir)?;
    let mut guard = OwnedDir(dir.into(), false);
    let pixels = width as u64 * height as u64;
    crate::gpu_stage_staging::snapshot_rgb_source(source, dir, pixels, pixels * 12, &mut cancel)?;
    let mut out = std::io::BufWriter::with_capacity(
        65536,
        io(OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(dir.join("tables.f64le")))?,
    );
    let mut hash = Sha256::new();
    for block in d.table.chunks(8192) {
        check(&mut cancel)?;
        for v in block {
            let b = v.to_le_bytes();
            io(out.write_all(&b))?;
            hash.update(b);
        }
    }
    io(out.flush())?;
    io(out.get_ref().sync_all())?;
    m.table_sha256 = hex(&hash.finalize());
    let bytes = serde_json::to_vec(&m).map_err(|_| "INVALID_EXPOSURE_MANIFEST")?;
    let p = make_plan(&m, hex(&Sha256::digest(&bytes)))?;
    let mut f = io_at(
        Phase::ManifestCreate,
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(dir.join(".manifest.partial")),
    )?;
    io_at(Phase::ManifestWrite, f.write_all(&bytes))?;
    io_at(Phase::ManifestSync, f.sync_all())?;
    check(&mut cancel)?;
    io_at(
        Phase::ManifestRename,
        fs::rename(dir.join(".manifest.partial"), dir.join("manifest.json")),
    )?;
    guard.1 = true;
    Ok(p)
}
pub(super) fn read_descriptor(
    dir: &Path,
    m: &Manifest,
    p: &ExposurePlan,
    cancel: &mut impl FnMut() -> bool,
) -> R<ExposureDescriptor> {
    let mut input = io(File::open(dir.join("tables.f64le")))?;
    let mut table = Vec::with_capacity(p.table_scalars as usize);
    let mut hash = Sha256::new();
    let mut buf = [0u8; 65536];
    let mut left = p.table_scalars * 8;
    while left != 0 {
        check(cancel)?;
        let count = left.min(buf.len() as u64) as usize;
        io(input.read_exact(&mut buf[..count]))?;
        hash.update(&buf[..count]);
        table.extend(
            buf[..count]
                .chunks_exact(8)
                .map(|b| f64::from_le_bytes(b.try_into().unwrap())),
        );
        left -= count as u64;
    }
    if hex(&hash.finalize()) != m.table_sha256 {
        return err("GPU_EXPOSURE_HASH_MISMATCH");
    }
    let d = m.descriptor(table)?;
    d.validate()?;
    Ok(d)
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct Complete {
    pub schema_version: u32,
    pub operation: String,
    pub token: u64,
    pub manifest_sha256: String,
    pub width: u32,
    pub height: u32,
    pub output_sha256: [String; 3],
}
pub(super) fn hash_plane(
    path: &Path,
    samples: u64,
    cancel: &mut impl FnMut() -> bool,
) -> R<String> {
    regular(path, samples * 8)?;
    let mut input = io(File::open(path))?;
    let mut left = samples * 8;
    let mut buf = [0u8; 65536];
    let mut hash = Sha256::new();
    while left != 0 {
        check(cancel)?;
        let count = left.min(buf.len() as u64) as usize;
        io(input.read_exact(&mut buf[..count]))?;
        if buf[..count]
            .chunks_exact(8)
            .any(|b| !f64::from_le_bytes(b.try_into().unwrap()).is_finite())
        {
            return err("GPU_EXPOSURE_NONFINITE");
        }
        hash.update(&buf[..count]);
        left -= count as u64;
    }
    Ok(hex(&hash.finalize()))
}
/// Must be called only after confirming helper termination and input immutability.
pub fn validate_outputs(
    dir: &Path,
    token: u64,
    sha: &str,
    mut cancel: impl FnMut() -> bool,
) -> R<[PathBuf; 3]> {
    check(&mut cancel)?;
    let (_, p) = load(dir, token)?;
    if p.manifest_sha256 != sha {
        return err("STALE_EXPOSURE_PLAN");
    }
    let candidate = dir.join("candidate");
    let m = io(fs::symlink_metadata(&candidate))?;
    if !m.is_dir() || m.file_type().is_symlink() {
        return err("INVALID_EXPOSURE_OUTPUT");
    }
    let mut names = Vec::new();
    for entry in io(fs::read_dir(&candidate))?.take(5) {
        names.push(io(entry)?.file_name().to_string_lossy().into_owned());
    }
    names.sort();
    if names
        != [
            "complete.json",
            "plane-0.f64le",
            "plane-1.f64le",
            "plane-2.f64le",
        ]
    {
        return err("INCOMPLETE_GPU_EXPOSURE");
    }
    let (c, _): (Complete, _) = read_json(&candidate.join("complete.json"))?;
    if c.schema_version != 1
        || c.operation != OPERATION
        || c.token != token
        || c.manifest_sha256 != sha
        || c.width != p.width
        || c.height != p.height
    {
        return err("STALE_EXPOSURE_OUTPUT");
    }
    let paths = std::array::from_fn(|ch| candidate.join(format!("plane-{ch}.f64le")));
    for ch in 0..3 {
        if !valid_hash(&c.output_sha256[ch])
            || hash_plane(&paths[ch], p.width as u64 * p.height as u64, &mut cancel)?
                != c.output_sha256[ch]
        {
            return err("GPU_EXPOSURE_HASH_MISMATCH");
        }
    }
    check(&mut cancel)?;
    Ok(paths)
}
#[cfg(feature = "gpu-diffusion-research")]
pub fn run_stage(
    dir: &Path,
    token: u64,
    budget: u64,
    sha: &str,
    mut cancel: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    check(&mut cancel)?;
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != sha {
        return err("STALE_EXPOSURE_PLAN");
    }
    if budget > MEMORY_CAP || budget < p.combined_peak_bytes {
        return err("INSUFFICIENT_GPU_EXPOSURE_MEMORY");
    }
    super::gpu::run(dir, &m, &p, &mut cancel, false)
}
#[cfg(test)]
mod tests;

/// Host integration oracle only. Never reports GPU execution or mobile performance.
#[cfg(test)]
pub fn run_stage_cpu_mirror_for_test(
    dir: &Path,
    token: u64,
    sha: &str,
    mut cancel: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    check(&mut cancel)?;
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != sha {
        return err("STALE_EXPOSURE_PLAN");
    }
    let d = read_descriptor(dir, &m, &p, &mut cancel)?;
    let samples = super::streaming::write_candidate(dir, &p, &d, &mut cancel, |bytes, _, c| {
        check(c)?;
        Ok(bytes
            .chunks_exact(12)
            .flat_map(|px| {
                d.emulate_f32(std::array::from_fn(|ch| {
                    f32::from_le_bytes(px[ch * 4..ch * 4 + 4].try_into().unwrap())
                }))
            })
            .collect())
    })?;
    Ok(
        serde_json::json!({"ok":true,"gpuUsed":false,"operation":OPERATION,"hostArithmeticMirror":true,
        "chunkCount":p.chunk_count,"sampleCount":samples.count,"sampleMaxAbs":samples.max_abs,"sampleRms":samples.rms()}),
    )
}
