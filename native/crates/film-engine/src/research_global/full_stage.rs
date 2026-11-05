//! Immutable, token-bound global-science packets. Whole-stage candidates are
//! adopted only after the existing helper lifecycle proves the worker stopped.
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase, PhaseGuard};
use super::*;
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema_version: u32,
    operation: String,
    token: u64,
    global: GlobalPlan,
    meter_source_sha256: Option<String>,
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
    pub sample_width: u32,
    pub sample_height: u32,
    pub grid_version: u32,
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub disk_bytes: u64,
}
#[derive(Debug)]
pub enum GlobalOutput {
    Meter { ev: f32, scale: f32 },
    Planes([PathBuf; 3]),
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
    output_sha256: Vec<String>,
}
struct Owned(PathBuf, bool);
impl Drop for Owned {
    fn drop(&mut self) {
        if !self.1 {
            trace::phase_value(GpuPhase::Cleanup, || {
            let _ = fs::remove_dir_all(&self.0);

            });
        }
    }
}
pub(super) fn io<T>(x: std::io::Result<T>) -> R<T> {
    x.map_err(|_| "GPU_GLOBAL_IO".into())
}
fn hex(x: &[u8]) -> String {
    x.iter().map(|v| format!("{v:02x}")).collect()
}
fn hash_valid(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
pub(super) fn regular(p: &Path, bytes: u64) -> R<()> {
    let m = io(fs::symlink_metadata(p))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() != bytes {
        Err("INVALID_GLOBAL_FILE".into())
    } else {
        Ok(())
    }
}
fn owner(dir: &Path, token: u64, exists: bool) -> R<()> {
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|x| x.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return Err("INVALID_GLOBAL_OWNER".into());
    }
    if exists {
        let m = io(fs::symlink_metadata(dir))?;
        if !m.is_dir() || m.file_type().is_symlink() || io(dir.canonicalize())? != dir {
            return Err("INVALID_GLOBAL_OWNER".into());
        }
    }
    Ok(())
}
fn make_plan(m: &Manifest, sha: String) -> R<StagePlan> {
    if m.schema_version != 1
        || m.operation != m.global.kind.operation()
        || m.token == 0
        || m.token > i64::MAX as u64
        || !hash_valid(&sha)
    {
        return Err("INVALID_GLOBAL_MANIFEST".into());
    }
    let p = &m.global;
    p.validate()?;
    if matches!(p.kind, GlobalKind::Meter { .. }) {
        if !m
            .meter_source_sha256
            .as_ref()
            .is_some_and(|s| hash_valid(s))
        {
            return Err("INVALID_GLOBAL_MANIFEST".into());
        }
    } else if m.meter_source_sha256.is_some() {
        return Err("INVALID_GLOBAL_MANIFEST".into());
    }
    Ok(StagePlan {
        schema_version: 1,
        operation: m.operation.clone(),
        token: m.token,
        manifest_sha256: sha,
        width: p.width,
        height: p.height,
        sample_width: p.sample_width,
        sample_height: p.sample_height,
        grid_version: p.grid_version,
        device_bytes: p.device_bytes,
        host_bytes: p.host_bytes,
        driver_allowance_bytes: p.driver_allowance_bytes,
        helper_allowance_bytes: p.helper_allowance_bytes,
        combined_peak_bytes: p.combined_peak_bytes,
        disk_bytes: p.disk_bytes,
    })
}
fn read_json<T: serde::de::DeserializeOwned>(p: &Path) -> R<(T, Vec<u8>)> {
    let m = io(fs::symlink_metadata(p))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() > 65536 {
        return Err("INVALID_GLOBAL_MANIFEST".into());
    }
    let bytes = io(fs::read(p))?;
    Ok((
        serde_json::from_slice(&bytes).map_err(|_| "INVALID_GLOBAL_MANIFEST")?,
        bytes,
    ))
}
fn load(dir: &Path, token: u64, c: &mut impl FnMut() -> bool) -> R<(Manifest, StagePlan)> {
        trace::phase_result(GpuPhase::Prepare, || {
    check(c)?;
    owner(dir, token, true)?;
    let (m, b): (Manifest, Vec<u8>) = read_json(&dir.join("manifest.json"))?;
    if m.token != token {
        return Err("INVALID_GLOBAL_IDENTITY".into());
    }
    let plan = make_plan(&m, hex(&Sha256::digest(&b)))?;
    let n = plan.width as u64 * plan.height as u64;
    if matches!(m.global.kind, GlobalKind::Meter { .. }) {
        let bytes = pixel_sample_bytes(&m.global);
        if hash_output(&dir.join("source.meter.f32le"), bytes, 4, c)?
            != *m.meter_source_sha256.as_ref().unwrap()
        {
            return Err("INVALID_GLOBAL_SOURCE_HASH".into());
        }
    } else {
        for ch in 0..3 {
            regular(&dir.join(format!("source-{ch}.f64le")), n * 8)?;
        }
    }
    Ok((m, plan))

        })
    }
fn pixel_sample_bytes(p: &GlobalPlan) -> u64 {
    p.sample_width as u64 * p.sample_height as u64 * 28
}
fn prepare(
    dir: &Path,
    token: u64,
    p: &GlobalPlan,
    c: &mut impl FnMut() -> bool,
    snapshot: impl FnOnce(&mut dyn FnMut() -> bool) -> R<Option<String>>,
) -> R<StagePlan> {
    use crate::gpu_stage_staging::{self as staging, io_at, Phase};
    check(c)?;
    p.validate()?;
    owner(dir, token, false)?;
    staging::create_stage_dir(dir)?;
    let mut guard = Owned(dir.into(), false);
    let source_hash = snapshot(c)?;
    let m = Manifest {
        schema_version: 1,
        operation: p.kind.operation().into(),
        token,
        global: p.clone(),
        meter_source_sha256: source_hash,
    };
    let bytes = serde_json::to_vec(&m).map_err(|_| "INVALID_GLOBAL_MANIFEST")?;
    let plan = make_plan(&m, hex(&Sha256::digest(&bytes)))?;
    let mut f = io_at(
        Phase::ManifestCreate,
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(dir.join(".manifest.partial")),
    )?;
    io_at(Phase::ManifestWrite, f.write_all(&bytes))?;
    io_at(Phase::ManifestSync, f.sync_all())?;
    check(c)?;
    io_at(
        Phase::ManifestRename,
        fs::rename(dir.join(".manifest.partial"), dir.join("manifest.json")),
    )?;
    guard.1 = true;
    Ok(plan)
}
pub fn prepare_meter_stage(
    source: &Path,
    dir: &Path,
    token: u64,
    p: &GlobalPlan,
    mut c: impl FnMut() -> bool,
) -> R<StagePlan> {
    if !matches!(p.kind, GlobalKind::Meter { .. }) {
        return Err("INVALID_GLOBAL_KIND".into());
    }
    prepare(dir, token, p, &mut c, |mut c| {
        use crate::gpu_stage_staging::{io_at, Phase};
        let (_, _, pack) = read_meter_pack(source, p, &mut c)?;
        let path = dir.join("source.meter.f32le");
        let mut f = io_at(
            Phase::CopyCreate,
            OpenOptions::new().write(true).create_new(true).open(&path),
        )?;
        let mut hash = Sha256::new();
        for chunk in pack.chunks(16384) {
            check(&mut c)?;
            let bytes: Vec<u8> = chunk.iter().flat_map(|v| v.to_le_bytes()).collect();
            hash.update(&bytes);
            io_at(Phase::CopyWrite, f.write_all(&bytes))?;
        }
        io_at(Phase::CopySync, f.sync_all())?;
        drop(f);
        let expected = hex(&hash.finalize());
        if hash_output(&path, pixel_sample_bytes(p), 4, &mut c)? != expected {
            return Err("GPU_PREPARE_COPY_HASH".into());
        }
        Ok(Some(expected))
    })
}
pub fn prepare_plane_stage(
    sources: [&Path; 3],
    dir: &Path,
    token: u64,
    p: &GlobalPlan,
    mut c: impl FnMut() -> bool,
) -> R<StagePlan> {
    if matches!(p.kind, GlobalKind::Meter { .. }) {
        return Err("INVALID_GLOBAL_KIND".into());
    }
    let n = p.width as u64 * p.height as u64;
    prepare(dir, token, p, &mut c, |c| {
        crate::gpu_stage_staging::snapshot_sources(sources, dir, n, n * 24, c).map(|_| None)
    })
}
pub fn plan_stage(dir: &Path, token: u64) -> R<StagePlan> {
    Ok(load(dir, token, &mut || false)?.1)
}
fn output_specs(p: &GlobalPlan) -> Vec<(String, u64, usize)> {
    if matches!(p.kind, GlobalKind::Meter { .. }) {
        vec![("meter.f32le".into(), 8, 4)]
    } else {
        (0..3)
            .map(|ch| {
                (
                    format!("plane-{ch}.f64le"),
                    p.width as u64 * p.height as u64 * 8,
                    8,
                )
            })
            .collect()
    }
}
fn hash_output(path: &Path, bytes: u64, scalar: usize, c: &mut impl FnMut() -> bool) -> R<String> {
    regular(path, bytes)?;
    let mut f = io(File::open(path))?;
    let mut b = vec![0u8; 65536];
    let mut left = bytes;
    let mut hash = Sha256::new();
    while left > 0 {
        check(c)?;
        let n = left.min(b.len() as u64) as usize;
        io(f.read_exact(&mut b[..n]))?;
        if b[..n].chunks_exact(scalar).any(|x| {
            if scalar == 8 {
                !finite(f64::from_le_bytes(x.try_into().unwrap()))
            } else {
                !f32::from_le_bytes(x.try_into().unwrap()).is_finite()
            }
        }) {
            return Err("NONFINITE_GLOBAL_OUTPUT".into());
        }
        hash.update(&b[..n]);
        left -= n as u64;
    }
    Ok(hex(&hash.finalize()))
}
pub fn validate_outputs(
    dir: &Path,
    token: u64,
    sha: &str,
    mut c: impl FnMut() -> bool,
) -> R<GlobalOutput> {
    check(&mut c)?;
    let (m, p) = load(dir, token, &mut c)?;
    if p.manifest_sha256 != sha {
        return Err("INVALID_GLOBAL_IDENTITY".into());
    }
    let candidate = dir.join("candidate");
    let meta = io(fs::symlink_metadata(&candidate))?;
    if !meta.is_dir() || meta.file_type().is_symlink() || io(candidate.canonicalize())? != candidate
    {
        return Err("INVALID_GLOBAL_OUTPUT".into());
    }
    let (done, _): (Complete, Vec<u8>) = read_json(&candidate.join("complete.json"))?;
    let specs = output_specs(&m.global);
    if done.schema_version != 1
        || done.operation != p.operation
        || done.token != token
        || done.manifest_sha256 != sha
        || done.width != p.width
        || done.height != p.height
        || done.output_sha256.len() != specs.len()
    {
        return Err("INVALID_GLOBAL_OUTPUT".into());
    }
    for ((name, bytes, scalar), hash) in specs.iter().zip(&done.output_sha256) {
        if !hash_valid(hash)
            || hash_output(&candidate.join(name), *bytes, *scalar, &mut c)? != *hash
        {
            return Err("INVALID_GLOBAL_OUTPUT".into());
        }
    }
    if matches!(m.global.kind, GlobalKind::Meter { .. }) {
        let b = io(fs::read(candidate.join("meter.f32le")))?;
        let ev = f32::from_le_bytes(b[..4].try_into().unwrap());
        let scale = f32::from_le_bytes(b[4..].try_into().unwrap());
        if !ev.is_finite() || !scale.is_finite() || scale <= 0. {
            return Err("NONFINITE_GLOBAL_OUTPUT".into());
        }
        Ok(GlobalOutput::Meter { ev, scale })
    } else {
        Ok(GlobalOutput::Planes(std::array::from_fn(|ch| {
            candidate.join(format!("plane-{ch}.f64le"))
        })))
    }
}
fn run_inner(
    dir: &Path,
    token: u64,
    budget: u64,
    sha: &str,
    mut c: impl FnMut() -> bool,
    mirror: bool,
) -> R<serde_json::Value> {
    check(&mut c)?;
    let (m, p) = load(dir, token, &mut c)?;
    if p.manifest_sha256 != sha {
        return Err("INVALID_GLOBAL_IDENTITY".into());
    }
    if budget < p.combined_peak_bytes {
        return Err("INSUFFICIENT_GPU_GLOBAL_MEMORY".into());
    }
    let candidate = dir.join("candidate");
    io(fs::create_dir(&candidate))?;
    let mut guard = Owned(candidate.clone(), false);
    let mut report: serde_json::Value = if mirror {
        #[cfg(test)]
        {
            host_mirror(&m.global, dir, &candidate, &mut c)?
        }
        #[cfg(not(test))]
        {
            return Err("GPU_GLOBAL_UNSUPPORTED".into());
        }
    } else {
        #[cfg(feature = "gpu-diffusion-research")]
        {
            gpu::run_files(&m.global, dir, &candidate, &mut c)?
        }
        #[cfg(not(feature = "gpu-diffusion-research"))]
        {
            return Err("GPU_GLOBAL_UNSUPPORTED".into());
        }
    };
    let commit_phase=PhaseGuard::begin(GpuPhase::Commit);
    let mut hashes = Vec::new();
    for (name, bytes, scalar) in output_specs(&m.global) {
        hashes.push(hash_output(&candidate.join(name), bytes, scalar, &mut c)?);
    }
    let done = Complete {
        schema_version: 1,
        operation: p.operation.clone(),
        token,
        manifest_sha256: sha.into(),
        width: p.width,
        height: p.height,
        output_sha256: hashes,
    };
    let bytes = serde_json::to_vec(&done).map_err(|_| "INVALID_GLOBAL_OUTPUT")?;
    let mut f = io(OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(candidate.join(".complete.partial")))?;
    io(f.write_all(&bytes))?;
    io(f.sync_all())?;
    check(&mut c)?;
    io(fs::rename(
        candidate.join(".complete.partial"),
        candidate.join("complete.json"),
    ))?;
    commit_phase.finish();
    let o = report.as_object_mut().ok_or("GPU_GLOBAL_REPORT")?;
    o.insert("ok".into(), true.into());
    o.insert("schemaVersion".into(), 1.into());
    o.insert("operation".into(), p.operation.into());
    o.insert("token".into(), token.into());
    o.insert("manifestSha256".into(), sha.into());
    o.insert("width".into(), p.width.into());
    o.insert("height".into(), p.height.into());
    guard.1 = true;
    Ok(report)
}
pub fn run_stage(
    dir: &Path,
    token: u64,
    budget: u64,
    sha: &str,
    c: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    run_inner(dir, token, budget, sha, c, false)
}
#[cfg(test)]
pub fn run_stage_cpu_mirror_for_test(
    dir: &Path,
    token: u64,
    sha: &str,
    c: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    let p = plan_stage(dir, token)?;
    run_inner(dir, token, p.combined_peak_bytes, sha, c, true)
}
#[cfg(test)]
fn host_mirror(
    p: &GlobalPlan,
    source: &Path,
    output: &Path,
    c: &mut impl FnMut() -> bool,
) -> R<serde_json::Value> {
    let mut summary = serde_json::json!({"gpuUsed":false,"testOnly":true,"hostArithmeticMirror":true,"backend":"host f32 global arithmetic mirror; not GPU timing","fullImage":true});
    if matches!(p.kind, GlobalKind::Meter { .. }) {
        let (w, h, pack) = read_meter_packet(&source.join("source.meter.f32le"), p, c)?;
        let (ev, scale) = meter_mirror(p, &pack, w, h, c)?;
        let mut f = io(OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(output.join("meter.f32le")))?;
        io(f.write_all(&ev.to_le_bytes()))?;
        io(f.write_all(&scale.to_le_bytes()))?;
        io(f.sync_all())?;
        summary["meteredEv"] = ev.into();
        summary["autoExposureScale"] = scale.into();
    } else {
        let n = p.width as usize * p.height as usize;
        if n > 1048576 {
            return Err("TEST_MIRROR_MEMORY_LIMIT".into());
        }
        let mut input: [Vec<f64>; 3] = std::array::from_fn(|_| Vec::new());
        for ch in 0..3 {
            check(c)?;
            input[ch] = io(fs::read(source.join(format!("source-{ch}.f64le"))))?
                .chunks_exact(8)
                .map(|b| f64::from_le_bytes(b.try_into().unwrap()))
                .collect();
        }
        let result = planes_mirror(p, &input, c)?;
        for ch in 0..3 {
            check(c)?;
            let mut f = io(OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(output.join(format!("plane-{ch}.f64le"))))?;
            for chunk in result[ch].chunks(CHUNK) {
                check(c)?;
                let b: Vec<_> = chunk.iter().flat_map(|v| v.to_le_bytes()).collect();
                io(f.write_all(&b))?;
            }
            io(f.sync_all())?;
        }
    }
    Ok(summary)
}
