//! Validated, distortion-only models for embedded camera lens metadata.
//!
//! The half diagonal of the caller's visible reference image is radius 1.
//! Coordinates are in that image before EXIF orientation or the user's crop.
//! Selecting the reference rectangle, excluding sensor padding and black
//! borders, belongs to the camera adapter; it is not inferred from pixel data.
//!
//! Model references (metadata extraction remains in the RAW decoder):
//! - RawTherapee `rtengine/lensmetadata.cc`, commit
//!   64c2d90a3248c7ad41842d9162368858b61aac6a: SonyMetadataLensCorrection,
//!   FujiMetadataLensCorrection, OlympusMetadataLensCorrection, and
//!   CenterRadiusMetadataLensCorrection::initCorrections/process.
//! - darktable `src/iop/lens.cc`, commit
//!   78f66f802183c6d241656f82b03ce6c10c1724d8: _init_coeffs_md_v2.
//!
//! Fuji describes a spline in source radius. We invert each linear segment
//! analytically instead of approximating its inverse with another sampled
//! spline. Chromatic aberration and vignetting are intentionally separate.

use crate::error::{Result, err};
use rawler::rawimage::EmbeddedLensDistortion;

// A bounded supported envelope, not a substitute for monotonicity checks.
// Unexpected camera generations remain decoded without applying this model.
const MIN_SCALE: f64 = 0.125;
const MAX_SCALE: f64 = 8.0;
const MIN_DERIVATIVE: f64 = 1e-8;
const MAX_KNOT_RADIUS: f64 = 4.0;

#[derive(Clone, Debug, PartialEq)]
pub enum LensStatus {
    Missing,
    Unsupported,
    Ready(EmbeddedLens),
}

#[derive(Clone, Debug, PartialEq)]
pub struct EmbeddedLens {
    model: RadialModel,
    zoom: f64,
}

#[derive(Clone, Debug, PartialEq)]
enum RadialModel {
    Sony(LinearSpline),
    Fuji(InverseSpline),
    Olympus { coefficients: [f64; 4] },
}

#[derive(Clone, Debug, PartialEq)]
struct LinearSpline {
    knots: Vec<f64>,
    factors: Vec<f64>,
}

#[derive(Clone, Debug, PartialEq)]
struct InverseSpline {
    source: LinearSpline,
    output_knots: Vec<f64>,
}

/// Malformed, unknown, or folding optional metadata never prevents RAW decode.
pub fn from_metadata(metadata: &EmbeddedLensDistortion) -> LensStatus {
    let model = match metadata {
        EmbeddedLensDistortion::Missing => return LensStatus::Missing,
        EmbeddedLensDistortion::Unsupported => return LensStatus::Unsupported,
        EmbeddedLensDistortion::Sony { samples } => sony(samples),
        EmbeddedLensDistortion::Fuji {
            knots,
            distortion,
            crop_factor,
        } => fuji(knots, distortion, *crop_factor),
        EmbeddedLensDistortion::Olympus { coefficients } => olympus(*coefficients),
    };
    match model {
        Some(model) => {
            let Some(maximum) = model.maximum_factor() else { return LensStatus::Unsupported };
            LensStatus::Ready(EmbeddedLens { model, zoom: maximum.max(1.0) })
        }
        None => LensStatus::Unsupported,
    }
}

impl EmbeddedLens {
    /// Filmroom's reference-domain convention. Fuji and Olympus use the
    /// on-file recommended visible rectangle; Sony uses the active rectangle
    /// (including SONYRAWIMAGESIZE for tiled images). This does not claim exact
    /// pixel parity with another developer's camera crop database.
    pub fn reference_uses_crop(&self) -> bool {
        matches!(self.model, RadialModel::Fuji(_) | RadialModel::Olympus { .. })
    }

    pub fn autofill_zoom(&self) -> f64 {
        self.zoom
    }

    pub fn is_identity(&self) -> bool {
        match &self.model {
            RadialModel::Sony(spline) => spline.factors.iter().all(|&v| v == 1.0),
            RadialModel::Fuji(inverse) => inverse.source.factors.iter().all(|&v| v == 1.0),
            RadialModel::Olympus { coefficients } => *coefficients == [0.0, 0.0, 0.0, 1.0],
        }
    }

    /// Inverse image mapping: an output pixel's position in the source image.
    /// Edge extension clamps the final coordinates to valid pixel centers.
    pub fn source_position(
        &self,
        width: usize,
        height: usize,
        x: usize,
        y: usize,
    ) -> Result<[f64; 2]> {
        self.position(width, height, x, y, 1.0)
    }

    /// Apply a conservative, uniform zoom before evaluating the distortion.
    /// With z >= sup(f(r), r in [0,1]), f(r/z)/z <= 1 over the whole image.
    /// Thus the corrected footprint stays inside the source rectangle; final
    /// clamping only absorbs rounding, rather than creating repeated edge bands.
    /// darktable's metadata algorithm v2 uses the same f(r/scale)/scale form
    /// (lens.cc:2783-2819), with a sampled, aspect-ratio-dependent scale.
    pub fn source_position_autofill(
        &self,
        width: usize,
        height: usize,
        x: usize,
        y: usize,
    ) -> Result<[f64; 2]> {
        self.position(width, height, x, y, self.zoom)
    }

    fn position(
        &self,
        width: usize,
        height: usize,
        x: usize,
        y: usize,
        zoom: f64,
    ) -> Result<[f64; 2]> {
        if width == 0 || height == 0 || x >= width || y >= height {
            return Err(err("INVALID_INPUT", "embedded_lens_geometry"));
        }
        let center = [width as f64 * 0.5, height as f64 * 0.5];
        let offset = [x as f64 - center[0], y as f64 - center[1]];
        let radius = offset[0].hypot(offset[1]) / center[0].hypot(center[1]) / zoom;
        let factor = self.factor(radius) / zoom;
        let position = [center[0] + factor * offset[0], center[1] + factor * offset[1]];
        if !factor.is_finite() || factor <= 0.0 || position.iter().any(|v| !v.is_finite()) {
            return Err(err("INVALID_INPUT", "embedded_lens_geometry"));
        }
        Ok([
            position[0].clamp(0.0, (width - 1) as f64),
            position[1].clamp(0.0, (height - 1) as f64),
        ])
    }

    fn factor(&self, radius: f64) -> f64 {
        self.model.factor(radius)
    }
}

impl RadialModel {
    fn factor(&self, radius: f64) -> f64 {
        match self {
            RadialModel::Sony(spline) => spline.interpolate(radius),
            RadialModel::Fuji(inverse) => inverse.factor(radius),
            RadialModel::Olympus { coefficients: [k2, k4, k6, scale] } => {
                let t = (radius * scale).powi(2);
                scale * (1.0 + t * (k2 + t * (k4 + t * k6)))
            }
        }
    }

    /// These are exact interval bounds for each supported model, not a grid
    /// search that could miss a narrow polynomial maximum.
    fn maximum_factor(&self) -> Option<f64> {
        let maximum = match self {
            Self::Sony(spline) => spline.knots.iter().zip(&spline.factors)
                .filter(|(r, _)| **r <= 1.0)
                .map(|(_, m)| *m)
                .fold(self.factor(0.0).max(self.factor(1.0)), f64::max),
            Self::Fuji(inverse) => inverse.output_knots.iter().zip(&inverse.source.factors)
                .filter(|(r, _)| **r <= 1.0)
                .map(|(_, m)| *m)
                .fold(self.factor(0.0).max(self.factor(1.0)), f64::max),
            Self::Olympus { coefficients: [k2, k4, k6, scale] } => {
                let (_, maximum) = cubic_range([1.0, *k2, *k4, *k6], scale * scale)?;
                scale * maximum
            }
        };
        supported_scale(maximum).then_some(maximum)
    }
}

impl LinearSpline {
    fn new(knots: Vec<f64>, factors: Vec<f64>) -> Option<Self> {
        if knots.len() != factors.len() || !(2..=17).contains(&knots.len())
            || knots.iter().any(|v| !v.is_finite() || !(0.0..=MAX_KNOT_RADIUS).contains(v))
            || knots.windows(2).any(|v| v[0] >= v[1])
            || factors.iter().any(|v| !supported_scale(*v))
        {
            return None;
        }
        Some(Self { knots, factors })
    }

    fn interpolate(&self, radius: f64) -> f64 {
        let upper = self.knots.partition_point(|&v| v < radius);
        if upper == 0 {
            self.factors[0]
        } else if upper == self.knots.len() {
            self.factors[upper - 1]
        } else {
            let t = (radius - self.knots[upper - 1]) / (self.knots[upper] - self.knots[upper - 1]);
            self.factors[upper - 1] + t * (self.factors[upper] - self.factors[upper - 1])
        }
    }

    fn segment(&self, lower: usize) -> (f64, f64) {
        let slope = (self.factors[lower + 1] - self.factors[lower])
            / (self.knots[lower + 1] - self.knots[lower]);
        let intercept = self.factors[lower] - slope * self.knots[lower];
        (slope, intercept)
    }

    /// For source_radius = r * (a*r + b), the derivative is 2*a*r+b.
    /// It is linear on each segment, so endpoint checks cover the full domain.
    fn maps_output_monotonically(&self) -> bool {
        (0..self.knots.len() - 1).all(|i| {
            let left = self.knots[i].max(0.0);
            let right = self.knots[i + 1].min(1.0);
            if left > right {
                return true;
            }
            let (a, b) = self.segment(i);
            [2.0 * a * left + b, 2.0 * a * right + b]
                .iter().all(|v| v.is_finite() && *v > MIN_DERIVATIVE)
        })
    }
}

impl InverseSpline {
    fn new(source: LinearSpline) -> Option<Self> {
        // Fuji output radius is s / (a*s+b). Its derivative is
        // b/(a*s+b)^2: checking b certifies the whole source segment.
        for i in 0..source.knots.len() - 1 {
            let (a, b) = source.segment(i);
            if !a.is_finite() || !b.is_finite()
                || b / source.factors[i].max(source.factors[i + 1]).powi(2) <= MIN_DERIVATIVE
            {
                return None;
            }
        }
        let output_knots: Vec<_> = source.knots.iter().zip(&source.factors)
            .map(|(r, m)| r / m).collect();
        if output_knots.windows(2).any(|v| !v[1].is_finite() || v[0] >= v[1]) {
            return None;
        }
        Some(Self { source, output_knots })
    }

    fn factor(&self, radius: f64) -> f64 {
        let upper = self.output_knots.partition_point(|&v| v < radius);
        if upper == 0 {
            self.source.factors[0]
        } else if upper == self.output_knots.len() {
            self.source.factors[upper - 1]
        } else {
            let (a, b) = self.source.segment(upper - 1);
            // r = s/(a*s+b) => s/r = b/(1-a*r).
            b / (1.0 - a * radius)
        }
    }
}

fn supported_scale(value: f64) -> bool {
    value.is_finite() && (MIN_SCALE..=MAX_SCALE).contains(&value)
}

fn sony(samples: &[i16]) -> Option<RadialModel> {
    if !(2..=16).contains(&samples.len()) {
        return None;
    }
    let knots = (0..samples.len()).map(|i| (i as f64 + 0.5) / (samples.len() - 1) as f64).collect();
    let factors = samples.iter().map(|v| 1.0 + *v as f64 / 16384.0).collect();
    let spline = LinearSpline::new(knots, factors)?;
    spline.maps_output_monotonically().then_some(RadialModel::Sony(spline))
}

fn fuji(knots: &[f64], distortion: &[f64], crop_factor: f64) -> Option<RadialModel> {
    if ![9, 11].contains(&knots.len()) || knots.len() != distortion.len()
        || (crop_factor != 1.0 && crop_factor != 1.25)
    {
        return None;
    }
    let mut source_knots = Vec::with_capacity(knots.len() + 1);
    let mut factors = Vec::with_capacity(knots.len() + 1);
    if knots[0] > 0.0 {
        source_knots.push(0.0);
        factors.push(1.0);
    }
    source_knots.extend(knots.iter().map(|v| v * crop_factor));
    factors.extend(distortion.iter().map(|v| 1.0 + v / 100.0));
    let source = LinearSpline::new(source_knots, factors)?;
    InverseSpline::new(source).map(RadialModel::Fuji)
}

fn olympus(coefficients: [f64; 4]) -> Option<RadialModel> {
    let [k2, k4, k6, scale] = coefficients;
    if coefficients.iter().any(|v| !v.is_finite()) || !supported_scale(scale) {
        return None;
    }
    let max_t = scale * scale;
    let (min_factor, max_factor) = cubic_range([1.0, k2, k4, k6], max_t)?;
    let (min_derivative, _) = cubic_range([1.0, 3.0 * k2, 5.0 * k4, 7.0 * k6], max_t)?;
    if !supported_scale(scale * min_factor) || !supported_scale(scale * max_factor)
        || scale * min_derivative <= MIN_DERIVATIVE
    {
        return None;
    }
    Some(RadialModel::Olympus { coefficients })
}

/// Exact interval extrema of a cubic: endpoints and its quadratic derivative's
/// real roots. This catches folds between samples, including narrow minima.
fn cubic_range(c: [f64; 4], max_x: f64) -> Option<(f64, f64)> {
    if c.iter().any(|v| !v.is_finite()) {
        return None;
    }
    let evaluate = |x| c[0] + x * (c[1] + x * (c[2] + x * c[3]));
    let ends = [evaluate(0.0), evaluate(max_x)];
    if ends.iter().any(|v| !v.is_finite()) {
        return None;
    }
    let mut minimum = ends[0].min(ends[1]);
    let mut maximum = ends[0].max(ends[1]);
    let (a, b, d) = (3.0 * c[3], 2.0 * c[2], c[1]);
    let roots = if a == 0.0 {
        if b == 0.0 { [f64::NAN; 2] } else { [-d / b, f64::NAN] }
    } else {
        let discriminant = b * b - 4.0 * a * d;
        if !discriminant.is_finite() {
            return None;
        }
        if discriminant < 0.0 {
            [f64::NAN; 2]
        } else {
            let q = -0.5 * (b + discriminant.sqrt().copysign(b));
            if q == 0.0 { [-b / (2.0 * a), f64::NAN] } else { [q / a, d / q] }
        }
    };
    for root in roots {
        if root > 0.0 && root < max_x {
            let value = evaluate(root);
            if !value.is_finite() { return None; }
            minimum = minimum.min(value);
            maximum = maximum.max(value);
        }
    }
    Some((minimum, maximum))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ready(metadata: EmbeddedLensDistortion) -> EmbeddedLens {
        let LensStatus::Ready(model) = from_metadata(&metadata) else {
            panic!("expected supported model: {metadata:?}");
        };
        model
    }

    fn close(actual: f64, expected: f64) {
        assert!((actual - expected).abs() < 1e-10, "actual {actual}, expected {expected}");
    }

    fn fuji_metadata(values: Vec<f64>) -> EmbeddedLensDistortion {
        EmbeddedLensDistortion::Fuji {
            knots: (0..11).map(|i| i as f64 / 10.0).collect(),
            distortion: values,
            crop_factor: 1.0,
        }
    }

    #[test]
    fn identity_preserves_pixels_in_landscape_portrait_and_single_pixel_images() {
        let models = [
            ready(EmbeddedLensDistortion::Sony { samples: vec![0; 11] }),
            ready(fuji_metadata(vec![0.0; 11])),
            ready(EmbeddedLensDistortion::Olympus { coefficients: [0.0, 0.0, 0.0, 1.0] }),
        ];
        for model in models {
            assert!(model.is_identity());
            assert_eq!(model.autofill_zoom(), 1.0);
            for (w, h) in [(12, 7), (7, 12), (1, 1)] {
                for y in 0..h {
                    for x in 0..w {
                        assert_eq!(model.source_position(w, h, x, y).unwrap(), [x as f64, y as f64]);
                        assert_eq!(model.source_position_autofill(w, h, x, y).unwrap(), [x as f64, y as f64]);
                    }
                }
            }
        }
    }

    #[test]
    fn sony_counted_table_interpolates_in_output_radius() {
        let constant = ready(EmbeddedLensDistortion::Sony { samples: vec![4096; 11] });
        assert!(!constant.is_identity());
        // Center=(300,400), half diagonal=500. A constant +4096 is 1.25x.
        assert_eq!(constant.source_position(600, 800, 400, 500).unwrap(), [425.0, 525.0]);
        assert_eq!(constant.source_position(600, 800, 0, 0).unwrap(), [0.0, 0.0]);
        let variable = ready(EmbeddedLensDistortion::Sony { samples: vec![0, -819, -1638] });
        // Three knots: .25, .75, 1.25. r=.5 is halfway through segment 0.
        close(variable.factor(0.5), 1.0 - 409.5 / 16384.0);
        close(variable.factor(0.0), 1.0);
    }

    #[test]
    fn fuji_source_spline_has_analytic_inverse_and_constant_tail() {
        // m(source_radius)=1+.1*s, thus source_radius=r/(1-.1*r).
        let model = ready(fuji_metadata((0..11).map(|i| i as f64).collect()));
        close(model.factor(0.5), 1.0 / 0.95);
        // Output radius beyond source knot 1 uses its constant 1.1 multiplier.
        close(model.factor(1.0), 1.1);
        let position = model.source_position(600, 800, 550, 400).unwrap();
        close(position[0], 300.0 + 250.0 / 0.95);
        close(position[1], 400.0);
    }

    #[test]
    fn fuji_inverse_matches_independent_binary_search_of_real_x_t20_parameters() {
        let values = [0.0, 0.114, 0.249, 0.427, 0.667, 0.976, 1.352, 1.818, 2.379, 3.042, 3.837];
        let model = ready(fuji_metadata(values.to_vec()));
        for i in 1..=100 {
            let output_radius = i as f64 / 100.0;
            let mut left = 0.0;
            let mut right = 2.0;
            // Independent forward-model evaluation and numerical inverse.
            for _ in 0..60 {
                let source_radius = (left + right) * 0.5;
                let index = ((source_radius * 10.0) as usize).min(10);
                let percent = if index == 10 { values[10] } else {
                    values[index] + (source_radius * 10.0 - index as f64) * (values[index + 1] - values[index])
                };
                if source_radius / (1.0 + percent / 100.0) < output_radius {
                    left = source_radius;
                } else {
                    right = source_radius;
                }
            }
            close(output_radius * model.factor(output_radius), (left + right) * 0.5);
        }
    }

    #[test]
    fn autofill_uses_the_entire_factor_domain_including_interior_maxima() {
        let sony = ready(EmbeddedLensDistortion::Sony { samples: vec![0, 1024, 0] });
        close(sony.autofill_zoom(), 1.0625);
        assert!(!sony.reference_uses_crop());
        // The Olympus scale peaks at t=.5, not at either endpoint.
        let olympus = ready(EmbeddedLensDistortion::Olympus {
            coefficients: [0.3, -0.3, 0.0, 1.0],
        });
        close(olympus.autofill_zoom(), 1.075);
        assert!(olympus.reference_uses_crop());
        // A constant magnification is exactly canceled by conservative fill.
        let constant = ready(EmbeddedLensDistortion::Sony { samples: vec![4096; 11] });
        close(constant.autofill_zoom(), 1.25);
        assert_eq!(constant.source_position_autofill(600, 800, 400, 500).unwrap(), [400.0, 500.0]);
    }

    #[test]
    fn real_fuji_autofill_stays_in_bounds_without_flat_clamped_edge_bands() {
        let model = ready(fuji_metadata(vec![0.0, 0.114, 0.249, 0.427, 0.667,
            0.976, 1.352, 1.818, 2.379, 3.042, 3.837]));
        close(model.autofill_zoom(), 1.03837);
        assert!(model.reference_uses_crop());
        for (width, height) in [(120, 80), (80, 120)] {
            let cx = width as f64 * 0.5;
            let cy = height as f64 * 0.5;
            for y in 0..height {
                for x in 0..width {
                    let dx = x as f64 - cx;
                    let dy = y as f64 - cy;
                    let r = dx.hypot(dy) / cx.hypot(cy);
                    // Check the footprint before source_position clamps it.
                    let factor = model.factor(r / model.zoom) / model.zoom;
                    let px = cx + dx * factor;
                    let py = cy + dy * factor;
                    assert!(px >= -1e-10 && px <= (width - 1) as f64 + 1e-10);
                    assert!(py >= -1e-10 && py <= (height - 1) as f64 + 1e-10);
                    let actual = model.source_position_autofill(width, height, x, y).unwrap();
                    close(actual[0], px.max(0.0));
                    close(actual[1], py.max(0.0));
                }
            }
        }
        // Without fill, neighboring output pixels hit the same clamped edge.
        assert_eq!(model.source_position(1200, 800, 0, 400).unwrap()[0], 0.0);
        assert_eq!(model.source_position(1200, 800, 1, 400).unwrap()[0], 0.0);
        let mut previous = model.source_position_autofill(1200, 800, 0, 400).unwrap()[0];
        assert!(previous > 0.0);
        for x in 1..40 {
            let next = model.source_position_autofill(1200, 800, x, 400).unwrap()[0];
            assert!(next > previous);
            previous = next;
        }
    }

    #[test]
    fn fuji_prepends_missing_center_and_applies_the_known_crop_radius() {
        let model = ready(EmbeddedLensDistortion::Fuji {
            knots: (1..=9).map(|i| i as f64 / 8.0).collect(),
            distortion: vec![10.0; 9],
            crop_factor: 1.25,
        });
        close(model.factor(0.0), 1.0);
        // First source node .125*1.25 has m=1.1, output node is source/1.1.
        close(model.factor(0.15625 / 1.1), 1.1);
    }

    #[test]
    fn olympus_polynomial_uses_scaled_output_radius() {
        let model = ready(EmbeddedLensDistortion::Olympus { coefficients: [0.1, 0.0, 0.0, 0.9] });
        // For r=.5: s=.9; s*(1 + .1*(r*s)^2)=.918225.
        close(model.factor(0.5), 0.918225);
        let position = model.source_position(600, 800, 550, 400).unwrap();
        close(position[0], 529.55625);
        close(position[1], 400.0);
        let real = ready(EmbeddedLensDistortion::Olympus {
            coefficients: [-0.007392614148557186, 0.008197912015020847, -0.0026901846285909414, 0.9997931718826294],
        });
        assert!(!real.is_identity());
        assert!(real.factor(1.0) < 1.0);
    }

    #[test]
    fn unknown_or_malformed_metadata_remains_a_warning_status() {
        assert_eq!(from_metadata(&EmbeddedLensDistortion::Missing), LensStatus::Missing);
        assert_eq!(from_metadata(&EmbeddedLensDistortion::Unsupported), LensStatus::Unsupported);
        let invalid = [
            EmbeddedLensDistortion::Sony { samples: vec![0] },
            EmbeddedLensDistortion::Sony { samples: vec![0; 17] },
            EmbeddedLensDistortion::Sony { samples: vec![-32768; 11] },
            EmbeddedLensDistortion::Sony { samples: vec![0, -14000] },
            fuji_metadata(vec![f64::NAN; 11]),
            fuji_metadata(vec![-100.0; 11]),
            EmbeddedLensDistortion::Fuji { knots: vec![0.0; 11], distortion: vec![0.0; 11], crop_factor: 1.0 },
            EmbeddedLensDistortion::Fuji { knots: (0..11).map(|v| v as f64).collect(), distortion: vec![0.0; 11], crop_factor: 1.1 },
            EmbeddedLensDistortion::Olympus { coefficients: [0.0, 0.0, 0.0, 0.0] },
            EmbeddedLensDistortion::Olympus { coefficients: [0.0, f64::INFINITY, 0.0, 1.0] },
            EmbeddedLensDistortion::Olympus { coefficients: [f64::MAX, 0.0, 0.0, 1.0] },
        ];
        for metadata in invalid {
            assert_eq!(from_metadata(&metadata), LensStatus::Unsupported, "{metadata:?}");
        }
    }

    #[test]
    fn folding_interior_is_rejected_even_when_endpoints_are_positive() {
        // d(source_radius)/dr = 1 - 6*t + 6*t^2. Both endpoints are 1,
        // but its interior minimum is -.5. The radial scale stays positive.
        let metadata = EmbeddedLensDistortion::Olympus { coefficients: [-2.0, 1.2, 0.0, 1.0] };
        assert_eq!(from_metadata(&metadata), LensStatus::Unsupported);
        let mut values = vec![0.0; 11];
        values[9] = 200.0;
        assert_eq!(from_metadata(&fuji_metadata(values)), LensStatus::Unsupported);
        let (min, max) = cubic_range([1.0, -6.0, 6.0, 0.0], 1.0).unwrap();
        close(min, -0.5);
        close(max, 1.0);
    }

    #[test]
    fn invalid_geometry_is_rejected_and_valid_coordinates_are_bounded() {
        let model = ready(EmbeddedLensDistortion::Sony { samples: vec![4096; 11] });
        for (w, h, x, y) in [(0, 4, 0, 0), (4, 0, 0, 0), (4, 4, 4, 1), (4, 4, 0, 4)] {
            assert!(model.source_position(w, h, x, y).is_err());
        }
        for y in 0..8 {
            for x in 0..6 {
                let p = model.source_position(6, 8, x, y).unwrap();
                assert!((0.0..=5.0).contains(&p[0]));
                assert!((0.0..=7.0).contains(&p[1]));
            }
        }
    }
}
