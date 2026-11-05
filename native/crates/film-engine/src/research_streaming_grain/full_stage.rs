//! Private immutable manifests and all-three-plane atomic candidate validation.
//! Private candidates require outer confirmed death or a real native session seal.
//! Source and output hashes bind immutable planes; no source mutation or CPU fallback.
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase};
use super::StreamGrainPlan;
use crate::research_grain::{GrainPlan, RngMode, guarded_graph};
use crate::research_spatial::{R, MEMORY_CAP, check};
use serde::{Serialize, Deserialize};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
pub const OPERATION: &str = "fullFilmGrainStreamV1";
pub const LAYOUT: &str = "fullLineCoordinateGrainV1";
pub const BACKEND: &str = "gpu_grain_stream_guarded64_spatial_f32_v1";
// Independent source, end-to-end helper and target hardware gates remain closed.
pub const SOURCE_ADMITTED: bool = false;
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema_version: u32,
    operation: String,
    layout: String,
    token: u64,
    source_sha256: [String; 3],
    grain_config: GrainPlan,
    distribution_arithmetic: String,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StagePlan {
    pub schema_version: u32,
    pub operation: String,
    pub layout: String,
    pub token: u64,
    pub manifest_sha256: String,
    pub width: u32,
    pub height: u32,
    pub pass_count: u64,
    pub grain_active: bool,
    pub rng_mode: RngMode,
    pub distribution_arithmetic: String,
    pub micro_active: bool,
    pub final_blur_active: bool,
    pub amount_blend_active: bool,
    pub sublayers: bool,
    pub layer_count: u32,
    pub chunk_pixels: u32,
    pub chunk_count: u64,
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
    operation: String,
    layout: String,
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
            trace::phase_value(GpuPhase::Cleanup, || {
            let _ = fs::remove_dir_all(&self.0);

            });
        }
    }
}
fn io<T>(x: std::io::Result<T>) -> R<T> {
    x.map_err(|_| "GPU_GRAIN_IO".into())
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
        return Err("INVALID_GRAIN_FILE".into());
    }
    Ok(())
}
fn owner(dir: &Path, token: u64) -> R<()> {
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|v| v.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return Err("INVALID_GRAIN_OWNER".into());
    }
    let m = io(fs::symlink_metadata(dir))?;
    if !m.is_dir() || m.file_type().is_symlink() || io(dir.canonicalize())? != dir {
        return Err("INVALID_GRAIN_OWNER".into());
    }
    Ok(())
}
fn read_json<T: serde::de::DeserializeOwned>(p: &Path) -> R<(T, Vec<u8>)> {
    let m = io(fs::symlink_metadata(p))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() > 2 * 1024 * 1024 {
        return Err("INVALID_GRAIN_MANIFEST".into());
    }
    let b = io(fs::read(p))?;
    let value = serde_json::from_slice(&b).map_err(|_| "INVALID_GRAIN_MANIFEST")?;
    Ok((value, b))
}
fn make_plan(m: &Manifest, hash: String) -> R<StagePlan> {
    if m.schema_version != 1
        || m.distribution_arithmetic != "guardedSoftwareBinary64"
        || m.operation != OPERATION
        || m.layout != LAYOUT
        || m.source_sha256.iter().any(|h| !valid_hash(h))
        || m.token == 0
        || m.token > i64::MAX as u64
        || !valid_hash(&hash)
    {
        return Err("INVALID_GRAIN_MANIFEST".into());
    }
    let stream = StreamGrainPlan::from_plan(m.grain_config.clone())?;
    let p = stream.parameters();
    let memory = stream.memory()?;
    Ok(StagePlan {
        schema_version: 1,
        operation: OPERATION.into(),
        layout: LAYOUT.into(),
        token: m.token,
        manifest_sha256: hash,
        width: p.width,
        height: p.height,
        grain_active: p.active,
        rng_mode:p.rng_mode,
        distribution_arithmetic:"guardedSoftwareBinary64".into(),
        micro_active:p.micro_active,
        final_blur_active:p.active&&p.final_sigma>0.,
        amount_blend_active:p.active&&p.amount!=1.,
        sublayers: p.sublayers,
        layer_count: p.layer_count(),
        chunk_pixels: super::streaming_contract::CAPACITY,
        chunk_count: (u64::from(p.width) * u64::from(p.height)).div_ceil(u64::from(super::streaming_contract::CAPACITY)),
        pass_count: p.pass_count(),
        device_bytes: memory.device_bytes,
        host_bytes: memory.host_bytes,
        driver_allowance_bytes: memory.driver_allowance_bytes,
        helper_allowance_bytes: memory.helper_allowance_bytes,
        combined_peak_bytes: memory.combined_peak_bytes,
        disk_bytes: memory.disk_bytes + 2 * 1024 * 1024,
    })
}
fn load(dir: &Path, token: u64, verify_source_hashes: bool, c: &mut impl FnMut() -> bool) -> R<(Manifest, StagePlan)> {
        trace::phase_result(GpuPhase::Prepare, || {
    owner(dir, token)?;
    let (m, b): (Manifest, _) = read_json(&dir.join("manifest.json"))?;
    if m.token != token {
        return Err("INVALID_GRAIN_MANIFEST".into());
    }
    let p = make_plan(&m, hex(&Sha256::digest(b)))?;
    for ch in 0..3 {
        let path = dir.join(format!("source-{ch}.f64le"));
        let n = u64::from(p.width) * u64::from(p.height);
        regular(&path, n * 8)?;
        if verify_source_hashes && plane_hash(&path, n, c)? != m.source_sha256[ch] {
            return Err("INVALID_STREAM_SOURCE_DIGEST".into());
        }
    }
    Ok((m, p))

        })
    }
pub fn estimated_disk_bytes(width: u32, height: u32, grain_config: &StreamGrainPlan) -> R<u64> {
    grain_config.validate()?;
    if grain_config.parameters().width != width || grain_config.parameters().height != height {
        return Err("INVALID_GRAIN_PLAN".into());
    }
    Ok(grain_config.memory()?.disk_bytes + 2 * 1024 * 1024)
}
#[allow(clippy::too_many_arguments)]
pub fn prepare_stage(
    sources: [&Path; 3],
    dir: &Path,
    token: u64,
    width: u32,
    height: u32,
    grain_config: &StreamGrainPlan,
    mut cancelled: impl FnMut() -> bool,
) -> R<StagePlan> {
    estimated_disk_bytes(width, height, grain_config)?;
    check(&mut cancelled)?;
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|v| v.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return Err("INVALID_GRAIN_OWNER".into());
    }
    crate::gpu_stage_staging::create_stage_dir(dir)?;
    let mut guard = OwnedDir(dir.to_path_buf(), false);
    let n = width as u64 * height as u64;
    crate::gpu_stage_staging::snapshot_sources(sources, dir, n, 24 * n, &mut cancelled)?;
    let mut source_sha256 = std::array::from_fn(|_| String::new());
    for ch in 0..3 { source_sha256[ch] = plane_hash(&dir.join(format!("source-{ch}.f64le")), n, &mut cancelled)?; }
    let m = Manifest {
        schema_version: 1,
        operation: OPERATION.into(),
        layout: LAYOUT.into(),
        token,
        source_sha256,
        grain_config: grain_config.parameters().clone(),
        distribution_arithmetic:"guardedSoftwareBinary64".into(),
    };
    let b = serde_json::to_vec(&m).map_err(|_| "INVALID_GRAIN_MANIFEST")?;
    if b.len() > 2 * 1024 * 1024 { return Err("INVALID_STREAM_MANIFEST_SIZE".into()); }
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
    plan_stage_cancellable(dir, token, || false)
}
/// Plans inspect metadata only. Execution and adoption rehash sources with cancellation.
pub fn plan_stage_cancellable(dir: &Path, token: u64, mut cancelled: impl FnMut() -> bool) -> R<StagePlan> {
    check(&mut cancelled)?;
    load(dir, token, false, &mut cancelled).map(|(_, p)| p)
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
                return Err("NONFINITE_GRAIN_OUTPUT".into());
            }
        }
        hash.update(&b[..len]);
        left -= len as u64;
    }
    Ok(hex(&hash.finalize()))
}
fn publish(dir: &Path, m: &Manifest, p: &StagePlan, c: &mut impl FnMut() -> bool) -> R<()> {
        trace::phase_result(GpuPhase::Commit, || {
    let candidate = dir.join("candidate");
    let n = p.width as u64 * p.height as u64;
    let mut hashes = std::array::from_fn(|_| String::new());
    for ch in 0..3 {
        hashes[ch] = plane_hash(&candidate.join(format!("plane-{ch}.f64le")), n, c)?;
    }
    let complete = Complete {
        ok: true,
        backend: (BACKEND).into(),
        schema_version: 1,
        operation: OPERATION.into(),
        layout: LAYOUT.into(),
        token: m.token,
        manifest_sha256: p.manifest_sha256.clone(),
        width: p.width,
        height: p.height,
        plane_sha256: hashes,
    };
    let b = serde_json::to_vec(&complete).map_err(|_| "INVALID_GRAIN_COMPLETE")?;
    let temp = candidate.join("complete.tmp");
    let mut f = io(OpenOptions::new().write(true).create_new(true).open(&temp))?;
    io(f.write_all(&b))?;
    io(f.sync_all())?;
    drop(f);
    check(c)?;
    io(fs::rename(temp, candidate.join("complete.json")))?;
    Ok(())

        })
    }
pub fn validate_outputs(
    dir: &Path,
    token: u64,
    manifest_sha256: &str,
    mut cancelled: impl FnMut() -> bool,
) -> R<[PathBuf; 3]> {
    check(&mut cancelled)?;
    let (_, p) = load(dir, token, true, &mut cancelled)?;
    if p.manifest_sha256 != manifest_sha256 {
        return Err("INVALID_GRAIN_MANIFEST".into());
    }
    let candidate = dir.join("candidate");
    let meta = io(fs::symlink_metadata(&candidate))?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return Err("INVALID_GRAIN_COMPLETE".into());
    }
    let (complete, _): (Complete, _) = read_json(&candidate.join("complete.json"))?;
    if complete.schema_version != 1 || !complete.ok || complete.backend != BACKEND
        || complete.operation != OPERATION
        || complete.layout != LAYOUT
        || complete.token != token
        || complete.manifest_sha256 != manifest_sha256
        || complete.width != p.width
        || complete.height != p.height
        || complete.plane_sha256.iter().any(|x| !valid_hash(x))
    {
        return Err("INVALID_GRAIN_COMPLETE".into());
    }
    let paths = std::array::from_fn(|ch| candidate.join(format!("plane-{ch}.f64le")));
    for ch in 0..3 {
        if plane_hash(&paths[ch], p.width as u64 * p.height as u64, &mut cancelled)?
            != complete.plane_sha256[ch]
        {
            return Err("INVALID_GRAIN_COMPLETE".into());
        }
    }
    check(&mut cancelled)?;
    Ok(paths)
}
/// Validate the original mandatory census and full-line witness before publication.
fn validate_run_summary(config: &GrainPlan, result: &serde_json::Value) -> R<()> {
    let census = &result["fixedAdmission"];
    let checks = (guarded_graph::SAMPLER_CHECKS + guarded_graph::PREPASS_CHECKS * guarded_graph::PREPASS_RATES.len() as u32 + guarded_graph::BOUNDARY_CHECKS) as u64;
    for (key, expected) in [("samplerSampleCount", checks), ("exactRngWordsChecked", 98304), ("softwareArithmeticChecks", 1280), ("poissonCountMismatches", 0), ("binomialCountMismatches", 0), ("randomWordCountMismatches", 0), ("samplerTupleMismatches", 0)] {
        if census[key].as_u64() != Some(expected) { return Err("GPU_GRAIN_NUMERIC_CHECK".into()); }
    }
    for key in ["samplerMaxAbs", "samplerRms"] {
        if !census[key].as_f64().is_some_and(|v| v.is_finite() && (0. ..=1e-4).contains(&v)) { return Err("GPU_GRAIN_NUMERIC_CHECK".into()); }
    }
    if result["layout"] != LAYOUT || result["passCount"].as_u64() != Some(config.pass_count())
        || result["sampleCount"].as_u64() != Some(config.pass_count() * (u64::from(config.width) + u64::from(config.height))) {
        return Err("GPU_GRAIN_NUMERIC_CHECK".into());
    }
    Ok(())
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
    let (m, p) = load(dir, token, true, &mut cancelled)?;
    if p.manifest_sha256 != manifest_sha256 {
        return Err("INVALID_GRAIN_MANIFEST".into());
    }
    if budget_bytes < p.combined_peak_bytes || budget_bytes > MEMORY_CAP {
        return Err("INSUFFICIENT_GPU_GRAIN_MEMORY".into());
    }
    if !SOURCE_ADMITTED { return Err("GPU_GRAIN_STREAM_PENDING".into()); }
    #[cfg(not(feature = "gpu-diffusion-research"))]
    {
        let _ = (m, p, stage_started);
        Err("GPU_GRAIN_UNSUPPORTED".into())
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
        let stream = StreamGrainPlan::from_plan(m.grain_config.clone())?;
        let mut sampler = super::streaming_sampler::StreamingSampler::new(&stream, &mut cancelled)?;
        let execution = crate::research_streaming_spatial::run_grain_files(
            sources.each_ref().map(|x| x.as_path()),
            outputs.each_ref().map(|x| x.as_path()),
            &candidate.join("scratch"),
            &mut sampler,
            &mut cancelled,
            &mut |_, _| {},
        );
        // Drop the only Runner/Context and all handles before returning to the outer seal.
        drop(sampler);
        let mut result = execution?;
        validate_run_summary(&m.grain_config,&result)?;
        publish(dir, &m, &p, &mut cancelled)?;
        check(&mut cancelled)?;
        let obj = result.as_object_mut().ok_or("INVALID_GRAIN_COMPLETE")?;
        obj.insert(
            "elapsedMs".into(),
            serde_json::json!(stage_started.elapsed().as_secs_f64() * 1000.),
        );
        for (k, v) in [
            ("ok", serde_json::json!(true)),
            ("schemaVersion", serde_json::json!(1)),
            ("operation", serde_json::json!(OPERATION)),
            ("layout", serde_json::json!(LAYOUT)),
            ("nativeStageSealed", serde_json::json!(false)),
            ("rngMode", serde_json::to_value(p.rng_mode).map_err(|_| "INVALID_GRAIN_COMPLETE")?),
            ("distributionArithmetic", serde_json::json!("guardedSoftwareBinary64")),
            ("chunkCount", serde_json::json!(p.chunk_count)),
            ("layerCount", serde_json::json!(p.layer_count)),
            ("grainActive", serde_json::json!(p.grain_active)),
            ("microActive", serde_json::json!(p.micro_active)),
            ("finalBlurActive", serde_json::json!(p.final_blur_active)),
            ("amountBlendActive", serde_json::json!(p.amount_blend_active)),
            ("sublayers", serde_json::json!(p.sublayers)),
            ("deviceBytes", serde_json::json!(p.device_bytes)),
            ("combinedPeakBytes", serde_json::json!(p.combined_peak_bytes)),
            ("token", serde_json::json!(token)),
            ("manifestSha256", serde_json::json!(manifest_sha256)),
            ("gpuUsed", serde_json::json!(true)),
            ("fullImage", serde_json::json!(true)),
            ("width", serde_json::json!(p.width)),
            ("height", serde_json::json!(p.height)),
            ("channels", serde_json::json!(3)),
            (
                "backend",
                serde_json::json!(BACKEND),
            ),
        ] {
            obj.insert(k.into(), v);
        }
        normalize_summary(&mut result)?;
        guard.1 = true;
        Ok(result)
    }
}

/// Public helper summaries expose measured counters and a closed, versioned field set.
fn normalize_summary(result: &mut serde_json::Value) -> R<()> {
    let census = result["fixedAdmission"].as_object().ok_or("INVALID_GRAIN_COMPLETE")?.clone();
    let object = result.as_object_mut().ok_or("INVALID_GRAIN_COMPLETE")?;
    for (key, value) in census {
        if key != "numericFlagReads" { object.insert(key, value); }
    }
    for (key, value) in [
        ("fullResolution", serde_json::json!(true)),
        ("contextCount", serde_json::json!(1)),
        ("sampleToleranceAbsolute", serde_json::json!(crate::research_spatial::NUMERIC_TOLERANCE)),
        ("sampleToleranceRelative", serde_json::json!(crate::research_spatial::NUMERIC_TOLERANCE)),
        ("sampleScope", serde_json::json!("per_axis_f64_on_gpu_f32_input")),
    ] { object.insert(key.into(), value); }
    object.insert("stochasticScope".into(), serde_json::json!("fixed_synthetic_reference_tuple_census"));
    object.insert("microstructureArithmetic".into(), serde_json::json!("original_words_guarded_f32_box_muller"));
    const KEYS: &[&str] = &["ok", "schemaVersion", "operation", "token", "manifestSha256", "width", "height", "channels", "gpuUsed", "fullImage", "fullResolution", "layout", "backend", "deviceBytes", "combinedPeakBytes", "passCount", "sampleCount", "sampleMaxAbs", "sampleRms", "elapsedMs", "vendor", "uploadBytes", "readbackBytes", "dispatches", "nativeStageSealed", "contextCount", "sampleToleranceAbsolute", "sampleToleranceRelative", "sampleScope", "rngMode", "distributionArithmetic", "grainActive", "microActive", "finalBlurActive", "amountBlendActive", "sublayers", "layerCount", "chunkCount", "samplerSampleCount", "exactRngWordsChecked", "softwareArithmeticChecks", "poissonCountMismatches", "binomialCountMismatches", "randomWordCountMismatches", "samplerTupleMismatches", "numericFlagReads", "samplerMaxAbs", "samplerRms", "stochasticScope", "microstructureArithmetic"];
    object.retain(|key, _| KEYS.contains(&key.as_str()));
    if object.len() != KEYS.len() { return Err("INVALID_STREAM_SUMMARY".into()); }
    Ok(())
}

#[cfg(test)]
mod manifest_tests {
    use super::*;
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    fn descriptor(w: u32, h: u32) -> StreamGrainPlan {
        let mut raw = super::super::streaming_shader::tests::plan();
        raw.width = w; raw.height = h;
        StreamGrainPlan::from_plan(raw).unwrap()
    }

    fn manifest(w: u32, h: u32) -> Manifest {
        Manifest { schema_version: 1, operation: OPERATION.into(), layout: LAYOUT.into(), token: 17, source_sha256: std::array::from_fn(|_| "a".repeat(64)), grain_config: descriptor(w, h).parameters().clone(), distribution_arithmetic: "guardedSoftwareBinary64".into(), }
    }
    struct Fixture { root: PathBuf, stage: PathBuf, sources: [PathBuf; 3] }
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!("filmroom-stream-grain-{}-{}", std::process::id(), NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)));
            fs::create_dir(&root).unwrap();
            let root = root.canonicalize().unwrap();
            let sources = std::array::from_fn(|ch| root.join(format!("input-{ch}.f64le")));
            for (ch, path) in sources.iter().enumerate() {
                let data: Vec<u8> = (0..15).flat_map(|i| (0.1 + (i + ch) as f64 / 31.).to_le_bytes()).collect();
                fs::write(path, data).unwrap();
            }
            Self { stage: root.join("gpu-stage-17"), root, sources }
        }
        fn prepare(&self) -> StagePlan {
            prepare_stage(self.sources.each_ref().map(|p| p.as_path()), &self.stage, 17, 5, 3, &descriptor(5, 3), || false).unwrap()
        }
    }
    impl Drop for Fixture { fn drop(&mut self) { let _ = fs::remove_dir_all(&self.root); } }

    #[test]
    fn new_operation_is_strict_and_admits_50mp_without_image_allocations() {
        let m = manifest(8192, 6144);
        let p = make_plan(&m, hex(&Sha256::digest(serde_json::to_vec(&m).unwrap()))).unwrap();
        if let Some(dir) = std::env::var_os("FILMROOM_STREAM_PLAN_FIXTURE_DIR") {
            let dir = PathBuf::from(dir); fs::create_dir_all(&dir).unwrap();
            fs::write(dir.join("grain-stream-v1.json"), serde_json::to_vec_pretty(&crate::research_streaming_route::plan_envelope(&p).unwrap()).unwrap()).unwrap();
        }
        assert_eq!(p.schema_version, 1);
        assert_eq!(p.operation, OPERATION);
        assert_eq!(p.layout, LAYOUT);
        assert_eq!(p.combined_peak_bytes, p.device_bytes + p.host_bytes + p.driver_allowance_bytes + p.helper_allowance_bytes);
        assert_eq!((p.width, p.height), (8192, 6144));
        let mut json = serde_json::to_value(&m).unwrap();
        json["unknownOldAllocation"] = serde_json::json!(123);
        assert!(serde_json::from_value::<Manifest>(json).is_err());
        for field in ["operation", "layout"] {
            let mut json = serde_json::to_value(&m).unwrap(); json[field] = serde_json::json!("legacy");
            assert!(make_plan(&serde_json::from_value(json).unwrap(), "b".repeat(64)).is_err());
        }
        let mut old = m.clone(); old.schema_version = 2;
        assert!(make_plan(&old, "b".repeat(64)).is_err());
        assert_eq!(p.device_bytes, 32 * 1024 * 1024 + 65536 + 8_917_696);
        assert_eq!(p.host_bytes, 64 * 1024 * 1024 + 9600);
        assert_eq!(p.disk_bytes, 68 * 8192 * 6144 + 65536 + 2 * 1024 * 1024);
        assert_eq!(p.chunk_pixels, 16384);
        let mut mt = m.clone(); mt.grain_config.rng_mode = RngMode::SequentialMt19937;
        assert!(make_plan(&mt, "b".repeat(64)).is_err());
    }

    #[test]
    fn source_hash_budget_gate_and_cancellation_remain_fail_closed() {
        let f = Fixture::new(); let p = f.prepare();
        assert_eq!(plan_stage(&f.stage, 17).unwrap(), p);
        assert!(run_stage(&f.stage, 17, p.combined_peak_bytes - 1, &p.manifest_sha256, || false).is_err());
        assert_eq!(run_stage(&f.stage, 17, p.combined_peak_bytes, &p.manifest_sha256, || false).unwrap_err(), "GPU_GRAIN_STREAM_PENDING");
        assert!(!f.stage.join("candidate").exists());
        assert_eq!(validate_outputs(&f.stage, 17, &p.manifest_sha256, || true).unwrap_err(), "CANCELLED");
        fs::write(f.stage.join("source-1.f64le"), vec![0u8; 15 * 8]).unwrap();
        // Planning is metadata-only; execution must still reject same-length tampering.
        assert_eq!(plan_stage(&f.stage, 17).unwrap(), p);
        assert_eq!(run_stage(&f.stage, 17, p.combined_peak_bytes, &p.manifest_sha256, || false).unwrap_err(), "INVALID_STREAM_SOURCE_DIGEST");
    }

    #[test]
    fn completion_binds_all_three_planes_and_preserves_sources() {
        let f = Fixture::new(); let p = f.prepare();
        let before: [Vec<u8>; 3] = std::array::from_fn(|ch| fs::read(&f.sources[ch]).unwrap());
        let (m, _) = load(&f.stage, 17, true, &mut || false).unwrap();
        fs::create_dir(f.stage.join("candidate")).unwrap();
        for ch in 0..3 { fs::copy(&f.sources[ch], f.stage.join(format!("candidate/plane-{ch}.f64le"))).unwrap(); }
        publish(&f.stage, &m, &p, &mut || false).unwrap();
        let outputs = validate_outputs(&f.stage, 17, &p.manifest_sha256, || false).unwrap();
        assert!(validate_outputs(&f.stage, 17, &"0".repeat(64), || false).is_err());
        fs::write(&outputs[2], vec![0u8; 15 * 8]).unwrap();
        assert!(validate_outputs(&f.stage, 17, &p.manifest_sha256, || false).is_err());
        for ch in 0..3 { assert_eq!(fs::read(&f.sources[ch]).unwrap(), before[ch]); }
    }

    #[test]
    fn cancelled_preparation_never_removes_existing_owner_or_sources() {
        let f = Fixture::new();
        let before = fs::read(&f.sources[0]).unwrap();
        assert_eq!(prepare_stage(f.sources.each_ref().map(|p| p.as_path()), &f.stage, 17, 5, 3, &descriptor(5, 3), || true).unwrap_err(), "CANCELLED");
        assert!(!f.stage.exists());
        f.prepare();
        let manifest = fs::read(f.stage.join("manifest.json")).unwrap();
        assert!(prepare_stage(f.sources.each_ref().map(|p| p.as_path()), &f.stage, 17, 5, 3, &descriptor(5, 3), || false).is_err());
        assert_eq!(fs::read(f.stage.join("manifest.json")).unwrap(), manifest);
        assert_eq!(fs::read(&f.sources[0]).unwrap(), before);
    }
}
