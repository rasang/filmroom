//! Private immutable manifests and all-three-plane atomic candidate validation.
//! Main process publishes only after confirmed helper death. No source mutation.
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase};
use super::*;
use crate::research_spatial::{MEMORY_CAP, check};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
pub const DIR_OPERATION: &str = "fullFilmDirDensityStreamV1";
pub const LOG_DIR_OPERATION: &str = "fullExposureLogDirStreamV1";
pub const SOURCE_ADMITTED: bool = false;
fn operation(p: &StreamDirPlan) -> &'static str { if p.log_correction().is_some() { LOG_DIR_OPERATION } else { DIR_OPERATION } }
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema_version: u32,
    layout: String,
    operation: String,
    token: u64,
    source_sha256: [String;3],
    dir_config: StreamDirPlan,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StagePlan {
    pub schema_version: u32,
    pub layout: String,
    pub pipeline_count: u32,
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
    backend: String,
    ok: bool,
    layout: String,
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
    x.map_err(|_| "GPU_DIR_IO".into())
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
        return Err("INVALID_DIR_FILE".into());
    }
    Ok(())
}
fn owner(dir: &Path, token: u64) -> R<()> {
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|v| v.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return Err("INVALID_DIR_OWNER".into());
    }
    let m = io(fs::symlink_metadata(dir))?;
    if !m.is_dir() || m.file_type().is_symlink() || io(dir.canonicalize())? != dir {
        return Err("INVALID_DIR_OWNER".into());
    }
    Ok(())
}
fn read_json<T: serde::de::DeserializeOwned>(p: &Path) -> R<(T, Vec<u8>)> {
    let m = io(fs::symlink_metadata(p))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() > 1024 * 1024 {
        return Err("INVALID_DIR_MANIFEST".into());
    }
    let b = io(fs::read(p))?;
    let value = serde_json::from_slice(&b).map_err(|_| "INVALID_DIR_MANIFEST")?;
    Ok((value, b))
}
fn make_plan(m: &Manifest, hash: String) -> R<StagePlan> {
    if m.schema_version != 1 || m.layout != super::LAYOUT
        || m.operation != operation(&m.dir_config)
        || m.token == 0
        || m.token > i64::MAX as u64
        || !valid_hash(&hash) || m.source_sha256.iter().any(|h| !valid_hash(h))
    {
        return Err("INVALID_DIR_MANIFEST".into());
    }
    m.dir_config.validate()?;
    let p = m.dir_config.parameters();
    let memory = m.dir_config.memory()?;
    Ok(StagePlan {
        schema_version: 1, layout: super::LAYOUT.into(), pipeline_count: memory.pipeline_count,
        operation: operation(&m.dir_config).into(), token: m.token, manifest_sha256: hash,
        width: p.width, height: p.height, dir_active: p.dir_active,
        curve_knots: p.initial.axis.len() as u32, pre_curve_knots: p.before_dir.axis.len() as u32,
        chunk_pixels: crate::research_dir::CHUNK_PIXELS,
        chunk_count: (p.width as u64 * p.height as u64).div_ceil(crate::research_dir::CHUNK_PIXELS as u64),
        pass_count: p.pass_count(), device_bytes: memory.device_bytes, host_bytes: memory.host_bytes,
        driver_allowance_bytes: memory.driver_allowance_bytes, helper_allowance_bytes: memory.helper_allowance_bytes,
        combined_peak_bytes: memory.combined_peak_bytes, disk_bytes: memory.disk_bytes,
    })
}

fn load(dir: &Path, token: u64) -> R<(Manifest, StagePlan)> {
        trace::phase_result(GpuPhase::Prepare, || {
    owner(dir, token)?;
    let (m, b): (Manifest, _) = read_json(&dir.join("manifest.json"))?;
    if m.token != token {
        return Err("INVALID_DIR_MANIFEST".into());
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
pub fn estimated_disk_bytes(width: u32, height: u32, dir_config: &StreamDirPlan) -> R<u64> {
    dir_config.validate()?;
    if dir_config.parameters().width != width || dir_config.parameters().height != height {
        return Err("INVALID_DIR_PLAN".into());
    }
    Ok(dir_config.memory()?.disk_bytes)
}
#[allow(clippy::too_many_arguments)]
pub fn prepare_stage(
    sources: [&Path; 3],
    dir: &Path,
    token: u64,
    width: u32,
    height: u32,
    dir_config: &StreamDirPlan,
    mut cancelled: impl FnMut() -> bool,
) -> R<StagePlan> {
    estimated_disk_bytes(width, height, dir_config)?;
    check(&mut cancelled)?;
    if token == 0
        || token > i64::MAX as u64
        || dir.file_name().and_then(|v| v.to_str()) != Some(format!("gpu-stage-{token}").as_str())
    {
        return Err("INVALID_DIR_OWNER".into());
    }
    crate::gpu_stage_staging::create_stage_dir(dir)?;
    let mut guard = OwnedDir(dir.to_path_buf(), false);
    let n = width as u64 * height as u64;
    crate::gpu_stage_staging::snapshot_sources(sources, dir, n, 24 * n, &mut cancelled)?;
    let mut source_sha256=std::array::from_fn(|_| String::new());
    for ch in 0..3 {source_sha256[ch]=crate::research_streaming_route::file_hash(&dir.join(format!("source-{ch}.f64le")),n*8,&mut cancelled)?;}
    let m = Manifest {
        source_sha256,
        schema_version: 1,
        layout: super::LAYOUT.into(),
        operation: operation(dir_config).into(),
        token,
        dir_config: dir_config.clone(),
    };
    let b = serde_json::to_vec(&m).map_err(|_| "INVALID_DIR_MANIFEST")?;
    if b.len()>1024*1024 {return Err("INVALID_DIR_MANIFEST".into());}
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
                return Err("NONFINITE_DIR_OUTPUT".into());
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
        backend: (crate::research_streaming_route::backend(&p.operation).ok_or("INVALID_DIR_COMPLETE")?).into(),
        schema_version: 1,
        layout: super::LAYOUT.into(),
        operation: p.operation.clone(),
        token: m.token,
        manifest_sha256: p.manifest_sha256.clone(),
        width: p.width,
        height: p.height,
        plane_sha256: hashes,
    };
    let b = serde_json::to_vec(&complete).map_err(|_| "INVALID_DIR_COMPLETE")?;
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
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != manifest_sha256 {
        return Err("INVALID_DIR_MANIFEST".into());
    }
    verify_sources(dir,&m,&p,&mut cancelled)?;
    let candidate = dir.join("candidate");
    let meta = io(fs::symlink_metadata(&candidate))?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return Err("INVALID_DIR_COMPLETE".into());
    }
    let (complete, _): (Complete, _) = read_json(&candidate.join("complete.json"))?;
    if complete.schema_version != 1 || !complete.ok || complete.backend != crate::research_streaming_route::backend(&p.operation).ok_or("INVALID_DIR_COMPLETE")? || complete.layout != super::LAYOUT
        || complete.operation != p.operation
        || complete.token != token
        || complete.manifest_sha256 != manifest_sha256
        || complete.width != p.width
        || complete.height != p.height
        || complete.plane_sha256.iter().any(|x| !valid_hash(x))
    {
        return Err("INVALID_DIR_COMPLETE".into());
    }
    let paths = std::array::from_fn(|ch| candidate.join(format!("plane-{ch}.f64le")));
    for ch in 0..3 {
        if plane_hash(&paths[ch], p.width as u64 * p.height as u64, &mut cancelled)?
            != complete.plane_sha256[ch]
        {
            return Err("INVALID_DIR_COMPLETE".into());
        }
    }
    check(&mut cancelled)?;
    Ok(paths)
}
fn verify_sources(dir:&Path,m:&Manifest,p:&StagePlan,c:&mut impl FnMut()->bool)->R<()> {
    for ch in 0..3 {if crate::research_streaming_route::file_hash(&dir.join(format!("source-{ch}.f64le")),p.width as u64*p.height as u64*8,c)?!=m.source_sha256[ch] {return Err("INVALID_STREAM_SOURCE_HASH".into());}}
    Ok(())
}

pub fn run_stage(
    dir: &Path,
    token: u64,
    budget_bytes: u64,
    manifest_sha256: &str,
    mut cancelled: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    if !SOURCE_ADMITTED { return Err("GPU_DIR_STREAM_PENDING".into()); }
    let stage_started = std::time::Instant::now();
    check(&mut cancelled)?;
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != manifest_sha256 {
        return Err("INVALID_DIR_MANIFEST".into());
    }
    if budget_bytes < p.combined_peak_bytes || budget_bytes > MEMORY_CAP {
        return Err("INSUFFICIENT_GPU_DIR_MEMORY".into());
    }
    verify_sources(dir,&m,&p,&mut cancelled)?;
    #[cfg(not(feature = "gpu-diffusion-research"))]
    {
        let _ = (m, p, stage_started);
        Err("GPU_DIR_UNSUPPORTED".into())
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
        let mut gpu = super::StreamingDir::new(&m.dir_config, &mut cancelled)?;
        let mut result = gpu.run_files(
            sources.each_ref().map(|x| x.as_path()),
            outputs.each_ref().map(|x| x.as_path()),
            &dir.join("scratch"),
            &mut cancelled,
            &mut |_, _| {},
        )?;
        drop(gpu); // The outer native seal is acquired only after this lexical owner closes.
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
            || (m.dir_config.log_correction().is_some() && result["logSampleCount"].as_u64() != Some((9 + if p.dir_active {3} else {0}) * samples))
        {
            return Err("GPU_DIR_NUMERIC_CHECK".into());
        }
        result["pointwiseToleranceAbsolute"]=crate::research_dir::POINT_TOLERANCE.into();
        result["pointwiseToleranceRelative"]=crate::research_dir::POINT_TOLERANCE.into();
        publish(dir, &m, &p, &mut cancelled)?;
        check(&mut cancelled)?;
        result = crate::research_streaming_route::summary(result, &p,
            if m.dir_config.log_correction().is_some() { "gpu_exposure_log_dir_stream_f32_v1" } else { "gpu_dir_stream_f32_v1" })?;
        guard.1 = true;
        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn manifest(log:bool)->Manifest {
        Manifest {schema_version:1,layout:super::super::LAYOUT.into(),operation:if log{LOG_DIR_OPERATION}else{DIR_OPERATION}.into(),token:71,
            source_sha256:std::array::from_fn(|_| "a".repeat(64)),
            dir_config:StreamDirPlan::from_plan(super::super::tests::fixture(8192,6144,true),log.then_some(0.73)).unwrap()}
    }
    #[test] fn native_stream_dir_plans_preserve_full_geometry_and_budgets() {
        for log in [false,true] {let m=manifest(log);let sha=hex(&Sha256::digest(serde_json::to_vec(&m).unwrap()));let p=make_plan(&m,sha).unwrap();let memory=m.dir_config.memory().unwrap();
            assert_eq!((p.width,p.height),(8192,6144));assert_eq!(p.device_bytes,32*crate::research_spatial::MIB+65536);
            assert_eq!(p.combined_peak_bytes,memory.combined_peak_bytes);assert_eq!(p.disk_bytes,68*8192*6144+2*crate::research_spatial::MIB);
            assert_eq!(p.chunk_pixels,65536);assert_eq!(p.chunk_count,768);
            if let Some(dir)=std::env::var_os("FILMROOM_STREAM_PLAN_FIXTURE_DIR") {std::fs::create_dir_all(&dir).unwrap();std::fs::write(PathBuf::from(dir).join(if log{"exposure-log-dir-stream-v1.json"}else{"dir-stream-v1.json"}),serde_json::to_vec_pretty(&crate::research_streaming_route::plan_envelope(&p).unwrap()).unwrap()).unwrap();}
        }
    }
    #[test] fn dir_wrapper_rejects_old_layout_unknown_fields_and_wrong_prefix_operation() {
        let m=manifest(false);let mut value=serde_json::to_value(&m).unwrap();value["unknown"]=true.into();assert!(serde_json::from_value::<Manifest>(value).is_err());
        for field in ["schemaVersion","layout","operation"] {let mut value=serde_json::to_value(&m).unwrap();value[field]=match field{"schemaVersion"=>2.into(),"layout"=>"resident".into(),_=>LOG_DIR_OPERATION.into()};let bad:Manifest=serde_json::from_value(value).unwrap();assert!(make_plan(&bad,"a".repeat(64)).is_err());}
    }
    #[test] fn dir_source_gate_does_not_touch_files_or_execute_cancel_callback() {
        let mut called=false;assert_eq!(run_stage(Path::new("not-a-packet"),1,1,"",||{called=true;false}).unwrap_err(),"GPU_DIR_STREAM_PENDING");assert!(!called);
    }
    #[test] fn packet_sources_and_all_three_complete_output_hashes_are_authoritative() {
        let root=std::env::temp_dir().join(format!("stream-dir-packet-{}",std::process::id()));
        let _=fs::remove_dir_all(&root);fs::create_dir(&root).unwrap();let root=root.canonicalize().unwrap();
        let sources:[PathBuf;3]=std::array::from_fn(|ch|root.join(format!("input-{ch}")));
        for path in &sources {fs::write(path,[0.1f64,0.2,0.3,0.4].into_iter().flat_map(f64::to_le_bytes).collect::<Vec<_>>()).unwrap();}
        let dir=root.join("gpu-stage-74");let descriptor=StreamDirPlan::from_plan(super::super::tests::fixture(2,2,false),Some(0.73)).unwrap();
        let p=prepare_stage(sources.each_ref().map(|p|p.as_path()),&dir,74,2,2,&descriptor,||false).unwrap();let(m,_)=load(&dir,74).unwrap();
        verify_sources(&dir,&m,&p,&mut||false).unwrap();assert_eq!(verify_sources(&dir,&m,&p,&mut||true).unwrap_err(),"CANCELLED");
        let candidate=dir.join("candidate");fs::create_dir(&candidate).unwrap();for ch in 0..3 {fs::copy(&sources[ch],candidate.join(format!("plane-{ch}.f64le"))).unwrap();}
        publish(&dir,&m,&p,&mut||false).unwrap();assert!(validate_outputs(&dir,74,&p.manifest_sha256,||false).is_ok());
        fs::write(candidate.join("plane-2.f64le"),[0.9f64;4].into_iter().flat_map(f64::to_le_bytes).collect::<Vec<_>>()).unwrap();
        assert!(validate_outputs(&dir,74,&p.manifest_sha256,||false).is_err());
        let input=dir.join("source-1.f64le");fs::remove_file(&input).unwrap();fs::write(&input,[0.8f64;4].into_iter().flat_map(f64::to_le_bytes).collect::<Vec<_>>()).unwrap();
        assert!(plan_stage(&dir,74).is_ok());assert_eq!(verify_sources(&dir,&m,&p,&mut||false).unwrap_err(),"INVALID_STREAM_SOURCE_HASH");
        fs::remove_dir_all(root).unwrap();
    }

    #[cfg(feature="gpu-diffusion-research")]
    #[test]
    #[ignore="fresh process and private output directory: actual Vulkan graph, native seal, raw Android roundtrip fixture"]
    fn actual_vulkan_log_dir_emits_android_raw_fixture_after_native_seal() {
        use crate::gpu_session as session;use crate::gpu_session_contract::StageTicket;
        let out=PathBuf::from(std::env::var_os("FILMROOM_STREAM_RAW_SUMMARY_FIXTURE_DIR").expect("set raw fixture dir"));fs::create_dir_all(&out).unwrap();let out=out.canonicalize().unwrap();
        let base=out.join("exposure-log-dir-stream-v1.stage");fs::create_dir(&base).expect("fresh fixture directory required");
        let(w,h)=(257,257);let n=w as usize*h as usize;let inputs:[PathBuf;3]=std::array::from_fn(|ch|base.join(format!("input-{ch}.f64le")));
        for(ch,path)in inputs.iter().enumerate(){fs::write(path,(0..n).flat_map(|i|(0.1+(i%97)as f64/101.+ch as f64*0.01).to_le_bytes()).collect::<Vec<_>>()).unwrap();}
        let typed=StreamDirPlan::from_plan(super::super::tests::fixture(w,h,true),Some(0.73)).unwrap();let dir=base.join("gpu-stage-7541");
        let p=prepare_stage(inputs.each_ref().map(|p|p.as_path()),&dir,7541,w,h,&typed,||false).unwrap();let(m,_)=load(&dir,7541).unwrap();let memory=typed.memory().unwrap();
        let _software=session::software_probe_scope();let ticket=StageTicket{epoch:9541,generation:1,sequence:1};
        session::activate(ticket.epoch,memory.combined_peak_bytes,memory.host_bytes+memory.helper_allowance_bytes,memory.pipeline_count as usize).unwrap();session::begin_frame(ticket.epoch,1).unwrap();session::begin_stage(ticket,memory.driver_allowance_bytes).unwrap();
        let candidate=dir.join("candidate");fs::create_dir(&candidate).unwrap();let outputs:[PathBuf;3]=std::array::from_fn(|ch|candidate.join(format!("plane-{ch}.f64le")));let sources:[PathBuf;3]=std::array::from_fn(|ch|dir.join(format!("source-{ch}.f64le")));
        let mut gpu=super::super::StreamingDir::new_probe(&typed,&mut||false).unwrap();assert!(session::complete_stage(ticket).is_err());
        let mut raw=gpu.run_files(sources.each_ref().map(|p|p.as_path()),outputs.each_ref().map(|p|p.as_path()),&dir.join("scratch"),&mut||false,&mut|_,_|{}).unwrap();drop(gpu);
        raw["pointwiseToleranceAbsolute"]=crate::research_dir::POINT_TOLERANCE.into();raw["pointwiseToleranceRelative"]=crate::research_dir::POINT_TOLERANCE.into();
        let samples=8*(p.chunk_count-1)+(w as u64*h as u64-(p.chunk_count-1)*p.chunk_pixels as u64).min(8);
        assert_eq!(raw["sampleCount"].as_u64(),Some(p.pass_count*(w as u64+h as u64)+6*samples));assert_eq!(raw["logSampleCount"].as_u64(),Some(12*samples));
        publish(&dir,&m,&p,&mut||false).unwrap();let raw=crate::research_streaming_route::summary(raw,&p,"gpu_exposure_log_dir_stream_f32_v1").unwrap();
        session::complete_stage(ticket).unwrap();session::finish_frame(ticket.epoch,1).unwrap();validate_outputs(&dir,7541,&p.manifest_sha256,||false).unwrap();
        let envelope=serde_json::json!({"plan":crate::research_streaming_route::plan_envelope(&p).unwrap(),"nativeResult":raw});
        fs::write(out.join("exposure-log-dir-stream-v1-raw.json"),serde_json::to_vec_pretty(&envelope).unwrap()).unwrap();
    }
    #[test]
    #[ignore="requires output from actual Vulkan producer and actual Android sanitize/encode/parse test"]
    fn actual_android_safe_summary_roundtrip_adopts_only_complete_hashed_packet() {
        let raw_dir=PathBuf::from(std::env::var_os("FILMROOM_STREAM_RAW_SUMMARY_FIXTURE_DIR").expect("set raw fixture dir"));let safe_dir=PathBuf::from(std::env::var_os("FILMROOM_STREAM_SAFE_SUMMARY_FIXTURE_DIR").expect("set safe fixture dir"));
        let safe:serde_json::Value=serde_json::from_slice(&fs::read(safe_dir.join("exposure-log-dir-stream-v1-safe.json")).unwrap()).unwrap();
        assert_eq!(safe.as_object().unwrap().keys().cloned().collect::<std::collections::BTreeSet<_>>(),["plan","result"].into_iter().map(str::to_owned).collect::<std::collections::BTreeSet<_>>());
        let dir=raw_dir.canonicalize().unwrap().join("exposure-log-dir-stream-v1.stage/gpu-stage-7541");let p=plan_stage(&dir,7541).unwrap();assert_eq!(safe["plan"],crate::research_streaming_route::plan_envelope(&p).unwrap());
        let result=&safe["result"];let accepted=|v:&serde_json::Value|v["schemaVersion"]==1&&v["code"]=="OK"&&v.as_object().is_some_and(|o|o.len()==3&&o.contains_key("schemaVersion")&&o.contains_key("code")&&o.contains_key("summary"))&&crate::research_streaming_route::valid_summary_identity(&v["summary"],&p.operation,p.token,&p.manifest_sha256,p.width,p.height);
        assert!(accepted(result));
        let total=p.width as u64*p.height as u64*3;
        let poll=serde_json::json!({"schemaVersion":2,"epoch":9541,"frameGeneration":1,"sequence":1,"token":p.token,"status":"success_sealed","completedPixels":total,"totalPixels":total,"code":result["code"],"summary":result["summary"]});
        let parsed=crate::streamed::session_success_summary_for_test(&poll.to_string(),9541,1,1,p.token,total).unwrap();
        assert!(crate::research_streaming_route::valid_summary_identity(&parsed,&p.operation,p.token,&p.manifest_sha256,p.width,p.height));
        assert!(crate::streamed::session_success_summary_for_test(&poll.to_string(),9542,1,1,p.token,total).is_none());
        for code in ["CANCELLED","NATIVE_FAILED","COMPLETE"]{let mut bad=poll.clone();bad["code"]=code.into();assert!(crate::streamed::session_success_summary_for_test(&bad.to_string(),9541,1,1,p.token,total).is_none());}
        let mut non_success=result.clone();non_success["code"]="CANCELLED".into();assert!(!accepted(&non_success));
        for value in [serde_json::Value::Bool(false),serde_json::Value::Bool(true),serde_json::Value::Null] {let mut bad=result.clone();bad["summary"]["ok"]=value;assert!(!accepted(&bad));}
        validate_outputs(&dir,p.token,&p.manifest_sha256,||false).unwrap();
        let complete=dir.join("candidate/complete.json");let original=fs::read(&complete).unwrap();let mut done:serde_json::Value=serde_json::from_slice(&original).unwrap();done["ok"]=false.into();fs::write(&complete,serde_json::to_vec(&done).unwrap()).unwrap();assert!(validate_outputs(&dir,p.token,&p.manifest_sha256,||false).is_err());fs::write(&complete,original).unwrap();
    }

}
