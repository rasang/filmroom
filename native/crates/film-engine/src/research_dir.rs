//! Opt-in complete initial-density + DIR stage. Original curve provenance and
//! global spatial dependencies; no microbatch cost gate, RNG or grain change.
use crate::research_spatial::{self as spatial, FIT, FilmOpticsPlan, Filter, MIB};
use serde::{Deserialize, Serialize};
use spektrafilm_core::{params::RuntimeParams, pipeline::Pipeline};
pub type R<T> = Result<T, String>;
pub const CHUNK_PIXELS: u32 = 65536;
pub const TABLE_BASE: u32 = 262144; // 1MiB, leaves first1MiB for complete-line gathers.
pub const CHUNK_BASE: u32 = 524288; // 2MiB, no overlap with original tables.
pub const POINT_TOLERANCE: f64 = 2e-5;
pub mod full_stage;
#[cfg(feature = "gpu-diffusion-research")]
pub mod gpu;
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Curve {
    #[serde(with = "f64_vec_bits")]
    pub axis: Vec<f64>,
    #[serde(with = "f64_vec_bits")]
    pub inverse_dx: Vec<f64>,
    #[serde(with = "f64_rgb_bits")]
    pub values: Vec<[f64; 3]>,
}
// Calibration packet transport must preserve the original f64 reciprocal and
// curve bits. Decimal JSON parsing is not used as an arithmetic conversion.
mod f64_vec_bits {
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    pub fn serialize<S: Serializer>(v: &Vec<f64>, s: S) -> Result<S::Ok, S::Error> {
        v.iter()
            .map(|x| format!("{:016x}", x.to_bits()))
            .collect::<Vec<_>>()
            .serialize(s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<f64>, D::Error> {
        Vec::<String>::deserialize(d)?
            .into_iter()
            .map(|s| {
                super::decode_bits(&s).ok_or_else(|| serde::de::Error::custom("invalid curve bits"))
            })
            .collect()
    }
}
mod f64_rgb_bits {
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    pub fn serialize<S: Serializer>(v: &Vec<[f64; 3]>, s: S) -> Result<S::Ok, S::Error> {
        v.iter()
            .map(|row| row.map(|x| format!("{:016x}", x.to_bits())))
            .collect::<Vec<_>>()
            .serialize(s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<[f64; 3]>, D::Error> {
        Vec::<[String; 3]>::deserialize(d)?
            .into_iter()
            .map(|row| {
                let mut out = [0.; 3];
                for i in 0..3 {
                    out[i] = super::decode_bits(&row[i])
                        .ok_or_else(|| serde::de::Error::custom("invalid curve bits"))?;
                }
                Ok(out)
            })
            .collect()
    }
}
mod f64_scalar_bits {
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    pub fn serialize<S: Serializer>(v: &f64, s: S) -> Result<S::Ok, S::Error> {
        format!("{:016x}", v.to_bits()).serialize(s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<f64, D::Error> {
        super::decode_bits(&String::deserialize(d)?)
            .ok_or_else(|| serde::de::Error::custom("invalid scalar bits"))
    }
}
mod f64_three_bits {
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    pub fn serialize<S: Serializer>(v: &[f64; 3], s: S) -> Result<S::Ok, S::Error> {
        v.map(|x| format!("{:016x}", x.to_bits())).serialize(s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<[f64; 3], D::Error> {
        let row = <[String; 3]>::deserialize(d)?;
        let mut out = [0.; 3];
        for i in 0..3 {
            out[i] = super::decode_bits(&row[i])
                .ok_or_else(|| serde::de::Error::custom("invalid triple bits"))?;
        }
        Ok(out)
    }
}
mod f64_matrix_bits {
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    pub fn serialize<S: Serializer>(v: &[[f64; 3]; 3], s: S) -> Result<S::Ok, S::Error> {
        v.map(|row| row.map(|x| format!("{:016x}", x.to_bits())))
            .serialize(s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<[[f64; 3]; 3], D::Error> {
        let input = <[[String; 3]; 3]>::deserialize(d)?;
        let mut out = [[0.; 3]; 3];
        for row in 0..3 {
            for col in 0..3 {
                out[row][col] = super::decode_bits(&input[row][col])
                    .ok_or_else(|| serde::de::Error::custom("invalid matrix bits"))?;
            }
        }
        Ok(out)
    }
}

fn decode_bits(s: &str) -> Option<f64> {
    if s.len() != 16
        || !s
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return None;
    }
    Some(f64::from_bits(u64::from_str_radix(s, 16).ok()?))
}

impl Curve {
    fn new(axis: Vec<f64>, values: Vec<[f64; 3]>, gamma: f64) -> R<Self> {
        if !gamma.is_finite() || gamma <= 0. {
            return Err("GPU_DIR_UNSUPPORTED".into());
        }
        let axis = if (gamma - 1.).abs() < 1e-12 {
            axis
        } else {
            axis.into_iter().map(|x| x / gamma).collect()
        };
        let inverse_dx = axis
            .windows(2)
            .map(|p| {
                let dx = p[1] - p[0];
                if dx != 0. { 1. / dx } else { 0. }
            })
            .collect();
        let result = Self {
            axis,
            inverse_dx,
            values,
        };
        result.validate()?;
        Ok(result)
    }
    fn validate(&self) -> R<()> {
        if !(2..=4096).contains(&self.axis.len())
            || self.axis.len() != self.values.len()
            || self.inverse_dx.len() + 1 != self.axis.len()
        {
            return Err("INVALID_DIR_TABLE".into());
        }
        if self
            .axis
            .iter()
            .chain(&self.inverse_dx)
            .chain(self.values.iter().flatten())
            .any(|&x| !finite(x))
            || self
                .axis
                .windows(2)
                .any(|p| p[1] <= p[0] || p[1] as f32 <= p[0] as f32)
        {
            return Err("INVALID_DIR_TABLE".into());
        }
        for (i, p) in self.axis.windows(2).enumerate() {
            if self.inverse_dx[i] != (1. / (p[1] - p[0])) {
                return Err("INVALID_DIR_TABLE".into());
            }
        }
        Ok(())
    }
    pub fn at_f64(&self, ch: usize, x: f64) -> f64 {
        if !x.is_finite() {
            return f64::NAN;
        }
        let n = self.axis.len();
        if x <= self.axis[0] {
            return self.values[0][ch];
        }
        if x >= self.axis[n - 1] {
            return self.values[n - 1][ch];
        }
        let lo = self.axis.partition_point(|&v| v <= x) - 1;
        let t = (x - self.axis[lo]) * self.inverse_dx[lo];
        self.values[lo][ch] + t * (self.values[lo + 1][ch] - self.values[lo][ch])
    }
    pub fn at_f32(&self, ch: usize, x: f32) -> f32 {
        if !x.is_finite() {
            return f32::NAN;
        }
        let n = self.axis.len();
        if x <= self.axis[0] as f32 {
            return self.values[0][ch] as f32;
        }
        if x >= self.axis[n - 1] as f32 {
            return self.values[n - 1][ch] as f32;
        }
        let lo = self.axis.partition_point(|&v| v as f32 <= x) - 1;
        let t = (x - self.axis[lo] as f32) * self.inverse_dx[lo] as f32;
        self.values[lo][ch] as f32
            + t * (self.values[lo + 1][ch] as f32 - self.values[lo][ch] as f32)
    }
}
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct DirPlan {
    pub width: u32,
    pub height: u32,
    pub dir_active: bool,
    pub positive: bool,
    pub core_sigma: f32,
    pub tail_decay: f32,
    #[serde(with = "f64_scalar_bits")]
    pub tail_weight: f64,
    #[serde(with = "f64_matrix_bits")]
    pub matrix: [[f64; 3]; 3],
    #[serde(with = "f64_three_bits")]
    pub density_max: [f64; 3],
    pub initial: Curve,
    pub before_dir: Curve,
}
impl DirPlan {
    pub fn new(pipe: &Pipeline, pitch: f32, width: u32, height: u32) -> R<Self> {
        if !pitch.is_finite() || pitch <= 0. {
            return Err("GPU_DIR_UNSUPPORTED".into());
        }
        let d = &pipe.params.film_render.dir_couplers;
        let gamma = pipe.params.film_render.density_curve_gamma as f64;
        let initial = Curve::new(
            pipe.film.log_exposure_f64(),
            spektrafilm_model::density_curves::normalize_density_curves_f64(
                &pipe.film.density_curves_f64(),
            ),
            gamma,
        )?;
        let mut matrix = spektrafilm_model::couplers::compute_dir_couplers_matrix(
            d.gamma_samelayer_rgb,
            d.gamma_interlayer_r_to_gb,
            d.gamma_interlayer_g_to_rb,
            d.gamma_interlayer_b_to_rg,
            d.inhibition_samelayer,
            d.inhibition_interlayer,
        );
        for row in &mut matrix {
            for v in row {
                *v *= d.amount;
            }
        }
        let norm = spektrafilm_model::density_curves::normalize_density_curves(
            &pipe.film.density_curves_f32(),
        );
        let le = pipe.film.log_exposure_f32();
        let positive = pipe.film.is_positive();
        let before = spektrafilm_model::couplers::compute_curves_before_dir(
            &norm,
            &le,
            &matrix.map(|row| row.map(|x| x as f32)),
            positive,
        );
        let before_dir = Curve::new(
            le.iter().map(|&x| x as f64).collect(),
            before.iter().map(|row| row.map(|x| x as f64)).collect(),
            gamma,
        )?;
        let result = Self {
            width,
            height,
            dir_active: d.active,
            positive,
            core_sigma: if d.diffusion_size_um > 0. {
                (d.diffusion_size_um / pitch as f64) as f32
            } else {
                0.
            },
            tail_decay: (d.diffusion_tail_um / pitch as f64) as f32,
            tail_weight: d.diffusion_tail_weight,
            matrix,
            density_max: spektrafilm_model::density_curves::max_density(&norm).map(|x| x as f64),
            initial,
            before_dir,
        };
        result.validate()?;
        Ok(result)
    }
    pub fn spatial_plan(&self) -> R<FilmOpticsPlan> {
        let mut p = RuntimeParams::default();
        p.film_render.halation.active = false;
        p.camera.lens_blur_um = 0.;
        FilmOpticsPlan::new(&p, 1., self.width, self.height)
    }
    pub fn validate(&self) -> R<()> {
        self.spatial_plan()?.validate()?;
        self.initial.validate()?;
        self.before_dir.validate()?;
        if self
            .matrix
            .iter()
            .flatten()
            .chain(self.density_max.iter())
            .any(|&x| !finite(x))
            || !finite(self.tail_weight)
            || !(0. ..=1.).contains(&self.tail_weight)
        {
            return Err("INVALID_DIR_PLAN".into());
        }
        Filter::new(self.core_sigma)?;
        Filter::new(self.tail_decay)?;
        for (_, ratio) in FIT {
            Filter::new((ratio * self.tail_decay as f64) as f32)?;
        }
        // Two axis + inverse-spacing + three-channel tables, each packed with one pad reciprocal.
        let table_scalars = 5 * (self.initial.axis.len() + self.before_dir.axis.len());
        if TABLE_BASE as usize + table_scalars > CHUNK_BASE as usize
            || CHUNK_BASE as u64 * 4 + CHUNK_PIXELS as u64 * 12 > spatial::TRANSFER_BYTES
        {
            return Err("GPU_DIR_MEMORY_LIMIT".into());
        }
        Ok(())
    }
    pub fn pass_count(&self) -> u64 {
        if self.dir_active && self.core_sigma > 0. {
            12
        } else {
            0
        }
    }
    pub fn chunk_pixels(&self) -> u32 {
        CHUNK_PIXELS.min(self.width * self.height)
    }
    pub fn chunk_count(&self) -> u64 {
        (self.width as u64 * self.height as u64).div_ceil(self.chunk_pixels() as u64)
    }
    pub fn disk_bytes(&self) -> u64 {
        48 * self.width as u64 * self.height as u64 + 2 * MIB
    }
    pub fn numerical_preflight(&self, c: &mut impl FnMut() -> bool) -> R<()> {
        self.validate()?;
        if self.pass_count() > 0 {
            spatial::numerical_preflight_for_sigmas(
                self.width,
                self.height,
                std::iter::once(self.core_sigma).chain(
                    FIT.into_iter()
                        .map(|(_, r)| (r * self.tail_decay as f64) as f32),
                ),
                c,
            )?;
        }
        Ok(())
    }
    pub fn initial_f64(&self, ch: usize, logs: [f64; 3]) -> f64 {
        let mut density = std::array::from_fn::<_, 3, _>(|i| self.initial.at_f64(i, logs[i]));
        if !self.dir_active {
            return density[ch];
        }
        if self.positive {
            for i in 0..3 {
                density[i] = self.density_max[i] - density[i];
            }
        }
        density[0] * self.matrix[0][ch]
            + density[1] * self.matrix[1][ch]
            + density[2] * self.matrix[2][ch]
    }
    pub fn initial_f32(&self, ch: usize, logs: [f32; 3]) -> f32 {
        let mut density = std::array::from_fn::<_, 3, _>(|i| self.initial.at_f32(i, logs[i]));
        if !self.dir_active {
            return density[ch];
        }
        if self.positive {
            for i in 0..3 {
                density[i] = self.density_max[i] as f32 - density[i];
            }
        }
        density[0] * self.matrix[0][ch] as f32
            + density[1] * self.matrix[1][ch] as f32
            + density[2] * self.matrix[2][ch] as f32
    }
}
fn finite(x: f64) -> bool {
    x.is_finite() && (x as f32).is_finite()
}
pub(crate) fn sample_indices(n: usize) -> Vec<usize> {
    let count = n.min(8);
    if count == 1 {
        return vec![0];
    }
    (0..count).map(|i| i * (n - 1) / (count - 1)).collect()
}
/// CPU arithmetic mirror, not a GPU timing or full photographic quality proof.
pub fn cpu_mirror(
    p: &DirPlan,
    logs: &[Vec<f64>; 3],
    c: &mut impl FnMut() -> bool,
) -> R<[Vec<f64>; 3]> {
    p.numerical_preflight(c)?;
    let n = p.width as usize * p.height as usize;
    if logs
        .iter()
        .any(|v| v.len() != n || v.iter().any(|&x| !finite(x)))
    {
        return Err("NONFINITE_DIR_INPUT".into());
    }
    let mut outputs = std::array::from_fn(|_| Vec::new());
    for ch in 0..3 {
        spatial::check(c)?;
        let mut corr: Vec<f64> = (0..n)
            .map(|i| {
                p.initial_f32(
                    ch,
                    [logs[0][i] as f32, logs[1][i] as f32, logs[2][i] as f32],
                ) as f64
            })
            .collect();
        if p.dir_active && p.core_sigma > 0. {
            let blur = spatial::ChannelPlan {
                lens_sigma: 0.,
                scatter: Some(spatial::Scatter {
                    sigma: p.core_sigma,
                    decay: p.tail_decay,
                    amount: 1.,
                    tail_weight: p.tail_weight,
                }),
                bounce_sigmas: vec![],
                bounce_weights: vec![],
                strength: 0.,
                divisor: 1.,
            };
            corr = spatial::channel_cpu_mirror(&corr, p.width, p.height, &blur, c)?;
        }
        if p.dir_active {
            for i in 0..n {
                corr[i] = p.before_dir.at_f32(ch, logs[ch][i] as f32 - corr[i] as f32) as f64;
            }
        }
        if corr.iter().any(|x| !x.is_finite()) {
            return Err("NONFINITE_DIR_OUTPUT".into());
        }
        outputs[ch] = corr;
    }
    Ok(outputs)
}
#[cfg(test)]
mod tests;
