//! App-private, bounded, diagnostic-only optical stage snapshots. No output replacement.
use super::{Plan, compare};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use spektrafilm_model::diffusion::ExactDiffusionKernel;
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Seek, SeekFrom, Write},
    path::Path,
    time::Instant,
};
const MIB: u64 = 1024 * 1024;
pub const SNAPSHOT_CAP: u64 = 64 * MIB;
pub const CAPTURE_MEMORY_BYTES: u64 = 2 * MIB;
pub const COMBINED_CAP: u64 = 320 * MIB;
const FILES: [&str; 4] = [
    "manifest.json",
    "source.f64le",
    "kernel.f64le",
    "reference.f64le",
];
type R<T> = Result<T, String>;
fn err<T>(code: &str) -> R<T> {
    Err(code.into())
}
fn io<T>(r: std::io::Result<T>) -> R<T> {
    r.map_err(|_| "SNAPSHOT_IO".into())
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|x| format!("{x:02x}")).collect()
}
fn valid_hex(v: &str, n: usize) -> bool {
    v.len() == n
        && v.bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}
fn bits(v: &str) -> R<f64> {
    if !valid_hex(v, 16) {
        return err("INVALID_MANIFEST");
    };
    let f = f64::from_bits(u64::from_str_radix(v, 16).map_err(|_| "INVALID_MANIFEST")?);
    if !f.is_finite() {
        return err("NONFINITE_MANIFEST");
    };
    Ok(f)
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Manifest {
    pub schema_version: u32,
    pub token: u64,
    pub original_width: u32,
    pub original_height: u32,
    pub fft_side: u32,
    pub radius: u32,
    pub roi_x: u32,
    pub roi_y: u32,
    pub roi_width: u32,
    pub roi_height: u32,
    pub channel: u32,
    pub family: String,
    pub pixel_pitch_bits: String,
    pub scatter_bits: String,
    pub source_sha256: String,
    pub kernel_sha256: String,
    pub reference_sha256: String,
}
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct ProbePlan {
    pub token: u64,
    pub manifest_sha256: String,
    pub original_width: u32,
    pub original_height: u32,
    pub fft_side: u32,
    pub radius: u32,
    pub roi_width: u32,
    pub roi_height: u32,
    pub channel: u32,
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub snapshot_bytes: u64,
}
fn geometry(w: u32, h: u32, r: u32) -> R<(u32, u32, u32, u32, u32)> {
    if w == 0 || h == 0 || w > 40000 || h > 40000 || r >= 1024 {
        return err("ORIGINAL_PSF_EXCEEDS_PROBE_LIMIT");
    }
    let need = 2 * r + 256.min(w).min(h);
    let n = need.max(2).next_power_of_two();
    if n > 2048 || n <= 2 * r {
        return err("ORIGINAL_PSF_EXCEEDS_PROBE_LIMIT");
    }
    let cw = 256.min(n - 2 * r).min(w);
    let ch = 256.min(n - 2 * r).min(h);
    Ok((n, cw, ch, (w - cw) / 2, (h - ch) / 2))
}
fn sizes(m: &Manifest, sha: String) -> R<ProbePlan> {
    if m.schema_version != 1
        || m.token == 0
        || m.token > i64::MAX as u64
        || m.channel != 1
        || !["black_pro_mist", "pro_mist", "glimmerglass", "cinebloom"].contains(&m.family.as_str())
    {
        return err("INVALID_MANIFEST");
    }
    let (n, cw, ch, x, y) = geometry(m.original_width, m.original_height, m.radius)?;
    if [n, cw, ch, x, y] != [m.fft_side, m.roi_width, m.roi_height, m.roi_x, m.roi_y]
        || bits(&m.pixel_pitch_bits)? <= 0.
        || !(bits(&m.scatter_bits)? as f32).is_finite()
    {
        return err("INVALID_MANIFEST");
    }
    for hash in [&m.source_sha256, &m.kernel_sha256, &m.reference_sha256] {
        if !valid_hex(hash, 64) {
            return err("INVALID_MANIFEST");
        }
    }
    let n = n as u64;
    let ks = 2 * m.radius as u64 + 1;
    let pixels = cw as u64 * ch as u64;
    let core = cw.max(ch) as u64;
    // Bounds above constrain every product; u64 arithmetic cannot overflow.
    let data = (n * n + ks * ks) * 4 + n * 8;
    let device = 3 * n * n * 8 + data + core * core * 8 + 65536;
    // Host upload + f64 PSF + twiddles + candidate/reference + mapped copy/rows.
    let host = data + ks * ks * 8 + n * 8 + pixels * 24 + 1024 * 1024;
    let combined = device + host + 128 * MIB;
    let snapshot = (n * n + ks * ks + pixels) * 8 + 8192;
    if snapshot > SNAPSHOT_CAP || combined > COMBINED_CAP {
        return err("PROBE_BUDGET_EXCEEDED");
    }
    Ok(ProbePlan {
        token: m.token,
        manifest_sha256: sha,
        original_width: m.original_width,
        original_height: m.original_height,
        fft_side: m.fft_side,
        radius: m.radius,
        roi_width: cw,
        roi_height: ch,
        channel: 1,
        device_bytes: device,
        host_bytes: host,
        driver_allowance_bytes: 64 * MIB,
        helper_allowance_bytes: 64 * MIB,
        combined_peak_bytes: combined,
        snapshot_bytes: snapshot,
    })
}
fn regular(path: &Path, expected: u64) -> R<()> {
    let meta = io(fs::symlink_metadata(path))?;
    if !meta.is_file() || meta.file_type().is_symlink() || meta.len() != expected {
        return err("INVALID_SNAPSHOT_FILE");
    };
    Ok(())
}
fn read_manifest(dir: &Path, token: u64) -> R<(Manifest, ProbePlan)> {
    if token == 0
        || dir.file_name().and_then(|s| s.to_str()) != Some(format!("probe-{token}").as_str())
    {
        return err("INVALID_SNAPSHOT_OWNER");
    }
    let meta = io(fs::symlink_metadata(dir))?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return err("INVALID_SNAPSHOT_DIRECTORY");
    }
    let mut names = io(fs::read_dir(dir))?
        .take(5)
        .map(|v| v.map(|v| v.file_name().to_string_lossy().into_owned()))
        .collect::<std::io::Result<Vec<_>>>()
        .map_err(|_| "SNAPSHOT_IO")?;
    names.sort();
    let mut expected = FILES.map(str::to_string).to_vec();
    expected.sort();
    if names != expected {
        return err("INVALID_SNAPSHOT_FILES");
    }
    let path = dir.join(FILES[0]);
    let meta = io(fs::symlink_metadata(&path))?;
    if !meta.is_file() || meta.file_type().is_symlink() || meta.len() > 8192 {
        return err("INVALID_MANIFEST");
    }
    let mut bytes = Vec::new();
    io(File::open(path))?
        .take(8193)
        .read_to_end(&mut bytes)
        .map_err(|_| "SNAPSHOT_IO")?;
    if bytes.len() > 8192 {
        return err("INVALID_MANIFEST");
    }
    let m: Manifest = serde_json::from_slice(&bytes).map_err(|_| "INVALID_MANIFEST")?;
    if m.token != token {
        return err("INVALID_SNAPSHOT_OWNER");
    }
    let p = sizes(&m, hex(&Sha256::digest(&bytes)))?;
    let n = m.fft_side as u64;
    let ks = 2 * m.radius as u64 + 1;
    regular(&dir.join(FILES[1]), n * n * 8)?;
    regular(&dir.join(FILES[2]), ks * ks * 8)?;
    regular(
        &dir.join(FILES[3]),
        m.roi_width as u64 * m.roi_height as u64 * 8,
    )?;
    Ok((m, p))
}
pub fn plan(dir: &Path, token: u64) -> R<ProbePlan> {
    read_manifest(dir, token).map(|(_, p)| p)
}
fn check(cancel: &mut impl FnMut() -> bool) -> R<()> {
    if cancel() { err("CANCELLED") } else { Ok(()) }
}
fn reflect(v: i64, n: u32) -> u32 {
    if n <= 1 {
        return 0;
    };
    let period = 2 * (n as i64 - 1);
    let p = v.rem_euclid(period);
    if p < n as i64 {
        p as u32
    } else {
        (period - p) as u32
    }
}
struct Partial(std::path::PathBuf, bool);
impl Drop for Partial {
    fn drop(&mut self) {
        if !self.1 {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
}
struct Writer {
    f: std::io::BufWriter<File>,
    hash: Sha256,
}
impl Writer {
    fn new(p: &Path) -> R<Self> {
        Ok(Self {
            f: std::io::BufWriter::with_capacity(
                65536,
                io(OpenOptions::new().create_new(true).write(true).open(p))?,
            ),
            hash: Sha256::new(),
        })
    }
    fn values(&mut self, values: &[f64]) -> R<()> {
        for v in values {
            if !v.is_finite() || !(*v as f32).is_finite() {
                return err("NONFINITE_SNAPSHOT");
            };
            let b = v.to_le_bytes();
            io(self.f.write_all(&b))?;
            self.hash.update(b);
        }
        Ok(())
    }
    fn finish(mut self) -> R<String> {
        io(self.f.flush())?;
        io(self.f.get_ref().sync_all())?;
        Ok(hex(&self.hash.finalize()))
    }
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptureResult {
    pub capture_ms: f64,
    pub plan: ProbePlan,
}
/// Caller must reserve capture disk/row memory and own a fresh private target.
pub fn capture_snapshot(
    source: &Path,
    reference: &Path,
    dir: &Path,
    token: u64,
    width: u32,
    height: u32,
    pitch: f64,
    family: &str,
    kernel: &ExactDiffusionKernel,
    channel: usize,
    mut cancel: impl FnMut() -> bool,
) -> R<CaptureResult> {
    let at = Instant::now();
    check(&mut cancel)?;
    if kernel.descriptor().geometry() != [width as usize, height as usize]
        || channel != 1
        || kernel.radius > u32::MAX as usize
    {
        return err("INVALID_CAPTURE_IDENTITY");
    }
    let (n, cw, ch, x, y) = geometry(width, height, kernel.radius as u32)?;
    let mut m = Manifest {
        schema_version: 1,
        token,
        original_width: width,
        original_height: height,
        fft_side: n,
        radius: kernel.radius as u32,
        roi_x: x,
        roi_y: y,
        roi_width: cw,
        roi_height: ch,
        channel: 1,
        family: family.into(),
        pixel_pitch_bits: format!("{:016x}", pitch.to_bits()),
        scatter_bits: format!("{:016x}", kernel.scatter.to_bits()),
        source_sha256: "0".repeat(64),
        kernel_sha256: "0".repeat(64),
        reference_sha256: "0".repeat(64),
    };
    sizes(&m, String::new())?;
    if dir.exists()
        || dir.file_name().and_then(|s| s.to_str()) != Some(format!("probe-{token}").as_str())
    {
        return err("INVALID_SNAPSHOT_OWNER");
    }
    regular(source, width as u64 * height as u64 * 8)?;
    regular(reference, width as u64 * height as u64 * 8)?;
    let tmp = dir.to_path_buf();
    io(fs::create_dir(&tmp))?;
    let mut guard = Partial(tmp.clone(), false);
    let mut input = io(File::open(source))?;
    let mut raw = vec![0u8; width as usize * 8];
    let mut values = vec![0.; n as usize];
    let mut out = Writer::new(&tmp.join(FILES[1]))?;
    for yy in 0..n {
        check(&mut cancel)?;
        let sy = reflect(y as i64 + yy as i64 - m.radius as i64, height);
        io(input.seek(SeekFrom::Start(sy as u64 * width as u64 * 8)))?;
        io(input.read_exact(&mut raw))?;
        for xx in 0..n {
            let sx = reflect(x as i64 + xx as i64 - m.radius as i64, width) as usize;
            values[xx as usize] = f64::from_le_bytes(raw[sx * 8..sx * 8 + 8].try_into().unwrap());
        }
        out.values(&values)?;
    }
    m.source_sha256 = out.finish()?;
    let mut out = Writer::new(&tmp.join(FILES[2]))?;
    for yy in 0..kernel.side() {
        check(&mut cancel)?;
        out.values(&kernel.row(channel, yy))?;
    }
    m.kernel_sha256 = out.finish()?;
    let mut input = io(File::open(reference))?;
    let mut out = Writer::new(&tmp.join(FILES[3]))?;
    raw.resize(cw as usize * 8, 0);
    values.resize(cw as usize, 0.);
    for yy in 0..ch {
        check(&mut cancel)?;
        io(input.seek(SeekFrom::Start(
            ((y + yy) as u64 * width as u64 + x as u64) * 8,
        )))?;
        io(input.read_exact(&mut raw))?;
        for (v, b) in values.iter_mut().zip(raw.chunks_exact(8)) {
            *v = f64::from_le_bytes(b.try_into().unwrap());
        }
        out.values(&values)?;
    }
    m.reference_sha256 = out.finish()?;
    let bytes = serde_json::to_vec(&m).map_err(|_| "INVALID_MANIFEST")?;
    let mut file = io(OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(tmp.join(".manifest.partial")))?;
    io(file.write_all(&bytes))?;
    io(file.sync_all())?;
    check(&mut cancel)?;
    let plan = sizes(&m, hex(&Sha256::digest(&bytes)))?;
    io(fs::rename(
        tmp.join(".manifest.partial"),
        tmp.join(FILES[0]),
    ))?;
    guard.1 = true;
    drop(guard);
    Ok(CaptureResult {
        capture_ms: at.elapsed().as_secs_f64() * 1000.,
        plan,
    })
}
fn read_values(
    path: &Path,
    count: usize,
    expected_hash: &str,
    cancel: &mut impl FnMut() -> bool,
    mut value: impl FnMut(f64),
) -> R<()> {
    let mut file = io(File::open(path))?;
    let mut raw = vec![0u8; 65536];
    let mut hash = Sha256::new();
    let mut left = count * 8;
    while left > 0 {
        check(cancel)?;
        let bytes = left.min(raw.len());
        io(file.read_exact(&mut raw[..bytes]))?;
        hash.update(&raw[..bytes]);
        for b in raw[..bytes].chunks_exact(8) {
            let v = f64::from_le_bytes(b.try_into().unwrap());
            if !v.is_finite() || !(v as f32).is_finite() {
                return err("NONFINITE_SNAPSHOT");
            };
            value(v);
        }
        left -= bytes;
    }
    if hex(&hash.finalize()) != expected_hash {
        return err("SNAPSHOT_HASH_MISMATCH");
    };
    Ok(())
}
#[cfg(feature = "gpu-diffusion-research")]
pub fn run(
    dir: &Path,
    budget: u64,
    token: u64,
    manifest_sha: &str,
    mut cancel: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    let total = Instant::now();
    check(&mut cancel)?;
    let (m, p) = read_manifest(dir, token)?;
    if p.manifest_sha256 != manifest_sha {
        return err("STALE_PROBE_PLAN");
    }
    if budget > COMBINED_CAP || budget < p.combined_peak_bytes {
        return err("INSUFFICIENT_PROBE_MEMORY");
    }
    let read = Instant::now();
    let n = m.fft_side as usize;
    let ks = 2 * m.radius as usize + 1;
    let pixels = m.roi_width as usize * m.roi_height as usize;
    let mut data = Vec::with_capacity(n * n + ks * ks + n * 2);
    read_values(
        &dir.join(FILES[1]),
        n * n,
        &m.source_sha256,
        &mut cancel,
        |v| data.push(v as f32),
    )?;
    let mut kernel = Vec::with_capacity(ks * ks);
    read_values(
        &dir.join(FILES[2]),
        ks * ks,
        &m.kernel_sha256,
        &mut cancel,
        |v| {
            kernel.push(v);
            data.push(v as f32)
        },
    )?;
    let mut reference = Vec::with_capacity(pixels);
    read_values(
        &dir.join(FILES[3]),
        pixels,
        &m.reference_sha256,
        &mut cancel,
        |v| reference.push(v),
    )?;
    let twiddles = (0..n)
        .map(|i| {
            let a = -2. * std::f64::consts::PI * i as f64 / n as f64;
            [a.cos() as f32, a.sin() as f32]
        })
        .collect::<Vec<_>>();
    data.extend(twiddles.iter().flatten());
    let plan = Plan {
        width: n,
        height: n,
        radius: m.radius as usize,
        side: n,
        core: m.roi_width.max(m.roi_height) as usize,
        scatter: bits(&m.scatter_bits)?,
        kernel,
        twiddles,
        device_bytes: p.device_bytes,
    };
    plan.validate_layout().map_err(|_| "INVALID_PROBE_PLAN")?;
    let preparation_ms = read.elapsed().as_secs_f64() * 1000.;
    check(&mut cancel)?;
    let init = Instant::now();
    let runner = super::gpu::Runner::new(&plan, false).map_err(|_| "GPU_UNAVAILABLE_OR_LIMITS")?;
    let initialization_ms = init.elapsed().as_secs_f64() * 1000.;
    check(&mut cancel)?;
    let (candidate, metrics) = runner
        .run_probe(
            &plan,
            data,
            m.roi_width as usize,
            m.roi_height as usize,
            &mut cancel,
        )
        .map_err(|_| {
            if cancel() {
                "CANCELLED"
            } else {
                "GPU_EXECUTION_FAILED"
            }
        })?;
    check(&mut cancel)?;
    let comparison = Instant::now();
    let error = compare(&reference, &candidate).map_err(|_| "NONFINITE_GPU_OUTPUT")?;
    let comparison_ms = comparison.elapsed().as_secs_f64() * 1000.;
    check(&mut cancel)?;
    Ok(
        serde_json::json!({"ok":true,"schemaVersion":1,"operation":"runGpuDiffusionProbe","diagnosticOnly":true,"outputReplaced":false,"fullRenderMeasured":false,"roiWidth":m.roi_width,"roiHeight":m.roi_height,"originalWidth":m.original_width,"originalHeight":m.original_height,"radius":m.radius,"channel":m.channel,"fftSide":n,"bitExact":error.changed_samples==0,"preparationMs":preparation_ms,"initializationMs":initialization_ms,"comparisonMs":comparison_ms,"totalMs":total.elapsed().as_secs_f64()*1000.,"metrics":metrics,"error":error,"plan":p}),
    )
}
#[cfg(test)]
mod tests {
    use super::*;
    use spektrafilm_model::diffusion::DiffusionFilter;
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
    fn root() -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!(
            "probe-test-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir(&p).unwrap();
        p
    }
    fn source(root: &Path, w: usize, h: usize) -> std::path::PathBuf {
        let p = root.join("original");
        let b = (0..w * h)
            .flat_map(|i| (i as f64 * 0.125).to_le_bytes())
            .collect::<Vec<_>>();
        fs::write(&p, b).unwrap();
        p
    }
    #[test]
    fn snapshot_preserves_original_reflection_kernel_and_reference() {
        let root = root();
        let _guard = Partial(root.clone(), false);
        let (w, h) = (32, 24);
        let src = source(&root, w, h);
        let kernel = kernel(w, h);
        let dir = root.join("probe-7");
        let captured = capture_snapshot(
            &src,
            &src,
            &dir,
            7,
            w as u32,
            h as u32,
            10.,
            "black_pro_mist",
            &kernel,
            1,
            || false,
        )
        .unwrap();
        let (m, p) = read_manifest(&dir, 7).unwrap();
        assert_eq!(p, captured.plan);
        let mut values = Vec::new();
        read_values(
            &dir.join(FILES[1]),
            m.fft_side as usize * m.fft_side as usize,
            &m.source_sha256,
            &mut || false,
            |v| values.push(v),
        )
        .unwrap();
        for y in 0..m.fft_side {
            for x in 0..m.fft_side {
                let sy = reflect(m.roi_y as i64 + y as i64 - m.radius as i64, h as u32);
                let sx = reflect(m.roi_x as i64 + x as i64 - m.radius as i64, w as u32);
                assert_eq!(
                    values[(y * m.fft_side + x) as usize],
                    (sy as usize * w + sx as usize) as f64 * 0.125
                );
            }
        }
        let mut values = Vec::new();
        read_values(
            &dir.join(FILES[2]),
            kernel.side() * kernel.side(),
            &m.kernel_sha256,
            &mut || false,
            |v| values.push(v),
        )
        .unwrap();
        let expected = (0..kernel.side())
            .flat_map(|y| kernel.row(1, y))
            .collect::<Vec<_>>();
        assert!(
            values
                .iter()
                .zip(expected)
                .all(|(a, b)| a.to_bits() == b.to_bits())
        );
        assert!(plan(&dir, 8).is_err());
        assert!(
            capture_snapshot(
                &src,
                &src,
                &dir,
                7,
                w as u32,
                h as u32,
                10.,
                "black_pro_mist",
                &kernel,
                1,
                || false
            )
            .is_err()
        );
        let bytes = fs::read(&src).unwrap();
        assert_eq!(bytes.len(), w * h * 8);
    }
    #[test]
    fn snapshot_cancellation_hash_and_size_fail_closed() {
        let root = root();
        let _guard = Partial(root.clone(), false);
        let src = source(&root, 32, 24);
        let kernel = kernel(32, 24);
        let dir = root.join("probe-9");
        let mut calls = 0;
        assert!(
            capture_snapshot(
                &src,
                &src,
                &dir,
                9,
                32,
                24,
                10.,
                "black_pro_mist",
                &kernel,
                1,
                || {
                    calls += 1;
                    calls > 4
                }
            )
            .is_err()
        );
        assert!(!dir.exists());
        assert!(!root.join("probe-9.partial").exists());
        capture_snapshot(
            &src,
            &src,
            &dir,
            9,
            32,
            24,
            10.,
            "black_pro_mist",
            &kernel,
            1,
            || false,
        )
        .unwrap();
        let (m, _) = read_manifest(&dir, 9).unwrap();
        let path = dir.join(FILES[1]);
        let mut f = OpenOptions::new().write(true).open(&path).unwrap();
        f.write_all(&1f64.to_le_bytes()).unwrap();
        assert_eq!(
            read_values(
                &path,
                m.fft_side as usize * m.fft_side as usize,
                &m.source_sha256,
                &mut || false,
                |_| {}
            )
            .unwrap_err(),
            "SNAPSHOT_HASH_MISMATCH"
        );
        f.set_len(1).unwrap();
        assert!(plan(&dir, 9).is_err());
    }
    #[test]
    fn original_large_psf_is_skipped_not_shrunk() {
        assert!(geometry(3072, 4096, 1999).is_err());
        let (n, cw, ch, _, _) = geometry(3072, 4096, 890).unwrap();
        assert_eq!((n, cw, ch), (2048, 256, 256));
        assert!(geometry(u32::MAX, 1, 1).is_err());
    }
    #[test]
    fn symlinks_and_extra_files_are_rejected() {
        let root = root();
        let _guard = Partial(root.clone(), false);
        let src = source(&root, 32, 24);
        let kernel = kernel(32, 24);
        let dir = root.join("probe-11");
        capture_snapshot(
            &src,
            &src,
            &dir,
            11,
            32,
            24,
            10.,
            "black_pro_mist",
            &kernel,
            1,
            || false,
        )
        .unwrap();
        fs::write(dir.join("extra"), b"x").unwrap();
        assert!(plan(&dir, 11).is_err());
        fs::remove_file(dir.join("extra")).unwrap();
        #[cfg(unix)]
        {
            fs::remove_file(dir.join(FILES[3])).unwrap();
            std::os::unix::fs::symlink(&src, dir.join(FILES[3])).unwrap();
            assert!(plan(&dir, 11).is_err());
        }
    }
}
/// One-shot process gate. Late cancellation for another owner cannot clear a
/// running operation's cancellation. Used by JNI and tested without a GPU.
pub(crate) struct ProcessGate {
    state: std::sync::Mutex<GateState>,
}
struct GateState {
    used: u64,
    cancelled: bool,
    pending: [u64; 8],
    overflow: bool,
}
impl ProcessGate {
    pub const fn new() -> Self {
        Self {
            state: std::sync::Mutex::new(GateState {
                used: 0,
                cancelled: false,
                pending: [0; 8],
                overflow: false,
            }),
        }
    }
    pub fn claim(&self, token: u64) -> R<()> {
        if token == 0 || token > i64::MAX as u64 {
            return err("INVALID_TOKEN");
        }
        let mut s = self.state.lock().map_err(|_| "PROBE_GATE_FAILED")?;
        if s.used != 0 {
            return err("PROBE_PROCESS_ALREADY_USED");
        }
        s.used = token;
        s.cancelled = s.overflow || s.pending.contains(&token);
        Ok(())
    }
    pub fn cancel(&self, token: u64) {
        if token == 0 || token > i64::MAX as u64 {
            return;
        }
        if let Ok(mut s) = self.state.lock() {
            if s.used == token {
                s.cancelled = true;
            } else if s.used == 0 && !s.pending.contains(&token) {
                if let Some(slot) = s.pending.iter_mut().find(|v| **v == 0) {
                    *slot = token;
                } else {
                    s.overflow = true;
                }
            }
        }
    }
    pub fn is_cancelled(&self, token: u64) -> bool {
        match self.state.lock() {
            Ok(s) => s.overflow || (s.used == token && s.cancelled) || s.pending.contains(&token),
            Err(_) => true,
        }
    }
}
#[cfg(test)]
mod gate_tests {
    use super::*;
    #[test]
    fn cancellation_is_sticky_and_one_shot() {
        let g = ProcessGate::new();
        g.cancel(7);
        g.cancel(9);
        g.claim(7).unwrap();
        assert!(g.is_cancelled(7));
        g.cancel(9);
        assert!(g.is_cancelled(7));
        assert!(g.claim(9).is_err());
        assert!(g.claim(7).is_err());
    }
    #[test]
    fn stale_precancel_does_not_cancel_new_owner() {
        let g = ProcessGate::new();
        g.cancel(8);
        g.claim(9).unwrap();
        assert!(!g.is_cancelled(9));
        g.cancel(9);
        g.cancel(8);
        assert!(g.is_cancelled(9));
    }
    #[test]
    fn multiple_precancels_and_overflow_fail_closed() {
        let g = ProcessGate::new();
        g.cancel(8);
        g.cancel(9);
        g.claim(9).unwrap();
        assert!(g.is_cancelled(9));
        let g = ProcessGate::new();
        for t in 1..=9 {
            g.cancel(t)
        }
        g.claim(100).unwrap();
        assert!(g.is_cancelled(100));
    }
    #[test]
    fn concurrent_claim_and_cancel_never_lose_cancellation() {
        for _ in 0..32 {
            let g = std::sync::Arc::new(ProcessGate::new());
            let other = g.clone();
            let worker = std::thread::spawn(move || other.cancel(27));
            g.claim(27).unwrap();
            worker.join().unwrap();
            assert!(g.is_cancelled(27));
        }
    }
}
#[cfg(test)]
mod parity_tests {
    use super::*;
    #[test]
    fn captured_probe_matches_actual_cpu_roi_across_geometry() {
        use spektrafilm_model::diffusion::DiffusionFilter;
        for (w, h) in [(600usize, 500usize), (1, 65), (65, 1), (9, 7)] {
            let root =
                std::env::temp_dir().join(format!("probe-parity-{}-{w}-{h}", std::process::id()));
            fs::create_dir(&root).unwrap();
            let _guard = Partial(root.clone(), false);
            let input = root.join("input");
            let values = (0..w * h)
                .map(|i| {
                    if i % 19 == 0 {
                        12.
                    } else {
                        (i * 7 % 31) as f64 / 17.
                    }
                })
                .collect::<Vec<_>>();
            fs::write(
                &input,
                values
                    .iter()
                    .flat_map(|v| v.to_le_bytes())
                    .collect::<Vec<_>>(),
            )
            .unwrap();
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
            let reference = root.join("reference");
            crate::disk_diffusion::apply_plane(
                &input,
                &reference,
                &root.join("work"),
                w as u32,
                h as u32,
                &kernel,
                1,
                || false,
                |_, _| {},
            )
            .unwrap();
            let dir = root.join("probe-27");
            capture_snapshot(
                &input,
                &reference,
                &dir,
                27,
                w as u32,
                h as u32,
                10.,
                "black_pro_mist",
                &kernel,
                1,
                || false,
            )
            .unwrap();
            let (m, p) = read_manifest(&dir, 27).unwrap();
            let n = m.fft_side as usize;
            let mut input = Vec::new();
            read_values(
                &dir.join(FILES[1]),
                n * n,
                &m.source_sha256,
                &mut || false,
                |v| input.push(v),
            )
            .unwrap();
            let mut coeffs = Vec::new();
            read_values(
                &dir.join(FILES[2]),
                kernel.side() * kernel.side(),
                &m.kernel_sha256,
                &mut || false,
                |v| coeffs.push(v),
            )
            .unwrap();
            let mut expected = Vec::new();
            read_values(
                &dir.join(FILES[3]),
                m.roi_width as usize * m.roi_height as usize,
                &m.reference_sha256,
                &mut || false,
                |v| expected.push(v),
            )
            .unwrap();
            let twiddles = (0..n)
                .map(|i| {
                    let a = -2. * std::f64::consts::PI * i as f64 / n as f64;
                    [a.cos() as f32, a.sin() as f32]
                })
                .collect();
            let plan = Plan {
                width: n,
                height: n,
                radius: kernel.radius,
                side: n,
                core: m.roi_width.max(m.roi_height) as usize,
                scatter: kernel.scatter,
                kernel: coeffs,
                twiddles,
                device_bytes: p.device_bytes,
            };
            let actual = super::super::emulate_probe_f32(
                &plan,
                &input,
                m.roi_width as usize,
                m.roi_height as usize,
            )
            .unwrap();
            let error = compare(&expected, &actual).unwrap();
            assert!(error.max_abs < 1e-5, "{w}x{h}: {error:?}");
            if w == 600 {
                assert!(m.roi_x > 0 && m.roi_y > 0);
            }
            let raw = fs::read(&reference).unwrap();
            for y in 0..m.roi_height as usize {
                for x in 0..m.roi_width as usize {
                    let offset = ((m.roi_y as usize + y) * w + m.roi_x as usize + x) * 8;
                    assert_eq!(
                        expected[y * m.roi_width as usize + x].to_bits(),
                        f64::from_le_bytes(raw[offset..offset + 8].try_into().unwrap()).to_bits()
                    );
                }
            }
        }
    }
}
