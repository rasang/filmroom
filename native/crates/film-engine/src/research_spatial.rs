//! Experimental resident film optics. Original full-resolution FIR/IIR operators,
//! original scatter fit and bounce ordering; f32 is explicitly not f64 parity.
//! No camera PSF substitution, downsampling, tiled IIR restart, or RNG changes.
use serde::{Deserialize, Serialize};
use spektrafilm_core::params::RuntimeParams;
pub const SHADER: &str = include_str!("shaders/research_spatial.wgsl");
pub const MIB: u64 = 1024 * 1024;
pub const MEMORY_CAP: u64 = 768 * MIB;
pub const TRANSFER_BYTES: u64 = 4 * MIB;
pub const NUMERIC_TOLERANCE: f64 = 1e-3;
pub const FIT: [(f64, f64); 3] = [(0.1633, 0.5360), (0.6496, 1.5236), (0.1870, 2.7684)];
pub type R<T> = Result<T, String>;
pub mod full_stage;
mod guarded;
#[cfg(feature = "gpu-diffusion-research")]
pub mod gpu;
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Scatter {
    pub sigma: f32,
    pub decay: f32,
    pub amount: f64,
    pub tail_weight: f64,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ChannelPlan {
    pub lens_sigma: f32,
    pub scatter: Option<Scatter>,
    pub bounce_sigmas: Vec<f32>,
    pub bounce_weights: Vec<f64>,
    pub strength: f64,
    pub divisor: f64,
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct FilmOpticsPlan {
    pub width: u32,
    pub height: u32,
    pub channels: [ChannelPlan; 3],
    pub device_bytes: u64,
    pub host_bytes: u64,
    pub driver_allowance_bytes: u64,
    pub helper_allowance_bytes: u64,
    pub combined_peak_bytes: u64,
    pub disk_bytes: u64,
}
impl FilmOpticsPlan {
    pub fn new(params: &RuntimeParams, pitch: f32, width: u32, height: u32) -> R<Self> {
        if !pitch.is_finite() || pitch <= 0. {
            return Err("GPU_OPTICS_UNSUPPORTED".into());
        }
        let v = &params.film_render.halation;
        let strengths = v.halation_strength.map(|x| x * v.halation_amount);
        let halation = v.active && v.halation_n_bounces >= 1 && strengths.iter().any(|&x| x > 0.);
        if v.halation_n_bounces > 5 {
            return Err("GPU_OPTICS_UNSUPPORTED".into());
        }
        let mut weights: Vec<f64> = if halation {
            (0..v.halation_n_bounces)
                .map(|i| v.halation_bounce_decay.powi(i as i32))
                .collect()
        } else {
            vec![]
        };
        let sum: f64 = weights.iter().sum();
        for x in &mut weights {
            *x /= sum;
        }
        let channels = std::array::from_fn(|ch| {
            let sigma = v.scatter_core_um[ch] * v.scatter_spatial_scale / pitch as f64;
            let decay = v.scatter_tail_um[ch] * v.scatter_spatial_scale / pitch as f64;
            let scatter = if v.active && v.scatter_amount > 0. && (sigma > 0. || decay > 0.) {
                Some(Scatter {
                    sigma: sigma.max(1e-6) as f32,
                    decay: decay.max(1e-6) as f32,
                    amount: v.scatter_amount,
                    tail_weight: v.scatter_tail_weight[ch],
                })
            } else {
                None
            };
            let first = v.halation_first_sigma_um[ch] * v.halation_spatial_scale / pitch as f64;
            let active = halation && strengths[ch] != 0. && first > 0.;
            ChannelPlan {
                lens_sigma: if params.camera.lens_blur_um > 0. {
                    params.camera.lens_blur_um / pitch
                } else {
                    0.
                },
                scatter,
                bounce_sigmas: if active {
                    (0..weights.len())
                        .map(|k| (first * ((k + 1) as f64).sqrt()).max(1e-6) as f32)
                        .collect()
                } else {
                    vec![]
                },
                bounce_weights: if active { weights.clone() } else { vec![] },
                strength: if active { strengths[ch] } else { 0. },
                // Original renormalization includes channels skipped by first-sigma/strength.
                divisor: if halation && v.halation_renormalize {
                    1. + strengths[ch]
                } else {
                    1.
                },
            }
        });
        let mut result = Self {
            width,
            height,
            channels,
            device_bytes: 0,
            host_bytes: 16 * MIB,
            driver_allowance_bytes: 64 * MIB,
            helper_allowance_bytes: 64 * MIB,
            combined_peak_bytes: 0,
            disk_bytes: 0,
        };
        let plane = width as u64 * height as u64 * 4;
        // Five resident image planes, bounded gather/transfer storage and map buffer.
        result.device_bytes = 5 * plane + 2 * TRANSFER_BYTES + 65536;
        result.combined_peak_bytes = result.device_bytes + result.host_bytes + 128 * MIB;
        result.disk_bytes = 12 * plane + 65536;
        result.validate()?;
        Ok(result)
    }
    pub fn validate(&self) -> R<()> {
        let plane = self.width as u64 * self.height as u64 * 4;
        if self.width == 0
            || self.height == 0
            || self.width > 40000
            || self.height > 40000
            || plane > 128 * MIB
            || self.device_bytes != 5 * plane + 2 * TRANSFER_BYTES + 65536
            || self.host_bytes != 16 * MIB
            || self.driver_allowance_bytes != 64 * MIB
            || self.helper_allowance_bytes != 64 * MIB
            || self.combined_peak_bytes != self.device_bytes + 144 * MIB
            || self.combined_peak_bytes > MEMORY_CAP
            || self.disk_bytes != 12 * plane + 65536
        {
            return Err("GPU_OPTICS_MEMORY_LIMIT".into());
        }
        for c in &self.channels {
            if c.bounce_sigmas.len() != c.bounce_weights.len()
                || c.bounce_sigmas.len() > 5
                || !finite(c.strength)
                || !finite(c.divisor)
                || c.divisor <= 0.
                || c.bounce_weights.iter().any(|&x| !finite(x) || x < 0.)
            {
                return Err("INVALID_OPTICS_PLAN".into());
            }
            for sigma in c.sigmas() {
                Filter::new(sigma)?;
            }
            if let Some(s) = &c.scatter {
                if !finite(s.amount)
                    || !finite(s.tail_weight)
                    || s.amount < 0.
                    || !(0. ..=1.).contains(&s.tail_weight)
                {
                    return Err("INVALID_OPTICS_PLAN".into());
                }
            }
        }
        Ok(())
    }
    pub fn has_work(&self) -> bool {
        self.channels.iter().any(|c| {
            c.lens_sigma > 0.
                || c.scatter.is_some()
                || !c.bounce_sigmas.is_empty()
                || c.divisor != 1.
        })
    }
    /// Fail closed on the actual original line lengths, before allocating GPU planes.
    /// Cast coefficients are unchanged. This does not prove final-image equivalence.
    pub fn numerical_preflight(&self, cancelled: &mut impl FnMut() -> bool) -> R<()> {
        self.validate()?;
        numerical_preflight_for_sigmas(
            self.width,
            self.height,
            self.channels.iter().flat_map(ChannelPlan::sigmas),
            cancelled,
        )
    }
}
pub(crate) fn numerical_preflight_for_sigmas(
    width: u32,
    height: u32,
    sigmas: impl IntoIterator<Item = f32>,
    cancelled: &mut impl FnMut() -> bool,
) -> R<()> {
    let mut seen = Vec::new();
    for sigma in sigmas {
        if seen.contains(&sigma.to_bits()) {
            continue;
        }
        seen.push(sigma.to_bits());
        let f = Filter::new(sigma)?;
        if sigma < 3. {
            continue;
        }
        for n in [width as usize, height as usize] {
            check(cancelled)?;
            for kind in 0..2 {
                let input: Vec<f64> = (0..n)
                    .map(|i| {
                        if kind == 0 {
                            1.
                        } else if i == n / 3 {
                            16.
                        } else {
                            -0.25
                        }
                    })
                    .collect();
                let reference = f.line_f64(&input);
                let mirror = f.line_f32(&input.iter().map(|&x| x as f32).collect::<Vec<_>>());
                compare_lines(&reference, &mirror)?;
            }
        }
    }
    Ok(())
}

impl ChannelPlan {
    pub fn sigmas(&self) -> Vec<f32> {
        let mut v = vec![self.lens_sigma];
        if let Some(s) = &self.scatter {
            v.push(s.sigma);
            v.extend(FIT.iter().map(|&(_, r)| (r * s.decay as f64) as f32));
        }
        v.extend_from_slice(&self.bounce_sigmas);
        v
    }
}
fn finite(x: f64) -> bool {
    x.is_finite() && (x as f32).is_finite()
}
pub(crate) fn check(c: &mut impl FnMut() -> bool) -> R<()> {
    if c() { Err("CANCELLED".into()) } else { Ok(()) }
}
pub(crate) fn compare_lines(reference: &[f64], actual: &[f32]) -> R<(f64, f64)> {
    if reference.len() != actual.len() {
        return Err("GPU_OPTICS_NUMERIC_CHECK".into());
    }
    let mut max = 0f64;
    let mut sum = 0.;
    for (&r, &a) in reference.iter().zip(actual) {
        let e = (r - a as f64).abs();
        if !r.is_finite() || !a.is_finite() || e > NUMERIC_TOLERANCE * r.abs().max(1.) {
            return Err("GPU_OPTICS_NUMERIC_CHECK".into());
        }
        max = max.max(e);
        sum += e * e;
    }
    Ok((max, sum))
}
#[derive(Clone, Debug)]
pub(crate) enum Filter {
    Identity,
    Fir(Vec<f64>),
    Iir([f64; 4]),
}
impl Filter {
    pub fn new(sigma: f32) -> R<Self> {
        if !sigma.is_finite() || sigma < 0. || sigma > 10000. {
            return Err("GPU_OPTICS_UNSUPPORTED".into());
        }
        if sigma == 0. {
            return Ok(Self::Identity);
        }
        let s = sigma as f64;
        if s < 3. {
            let radius = (3. * s + 0.5) as usize;
            let mut k: Vec<f64> = (0..=2 * radius)
                .map(|i| {
                    let x = i as f64 - radius as f64;
                    (-x * x / (2. * s * s)).exp()
                })
                .collect();
            let sum: f64 = k.iter().sum();
            for x in &mut k {
                *x /= sum;
            }
            return Ok(Self::Fir(k));
        }
        let q = 0.98711 * s - 0.96330;
        let q2 = q * q;
        let q3 = q2 * q;
        let b0 = 1.57825 + 2.44413 * q + 1.4281 * q2 + 0.422205 * q3;
        let b1 = (2.44413 * q + 2.85619 * q2 + 1.26661 * q3) / b0;
        let b2 = -(1.4281 * q2 + 1.26661 * q3) / b0;
        let b3 = 0.422205 * q3 / b0;
        Ok(Self::Iir([1. - b1 - b2 - b3, b1, b2, b3]))
    }
    pub fn line_f64(&self, src: &[f64]) -> Vec<f64> {
        let mut dst = vec![0.; src.len()];
        match self {
            Self::Identity => dst.copy_from_slice(src),
            Self::Fir(k) => {
                for (i, x) in dst.iter_mut().enumerate() {
                    for (j, &a) in k.iter().enumerate() {
                        *x += src
                            [reflect(i as isize + j as isize - (k.len() / 2) as isize, src.len())]
                            * a;
                    }
                }
            }
            Self::Iir([a, b1, b2, b3]) => {
                if src.is_empty() {
                    return dst;
                }
                let mut s = [src[0]; 3];
                for i in 0..src.len() {
                    let x = a * src[i] + b1 * s[0] + b2 * s[1] + b3 * s[2];
                    dst[i] = x;
                    s = [x, s[0], s[1]];
                }
                s = [dst[src.len() - 1]; 3];
                for i in (0..src.len()).rev() {
                    let x = a * dst[i] + b1 * s[0] + b2 * s[1] + b3 * s[2];
                    dst[i] = x;
                    s = [x, s[0], s[1]];
                }
            }
        }
        dst
    }
    pub fn line_f32(&self, src: &[f32]) -> Vec<f32> {
        let mut dst = vec![0.; src.len()];
        match self {
            Self::Identity => dst.copy_from_slice(src),
            Self::Fir(k) => {
                for (i, x) in dst.iter_mut().enumerate() {
                    for (j, &a) in k.iter().enumerate() {
                        *x += src
                            [reflect(i as isize + j as isize - (k.len() / 2) as isize, src.len())]
                            * a as f32;
                    }
                }
            }
            Self::Iir(coeff) => {
                if src.is_empty() {
                    return dst;
                }
                let [a, b1, b2, b3] = coeff.map(|x| x as f32);
                let mut s = [src[0]; 3];
                for i in 0..src.len() {
                    let x = a * src[i] + b1 * s[0] + b2 * s[1] + b3 * s[2];
                    dst[i] = x;
                    s = [x, s[0], s[1]];
                }
                s = [dst[src.len() - 1]; 3];
                for i in (0..src.len()).rev() {
                    let x = a * dst[i] + b1 * s[0] + b2 * s[1] + b3 * s[2];
                    dst[i] = x;
                    s = [x, s[0], s[1]];
                }
            }
        }
        dst
    }
}
pub(crate) fn reflect(i: isize, n: usize) -> usize {
    if n <= 1 {
        return 0;
    }
    let p = 2 * n as isize;
    let v = i.rem_euclid(p);
    if v >= n as isize {
        (p - 1 - v) as usize
    } else {
        v as usize
    }
}
/// Test/reference arithmetic mirror only. No GPU or mobile performance evidence.
pub fn channel_cpu_mirror(
    input: &[f64],
    w: u32,
    h: u32,
    p: &ChannelPlan,
    c: &mut impl FnMut() -> bool,
) -> R<Vec<f64>> {
    if input.len() != w as usize * h as usize || input.iter().any(|&x| !finite(x)) {
        return Err("NONFINITE_OPTICS_INPUT".into());
    }
    let mut src: Vec<f32> = input.iter().map(|&x| x as f32).collect();
    let blur = |src: &[f32], sigma: f32, c: &mut dyn FnMut() -> bool| -> R<Vec<f32>> {
        let f = Filter::new(sigma)?;
        let mut tmp = vec![0.; src.len()];
        let mut out = vec![0.; src.len()];
        for y in 0..h as usize {
            if c() {
                return Err("CANCELLED".into());
            }
            tmp[y * w as usize..(y + 1) * w as usize]
                .copy_from_slice(&f.line_f32(&src[y * w as usize..(y + 1) * w as usize]));
        }
        for x in 0..w as usize {
            if c() {
                return Err("CANCELLED".into());
            }
            let col: Vec<f32> = (0..h as usize).map(|y| tmp[y * w as usize + x]).collect();
            for (y, z) in f.line_f32(&col).into_iter().enumerate() {
                out[y * w as usize + x] = z;
            }
        }
        Ok(out)
    };
    if p.lens_sigma > 0. {
        src = blur(&src, p.lens_sigma, c)?;
    }
    if let Some(s) = &p.scatter {
        let core = blur(&src, s.sigma, c)?;
        let mut tail = vec![0f32; src.len()];
        for (amp, ratio) in FIT {
            let v = blur(&src, (ratio * s.decay as f64) as f32, c)?;
            for (i, x) in tail.iter_mut().enumerate() {
                *x += amp as f32 * v[i];
            }
        }
        let a = s.amount as f32;
        let t = s.tail_weight as f32;
        for i in 0..src.len() {
            src[i] = (1. - a) * src[i] + a * ((1. - t) * core[i] + t * tail[i]);
        }
    }
    if !p.bounce_sigmas.is_empty() {
        let mut sum = vec![0f32; src.len()];
        for (&sigma, &weight) in p.bounce_sigmas.iter().zip(&p.bounce_weights) {
            let v = blur(&src, sigma, c)?;
            for (i, x) in sum.iter_mut().enumerate() {
                *x += weight as f32 * v[i];
            }
        }
        for i in 0..src.len() {
            src[i] += p.strength as f32 * sum[i];
        }
    }
    if p.divisor != 1. {
        for x in &mut src {
            *x /= p.divisor as f32;
        }
    }
    check(c)?;
    if src.iter().any(|x| !x.is_finite()) {
        return Err("NONFINITE_OPTICS_OUTPUT".into());
    }
    Ok(src.into_iter().map(f64::from).collect())
}
#[cfg(test)]
mod tests;
