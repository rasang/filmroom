//! Experimental export exposure arithmetic. Original bicubic table and two matrices;
//! never the preview-only collapsed matrix. Enabled only by the isolated research route.
use serde::{Deserialize, Serialize};
use spektrafilm_math::spectral::TcLut;
pub mod full_stage;
#[cfg(any(test, feature = "gpu-diffusion-research"))]
mod streaming;
pub use full_stage::{
    estimated_disk_bytes, plan_stage, prepare_stage, validate_outputs, ExposurePlan,
};
#[cfg(feature = "gpu-diffusion-research")]
mod gpu;
#[cfg(feature = "gpu-diffusion-research")]
pub use full_stage::run_stage;
#[cfg(test)]
pub use full_stage::run_stage_cpu_mirror_for_test;
pub const NUMERIC_TOLERANCE: f64 = 2e-5;
pub const SHADER: &str = include_str!("shaders/research_exposure.wgsl");
#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ExposureDescriptor {
    pub size: usize,
    pub table: Vec<f64>,
    pub rgb_to_xyz: [[f64; 3]; 3],
    pub adaptation: [[f64; 3]; 3],
    pub auto_exposure: f64,
    pub exposure: f64,
}
impl ExposureDescriptor {
    /// Build the same two matrices as the original CPU Hanatos export, in order.
    /// The caller must supply the already-computed global AE and film EV factors.
    pub fn from_pipeline(
        pipe: &spektrafilm_core::pipeline::Pipeline,
        auto_exposure: f64,
        exposure: f64,
    ) -> Result<Self, &'static str> {
        use spektrafilm_math::{colorspace, spectral};
        let lut = pipe.tc_lut().ok_or("GPU_EXPOSURE_UNSUPPORTED")?;
        let name = &pipe.params.io.input_color_space;
        let rgb_to_xyz = spectral::colorspace_to_xyz_f64(name);
        let src = spectral::colorspace_white_xyz_f64(name);
        let dst = spectral::illuminant_xyz_f64(pipe.front_illuminant_spd());
        let adaptation = if pipe.params.settings.use_cat16 {
            colorspace::chromatic_adaptation_matrix_cat16_f64(src, dst)
        } else {
            colorspace::chromatic_adaptation_matrix_f64(src, dst)
        };
        Self::new(lut, rgb_to_xyz, adaptation, auto_exposure, exposure)
    }
    pub fn new(
        lut: &TcLut,
        rgb_to_xyz: [[f64; 3]; 3],
        adaptation: [[f64; 3]; 3],
        auto_exposure: f64,
        exposure: f64,
    ) -> Result<Self, &'static str> {
        if lut.channels != 3 {
            return Err("GPU_EXPOSURE_INVALID_TABLE");
        }
        let d = Self {
            size: lut.size,
            table: lut.data.clone(),
            rgb_to_xyz,
            adaptation,
            auto_exposure,
            exposure,
        };
        d.validate()?;
        Ok(d)
    }
    pub fn validate(&self) -> Result<(), &'static str> {
        if !(2..=1024).contains(&self.size) || self.table.len() != self.size * self.size * 3 {
            return Err("GPU_EXPOSURE_INVALID_TABLE");
        }
        if !self
            .table
            .iter()
            .chain(self.rgb_to_xyz.iter().flatten())
            .chain(self.adaptation.iter().flatten())
            .chain([&self.auto_exposure, &self.exposure])
            .all(|x| x.is_finite() && (*x as f32).is_finite())
            || self.auto_exposure <= 0.
            || self.exposure <= 0.
            || (self.auto_exposure as f32) <= 0.
            || (self.exposure as f32) <= 0.
        {
            return Err("GPU_EXPOSURE_NONFINITE");
        }
        Ok(())
    }
    /// This is the same original CPU function, used as the numerical oracle.
    pub fn reference(&self, pixel: [f32; 3]) -> [f64; 3] {
        let rgb = pixel.map(|v| (v as f64 * self.auto_exposure) * self.exposure);
        let xyz = mat64(&self.adaptation, mat64(&self.rgb_to_xyz, rgb));
        let b = xyz[0] + xyz[1] + xyz[2];
        if b <= 1e-10 {
            return [0.; 3];
        }
        let (t, u) = spektrafilm_math::spectral::xy_to_tc(xyz[0] / b, xyz[1] / b);
        let v = spektrafilm_math::lut::bicubic_2d_f64(
            &self.table,
            self.size,
            self.size,
            3,
            t * (self.size - 1) as f64,
            u * (self.size - 1) as f64,
        );
        [v[0] * b, v[1] * b, v[2] * b]
    }
    pub fn emulate_f32(&self, pixel: [f32; 3]) -> [f32; 3] {
        let invalid = [f32::NAN; 3];
        let ae_rgb = pixel.map(|v| v * self.auto_exposure as f32);
        if !ae_rgb.iter().all(|v| v.is_finite()) {
            return invalid;
        }
        let rgb = ae_rgb.map(|v| v * self.exposure as f32);
        if !rgb.iter().all(|v| v.is_finite()) {
            return invalid;
        }
        let native = mat32(&self.rgb_to_xyz.map(|r| r.map(|v| v as f32)), rgb);
        if !native.iter().all(|v| v.is_finite()) {
            return invalid;
        }
        let xyz = mat32(&self.adaptation.map(|r| r.map(|v| v as f32)), native);
        if !xyz.iter().all(|v| v.is_finite()) {
            return invalid;
        }
        let b = xyz[0] + xyz[1] + xyz[2];
        if !b.is_finite() {
            return invalid;
        }
        if b <= 1e-10 {
            return [0.; 3];
        }
        let x = xyz[0] / b;
        let y = xyz[1] / b;
        let tx = (1. - x) * (1. - x);
        let ty = y / (1. - x).max(1e-10);
        if ![x, y, tx, ty].iter().all(|v| v.is_finite()) {
            return invalid;
        }
        let t = tx.clamp(0., 1.);
        let u = ty.clamp(0., 1.);
        let max = (self.size - 1) as f32;
        let xf = (t * max).clamp(0., max);
        let yf = (u * max).clamp(0., max);
        let xi = if xf >= max {
            self.size - 2
        } else {
            xf as usize
        };
        let yi = if yf >= max {
            self.size - 2
        } else {
            yf as usize
        };
        let fx = xf - xi as f32;
        let fy = yf - yi as f32;
        let wx = [
            weight(fx + 1.),
            weight(fx),
            weight(fx - 1.),
            weight(fx - 2.),
        ];
        let wy = [
            weight(fy + 1.),
            weight(fy),
            weight(fy - 1.),
            weight(fy - 2.),
        ];
        let mut out = [0.; 3];
        let mut sum = 0.;
        for i in 0..4 {
            for j in 0..4 {
                let w = wx[i] * wy[j];
                sum += w;
                let base = (reflect(xi as isize + i as isize - 1, self.size) * self.size
                    + reflect(yi as isize + j as isize - 1, self.size))
                    * 3;
                for ch in 0..3 {
                    out[ch] += w * self.table[base + ch] as f32;
                    if !out[ch].is_finite() {
                        return invalid;
                    }
                }
            }
        }
        for v in &mut out {
            if sum != 0. {
                *v /= sum;
            }
            *v *= b;
            if !v.is_finite() {
                return invalid;
            }
        }
        out
    }
}
#[cfg(any(test, feature = "gpu-diffusion-research"))]
pub(super) fn uniform_words(d: &ExposureDescriptor, pixels: u32) -> [u32; 32] {
    let mut words = [0; 32];
    words[0] = pixels;
    words[1] = d.size as u32;
    words[4] = (d.auto_exposure as f32).to_bits();
    words[5] = (d.exposure as f32).to_bits();
    for (i, row) in d.rgb_to_xyz.iter().chain(d.adaptation.iter()).enumerate() {
        for ch in 0..3 {
            words[8 + i * 4 + ch] = (row[ch] as f32).to_bits();
        }
    }
    words
}
fn mat64(m: &[[f64; 3]; 3], x: [f64; 3]) -> [f64; 3] {
    m.map(|r| r[0] * x[0] + r[1] * x[1] + r[2] * x[2])
}
fn mat32(m: &[[f32; 3]; 3], x: [f32; 3]) -> [f32; 3] {
    m.map(|r| {
        let products = [r[0] * x[0], r[1] * x[1], r[2] * x[2]];
        let pair = products[0] + products[1];
        if products.iter().any(|v| !v.is_finite()) || !pair.is_finite() {
            return f32::NAN;
        }
        pair + products[2]
    })
}
fn reflect(i: isize, n: usize) -> usize {
    if i < 0 {
        (-i) as usize
    } else if i >= n as isize {
        (2 * (n as isize - 1) - i) as usize
    } else {
        i as usize
    }
}
fn weight(t: f32) -> f32 {
    let b = 1. / 3.;
    let c = 1. / 3.;
    let x = t.abs();
    if x < 1. {
        (1. / 6.)
            * ((12. - 9. * b - 6. * c) * x * x * x
                + (-18. + 12. * b + 6. * c) * x * x
                + (6. - 2. * b))
    } else if x < 2. {
        (1. / 6.)
            * ((-b - 6. * c) * x * x * x
                + (6. * b + 30. * c) * x * x
                + (-12. * b - 48. * c) * x
                + (8. * b + 24. * c))
    } else {
        0.
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn uniform_matches_wgsl_layout() {
        let d = fixture(5);
        let p = uniform_words(&d, 63);
        assert_eq!(std::mem::size_of_val(&p), 128);
        assert_eq!(&p[..2], &[63, 5]);
        assert_eq!(p[4], (d.auto_exposure as f32).to_bits());
        assert_eq!(p[5], (d.exposure as f32).to_bits());
        for (i, row) in d.rgb_to_xyz.iter().chain(d.adaptation.iter()).enumerate() {
            for ch in 0..3 {
                assert_eq!(p[8 + i * 4 + ch], (row[ch] as f32).to_bits());
            }
            assert_eq!(p[11 + i * 4], 0);
        }
    }
    #[test]
    fn shader_validates() {
        let m = naga::front::wgsl::parse_str(SHADER).unwrap();
        naga::valid::Validator::new(
            naga::valid::ValidationFlags::all(),
            naga::valid::Capabilities::empty(),
        )
        .validate(&m)
        .unwrap();
    }
    #[test]
    fn descriptor_rejects_approximate_shape_and_nonfinite() {
        let mut d = fixture(5);
        d.table.pop();
        assert!(d.validate().is_err());
        let mut d = fixture(5);
        d.table[0] = f64::NAN;
        assert!(d.validate().is_err());
    }
    pub(super) fn fixture(size: usize) -> ExposureDescriptor {
        let mut table = Vec::new();
        for x in 0..size {
            for y in 0..size {
                table.extend([
                    0.1 + x as f64 * 0.03,
                    0.2 + y as f64 * 0.02,
                    0.3 + (x * y) as f64 * 0.01,
                ]);
            }
        }
        ExposureDescriptor::new(
            &TcLut {
                size,
                channels: 3,
                data: table,
            },
            [[0.5, 0.3, 0.2], [0.2, 0.7, 0.1], [0.1, 0.1, 0.8]],
            [[1.01, -0.01, 0.], [0., 0.99, 0.01], [0.01, 0., 0.99]],
            1.23,
            0.87,
        )
        .unwrap()
    }
    #[test]
    fn intermediate_overflow_is_rejected_instead_of_clamped_to_black() {
        let mut d = fixture(5);
        d.auto_exposure = 16.;
        for p in [[f32::MAX / 2.; 3], [-f32::MAX / 2.; 3]] {
            assert!(d.reference(p).iter().all(|v| v.is_finite()));
            assert!(d.emulate_f32(p).iter().all(|v| v.is_nan()));
        }
        assert_eq!(d.emulate_f32([-1., -2., -3.]), [0.; 3]);
        d.auto_exposure = 1.;
        d.exposure = 1.;
        d.rgb_to_xyz = [[1e38, 1e38, -1e38]; 3];
        assert!(d.reference([10.; 3]).iter().all(|v| v.is_finite()));
        assert!(d.emulate_f32([10.; 3]).iter().all(|v| v.is_nan()));
        d.rgb_to_xyz = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]];
        d.adaptation = d.rgb_to_xyz;
        assert!(d.emulate_f32([-f32::MAX; 3]).iter().all(|v| v.is_nan()));
        d.auto_exposure = f64::MIN_POSITIVE;
        assert!(d.validate().is_err());
    }
    #[test]
    fn asymmetric_hdr_edges_preserve_bicubic_shape() {
        for n in [2, 3, 5, 17] {
            let d = fixture(n);
            for p in [
                [0., 0., 0.],
                [-1., -2., -3.],
                [1., 0., 0.],
                [0., 1., 0.],
                [0., 0., 1.],
                [12., 3., 0.4],
                [-0.1, 1., 0.2],
                [0.3, 0.8, 0.1],
            ] {
                let a = d.reference(p);
                let b = d.emulate_f32(p);
                for c in 0..3 {
                    assert!(b[c].is_finite());
                    assert!(
                        (a[c] - b[c] as f64).abs() <= 2e-5 * a[c].abs().max(1.),
                        "{n} {p:?} {a:?} {b:?}"
                    );
                }
            }
        }
    }
    #[test]
    #[cfg(feature = "precision-f64")]
    fn original_prepared_cpu_reference_bit_parity() {
        use spektrafilm_math::{image::ImageBuf, precision::from_f64};
        let d = fixture(17);
        let p = [0.31, 0.82, 1.2];
        let scaled = p.map(|v| from_f64((v as f64 * d.auto_exposure) * d.exposure));
        let image = ImageBuf::from_data(1, 1, scaled.to_vec());
        let lut = TcLut {
            size: d.size,
            channels: 3,
            data: d.table.clone(),
        };
        let cpu = spektrafilm_math::spectral::hanatos2025_rgb_to_raw_prepared(
            &image,
            &lut,
            &d.rgb_to_xyz,
            &d.adaptation,
        );
        let a = d.reference(p);
        for c in 0..3 {
            assert_eq!(a[c].to_bits(), (cpu.data[c] as f64).to_bits());
        }
    }
}
