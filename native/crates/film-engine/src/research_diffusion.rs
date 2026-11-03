//! Isolated full camera-diffusion FFT stage prototype. Never selected by mobile
//! production rendering. Original PSF/normalization/reflect geometry, but f32 and
//! power-of-two FFT change rounding. Numeric emulator is NOT GPU evidence.
use rustfft::num_complex::Complex;
use spektrafilm_model::diffusion::ExactDiffusionKernel;
pub const SHADER: &str = include_str!("shaders/research_diffusion_fft.wgsl");
#[derive(Clone, Debug)]
pub struct Plan {
    pub width: usize,
    pub height: usize,
    pub radius: usize,
    pub side: usize,
    pub core: usize,
    pub scatter: f64,
    pub kernel: Vec<f64>,
    pub twiddles: Vec<[f32; 2]>,
    pub device_bytes: u64,
}
impl Plan {
    pub fn new(
        width: usize,
        height: usize,
        kernel: &ExactDiffusionKernel,
        channel: usize,
        budget: u64,
        max_side: usize,
    ) -> Result<Self, String> {
        if width == 0
            || height == 0
            || width > 40000
            || height > 40000
            || channel >= 3
            || !(2..=8192).contains(&max_side)
            || !max_side.is_power_of_two()
        {
            return Err("invalid dimensions/channel/FFT limit".into());
        }
        if kernel.descriptor().geometry() != [width, height] {
            return Err("PSF belongs to different original image geometry".into());
        }
        let radius = kernel.radius;
        let ks = kernel.side();
        let source_bytes = (width as u64 * height as u64 + ks as u64 * ks as u64) * 4;
        // Walk down bounded power-of-two scratch; never shrink PSF or image.
        let mut side = max_side.min((2 * radius + width.max(height).min(4096)).next_power_of_two());
        loop {
            if side <= 2 * radius {
                return Err("original PSF does not fit admitted GPU scratch".into());
            }
            let core = side - 2 * radius;
            let bytes = 3 * side as u64 * side as u64 * 8
                + source_bytes
                + side as u64 * 8
                + core as u64 * core as u64 * 8
                + 65536;
            if bytes <= budget {
                let values = (0..ks).flat_map(|y| kernel.row(channel, y)).collect();
                let twiddles = (0..side)
                    .map(|i| {
                        let angle = -2.0 * std::f64::consts::PI * i as f64 / side as f64;
                        [angle.cos() as f32, angle.sin() as f32]
                    })
                    .collect();
                return Ok(Self {
                    width,
                    height,
                    radius,
                    side,
                    core,
                    scatter: kernel.scatter,
                    kernel: values,
                    twiddles,
                    device_bytes: bytes,
                });
            }
            side /= 2;
        }
    }
    pub fn shape_signature(&self) -> [u64; 8] {
        [
            self.width as u64,
            self.height as u64,
            self.radius as u64,
            self.side as u64,
            self.core as u64,
            self.kernel.len() as u64,
            self.twiddles.len() as u64,
            self.device_bytes,
        ]
    }
    pub fn validate_layout(&self) -> Result<(), String> {
        if self.width == 0
            || self.height == 0
            || self.width > 40000
            || self.height > 40000
            || !(2..=8192).contains(&self.side)
            || !self.side.is_power_of_two()
            || self.radius >= self.side / 2
            || self.core == 0
            || self.core > 8192
            || self.core > self.side - 2 * self.radius
        {
            return Err("invalid research FFT plan geometry".into());
        }
        let ks = 2 * self.radius + 1;
        let expected = 3 * self.side as u64 * self.side as u64 * 8
            + (self.width as u64 * self.height as u64 + ks as u64 * ks as u64) * 4
            + self.side as u64 * 8
            + self.core as u64 * self.core as u64 * 8
            + 65536;
        if self.device_bytes != expected
            || self.kernel.len() != ks * ks
            || self.twiddles.len() != self.side
            || !self.scatter.is_finite()
            || !(self.scatter as f32).is_finite()
            || self
                .kernel
                .iter()
                .any(|v| !v.is_finite() || !(*v as f32).is_finite())
            || self.twiddles.iter().flatten().any(|v| !v.is_finite())
        {
            return Err("invalid research FFT tables/accounting".into());
        }
        Ok(())
    }
    pub fn tiles(&self) -> usize {
        self.width.div_ceil(self.core) * self.height.div_ceil(self.core)
    }
    pub fn validate_input(&self, input: &[f64]) -> Result<(), String> {
        if input.len() != self.width * self.height
            || input
                .iter()
                .any(|v| !v.is_finite() || !(*v as f32).is_finite())
        {
            return Err("invalid/nonfinite source plane".into());
        }
        Ok(())
    }
}
fn reflect(v: isize, n: usize) -> usize {
    if n <= 1 {
        return 0;
    }
    let period = 2 * (n - 1) as isize;
    let v = v.rem_euclid(period);
    if v < n as isize {
        v as usize
    } else {
        (period - v) as usize
    }
}
fn mul(a: Complex<f32>, b: Complex<f32>) -> Complex<f32> {
    Complex::new(a.re * b.re - a.im * b.im, a.re * b.im + a.im * b.re)
}
/// CPU execution of the proposed f32 arithmetic graph. Reports approximation
/// evidence only: no shader execution, driver FMA or mobile timing proof.
fn fft_rows(a: &mut [Complex<f32>], n: usize, inverse: bool, tw: &[[f32; 2]]) {
    let mut tmp = vec![Complex::new(0., 0.); n];
    for row in a.chunks_exact_mut(n) {
        for i in 0..n {
            tmp[i] = row[i.reverse_bits() >> (usize::BITS - n.trailing_zeros())];
        }
        let mut span = 2;
        while span <= n {
            for base in (0..n).step_by(span) {
                for j in 0..span / 2 {
                    let t = tw[j * n / span];
                    let w = Complex::new(t[0], if inverse { -t[1] } else { t[1] });
                    let even = tmp[base + j];
                    let odd = mul(tmp[base + j + span / 2], w);
                    row[base + j] = even + odd;
                    row[base + j + span / 2] = even - odd;
                }
            }
            tmp.copy_from_slice(row);
            span *= 2;
        }
    }
}
fn fft2(a: &mut Vec<Complex<f32>>, n: usize, inverse: bool, tw: &[[f32; 2]]) {
    fft_rows(a, n, inverse, tw);
    let mut b = vec![Complex::new(0., 0.); n * n];
    for y in 0..n {
        for x in 0..n {
            b[x * n + y] = a[y * n + x];
        }
    }
    fft_rows(&mut b, n, inverse, tw);
    for y in 0..n {
        for x in 0..n {
            a[y * n + x] = b[x * n + y];
        }
    }
}
pub fn emulate_f32(plan: &Plan, input: &[f64]) -> Result<Vec<f64>, String> {
    emulate_region(plan, input, None)
}
pub(crate) fn emulate_probe_f32(
    plan: &Plan,
    input: &[f64],
    width: usize,
    height: usize,
) -> Result<Vec<f64>, String> {
    if plan.width != plan.side
        || plan.height != plan.side
        || width == 0
        || height == 0
        || width > plan.core
        || height > plan.core
    {
        return Err("invalid probe ROI".into());
    }
    emulate_region(plan, input, Some((width, height)))
}
fn emulate_region(
    plan: &Plan,
    input: &[f64],
    roi: Option<(usize, usize)>,
) -> Result<Vec<f64>, String> {
    plan.validate_layout()?;
    plan.validate_input(input)?;
    let n = plan.side;
    let ks = 2 * plan.radius + 1;
    let mut spectrum = vec![Complex::new(0., 0.); n * n];
    for y in 0..ks {
        for x in 0..ks {
            spectrum[y * n + x].re = plan.kernel[y * ks + x] as f32;
        }
    }
    fft2(&mut spectrum, n, false, &plan.twiddles);
    let (ow, oh) = roi.unwrap_or((plan.width, plan.height));
    let mut output = vec![0.; ow * oh];
    for output_y in (0..oh).step_by(plan.core) {
        for output_x in (0..ow).step_by(plan.core) {
            let (ox, oy) = if roi.is_some() {
                (plan.radius, plan.radius)
            } else {
                (output_x, output_y)
            };
            let mut a = vec![Complex::new(0., 0.); n * n];
            for y in 0..n {
                let sy = reflect((oy + y) as isize - plan.radius as isize, plan.height);
                for x in 0..n {
                    let sx = reflect((ox + x) as isize - plan.radius as isize, plan.width);
                    a[y * n + x].re = input[sy * plan.width + sx] as f32;
                }
            }
            fft2(&mut a, n, false, &plan.twiddles);
            for (i, k) in a.iter_mut().zip(&spectrum) {
                *i = mul(*i, *k);
            }
            fft2(&mut a, n, true, &plan.twiddles);
            let scatter = plan.scatter as f32;
            for y in 0..plan.core.min(oh - output_y) {
                for x in 0..plan.core.min(ow - output_x) {
                    let i = (oy + y) * plan.width + ox + x;
                    let blurred =
                        a[(y + 2 * plan.radius) * n + x + 2 * plan.radius].re / (n * n) as f32;
                    output[(output_y + y) * ow + output_x + x] =
                        ((1. - scatter) * (input[i] as f32) + scatter * blurred) as f64;
                }
            }
        }
    }
    Ok(output)
}
#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorMetrics {
    pub max_abs: f64,
    pub rms: f64,
    pub reference_peak: f64,
    pub max_abs_over_peak: f64,
    pub changed_samples: usize,
    pub nonfinite_samples: usize,
}
pub fn compare(reference: &[f64], candidate: &[f64]) -> Result<ErrorMetrics, String> {
    if reference.len() != candidate.len() || reference.is_empty() {
        return Err("comparison shape mismatch".into());
    }
    let (mut max_abs, mut square, mut peak, mut changed, mut invalid) =
        (0f64, 0f64, 0f64, 0usize, 0usize);
    for (&a, &b) in reference.iter().zip(candidate) {
        if !a.is_finite() || !b.is_finite() {
            invalid += 1;
            continue;
        }
        let d = (a - b).abs();
        max_abs = max_abs.max(d);
        square += d * d;
        peak = peak.max(a.abs());
        changed += usize::from(a.to_bits() != b.to_bits());
    }
    if invalid > 0 {
        return Err(format!("nonfinite comparison samples: {invalid}"));
    }
    Ok(ErrorMetrics {
        max_abs,
        rms: (square / reference.len() as f64).sqrt(),
        reference_peak: peak,
        max_abs_over_peak: max_abs / peak.max(1e-30),
        changed_samples: changed,
        nonfinite_samples: invalid,
    })
}
#[cfg(feature = "gpu-diffusion-research")]
pub mod gpu;
#[cfg(test)]
mod tests {
    use super::*;
    fn test_plan(w: usize, h: usize, n: usize, core: usize, kernel: Vec<f64>) -> Plan {
        let twiddles = (0..n)
            .map(|i| {
                let t = -2.0 * std::f64::consts::PI * i as f64 / n as f64;
                [t.cos() as f32, t.sin() as f32]
            })
            .collect();
        Plan {
            width: w,
            height: h,
            radius: 1,
            side: n,
            core,
            scatter: 0.6,
            kernel,
            twiddles,
            device_bytes: 3 * n as u64 * n as u64 * 8
                + (w as u64 * h as u64 + 9) * 4
                + n as u64 * 8
                + core as u64 * core as u64 * 8
                + 65536,
        }
    }
    #[test]
    fn forward_fft_matches_direct_dft_on_nonsymmetric_complex_input() {
        let n = 4;
        let plan = test_plan(4, 4, n, 2, vec![0.; 9]);
        let input = (0..n * n)
            .map(|i| Complex::new((i * 7 % 13) as f32 - 3.0, (i * 11 % 7) as f32 * 0.3))
            .collect::<Vec<_>>();
        let mut actual = input.clone();
        fft2(&mut actual, n, false, &plan.twiddles);
        for ky in 0..n {
            for kx in 0..n {
                let mut expected = Complex::new(0f64, 0f64);
                for y in 0..n {
                    for x in 0..n {
                        let angle =
                            -2.0 * std::f64::consts::PI * (kx * x + ky * y) as f64 / n as f64;
                        let v = input[y * n + x];
                        expected += Complex::new(v.re as f64, v.im as f64)
                            * Complex::new(angle.cos(), angle.sin());
                    }
                }
                let v = actual[ky * n + kx];
                assert!((v.re as f64 - expected.re).abs() < 2e-5);
                assert!((v.im as f64 - expected.im).abs() < 2e-5);
            }
        }
    }
    #[test]
    fn asymmetric_convolution_matches_direct_across_small_tiles_and_edges() {
        for (w, h) in [(5, 4), (1, 7), (7, 1), (1, 1)] {
            let kernel = (1..=9).map(|v| v as f64 / 45.).collect::<Vec<_>>();
            let plan = test_plan(w, h, 8, 2, kernel.clone());
            let input = (0..w * h)
                .map(|i| (i * 13 % 29) as f64 / 7. - 1.)
                .collect::<Vec<_>>();
            let actual = emulate_f32(&plan, &input).unwrap();
            for y in 0..h {
                for x in 0..w {
                    let mut sum = 0.;
                    for ky in 0..3 {
                        for kx in 0..3 {
                            sum += kernel[ky * 3 + kx]
                                * input[reflect(y as isize + 1 - ky as isize, h) * w
                                    + reflect(x as isize + 1 - kx as isize, w)];
                        }
                    }
                    let expected = 0.4 * input[y * w + x] + 0.6 * sum;
                    assert!(
                        (actual[y * w + x] - expected).abs() < 2e-6,
                        "{w}x{h} ({x},{y}) {} {expected}",
                        actual[y * w + x]
                    );
                }
            }
        }
    }
    #[test]
    fn admission_preserves_psf_and_rejects_mutated_plan() {
        use spektrafilm_model::diffusion::DiffusionFilter;
        let df = DiffusionFilter {
            family: "black_pro_mist",
            strength: 0.62,
            spatial_scale: 1.,
            halo_warmth: 0.,
            core_intensity: 1.,
            core_size: 1.,
            halo_intensity: 1.,
            halo_size: 1.,
            bloom_intensity: 1.,
            bloom_size: 1.,
        };
        let kernel = ExactDiffusionKernel::new(&df, 10., 32, 24).unwrap();
        assert!(Plan::new(32, 24, &kernel, 0, 1, 64).is_err());
        assert!(Plan::new(31, 24, &kernel, 0, 1024 * 1024, 64).is_err());
        let plan = Plan::new(32, 24, &kernel, 0, 1024 * 1024, 64).unwrap();
        assert_eq!(plan.radius, kernel.radius);
        assert!(plan.validate_layout().is_ok());
        let mut bad = plan.clone();
        bad.core = 0;
        assert!(bad.validate_layout().is_err());
        assert!(emulate_f32(&bad, &vec![0.; 32 * 24]).is_err());
        let mut bad = plan.clone();
        bad.kernel[0] = f64::NAN;
        assert!(bad.validate_layout().is_err());
        let mut bad = plan.clone();
        bad.device_bytes -= 1;
        assert!(bad.validate_layout().is_err());
        assert!(plan.validate_input(&vec![f64::INFINITY; 32 * 24]).is_err());
    }
    #[test]
    fn nonfinite_quality_comparison_is_rejected_not_zero_error() {
        assert!(compare(&[f64::NAN], &[f64::NAN]).is_err());
        assert!(compare(&[1.0], &[f64::INFINITY]).is_err());
        assert!(compare(&[0.0], &[0.0]).unwrap().max_abs == 0.0);
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
    fn reflect_matches_numpy_style_without_edge_repeat() {
        assert_eq!(
            ((-3)..8).map(|x| reflect(x, 3)).collect::<Vec<_>>(),
            vec![1, 2, 1, 0, 1, 2, 1, 0, 1, 2, 1]
        );
        assert_eq!(reflect(-999, 1), 0);
    }
    #[test]
    fn fft_round_trip() {
        let n = 8;
        let tw = (0..n)
            .map(|i| {
                let t = -2. * std::f64::consts::PI * i as f64 / n as f64;
                [t.cos() as f32, t.sin() as f32]
            })
            .collect::<Vec<_>>();
        let a = (0..n * n)
            .map(|i| Complex::new((i % 13) as f32, 0.))
            .collect::<Vec<_>>();
        let mut b = a.clone();
        fft2(&mut b, n, false, &tw);
        fft2(&mut b, n, true, &tw);
        for (x, y) in a.iter().zip(b) {
            assert!((x.re - y.re / (n * n) as f32).abs() < 1e-5);
        }
    }
}

pub mod probe;

#[cfg(feature = "gpu-diffusion-research")]
mod probe_jni;

pub mod full_stage;
#[cfg(feature="gpu-diffusion-research")]
mod streaming;
