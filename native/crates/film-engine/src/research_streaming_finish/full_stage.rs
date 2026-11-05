//! Owned immutable scanner-tail packets and complete candidate publication.
//! Caller must confirm isolated helper death before adoption or whole-stage CPU fallback.
use super::*;
use crate::research_spatial::{MIB, check};
use crate::research_finish_tail::TailOptions;
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase, PhaseGuard};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
pub const OP: &str = "fullScanTailStreamV1";
pub const SOURCE_ADMITTED: bool = false;
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema_version: u32,
    layout: String,
    operation: String,
    token: u64,
    source_sha256: String,
    glare_sha256: Option<String>,
    tail: StreamFinishPlan,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StagePlan {
    pub schema_version: u32,
    pub layout: String,
    pub external_glare: bool,
    pub glare_active: bool,
    pub table_scalars: u64,
    pub mode: String,
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
    backend: String,
    ok: bool,
    layout: String,
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
    if m.schema_version != 1 || m.layout != super::LAYOUT
        || m.operation != OP
        || m.token == 0
        || m.token > i64::MAX as u64
        || !hash_valid(&hash) || !hash_valid(&m.source_sha256)
        || m.glare_sha256.as_ref().is_some_and(|h| !hash_valid(h))
        || m.glare_sha256.is_some()!=m.tail.external_glare()
    {
        return Err("INVALID_TAIL_MANIFEST".into());
    }
    m.tail.validate()?;
    if m.tail.prefix_table_scalars().is_some() { return Err("INVALID_TAIL_MANIFEST".into()); }
    let p = &m.tail;
    let memory = p.memory()?;
    Ok(StagePlan {
        schema_version: 1, layout: super::LAYOUT.into(), operation: OP.into(), token: m.token,
        manifest_sha256: hash, width: p.width(), height: p.height(),
        pass_count: p.parameters().pass_count(), sample_count: p.sample_count(),
        external_glare: p.external_glare(), glare_active: p.parameters().options.glare.is_some(), table_scalars: 0, mode: "standaloneScan".into(),
        device_bytes: memory.device_bytes, host_bytes: memory.host_bytes,
        driver_allowance_bytes: memory.driver_allowance_bytes, helper_allowance_bytes: memory.helper_allowance_bytes,
        combined_peak_bytes: memory.combined_peak_bytes, disk_bytes: memory.disk_bytes,
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
            m.tail.width() as u64 * m.tail.height() as u64 * 24,
        )?;
        if m.tail.external_glare() {
            regular(
                &dir.join("source.glare.f64le"),
                m.tail.width() as u64 * m.tail.height() as u64 * 8,
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
    external_glare: bool,
) -> R<u64> {
    Ok(StreamFinishPlan::new(d, w, h, o, None, external_glare)?.memory()?.disk_bytes)
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
    let p = StreamFinishPlan::new(d, width, height, options, None, raw_glare.is_some())?;
    if raw_glare.is_some() && p.parameters().options.glare.is_none() {
        return Err("INVALID_TAIL_GLARE".into());
    }
    staging::create_stage_dir(dir)?;
    let mut guard = Owned(dir.into(), false);
    let n = width as u64 * height as u64;
    staging::snapshot_f64_source(source, dir, n, F64SourceKind::LogXyz, n * 24, &mut c)?;
    if let Some(g) = raw_glare { staging::snapshot_f64_source(g, dir, n, F64SourceKind::Glare, n * 8, &mut c)?; }
    let source_sha256=crate::research_streaming_route::file_hash(&dir.join("source.logxyz.f64le"),n*24,&mut c)?;
    let glare_sha256=if raw_glare.is_some(){Some(crate::research_streaming_route::file_hash(&dir.join("source.glare.f64le"),n*8,&mut c)?)}else{None};
    let m = Manifest { schema_version:1,layout:super::LAYOUT.into(),operation:OP.into(),token,source_sha256,glare_sha256,tail:p };
    let bytes = serde_json::to_vec(&m).map_err(|_| "INVALID_TAIL_MANIFEST")?;
    if bytes.len() as u64 > 2 * MIB { return Err("GPU_TAIL_MEMORY_LIMIT".into()); }
    let plan=make_plan(&m,hex(&Sha256::digest(&bytes)))?;
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
    verify_sources(dir,&m,&p,&mut c)?;
    let candidate = dir.join("candidate");
    let meta = io(fs::symlink_metadata(&candidate))?;
    if !meta.is_dir() || meta.file_type().is_symlink() || io(candidate.canonicalize())? != candidate
    {
        return Err("INVALID_TAIL_OUTPUT".into());
    }
    let (done, _): (Complete, Vec<u8>) = read_json(&candidate.join("complete.json"), 65536)?;
    if done.schema_version != 1 || !done.ok || done.backend != "gpu_scan_tail_stream_f32_v1" || done.layout != super::LAYOUT
        || done.operation != OP
        || done.token != token
        || done.manifest_sha256 != sha
        || done.width != m.tail.width()
        || done.height != m.tail.height()
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
fn verify_sources(dir:&Path,m:&Manifest,p:&StagePlan,c:&mut impl FnMut()->bool)->R<()> {
    let n=p.width as u64*p.height as u64;
    if crate::research_streaming_route::file_hash(&dir.join("source.logxyz.f64le"),n*24,c)?!=m.source_sha256{return Err("INVALID_STREAM_SOURCE_HASH".into());}
    if let Some(hash)=&m.glare_sha256 {if crate::research_streaming_route::file_hash(&dir.join("source.glare.f64le"),n*8,c)?!=*hash{return Err("INVALID_STREAM_SOURCE_HASH".into());}}
    Ok(())
}

fn run_inner(
    dir: &Path,
    token: u64,
    budget: u64,
    sha: &str,
    mut c: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    check(&mut c)?;
    if !SOURCE_ADMITTED { return Err("GPU_FINISH_STREAM_PENDING".into()); }
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != sha {
        return Err("INVALID_TAIL_IDENTITY".into());
    }
    if budget > crate::research_spatial::MEMORY_CAP || budget < p.combined_peak_bytes {
        return Err("INSUFFICIENT_GPU_TAIL_MEMORY".into());
    }
    verify_sources(dir,&m,&p,&mut c)?;
    m.tail.numerical_preflight(&mut c)?;
    let candidate = dir.join("candidate");
    io(fs::create_dir(&candidate))?;
    let mut guard = Owned(candidate.clone(), false);
    let source = dir.join("source.logxyz.f64le");
    let glare = m.tail.external_glare().then(|| dir.join("source.glare.f64le"));
    let output = candidate.join("candidate.rgb.f32le");
    let report: serde_json::Value = {
        #[cfg(feature = "gpu-diffusion-research")]
        {
            let mut owner = super::gpu::StreamingFinish::new(&m.tail, &mut c)?;
            let report = owner.run_files(&source, glare.as_deref(), &output, &dir.join("scratch"), &mut c)?;
            drop(owner);
            report
        }
        #[cfg(not(feature = "gpu-diffusion-research"))]
        { return Err("GPU_FINISH_STREAM_UNSUPPORTED".into()); }
    };
    if report["passCount"].as_u64() != Some(p.pass_count) || report["sampleCount"].as_u64() != Some(p.sample_count) {
        return Err("GPU_FINISH_STREAM_NUMERIC_CHECK".into());
    }
    let commit_phase = PhaseGuard::begin(GpuPhase::Commit);
    let done = Complete {
        ok: true,
        backend: ("gpu_scan_tail_stream_f32_v1").into(),
        schema_version: 1,
        layout: super::LAYOUT.into(),
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
    let report = crate::research_streaming_route::summary(report, &p, "gpu_scan_tail_stream_f32_v1")?;
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
    run_inner(dir, token, budget, sha, c)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn manifest(external:bool)->Manifest {
        let d=super::super::tests::descriptor();let options=super::super::tests::options(0.7,3.2,true);
        Manifest{schema_version:1,layout:super::super::LAYOUT.into(),operation:OP.into(),token:72,source_sha256:"a".repeat(64),glare_sha256:external.then(||"b".repeat(64)),tail:StreamFinishPlan::new(&d,8192,6144,options,None,external).unwrap()}
    }
    #[test] fn native_stream_scanner_plan_uses_real_size_and_external_glare_budget() {
        let a=manifest(false);let b=manifest(true);let p=make_plan(&a,hex(&Sha256::digest(serde_json::to_vec(&a).unwrap()))).unwrap();let q=make_plan(&b,"b".repeat(64)).unwrap();
        assert_eq!((p.width,p.height),(8192,6144));assert_eq!((a.tail.parameters().width,a.tail.parameters().height),(1,1));
        assert_eq!(q.disk_bytes-p.disk_bytes,8192*6144*8);
        assert_eq!(estimated_disk_bytes(8192,6144,&super::super::tests::descriptor(),super::super::tests::options(0.7,3.2,true),true).unwrap(),q.disk_bytes);
        assert!(p.glare_active);assert!(!p.external_glare);
        if let Some(dir)=std::env::var_os("FILMROOM_STREAM_PLAN_FIXTURE_DIR") {std::fs::create_dir_all(&dir).unwrap();std::fs::write(PathBuf::from(dir).join("scan-tail-stream-v1.json"),serde_json::to_vec_pretty(&crate::research_streaming_route::plan_envelope(&p).unwrap()).unwrap()).unwrap();}
    }
    #[test] fn scanner_wrapper_rejects_unknown_or_inconsistent_glare_hashes() {
        let mut m=manifest(false);m.glare_sha256=Some("b".repeat(64));assert!(make_plan(&m,"a".repeat(64)).is_err());
        let mut v=serde_json::to_value(manifest(false)).unwrap();v["extra"]=true.into();assert!(serde_json::from_value::<Manifest>(v).is_err());
    }
    #[test] fn scanner_gate_remains_closed_without_files() {assert_eq!(run_stage(Path::new("unused"),1,1,"",||false).unwrap_err(),"GPU_FINISH_STREAM_PENDING");}
}
