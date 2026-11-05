//! Owned immutable scanner-tail packets and complete candidate publication.
//! Caller must confirm isolated helper death before adoption or whole-stage CPU fallback.
use super::*;
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase, PhaseGuard};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
const OP: &str = "fullScanTail";
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema_version: u32,
    operation: String,
    token: u64,
    tail: TailPlan,
    external_glare: bool,
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
    pub sample_count: u64,
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
    output_sha256: String,
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
fn io<T>(x: std::io::Result<T>) -> R<T> {
    x.map_err(|_| "GPU_TAIL_IO".into())
}
fn hex(b: &[u8]) -> String {
    b.iter().map(|v| format!("{v:02x}")).collect()
}
fn hash_valid(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
pub(super) fn regular(p: &Path, len: u64) -> R<()> {
    let m = io(fs::symlink_metadata(p))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() != len {
        Err("INVALID_TAIL_FILE".into())
    } else {
        Ok(())
    }
}
fn owner(dir: &Path, token: u64, exists: bool) -> R<()> {
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|s| s.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return Err("INVALID_TAIL_OWNER".into());
    }
    if exists {
        let m = io(fs::symlink_metadata(dir))?;
        if !m.is_dir() || m.file_type().is_symlink() || io(dir.canonicalize())? != dir {
            return Err("INVALID_TAIL_OWNER".into());
        }
    }
    Ok(())
}
fn make_plan(m: &Manifest, hash: String) -> R<StagePlan> {
    if m.schema_version != 1
        || m.operation != OP
        || m.token == 0
        || m.token > i64::MAX as u64
        || !hash_valid(&hash)
    {
        return Err("INVALID_TAIL_MANIFEST".into());
    }
    m.tail.validate()?;
    if m.external_glare && m.tail.options.glare.is_none() {
        return Err("INVALID_TAIL_GLARE".into());
    }
    let p = &m.tail;
    Ok(StagePlan {
        schema_version: 1,
        operation: OP.into(),
        token: m.token,
        manifest_sha256: hash,
        width: p.width,
        height: p.height,
        pass_count: p.pass_count(),
        sample_count: p.sample_count(),
        device_bytes: p.device_bytes,
        host_bytes: p.host_bytes,
        driver_allowance_bytes: p.driver_allowance_bytes,
        helper_allowance_bytes: p.helper_allowance_bytes,
        combined_peak_bytes: p.combined_peak_bytes,
        disk_bytes: p.disk_bytes,
    })
}
fn read_json<T: serde::de::DeserializeOwned>(path: &Path, max: u64) -> R<(T, Vec<u8>)> {
    let m = io(fs::symlink_metadata(path))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() > max {
        return Err("INVALID_TAIL_MANIFEST".into());
    }
    let bytes = io(fs::read(path))?;
    Ok((
        serde_json::from_slice(&bytes).map_err(|_| "INVALID_TAIL_MANIFEST")?,
        bytes,
    ))
}
fn load(dir: &Path, token: u64) -> R<(Manifest, StagePlan)> {
    trace::phase_result(GpuPhase::Prepare, || {
        owner(dir, token, true)?;
        let (m, bytes): (Manifest, Vec<u8>) = read_json(&dir.join("manifest.json"), 2 * MIB)?;
        if m.token != token {
            return Err("INVALID_TAIL_IDENTITY".into());
        }
        let plan = make_plan(&m, hex(&Sha256::digest(&bytes)))?;
        regular(
            &dir.join("source.logxyz.f64le"),
            m.tail.width as u64 * m.tail.height as u64 * 24,
        )?;
        if m.external_glare {
            regular(
                &dir.join("source.glare.f64le"),
                m.tail.width as u64 * m.tail.height as u64 * 8,
            )?;
        }
        Ok((m, plan))
    })
}
pub fn estimated_disk_bytes(
    w: u32,
    h: u32,
    d: &GpuScanTailDescriptor<'_>,
    o: TailOptions,
) -> R<u64> {
    Ok(TailPlan::new(d, w, h, o)?.disk_bytes)
}
pub fn prepare_stage(
    source: &Path,
    raw_glare: Option<&Path>,
    dir: &Path,
    token: u64,
    width: u32,
    height: u32,
    d: &GpuScanTailDescriptor<'_>,
    options: TailOptions,
    mut c: impl FnMut() -> bool,
) -> R<StagePlan> {
    use crate::gpu_stage_staging::{self as staging, F64SourceKind, Phase, io_at};
    check(&mut c)?;
    owner(dir, token, false)?;
    let p = TailPlan::new(d, width, height, options)?;
    if raw_glare.is_some() && p.options.glare.is_none() {
        return Err("INVALID_TAIL_GLARE".into());
    }
    let m = Manifest {
        schema_version: 1,
        operation: OP.into(),
        token,
        tail: p,
        external_glare: raw_glare.is_some(),
    };
    let bytes = serde_json::to_vec(&m).map_err(|_| "INVALID_TAIL_MANIFEST")?;
    if bytes.len() as u64 > 2 * MIB {
        return Err("GPU_TAIL_MEMORY_LIMIT".into());
    }
    let plan = make_plan(&m, hex(&Sha256::digest(&bytes)))?;
    staging::create_stage_dir(dir)?;
    let mut guard = Owned(dir.into(), false);
    let n = width as u64 * height as u64;
    staging::snapshot_f64_source(source, dir, n, F64SourceKind::LogXyz, n * 24, &mut c)?;
    if let Some(g) = raw_glare {
        staging::snapshot_f64_source(g, dir, n, F64SourceKind::Glare, n * 8, &mut c)?;
    }
    let mut f = io_at(
        Phase::ManifestCreate,
        OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(dir.join(".manifest.partial")),
    )?;
    io_at(Phase::ManifestWrite, f.write_all(&bytes))?;
    io_at(Phase::ManifestSync, f.sync_all())?;
    check(&mut c)?;
    io_at(
        Phase::ManifestRename,
        fs::rename(dir.join(".manifest.partial"), dir.join("manifest.json")),
    )?;
    guard.1 = true;
    Ok(plan)
}
pub fn plan_stage(dir: &Path, token: u64) -> R<StagePlan> {
    Ok(load(dir, token)?.1)
}
fn hash_output(path: &Path, n: u64, c: &mut impl FnMut() -> bool) -> R<String> {
    regular(path, n * 12)?;
    let mut f = io(File::open(path))?;
    let mut left = n * 12;
    let mut b = vec![0u8; 65536];
    let mut hash = Sha256::new();
    while left > 0 {
        check(c)?;
        let count = left.min(b.len() as u64) as usize;
        io(f.read_exact(&mut b[..count]))?;
        if b[..count]
            .chunks_exact(4)
            .any(|x| !f32::from_le_bytes(x.try_into().unwrap()).is_finite())
        {
            return Err("NONFINITE_TAIL_OUTPUT".into());
        }
        hash.update(&b[..count]);
        left -= count as u64;
    }
    Ok(hex(&hash.finalize()))
}
pub fn validate_output(
    dir: &Path,
    token: u64,
    sha: &str,
    mut c: impl FnMut() -> bool,
) -> R<PathBuf> {
    check(&mut c)?;
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != sha {
        return Err("INVALID_TAIL_IDENTITY".into());
    }
    let candidate = dir.join("candidate");
    let meta = io(fs::symlink_metadata(&candidate))?;
    if !meta.is_dir() || meta.file_type().is_symlink() || io(candidate.canonicalize())? != candidate
    {
        return Err("INVALID_TAIL_OUTPUT".into());
    }
    let (done, _): (Complete, Vec<u8>) = read_json(&candidate.join("complete.json"), 65536)?;
    if done.schema_version != 1
        || done.operation != OP
        || done.token != token
        || done.manifest_sha256 != sha
        || done.width != m.tail.width
        || done.height != m.tail.height
        || !hash_valid(&done.output_sha256)
    {
        return Err("INVALID_TAIL_OUTPUT".into());
    }
    let output = candidate.join("candidate.rgb.f32le");
    if hash_output(&output, p.width as u64 * p.height as u64, &mut c)? != done.output_sha256 {
        return Err("INVALID_TAIL_OUTPUT".into());
    }
    Ok(output)
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
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != sha {
        return Err("INVALID_TAIL_IDENTITY".into());
    }
    if budget < p.combined_peak_bytes {
        return Err("INSUFFICIENT_GPU_TAIL_MEMORY".into());
    }
    m.tail.numerical_preflight(&mut c)?;
    let candidate = dir.join("candidate");
    io(fs::create_dir(&candidate))?;
    let mut guard = Owned(candidate.clone(), false);
    let source = dir.join("source.logxyz.f64le");
    let glare = m.external_glare.then(|| dir.join("source.glare.f64le"));
    let output = candidate.join("candidate.rgb.f32le");
    let mut report: serde_json::Value = if mirror {
        #[cfg(test)]
        {
            host_mirror(&m.tail, &source, glare.as_deref(), &output, &mut c)?
        }
        #[cfg(not(test))]
        {
            return Err("GPU_TAIL_UNSUPPORTED".into());
        }
    } else {
        #[cfg(feature = "gpu-diffusion-research")]
        {
            gpu::run_files(&m.tail, &source, glare.as_deref(), &output, &mut c)?
        }
        #[cfg(not(feature = "gpu-diffusion-research"))]
        {
            return Err("GPU_TAIL_UNSUPPORTED".into());
        }
    };
    let commit_phase = PhaseGuard::begin(GpuPhase::Commit);
    let done = Complete {
        schema_version: 1,
        operation: OP.into(),
        token,
        manifest_sha256: sha.into(),
        width: p.width,
        height: p.height,
        output_sha256: hash_output(&output, p.width as u64 * p.height as u64, &mut c)?,
    };
    let bytes = serde_json::to_vec(&done).map_err(|_| "INVALID_TAIL_OUTPUT")?;
    let mut f = io(OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(candidate.join(".complete.partial")))?;
    io(f.write_all(&bytes))?;
    io(f.sync_all())?;
    check(&mut c)?;
    io(fs::rename(
        candidate.join(".complete.partial"),
        candidate.join("complete.json"),
    ))?;
    commit_phase.finish();
    let object = report.as_object_mut().ok_or("GPU_TAIL_REPORT")?;
    object.insert("ok".into(), true.into());
    object.insert("schemaVersion".into(), 1.into());
    object.insert("operation".into(), OP.into());
    object.insert("token".into(), token.into());
    object.insert("manifestSha256".into(), sha.into());
    object.insert("width".into(), p.width.into());
    object.insert("height".into(), p.height.into());
    object.insert("channels".into(), 3.into());
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
pub(crate) fn run_stage_cpu_mirror_for_test(
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
    p: &TailPlan,
    source: &Path,
    glare: Option<&Path>,
    output: &Path,
    c: &mut impl FnMut() -> bool,
) -> R<serde_json::Value> {
    let n = p.width as usize * p.height as usize;
    if n > 1048576 {
        return Err("TEST_MIRROR_MEMORY_LIMIT".into());
    }
    let read = |path: &Path| -> R<Vec<f32>> {
        io(fs::read(path))?
            .chunks_exact(8)
            .map(|b| {
                let x = f64::from_le_bytes(b.try_into().unwrap());
                if finite(x) {
                    Ok(x as f32)
                } else {
                    Err("NONFINITE_TAIL_INPUT".into())
                }
            })
            .collect()
    };
    let logs = read(source)?
        .chunks_exact(3)
        .map(|x| [x[0], x[1], x[2]])
        .collect::<Vec<_>>();
    let glare = if let Some(path) = glare {
        Some(read(path)?)
    } else if let Some(g) = &p.options.glare {
        let mut sampler = spektrafilm_model::glare::GlareSamples::new(g.percent, g.roughness, 42)
            .map_err(|e| e.to_string())?;
        Some((0..n).map(|_| sampler.sample() as f32).collect())
    } else {
        None
    };
    let result = image_mirror(p, &logs, glare.as_deref(), c)?;
    let bytes: Vec<u8> = result
        .into_iter()
        .flatten()
        .flat_map(|x| x.to_le_bytes())
        .collect();
    let mut f = io(OpenOptions::new().create_new(true).write(true).open(output))?;
    io(f.write_all(&bytes))?;
    io(f.sync_all())?;
    Ok(
        serde_json::json!({"gpuUsed":false,"backend":"host f32 scanner tail mirror; not GPU timing","fullImage":true,"glareGenerationGpu":false}),
    )
}
