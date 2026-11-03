//! Opt-in print/scanner resident f32 core. Original f64 scanner finish stays in main.
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
type R<T> = Result<T, String>;
const MIB: u64 = 1024 * 1024;
const CAP: u64 = 384 * MIB;
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
            let _ = fs::remove_dir_all(&self.0);
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
    let host = 16 * t + 128 * c + 2 * MIB;
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
    let mut out = std::io::BufWriter::with_capacity(
        65536,
        io_at(
            Phase::TableCreate,
            OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(dir.join("tables.f64le")),
        )?,
    );
    let mut hash = Sha256::new();
    let mut put = |v: f64| -> R<()> {
        if !v.is_finite() || !(v as f32).is_finite() {
            return err("NONFINITE_FINISH_TABLE");
        };
        let b = v.to_le_bytes();
        io_at(Phase::TableWrite, out.write_all(&b))?;
        hash.update(b);
        Ok(())
    };
    if m.printing.is_some() {
        for a in arrays(d.printing.as_ref().unwrap().lut) {
            check(&mut cancel)?;
            for &v in a {
                put(v)?
            }
        }
    }
    for a in arrays(d.scanner) {
        check(&mut cancel)?;
        for &v in a {
            put(v)?
        }
    }
    if let Some(p) = &d.printing {
        let mut last = f64::NEG_INFINITY;
        let mut last32 = f32::NEG_INFINITY;
        for &x in p.log_exposure {
            let x = if (p.gamma - 1.).abs() < 1e-12 {
                x
            } else {
                x / p.gamma
            };
            if x <= last || (x as f32) <= last32 {
                return err("GPU_FINISH_UNSUPPORTED");
            };
            put(x)?;
            last = x;
            last32 = x as f32;
        }
        for row in p.curves {
            for &v in row {
                put(v)?
            }
        }
    }
    drop(put);
    io_at(Phase::TableWrite, out.flush())?;
    io_at(Phase::TableSync, out.get_ref().sync_all())?;
    m.table_sha256 = hex(&hash.finalize());
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
        let mut f =
            std::io::BufReader::with_capacity(65536, io(File::open(dir.join("tables.f64le")))?);
        let mut hash = Sha256::new();
        let mut read = |n: usize| -> R<Vec<f64>> {
            let mut v = Vec::with_capacity(n);
            for i in 0..n {
                if i % 8192 == 0 {
                    check(cancel)?
                }
                let mut b = [0; 8];
                io(f.read_exact(&mut b))?;
                hash.update(b);
                let x = f64::from_le_bytes(b);
                if !x.is_finite() || !(x as f32).is_finite() {
                    return err("NONFINITE_FINISH_TABLE");
                };
                v.push(x)
            }
            Ok(v)
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
        for v in output {
            let b = (v as f64).to_le_bytes();
            io(out.write_all(&b))?;
            hash.update(b)
        }
        remaining -= n as u64;
        chunks += 1;
    }
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
