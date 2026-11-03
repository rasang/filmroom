//! Experimental full-image GPU optical stage. Immutable sources, whole-stage commit only.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use spektrafilm_model::diffusion::ExactDiffusionKernel;
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
};
pub const MIB: u64 = 1024 * 1024;
pub const STAGE_MEMORY_CAP: u64 = 768 * MIB;
pub const CHUNK_BYTES: usize = 8 * 1024 * 1024;
type R<T> = Result<T, String>;
// Numeric admission for the actual radix-2 WGSL streaming graph, not a clamp.
// Each complex component starts within X=1024 (source) or K=1 (PSF).
// |twiddle component|<=1: each complex-product component has two products,
// then a butterfly adds/subtracts one even component. A 3.01 growth factor
// dominates 3*(1+2^-23)^3 plus <1e-44 absolute subnormal rounding
// error: the proof envelopes start at M>=1 and only grow.
// N<=4096 gives <=24 butterfly layers per 2D FFT. The two forward transforms
// and inverse give <=72 layers in the product bound. Spectral cmul is bounded
// by 2.01*A*B, dominating 2*(1+2^-23)^2. Thus every unnormalized component is
// <= 2.01*1024*3.01^72 < 5.9e37. Normalization divides by N^2>=1.
// With scatter in [0,1], even allowing an extra factor two for normalization,
// blend and their rounding gives 2*2.01*1024*3.01^72 < 1.18e38 < f32::MAX.
// Intermediate scalar products/sums are bounded by the same next-stage
// envelope; rearrangement/fusion cannot require a larger envelope. Flush-to-
// zero cannot increase this absolute bound. No FFT stage changes coefficients
// or normalizes early. A rejected scalar rejects the entire candidate stage.
// This is overflow admission only, not a claim of f64 numerical equivalence.
pub(super) const FFT_SOURCE_BOUND: f64 = 1024.;
pub(super) fn numeric_scalar(value: f64, bound: f64) -> R<f32> {
    if !value.is_finite() || value.abs() > bound {
        return fail("GPU_STAGE_NUMERIC_DOMAIN");
    }
    Ok(value as f32)
}
pub(super) fn numeric_fft_domain(n: usize, scatter: f64) -> R<()> {
    if !n.is_power_of_two() || n > 4096 || !scatter.is_finite() || !(0. ..=1.).contains(&scatter) {
        return fail("GPU_STAGE_NUMERIC_DOMAIN");
    }
    Ok(())
}

pub(super) fn fail<T>(s: &str) -> R<T> {
    Err(s.into())
}
pub(super) fn io<T>(r: std::io::Result<T>) -> R<T> {
    r.map_err(|_| "GPU_STAGE_IO".into())
}
pub(super) fn check(c: &mut impl FnMut() -> bool) -> R<()> {
    if c() { fail("CANCELLED") } else { Ok(()) }
}
pub(super) fn hex(b: &[u8]) -> String {
    b.iter().map(|v| format!("{v:02x}")).collect()
}
fn valid_hash(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}
fn from_bits(s: &str) -> R<f64> {
    if s.len() != 16
        || !s
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return fail("INVALID_STAGE_MANIFEST");
    };
    let v = f64::from_bits(u64::from_str_radix(s, 16).map_err(|_| "INVALID_STAGE_MANIFEST")?);
    if !v.is_finite() || !(v as f32).is_finite() {
        return fail("INVALID_STAGE_MANIFEST");
    };
    Ok(v)
}
pub(super) fn regular(p: &Path, bytes: u64) -> R<()> {
    let m = io(fs::symlink_metadata(p))?;
    if !m.is_file() || m.file_type().is_symlink() || m.len() != bytes {
        return fail("INVALID_STAGE_FILE");
    };
    Ok(())
}
pub(super) struct OwnedDir(pub PathBuf, pub bool);
impl Drop for OwnedDir {
    fn drop(&mut self) {
        if !self.1 {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct Manifest {
    schema_version: u32,
    pub(super) operation: String,
    pub token: u64,
    pub width: u32,
    pub height: u32,
    pub radius: u32,
    pub fft_side: u32,
    scatter_bits: String,
    pixel_pitch_bits: String,
    family: String,
    pub kernel_sha256: [String; 3],
}
impl Manifest {
    pub fn scatter_f64(&self) -> R<f64> {
        let value = from_bits(&self.scatter_bits)?;
        numeric_fft_domain(self.fft_side as usize, value)?;
        Ok(value)
    }
    pub fn scatter(&self) -> R<f32> {
        Ok(self.scatter_f64()? as f32)
    }
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StagePlan {
    pub schema_version: u32,
    pub operation: String,
    pub token: u64,
    pub manifest_sha256: String,
    pub width: u32,
    pub height: u32,
    pub radius: u32,
    pub fft_side: u32,
    pub core: u32,
    pub tile_count: u64,
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub disk_bytes: u64,
}
fn geometry(w: u32, h: u32, r: usize) -> R<(usize, usize, u64)> {
    if w == 0 || h == 0 || w > 40000 || h > 40000 || r >= 2048 {
        return fail("ORIGINAL_PSF_EXCEEDS_GPU_LIMIT");
    }
    let n = (2 * r + w.max(h) as usize).next_power_of_two().min(4096);
    if n <= 2 * r {
        return fail("ORIGINAL_PSF_EXCEEDS_GPU_LIMIT");
    };
    let core = n - 2 * r;
    if core < 256 && core < w.max(h) as usize {
        return fail("ORIGINAL_PSF_EXCEEDS_GPU_LIMIT");
    }
    let tiles = (w as u64).div_ceil(core as u64) * (h as u64).div_ceil(core as u64) * 3;
    if tiles > 64 {
        return fail("GPU_TILE_COUNT_LIMIT");
    };
    Ok((n, core, tiles))
}
pub fn estimated_disk_bytes(w: u32, h: u32, r: usize) -> R<u64> {
    geometry(w, h, r)?;
    let ks = 2 * r as u64 + 1;
    Ok(6 * w as u64 * h as u64 * 8 + 3 * ks * ks * 8 + 65536)
}
fn make_plan(m: &Manifest, hash: String) -> R<StagePlan> {
    if m.schema_version != 1
        || !["fullCameraDiffusion","fullPrintDiffusion"].contains(&m.operation.as_str())
        || m.token == 0
        || m.token > i64::MAX as u64
        || !["black_pro_mist", "pro_mist", "glimmerglass", "cinebloom"].contains(&m.family.as_str())
        || from_bits(&m.pixel_pitch_bits)? <= 0.
        || m.kernel_sha256.iter().any(|h| !valid_hash(h))
    {
        return fail("INVALID_STAGE_MANIFEST");
    }
    m.scatter()?;
    let (n, core, tiles) = geometry(m.width, m.height, m.radius as usize)?;
    if n != m.fft_side as usize {
        return fail("INVALID_STAGE_MANIFEST");
    };
    let ks = 2 * m.radius as u64 + 1;
    let readback = (CHUNK_BYTES as u64).min(core as u64 * core as u64 * 8);
    let device = 3 * n as u64 * n as u64 * 8 + ks * ks * 4 + n as u64 * 8 + readback + CHUNK_BYTES as u64 + 65536;
    let host = 56 * MIB;
    let combined = device + host + 128 * MIB;
    if combined > STAGE_MEMORY_CAP {
        return fail("GPU_STAGE_MEMORY_LIMIT");
    }
    Ok(StagePlan {
        schema_version: 1,
        operation: m.operation.clone(),
        token: m.token,
        manifest_sha256: hash,
        width: m.width,
        height: m.height,
        radius: m.radius,
        fft_side: n as u32,
        core: core as u32,
        tile_count: tiles,
        device_bytes: device,
        host_bytes: host,
        driver_allowance_bytes: 64 * MIB,
        helper_allowance_bytes: 64 * MIB,
        combined_peak_bytes: combined,
        disk_bytes: estimated_disk_bytes(m.width, m.height, m.radius as usize)?,
    })
}
fn read_json<T: serde::de::DeserializeOwned>(p: &Path) -> R<(T, Vec<u8>)> {
    let meta = io(fs::symlink_metadata(p))?;
    if !meta.is_file() || meta.file_type().is_symlink() || meta.len() > 8192 {
        return fail("INVALID_STAGE_MANIFEST");
    };
    let mut b = Vec::new();
    io(File::open(p))?
        .take(8193)
        .read_to_end(&mut b)
        .map_err(|_| "GPU_STAGE_IO")?;
    if b.len() > 8192 {
        return fail("INVALID_STAGE_MANIFEST");
    };
    let v = serde_json::from_slice(&b).map_err(|_| "INVALID_STAGE_MANIFEST")?;
    Ok((v, b))
}
pub(super) fn load(dir: &Path, token: u64) -> R<(Manifest, StagePlan)> {
    if dir.file_name().and_then(|s| s.to_str()) != Some(format!("gpu-stage-{token}").as_str()) {
        return fail("INVALID_STAGE_OWNER");
    };
    let meta = io(fs::symlink_metadata(dir))?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return fail("INVALID_STAGE_OWNER");
    }
    let mut expected = vec!["manifest.json".to_string()];
    for ch in 0..3 {
        expected.push(format!("source-{ch}.f64le"));
        expected.push(format!("kernel-{ch}.f64le"));
    }
    let mut names = Vec::new();
    for item in io(fs::read_dir(dir))?.take(9) {
        let e = io(item)?;
        let name = e.file_name().to_string_lossy().into_owned();
        if name == "candidate" {
            let t = io(e.file_type())?;
            if !t.is_dir() || t.is_symlink() {
                return fail("INVALID_STAGE_FILE");
            }
        } else {
            names.push(name);
        }
    }
    names.sort();
    expected.sort();
    if names != expected {
        return fail("INVALID_STAGE_FILES");
    }
    let (m, b): (Manifest, _) = read_json(&dir.join("manifest.json"))?;
    if m.token != token {
        return fail("INVALID_STAGE_OWNER");
    };
    let p = make_plan(&m, hex(&Sha256::digest(&b)))?;
    let ks = 2 * m.radius as u64 + 1;
    for ch in 0..3 {
        regular(
            &dir.join(format!("source-{ch}.f64le")),
            m.width as u64 * m.height as u64 * 8,
        )?;
        regular(&dir.join(format!("kernel-{ch}.f64le")), ks * ks * 8)?;
    }
    Ok((m, p))
}
pub fn plan_stage(dir: &Path, token: u64) -> R<StagePlan> {
    load(dir, token).map(|(_, p)| p)
}
#[allow(clippy::too_many_arguments)]
pub fn prepare_stage(sources:[&Path;3],dir:&Path,token:u64,width:u32,height:u32,pitch:f64,family:&str,kernel:&ExactDiffusionKernel,cancelled:impl FnMut()->bool)->R<StagePlan>{prepare_stage_operation("fullCameraDiffusion",sources,dir,token,width,height,pitch,family,kernel,cancelled)}
#[allow(clippy::too_many_arguments)]
pub fn prepare_print_stage(sources:[&Path;3],dir:&Path,token:u64,width:u32,height:u32,pitch:f64,family:&str,kernel:&ExactDiffusionKernel,cancelled:impl FnMut()->bool)->R<StagePlan>{prepare_stage_operation("fullPrintDiffusion",sources,dir,token,width,height,pitch,family,kernel,cancelled)}
fn prepare_stage_operation(
    operation: &str,
    sources: [&Path; 3],
    dir: &Path,
    token: u64,
    width: u32,
    height: u32,
    pitch: f64,
    family: &str,
    kernel: &ExactDiffusionKernel,
    mut cancelled: impl FnMut() -> bool,
) -> R<StagePlan> {
    use crate::gpu_stage_staging::{self,Phase,io_at};
    check(&mut cancelled)?;
    if kernel.descriptor().geometry() != [width as usize, height as usize]
        || kernel.radius > u32::MAX as usize
    {
        return fail("INVALID_STAGE_KERNEL");
    };
    let (n, _, _) = geometry(width, height, kernel.radius)?;
    let mut m = Manifest {
        schema_version: 1,
        operation: operation.into(),
        token,
        width,
        height,
        radius: kernel.radius as u32,
        fft_side: n as u32,
        scatter_bits: format!("{:016x}", kernel.scatter.to_bits()),
        pixel_pitch_bits: format!("{:016x}", pitch.to_bits()),
        family: family.into(),
        kernel_sha256: std::array::from_fn(|_| "0".repeat(64)),
    };
    make_plan(&m, String::new())?;
    if dir.file_name().and_then(|s| s.to_str()) != Some(format!("gpu-stage-{token}").as_str()) {
        return fail("INVALID_STAGE_OWNER");
    };
    gpu_stage_staging::create_stage_dir(dir)?;
    let mut guard = OwnedDir(dir.into(), false);
    gpu_stage_staging::snapshot_sources(sources,dir,width as u64*height as u64,24*width as u64*height as u64,&mut cancelled)?;
    for ch in 0..3 {
        check(&mut cancelled)?;
        let f = io_at(Phase::KernelCreate,OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(dir.join(format!("kernel-{ch}.f64le"))))?;
        let mut out = std::io::BufWriter::with_capacity(65536, f);
        let mut hash = Sha256::new();
        for y in 0..kernel.side() {
            check(&mut cancelled)?;
            for v in kernel.row(ch, y) {
                numeric_scalar(v, 1.)?;
                let b = v.to_le_bytes();
                io_at(Phase::KernelWrite,out.write_all(&b))?;
                hash.update(b);
            }
        }
        io_at(Phase::KernelWrite,out.flush())?;
        io_at(Phase::KernelSync,out.get_ref().sync_all())?;
        m.kernel_sha256[ch] = hex(&hash.finalize());
    }
    let b = serde_json::to_vec(&m).map_err(|_| "INVALID_STAGE_MANIFEST")?;
    let p = make_plan(&m, hex(&Sha256::digest(&b)))?;
    let mut f = io_at(Phase::ManifestCreate,OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(dir.join(".manifest.partial")))?;
    io_at(Phase::ManifestWrite,f.write_all(&b))?;
    io_at(Phase::ManifestSync,f.sync_all())?;
    check(&mut cancelled)?;
    io_at(Phase::ManifestRename,fs::rename(
        dir.join(".manifest.partial"),
        dir.join("manifest.json"),
    ))?;
    guard.1 = true;
    Ok(p)
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct Complete {
    pub schema_version: u32,
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
    let mut file = io(File::open(path))?;
    let mut data = vec![0u8; 65536];
    let mut hash = Sha256::new();
    let mut left = samples * 8;
    while left > 0 {
        check(cancel)?;
        let n = (left as usize).min(data.len());
        io(file.read_exact(&mut data[..n]))?;
        for b in data[..n].chunks_exact(8) {
            if !f64::from_le_bytes(b.try_into().unwrap()).is_finite() {
                return fail("NONFINITE_STAGE_OUTPUT");
            }
        }
        hash.update(&data[..n]);
        left -= n as u64;
    }
    Ok(hex(&hash.finalize()))
}
/// Caller MUST confirm helper termination first and keep snapshot inputs immutable.
pub fn validate_outputs(
    dir: &Path,
    token: u64,
    manifest_sha: &str,
    mut cancel: impl FnMut() -> bool,
) -> R<[PathBuf; 3]> {
    check(&mut cancel)?;
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != manifest_sha {
        return fail("STALE_STAGE_PLAN");
    };
    let candidate = dir.join("candidate");
    let meta = io(fs::symlink_metadata(&candidate))?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return fail("INVALID_STAGE_OUTPUT");
    };
    let mut names = Vec::new();
    for item in io(fs::read_dir(&candidate))?.take(5) {
        names.push(io(item)?.file_name().to_string_lossy().into_owned());
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
        return fail("INCOMPLETE_GPU_STAGE");
    };
    let (c, _): (Complete, _) = read_json(&candidate.join("complete.json"))?;
    if c.schema_version != 1
        || c.token != token
        || c.manifest_sha256 != manifest_sha
        || c.width != m.width
        || c.height != m.height
    {
        return fail("STALE_STAGE_OUTPUT");
    };
    let paths = std::array::from_fn(|ch| candidate.join(format!("plane-{ch}.f64le")));
    for ch in 0..3 {
        if !valid_hash(&c.output_sha256[ch])
            || hash_plane(&paths[ch], m.width as u64 * m.height as u64, &mut cancel)?
                != c.output_sha256[ch]
        {
            return fail("GPU_STAGE_HASH_MISMATCH");
        }
    }
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
        return fail("STALE_STAGE_PLAN");
    };
    if budget > STAGE_MEMORY_CAP || budget < p.combined_peak_bytes {
        return fail("INSUFFICIENT_GPU_STAGE_MEMORY");
    };
    super::streaming::run(dir, &m, &p, &mut cancel)
}

/// Bounded scalar oracle for a tile-center safety check. Not a full-image quality proof.
pub(super) fn direct_sample(
    source: &Path,
    kernel: &Path,
    w: u32,
    h: u32,
    r: u32,
    x: u32,
    y: u32,
    scatter: f64,
    c: &mut impl FnMut() -> bool,
) -> R<f64> {
    let mut src = io(File::open(source))?;
    let mut psf = io(File::open(kernel))?;
    let ks = 2 * r as usize + 1;
    let mut row = vec![0u8; w as usize * 8];
    let mut coeffs = vec![0u8; ks * 8];
    let mut sum = 0.;
    let reflect = |v: i64, n: u32| {
        if n <= 1 {
            0
        } else {
            let period = 2 * (n as i64 - 1);
            let v = v.rem_euclid(period);
            if v < n as i64 {
                v as u32
            } else {
                (period - v) as u32
            }
        }
    };
    for ky in 0..ks {
        check(c)?;
        let sy = reflect(y as i64 + r as i64 - ky as i64, h);
        io(src.seek(SeekFrom::Start(sy as u64 * w as u64 * 8)))?;
        io(src.read_exact(&mut row))?;
        io(psf.read_exact(&mut coeffs))?;
        for kx in 0..ks {
            let sx = reflect(x as i64 + r as i64 - kx as i64, w) as usize;
            let v = f64::from_le_bytes(row[sx * 8..sx * 8 + 8].try_into().unwrap());
            let k = f64::from_le_bytes(coeffs[kx * 8..kx * 8 + 8].try_into().unwrap());
            if !v.is_finite() || !k.is_finite() {
                return fail("NONFINITE_STAGE_INPUT");
            };
            sum += v * k;
        }
    }
    let mut original = [0u8; 8];
    io(src.seek(SeekFrom::Start((y as u64 * w as u64 + x as u64) * 8)))?;
    io(src.read_exact(&mut original))?;
    let v = (1. - scatter) * f64::from_le_bytes(original) + scatter * sum;
    if !v.is_finite() {
        return fail("NONFINITE_STAGE_INPUT");
    };
    Ok(v)
}
/// Exact production streaming packer, shared with CPU tests. Buffer sizes are
/// explicit to keep the GPU driver's staging uploads bounded.
pub(super) fn read_reflected_chunk(
    src: &mut File,
    w: u32,
    h: u32,
    r: u32,
    n: usize,
    ox: u32,
    oy: u32,
    first: usize,
    count: usize,
    row: &mut [u8],
    chunk: &mut [u8],
    cancel: &mut impl FnMut() -> bool,
) -> R<()> {
    if w == 0
        || h == 0
        || n == 0
        || n > 4096
        || first > n
        || count > n - first
        || row.len() != w as usize * 8
        || chunk.len() != count * n * 8
        || chunk.len() > CHUNK_BYTES
    {
        return fail("INVALID_STAGE_CHUNK");
    }
    let reflect = |v: i64, size: u32| {
        if size <= 1 {
            0
        } else {
            let period = 2 * (size as i64 - 1);
            let v = v.rem_euclid(period);
            if v < size as i64 {
                v as u32
            } else {
                (period - v) as u32
            }
        }
    };
    for dy in 0..count {
        check(cancel)?;
        let sy = reflect(oy as i64 + (first + dy) as i64 - r as i64, h);
        io(src.seek(SeekFrom::Start(sy as u64 * w as u64 * 8)))?;
        io(src.read_exact(row))?;
        for x in 0..n {
            let sx = reflect(ox as i64 + x as i64 - r as i64, w) as usize;
            let v = f64::from_le_bytes(row[sx * 8..sx * 8 + 8].try_into().unwrap());
            let admitted = numeric_scalar(v, FFT_SOURCE_BOUND)?;
            let i = (dy * n + x) * 8;
            chunk[i..i + 4].copy_from_slice(&admitted.to_le_bytes());
            chunk[i + 4..i + 8].fill(0);
        }
    }
    Ok(())
}
pub(super) fn blend_readback_row(
    original: &[u8],
    complex: &[f32],
    scatter: f32,
    out: &mut [u8],
) -> R<()> {
    if original.len() % 8 != 0
        || out.len() != original.len()
        || complex.len() != original.len() / 4
        || !scatter.is_finite()
    {
        return fail("INVALID_STAGE_ROW");
    }
    numeric_fft_domain(1, scatter as f64)?;
    for x in 0..original.len() / 8 {
        let base = numeric_scalar(f64::from_le_bytes(original[x * 8..x * 8 + 8].try_into().unwrap()), FFT_SOURCE_BOUND)?;
        let blur = complex[x * 2];
        let value = (1. - scatter) * base + scatter * blur;
        if !base.is_finite() || !blur.is_finite() || !value.is_finite() {
            return fail("NONFINITE_STAGE_OUTPUT");
        };
        out[x * 8..x * 8 + 8].copy_from_slice(&(value as f64).to_le_bytes());
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use spektrafilm_model::diffusion::DiffusionFilter;
    fn root() -> PathBuf {
        let p = std::env::temp_dir().join(format!(
            "gpu-stage-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir(&p).unwrap();
        p
    }
    fn kernel(w: usize, h: usize) -> ExactDiffusionKernel {
        ExactDiffusionKernel::new(
            &DiffusionFilter {
                family: "black_pro_mist",
                strength: 0.5,
                spatial_scale: 1.,
                halo_warmth: 0.,
                core_intensity: 1.,
                core_size: 1.,
                halo_intensity: 1.,
                halo_size: 1.,
                bloom_intensity: 1.,
                bloom_size: 1.,
            },
            10.,
            w,
            h,
        )
        .unwrap()
    }
    fn sources(root: &Path, w: usize, h: usize) -> [PathBuf; 3] {
        std::array::from_fn(|ch| {
            let path = root.join(format!("original-{ch}"));
            fs::write(
                &path,
                (0..w * h)
                    .flat_map(|i| ((i + ch) as f64 / 13. - 2.).to_le_bytes())
                    .collect::<Vec<_>>(),
            )
            .unwrap();
            path
        })
    }
    #[test]
    fn fft_numeric_domain_bound_and_boundaries() {
        let rounding = 1. + 2f64.powi(-23);
        assert!(3. * rounding.powi(3) < 3.01);
        assert!(2. * rounding.powi(2) < 2.01);
        let shader = include_str!("../shaders/research_diffusion_fft.wgsl");
        assert!(shader.contains("x.x*y.x-x.y*y.y,x.x*y.y+x.y*y.x"));
        assert!(shader.contains("write_complex(i,even+odd)"));
        assert!(shader.contains("write_complex(i,even-odd)"));
        assert!(shader.contains(".x/f32(p.n*p.n)"));
        assert!(shader.contains("(1.0-p.scatter)*original+p.scatter*blurred"));
        let worst_component = 2.01 * FFT_SOURCE_BOUND * 3.01f64.powi(72);
        assert!(worst_component < 5.9e37);
        assert!(2. * worst_component < f32::MAX as f64);
        for side in [1, 2, 512, 4096] {
            for scatter in [0., 0.5, 1.] {
                numeric_fft_domain(side, scatter).unwrap();
            }
        }
        for side in [0, 3, 4095, 8192] {
            assert_eq!(
                numeric_fft_domain(side, 0.5).unwrap_err(),
                "GPU_STAGE_NUMERIC_DOMAIN"
            );
        }
        for scatter in [-f64::EPSILON, 1. + f64::EPSILON, f64::NAN, f64::INFINITY] {
            assert_eq!(
                numeric_fft_domain(4096, scatter).unwrap_err(),
                "GPU_STAGE_NUMERIC_DOMAIN"
            );
        }
        for bound in [1., FFT_SOURCE_BOUND] {
            for v in [-bound, -0., 0., bound] {
                assert_eq!(
                    numeric_scalar(v, bound).unwrap().to_bits(),
                    (v as f32).to_bits()
                );
            }
            // Even a value which rounds back onto the f32 boundary is rejected.
            let outside = f64::from_bits(bound.to_bits() + 1);
            assert_eq!(outside as f32, bound as f32);
            for v in [-outside, outside, f64::NAN, f64::INFINITY] {
                assert_eq!(
                    numeric_scalar(v, bound).unwrap_err(),
                    "GPU_STAGE_NUMERIC_DOMAIN"
                );
            }
        }
    }
    #[test]
    fn fft_numeric_domain_covers_reflected_upload_and_original_crop() {
        let root = root();
        let _guard = OwnedDir(root.clone(), false);
        let path = root.join("numeric-source");
        for v in [-1024f64, -0., 0., 1024., -1025., 1025., 2e38] {
            fs::write(&path, v.to_le_bytes()).unwrap();
            let mut file = File::open(&path).unwrap();
            let mut packed = [0u8; 8];
            let reflected = read_reflected_chunk(
                &mut file,
                1,
                1,
                0,
                1,
                0,
                0,
                0,
                1,
                &mut [0; 8],
                &mut packed,
                &mut || false,
            );
            let crop = blend_readback_row(&v.to_le_bytes(), &[0., 0.], 0.5, &mut [0; 8]);
            if v.abs() <= FFT_SOURCE_BOUND {
                reflected.unwrap();
                crop.unwrap();
                assert_eq!(&packed[..4], &(v as f32).to_le_bytes());
                assert_eq!(&packed[4..], &0f32.to_le_bytes());
                assert_eq!(fs::read(&path).unwrap(), v.to_le_bytes());
            } else {
                assert_eq!(reflected.unwrap_err(), "GPU_STAGE_NUMERIC_DOMAIN");
                assert_eq!(crop.unwrap_err(), "GPU_STAGE_NUMERIC_DOMAIN");
            }
        }
        let streaming = include_str!("streaming.rs");
        assert!(streaming.contains("originals.push(numeric_scalar(v, FFT_SOURCE_BOUND)?);"));
        assert!(streaming.contains("let admitted = numeric_scalar(x, 1.)?;"));
        assert!(streaming.contains("numeric_scalar(a.cos(), 1.)?"));
        assert!(streaming.contains("numeric_scalar(a.sin(), 1.)?"));
    }
    #[test]
    fn fft_numeric_domain_rejects_whole_camera_and_print_candidate() {
        for print in [false, true] {
            let root = root();
            let _guard = OwnedDir(root.clone(), false);
            let src = sources(&root, 9, 7);
            let k = kernel(9, 7);
            let dir = root.join("gpu-stage-101");
            let mut raw = fs::read(&src[2]).unwrap();
            raw[8..16].copy_from_slice(&1025f64.to_le_bytes());
            fs::write(&src[2], &raw).unwrap();
            let paths = src.each_ref().map(|p| p.as_path());
            let plan = if print {
                prepare_print_stage(paths, &dir, 101, 9, 7, 10., "black_pro_mist", &k, || false)
            } else {
                prepare_stage(paths, &dir, 101, 9, 7, 10., "black_pro_mist", &k, || false)
            }
            .unwrap();
            let error = run_stage_cpu_mirror_for_test(&dir, 101, &plan.manifest_sha256, || false)
                .unwrap_err();
            assert_eq!(error, "GPU_STAGE_NUMERIC_DOMAIN");
            assert!(!dir.join("candidate").exists());
            assert_eq!(fs::read(&src[2]).unwrap(), raw);
        }
    }
    #[test]
    fn planning_limits_and_4096_memory_are_explicit() {
        let m = Manifest {
            schema_version: 1,
            operation: "fullCameraDiffusion".into(),
            token: 1,
            width: 3072,
            height: 4096,
            radius: 890,
            fft_side: 4096,
            scatter_bits: format!("{:016x}", 0.5f64.to_bits()),
            pixel_pitch_bits: format!("{:016x}", 8.5f64.to_bits()),
            family: "black_pro_mist".into(),
            kernel_sha256: std::array::from_fn(|_| "0".repeat(64)),
        };
        let p = make_plan(&m, String::new()).unwrap();
        assert_eq!(p.tile_count, 12);
        assert_eq!(p.core, 2316);
        assert_eq!(
            p.device_bytes,
            3 * 4096u64.pow(2) * 8 + 1781u64.pow(2) * 4 + 4096 * 8 + 16 * MIB + 65536
        );
        assert_eq!(p.combined_peak_bytes, p.device_bytes + 184 * MIB);
        assert!(p.combined_peak_bytes < 640 * MIB);
        assert!(geometry(3072, 4096, 1999).is_err());
        assert!(geometry(40000, 40000, 1).is_err());
        assert!(geometry(0, 1, 0).is_err());
        for (w, h) in [(1, 7), (7, 1), (9, 13)] {
            assert!(geometry(w, h, 1).is_ok());
        }
    }
    #[test]
    fn prepare_collision_cancel_and_all_three_output_validation() {
        let root = root();
        let _guard = OwnedDir(root.clone(), false);
        let src = sources(&root, 9, 7);
        let refs = [src[0].as_path(), src[1].as_path(), src[2].as_path()];
        let k = kernel(9, 7);
        let dir = root.join("gpu-stage-17");
        let mut calls = 0;
        assert!(
            prepare_stage(refs, &dir, 17, 9, 7, 10., "black_pro_mist", &k, || {
                calls += 1;
                calls > 4
            })
            .is_err()
        );
        assert!(!dir.exists());
        assert!(src.iter().all(|p| p.exists()));
        fs::create_dir(&dir).unwrap();
        fs::write(dir.join("keep"), b"existing").unwrap();
        assert!(prepare_stage(refs, &dir, 17, 9, 7, 10., "black_pro_mist", &k, || false).is_err());
        assert_eq!(fs::read(dir.join("keep")).unwrap(), b"existing");
        fs::remove_dir_all(&dir).unwrap();
        let p = prepare_stage(refs, &dir, 17, 9, 7, 10., "black_pro_mist", &k, || false).unwrap();
        assert_eq!(p, plan_stage(&dir, 17).unwrap());
        if let Some(results)=std::env::var_os("FILM_GPU_CONTRACT_RESULTS") {
            let results=PathBuf::from(results);fs::create_dir_all(&results).unwrap();
            fs::write(results.join("camera-plan.json"),serde_json::to_vec_pretty(&serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuOpticalStage","plan":p})).unwrap()).unwrap();
            let print_dir=root.join("gpu-stage-18");let print=prepare_print_stage(refs,&print_dir,18,9,7,10.,"black_pro_mist",&k,||false).unwrap();assert_eq!(print,plan_stage(&print_dir,18).unwrap());fs::write(results.join("print-diffusion-plan.json"),serde_json::to_vec_pretty(&serde_json::json!({"ok":true,"schemaVersion":1,"operation":"planGpuPrintDiffusionStage","plan":print})).unwrap()).unwrap();
        }
        let candidate = dir.join("candidate");
        fs::create_dir(&candidate).unwrap();
        let mut hashes = std::array::from_fn(|_| String::new());
        for ch in 0..3 {
            fs::copy(&src[ch], candidate.join(format!("plane-{ch}.f64le"))).unwrap();
            hashes[ch] = hash_plane(
                &candidate.join(format!("plane-{ch}.f64le")),
                63,
                &mut || false,
            )
            .unwrap();
        }
        let complete = Complete {
            schema_version: 1,
            token: 17,
            manifest_sha256: p.manifest_sha256.clone(),
            width: 9,
            height: 7,
            output_sha256: hashes,
        };
        fs::write(
            candidate.join("complete.json"),
            serde_json::to_vec(&complete).unwrap(),
        )
        .unwrap();
        assert!(validate_outputs(&dir, 17, &p.manifest_sha256, || false).is_ok());
        assert!(validate_outputs(&dir, 17, &p.manifest_sha256, || true).is_err());
        let third = candidate.join("plane-2.f64le");
        let mut f = OpenOptions::new().write(true).open(&third).unwrap();
        f.write_all(&f64::NAN.to_le_bytes()).unwrap();
        assert_eq!(
            validate_outputs(&dir, 17, &p.manifest_sha256, || false).unwrap_err(),
            "NONFINITE_STAGE_OUTPUT"
        );
        fs::remove_file(third).unwrap();
        assert!(validate_outputs(&dir, 17, &p.manifest_sha256, || false).is_err());
        assert!(src.iter().all(|p| p.exists()));
    }
    #[test]
    fn complete_prepare_copies_only_known_unsupported_links_and_reports_phases() {
        for errno in [1,13,18,95] {
            let root=root();let _guard=OwnedDir(root.clone(),false);let src=sources(&root,9,7);let k=kernel(9,7);let dir=root.join("gpu-stage-99");
            let p=crate::gpu_stage_staging::with_test_link_error(errno,||prepare_stage([src[0].as_path(),src[1].as_path(),src[2].as_path()],&dir,99,9,7,10.,"black_pro_mist",&k,||false)).unwrap();
            assert_eq!(p,plan_stage(&dir,99).unwrap());assert_eq!(p.disk_bytes,48*9*7+24*(k.side()as u64).pow(2)+65536);
            for ch in 0..3 {assert_eq!(fs::read(&src[ch]).unwrap(),fs::read(dir.join(format!("source-{ch}.f64le"))).unwrap());}
        }
        let root=root();let _guard=OwnedDir(root.clone(),false);let src=sources(&root,9,7);let k=kernel(9,7);let dir=root.join("gpu-stage-100");
        let code=crate::gpu_stage_staging::with_test_link_error(28,||prepare_stage([src[0].as_path(),src[1].as_path(),src[2].as_path()],&dir,100,9,7,10.,"black_pro_mist",&k,||false)).unwrap_err();
        assert_eq!(code,"GPU_PREPARE_SOURCE_LINK_ENOSPC");assert!(!dir.exists());assert!(src.iter().all(|p|p.exists()));
    }
    #[test]
    fn real_stream_pack_and_blend_match_direct_asymmetric_convolution() {
        use rustfft::num_complex::Complex;
        for (w, h) in [(17u32, 13u32), (1, 7), (7, 1), (9, 5)] {
            let root = root();
            let _guard = OwnedDir(root.clone(), false);
            let source = root.join("source");
            let vals = (0..w * h)
                .map(|i| {
                    if i % 11 == 0 {
                        32.
                    } else {
                        (i % 7) as f64 - 3.
                    }
                })
                .collect::<Vec<_>>();
            fs::write(
                &source,
                vals.iter()
                    .flat_map(|v| v.to_le_bytes())
                    .collect::<Vec<_>>(),
            )
            .unwrap();
            let kernel = root.join("kernel");
            let coeff = (1..=9).map(|v| v as f64 / 45.).collect::<Vec<_>>();
            fs::write(
                &kernel,
                coeff
                    .iter()
                    .flat_map(|v| v.to_le_bytes())
                    .collect::<Vec<_>>(),
            )
            .unwrap();
            let n = 8usize;
            let radius = 1usize;
            let core = 6usize;
            let scatter = 0.6f32;
            let tw = (0..n)
                .map(|i| {
                    let a = -2. * std::f64::consts::PI * i as f64 / n as f64;
                    [a.cos() as f32, a.sin() as f32]
                })
                .collect::<Vec<_>>();
            let mut spectrum = vec![Complex::new(0., 0.); n * n];
            for y in 0..3 {
                for x in 0..3 {
                    spectrum[y * n + x].re = coeff[y * 3 + x] as f32;
                }
            }
            super::super::fft2(&mut spectrum, n, false, &tw);
            let mut src = File::open(&source).unwrap();
            let mut original = vec![0u8; w as usize * 8];
            for oy in (0..h as usize).step_by(core) {
                for ox in (0..w as usize).step_by(core) {
                    let ww = core.min(w as usize - ox);
                    let hh = core.min(h as usize - oy);
                    let mut packed = vec![0u8; n * n * 8];
                    read_reflected_chunk(
                        &mut src,
                        w,
                        h,
                        radius as u32,
                        n,
                        ox as u32,
                        oy as u32,
                        0,
                        n,
                        &mut original,
                        &mut packed,
                        &mut || false,
                    )
                    .unwrap();
                    let mut image = packed
                        .chunks_exact(8)
                        .map(|b| {
                            Complex::new(
                                f32::from_le_bytes(b[..4].try_into().unwrap()),
                                f32::from_le_bytes(b[4..].try_into().unwrap()),
                            )
                        })
                        .collect::<Vec<_>>();
                    super::super::fft2(&mut image, n, false, &tw);
                    for (a, k) in image.iter_mut().zip(&spectrum) {
                        *a = super::super::mul(*a, *k)
                    }
                    super::super::fft2(&mut image, n, true, &tw);
                    for y in 0..hh {
                        let mut base = Vec::new();
                        let mut blurred = Vec::new();
                        for x in 0..ww {
                            base.extend_from_slice(
                                &vals[(oy + y) * w as usize + ox + x].to_le_bytes(),
                            );
                            blurred.extend_from_slice(&[
                                image[(y + 2 * radius) * n + x + 2 * radius].re / (n * n) as f32,
                                0.,
                            ]);
                        }
                        let mut result = vec![0u8; ww * 8];
                        blend_readback_row(&base, &blurred, scatter, &mut result).unwrap();
                        for x in 0..ww {
                            let expected = direct_sample(
                                &source,
                                &kernel,
                                w,
                                h,
                                1,
                                (ox + x) as u32,
                                (oy + y) as u32,
                                scatter as f64,
                                &mut || false,
                            )
                            .unwrap();
                            let actual =
                                f64::from_le_bytes(result[x * 8..x * 8 + 8].try_into().unwrap());
                            assert!(
                                (actual - expected).abs() < 1e-5,
                                "{w}x{h} {ox}/{oy}/{x}/{y}: {actual} vs {expected}"
                            );
                        }
                    }
                }
            }
        }
    }
    #[test]
    fn chunk_and_blend_reject_invalid_nonfinite_and_cancel() {
        let root = root();
        let _guard = OwnedDir(root.clone(), false);
        let src = sources(&root, 1, 1);
        let mut f = File::open(&src[0]).unwrap();
        assert!(
            read_reflected_chunk(
                &mut f,
                1,
                1,
                1,
                8,
                0,
                0,
                0,
                8,
                &mut [0; 8],
                &mut [0; 512],
                &mut || true
            )
            .is_err()
        );
        assert!(
            blend_readback_row(&f64::INFINITY.to_le_bytes(), &[0., 0.], 0.5, &mut [0; 8]).is_err()
        );
        assert!(
            blend_readback_row(&0f64.to_le_bytes(), &[f32::NAN, 0.], 0.5, &mut [0; 8]).is_err()
        );
    }
}
pub(super) fn publish_complete(
    candidate: &Path,
    m: &Manifest,
    p: &StagePlan,
    hashes: [String; 3],
    cancel: &mut impl FnMut() -> bool,
) -> R<()> {
    let complete = Complete {
        schema_version: 1,
        token: m.token,
        manifest_sha256: p.manifest_sha256.clone(),
        width: m.width,
        height: m.height,
        output_sha256: hashes,
    };
    let bytes = serde_json::to_vec(&complete).map_err(|_| "GPU_STAGE_RESULT_FAILED")?;
    let mut f = io(OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(candidate.join(".complete.partial")))?;
    io(f.write_all(&bytes))?;
    io(f.sync_all())?;
    check(cancel)?;
    io(fs::rename(
        candidate.join(".complete.partial"),
        candidate.join("complete.json"),
    ))?;
    Ok(())
}
/// Test-only host mirror. Never available in a release library; times are not GPU times.
#[cfg(test)]
pub(crate) fn run_stage_cpu_mirror_for_test(
    dir: &Path,
    token: u64,
    manifest_sha: &str,
    mut cancel: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    use rustfft::num_complex::Complex;
    check(&mut cancel)?;
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != manifest_sha {
        return fail("STALE_STAGE_PLAN");
    };
    let n = p.fft_side as usize;
    if n > 512 {
        return fail("TEST_MIRROR_FIXTURE_LIMIT");
    };
    let core = p.core as usize;
    let r = p.radius as usize;
    let ks = 2 * r + 1;
    let tw = (0..n)
        .map(|i| {
            let a = -2. * std::f64::consts::PI * i as f64 / n as f64;
            [a.cos() as f32, a.sin() as f32]
        })
        .collect::<Vec<_>>();
    let candidate = dir.join("candidate");
    io(fs::create_dir(&candidate))?;
    let mut guard = OwnedDir(candidate.clone(), false);
    let mut hashes = std::array::from_fn(|_| String::new());
    let mut sample_max = 0f64;
    let mut sample_sum = 0.;
    let mut sample_count = 0u64;
    for ch in 0..3 {
        check(&mut cancel)?;
        let kernel_path = dir.join(format!("kernel-{ch}.f64le"));
        if hash_plane(&kernel_path, (ks * ks) as u64, &mut cancel)? != m.kernel_sha256[ch] {
            return fail("GPU_STAGE_KERNEL_HASH");
        };
        let raw = io(fs::read(&kernel_path))?;
        let mut spectrum = vec![Complex::new(0., 0.); n * n];
        for y in 0..ks {
            for x in 0..ks {
                spectrum[y * n + x].re = numeric_scalar(f64::from_le_bytes(
                    raw[(y * ks + x) * 8..(y * ks + x + 1) * 8]
                        .try_into()
                        .unwrap(),
                ), 1.)?;
            }
        }
        super::fft2(&mut spectrum, n, false, &tw);
        let source_path = dir.join(format!("source-{ch}.f64le"));
        let mut source = io(File::open(&source_path))?;
        let output_path = candidate.join(format!("plane-{ch}.f64le"));
        let mut output = io(OpenOptions::new()
            .create_new(true)
            .read(true)
            .write(true)
            .open(&output_path))?;
        io(output.set_len(m.width as u64 * m.height as u64 * 8))?;
        let mut row = vec![0u8; m.width as usize * 8];
        for oy in (0..m.height as usize).step_by(core) {
            for ox in (0..m.width as usize).step_by(core) {
                check(&mut cancel)?;
                let ww = core.min(m.width as usize - ox);
                let hh = core.min(m.height as usize - oy);
                let mut packed = vec![0u8; n * n * 8];
                read_reflected_chunk(
                    &mut source,
                    m.width,
                    m.height,
                    m.radius,
                    n,
                    ox as u32,
                    oy as u32,
                    0,
                    n,
                    &mut row,
                    &mut packed,
                    &mut cancel,
                )?;
                let mut image = packed
                    .chunks_exact(8)
                    .map(|b| Complex::new(f32::from_le_bytes(b[..4].try_into().unwrap()), 0.))
                    .collect::<Vec<_>>();
                super::fft2(&mut image, n, false, &tw);
                for (a, k) in image.iter_mut().zip(&spectrum) {
                    *a = super::mul(*a, *k)
                }
                super::fft2(&mut image, n, true, &tw);
                for y in 0..hh {
                    let at = ((oy + y) * m.width as usize + ox) as u64 * 8;
                    let mut original = vec![0u8; ww * 8];
                    io(source.seek(SeekFrom::Start(at)))?;
                    io(source.read_exact(&mut original))?;
                    let mut blurred = Vec::with_capacity(ww * 2);
                    for x in 0..ww {
                        blurred.extend_from_slice(&[
                            image[(y + 2 * r) * n + x + 2 * r].re / (n * n) as f32,
                            0.,
                        ]);
                    }
                    let mut out = vec![0u8; ww * 8];
                    blend_readback_row(&original, &blurred, m.scatter()?, &mut out)?;
                    io(output.seek(SeekFrom::Start(at)))?;
                    io(output.write_all(&out))?;
                }
                let sx = ox as u32 + (ww / 2) as u32;
                let sy = oy as u32 + (hh / 2) as u32;
                let expected = direct_sample(
                    &source_path,
                    &kernel_path,
                    m.width,
                    m.height,
                    m.radius,
                    sx,
                    sy,
                    m.scatter_f64()?,
                    &mut cancel,
                )?;
                let mut b = [0u8; 8];
                io(output.seek(SeekFrom::Start(
                    (sy as u64 * m.width as u64 + sx as u64) * 8,
                )))?;
                io(output.read_exact(&mut b))?;
                let error = (f64::from_le_bytes(b) - expected).abs();
                if !error.is_finite() || error > 2e-5 * expected.abs().max(1.) {
                    return fail("GPU_STAGE_NUMERIC_CHECK");
                };
                sample_max = sample_max.max(error);
                sample_sum += error * error;
                sample_count += 1;
            }
        }
        io(output.sync_all())?;
        hashes[ch] = hash_plane(&output_path, m.width as u64 * m.height as u64, &mut cancel)?;
    }
    publish_complete(&candidate, &m, &p, hashes, &mut cancel)?;
    guard.1 = true;
    Ok(
        serde_json::json!({"ok":true,"schemaVersion":1,"operation":m.operation,"token":token,"manifestSha256":manifest_sha,"testOnly":true,"gpuUsed":false,"fullImage":true,"width":m.width,"height":m.height,"channels":3,"tileCount":p.tile_count,"elapsedMs":0.,"backend":"host f32 FFT mirror (test only)","adapter":"CPU test mirror","vendor":0,"shaderF64Advertised":false,"uploadBytes":0,"readbackBytes":0,"dispatches":0,"deviceBytes":p.device_bytes,"sampleCount":sample_count,"sampleMaxAbs":sample_max,"sampleRms":(sample_sum/sample_count.max(1)as f64).sqrt(),"sampleToleranceRelative":2e-5,"sampleToleranceAbsolute":2e-5}),
    )
}
#[cfg(test)]
mod mirror_tests {
    use super::*;
    #[test]
    fn full_stage_mirror_publishes_three_channels_and_matches_cpu() {
        use spektrafilm_model::diffusion::DiffusionFilter;
        let root = std::env::temp_dir().join(format!("gpu-full-mirror-{}", std::process::id()));
        fs::create_dir(&root).unwrap();
        let _guard = OwnedDir(root.clone(), false);
        let (w, h) = (31usize, 17usize);
        let inputs: [PathBuf; 3] = std::array::from_fn(|ch| {
            let p = root.join(format!("source-{ch}"));
            fs::write(
                &p,
                (0..w * h)
                    .flat_map(|i| {
                        let v = if i % 13 == 0 {
                            15.
                        } else {
                            (i % 19) as f64 / 13. - 0.25 + ch as f64
                        };
                        v.to_le_bytes()
                    })
                    .collect::<Vec<_>>(),
            )
            .unwrap();
            p
        });
        let df = DiffusionFilter {
            family: "black_pro_mist",
            strength: 0.5,
            spatial_scale: 1.,
            halo_warmth: 0.,
            core_intensity: 1.,
            core_size: 1.,
            halo_intensity: 1.,
            halo_size: 1.,
            bloom_intensity: 1.,
            bloom_size: 1.,
        };
        let kernel = ExactDiffusionKernel::new(&df, 10., w, h).unwrap();
        let dir = root.join("gpu-stage-55");
        let plan = prepare_stage(
            [&inputs[0], &inputs[1], &inputs[2]],
            &dir,
            55,
            w as u32,
            h as u32,
            10.,
            "black_pro_mist",
            &kernel,
            || false,
        )
        .unwrap();
        let result =
            run_stage_cpu_mirror_for_test(&dir, 55, &plan.manifest_sha256, || false).unwrap();
        assert_eq!(result["testOnly"], true);
        assert_eq!(result["gpuUsed"], false);
        let outputs = validate_outputs(&dir, 55, &plan.manifest_sha256, || false).unwrap();
        for ch in 0..3 {
            let reference = root.join(format!("reference-{ch}"));
            crate::disk_diffusion::apply_plane(
                &inputs[ch],
                &reference,
                &root.join(format!("work-{ch}")),
                w as u32,
                h as u32,
                &kernel,
                ch,
                || false,
                |_, _| {},
            )
            .unwrap();
            let original = fs::read(&reference).unwrap();
            let actual = fs::read(&outputs[ch]).unwrap();
            for (a, b) in original.chunks_exact(8).zip(actual.chunks_exact(8)) {
                let a = f64::from_le_bytes(a.try_into().unwrap());
                let b = f64::from_le_bytes(b.try_into().unwrap());
                assert!((a - b).abs() < 2e-5, "{ch}: {a} vs {b}");
            }
        }
        let other = root.join("gpu-stage-56");
        let p = prepare_stage(
            [&inputs[0], &inputs[1], &inputs[2]],
            &other,
            56,
            w as u32,
            h as u32,
            10.,
            "black_pro_mist",
            &kernel,
            || false,
        )
        .unwrap();
        let mut calls = 0;
        assert!(
            run_stage_cpu_mirror_for_test(&other, 56, &p.manifest_sha256, || {
                calls += 1;
                calls > 8
            })
            .is_err()
        );
        assert!(!other.join("candidate").exists());
        assert!(inputs.iter().all(|p| p.exists()));
    }
}

/// Same 2D dispatch used by production streaming. WGSL linearizes each Y row
/// with stride65535*64 and rejects excess lanes before buffer access.
pub(super) fn dispatch_grid(count: usize) -> (u32, u32) {
    let groups = count.div_ceil(64) as u32;
    (groups.min(65535), groups.div_ceil(65535))
}
#[cfg(test)]
mod grid_tests {
    use super::*;
    #[test]
    fn fft4096_and_odd_crop_dispatch_cover_tail() {
        for count in [
            1usize,
            63,
            64,
            65,
            65535 * 64,
            65535 * 64 + 1,
            4096 * 4096,
            2316 * 2315,
        ] {
            let (x, y) = dispatch_grid(count);
            assert!(x <= 65535 && y <= 65535);
            assert!(x as usize * y as usize * 64 >= count);
            let last = count - 1;
            let gy = last / (65535 * 64);
            let gx = last % (65535 * 64);
            assert!(gy < y as usize);
            assert!(gx < x as usize * 64);
            assert_eq!(gx + gy * 65535 * 64, last);
        }
        assert_eq!(dispatch_grid(4096 * 4096), (65535, 5));
    }
}
