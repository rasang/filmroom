//! One isolated helper, one device, no full logXYZ intermediate or host transfer.
use super::*;
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase, PhaseGuard};
use crate::research_finish_tail::TailOptions;
use crate::research_streaming_finish::StreamFinishPlan;
use super::fused::FinishPrefix;
use spektrafilm_core::pipeline::GpuScanTailDescriptor;
pub const OPERATION: &str = "fullFinishTailStreamV1";
pub const SOURCE_ADMITTED: bool = false;
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema_version: u32,
    layout: String,
    operation: String,
    token: u64,
    prefix_sha256: String,
    source_sha256: [String;3],
    tail: StreamFinishPlan,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StagePlan {
    pub schema_version: u32,
    pub layout: String,
    pub external_glare: bool,
    pub glare_active: bool,
    pub operation: String,
    pub token: u64,
    pub manifest_sha256: String,
    pub width: u32,
    pub height: u32,
    pub mode: String,
    pub table_scalars: u64,
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
fn prefix_dir(dir: &Path, token: u64) -> PathBuf {
    dir.join("prefix").join(format!("gpu-stage-{token}"))
}
fn plan(m: &Manifest, p: &FinishPlan, sha: String) -> R<StagePlan> {
    m.tail.validate()?;
    if m.schema_version != 1 || m.layout != crate::research_streaming_finish::LAYOUT
        || m.operation != OPERATION
        || m.token != p.token
        || !hash_ok(&sha) || m.source_sha256.iter().any(|h| !hash_ok(h))
        || m.prefix_sha256 != p.manifest_sha256
        || m.tail.width() != p.width
        || m.tail.height() != p.height
        || p.mode == "printExposureOnly"
        || m.tail.external_glare() || m.tail.prefix_table_scalars() != Some(p.table_scalars)
    {
        return err("INVALID_FUSION_MANIFEST");
    }
    let memory = m.tail.memory()?;
    Ok(StagePlan {
        schema_version: 1, layout: crate::research_streaming_finish::LAYOUT.into(), external_glare: false, glare_active: m.tail.parameters().options.glare.is_some(),
        operation: OPERATION.into(), token: m.token, manifest_sha256: sha, width: p.width, height: p.height,
        mode: p.mode.clone(), table_scalars: p.table_scalars, pass_count: m.tail.parameters().pass_count(),
        sample_count: m.tail.sample_count(), device_bytes: memory.device_bytes, host_bytes: memory.host_bytes,
        driver_allowance_bytes: memory.driver_allowance_bytes, helper_allowance_bytes: memory.helper_allowance_bytes,
        combined_peak_bytes: memory.combined_peak_bytes, disk_bytes: memory.disk_bytes,
    })
}

fn read_manifest(dir: &Path, token: u64) -> R<(Manifest, StagePlan)> {
    owner(dir, token)?;
    let meta = io(fs::symlink_metadata(dir))?;
    if !meta.is_dir() || meta.file_type().is_symlink() || io(dir.canonicalize())? != dir {
        return err("INVALID_FUSION_OWNER");
    }
    let path = dir.join("manifest.json");
    let meta = io(fs::symlink_metadata(&path))?;
    if !meta.is_file() || meta.file_type().is_symlink() || meta.len() > 2 * MIB {
        return err("INVALID_FUSION_MANIFEST");
    }
    let bytes = io(fs::read(path))?;
    let m: Manifest = serde_json::from_slice(&bytes).map_err(|_| "INVALID_FUSION_MANIFEST")?;
    let prefix = dir.join("prefix");
    let pm = io(fs::symlink_metadata(&prefix))?;
    if !pm.is_dir() || pm.file_type().is_symlink() || io(prefix.canonicalize())? != prefix {
        return err("INVALID_FUSION_OWNER");
    }
    let p = super::plan_stage(&prefix_dir(dir, token), token)?;
    let p = plan(&m, &p, hex(&Sha256::digest(&bytes)))?;
    Ok((m, p))
}
pub(crate) fn requires_streaming(w:u32,h:u32,d:&GpuFinishDescriptor,t:&GpuScanTailDescriptor<'_>,o:TailOptions)->R<bool> {
    let semantic=crate::research_finish_tail::TailPlan::new(t,1,1,o)?;
    let prefix=descriptor_manifest(d,w,h,1)?;
    let p=make_plan(&prefix,"0".repeat(64))?;
    if p.mode=="printExposureOnly" {return err("GPU_FUSION_UNSUPPORTED");}
    crate::research_streaming_route::needs_streaming_finish(&semantic,w,h,Some(p.table_scalars))
}

pub fn estimated_disk_bytes(
    w: u32,
    h: u32,
    d: &GpuFinishDescriptor,
    t: &GpuScanTailDescriptor<'_>,
    o: TailOptions,
) -> R<u64> {
    let f = descriptor_manifest(d, w, h, 1)?;
    let p = make_plan(&f, "0".repeat(64))?;
    let m = Manifest {
        schema_version: 1,
        layout: crate::research_streaming_finish::LAYOUT.into(),
        operation: OPERATION.into(),
        token: 1,
        prefix_sha256: p.manifest_sha256.clone(),
        source_sha256:std::array::from_fn(|_| "0".repeat(64)),
        tail: StreamFinishPlan::new(t, w, h, o, Some(p.table_scalars), false)?,
    };
    Ok(plan(&m, &p, "0".repeat(64))?.disk_bytes)
}
pub fn prepare_stage(
    sources: [&Path; 3],
    dir: &Path,
    token: u64,
    w: u32,
    h: u32,
    d: &GpuFinishDescriptor,
    t: &GpuScanTailDescriptor<'_>,
    o: TailOptions,
    mut c: impl FnMut() -> bool,
) -> R<StagePlan> {
    check(&mut c)?;
    owner(dir, token)?;
    let semantic = descriptor_manifest(d, w, h, token)?;
    let pp = make_plan(&semantic, "0".repeat(64))?;
    let tail = StreamFinishPlan::new(t, w, h, o, Some(pp.table_scalars), false)?;
    crate::gpu_stage_staging::create_stage_dir(dir)?;
    let mut guard = Owned(dir.into(), false);
    io(fs::create_dir(dir.join("prefix")))?;
    let p = super::prepare_stage(sources, &prefix_dir(dir, token), token, w, h, d, &mut c)?;
    let mut source_sha256=std::array::from_fn(|_| String::new());
    for ch in 0..3 {source_sha256[ch]=crate::research_streaming_route::file_hash(&prefix_dir(dir,token).join(format!("source-{ch}.f64le")),w as u64*h as u64*8,&mut c)?;}
    let m = Manifest {
        schema_version: 1,
        layout: crate::research_streaming_finish::LAYOUT.into(),
        operation: OPERATION.into(),
        token,
        prefix_sha256: p.manifest_sha256.clone(),
        source_sha256,
        tail,
    };
    let bytes = serde_json::to_vec(&m).map_err(|_| "INVALID_FUSION_MANIFEST")?;
    let p = plan(&m, &p, hex(&Sha256::digest(&bytes)))?;
    let mut f = io(OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(dir.join(".manifest.partial")))?;
    io(f.write_all(&bytes))?;
    io(f.sync_all())?;
    check(&mut c)?;
    io(fs::rename(
        dir.join(".manifest.partial"),
        dir.join("manifest.json"),
    ))?;
    guard.1 = true;
    Ok(p)
}
pub fn plan_stage(dir: &Path, token: u64) -> R<StagePlan> {
    Ok(read_manifest(dir, token)?.1)
}
fn output_hash(path: &Path, n: u64, c: &mut impl FnMut() -> bool) -> R<String> {
    regular(path, n * 12)?;
    let mut file = io(File::open(path))?;
    let mut left = n * 12;
    let mut buf = vec![0u8; 65536];
    let mut hash = Sha256::new();
    while left > 0 {
        check(c)?;
        let len = left.min(buf.len() as u64) as usize;
        io(file.read_exact(&mut buf[..len]))?;
        if buf[..len]
            .chunks_exact(4)
            .any(|b| !f32::from_le_bytes(b.try_into().unwrap()).is_finite())
        {
            return err("NONFINITE_FUSION_OUTPUT");
        }
        hash.update(&buf[..len]);
        left -= len as u64;
    }
    Ok(hex(&hash.finalize()))
}
pub fn validate_output(
    dir: &Path,
    token: u64,
    sha: &str,
    mut c: impl FnMut() -> bool,
) -> R<PathBuf> {
    let (m, p) = read_manifest(dir, token)?;
    if p.manifest_sha256 != sha {
        return err("INVALID_FUSION_IDENTITY");
    }
    verify_sources(dir,&m,&p,&mut c)?;
    let candidate = dir.join("candidate");
    let meta = io(fs::symlink_metadata(&candidate))?;
    if !meta.is_dir() || meta.file_type().is_symlink() || io(candidate.canonicalize())? != candidate
    {
        return err("INVALID_FUSION_OUTPUT");
    }
    let complete = candidate.join("complete.json");
    let meta = io(fs::symlink_metadata(&complete))?;
    if !meta.is_file() || meta.file_type().is_symlink() || meta.len() > 65536 {
        return err("INVALID_FUSION_OUTPUT");
    }
    let done: Complete =
        serde_json::from_slice(&io(fs::read(complete))?).map_err(|_| "INVALID_FUSION_OUTPUT")?;
    if done.schema_version != 1 || !done.ok || done.backend != "gpu_finish_tail_stream_f32_v1" || done.layout != crate::research_streaming_finish::LAYOUT
        || done.operation != OPERATION
        || done.token != token
        || done.manifest_sha256 != sha
        || done.width != p.width
        || done.height != p.height
    {
        return err("INVALID_FUSION_OUTPUT");
    }
    let out = candidate.join("candidate.rgb.f32le");
    if output_hash(&out, p.width as u64 * p.height as u64, &mut c)? != done.output_sha256 {
        return err("INVALID_FUSION_OUTPUT");
    }
    Ok(out)
}
fn verify_sources(dir:&Path,m:&Manifest,p:&StagePlan,c:&mut impl FnMut()->bool)->R<()> {
    for ch in 0..3 {if crate::research_streaming_route::file_hash(&prefix_dir(dir,m.token).join(format!("source-{ch}.f64le")),p.width as u64*p.height as u64*8,c)?!=m.source_sha256[ch]{return err("INVALID_STREAM_SOURCE_HASH");}}
    Ok(())
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
fn run_inner(
    dir: &Path,
    token: u64,
    budget: u64,
    sha: &str,
    mut c: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    check(&mut c)?;
    if !SOURCE_ADMITTED { return err("GPU_FINISH_STREAM_PENDING"); }
    let (m, p) = read_manifest(dir, token)?;
    if p.manifest_sha256 != sha {
        return err("INVALID_FUSION_IDENTITY");
    }
    if budget > crate::research_spatial::MEMORY_CAP || budget < p.combined_peak_bytes {
        return err("INSUFFICIENT_GPU_FUSION_MEMORY");
    }
    verify_sources(dir,&m,&p,&mut c)?;
    let prefix = FinishPrefix::load(&prefix_dir(dir, token), token, &mut c)?;
    let candidate = dir.join("candidate");
    io(fs::create_dir(&candidate))?;
    let mut guard = Owned(candidate.clone(), false);
    let output = candidate.join("candidate.rgb.f32le");
    let result: serde_json::Value = {
        #[cfg(feature = "gpu-diffusion-research")]
        {
            let mut owner = crate::research_streaming_finish::gpu::StreamingFinish::new_with_prefix(&m.tail, &prefix, &mut c)?;
            let report = owner.run_with_prefix(prefix, &output, &dir.join("scratch"), &mut c)?;
            drop(owner);
            report
        }
        #[cfg(not(feature = "gpu-diffusion-research"))]
        { return err("GPU_FINISH_STREAM_UNSUPPORTED"); }
    };
    if result["passCount"].as_u64() != Some(p.pass_count) || result["sampleCount"].as_u64() != Some(p.sample_count) {
        return err("GPU_FINISH_STREAM_NUMERIC_CHECK");
    }
    let commit_phase = PhaseGuard::begin(GpuPhase::Commit);
    let done = Complete {
        ok: true,
        backend: ("gpu_finish_tail_stream_f32_v1").into(),
        schema_version: 1,
        layout: crate::research_streaming_finish::LAYOUT.into(),
        operation: OPERATION.into(),
        token,
        manifest_sha256: sha.into(),
        width: p.width,
        height: p.height,
        output_sha256: output_hash(&output, p.width as u64 * p.height as u64, &mut c)?,
    };
    let bytes = serde_json::to_vec(&done).map_err(|_| "INVALID_FUSION_OUTPUT")?;
    let mut file = io(OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(candidate.join(".complete.partial")))?;
    io(file.write_all(&bytes))?;
    io(file.sync_all())?;
    check(&mut c)?;
    io(fs::rename(
        candidate.join(".complete.partial"),
        candidate.join("complete.json"),
    ))?;
    commit_phase.finish();
    let result = crate::research_streaming_route::summary(result, &p, "gpu_finish_tail_stream_f32_v1")?;
    guard.1 = true;
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture()->(Manifest,FinishPlan) {
        let n=4usize;let data=(0..n).flat_map(|x|(0..n).flat_map(move|y|(0..n).flat_map(move|z|[x as f64/3.,y as f64/3.,z as f64/3.]))).collect();
        let lut=spektrafilm_math::pchip3d::prepare_pchip_3d(data,n);
        let d=GpuFinishDescriptor{input:GpuFinishInput::FilmDensity,printing:None,scanner:&lut,scanner_min:[0.;3],scanner_max:[1.;3]};
        let prefix=descriptor_manifest(&d,8192,6144,73).unwrap();let p=make_plan(&prefix,hex(&Sha256::digest(serde_json::to_vec(&prefix).unwrap()))).unwrap();
        let id=[[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]];
        let t=GpuScanTailDescriptor{adapt:id,xyz_to_rgb:id,xyz_remap:None,glare_rgb_offset:[1.;3],gamut:None,rgb_identity:id,output_cctf_encoding:false};
        let options=TailOptions{glare:None,lens_sigma:0.,sharp_radius:0.,sharp_amount:0.,brightness_ev:0.,finalize_cctf:true,preview_output_space:None};
        let tail=StreamFinishPlan::new(&t,8192,6144,options,Some(p.table_scalars),false).unwrap();
        (Manifest{schema_version:1,layout:crate::research_streaming_finish::LAYOUT.into(),operation:OPERATION.into(),token:73,prefix_sha256:p.manifest_sha256.clone(),source_sha256:std::array::from_fn(|_|"a".repeat(64)),tail},p)
    }
    #[test] fn native_stream_fused_plan_keeps_prefix_identity_and_exact_disk_formula() {
        let(m,p)=fixture();let q=plan(&m,&p,hex(&Sha256::digest(serde_json::to_vec(&m).unwrap()))).unwrap();
        assert_eq!((q.width,q.height),(8192,6144));assert_eq!(q.mode,"directFilm");assert_eq!(q.disk_bytes,48*8192*6144+8*p.table_scalars+3*MIB);assert_eq!(q.table_scalars,p.table_scalars);
        if let Some(dir)=std::env::var_os("FILMROOM_STREAM_PLAN_FIXTURE_DIR") {std::fs::create_dir_all(&dir).unwrap();std::fs::write(PathBuf::from(dir).join("finish-tail-stream-v1.json"),serde_json::to_vec_pretty(&crate::research_streaming_route::plan_envelope(&q).unwrap()).unwrap()).unwrap();}
    }
    #[test] fn fused_wrapper_rejects_prefix_hash_mode_and_geometry_mismatch() {
        let(m,p)=fixture();let mut bad=m.clone();bad.prefix_sha256="b".repeat(64);assert!(plan(&bad,&p,"a".repeat(64)).is_err());
        let mut wrong=p.clone();wrong.width-=1;assert!(plan(&m,&wrong,"a".repeat(64)).is_err());wrong=p.clone();wrong.mode="printExposureOnly".into();assert!(plan(&m,&wrong,"a".repeat(64)).is_err());
        let mut v=serde_json::to_value(&m).unwrap();v["unknown"]=true.into();assert!(serde_json::from_value::<Manifest>(v).is_err());
    }
    #[test] fn fused_gate_remains_closed_without_files() {assert_eq!(run_stage(Path::new("unused"),1,1,"",||false).unwrap_err(),"GPU_FINISH_STREAM_PENDING");}
}
