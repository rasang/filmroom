//! Original global scientific operators on the GPU: metering, highlight boost,
//! and filming correction/log exposure. No per-tile exposure, approximate meter,
//! or CPU-computed result is labelled GPU. Host work is IO and immutable geometry.
use crate::research_spatial::{ChannelPlan, FilmOpticsPlan, MIB, TRANSFER_BYTES};
use serde::{Deserialize, Serialize};
pub type R<T> = Result<T, String>;
pub const SHADER: &str = concat!(
    include_str!("shaders/research_global_guard.wgsl"),
    "\n",
    include_str!("shaders/research_global.wgsl")
);
// ExposureLog needs only packet upload, the original logarithm, and readback.
// Keep the same guard source and ABI without compiling the unrelated metering
// and highlight kernels into its pipeline.
pub const LOG_SHADER: &str = concat!(
    include_str!("shaders/research_global_guard.wgsl"),
    "\n",
    include_str!("shaders/research_global_log.wgsl")
);
pub const CHUNK: usize = 65536;
pub const PACK_BASE: usize = 524288;
pub const METER_STRIDE: usize = 7;
pub const STATS: usize = 40;
pub const NUMERIC_TOLERANCE: f64 = 2e-3;
pub mod full_stage;
pub mod streaming;
#[cfg(any(test, feature = "gpu-diffusion-research"))]
mod specialized;
#[cfg(feature = "gpu-diffusion-research")]
mod gpu;
#[cfg(all(test, feature = "gpu-diffusion-research"))]
pub(crate) use gpu::run_probe_for_test;
#[cfg(test)]
pub use full_stage::run_stage_cpu_mirror_for_test;
pub use full_stage::{
    plan_stage, prepare_meter_stage, prepare_plane_stage, run_stage, validate_outputs,
    GlobalOutput, StagePlan,
};
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MeterMethod {
    Average,
    Median,
    CenterWeighted,
    Partial,
    Matrix,
    MultiZone,
    HighlightWeighted,
    Unknown,
}
impl MeterMethod {
    pub fn parse(s: &str) -> Self {
        match s {
            "average" => Self::Average,
            "median" => Self::Median,
            "center_weighted" => Self::CenterWeighted,
            "partial" => Self::Partial,
            "matrix" => Self::Matrix,
            "multi_zone" => Self::MultiZone,
            "highlight_weighted" => Self::HighlightWeighted,
            _ => Self::Unknown,
        }
    }
    pub fn name(self) -> &'static str {
        match self {
            Self::Average => "average",
            Self::Median => "median",
            Self::CenterWeighted => "center_weighted",
            Self::Partial => "partial",
            Self::Matrix => "matrix",
            Self::MultiZone => "multi_zone",
            Self::HighlightWeighted => "highlight_weighted",
            Self::Unknown => "unknown",
        }
    }
    pub fn code(self) -> u32 {
        match self {
            Self::Average => 1,
            Self::Median => 2,
            Self::CenterWeighted => 3,
            Self::Partial => 4,
            Self::Matrix => 5,
            Self::MultiZone => 6,
            Self::HighlightWeighted => 7,
            Self::Unknown => 0,
        }
    }
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub enum GlobalKind {
    Meter {
        method: MeterMethod,
        rgb_to_xyz: [[f32; 3]; 3],
    },
    Highlight {
        boost_ev: f32,
        boost_range: f32,
        protect_ev: f32,
    },
    LogExposure {
        correction: f64,
    },
}
impl GlobalKind {
    pub fn operation(&self) -> &'static str {
        match self {
            Self::Meter { .. } => "fullAutoExposure",
            Self::Highlight { .. } => "fullHighlightBoost",
            Self::LogExposure { .. } => "fullExposureLog",
        }
    }
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct GlobalPlan {
    pub width: u32,
    pub height: u32,
    pub kind: GlobalKind,
    pub sample_width: u32,
    pub sample_height: u32,
    pub grid_version: u32,
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub disk_bytes: u64,
}
pub(crate) fn check(c: &mut impl FnMut() -> bool) -> R<()> {
    if c() {
        Err("CANCELLED".into())
    } else {
        Ok(())
    }
}
pub(crate) fn finite(x: f64) -> bool {
    x.is_finite() && (x as f32).is_finite()
}
impl GlobalPlan {
    pub fn meter(w: u32, h: u32, method: &str, matrix: [[f32; 3]; 3]) -> R<Self> {
        Self::new(
            w,
            h,
            GlobalKind::Meter {
                method: MeterMethod::parse(method),
                rgb_to_xyz: matrix,
            },
        )
    }
    pub fn highlight(w: u32, h: u32, boost_ev: f32, boost_range: f32, protect_ev: f32) -> R<Self> {
        Self::new(
            w,
            h,
            GlobalKind::Highlight {
                boost_ev,
                boost_range,
                protect_ev,
            },
        )
    }
    pub fn log_exposure(w: u32, h: u32, correction: f64) -> R<Self> {
        Self::new(w, h, GlobalKind::LogExposure { correction })
    }
    fn new(width: u32, height: u32, kind: GlobalKind) -> R<Self> {
        if width == 0 || height == 0 || width > 40000 || height > 40000 {
            return Err("INVALID_GLOBAL_DIMENSIONS".into());
        }
        match &kind {
            GlobalKind::Meter { rgb_to_xyz, .. } => {
                if rgb_to_xyz.iter().flatten().any(|x| !x.is_finite()) {
                    return Err("NONFINITE_GLOBAL_PARAMETERS".into());
                }
            }
            GlobalKind::Highlight {
                boost_ev,
                boost_range,
                protect_ev,
            } => {
                if ![boost_ev, boost_range, protect_ev]
                    .into_iter()
                    .all(|v| v.is_finite())
                    || !(0.0..=8.0).contains(boost_ev)
                    || !(0.0..=1.0).contains(boost_range)
                    || !(-16.0..=16.0).contains(protect_ev)
                {
                    return Err("INVALID_GLOBAL_PARAMETERS".into());
                }
            }
            GlobalKind::LogExposure { correction } => {
                if !finite(*correction) || *correction <= 0. || (*correction as f32) <= 0. {
                    return Err("INVALID_GLOBAL_PARAMETERS".into());
                }
            }
        }
        let meter = matches!(kind, GlobalKind::Meter { .. });
        let n = width as u64 * height as u64;
        let (sample_width, sample_height) = if meter {
            let (w, h, _, _) = sample_grid(width, height)?;
            (w, h)
        } else {
            (0, 0)
        };
        let plane = if meter { 65536 * 4 } else { n * 4 };
        let device_bytes = 5 * plane + 2 * TRANSFER_BYTES + 65536;
        let result = Self {
            width,
            height,
            kind,
            sample_width,
            sample_height,
            grid_version: if meter { 1 } else { 0 },
            device_bytes,
            host_bytes: 16 * MIB,
            driver_allowance_bytes: 64 * MIB,
            helper_allowance_bytes: 64 * MIB,
            combined_peak_bytes: device_bytes + 144 * MIB,
            disk_bytes: if meter {
                sample_width as u64 * sample_height as u64 * 28 + 65536
            } else {
                n * 48 + 65536
            },
        };
        result
            .spatial_plan()?
            .validate()
            .map_err(|_| "GPU_GLOBAL_MEMORY_LIMIT")?;
        Ok(result)
    }
    pub fn validate(&self) -> R<()> {
        let expected = Self::new(self.width, self.height, self.kind.clone())?;
        if *self != expected {
            Err("INVALID_GLOBAL_PLAN".into())
        } else {
            Ok(())
        }
    }
    pub(crate) fn spatial_plan(&self) -> R<FilmOpticsPlan> {
        let (w, h) = if matches!(self.kind, GlobalKind::Meter { .. }) {
            (256, 256)
        } else {
            (self.width, self.height)
        };
        let plane = w as u64 * h as u64 * 4;
        let p = FilmOpticsPlan {
            width: w,
            height: h,
            channels: std::array::from_fn(|_| ChannelPlan {
                lens_sigma: 0.,
                scatter: None,
                bounce_sigmas: Vec::new(),
                bounce_weights: Vec::new(),
                strength: 0.,
                divisor: 1.,
            }),
            device_bytes: 5 * plane + 2 * TRANSFER_BYTES + 65536,
            host_bytes: 16 * MIB,
            driver_allowance_bytes: 64 * MIB,
            helper_allowance_bytes: 64 * MIB,
            combined_peak_bytes: 5 * plane + 2 * TRANSFER_BYTES + 65536 + 144 * MIB,
            disk_bytes: 12 * plane + 65536,
        };
        p.validate().map_err(|_| "GPU_GLOBAL_MEMORY_LIMIT")?;
        Ok(p)
    }
}
/// Exactly the original rounded shape and per-axis nearest coordinates. This is
/// address calculation only; RGB luminance and image statistics remain on GPU.
pub fn sample_grid(w: u32, h: u32) -> R<(u32, u32, Vec<u32>, Vec<u32>)> {
    if w == 0 || h == 0 || w > 40000 || h > 40000 {
        return Err("INVALID_GLOBAL_DIMENSIONS".into());
    }
    let max = w.max(h);
    let scale = 256. / max as f64;
    let (sw, sh) = if max > 256 {
        (
            ((w as f64 * scale).round() as u32).max(1),
            ((h as f64 * scale).round() as u32).max(1),
        )
    } else {
        (w, h)
    };
    let axis = |n: u32, s: u32| {
        (0..s)
            .map(|o| {
                (((o as f64 + 0.5) * (n as f64 / s as f64) - 0.5).round() as i64)
                    .clamp(0, n as i64 - 1) as u32
            })
            .collect()
    };
    Ok((sw, sh, axis(w, sw), axis(h, sh)))
}
/// Original f64 geometric membership/weights, independent of image values. This
/// avoids moving partial/ring boundary pixels because of rounded GPU coordinates.
/// The GPU still computes every weighted statistic and the matrix cell means.
pub(crate) fn geometry(x: u32, y: u32, w: u32, h: u32) -> [f32; 4] {
    let m = w.max(h) as f64;
    let nx = (x as f64 / w as f64 - 0.5) * (w as f64 / m);
    let ny = (y as f64 / h as f64 - 0.5) * (h as f64 / m);
    let r = (nx * nx + ny * ny).sqrt();
    let ring = if r < 0.05 {
        0.
    } else if r < 0.25 {
        1.
    } else if r < 0.5 {
        2.
    } else {
        3.
    };
    let cw = w / 5;
    let ch = h / 5;
    let cell = if cw > 0 && ch > 0 && x < 5 * cw && y < 5 * ch {
        (y / ch * 5 + x / cw) as f32
    } else {
        25.
    };
    [
        (-(nx * nx + ny * ny) / (2. * 0.2f64 * 0.2)).exp() as f32,
        if r < 0.15 { 1. } else { 0. },
        ring,
        cell,
    ]
}
pub(crate) fn matrix_weights() -> [f32; 25] {
    std::array::from_fn(|i| {
        let dy = (i / 5) as f64 / 2. - 1.;
        let dx = (i % 5) as f64 / 2. - 1.;
        (0.5 * (1. + (std::f64::consts::PI * (dx * dx + dy * dy).sqrt() / 2f64.sqrt()).cos()))
            as f32
    })
}
pub(crate) fn read_meter_pack(
    source: &std::path::Path,
    p: &GlobalPlan,
    c: &mut impl FnMut() -> bool,
) -> R<(u32, u32, Vec<f32>)> {
    use crate::gpu_stage_staging::{io_at, Phase};
    use std::{
        fs,
        io::{Read, Seek, SeekFrom},
    };
    let (sw, sh, xs, ys) = sample_grid(p.width, p.height)?;
    let original = io_at(Phase::SourceStat, fs::symlink_metadata(source))?;
    if !original.is_file()
        || original.file_type().is_symlink()
        || original.len() != p.width as u64 * p.height as u64 * 12
    {
        return Err("GPU_PREPARE_SOURCE_SHAPE".into());
    }
    let mut options = fs::OpenOptions::new();
    options.read(true);
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.custom_flags(libc::O_NOFOLLOW);
    }
    let mut f = io_at(Phase::SourceOpen, options.open(source))?;
    if !same_meter_source(&original, &io_at(Phase::SourceStat, f.metadata())?) {
        return Err("GPU_PREPARE_SOURCE_IDENTITY".into());
    }
    let mut row = vec![0u8; p.width as usize * 12];
    let mut packed = Vec::with_capacity(sw as usize * sh as usize * METER_STRIDE);
    for (y, &sy) in ys.iter().enumerate() {
        check(c)?;
        f.seek(SeekFrom::Start(sy as u64 * p.width as u64 * 12))
            .map_err(|_| "GPU_GLOBAL_IO")?;
        f.read_exact(&mut row).map_err(|_| "GPU_GLOBAL_IO")?;
        for (x, &sx) in xs.iter().enumerate() {
            for ch in 0..3 {
                let at = (sx as usize * 3 + ch) * 4;
                let v = f32::from_le_bytes(row[at..at + 4].try_into().unwrap());
                if !v.is_finite() {
                    return Err("NONFINITE_GLOBAL_INPUT".into());
                }
                packed.push(v);
            }
            packed.extend(geometry(x as u32, y as u32, sw, sh));
        }
    }
    if !same_meter_source(&original, &io_at(Phase::SourceRestat, f.metadata())?)
        || !same_meter_source(
            &original,
            &io_at(Phase::SourceRestat, fs::symlink_metadata(source))?,
        )
    {
        return Err("GPU_PREPARE_SOURCE_IDENTITY".into());
    }
    Ok((sw, sh, packed))
}
fn same_meter_source(a: &std::fs::Metadata, b: &std::fs::Metadata) -> bool {
    if !a.is_file()
        || !b.is_file()
        || a.file_type().is_symlink()
        || b.file_type().is_symlink()
        || a.len() != b.len()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        a.dev() == b.dev()
            && a.ino() == b.ino()
            && a.mtime() == b.mtime()
            && a.mtime_nsec() == b.mtime_nsec()
            && a.ctime() == b.ctime()
            && a.ctime_nsec() == b.ctime_nsec()
    }
    #[cfg(not(unix))]
    {
        a.modified().ok() == b.modified().ok()
    }
}
pub(crate) fn read_meter_packet(
    source: &std::path::Path,
    p: &GlobalPlan,
    c: &mut impl FnMut() -> bool,
) -> R<(u32, u32, Vec<f32>)> {
    use std::io::Read;
    check(c)?;
    p.validate()?;
    let bytes = p.sample_width as u64 * p.sample_height as u64 * 28;
    full_stage::regular(source, bytes)?;
    let mut f = std::fs::File::open(source).map_err(|_| "GPU_GLOBAL_IO")?;
    let mut packet = Vec::with_capacity((bytes / 4) as usize);
    let mut buffer = vec![0u8; 65536];
    let mut left = bytes;
    while left > 0 {
        check(c)?;
        let count = left.min(buffer.len() as u64) as usize;
        f.read_exact(&mut buffer[..count])
            .map_err(|_| "GPU_GLOBAL_IO")?;
        for b in buffer[..count].chunks_exact(4) {
            let v = f32::from_le_bytes(b.try_into().unwrap());
            if !v.is_finite() {
                return Err("NONFINITE_GLOBAL_INPUT".into());
            }
            packet.push(v);
        }
        left -= count as u64;
    }
    Ok((p.sample_width, p.sample_height, packet))
}
/// Independent host f32 arithmetic mirror for tests and bounded witnesses only.
/// Never called to manufacture production GPU EV or complete GPU plane results.
#[cfg(test)]
pub fn meter_mirror(
    p: &GlobalPlan,
    packed: &[f32],
    sw: u32,
    sh: u32,
    c: &mut impl FnMut() -> bool,
) -> R<(f32, f32)> {
    let GlobalKind::Meter { method, rgb_to_xyz } = &p.kind else {
        return Err("INVALID_GLOBAL_KIND".into());
    };
    let n = sw as usize * sh as usize;
    if packed.len() != n * METER_STRIDE
        || n == 0
        || n > 65536
        || packed.iter().any(|x| !x.is_finite())
    {
        return Err("INVALID_GLOBAL_INPUT".into());
    }
    let lum: Vec<_> = packed
        .chunks_exact(METER_STRIDE)
        .map(|v| rgb_to_xyz[1][0] * v[0] + rgb_to_xyz[1][1] * v[1] + rgb_to_xyz[1][2] * v[2])
        .collect();
    if lum.iter().any(|v| !v.is_finite()) {
        return Err("NONFINITE_GLOBAL_INTERMEDIATE".into());
    }
    let mut total = [0f32; STATS];
    for (begin, block) in lum.chunks(256).enumerate() {
        check(c)?;
        let mut s = [0f32; STATS];
        for (j, &y) in block.iter().enumerate() {
            let g = &packed[(begin * 256 + j) * METER_STRIDE + 3..];
            match method {
                MeterMethod::Average
                | MeterMethod::Partial
                | MeterMethod::MultiZone
                | MeterMethod::HighlightWeighted => s[0] += y,
                _ => {}
            }
            match method {
                MeterMethod::CenterWeighted => {
                    s[1] += y * g[0];
                    s[2] += g[0];
                }
                MeterMethod::Partial => {
                    s[3] += y * g[1];
                    s[4] += g[1];
                }
                MeterMethod::HighlightWeighted => {
                    let w = y * y;
                    s[5] += y * w;
                    s[6] += w;
                }
                MeterMethod::MultiZone => {
                    let ring = g[2] as usize;
                    if ring < 3 {
                        s[8 + 2 * ring] += y;
                        s[9 + 2 * ring] += 1.;
                    }
                }
                MeterMethod::Matrix => {
                    let cell = g[3] as usize;
                    if cell < 25 {
                        s[14 + cell] += y;
                    }
                }
                _ => {}
            }
        }
        for i in 0..STATS {
            total[i] += s[i];
        }
    }
    if total.iter().any(|v| !v.is_finite()) {
        return Err("NONFINITE_GLOBAL_INTERMEDIATE".into());
    }
    let average = total[0] / n as f32;
    let metered = match method {
        MeterMethod::Average => average,
        MeterMethod::Median => {
            let mut v = lum;
            v.sort_by(f32::total_cmp);
            if n % 2 == 1 {
                v[n / 2]
            } else {
                0.5 * (v[n / 2 - 1] + v[n / 2])
            }
        }
        MeterMethod::CenterWeighted => total[1] / total[2],
        MeterMethod::Partial => {
            if total[4] > 0. {
                total[3] / total[4]
            } else {
                average
            }
        }
        MeterMethod::HighlightWeighted => {
            if total[6] < 1e-12 {
                average
            } else {
                total[5] / total[6]
            }
        }
        MeterMethod::MultiZone => {
            let mut s = 0.;
            let mut w = 0.;
            for (r, weight) in [0.5, 0.3, 0.2].into_iter().enumerate() {
                if total[9 + 2 * r] > 0. {
                    s += weight * (total[8 + 2 * r] / total[9 + 2 * r]);
                    w += weight;
                }
            }
            if w > 0. {
                s / w
            } else {
                average
            }
        }
        MeterMethod::Matrix => {
            let cell = (sw / 5) * (sh / 5);
            if cell == 0 {
                0.
            } else {
                let weights = matrix_weights();
                let wsum: f32 = weights.iter().sum();
                let mut sum = 0.;
                for i in 0..25 {
                    sum += (total[14 + i] / cell as f32) * weights[i] / wsum;
                }
                sum
            }
        }
        MeterMethod::Unknown => 0.184,
    };
    if !metered.is_finite() {
        return Err("NONFINITE_GLOBAL_INTERMEDIATE".into());
    }
    let exposure = metered / 0.184f32;
    if !exposure.is_finite() {
        return Err("NONFINITE_GLOBAL_INTERMEDIATE".into());
    }
    let ev = if exposure <= 0. { 0. } else { -exposure.log2() };
    let scale = 2f32.powf(ev);
    if !ev.is_finite() || !scale.is_finite() || scale <= 0. {
        return Err("NONFINITE_GLOBAL_OUTPUT".into());
    }
    Ok((ev, scale))
}
#[cfg(test)]
pub fn planes_mirror(
    p: &GlobalPlan,
    input: &[Vec<f64>; 3],
    c: &mut impl FnMut() -> bool,
) -> R<[Vec<f64>; 3]> {
    p.validate()?;
    let n = p.width as usize * p.height as usize;
    if input
        .iter()
        .any(|v| v.len() != n || v.iter().any(|x| !finite(*x)))
    {
        return Err("NONFINITE_GLOBAL_INPUT".into());
    }
    let mut result: [Vec<f64>; 3] = std::array::from_fn(|_| Vec::new());
    let max = input
        .iter()
        .flatten()
        .map(|x| *x as f32)
        .fold(f32::NEG_INFINITY, f32::max);
    if let GlobalKind::Highlight {
        boost_ev,
        boost_range,
        protect_ev,
    } = p.kind
    {
        let actual = boost_value(max, max, boost_ev, boost_range, protect_ev)?;
        let reference = boost_reference_value(
            max as f64,
            max as f64,
            boost_ev as f64,
            boost_range as f64,
            protect_ev as f64,
        );
        if !reference.is_finite()
            || !actual.is_finite()
            || (reference - actual as f64).abs() > NUMERIC_TOLERANCE * reference.abs().max(1.)
        {
            return Err("GPU_GLOBAL_NUMERIC_CHECK".into());
        }
    }
    for ch in 0..3 {
        result[ch] = Vec::with_capacity(n);
        for (i, &v) in input[ch].iter().enumerate() {
            if i % CHUNK == 0 {
                check(c)?;
            }
            let v = v as f32;
            let x = match p.kind {
                GlobalKind::Highlight {
                    boost_ev,
                    boost_range,
                    protect_ev,
                } => boost_value(v, max, boost_ev, boost_range, protect_ev)?,
                GlobalKind::LogExposure { correction } => {
                    let corrected = v * correction as f32;
                    if !corrected.is_finite() {
                        return Err("NONFINITE_GLOBAL_INTERMEDIATE".into());
                    }
                    (corrected.max(0.) + 1e-10f32).ln() * std::f32::consts::LOG10_E
                }
                _ => return Err("INVALID_GLOBAL_KIND".into()),
            };
            if !x.is_finite() {
                return Err("NONFINITE_GLOBAL_OUTPUT".into());
            }
            result[ch].push(x as f64);
        }
    }
    Ok(result)
}
#[cfg(test)]
fn boost_value(v: f32, max: f32, boost: f32, range: f32, protect: f32) -> R<f32> {
    if boost == 0. {
        return Ok(v);
    }
    if max < 0. {
        return Err("INVALID_GLOBAL_PEAK".into());
    }
    let x0 = (0.184f32 * 2f32.powf(protect)).clamp(0., max);
    if x0 == max && max != 0. {
        return Ok(v);
    }
    let a = 28f32.powf(1. - range);
    let xn = if max > 0. { x0 / max } else { 0. };
    let t = a * (1. - xn);
    let denom = t.exp() - t - 1.;
    if denom <= 0. {
        return Ok(v);
    }
    let k = (2f32.powf(boost) - 1.) / denom;
    let inv = if max > 0. { 1. / max } else { 0. };
    let scale = k * max;
    Ok(if max == 0. {
        0.
    } else if v <= x0 {
        v
    } else {
        let d = (v - x0) * inv;
        v + scale * ((a * d).exp() - a * d - 1.)
    })
}

/// Scalar witness only. Production never evaluates a full image or peak here.
pub(crate) fn boost_reference_value(v: f64, max: f64, boost: f64, range: f64, protect: f64) -> f64 {
    if boost == 0. {
        return v;
    }
    if max < 0. {
        return f64::NAN;
    }
    let x0 = (0.184 * 2f64.powf(protect)).clamp(0., max);
    if x0 == max && max != 0. {
        return v;
    }
    let a = 28f64.powf(1. - range);
    let xn = if max > 0. { x0 / max } else { 0. };
    let denom = (a * (1. - xn)).exp() - a * (1. - xn) - 1.;
    if denom <= 0. {
        return v;
    }
    let k = (2f64.powf(boost) - 1.) / denom;
    let inv = if max > 0. { 1. / max } else { 0. };
    let scale = k * max;
    if max == 0. {
        0.
    } else if v <= x0 {
        v
    } else {
        let d = (v - x0) * inv;
        v + scale * ((a * d).exp() - a * d - 1.)
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::{
        fs,
        path::{Path, PathBuf},
        sync::atomic::{AtomicU64, Ordering},
    };
    pub const METHODS: [&str; 8] = [
        "average",
        "median",
        "center_weighted",
        "partial",
        "matrix",
        "multi_zone",
        "highlight_weighted",
        "unknown",
    ];
    pub fn root() -> PathBuf {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let p = std::env::temp_dir().join(format!(
            "gpu-global-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&p).unwrap();
        p.canonicalize().unwrap()
    }
    pub fn rgb(root: &Path, w: u32, h: u32) -> PathBuf {
        let p = root.join(format!("input-{w}x{h}.f32le"));
        let values: Vec<f32> = (0..w * h * 3)
            .map(|i| {
                if i % 499 == 0 {
                    6.
                } else {
                    ((i * 29 % 1009) as f32 + 1.) / 1009.
                }
            })
            .collect();
        fs::write(
            &p,
            values
                .iter()
                .flat_map(|v| v.to_le_bytes())
                .collect::<Vec<_>>(),
        )
        .unwrap();
        p
    }
    pub fn planes(root: &Path, w: u32, h: u32) -> [PathBuf; 3] {
        std::array::from_fn(|ch| {
            let p = root.join(format!("plane-source-{w}x{h}-{ch}"));
            let v: Vec<f64> = (0..w * h)
                .map(|i| {
                    if i % 73 == 0 {
                        -0.04
                    } else {
                        (i as f64 * (ch + 1) as f64 % 101.) / 13.
                    }
                })
                .collect();
            fs::write(
                &p,
                v.iter().flat_map(|v| v.to_le_bytes()).collect::<Vec<_>>(),
            )
            .unwrap();
            p
        })
    }
    pub fn read_planes(paths: &[PathBuf; 3]) -> [Vec<f64>; 3] {
        std::array::from_fn(|ch| {
            fs::read(&paths[ch])
                .unwrap()
                .chunks_exact(8)
                .map(|b| f64::from_le_bytes(b.try_into().unwrap()))
                .collect()
        })
    }
    #[test]
    fn guarded_arithmetic_bounds_precede_unsafe_gpu_operations() {
        // These are the exact conservative guard bounds, not the photographic
        // reference. Even the raw guarded operations cannot reach f32 overflow.
        let b = 1e15f32;
        for x in [-b, -1., 0., 1., b] {
            for y in [-b, -1., 0., 1., b] {
                assert!((x + y).is_finite() && (x - y).is_finite() && (x * y).is_finite());
            }
            for y in [-b, -1e-15, 1e-15, b] {
                assert!((x / y).is_finite());
            }
        }
        for x in [-60f32, 34.] {
            assert!(x.exp().is_finite());
        }
        for x in [-80f32, 49.] {
            assert!(x.exp2().is_finite() && x.exp2() > 0.);
        }
        for x in [1e-30f32, b] {
            assert!(x.ln().is_finite() && x.log2().is_finite());
        }
        // One un-witnessed pixel can exceed the admitted range without raw
        // overflow. It must latch a whole-request error, never clamp/publish.
        let witness_indices: Vec<_> = (0..8).map(|i| i * 256 / 7).collect();
        assert!(!witness_indices.contains(&17));
        assert!((1e6f32 * 1e6 * 1e6) > b);
        assert!((1e12f32 * 1e12) > b);
        assert!(SHADER.contains("@binding(7)"));
        assert!(SHADER.contains("atomicOr(&global_numeric_error.value,1u)"));
        assert!(!SHADER.contains("fn get("));
        assert!(!SHADER.contains("fn bad("));
        let runner = include_str!("research_global/gpu.rs");
        assert!(runner.contains("Runner::new_with_shader_error"));
        assert!(
            runner
                .matches("reject_numeric_error(&r, c, &mut m)?")
                .count()
                >= 5
        );
    }
    #[test]
    fn shader_is_valid_without_optional_features() {
        let module = naga::front::wgsl::parse_str(SHADER).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&module)
        .unwrap();
    }
    #[test]
    fn log_shader_preserves_original_guard_bindings_and_operation_bodies() {
        fn block<'a>(source: &'a str, marker: &str) -> &'a str {
            let start = source.find(marker).expect("shader marker");
            let open = start + source[start..].find('{').expect("shader block");
            let mut depth = 0;
            for (offset, &byte) in source.as_bytes()[open..].iter().enumerate() {
                match byte {
                    b'{' => depth += 1,
                    b'}' => {
                        depth -= 1;
                        if depth == 0 {
                            return &source[start..=open + offset];
                        }
                    }
                    _ => {}
                }
            }
            panic!("unclosed shader block");
        }
        let original = include_str!("shaders/research_global.wgsl");
        let specialized = include_str!("shaders/research_global_log.wgsl");
        let guard = include_str!("shaders/research_global_guard.wgsl");
        assert!(SHADER.starts_with(guard));
        assert!(LOG_SHADER.starts_with(guard));
        assert_eq!(
            &original[original.find("struct Data").unwrap()..original.find("fn boost_point").unwrap()],
            &specialized[specialized.find("struct Data").unwrap()..specialized.find("@compute").unwrap()]
        );
        assert_eq!(
            &original[original.find("@compute").unwrap()..original.find(" let op=").unwrap()],
            &specialized[specialized.find("@compute").unwrap()..specialized.find(" let op=").unwrap()]
        );
        for op in [10, 14, 15] {
            let marker = format!("if(op=={op}u)");
            assert_eq!(block(original, &marker), block(specialized, &marker));
        }
        let operations: Vec<u32> = specialized
            .split("if(op==")
            .skip(1)
            .map(|part| part.split('u').next().unwrap().parse().unwrap())
            .collect();
        assert_eq!(operations, [10, 14, 15]);
        assert!(!specialized.contains("boost_point"));
        assert!(!specialized.contains("array<f32,40>"));
        assert_eq!(specialized.matches("for(").count(), 1);
        let runner = include_str!("research_global/gpu.rs");
        assert!(runner.contains("if matches!(p.kind, GlobalKind::LogExposure { .. })"));
        assert!(runner.contains("Runner::new_with_shader_error_explicit_layout(&spatial, allow_probe, LOG_SHADER)"));
        assert!(runner.contains("Runner::new_with_shader_family_error_explicit_layout"));
        assert!(runner.contains("specialized::shaders(&p.kind)?"));
    }
    #[test]
    fn log_shader_validates_and_excludes_unrelated_entry_point_work() {
        let original = naga::front::wgsl::parse_str(SHADER).unwrap();
        let specialized = naga::front::wgsl::parse_str(LOG_SHADER).unwrap();
        let info = naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&specialized)
        .unwrap();
        assert_eq!(specialized.entry_points.len(), 1);
        let entry = &specialized.entry_points[0];
        assert_eq!(entry.name, "main");
        assert_eq!(entry.workgroup_size, [64, 1, 1]);
        assert!(entry.function.expressions.len() < original.entry_points[0].function.expressions.len());
        assert!(entry.function.local_variables.len() < original.entry_points[0].function.local_variables.len());
        let mut bindings = Vec::new();
        for (handle, variable) in specialized.global_variables.iter() {
            let binding = variable.binding.as_ref().expect("bound shader resource");
            assert_eq!(binding.group, 0);
            bindings.push(binding.binding);
            // These bindings must remain present in the explicit host layout,
            // even though reflection correctly finds no use of their planes.
            if [3, 4].contains(&binding.binding) {
                assert!(info.get_entry_point(0)[handle].is_empty());
            } else {
                assert!(!info.get_entry_point(0)[handle].is_empty());
            }
        }
        bindings.sort_unstable();
        assert_eq!(bindings, (0..8).collect::<Vec<_>>());
    }
    #[test]
    fn seven_metering_methods_keep_original_grid_and_small_matrix_behavior() {
        use spektrafilm_math::{image::ImageBuf, precision::from_f64};
        let root = root();
        let matrix = spektrafilm_math::colorspace::SRGB_TO_XYZ;
        for (w, h) in [
            (1, 1),
            (3, 2),
            (17, 13),
            (307, 181),
            (513, 1),
            (1, 513),
            (256, 256),
        ] {
            let source = rgb(&root, w, h);
            let data: Vec<f32> = fs::read(&source)
                .unwrap()
                .chunks_exact(4)
                .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
                .collect();
            let image =
                ImageBuf::from_data(w, h, data.iter().map(|&v| from_f64(v as f64)).collect());
            for method in METHODS {
                let p = GlobalPlan::meter(w, h, method, matrix).unwrap();
                let (sw, sh, pack) = read_meter_pack(&source, &p, &mut || false).unwrap();
                assert!(sw <= 256 && sh <= 256);
                let (ev, scale) = meter_mirror(&p, &pack, sw, sh, &mut || false).unwrap();
                let reference = spektrafilm_core::stages::filming::measure_autoexposure_ev(
                    &image, &matrix, method,
                );
                assert!(
                    (ev - reference).abs() < 1e-3,
                    "{w}x{h} {method}: {ev} vs {reference}"
                );
                assert_eq!(scale.to_bits(), 2f32.powf(ev).to_bits());
                if method == "matrix" && (sw < 5 || sh < 5) {
                    assert_eq!(ev, 0.);
                    assert_eq!(reference, 0.);
                }
            }
        }
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn metering_rejects_nonfinite_intermediates_and_preserves_nonpositive_ev_rule() {
        let root = root();
        let input = rgb(&root, 1, 1);
        let identity = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
        for value in [0., -1., f32::MIN_POSITIVE] {
            fs::write(
                &input,
                [value; 3]
                    .iter()
                    .flat_map(|x| x.to_le_bytes())
                    .collect::<Vec<_>>(),
            )
            .unwrap();
            for method in METHODS {
                let p = GlobalPlan::meter(1, 1, method, identity).unwrap();
                let (w, h, pack) = read_meter_pack(&input, &p, &mut || false).unwrap();
                let (ev, scale) = meter_mirror(&p, &pack, w, h, &mut || false).unwrap();
                if value <= 0. {
                    assert_eq!((ev, scale), (0., 1.));
                } else {
                    assert!(ev.is_finite() && scale.is_finite());
                }
            }
        }
        fs::write(
            &input,
            [2f32; 3]
                .iter()
                .flat_map(|x| x.to_le_bytes())
                .collect::<Vec<_>>(),
        )
        .unwrap();
        let p = GlobalPlan::meter(1, 1, "median", [[f32::MAX; 3]; 3]).unwrap();
        let (w, h, pack) = read_meter_pack(&input, &p, &mut || false).unwrap();
        assert!(meter_mirror(&p, &pack, w, h, &mut || false).is_err());
        // Finite luminance whose exposure division overflows cannot masquerade
        // as GPU EV 0. The caller may use the original f64 stage instead.
        fs::write(
            &input,
            [f32::MAX; 3]
                .iter()
                .flat_map(|v| v.to_le_bytes())
                .collect::<Vec<_>>(),
        )
        .unwrap();
        let extreme = GlobalPlan::meter(1, 1, "median", identity).unwrap();
        let (w, h, pack) = read_meter_pack(&input, &extreme, &mut || false).unwrap();
        assert_eq!(
            meter_mirror(&extreme, &pack, w, h, &mut || false).unwrap_err(),
            "NONFINITE_GLOBAL_INTERMEDIATE"
        );
        fs::write(
            &input,
            [f32::NAN; 3]
                .iter()
                .flat_map(|x| x.to_le_bytes())
                .collect::<Vec<_>>(),
        )
        .unwrap();
        assert_eq!(
            read_meter_pack(&input, &p, &mut || false).unwrap_err(),
            "NONFINITE_GLOBAL_INPUT"
        );
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn highlight_uses_one_full_rgb_peak_and_original_curve() {
        let root = root();
        for (w, h) in [(1, 1), (29, 17)] {
            let paths = planes(&root, w, h);
            let mut input = read_planes(&paths);
            input[2][0] = 12.;
            for (boost, range, protect) in [
                (0., 0.3, 1.),
                (2., 0.3, 1.),
                (0.5, 1., 4.),
                (3., 0., 0.),
                (8., 1., -16.),
            ] {
                for ch in 0..3 {
                    fs::write(
                        &paths[ch],
                        input[ch]
                            .iter()
                            .flat_map(|v| v.to_le_bytes())
                            .collect::<Vec<_>>(),
                    )
                    .unwrap();
                }
                let p = GlobalPlan::highlight(w, h, boost, range, protect).unwrap();
                let got = planes_mirror(&p, &input, &mut || false).unwrap();
                crate::streamed_global::boost_planes(
                    &paths,
                    w as u64 * h as u64,
                    boost as f64,
                    range as f64,
                    protect as f64,
                    || false,
                    |_, _| {},
                )
                .unwrap();
                let want = read_planes(&paths);
                for ch in 0..3 {
                    for (a, b) in got[ch].iter().zip(&want[ch]) {
                        assert!(
                            (a - b).abs() < NUMERIC_TOLERANCE * b.abs().max(1.),
                            "{w}x{h} {boost} {range} {protect}: {a} vs {b}"
                        );
                    }
                }
            }
        }
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn log_exposure_preserves_correction_floor_then_additive_epsilon() {
        let values = vec![-1., 0., 1e-14, 1e-10, 0.184, 1., 6., 1e20];
        let input = std::array::from_fn(|_| values.clone());
        for factor in [0.1, 1., 1.23456789] {
            let p = GlobalPlan::log_exposure(values.len() as u32, 1, factor).unwrap();
            let got = planes_mirror(&p, &input, &mut || false).unwrap();
            for (a, &raw) in got[0].iter().zip(&values) {
                let want = ((raw * factor).max(0.) + 1e-10).log10();
                assert!((a - want).abs() < 2e-5, "{raw} {factor}: {a} vs {want}");
            }
            assert!(got[0][3] > -10.);
        }
        let p = GlobalPlan::log_exposure(8, 1, 2.).unwrap();
        let input = std::array::from_fn(|_| vec![f32::MAX as f64; 8]);
        assert!(planes_mirror(&p, &input, &mut || false).is_err());
    }
    #[test]
    fn global_plans_and_mirrors_reject_unsafe_memory_and_cancellation() {
        assert!(GlobalPlan::highlight(40000, 40000, 1., 0., 0.).is_err());
        assert!(GlobalPlan::meter(0, 1, "average", [[1.; 3]; 3]).is_err());
        assert!(GlobalPlan::log_exposure(1, 1, f64::NAN).is_err());
        assert!(GlobalPlan::log_exposure(1, 1, 0.).is_err());
        let mut p = GlobalPlan::log_exposure(1, 1, 1.).unwrap();
        p.device_bytes += 4;
        assert!(p.validate().is_err());
        let p = GlobalPlan::highlight(1, 1, 1., 0.5, 0.).unwrap();
        assert_eq!(
            planes_mirror(&p, &[vec![1.], vec![2.], vec![3.]], &mut || true).unwrap_err(),
            "CANCELLED"
        );
        assert!(planes_mirror(&p, &[vec![-1.], vec![-2.], vec![-3.]], &mut || false).is_err());
    }
    #[test]
    fn real_global_packets_avoid_full_rgb_staging_and_reject_corrupt_outputs() {
        let root = root();
        let input = rgb(&root, 17, 13);
        let p =
            GlobalPlan::meter(17, 13, "median", spektrafilm_math::colorspace::SRGB_TO_XYZ).unwrap();
        let dir = root.join("gpu-stage-77");
        let plan = crate::gpu_stage_staging::with_test_link_error(13, || {
            prepare_meter_stage(&input, &dir, 77, &p, || false)
        })
        .unwrap();
        assert!(!dir.join("source.rgb.f32le").exists());
        assert_eq!(
            fs::metadata(dir.join("source.meter.f32le")).unwrap().len(),
            17 * 13 * 28
        );
        assert_eq!(
            read_meter_pack(&input, &p, &mut || false).unwrap(),
            read_meter_packet(&dir.join("source.meter.f32le"), &p, &mut || false).unwrap()
        );
        let report =
            run_stage_cpu_mirror_for_test(&dir, 77, &plan.manifest_sha256, || false).unwrap();
        assert_eq!(report["gpuUsed"], false);
        assert_eq!(report["hostArithmeticMirror"], true);
        let GlobalOutput::Meter { ev, scale } =
            validate_outputs(&dir, 77, &plan.manifest_sha256, || false).unwrap()
        else {
            panic!("wrong output kind")
        };
        assert!(ev.is_finite() && scale > 0.);
        assert!(validate_outputs(&dir, 78, &plan.manifest_sha256, || false).is_err());
        fs::write(dir.join("candidate/meter.f32le"), [0u8; 8]).unwrap();
        assert!(validate_outputs(&dir, 77, &plan.manifest_sha256, || false).is_err());
        for (token, p) in [
            (78, GlobalPlan::highlight(17, 13, 1., 0.5, 0.).unwrap()),
            (79, GlobalPlan::log_exposure(17, 13, 1.23).unwrap()),
        ] {
            let paths = planes(&root, 17, 13);
            let original = read_planes(&paths);
            let dir = root.join(format!("gpu-stage-{token}"));
            let plan = crate::gpu_stage_staging::with_test_link_error(13, || {
                prepare_plane_stage(
                    std::array::from_fn(|ch| paths[ch].as_path()),
                    &dir,
                    token,
                    &p,
                    || false,
                )
            })
            .unwrap();
            run_stage_cpu_mirror_for_test(&dir, token, &plan.manifest_sha256, || false).unwrap();
            let GlobalOutput::Planes(outputs) =
                validate_outputs(&dir, token, &plan.manifest_sha256, || false).unwrap()
            else {
                panic!("wrong output kind")
            };
            assert_eq!(read_planes(&paths), original);
            assert_eq!(fs::metadata(&outputs[2]).unwrap().len(), 17 * 13 * 8);
            fs::write(&outputs[2], 1f64.to_le_bytes()).unwrap();
            assert!(validate_outputs(&dir, token, &plan.manifest_sha256, || false).is_err());
        }
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn cancelled_global_prepare_and_candidate_never_publish() {
        let root = root();
        let input = rgb(&root, 17, 13);
        let p = GlobalPlan::meter(17, 13, "average", spektrafilm_math::colorspace::SRGB_TO_XYZ)
            .unwrap();
        let dir = root.join("gpu-stage-99");
        let mut calls = 0;
        assert_eq!(
            crate::gpu_stage_staging::with_test_link_error(13, || prepare_meter_stage(
                &input,
                &dir,
                99,
                &p,
                || {
                    calls += 1;
                    calls == 4
                }
            ))
            .unwrap_err(),
            "CANCELLED"
        );
        assert!(!dir.exists());
        let plan = prepare_meter_stage(&input, &dir, 99, &p, || false).unwrap();
        let mut calls = 0;
        assert_eq!(
            run_stage_cpu_mirror_for_test(&dir, 99, &plan.manifest_sha256, || {
                calls += 1;
                calls == 4
            })
            .unwrap_err(),
            "CANCELLED"
        );
        assert!(!dir.join("candidate").exists());
        assert!(input.exists());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn sampled_meter_packet_is_bounded_and_binds_original_grid_identity() {
        let root = root();
        let input = rgb(&root, 513, 1);
        let p = GlobalPlan::meter(513, 1, "average", spektrafilm_math::colorspace::SRGB_TO_XYZ)
            .unwrap();
        assert_eq!(
            (p.sample_width, p.sample_height, p.grid_version),
            (256, 1, 1)
        );
        assert_eq!(p.disk_bytes, 256 * 28 + 65536);
        let dir = root.join("gpu-stage-250");
        let (plan, snapshots) = crate::gpu_stage_staging::with_test_snapshot_summaries(|| {
            crate::gpu_stage_staging::with_test_link_error(13, || {
                prepare_meter_stage(&input, &dir, 250, &p, || false)
            })
        });
        let plan = plan.unwrap();
        assert!(
            snapshots.is_empty(),
            "AE must not snapshot/copy the full-resolution RGB source"
        );
        assert_eq!(plan.grid_version, 1);
        assert_eq!(
            fs::metadata(dir.join("source.meter.f32le")).unwrap().len(),
            256 * 28
        );
        let mut bytes = fs::read(dir.join("source.meter.f32le")).unwrap();
        bytes[..4].copy_from_slice(&0.5f32.to_le_bytes());
        fs::write(dir.join("source.meter.f32le"), bytes).unwrap();
        assert_eq!(
            plan_stage(&dir, 250).unwrap_err(),
            "INVALID_GLOBAL_SOURCE_HASH"
        );
        let p = GlobalPlan::meter(
            40000,
            40000,
            "median",
            spektrafilm_math::colorspace::SRGB_TO_XYZ,
        )
        .unwrap();
        assert_eq!(p.disk_bytes, 65536 * 28 + 65536);
        assert_eq!(p.device_bytes, 9_764_864);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn sampled_meter_source_mutation_and_symlinks_fail_closed() {
        let root = root();
        let input = rgb(&root, 17, 13);
        let p = GlobalPlan::meter(17, 13, "average", spektrafilm_math::colorspace::SRGB_TO_XYZ)
            .unwrap();
        let mut changed = false;
        let result = read_meter_pack(&input, &p, &mut || {
            if !changed {
                changed = true;
                let mut bytes = fs::read(&input).unwrap();
                bytes[..4].copy_from_slice(&1f32.to_le_bytes());
                fs::write(&input, bytes).unwrap();
            }
            false
        });
        assert_eq!(result.unwrap_err(), "GPU_PREPARE_SOURCE_IDENTITY");
        #[cfg(unix)]
        {
            let alias = root.join("alias");
            std::os::unix::fs::symlink(&input, &alias).unwrap();
            assert_eq!(
                read_meter_pack(&alias, &p, &mut || false).unwrap_err(),
                "GPU_PREPARE_SOURCE_SHAPE"
            );
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&input, fs::Permissions::from_mode(0)).unwrap();
            if fs::File::open(&input).is_err() {
                assert_eq!(
                    read_meter_pack(&input, &p, &mut || false).unwrap_err(),
                    "GPU_PREPARE_SOURCE_OPEN_EACCES"
                );
            }
            fs::set_permissions(&input, fs::Permissions::from_mode(0o600)).unwrap();
        }
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn production_global_plan_contracts_for_android() {
        let Some(destination) = std::env::var_os("FILM_GPU_CONTRACT_RESULTS") else {
            return;
        };
        let destination = PathBuf::from(destination);
        fs::create_dir_all(&destination).unwrap();
        let root = root();
        let source = rgb(&root, 17, 11);
        let paths = planes(&root, 17, 11);
        for (token, name, operation, p) in [
            (
                301,
                "auto-exposure",
                "planGpuAutoExposureStage",
                GlobalPlan::meter(17, 11, "matrix", spektrafilm_math::colorspace::SRGB_TO_XYZ)
                    .unwrap(),
            ),
            (
                302,
                "highlight-boost",
                "planGpuHighlightBoostStage",
                GlobalPlan::highlight(17, 11, 2., 0.3, 1.).unwrap(),
            ),
            (
                303,
                "exposure-log",
                "planGpuExposureLogStage",
                GlobalPlan::log_exposure(17, 11, 0.73).unwrap(),
            ),
        ] {
            let dir = root.join(format!("gpu-stage-{token}"));
            let stage = if matches!(p.kind, GlobalKind::Meter { .. }) {
                prepare_meter_stage(&source, &dir, token, &p, || false)
            } else {
                prepare_plane_stage(
                    std::array::from_fn(|ch| paths[ch].as_path()),
                    &dir,
                    token,
                    &p,
                    || false,
                )
            }
            .unwrap();
            assert_eq!(stage, plan_stage(&dir, token).unwrap());
            let envelope =
                serde_json::json!({"ok":true,"schemaVersion":1,"operation":operation,"plan":stage});
            fs::write(
                destination.join(format!("{name}.json")),
                serde_json::to_vec_pretty(&envelope).unwrap(),
            )
            .unwrap();
        }
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn unstable_f32_highlight_threshold_is_rejected_not_silently_skipped() {
        let peak = f32::from_bits((0.184f32 * 2.).to_bits() + 1);
        let p = GlobalPlan::highlight(1, 1, 2., 1., 1.).unwrap();
        let input = std::array::from_fn(|_| vec![peak as f64]);
        assert_eq!(
            planes_mirror(&p, &input, &mut || false).unwrap_err(),
            "GPU_GLOBAL_NUMERIC_CHECK"
        );
    }
}
