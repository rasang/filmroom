//! One isolated helper, one device, no full logXYZ intermediate or host transfer.
use super::*;
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase, PhaseGuard};
use crate::research_finish_tail::{self as tail, TailOptions, TailPlan};
use spektrafilm_core::pipeline::GpuScanTailDescriptor;
const OPERATION: &str = "fullFinishTail";
mod pointwise;

/// Prunes unreachable operations and defers numeric flag publication until each
/// invocation returns; scientific bodies and arithmetic order remain unchanged.
pub(crate) fn pointwise_shader(p: &TailPlan) -> R<Option<String>> {
    pointwise::shader(p)
}
pub(crate) fn standalone_pointwise_shader(p: &TailPlan) -> R<Option<String>> {
    pointwise::standalone_shader(p)
}
pub(crate) fn full_private_shader(source: &str) -> R<String> {
    pointwise::full_private_numeric_flag(source)
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema_version: u32,
    operation: String,
    token: u64,
    prefix_sha256: String,
    tail: TailPlan,
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
    if m.schema_version != 1
        || m.operation != OPERATION
        || m.token != p.token
        || !hash_ok(&sha)
        || m.prefix_sha256 != p.manifest_sha256
        || m.tail.width != p.width
        || m.tail.height != p.height
        || p.mode == "printExposureOnly"
    {
        return err("INVALID_FUSION_MANIFEST");
    }
    let device = m.tail.device_bytes + 4 * p.table_scalars + 256;
    let host = m.tail.host_bytes + 16 * p.table_scalars + 8 * MIB;
    // One finish pipeline: policy allowance, not a measured driver cap.
    let pointwise = m.tail.options.glare.is_none()
        && m.tail.options.lens_sigma == 0.
        && !(m.tail.options.sharp_radius > 0. && m.tail.options.sharp_amount > 0.);
    let driver = if pointwise {
        64 * MIB
    } else {
        m.tail.driver_allowance_bytes
    };
    let combined = device + host + driver + 64 * MIB;
    if combined > crate::research_spatial::MEMORY_CAP {
        return err("GPU_FUSION_MEMORY_LIMIT");
    }
    let n = p.width as u64 * p.height as u64;
    let chunks = n.div_ceil(tail::CHUNK_PIXELS as u64);
    let samples = 3 * (8 * (chunks - 1) + (n - (chunks - 1) * tail::CHUNK_PIXELS as u64).min(8));
    Ok(StagePlan {
        schema_version: 1,
        operation: OPERATION.into(),
        token: m.token,
        manifest_sha256: sha,
        width: p.width,
        height: p.height,
        mode: p.mode.clone(),
        table_scalars: p.table_scalars,
        pass_count: m.tail.pass_count(),
        sample_count: m.tail.sample_count() + samples,
        device_bytes: device,
        host_bytes: host,
        driver_allowance_bytes: driver,
        helper_allowance_bytes: 64 * MIB,
        combined_peak_bytes: combined,
        disk_bytes: 36 * n + 8 * p.table_scalars + 3 * MIB,
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
        operation: OPERATION.into(),
        token: 1,
        prefix_sha256: p.manifest_sha256.clone(),
        tail: TailPlan::new(t, w, h, o)?,
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
    let tail = TailPlan::new(t, w, h, o)?;
    crate::gpu_stage_staging::create_stage_dir(dir)?;
    let mut guard = Owned(dir.into(), false);
    io(fs::create_dir(dir.join("prefix")))?;
    let p = super::prepare_stage(sources, &prefix_dir(dir, token), token, w, h, d, &mut c)?;
    let m = Manifest {
        schema_version: 1,
        operation: OPERATION.into(),
        token,
        prefix_sha256: p.manifest_sha256.clone(),
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
    let (_, p) = read_manifest(dir, token)?;
    if p.manifest_sha256 != sha {
        return err("INVALID_FUSION_IDENTITY");
    }
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
    if done.schema_version != 1
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
fn run_inner(
    dir: &Path,
    token: u64,
    budget: u64,
    sha: &str,
    mut c: impl FnMut() -> bool,
    mirror_host: bool,
) -> R<serde_json::Value> {
    check(&mut c)?;
    let (m, p) = read_manifest(dir, token)?;
    if p.manifest_sha256 != sha {
        return err("INVALID_FUSION_IDENTITY");
    }
    if budget < p.combined_peak_bytes {
        return err("INSUFFICIENT_GPU_FUSION_MEMORY");
    }
    let mut prefix = FinishPrefix::load(&prefix_dir(dir, token), token, &mut c)?;
    let candidate = dir.join("candidate");
    io(fs::create_dir(&candidate))?;
    let mut guard = Owned(candidate.clone(), false);
    let output = candidate.join("candidate.rgb.f32le");
    let mut result: serde_json::Value = if mirror_host {
        #[cfg(test)]
        {
            host_mirror(&m.tail, &mut prefix, &output, &mut c)?
        }
        #[cfg(not(test))]
        {
            return err("GPU_FUSION_UNSUPPORTED");
        }
    } else {
        #[cfg(feature = "gpu-diffusion-research")]
        {
            tail::gpu::run_files_with_prefix(&m.tail, &output, &mut c, &mut prefix)?
        }
        #[cfg(not(feature = "gpu-diffusion-research"))]
        {
            return err("GPU_FUSION_UNSUPPORTED");
        }
    };
    let commit_phase = PhaseGuard::begin(GpuPhase::Commit);
    let done = Complete {
        schema_version: 1,
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
    let o = result.as_object_mut().ok_or("INVALID_FUSION_REPORT")?;
    for (k, v) in [
        ("operation", OPERATION.into()),
        ("token", token.into()),
        ("schemaVersion", 1.into()),
        ("ok", true.into()),
        ("manifestSha256", sha.into()),
        ("width", p.width.into()),
        ("height", p.height.into()),
        ("channels", 3.into()),
        ("deviceBytes", p.device_bytes.into()),
        ("combinedPeakBytes", p.combined_peak_bytes.into()),
        (
            "backend",
            if mirror_host {
                "host f32 fused mirror; not GPU timing"
            } else {
                "Vulkan f32 resident print scanner tail"
            }
            .into(),
        ),
        ("prefixSampleToleranceAbsolute", 2e-5.into()),
        ("prefixSampleToleranceRelative", 2e-5.into()),
        ("residentFinishTail", true.into()),
        ("intermediateImageReadbackBytes", 0.into()),
    ] {
        o.insert(k.into(), v);
    }
    guard.1 = true;
    Ok(result)
}
/// Immutable LUT/calibration and bounded planar ingress. Never contains an image.
pub(crate) struct FinishPrefix {
    manifest: super::Manifest,
    tables: Tables,
    storage: Vec<f32>,
    sources: Vec<std::io::BufReader<File>>,
    remaining: u64,
    last: Vec<f64>,
    pub(crate) sample_sum: f64,
    pub(crate) sample_max: f64,
}
impl FinishPrefix {
    pub(crate) fn load(dir: &Path, token: u64, c: &mut impl FnMut() -> bool) -> R<Self> {
        trace::phase_result(GpuPhase::Prepare, || {
            let (m, _) = super::load(dir, token)?;
            if m.mode == "printExposureOnly" {
                return err("GPU_FUSION_UNSUPPORTED");
            }
            let tables = Tables::load(dir, &m, c)?;
            let flat = tables.flat32();
            // Bound every uploaded table and interpolation divisor before any GPU arithmetic.
            if flat.iter().any(|x| x.abs() > 1e10)
                || [m.exposure, m.bw].iter().any(|x| x.abs() > 1e10)
            {
                return err("GPU_FUSION_NUMERIC_DOMAIN");
            }
            for s in m.printing.iter().chain(std::iter::once(&m.scanner)) {
                for ch in 0..3 {
                    let range = s.max[ch] as f32 - s.min[ch] as f32;
                    if range < 1e-10
                        || range > 1e10
                        || s.min[ch].abs() > 1e10
                        || s.max[ch].abs() > 1e10
                    {
                        return err("GPU_FUSION_NUMERIC_DOMAIN");
                    }
                }
            }
            if tables
                .axis
                .windows(2)
                .any(|x| (x[1] as f32 - x[0] as f32) < 1e-10)
            {
                return err("GPU_FUSION_NUMERIC_DOMAIN");
            }
            let mut h = [0u32; 64];
            h[1] = u32::from(m.mode == "printFromDensity");
            for (base, s) in m
                .printing
                .iter()
                .map(|s| (4, s))
                .chain(std::iter::once((20, &m.scanner)))
            {
                h[base] = s.size;
                h[base + 1] = s.offset as u32;
                for ch in 0..3 {
                    h[base + 2 + ch] = (s.min[ch] as f32).to_bits();
                    h[base + 5 + ch] = (s.max[ch] as f32).to_bits();
                }
            }
            h[36] = m.axis_offset as u32;
            h[37] = m.curve_count;
            h[38] = (m.exposure as f32).to_bits();
            h[39] = (m.bw as f32).to_bits();
            let mut storage = Vec::with_capacity(64 + flat.len());
            storage.extend(h.map(f32::from_bits));
            storage.extend(flat);
            let sources = (0..3)
                .map(|ch| {
                    Ok(std::io::BufReader::with_capacity(
                        65536,
                        io(File::open(dir.join(format!("source-{ch}.f64le"))))?,
                    ))
                })
                .collect::<R<Vec<_>>>()?;
            let remaining = m.width as u64 * m.height as u64;
            Ok(Self {
                manifest: m,
                tables,
                storage,
                sources,
                remaining,
                last: Vec::new(),
                sample_sum: 0.,
                sample_max: 0.,
            })
        })
    }
    pub(crate) fn stream_identity(&self) -> (u32, u32, u64, u64) {
        (self.manifest.width, self.manifest.height, self.manifest.table_scalars, self.remaining)
    }
    pub(crate) fn storage(&self) -> &[f32] {
        &self.storage
    }
    pub(crate) fn shader(&self, source: &str) -> R<String> {
        fused_shader(source)
    }
    pub(crate) fn read_chunk(&mut self, count: usize) -> R<Vec<f32>> {
        if count == 0 || count as u64 > self.remaining || count > tail::CHUNK_PIXELS {
            return err("INVALID_FUSION_CHUNK");
        }
        self.last = vec![0.; count * 3];
        for ch in 0..3 {
            for i in 0..count {
                let mut b = [0u8; 8];
                io(self.sources[ch].read_exact(&mut b))?;
                let x = f64::from_le_bytes(b);
                if !x.is_finite() || x.abs() > 1e15 {
                    return err("NONFINITE_FUSION_INPUT");
                }
                self.last[3 * i + ch] = x;
            }
        }
        self.remaining -= count as u64;
        Ok(self.last.iter().map(|&x| x as f32).collect())
    }
    pub(crate) fn witness(&mut self, index: usize, actual: [f32; 3]) -> R<[f32; 3]> {
        let x = &self.last[index * 3..index * 3 + 3];
        let expected = self.tables.reference(&self.manifest, [x[0], x[1], x[2]]);
        for ch in 0..3 {
            if !actual[ch].is_finite()
                || !expected[ch].is_finite()
                || (actual[ch] as f64 - expected[ch]).abs() > 2e-5 * expected[ch].abs().max(1.)
            {
                return err("GPU_FUSION_PREFIX_NUMERIC_CHECK");
            }
            let e = (actual[ch] as f64 - expected[ch]).abs();
            self.sample_sum += e * e;
            self.sample_max = self.sample_max.max(e);
        }
        Ok(actual)
    }
    pub(crate) fn complete(&self) -> R<()> {
        if self.remaining == 0 {
            Ok(())
        } else {
            err("INVALID_FUSION_LENGTH")
        }
    }
}
pub fn fused_shader(source: &str) -> R<String> {
    let marker = " if(op>=30u){glare_main(i);return;}";
    if source.matches(marker).count() != 1 {
        return err("GPU_FUSION_SHADER_VERSION");
    }
    Ok(source.replace(
        marker,
        " if(op==40u){finish_main(i);return;}\n if(op>=30u){glare_main(i);return;}",
    ) + include_str!("../shaders/research_finish_fused.wgsl"))
}

#[cfg(test)]
fn host_mirror(
    p: &TailPlan,
    prefix: &mut FinishPrefix,
    out: &Path,
    c: &mut impl FnMut() -> bool,
) -> R<serde_json::Value> {
    let n = p.width as usize * p.height as usize;
    if n > 1048576 {
        return err("TEST_MIRROR_MEMORY_LIMIT");
    }
    let mut logs = Vec::with_capacity(n);
    while logs.len() < n {
        check(c)?;
        let count = (n - logs.len()).min(tail::CHUNK_PIXELS);
        let input = prefix.read_chunk(count)?;
        for x in input.chunks_exact(3) {
            logs.push(super::mirror(
                &prefix.storage[64..],
                &prefix.manifest,
                [x[0], x[1], x[2]],
            ));
        }
    }
    prefix.complete()?;
    let glare = if let Some(g) = &p.options.glare {
        let mut sampler = spektrafilm_model::glare::GlareSamples::new(g.percent, g.roughness, 42)
            .map_err(|e| e.to_string())?;
        Some((0..n).map(|_| sampler.sample() as f32).collect::<Vec<_>>())
    } else {
        None
    };
    let pixels = tail::image_mirror(p, &logs, glare.as_deref(), c)?;
    let mut file = io(OpenOptions::new().write(true).create_new(true).open(out))?;
    for pixel in pixels {
        for value in pixel {
            io(file.write_all(&value.to_le_bytes()))?;
        }
    }
    io(file.sync_all())?;
    Ok(
        serde_json::json!({"gpuUsed":false,"backend":"host f32 fused mirror; not GPU timing","fullImage":true}),
    )
}
#[cfg(test)]
mod tests;
