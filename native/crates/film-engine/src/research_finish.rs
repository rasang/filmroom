//! Opt-in print/scanner resident f32 core. Original f64 scanner finish stays in main.
use crate::gpu_phase_diagnostics::{self as trace, Phase as GpuPhase, PhaseGuard};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use spektrafilm_core::pipeline::{GpuFinishDescriptor, GpuFinishInput};
use spektrafilm_math::pchip3d::{PreparedPchip3d, pchip_interp};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    time::Instant,
};
#[cfg(feature = "gpu-diffusion-research")]
mod gpu;
pub mod fused;
pub mod streaming;
type R<T> = Result<T, String>;
const MIB: u64 = 1024 * 1024;
const CAP: u64 = 384 * MIB;
// Replace the previous 64 KiB BufWriter/BufReader, rather than adding a second
// table-sized allocation. The explicit buffer remains in the 2 MiB host reserve.
const TABLE_IO_BYTES: usize = 64 * 1024;
const TABLE_IO_SCALARS: usize = TABLE_IO_BYTES / 8;
const HOST_MISC_BYTES: u64 = 2 * MIB;
const _: () = assert!(TABLE_IO_BYTES as u64 <= HOST_MISC_BYTES);
const OP: &str = "fullPrintScanLogXyz";
pub const SHADER: &str = include_str!("shaders/research_finish.wgsl");
fn err<T>(s: &str) -> R<T> {
    Err(s.into())
}
fn io<T>(r: std::io::Result<T>) -> R<T> {
    r.map_err(|_| "GPU_FINISH_IO".into())
}
fn check(c: &mut impl FnMut() -> bool) -> R<()> {
    if c() { err("CANCELLED") } else { Ok(()) }
}
fn hex(b: &[u8]) -> String {
    b.iter().map(|v| format!("{v:02x}")).collect()
}
fn hash_ok(s: &str) -> bool {
    s.len() == 64
        && s.bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
}
fn regular(p: &Path, n: u64) -> R<()> {
    let m = io(fs::symlink_metadata(p))?;
    if m.file_type().is_symlink() || !m.is_file() || m.len() != n {
        return err("INVALID_FINISH_FILE");
    }
    Ok(())
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
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LutSpec {
    size: u32,
    offset: u64,
    min: [f64; 3],
    max: [f64; 3],
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Manifest {
    schema_version: u32,
    operation: String,
    token: u64,
    width: u32,
    height: u32,
    mode: String,
    printing: Option<LutSpec>,
    scanner: LutSpec,
    axis_offset: u64,
    curve_count: u32,
    exposure: f64,
    bw: f64,
    table_scalars: u64,
    table_sha256: String,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FinishPlan {
    pub schema_version: u32,
    pub operation: String,
    pub token: u64,
    pub manifest_sha256: String,
    pub width: u32,
    pub height: u32,
    pub mode: String,
    pub chunk_pixels: u32,
    pub chunk_count: u64,
    pub table_scalars: u64,
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub disk_bytes: u64,
}
fn lut_count(n: usize) -> R<u64> {
    if !(2..=129).contains(&n) {
        return err("GPU_FINISH_UNSUPPORTED");
    }
    Ok((12 * n * n * n + 6 * (n - 1) * (n - 1) * (n - 1)) as u64)
}
fn arrays(p: &PreparedPchip3d) -> [&[f64]; 6] {
    [
        &p.lut,
        &p.slope_x,
        &p.slope_y,
        &p.slope_z,
        &p.cell_min,
        &p.cell_max,
    ]
}
fn lut_valid(p: &PreparedPchip3d) -> R<()> {
    let n = p.size;
    lut_count(n)?;
    let lens = [
        3 * n * n * n,
        3 * n * n * n,
        3 * n * n * n,
        3 * n * n * n,
        3 * (n - 1).pow(3),
        3 * (n - 1).pow(3),
    ];
    for (a, len) in arrays(p).into_iter().zip(lens) {
        if a.len() != len || a.iter().any(|v| !v.is_finite() || !(*v as f32).is_finite()) {
            return err("NONFINITE_FINISH_TABLE");
        }
    }
    Ok(())
}
fn spec(p: &PreparedPchip3d, min: [f64; 3], max: [f64; 3], offset: u64) -> R<LutSpec> {
    lut_valid(p)?;
    for c in 0..3 {
        let range = max[c] as f32 - min[c] as f32;
        if !range.is_finite()
            || range <= 0.
            || !(min[c] as f32).is_finite()
            || !(max[c] as f32).is_finite()
        {
            return err("GPU_FINISH_UNSUPPORTED");
        }
    }
    if (0..3).any(|c| {
        !min[c].is_finite()
            || !max[c].is_finite()
            || max[c] <= min[c]
            || !((p.size - 1) as f32 / (max[c] as f32 - min[c] as f32)).is_finite()
    }) {
        return err("INVALID_FINISH_BOUNDS");
    }
    Ok(LutSpec {
        size: p.size as u32,
        offset,
        min,
        max,
    })
}
fn descriptor_manifest(d: &GpuFinishDescriptor, w: u32, h: u32, token: u64) -> R<Manifest> {
    let mode = match (d.input, d.printing.is_some()) {
        (GpuFinishInput::FilmDensity, true) => "printFromDensity",
        (GpuFinishInput::FilmDensity, false) => "directFilm",
        (GpuFinishInput::PrintRaw, true) => "printFromRaw",
        (GpuFinishInput::PrintExposure, true) => "printExposureOnly",
        _ => return err("GPU_FINISH_UNSUPPORTED"),
    };
    let mut offset = 0;
    let printing = if matches!(mode, "printFromDensity" | "printExposureOnly") {
        let p = d.printing.as_ref().unwrap();
        let s = spec(p.lut, p.data_min, p.data_max, 0)?;
        offset += lut_count(p.lut.size)?;
        Some(s)
    } else {
        None
    };
    let scanner = spec(d.scanner, d.scanner_min, d.scanner_max, offset)?;
    offset += lut_count(d.scanner.size)?;
    let axis_offset = offset;
    let (mut curve_count, mut exposure, mut bw) = (0, 1., 1.);
    if let Some(p) = &d.printing {
        if p.log_exposure.len() < 2
            || p.log_exposure.len() != p.curves.len()
            || p.log_exposure.len() > 65536
            || !p.gamma.is_finite()
            || p.gamma <= 0.
        {
            return err("INVALID_FINISH_CURVE");
        };
        curve_count = p.curves.len() as u32;
        offset += curve_count as u64 * 4;
        exposure = p.print_exposure;
        bw = p.bw;
    }
    let m = Manifest {
        schema_version: 1,
        operation: if mode == "printExposureOnly" {"fullPrintExposure"} else {OP}.into(),
        token,
        width: w,
        height: h,
        mode: mode.into(),
        printing,
        scanner,
        axis_offset,
        curve_count,
        exposure,
        bw,
        table_scalars: offset,
        table_sha256: "0".repeat(64),
    };
    make_plan(&m, String::new())?;
    Ok(m)
}
fn make_plan(m: &Manifest, hash: String) -> R<FinishPlan> {
    if m.schema_version != 1
        || m.operation != if m.mode == "printExposureOnly" {"fullPrintExposure"} else {OP}
        || m.token == 0
        || m.token > i64::MAX as u64
        || m.width == 0
        || m.height == 0
        || m.width > 40000
        || m.height > 40000
        || !hash_ok(&m.table_sha256)
        || !m.exposure.is_finite()
        || !m.bw.is_finite()
        || !(m.exposure as f32).is_finite()
        || !(m.bw as f32).is_finite()
    {
        return err("INVALID_FINISH_MANIFEST");
    }
    let mut off = 0;
    if let Some(p) = &m.printing {
        if !matches!(m.mode.as_str(), "printFromDensity" | "printExposureOnly") || p.offset != 0 {
            return err("INVALID_FINISH_MANIFEST");
        };
        off += lut_count(p.size as usize)?;
    } else if matches!(m.mode.as_str(), "printFromDensity" | "printExposureOnly") {
        return err("INVALID_FINISH_MANIFEST");
    }
    if !["directFilm", "printFromDensity", "printFromRaw", "printExposureOnly"].contains(&m.mode.as_str())
        || m.scanner.offset != off
    {
        return err("INVALID_FINISH_MANIFEST");
    };
    off += lut_count(m.scanner.size as usize)?;
    if m.axis_offset != off
        || m.curve_count > 65536
        || (m.mode == "directFilm" && m.curve_count != 0)
        || (m.mode != "directFilm" && m.curve_count < 2)
    {
        return err("INVALID_FINISH_MANIFEST");
    };
    off += m.curve_count as u64 * 4;
    if off != m.table_scalars {
        return err("INVALID_FINISH_MANIFEST");
    }
    for p in m.printing.iter().chain(std::iter::once(&m.scanner)) {
        for c in 0..3 {
            let range = p.max[c] as f32 - p.min[c] as f32;
            if !range.is_finite()
                || range <= 0.
                || !(p.min[c] as f32).is_finite()
                || !(p.max[c] as f32).is_finite()
            {
                return err("GPU_FINISH_UNSUPPORTED");
            }
            if !p.min[c].is_finite()
                || !p.max[c].is_finite()
                || p.max[c] <= p.min[c]
                || !((p.size - 1) as f32 / (p.max[c] as f32 - p.min[c] as f32)).is_finite()
            {
                return err("INVALID_FINISH_BOUNDS");
            }
        }
    }
    let n = m.width as u64 * m.height as u64;
    let c = n.min(65536);
    let t = m.table_scalars;
    let device = 4 * t + 36 * c + 4096;
    // The 64 KiB table buffer replaces the old buffered file adapter. Keep the
    // same admission total while accounting for that allocation explicitly.
    let host = 16 * t + 128 * c + TABLE_IO_BYTES as u64
        + (HOST_MISC_BYTES - TABLE_IO_BYTES as u64);
    let combined = device + host + 128 * MIB;
    if combined > CAP {
        return err("GPU_FINISH_MEMORY_LIMIT");
    }
    Ok(FinishPlan {
        schema_version: 1,
        operation: m.operation.clone(),
        token: m.token,
        manifest_sha256: hash,
        width: m.width,
        height: m.height,
        mode: m.mode.clone(),
        chunk_pixels: c as u32,
        chunk_count: n.div_ceil(c),
        table_scalars: t,
        device_bytes: device,
        host_bytes: host,
        driver_allowance_bytes: 64 * MIB,
        helper_allowance_bytes: 64 * MIB,
        combined_peak_bytes: combined,
        disk_bytes: 48 * n + 8 * t + 65536,
    })
}
pub fn estimated_disk_bytes(w: u32, h: u32, d: &GpuFinishDescriptor) -> R<u64> {
    Ok(make_plan(&descriptor_manifest(d, w, h, 1)?, String::new())?.disk_bytes)
}
// The serialized packet is unchanged: original f64 values, original ordering,
// explicit little endian, and SHA-256 over precisely the bytes written. Hash and
// file calls operate on bounded chunks instead of once per eight-byte scalar.
fn write_table_values<W: Write>(
    out: &mut W,
    hash: &mut Sha256,
    buffer: &mut Vec<u8>,
    values: impl IntoIterator<Item = R<f64>>,
    cancel: &mut impl FnMut() -> bool,
) -> R<()> {
    use crate::gpu_stage_staging::{io_at, Phase};
    buffer.clear();
    for value in values {
        if buffer.is_empty() {
            check(cancel)?;
        }
        let value = value?;
        if !value.is_finite() || !(value as f32).is_finite() {
            return err("NONFINITE_FINISH_TABLE");
        }
        buffer.extend_from_slice(&value.to_le_bytes());
        if buffer.len() == TABLE_IO_BYTES {
            io_at(Phase::TableWrite, out.write_all(buffer))?;
            hash.update(&*buffer);
            buffer.clear();
        }
    }
    if !buffer.is_empty() {
        io_at(Phase::TableWrite, out.write_all(buffer))?;
        hash.update(&*buffer);
    }
    Ok(())
}
fn write_tables<W: Write>(
    out: &mut W,
    descriptor: &GpuFinishDescriptor,
    include_printing: bool,
    cancel: &mut impl FnMut() -> bool,
) -> R<String> {
    use crate::gpu_stage_staging::{io_at, Phase};
    let mut buffer = Vec::with_capacity(TABLE_IO_BYTES);
    let mut hash = Sha256::new();
    if include_printing {
        for values in arrays(descriptor.printing.as_ref().unwrap().lut) {
            check(cancel)?;
            write_table_values(out, &mut hash, &mut buffer, values.iter().copied().map(Ok), cancel)?;
        }
    }
    for values in arrays(descriptor.scanner) {
        check(cancel)?;
        write_table_values(out, &mut hash, &mut buffer, values.iter().copied().map(Ok), cancel)?;
    }
    if let Some(printing) = &descriptor.printing {
        let mut last = f64::NEG_INFINITY;
        let mut last32 = f32::NEG_INFINITY;
        let axis = printing.log_exposure.iter().map(|&x| {
            let x = if (printing.gamma - 1.).abs() < 1e-12 { x } else { x / printing.gamma };
            if x <= last || (x as f32) <= last32 {
                return err("GPU_FINISH_UNSUPPORTED");
            }
            last = x;
            last32 = x as f32;
            Ok(x)
        });
        write_table_values(out, &mut hash, &mut buffer, axis, cancel)?;
        write_table_values(
            out, &mut hash, &mut buffer,
            printing.curves.iter().flat_map(|row| row.iter().copied()).map(Ok), cancel,
        )?;
    }
    io_at(Phase::TableWrite, out.flush())?;
    Ok(hex(&hash.finalize()))
}
fn read_table_values<Rd: Read>(
    input: &mut Rd,
    count: usize,
    buffer: &mut [u8],
    hash: &mut Sha256,
    cancel: &mut impl FnMut() -> bool,
) -> R<Vec<f64>> {
    debug_assert_eq!(buffer.len(), TABLE_IO_BYTES);
    let mut values = Vec::with_capacity(count);
    while values.len() < count {
        check(cancel)?;
        let len = (count - values.len()).min(TABLE_IO_SCALARS) * 8;
        io(input.read_exact(&mut buffer[..len]))?;
        hash.update(&buffer[..len]);
        for bytes in buffer[..len].chunks_exact(8) {
            let value = f64::from_le_bytes(bytes.try_into().unwrap());
            if !value.is_finite() || !(value as f32).is_finite() {
                return err("NONFINITE_FINISH_TABLE");
            }
            values.push(value);
        }
    }
    Ok(values)
}
pub fn prepare_stage(
    sources: [&Path; 3],
    dir: &Path,
    token: u64,
    w: u32,
    h: u32,
    d: &GpuFinishDescriptor,
    mut cancel: impl FnMut() -> bool,
) -> R<FinishPlan> {
    use crate::gpu_stage_staging::{Phase, io_at};
    check(&mut cancel)?;
    let mut m = descriptor_manifest(d, w, h, token)?;
    owner(dir, token)?;
    crate::gpu_stage_staging::create_stage_dir(dir)?;
    let mut guard = Owned(dir.into(), false);
    crate::gpu_stage_staging::snapshot_sources(
        sources,
        dir,
        w as u64 * h as u64,
        24 * w as u64 * h as u64,
        &mut cancel,
    )?;
    let mut out = io_at(
        Phase::TableCreate,
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(dir.join("tables.f64le")),
    )?;
    let table_sha256 = write_tables(&mut out, d, m.printing.is_some(), &mut cancel)?;
    io_at(Phase::TableSync, out.sync_all())?;
    m.table_sha256 = table_sha256;
    let b = serde_json::to_vec(&m).map_err(|_| "INVALID_FINISH_MANIFEST")?;
    let p = make_plan(&m, hex(&Sha256::digest(&b)))?;
    check(&mut cancel)?;
    let mut f = io_at(
        Phase::ManifestCreate,
        OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(dir.join(".manifest.partial")),
    )?;
    io_at(Phase::ManifestWrite, f.write_all(&b))?;
    io_at(Phase::ManifestSync, f.sync_all())?;
    io_at(
        Phase::ManifestRename,
        fs::rename(dir.join(".manifest.partial"), dir.join("manifest.json")),
    )?;
    guard.1 = true;
    Ok(p)
}
fn owner(dir: &Path, token: u64) -> R<()> {
    if dir.file_name().and_then(|x| x.to_str()) != Some(format!("gpu-stage-{token}").as_str()) {
        return err("INVALID_FINISH_OWNER");
    }
    Ok(())
}
fn load(dir: &Path, token: u64) -> R<(Manifest, FinishPlan)> {
        trace::phase_result(GpuPhase::Prepare, || {
    owner(dir, token)?;
    let meta = io(fs::symlink_metadata(dir))?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return err("INVALID_FINISH_OWNER");
    };
    let mut names = Vec::new();
    for e in io(fs::read_dir(dir))?.take(8) {
        let e = io(e)?;
        let name = e.file_name().to_string_lossy().into_owned();
        if name == "candidate" {
            let t = io(e.file_type())?;
            if !t.is_dir() || t.is_symlink() {
                return err("INVALID_FINISH_FILE");
            }
        } else {
            names.push(name)
        }
    }
    names.sort();
    if names
        != [
            "manifest.json",
            "source-0.f64le",
            "source-1.f64le",
            "source-2.f64le",
            "tables.f64le",
        ]
    {
        return err("INVALID_FINISH_FILES");
    }
    let mp = dir.join("manifest.json");
    let meta = io(fs::symlink_metadata(&mp))?;
    if !meta.is_file() || meta.file_type().is_symlink() || meta.len() > 8192 {
        return err("INVALID_FINISH_MANIFEST");
    };
    let b = io(fs::read(mp))?;
    let m: Manifest = serde_json::from_slice(&b).map_err(|_| "INVALID_FINISH_MANIFEST")?;
    if m.token != token {
        return err("INVALID_FINISH_OWNER");
    };
    let p = make_plan(&m, hex(&Sha256::digest(&b)))?;
    for c in 0..3 {
        regular(
            &dir.join(format!("source-{c}.f64le")),
            m.width as u64 * m.height as u64 * 8,
        )?
    }
    regular(&dir.join("tables.f64le"), m.table_scalars * 8)?;
    Ok((m, p))

        })
    }
pub fn plan_stage(dir: &Path, token: u64) -> R<FinishPlan> {
    load(dir, token).map(|(_, p)| p)
}
struct Tables {
    printing: Option<PreparedPchip3d>,
    scanner: PreparedPchip3d,
    axis: Vec<f64>,
    curves: Vec<[f64; 3]>,
}
impl Tables {
    fn load(dir: &Path, m: &Manifest, cancel: &mut impl FnMut() -> bool) -> R<Self> {
        let mut f = io(File::open(dir.join("tables.f64le")))?;
        let mut buffer = vec![0u8; TABLE_IO_BYTES];
        let mut hash = Sha256::new();
        let mut read = |n: usize| -> R<Vec<f64>> {
            read_table_values(&mut f, n, &mut buffer, &mut hash, cancel)
        };
        let mut lut = |n: usize| -> R<PreparedPchip3d> {
            let a = 3 * n * n * n;
            let b = 3 * (n - 1).pow(3);
            Ok(PreparedPchip3d {
                size: n,
                lut: read(a)?,
                slope_x: read(a)?,
                slope_y: read(a)?,
                slope_z: read(a)?,
                cell_min: read(b)?,
                cell_max: read(b)?,
            })
        };
        let printing = m
            .printing
            .as_ref()
            .map(|p| lut(p.size as usize))
            .transpose()?;
        let scanner = lut(m.scanner.size as usize)?;
        drop(lut);
        let axis = read(m.curve_count as usize)?;
        let curve_flat = read(m.curve_count as usize * 3)?;
        drop(read);
        if hex(&hash.finalize()) != m.table_sha256 {
            return err("INVALID_FINISH_TABLE_HASH");
        };
        let curves = curve_flat
            .chunks_exact(3)
            .map(|r| [r[0], r[1], r[2]])
            .collect();
        if axis
            .windows(2)
            .any(|x| x[1] <= x[0] || x[1] as f32 <= x[0] as f32)
        {
            return err("GPU_FINISH_UNSUPPORTED");
        };
        for p in printing.iter().chain(std::iter::once(&scanner)) {
            if p.cell_min.iter().zip(&p.cell_max).any(|(a, b)| a > b) {
                return err("INVALID_FINISH_TABLE");
            }
        }
        Ok(Self {
            printing,
            scanner,
            axis,
            curves,
        })
    }
    fn flat32(&self) -> Vec<f32> {
        let capacity = self
            .printing
            .iter()
            .chain(std::iter::once(&self.scanner))
            .map(|p| arrays(p).iter().map(|a| a.len()).sum::<usize>())
            .sum::<usize>()
            + self.axis.len()
            + self.curves.len() * 3;
        let mut v = Vec::with_capacity(capacity);
        for p in self.printing.iter().chain(std::iter::once(&self.scanner)) {
            for a in arrays(p) {
                v.extend(a.iter().map(|&v| v as f32))
            }
        }
        v.extend(self.axis.iter().map(|&x| x as f32));
        v.extend(self.curves.iter().flatten().map(|&x| x as f32));
        v
    }
    fn reference(&self, m: &Manifest, mut x: [f64; 3]) -> [f64; 3] {
        if let (Some(p), Some(s)) = (&self.printing, &m.printing) {
            x = eval64(p, s, x);
            for v in &mut x {
                *v = 10f64.powf(*v) * m.exposure * m.bw;
            }
        }
        if m.mode == "printExposureOnly" { return x; }
        if !self.axis.is_empty() {
            for c in 0..3 {
                let q = (x[c].max(0.) + 1e-10).log10();
                let i = self.axis.partition_point(|&v| v <= q);
                x[c] = if i == 0 {
                    self.curves[0][c]
                } else if i >= self.axis.len() {
                    self.curves[self.axis.len() - 1][c]
                } else {
                    let inv = 1. / (self.axis[i] - self.axis[i - 1]);
                    let t = (q - self.axis[i - 1]) * inv;
                    self.curves[i - 1][c] + t * (self.curves[i][c] - self.curves[i - 1][c])
                };
            }
        }
        eval64(&self.scanner, &m.scanner, x)
    }
}
fn eval64(p: &PreparedPchip3d, s: &LutSpec, x: [f64; 3]) -> [f64; 3] {
    let q = std::array::from_fn::<_, 3, _>(|c| {
        (x[c] - s.min[c]) * ((p.size - 1) as f64 / (s.max[c] - s.min[c]))
    });
    pchip_interp(p, q[0], q[1], q[2])
}
// Same tensor Hermite order and cell clamp as the reference, narrowed only for
// the explicitly experimental f32 shader mirror. No trilinear replacement.
fn eval32(table: &[f32], s: &LutSpec, x: [f32; 3]) -> [f32; 3] {
    fn h(a: f32, b: f32, ma: f32, mb: f32, t: f32) -> f32 {
        let t2 = t * t;
        let t3 = t2 * t;
        let h00 = 2. * t3 - 3. * t2 + 1.;
        let h10 = t3 - 2. * t2 + t;
        let h01 = -2. * t3 + 3. * t2;
        let h11 = t3 - t2;
        h00 * a + h10 * ma + h01 * b + h11 * mb
    }
    fn mix(a: f32, b: f32, t: f32) -> f32 {
        a + t * (b - a)
    }
    let n = s.size as usize;
    let count = 3 * n * n * n;
    let cells = 3 * (n - 1).pow(3);
    let mut base = [0; 3];
    let mut t = [0.; 3];
    for c in 0..3 {
        let raw = (x[c] - s.min[c] as f32) * ((n - 1) as f32 / (s.max[c] as f32 - s.min[c] as f32));
        if !raw.is_finite() {
            return [f32::NAN; 3];
        }
        let q = raw.clamp(0., (n - 1) as f32);
        base[c] = (q.floor() as usize).min(n - 2);
        t[c] = q - base[c] as f32;
    }
    let [i, j, k] = base;
    let [tx, ty, tz] = t;
    let at = |kind: usize, i: usize, j: usize, k: usize, c: usize| {
        table[s.offset as usize + kind * count + ((i * n + j) * n + k) * 3 + c]
    };
    std::array::from_fn(|c| {
        let mut vx = [[0.; 2]; 2];
        let mut sy = [[0.; 2]; 2];
        for y in 0..2 {
            for z in 0..2 {
                vx[y][z] = h(
                    at(0, i, j + y, k + z, c),
                    at(0, i + 1, j + y, k + z, c),
                    at(1, i, j + y, k + z, c),
                    at(1, i + 1, j + y, k + z, c),
                    tx,
                );
                sy[y][z] = mix(at(2, i, j + y, k + z, c), at(2, i + 1, j + y, k + z, c), tx);
            }
        }
        let a = h(vx[0][0], vx[1][0], sy[0][0], sy[1][0], ty);
        let b = h(vx[0][1], vx[1][1], sy[0][1], sy[1][1], ty);
        let zsl = |z| {
            mix(
                mix(at(3, i, j, k + z, c), at(3, i + 1, j, k + z, c), tx),
                mix(at(3, i, j + 1, k + z, c), at(3, i + 1, j + 1, k + z, c), tx),
                ty,
            )
        };
        let ci = ((i * (n - 1) + j) * (n - 1) + k) * 3 + c;
        {
            let value = h(a, b, zsl(0), zsl(1), tz);
            if !value.is_finite() {
                return f32::NAN;
            }
            value
        }
        .clamp(
            table[s.offset as usize + 4 * count + ci],
            table[s.offset as usize + 4 * count + cells + ci],
        )
    })
}
fn mirror(table: &[f32], m: &Manifest, mut x: [f32; 3]) -> [f32; 3] {
    if let Some(p) = &m.printing {
        x = eval32(table, p, x);
        for v in &mut x {
            *v = 10f32.powf(*v);
            if !v.is_finite() {
                return [f32::NAN; 3];
            }
            *v *= m.exposure as f32;
            if !v.is_finite() {
                return [f32::NAN; 3];
            }
            *v *= m.bw as f32;
        }
    }
    if x.iter().any(|v| !v.is_finite()) {
        return [f32::NAN; 3];
    }
    if m.mode == "printExposureOnly" { return x; }
    if m.curve_count > 0 {
        let n = m.curve_count as usize;
        let a = m.axis_offset as usize;
        let axis = &table[a..a + n];
        for c in 0..3 {
            let q = (x[c].max(0.) + 1e-10).ln() / 10f32.ln();
            let i = axis.partition_point(|&v| v <= q);
            let curve = |i: usize| table[a + n + i * 3 + c];
            x[c] = if i == 0 {
                curve(0)
            } else if i >= n {
                curve(n - 1)
            } else {
                let inv = 1. / (axis[i] - axis[i - 1]);
                let t = (q - axis[i - 1]) * inv;
                curve(i - 1) + t * (curve(i) - curve(i - 1))
            };
        }
    }
    eval32(table, &m.scanner, x)
}
#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Complete {
    schema_version: u32,
    token: u64,
    manifest_sha256: String,
    width: u32,
    height: u32,
    output_sha256: String,
}
fn hash_output(path: &Path, n: u64, cancel: &mut impl FnMut() -> bool) -> R<String> {
    regular(path, n * 8)?;
    let mut f = std::io::BufReader::with_capacity(65536, io(File::open(path))?);
    let mut h = Sha256::new();
    for i in 0..n {
        if i % 8192 == 0 {
            check(cancel)?
        }
        let mut b = [0; 8];
        io(f.read_exact(&mut b))?;
        if !f64::from_le_bytes(b).is_finite() {
            return err("NONFINITE_FINISH_OUTPUT");
        };
        h.update(b)
    }
    Ok(hex(&h.finalize()))
}
pub fn validate_output(
    dir: &Path,
    token: u64,
    sha: &str,
    mut cancel: impl FnMut() -> bool,
) -> R<PathBuf> {
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != sha {
        return err("INVALID_FINISH_IDENTITY");
    };
    let d = dir.join("candidate");
    let meta = io(fs::symlink_metadata(&d))?;
    if !meta.is_dir() || meta.file_type().is_symlink() {
        return err("INVALID_FINISH_OUTPUT");
    };
    let mut names = io(fs::read_dir(&d))?
        .take(4)
        .map(|x| x.map(|e| e.file_name()))
        .collect::<std::io::Result<Vec<_>>>()
        .map_err(|_| "GPU_FINISH_IO")?;
    names.sort();
    if names != ["candidate.f64le", "complete.json"].map(std::ffi::OsString::from) {
        return err("INVALID_FINISH_OUTPUT");
    };
    let cp = d.join("complete.json");
    let cm = io(fs::symlink_metadata(&cp))?;
    if !cm.is_file() || cm.file_type().is_symlink() || cm.len() > 4096 {
        return err("INVALID_FINISH_OUTPUT");
    };
    let c: Complete =
        serde_json::from_slice(&io(fs::read(cp))?).map_err(|_| "INVALID_FINISH_OUTPUT")?;
    if c.schema_version != 1
        || c.token != token
        || c.manifest_sha256 != sha
        || c.width != m.width
        || c.height != m.height
    {
        return err("INVALID_FINISH_IDENTITY");
    };
    let out = d.join("candidate.f64le");
    if hash_output(&out, m.width as u64 * m.height as u64 * 3, &mut cancel)? != c.output_sha256 {
        return err("INVALID_FINISH_OUTPUT_HASH");
    };
    Ok(out)
}
fn run_inner(
    dir: &Path,
    token: u64,
    budget: u64,
    sha: &str,
    mut cancel: impl FnMut() -> bool,
    host_mirror: bool,
) -> R<serde_json::Value> {
    let started = Instant::now();
    check(&mut cancel)?;
    let (m, p) = load(dir, token)?;
    if p.manifest_sha256 != sha {
        return err("INVALID_FINISH_IDENTITY");
    };
    if budget < p.combined_peak_bytes {
        return err("INSUFFICIENT_GPU_FINISH_MEMORY");
    };
    let tables = Tables::load(dir, &m, &mut cancel)?;
    let flat = tables.flat32();
    #[cfg(feature = "gpu-diffusion-research")]
    let mut runner = if host_mirror {
        None
    } else {
        Some(gpu::Runner::new(&m, &p, &flat)?)
    };
    #[cfg(not(feature = "gpu-diffusion-research"))]
    if !host_mirror {
        return err("GPU_FINISH_UNSUPPORTED");
    }
    let candidate = dir.join("candidate");
    io(fs::create_dir(&candidate))?;
    let mut guard = Owned(candidate.clone(), false);
    let mut src = Vec::new();
    for c in 0..3 {
        src.push(std::io::BufReader::with_capacity(
            65536,
            io(File::open(dir.join(format!("source-{c}.f64le"))))?,
        ));
    }
    let mut out = std::io::BufWriter::with_capacity(
        65536,
        io(OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(candidate.join("candidate.f64le")))?,
    );
    let mut hash = Sha256::new();
    let mut sample_count = 0u64;
    let (mut max_abs, mut square) = (0f64, 0f64);
    let mut remaining = m.width as u64 * m.height as u64;
    let mut chunks = 0u64;
    while remaining > 0 {
        check(&mut cancel)?;
        if started.elapsed().as_secs() >= 110 {
            return err("GPU_FINISH_TIMEOUT");
        };
        let n = remaining.min(p.chunk_pixels as u64) as usize;
        let mut input = vec![0f64; n * 3];
        for c in 0..3 {
            for i in 0..n {
                let mut b = [0; 8];
                io(src[c].read_exact(&mut b))?;
                let v = f64::from_le_bytes(b);
                if !v.is_finite() || !(v as f32).is_finite() {
                    return err("NONFINITE_FINISH_INPUT");
                };
                input[i * 3 + c] = v;
            }
        }
        let input32: Vec<f32> = input.iter().map(|&x| x as f32).collect();
        let output: Vec<f32> = if host_mirror {
            input32
                .chunks_exact(3)
                .flat_map(|x| mirror(&flat, &m, [x[0], x[1], x[2]]))
                .collect()
        } else {
            #[cfg(feature = "gpu-diffusion-research")]
            {
                runner.as_mut().unwrap().dispatch(&input32, &mut cancel)?
            }
            #[cfg(not(feature = "gpu-diffusion-research"))]
            {
                return err("GPU_FINISH_UNSUPPORTED");
            }
        };
        if output.len() != n * 3 || output.iter().any(|v| !v.is_finite()) {
            return err("NONFINITE_FINISH_OUTPUT");
        }
        let guard_phase=PhaseGuard::begin(GpuPhase::Guard);
        let samples = n.min(8);
        for j in 0..samples {
            let i = if samples == 1 {
                0
            } else {
                j * (n - 1) / (samples - 1)
            };
            let reference =
                tables.reference(&m, [input[i * 3], input[i * 3 + 1], input[i * 3 + 2]]);
            for c in 0..3 {
                let e = (output[i * 3 + c] as f64 - reference[c]).abs();
                if !e.is_finite() || e > 2e-5 * reference[c].abs().max(1.) {
                    return err("GPU_FINISH_NUMERIC_CHECK");
                };
                sample_count += 1;
                max_abs = max_abs.max(e);
                square += e * e;
            }
        }
        guard_phase.finish();
        for v in output {
            let b = (v as f64).to_le_bytes();
            io(out.write_all(&b))?;
            hash.update(b)
        }
        remaining -= n as u64;
        chunks += 1;
    }
    let commit_phase=PhaseGuard::begin(GpuPhase::Commit);
    io(out.flush())?;
    io(out.get_ref().sync_all())?;
    check(&mut cancel)?;
    let complete = Complete {
        schema_version: 1,
        token,
        manifest_sha256: sha.into(),
        width: m.width,
        height: m.height,
        output_sha256: hex(&hash.finalize()),
    };
    let b = serde_json::to_vec(&complete).map_err(|_| "INVALID_FINISH_OUTPUT")?;
    let mut c = io(OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(candidate.join(".complete.partial")))?;
    io(c.write_all(&b))?;
    io(c.sync_all())?;
    check(&mut cancel)?;
    io(fs::rename(
        candidate.join(".complete.partial"),
        candidate.join("complete.json"),
    ))?;
    commit_phase.finish();
    guard.1 = true;
    #[allow(unused_mut)]
    let (mut adapter, mut vendor, mut f64cap) = ("host_f32_mirror".to_string(), 0u32, false);
    #[cfg(feature = "gpu-diffusion-research")]
    if let Some(r) = runner {
        adapter = r.adapter;
        vendor = r.vendor;
        f64cap = r.f64_cap;
    }
    Ok(
        serde_json::json!({"ok":true,"schemaVersion":1,"operation":m.operation,"token":token,"manifestSha256":sha,"gpuUsed":!host_mirror,"fullImage":true,"width":m.width,"height":m.height,"channels":3,"mode":m.mode,"chunkCount":chunks,"elapsedMs":started.elapsed().as_secs_f64()*1000.,"backend":if host_mirror{"host f32 mirror; not GPU timing"}else if m.mode=="printExposureOnly"{"Vulkan f32 original print exposure"}else{"Vulkan f32 resident PCHIP chain; CPU f64 scan finish"},"adapter":adapter,"vendor":vendor,"shaderF64Advertised":f64cap,"uploadBytes":4*m.table_scalars+12*m.width as u64*m.height as u64+chunks*256,"readbackBytes":12*m.width as u64*m.height as u64,"dispatches":chunks,"deviceBytes":p.device_bytes,"sampleCount":sample_count,"sampleMaxAbs":max_abs,"sampleRms":(square/sample_count as f64).sqrt(),"sampleToleranceAbsolute":2e-5,"sampleToleranceRelative":2e-5}),
    )
}
pub fn run_stage(
    dir: &Path,
    token: u64,
    budget: u64,
    sha: &str,
    cancel: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    run_inner(dir, token, budget, sha, cancel, false)
}
#[cfg(test)]
pub(crate) fn run_stage_cpu_mirror_for_test(
    dir: &Path,
    token: u64,
    sha: &str,
    cancel: impl FnMut() -> bool,
) -> R<serde_json::Value> {
    let p = plan_stage(dir, token)?;
    run_inner(dir, token, p.combined_peak_bytes, sha, cancel, true)
}
#[cfg(test)]
mod tests;

#[cfg(test)]
mod table_io_tests {
    use super::*;
    use std::io::{self as stdio, BufReader, BufWriter, Cursor};
    use spektrafilm_core::pipeline::GpuPrintingDescriptor;
    use spektrafilm_math::pchip3d::prepare_pchip_3d;

    // Frozen scalar implementation from the delivered packet writer. Kept only
    // in tests to establish byte/digest parity and matched host timings.
    fn scalar_write_tables<W: Write>(out: &mut W, d: &GpuFinishDescriptor, printing: bool) -> R<String> {
        let mut hash = Sha256::new();
        let mut put = |v: f64| -> R<()> {
            if !v.is_finite() || !(v as f32).is_finite() { return err("NONFINITE_FINISH_TABLE"); }
            let bytes = v.to_le_bytes();
            io(out.write_all(&bytes))?;
            hash.update(bytes);
            Ok(())
        };
        if printing {
            for array in arrays(d.printing.as_ref().unwrap().lut) { for &v in array { put(v)?; } }
        }
        for array in arrays(d.scanner) { for &v in array { put(v)?; } }
        if let Some(p) = &d.printing {
            let mut last = f64::NEG_INFINITY;
            let mut last32 = f32::NEG_INFINITY;
            for &x in p.log_exposure {
                let x = if (p.gamma - 1.).abs() < 1e-12 { x } else { x / p.gamma };
                if x <= last || (x as f32) <= last32 { return err("GPU_FINISH_UNSUPPORTED"); }
                put(x)?;
                last = x;
                last32 = x as f32;
            }
            for row in p.curves { for &v in row { put(v)?; } }
        }
        drop(put);
        io(out.flush())?;
        Ok(hex(&hash.finalize()))
    }
    fn scalar_read_values<Rd: Read>(reader: &mut Rd, n: usize, hash: &mut Sha256, c: &mut impl FnMut() -> bool) -> R<Vec<f64>> {
        let mut result = Vec::with_capacity(n);
        for i in 0..n {
            if i % 8192 == 0 { check(c)?; }
            let mut bytes = [0; 8];
            io(reader.read_exact(&mut bytes))?;
            hash.update(bytes);
            let v = f64::from_le_bytes(bytes);
            if !v.is_finite() || !(v as f32).is_finite() { return err("NONFINITE_FINISH_TABLE"); }
            result.push(v);
        }
        Ok(result)
    }
    fn values(n: usize) -> Vec<f64> {
        let cases = [0., -0., f64::from_bits(1), -f64::from_bits(1), f32::MIN_POSITIVE as f64,
            f32::from_bits(1) as f64, 1e20, -1e20, f32::MAX as f64, -1.25];
        (0..n).map(|i| cases[i % cases.len()]).collect()
    }
    fn bits(v: &[f64]) -> Vec<u64> { v.iter().map(|x| x.to_bits()).collect() }
    fn encode(values: &[f64]) -> R<(Vec<u8>, String)> {
        let mut out = Vec::new();
        let mut hash = Sha256::new();
        let mut buffer = Vec::with_capacity(TABLE_IO_BYTES);
        write_table_values(&mut out, &mut hash, &mut buffer, values.iter().copied().map(Ok), &mut || false)?;
        assert_eq!(buffer.capacity(), TABLE_IO_BYTES);
        Ok((out, hex(&hash.finalize())))
    }
    #[test]
    fn exact_scalar_bytes_digest_and_decode_across_partial_chunks() {
        for n in [0, 1, TABLE_IO_SCALARS - 1, TABLE_IO_SCALARS, TABLE_IO_SCALARS + 1, TABLE_IO_SCALARS * 2 + 7] {
            let expected = values(n);
            let scalar_bytes: Vec<u8> = expected.iter().flat_map(|v| v.to_le_bytes()).collect();
            let (bytes, digest) = encode(&expected).unwrap();
            assert_eq!(bytes, scalar_bytes);
            assert_eq!(digest, hex(&Sha256::digest(&scalar_bytes)));
            let mut hash = Sha256::new();
            let mut buffer = vec![0; TABLE_IO_BYTES];
            let actual = read_table_values(&mut Cursor::new(&bytes), n, &mut buffer, &mut hash, &mut || false).unwrap();
            let mut old_hash = Sha256::new();
            let old = scalar_read_values(&mut Cursor::new(&bytes), n, &mut old_hash, &mut || false).unwrap();
            assert_eq!(bits(&actual), bits(&expected));
            assert_eq!(bits(&actual), bits(&old));
            assert_eq!(hex(&hash.finalize()), digest);
            assert_eq!(hex(&old_hash.finalize()), digest);
        }
    }
    #[test]
    fn nonfinite_and_f32_overflow_are_rejected_in_every_chunk() {
        for bad in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, f64::MAX] {
            for index in [0, TABLE_IO_SCALARS - 1, TABLE_IO_SCALARS] {
                let mut data = values(TABLE_IO_SCALARS + 2);
                data[index] = bad;
                assert_eq!(encode(&data).unwrap_err(), "NONFINITE_FINISH_TABLE");
                let bytes: Vec<u8> = data.iter().flat_map(|v| v.to_le_bytes()).collect();
                let new = read_table_values(&mut Cursor::new(&bytes), data.len(), &mut vec![0; TABLE_IO_BYTES], &mut Sha256::new(), &mut || false);
                let old = scalar_read_values(&mut Cursor::new(&bytes), data.len(), &mut Sha256::new(), &mut || false);
                assert_eq!(new.unwrap_err(), old.unwrap_err());
            }
        }
    }
    #[test]
    fn cancellation_is_checked_at_most_8192_scalars_apart() {
        let data = values(TABLE_IO_SCALARS * 3);
        let mut calls = 0;
        let mut out = Vec::new();
        let result = write_table_values(&mut out, &mut Sha256::new(), &mut Vec::with_capacity(TABLE_IO_BYTES), data.iter().copied().map(Ok), &mut || { calls += 1; calls == 2 });
        assert_eq!(result.unwrap_err(), "CANCELLED");
        assert_eq!(out.len(), TABLE_IO_BYTES);
        let bytes: Vec<u8> = data.iter().flat_map(|v| v.to_le_bytes()).collect();
        let mut cursor = Cursor::new(bytes);
        let mut calls = 0;
        let result = read_table_values(&mut cursor, data.len(), &mut vec![0; TABLE_IO_BYTES], &mut Sha256::new(), &mut || { calls += 1; calls == 2 });
        assert_eq!(result.unwrap_err(), "CANCELLED");
        assert_eq!(cursor.position(), TABLE_IO_BYTES as u64);
    }
    struct ShortWriter { bytes: Vec<u8>, fail_at: Option<usize> }
    impl Write for ShortWriter {
        fn write(&mut self, bytes: &[u8]) -> stdio::Result<usize> {
            if self.fail_at == Some(self.bytes.len()) { return Err(stdio::Error::from_raw_os_error(28)); }
            let count = bytes.len().min(17).min(self.fail_at.map_or(usize::MAX, |n| n - self.bytes.len()));
            self.bytes.extend_from_slice(&bytes[..count]);
            Ok(count)
        }
        fn flush(&mut self) -> stdio::Result<()> { Ok(()) }
    }
    struct ShortReader { cursor: Cursor<Vec<u8>>, fail_at: Option<u64> }
    impl Read for ShortReader {
        fn read(&mut self, bytes: &mut [u8]) -> stdio::Result<usize> {
            if self.fail_at == Some(self.cursor.position()) { return Err(stdio::Error::from_raw_os_error(5)); }
            let count = bytes.len().min(19).min(self.fail_at.map_or(usize::MAX, |n| (n - self.cursor.position()) as usize));
            self.cursor.read(&mut bytes[..count])
        }
    }
    #[test]
    fn partial_io_faults_and_truncation_fail_closed() {
        let data = values(TABLE_IO_SCALARS + 31);
        let (bytes, digest) = encode(&data).unwrap();
        let mut writer = ShortWriter { bytes: Vec::new(), fail_at: None };
        let mut hash = Sha256::new();
        write_table_values(&mut writer, &mut hash, &mut Vec::with_capacity(TABLE_IO_BYTES), data.iter().copied().map(Ok), &mut || false).unwrap();
        assert_eq!(writer.bytes, bytes);
        assert_eq!(hex(&hash.finalize()), digest);
        let mut reader = ShortReader { cursor: Cursor::new(bytes.clone()), fail_at: None };
        let actual = read_table_values(&mut reader, data.len(), &mut vec![0; TABLE_IO_BYTES], &mut Sha256::new(), &mut || false).unwrap();
        assert_eq!(bits(&actual), bits(&data));
        for failure in [0, TABLE_IO_BYTES + 31] {
            let mut writer = ShortWriter { bytes: Vec::new(), fail_at: Some(failure) };
            let error = write_table_values(&mut writer, &mut Sha256::new(), &mut Vec::with_capacity(TABLE_IO_BYTES), data.iter().copied().map(Ok), &mut || false).unwrap_err();
            assert_eq!(error, "GPU_PREPARE_TABLE_WRITE_ENOSPC");
            let mut reader = ShortReader { cursor: Cursor::new(bytes.clone()), fail_at: Some(failure as u64) };
            assert_eq!(read_table_values(&mut reader, data.len(), &mut vec![0; TABLE_IO_BYTES], &mut Sha256::new(), &mut || false).unwrap_err(), "GPU_FINISH_IO");
        }
        for removed in [1, 8, 31 * 8 + 1] {
            assert_eq!(read_table_values(&mut Cursor::new(&bytes[..bytes.len() - removed]), data.len(), &mut vec![0; TABLE_IO_BYTES], &mut Sha256::new(), &mut || false).unwrap_err(), "GPU_FINISH_IO");
        }
    }
    fn fixture_lut(n: usize) -> PreparedPchip3d {
        let mut data = Vec::with_capacity(3 * n.pow(3));
        for x in 0..n { for y in 0..n { for z in 0..n {
            data.extend([x as f64 / (n - 1) as f64, y as f64 / (n - 1) as f64, z as f64 / (n - 1) as f64]);
        } } }
        prepare_pchip_3d(data, n)
    }
    fn descriptor<'a>(lut: &'a PreparedPchip3d, axis: &'a [f64], curves: &'a [[f64; 3]], mode: GpuFinishInput, gamma: f64) -> GpuFinishDescriptor<'a> {
        GpuFinishDescriptor {
            input: mode,
            printing: Some(GpuPrintingDescriptor { lut, data_min: [0.; 3], data_max: [1.; 3], print_exposure: 0.75, bw: 1.2, log_exposure: axis, curves, gamma, external_diffusion_required: false }),
            scanner: lut, scanner_min: [0.; 3], scanner_max: [1.; 3],
        }
    }
    #[test]
    fn complete_packets_match_scalar_reference_for_all_finish_modes() {
        let lut = fixture_lut(17);
        let axis = [-10., -1., -0., 1., 10.];
        let curves = [[-0.; 3], [f64::from_bits(1); 3], [0.5; 3], [1e20; 3], [f32::MAX as f64; 3]];
        for mode in [GpuFinishInput::FilmDensity, GpuFinishInput::PrintRaw, GpuFinishInput::PrintExposure] {
            for gamma in [1., 1.1] {
                let d = descriptor(&lut, &axis, &curves, mode, gamma);
                let m = descriptor_manifest(&d, 512, 384, 7).unwrap();
                let mut old = Vec::new();
                let old_hash = scalar_write_tables(&mut old, &d, m.printing.is_some()).unwrap();
                let mut new = Vec::new();
                let new_hash = write_tables(&mut new, &d, m.printing.is_some(), &mut || false).unwrap();
                assert_eq!(new, old);
                assert_eq!(new_hash, old_hash);
                assert_eq!(new.len() as u64, m.table_scalars * 8);
                let plan = make_plan(&m, "0".repeat(64)).unwrap();
                assert_eq!(plan.host_bytes, 16 * m.table_scalars + 128 * 65536 + 2 * MIB);
            }
        }
        let d = GpuFinishDescriptor { input: GpuFinishInput::FilmDensity, printing: None, scanner: &lut, scanner_min: [0.; 3], scanner_max: [1.; 3] };
        let mut old = Vec::new();
        let expected_hash = scalar_write_tables(&mut old, &d, false).unwrap();
        let mut new = Vec::new();
        assert_eq!(write_tables(&mut new, &d, false, &mut || false).unwrap(), expected_hash);
        assert_eq!(new, old);
    }
    #[test]
    fn packet_loader_keeps_hash_axis_bounds_and_cancellation_guards() {
        let root = super::tests::temp();
        let (src, _) = super::tests::fixture(&root, 5, 2);
        let lut = fixture_lut(17);
        let axis = [-10., -1., 0., 1., 10.];
        let curves = [[0.; 3], [0.2; 3], [0.5; 3], [0.7; 3], [1.; 3]];
        let d = descriptor(&lut, &axis, &curves, GpuFinishInput::FilmDensity, 1.1);
        let dir = root.join("gpu-stage-7");
        let table_path = dir.join("tables.f64le");
        assert_eq!(prepare_stage([&src[0], &src[1], &src[2]], &dir, 7, 5, 2, &d, || fs::metadata(&table_path).is_ok_and(|m| m.len() >= TABLE_IO_BYTES as u64)).unwrap_err(), "CANCELLED");
        assert!(!dir.exists());
        assert!(src.iter().all(|p| p.exists()));
        prepare_stage([&src[0], &src[1], &src[2]], &dir, 7, 5, 2, &d, || false).unwrap();
        let (m, _) = load(&dir, 7).unwrap();
        let tables = Tables::load(&dir, &m, &mut || false).unwrap();
        for (actual, expected) in arrays(&tables.scanner).into_iter().zip(arrays(&lut)) { assert_eq!(bits(actual), bits(expected)); }
        assert_eq!(bits(&tables.axis), bits(&axis.map(|x| x / 1.1)));
        assert_eq!(tables.curves, curves);
        let original = fs::read(&table_path).unwrap();
        let mut corrupt = original.clone();
        corrupt[..8].copy_from_slice(&0.25f64.to_le_bytes());
        fs::write(&table_path, &corrupt).unwrap();
        assert_eq!(Tables::load(&dir, &m, &mut || false).err().unwrap(), "INVALID_FINISH_TABLE_HASH");
        fs::write(&table_path, &original[..original.len() - 1]).unwrap();
        assert_eq!(Tables::load(&dir, &m, &mut || false).err().unwrap(), "GPU_FINISH_IO");
        fs::write(&table_path, &original).unwrap();
        assert_eq!(Tables::load(&dir, &m, &mut || true).err().unwrap(), "CANCELLED");
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn gamma_and_axis_rejection_matches_scalar_reference() {
        let lut = fixture_lut(2);
        let curves = [[0.; 3]; 3];
        for axis in [[0., -0., 1.], [0., 1., 0.5], [0., f64::NAN, 1.], [0., 1e-300, 1.]] {
            let d = descriptor(&lut, &axis, &curves, GpuFinishInput::FilmDensity, 1.1);
            assert_eq!(write_tables(&mut Vec::new(), &d, true, &mut || false).unwrap_err(), scalar_write_tables(&mut Vec::new(), &d, true).unwrap_err());
        }
    }
    fn packet_counts(m: &Manifest) -> Vec<usize> {
        let mut counts = Vec::new();
        for lut in m.printing.iter().chain(std::iter::once(&m.scanner)) {
            let n = lut.size as usize;
            counts.extend([3 * n.pow(3); 4]);
            counts.extend([3 * (n - 1).pow(3); 2]);
        }
        counts.extend([m.curve_count as usize, m.curve_count as usize * 3]);
        counts
    }
    fn bench_read(path: &Path, counts: &[usize], scalar: bool) -> String {
        let mut hash = Sha256::new();
        if scalar {
            let mut reader = BufReader::with_capacity(TABLE_IO_BYTES, File::open(path).unwrap());
            let data: Vec<_> = counts.iter().map(|&n| scalar_read_values(&mut reader, n, &mut hash, &mut || false).unwrap()).collect();
            std::hint::black_box(data);
        } else {
            let mut reader = File::open(path).unwrap();
            let mut buffer = vec![0; TABLE_IO_BYTES];
            let data: Vec<_> = counts.iter().map(|&n| read_table_values(&mut reader, n, &mut buffer, &mut hash, &mut || false).unwrap()).collect();
            std::hint::black_box(data);
        }
        hex(&hash.finalize())
    }
    #[test]
    #[ignore = "Explicit serial host table I/O benchmark; not a phone or GPU timing"]
    fn benchmark_full_33_lut_packet_io() {
        let root = super::tests::temp();
        let lut = fixture_lut(33);
        let axis: Vec<_> = (0..256).map(|i| -10. + i as f64 / 16.).collect();
        let curves: Vec<_> = (0..256).map(|i| [i as f64 / 255.; 3]).collect();
        let d = descriptor(&lut, &axis, &curves, GpuFinishInput::FilmDensity, 1.1);
        let m = descriptor_manifest(&d, 512, 384, 7).unwrap();
        let counts = packet_counts(&m);
        assert_eq!(m.table_scalars * 8, 10_053_824);
        let mut write_ms: [Vec<f64>; 2] = std::array::from_fn(|_| Vec::new());
        let mut read_ms: [Vec<f64>; 2] = std::array::from_fn(|_| Vec::new());
        let paths = [root.join("old-tables.f64le"), root.join("new-tables.f64le")];
        let mut expected_hash = None;
        for round in 0..9 {
            for implementation in if round % 2 == 0 { [0, 1] } else { [1, 0] } {
                let at = Instant::now();
                let hash = if implementation == 0 {
                    let mut writer = BufWriter::with_capacity(TABLE_IO_BYTES, File::create(&paths[0]).unwrap());
                    let hash = scalar_write_tables(&mut writer, &d, true).unwrap();
                    writer.get_ref().sync_all().unwrap();
                    hash
                } else {
                    let mut writer = File::create(&paths[1]).unwrap();
                    let hash = write_tables(&mut writer, &d, true, &mut || false).unwrap();
                    writer.sync_all().unwrap();
                    hash
                };
                let elapsed = at.elapsed().as_secs_f64() * 1000.;
                if round > 0 { write_ms[implementation].push(elapsed); }
                if let Some(expected) = &expected_hash { assert_eq!(&hash, expected); } else { expected_hash = Some(hash.clone()); }
                let at = Instant::now();
                assert_eq!(bench_read(&paths[implementation], &counts, implementation == 0), hash);
                if round > 0 { read_ms[implementation].push(at.elapsed().as_secs_f64() * 1000.); }
            }
        }
        assert_eq!(fs::read(&paths[0]).unwrap(), fs::read(&paths[1]).unwrap());
        let median = |values: &mut Vec<f64>| { values.sort_by(f64::total_cmp); (values[3] + values[4]) / 2. };
        let old_write = median(&mut write_ms[0]);
        let new_write = median(&mut write_ms[1]);
        let old_read = median(&mut read_ms[0]);
        let new_read = median(&mut read_ms[1]);
        eprintln!("FINISH_TABLE_IO_BENCH {}", serde_json::json!({"hostOnly":true,"deviceTiming":false,"tableBytes":m.table_scalars*8,"samplesPerImplementation":8,"warmupRounds":1,"alternatingOrder":true,"includesWriteFsync":true,"byteAndDigestParity":true,"sha256":expected_hash,"oldWriteMedianMs":old_write,"newWriteMedianMs":new_write,"oldReadMedianMs":old_read,"newReadMedianMs":new_read,"oldWriteMs":write_ms[0],"newWriteMs":write_ms[1],"oldReadMs":read_ms[0],"newReadMs":read_ms[1]}));
        fs::remove_dir_all(root).unwrap();
    }
}
