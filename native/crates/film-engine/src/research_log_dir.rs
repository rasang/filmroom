//! Compound exposure-log and DIR packet: one helper, one device, chunk-resident handoff.
//! Main process publishes only after confirmed helper death. No source mutation.
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase};
use crate::research_dir::{DirPlan, R};
use serde::{Serialize, Deserialize};
#[cfg(test)] use crate::research_dir::cpu_mirror;
pub(crate) mod log_prefix;
use crate::research_spatial::{MEMORY_CAP, check};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
const OPERATION: &str = "fullExposureLogDir";
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema_version: u32,
    operation: String,
    token: u64,
    dir_config: DirPlan,
    exposure_correction: f64,
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
    pub dir_active: bool,
    pub curve_knots: u32,
    pub pre_curve_knots: u32,
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
            trace::phase_value(GpuPhase::Cleanup, || {
            let _ = fs::remove_dir_all(&self.0);

            });
        }
    }
}
fn io<T>(x: std::io::Result<T>) -> R<T> {
    x.map_err(|_| "GPU_LOG_DIR_IO".into())
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
        return Err("INVALID_LOG_DIR_FILE".into());
    }
    Ok(())
}
fn owner(dir: &Path, token: u64) -> R<()> {
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|v| v.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return Err("INVALID_LOG_DIR_OWNER".into());
    }
    let m = io(fs::symlink_metadata(dir))?;
    if !m.is_dir() || m.file_type().is_symlink() || io(dir.canonicalize())? != dir {
        return Err("INVALID_LOG_DIR_OWNER".into());
    }
    Ok(())
}
fn read_json<T: serde::de::DeserializeOwned>(p: &Path) -> R<(T, Vec<u8>)> {
    let m = io(fs::symlink_metadata(p))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() > 1024 * 1024 {
        return Err("INVALID_LOG_DIR_MANIFEST".into());
    }
    let b = io(fs::read(p))?;
    let value = serde_json::from_slice(&b).map_err(|_| "INVALID_LOG_DIR_MANIFEST")?;
    Ok((value, b))
}
fn make_plan(m: &Manifest, hash: String) -> R<StagePlan> {
    if m.schema_version != 1
        || m.operation != OPERATION
        || m.token == 0
        || m.token > i64::MAX as u64
        || !valid_hash(&hash)
    {
        return Err("INVALID_LOG_DIR_MANIFEST".into());
    }
    m.dir_config.validate()?;
    log_prefix::validate_correction(m.dir_config.width, m.dir_config.height, m.exposure_correction)?;
    let p = &m.dir_config;
    let memory = p.spatial_plan()?;
    let extra = crate::research_spatial::PIPELINE_RESERVATION_BYTES + log_prefix::PIPELINE_BYTES;
    let combined_peak_bytes = memory.combined_peak_bytes.checked_add(extra).and_then(|v| v.checked_add(log_prefix::HOST_BYTES)).ok_or("GPU_LOG_DIR_MEMORY_LIMIT")?;
    if combined_peak_bytes > MEMORY_CAP { return Err("GPU_LOG_DIR_MEMORY_LIMIT".into()); }
    Ok(StagePlan {
        schema_version: 1,
        operation: OPERATION.into(),
        token: m.token,
        manifest_sha256: hash,
        width: p.width,
        height: p.height,
        dir_active: p.dir_active,
        curve_knots: p.initial.axis.len() as u32,
        pre_curve_knots: p.before_dir.axis.len() as u32,
        chunk_pixels: p.chunk_pixels(),
        chunk_count: p.chunk_count(),
        pass_count: p.pass_count(),
        device_bytes: memory.device_bytes,
        host_bytes: memory.host_bytes + log_prefix::HOST_BYTES,
        driver_allowance_bytes: memory.driver_allowance_bytes + extra,
        helper_allowance_bytes: memory.helper_allowance_bytes,
        combined_peak_bytes,
        disk_bytes: p.disk_bytes(),
    })
}
fn load(dir: &Path, token: u64) -> R<(Manifest, StagePlan)> {
        trace::phase_result(GpuPhase::Prepare, || {
    owner(dir, token)?;
    let (m, b): (Manifest, _) = read_json(&dir.join("manifest.json"))?;
    if m.token != token {
        return Err("INVALID_LOG_DIR_MANIFEST".into());
    }
    let p = make_plan(&m, hex(&Sha256::digest(b)))?;
    for ch in 0..3 {
        regular(
            &dir.join(format!("source-{ch}.f64le")),
            p.width as u64 * p.height as u64 * 8,
        )?;
    }
    Ok((m, p))

        })
    }
pub fn estimated_disk_bytes(width: u32, height: u32, dir_config: &DirPlan, correction: f64) -> R<u64> {
    dir_config.validate()?;
    log_prefix::validate_correction(width, height, correction)?;
    if dir_config.width != width || dir_config.height != height {
        return Err("INVALID_LOG_DIR_PLAN".into());
    }
    Ok(dir_config.disk_bytes())
}
#[allow(clippy::too_many_arguments)]
pub fn prepare_stage(
    sources: [&Path; 3],
    dir: &Path,
    token: u64,
    width: u32,
    height: u32,
    dir_config: &DirPlan,
    correction: f64,
    mut cancelled: impl FnMut() -> bool,
) -> R<StagePlan> {
    estimated_disk_bytes(width, height, dir_config, correction)?;
    check(&mut cancelled)?;
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|v| v.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return Err("INVALID_LOG_DIR_OWNER".into());
    }
    crate::gpu_stage_staging::create_stage_dir(dir)?;
    let mut guard = OwnedDir(dir.to_path_buf(), false);
    let n = width as u64 * height as u64;
    crate::gpu_stage_staging::snapshot_sources(sources, dir, n, 24 * n, &mut cancelled)?;
    let m = Manifest {
        schema_version: 1,
        operation: OPERATION.into(),
        token,
        dir_config: dir_config.clone(),
        exposure_correction: correction,
    };
    let b = serde_json::to_vec(&m).map_err(|_| "INVALID_LOG_DIR_MANIFEST")?;
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
                return Err("NONFINITE_LOG_DIR_OUTPUT".into());
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
        schema_version: 1,
        operation: OPERATION.into(),
        token: m.token,
        manifest_sha256: p.manifest_sha256.clone(),
        width: p.width,
        height: p.height,
        plane_sha256: hashes,
    };
    let b = serde_json::to_vec(&complete).map_err(|_| "INVALID_LOG_DIR_COMPLETE")?;
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
    let (_, p) = load(dir, token)?;
    if p.manifest_sha256 != manifest_sha256 {
        return Err("INVALID_LOG_DIR_MANIFEST".into());
    }
    let candidate = dir.join("candidate");
    let meta = io(fs::symlink_metadata(&candidate))?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return Err("INVALID_LOG_DIR_COMPLETE".into());
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
        return Err("INVALID_LOG_DIR_COMPLETE".into());
    }
    let paths = std::array::from_fn(|ch| candidate.join(format!("plane-{ch}.f64le")));
    for ch in 0..3 {
        if plane_hash(&paths[ch], p.width as u64 * p.height as u64, &mut cancelled)?
            != complete.plane_sha256[ch]
        {
            return Err("INVALID_LOG_DIR_COMPLETE".into());
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
        return Err("INVALID_LOG_DIR_MANIFEST".into());
    }
    if budget_bytes < p.combined_peak_bytes || budget_bytes > MEMORY_CAP {
        return Err("INSUFFICIENT_GPU_LOG_DIR_MEMORY".into());
    }
    #[cfg(not(feature = "gpu-diffusion-research"))]
    {
        let _ = (m, p, stage_started);
        Err("GPU_LOG_DIR_UNSUPPORTED".into())
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
        let mut result = crate::research_dir::gpu::run_files_with_log_prefix(
            &m.dir_config,
            m.exposure_correction,
            sources.each_ref().map(|x| x.as_path()),
            outputs.each_ref().map(|x| x.as_path()),
            &mut cancelled,
        )?;
        let n = p.width as u64 * p.height as u64;
        let samples =
            8 * (p.chunk_count - 1) + (n - (p.chunk_count - 1) * p.chunk_pixels as u64).min(8);
        let expected = p.pass_count * (p.width as u64 + p.height as u64)
            + if p.dir_active {
                6 * samples
            } else {
                3 * samples
            };
        if result["passCount"].as_u64() != Some(p.pass_count)
            || result["sampleCount"].as_u64() != Some(expected)
            || result["logSampleCount"].as_u64() != Some((9 + if p.dir_active { 3 } else { 0 }) * samples)
        {
            return Err("GPU_LOG_DIR_NUMERIC_CHECK".into());
        }
        publish(dir, &m, &p, &mut cancelled)?;
        check(&mut cancelled)?;
        let obj = result.as_object_mut().ok_or("INVALID_LOG_DIR_COMPLETE")?;
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
                serde_json::json!("Vulkan f32 resident exposure log, initial density and DIR"),
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
        return Err("INVALID_LOG_DIR_MANIFEST".into());
    }
    m.dir_config.numerical_preflight(&mut cancelled)?;
    let candidate = dir.join("candidate");
    io(fs::create_dir(&candidate))?;
    let mut guard = OwnedDir(candidate.clone(), false);
    let mut logs = std::array::from_fn(|_| Vec::new());
    for ch in 0..3 {
        let b = io(fs::read(dir.join(format!("source-{ch}.f64le"))))?;
        logs[ch] = b
            .chunks_exact(8)
            .map(|x| f64::from_le_bytes(x.try_into().unwrap()))
            .collect();
    }
    // Test-only scalar reference, explicitly never reported as GPU execution.
    for plane in &mut logs {
        for value in plane {
            check(&mut cancelled)?;
            *value = log_prefix::mirror(*value, m.exposure_correction)? as f64;
        }
    }
    let outputs = cpu_mirror(&m.dir_config, &logs, &mut cancelled)?;
    for ch in 0..3 {
        check(&mut cancelled)?;
        let mut f = io(OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(candidate.join(format!("plane-{ch}.f64le"))))?;
        for x in &outputs[ch] {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::research_dir::Curve;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(1);
    struct Root(PathBuf);
    impl Root {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!("log-dir-{}-{}", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)));
            fs::create_dir(&path).unwrap();
            Self(path.canonicalize().unwrap())
        }
    }
    impl Drop for Root { fn drop(&mut self) { let _ = fs::remove_dir_all(&self.0); } }
    fn fixture(active: bool) -> DirPlan {
        let axis: Vec<f64> = vec![-12., -3., -1., 0., 2.];
        let inverse_dx = axis.windows(2).map(|v| 1. / (v[1] - v[0])).collect();
        let curve = Curve { axis, inverse_dx, values: vec![[0.;3], [0.2,0.3,0.4], [0.8,0.9,1.], [1.4,1.5,1.6], [2.;3]] };
        DirPlan { width: 11, height: 7, dir_active: active, positive: false,
            core_sigma: if active { 0.7 } else { 0. }, tail_decay: 0.5, tail_weight: 0.3,
            matrix: [[0.09,0.03,0.02],[0.07,0.06,0.01],[0.04,0.05,0.08]],
            density_max: [2.;3], initial: curve.clone(), before_dir: curve }
    }
    fn input(root: &Root, p: &DirPlan) -> ([PathBuf;3], [Vec<f64>;3]) {
        let values = std::array::from_fn(|ch| (0..p.width*p.height).map(|i| {
            if i%17==0 { 1e12 } else if i%13==0 { -0.3 } else if i%11==0 { 0. } else if i%7==0 { 1.0000000596046448 } else { (i as f64 + ch as f64*0.37) / 67. }
        }).collect::<Vec<_>>());
        let paths = std::array::from_fn(|ch| root.0.join(format!("input-{ch}")));
        for ch in 0..3 {
            let mut f = File::create(&paths[ch]).unwrap();
            for value in &values[ch] { f.write_all(&value.to_le_bytes()).unwrap(); }
        }
        (paths, values)
    }
    #[test]
    fn compound_packet_matches_original_sequential_mirrors_without_mutating_sources() {
        for (active, w, h, correction) in [(false,11,7,0.731923),(true,11,7,3.7),(false,257,257,1e-6)] {
            let root = Root::new(); let mut config = fixture(active); config.width=w;config.height=h; let (sources, raw) = input(&root, &config);
            let originals: Vec<_> = sources.iter().map(|p| fs::read(p).unwrap()).collect();
            let dir = root.0.join("gpu-stage-81");
            let plan = prepare_stage(sources.each_ref().map(|p|p.as_path()), &dir, 81, config.width, config.height, &config, correction, ||false).unwrap();
            assert_eq!(plan, plan_stage(&dir,81).unwrap());
            let result = run_stage_cpu_mirror_for_test(&dir,81,&plan.manifest_sha256,||false).unwrap();
            assert_eq!(result["gpuUsed"], false);
            let paths = validate_outputs(&dir,81,&plan.manifest_sha256,||false).unwrap();
            let global = crate::research_global::GlobalPlan::log_exposure(config.width,config.height,correction).unwrap();
            let logged = crate::research_global::planes_mirror(&global, &raw, &mut ||false).unwrap();
            let expected = crate::research_dir::cpu_mirror(&config, &logged, &mut ||false).unwrap();
            // Also traverse both legacy packet boundaries, including f32 ->
            // f64 file storage -> f32 DIR upload, not merely a CPU f64 oracle.
            let log_dir=root.0.join("gpu-stage-91");
            let lp=crate::research_global::prepare_plane_stage(sources.each_ref().map(|p|p.as_path()),&log_dir,91,&global,||false).unwrap();
            crate::research_global::run_stage_cpu_mirror_for_test(&log_dir,91,&lp.manifest_sha256,||false).unwrap();
            let crate::research_global::GlobalOutput::Planes(log_paths)=crate::research_global::validate_outputs(&log_dir,91,&lp.manifest_sha256,||false).unwrap() else {panic!("expected three log planes")};
            let legacy_dir=root.0.join("gpu-stage-92");
            let dp=crate::research_dir::full_stage::prepare_stage(log_paths.each_ref().map(|p|p.as_path()),&legacy_dir,92,w,h,&config,||false).unwrap();
            crate::research_dir::full_stage::run_stage_cpu_mirror_for_test(&legacy_dir,92,&dp.manifest_sha256,||false).unwrap();
            let legacy=crate::research_dir::full_stage::validate_outputs(&legacy_dir,92,&dp.manifest_sha256,||false).unwrap();
            for ch in 0..3 {
                let bytes: Vec<_> = expected[ch].iter().flat_map(|v|v.to_le_bytes()).collect();
                assert_eq!(fs::read(&paths[ch]).unwrap(),bytes);
                assert_eq!(fs::read(&paths[ch]).unwrap(),fs::read(&legacy[ch]).unwrap());
                assert_eq!(fs::read(&sources[ch]).unwrap(), originals[ch]);
            }
            assert_eq!(plan.device_bytes, config.spatial_plan().unwrap().device_bytes);
            assert_eq!(plan.host_bytes, config.spatial_plan().unwrap().host_bytes + log_prefix::HOST_BYTES);
            assert_eq!(plan.driver_allowance_bytes, 112*crate::research_spatial::MIB);
            assert_eq!(plan.combined_peak_bytes, plan.device_bytes + plan.host_bytes + plan.driver_allowance_bytes + plan.helper_allowance_bytes);
        }
    }
    #[test]
    fn cancellation_and_hash_tampering_never_validate_partial_outputs() {
        let root = Root::new(); let config = fixture(false); let (sources, _) = input(&root, &config);
        let dir = root.0.join("gpu-stage-82");
        let p = prepare_stage(sources.each_ref().map(|p|p.as_path()), &dir,82,config.width,config.height,&config,0.73,||false).unwrap();
        assert!(run_stage_cpu_mirror_for_test(&dir,82,&p.manifest_sha256,||true).is_err());
        assert!(!dir.join("candidate/complete.json").exists());
        run_stage_cpu_mirror_for_test(&dir,82,&p.manifest_sha256,||false).unwrap();
        let paths = validate_outputs(&dir,82,&p.manifest_sha256,||false).unwrap();
        let mut bytes = fs::read(&paths[1]).unwrap(); bytes[0] ^= 1; fs::write(&paths[1],bytes).unwrap();
        assert!(validate_outputs(&dir,82,&p.manifest_sha256,||false).is_err());
        assert!(validate_outputs(&dir,82,&"0".repeat(64),||false).is_err());
    }
    #[test]
    fn plan_hash_binds_correction_and_rejects_nonfinite_or_legacy_operation() {
        let config = fixture(false);
        let mut m = Manifest { schema_version:1, operation:OPERATION.into(), token:83, dir_config:config, exposure_correction:0.73 };
        assert!(make_plan(&m,"0".repeat(64)).is_ok());
        let first = serde_json::to_vec(&m).unwrap(); m.exposure_correction=1.;
        assert_ne!(Sha256::digest(&first),Sha256::digest(serde_json::to_vec(&m).unwrap()));
        m.exposure_correction=f64::NAN; assert!(make_plan(&m,"0".repeat(64)).is_err());
        m.exposure_correction=1.;m.operation="fullFilmDirDensity".into();assert!(make_plan(&m,"0".repeat(64)).is_err());
    }
    #[test]
    fn emit_exposure_log_dir_plan_contract_when_requested() {
        let Some(destination)=std::env::var_os("FILM_GPU_CONTRACT_RESULTS") else {return;};
        let root=Root::new();let config=fixture(true);let(sources,_)=input(&root,&config);
        let stage=prepare_stage(sources.each_ref().map(|p|p.as_path()),&root.0.join("gpu-stage-84"),84,config.width,config.height,&config,0.73,||false).unwrap();
        let destination=PathBuf::from(destination);fs::create_dir_all(&destination).unwrap();
        fs::write(destination.join("exposure_log_dir.json"),serde_json::to_vec_pretty(&serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuExposureLogDirStage","plan":stage})).unwrap()).unwrap();
    }
    #[test]
    #[cfg(feature="gpu-diffusion-research")]
    #[ignore="Explicit real Adreno production runners; compares all outputs, not mobile speed evidence"]
    fn actual_adreno_compound_matches_separate_gpu_log_then_gpu_dir_all_outputs() {
        for active in [false,true] {
            let root=Root::new();let config=fixture(active);let(sources,_)=input(&root,&config);let correction=0.731923;
            let log=crate::research_global::GlobalPlan::log_exposure(config.width,config.height,correction).unwrap();
            let log_dir=root.0.join("gpu-stage-101");
            let lp=crate::research_global::prepare_plane_stage(sources.each_ref().map(|p|p.as_path()),&log_dir,101,&log,||false).unwrap();
            let lm=crate::research_global::run_stage(&log_dir,101,lp.combined_peak_bytes,&lp.manifest_sha256,||false).unwrap();
            let crate::research_global::GlobalOutput::Planes(log_paths)=crate::research_global::validate_outputs(&log_dir,101,&lp.manifest_sha256,||false).unwrap() else {panic!("expected planes")};
            let separate=root.0.join("gpu-stage-102");
            let dp=crate::research_dir::full_stage::prepare_stage(log_paths.each_ref().map(|p|p.as_path()),&separate,102,config.width,config.height,&config,||false).unwrap();
            let dm=crate::research_dir::full_stage::run_stage(&separate,102,dp.combined_peak_bytes,&dp.manifest_sha256,||false).unwrap();
            let expected=crate::research_dir::full_stage::validate_outputs(&separate,102,&dp.manifest_sha256,||false).unwrap();
            let compound=root.0.join("gpu-stage-103");
            let cp=prepare_stage(sources.each_ref().map(|p|p.as_path()),&compound,103,config.width,config.height,&config,correction,||false).unwrap();
            let cm=run_stage(&compound,103,cp.combined_peak_bytes,&cp.manifest_sha256,||false).unwrap();
            let actual=validate_outputs(&compound,103,&cp.manifest_sha256,||false).unwrap();
            assert_eq!(lm["adapter"],dm["adapter"]);assert_eq!(dm["adapter"],cm["adapter"]);
            assert_eq!(lm["vendor"],dm["vendor"]);assert_eq!(dm["vendor"],cm["vendor"]);
            let mut max=0f64;let mut square=0f64;let mut count=0u64;
            for ch in 0..3 {
                let a=fs::read(&actual[ch]).unwrap();let b=fs::read(&expected[ch]).unwrap();assert_eq!(a.len(),b.len());
                for(a,b)in a.chunks_exact(8).zip(b.chunks_exact(8)) {
                    let a=f64::from_le_bytes(a.try_into().unwrap());let b=f64::from_le_bytes(b.try_into().unwrap());let error=(a-b).abs();
                    assert!(a.is_finite()&&b.is_finite()&&error<=crate::research_dir::POINT_TOLERANCE*b.abs().max(1.));
                    max=max.max(error);square+=error*error;count+=1;
                }
            }
            assert_eq!(count,config.width as u64*config.height as u64*3);
            eprintln!("compound_log_dir={cm}; separate_log={lm}; separate_dir={dm}; all_scalars={count}; max_abs={max}; rms={}",(square/count as f64).sqrt());
        }
    }

}
