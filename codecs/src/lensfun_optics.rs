//! Bounded Lensfun 0.3.4 subpixel and source-brightness corrections.
//!
//! Formula, normalization and IDW parameter interpolation follow the fixed
//! `lens.cpp`, `mod-subpix.cpp`, `mod-color.cpp`, `modifier.cpp` implementation:
//! https://github.com/lensfun/lensfun/tree/v0.3.4/libs/lensfun . Copyright Andrew
//! Zabolotny and Lensfun contributors, LGPL-3.0; this adaptation is distributed
//! with Filmroom's GPL-3.0-or-later source. The native library is a test oracle,
//! not an Android dependency.
//!
//! TCA is transverse/lateral chromatic aberration, not generic purple-fringe
//! removal. Vignetting multiplies original linear camera RGB before any pixel
//! remapping. Its half-diagonal radius differs from TCA's half-short-side radius.

use crate::{error::{Result, err}, lensfun_geometry::{Geometry, cubic_range}};

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TcaCalibration {
    pub focal_mm: f64,
    /// Native poly3 order: vr, vb, cr, cb, br, bb. Green is the reference plane.
    pub terms: [f64; 6],
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct VignetteCalibration {
    pub focal_mm: f64,
    pub aperture: f64,
    pub distance_m: f64,
    pub terms: [f64; 3],
}

#[derive(Clone, Debug, PartialEq)]
pub struct TcaCorrection {
    geometry: Geometry,
    terms: [f32; 6],
}

impl TcaCorrection {
    pub fn new(geometry: &Geometry, calibration: TcaCalibration, focal: f64) -> Option<Self> {
        // This qualified fixed-focal profile has exactly one calibration. Never
        // extend it to another focal length or mixed model by endpoint guessing.
        if !focal.is_finite() || !(0.1..=2000.0).contains(&focal)
            || !calibration.focal_mm.is_finite() || focal as f32 != calibration.focal_mm as f32
            || calibration.terms.iter().any(|v| !v.is_finite()) {
            return None;
        }
        let terms = calibration.terms.map(|v| v as f32);
        if terms[..2].iter().any(|v| !(0.5..=2.0).contains(v))
            || terms[2..].iter().any(|v| !(-0.5..=0.5).contains(v)) {
            return None;
        }
        let geometry = geometry.with_subpixel_scale()?;
        let result = Self { geometry, terms };
        for channel in [0, 2] {
            if !result.geometry.certify_subpixel(|lo, hi| result.factor_range(channel, lo, hi)) {
                return None;
            }
        }
        Some(result)
    }

    fn factor_range(&self, channel: usize, lo: f64, hi: f64) -> Option<(f64, f64)> {
        let index = if channel == 0 { 0 } else { 1 };
        let [v, c, b] = [self.terms[index], self.terms[index + 2], self.terms[index + 4]].map(f64::from);
        let range = cubic_range([v, c, b, 0.0], lo, hi)?;
        let derivative = cubic_range([v, 2.0 * c, 3.0 * b, 0.0], lo, hi)?;
        (range.0 > 1e-8 && derivative.0 > 1e-8).then_some(range)
    }

    pub fn autofill_zoom(&self) -> f64 { self.geometry.autofill_zoom() }

    pub fn source_position(&self, x: usize, y: usize, channel: usize, extra_zoom: f64) -> Result<[f64; 2]> {
        if channel >= 3 { return Err(err("INVALID_INPUT", "lensfun_tca")); }
        let p = self.geometry.source_position_with_zoom(x, y, extra_zoom)?;
        if channel == 1 { return Ok(p); }
        let (scale, unscale, center) = self.geometry.normalization();
        let point = [p[0] * scale - center[0], p[1] * scale - center[1]];
        let index = if channel == 0 { 0 } else { 1 };
        let r2 = point[0] * point[0] + point[1] * point[1];
        let factor = self.terms[index] as f64 + self.terms[index + 2] as f64 * r2.sqrt()
            + self.terms[index + 4] as f64 * r2;
        let result = [(point[0] * factor + center[0]) * unscale,
            (point[1] * factor + center[1]) * unscale];
        let [width, height] = self.geometry.dimensions();
        if result.iter().any(|v| !v.is_finite()) || result[0] < 0.0 || result[1] < 0.0
            || result[0] > (width - 1) as f64 || result[1] > (height - 1) as f64 {
            return Err(err("INVALID_INPUT", "lensfun_tca"));
        }
        Ok(result)
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct VignettingCorrection {
    dimensions: [usize; 2],
    terms: [f32; 3],
    norm_scale: f64,
    center: [f64; 2],
    diagonal: f64,
    guard: f64,
    pub aperture: f64,
    /// [near, far], with +infinity accepted only as an explicit on-file bound.
    pub focus_range_m: [f64; 2],
    /// Midpoint in reciprocal-distance space, not an assumed infinity value.
    pub focus_estimate_m: f64,
}

const MAX_GAIN: f64 = 64.0;
const MAX_FOCUS_RELATIVE_ERROR: f64 = 0.02;

impl VignettingCorrection {
    pub fn new(geometry: &Geometry, calibration: &[VignetteCalibration], aspect: f64,
        focal: f64, aperture: f64, focus_range_m: [f64; 2]) -> Option<Self> {
        let [near, far] = focus_range_m;
        if !aperture.is_finite() || !(0.7..=128.0).contains(&aperture)
            || near.is_nan() || far.is_nan() || near <= 0.0 || far < near
            || (!near.is_finite() && near != f64::INFINITY)
            || (!far.is_finite() && far != f64::INFINITY)
            || !aspect.is_finite() || !(1.0..=4.0).contains(&aspect) {
            return None;
        }
        let reciprocal_midpoint = (1.0 / near + 1.0 / far) * 0.5;
        let estimate = if reciprocal_midpoint == 0.0 { f64::INFINITY } else { 1.0 / reciprocal_midpoint };
        let terms = interpolate_vignetting(calibration, focal, aperture, estimate)?;
        let dimensions = geometry.dimensions();
        let (norm_scale, _, center) = geometry.normalization();
        let aspect = aspect as f32;
        // Native callbacks store this reciprocal in a float before multiplication.
        let diagonal = (1.0 / (aspect * aspect + 1.0).sqrt() as f64) as f32 as f64;
        let guard = (dimensions[0] - 1).min(dimensions[1] - 1) as f64 * 0.005;
        let result = Self { dimensions, terms, norm_scale, center, diagonal, guard,
            aperture, focus_range_m, focus_estimate_m: estimate };
        let r2_max = result.max_radius_squared();
        let bounds = denominator_range(terms, r2_max)?;
        if bounds.0 < 1.0 / MAX_GAIN || bounds.1 > 4.0 { return None; }
        // Treat the finite focus interval as uncertainty, not an exact reading.
        // Bound the entire source radius interval for both endpoint models.
        // Profile-dependent variation exceeding 2% leaves this operation off.
        for distance in [near, far] {
            let endpoint = interpolate_vignetting(calibration, focal, aperture, distance)?;
            let endpoint_bounds = denominator_range(endpoint, r2_max)?;
            if endpoint_bounds.0 < 1.0 / MAX_GAIN || endpoint_bounds.1 > 4.0 { return None; }
            let delta = [0.0, endpoint[0] as f64 - terms[0] as f64,
                endpoint[1] as f64 - terms[1] as f64, endpoint[2] as f64 - terms[2] as f64];
            let (lo, hi) = cubic_range(delta, 0.0, r2_max)?;
            if lo.abs().max(hi.abs()) / endpoint_bounds.0 > MAX_FOCUS_RELATIVE_ERROR { return None; }
        }
        // Endpoint checks alone cannot bound an IDW interpolant between them.
        // Certify all intermediate inverse distances as well, including the
        // native exact-match neighbourhoods around calibration samples.
        if !certify_focus_interval(calibration, aperture, focus_range_m, terms, r2_max, bounds.0) {
            return None;
        }
        Some(result)
    }

    fn max_radius_squared(&self) -> f64 {
        let extents = [0, 1].map(|i| {
            let low = -self.guard * self.norm_scale - self.center[i];
            let high = ((self.dimensions[i] - 1) as f64 + self.guard) * self.norm_scale - self.center[i];
            low.abs().max(high.abs()) * self.diagonal
        });
        extents[0] * extents[0] + extents[1] * extents[1]
    }

    /// Absolute original source position relative to the unrotated reference
    /// crop. The narrow extra domain includes active-area demosaic/resample taps.
    pub fn source_gain(&self, x: f64, y: f64) -> Result<f64> {
        if !x.is_finite() || !y.is_finite() || x < -self.guard || y < -self.guard
            || x > (self.dimensions[0] - 1) as f64 + self.guard
            || y > (self.dimensions[1] - 1) as f64 + self.guard {
            return Err(err("INVALID_INPUT", "lensfun_vignetting"));
        }
        let x = (x * self.norm_scale - self.center[0]) * self.diagonal;
        let y = (y * self.norm_scale - self.center[1]) * self.diagonal;
        let q = x * x + y * y;
        let k = self.terms.map(f64::from);
        let denominator = 1.0 + q * (k[0] + q * (k[1] + q * k[2]));
        let gain = 1.0 / denominator;
        if !gain.is_finite() || !(0.25..=MAX_GAIN).contains(&gain) {
            return Err(err("INVALID_INPUT", "lensfun_vignetting"));
        }
        Ok(gain)
    }

    pub fn coefficients(&self) -> [f32; 3] { self.terms }
}

fn denominator_range(terms: [f32; 3], radius_squared: f64) -> Option<(f64, f64)> {
    cubic_range([1.0, terms[0] as f64, terms[1] as f64, terms[2] as f64], 0.0, radius_squared)
}

fn certify_focus_interval(points: &[VignetteCalibration], aperture: f64, range: [f64; 2],
    terms: [f32; 3], radius_squared: f64, minimum_denominator: f64) -> bool {
    if range[0] == range[1] { return true; }
    let aperture_coordinate = (4.0 / aperture as f32 as f64) as f32 as f64;
    let low = (0.1 / range[1] as f32 as f64) as f32 as f64;
    let high = (0.1 / range[0] as f32 as f64) as f32 as f64;
    if !low.is_finite() || !high.is_finite() || low > high { return false; }
    let mut budget = 2048_u32;
    focus_interval_inside(points, aperture_coordinate, low, high, terms,
        radius_squared, minimum_denominator, 0, &mut budget)
}

fn focus_interval_inside(points: &[VignetteCalibration], aperture: f64, low: f64, high: f64,
    terms: [f32; 3], q: f64, denominator: f64, depth: u8, budget: &mut u32) -> bool {
    if *budget == 0 { return false; }
    *budget -= 1;
    let mut weights = Vec::with_capacity(points.len());
    let mut exact = Vec::new();
    for point in points {
        let a = (4.0 / point.aperture as f32 as f64) as f32 as f64 - aperture;
        let d = (0.1 / point.distance_m as f32 as f64) as f32 as f64;
        let nearest = if (low..=high).contains(&d) { 0.0 } else { (d - low).abs().min((d - high).abs()) };
        let farthest = (d - low).abs().max((d - high).abs());
        let r_min = a.hypot(nearest);
        let r_max = a.hypot(farthest);
        if r_min < 0.000101 { exact.push(point.terms.map(|v| v as f32 as f64)); }
        // Padding encloses float rounding of the native distance and pow call.
        let w_min = r_max.powf(-3.5) * (1.0 - 0.00001);
        let w_max = if r_min == 0.0 { f64::INFINITY } else { r_min.powf(-3.5) * (1.0 + 0.00001) };
        weights.push((w_min, w_max));
    }
    let mut bounds = [[0.0_f64; 2]; 3];
    for (i, point) in points.iter().enumerate() {
        let (min, max) = weights[i];
        let other_min: f64 = weights.iter().enumerate().filter(|(j, _)| *j != i).map(|(_, w)| w.0).sum();
        let other_max: f64 = weights.iter().enumerate().filter(|(j, _)| *j != i).map(|(_, w)| w.1).sum();
        let fraction_min = if other_max.is_infinite() { 0.0 }
            else if min.is_infinite() { 1.0 } else { min / (min + other_max) };
        let fraction_max = if max.is_infinite() { 1.0 } else { max / (max + other_min) };
        for j in 0..3 {
            // Center before interval evaluation. Since normalized weights sum
            // to one, this avoids losing that correlation for identical rows.
            let coefficient = point.terms[j] as f32 as f64 - terms[j] as f64;
            let values = [fraction_min * coefficient, fraction_max * coefficient];
            bounds[j][0] += values[0].min(values[1]);
            bounds[j][1] += values[0].max(values[1]);
        }
    }
    for exact in exact { for j in 0..3 {
        bounds[j][0] = bounds[j][0].min(exact[j] - terms[j] as f64);
        bounds[j][1] = bounds[j][1].max(exact[j] - terms[j] as f64);
    }}
    let mut difference = 0.0;
    let mut power = q;
    for j in 0..3 {
        // Also cover the native f32 accumulation and final normalization.
        let roundoff = points.len() as f64 * 16.0 * f32::EPSILON as f64
            * points.iter().map(|p| p.terms[j].abs()).fold(1.0, f64::max);
        let delta = bounds[j][0].abs().max(bounds[j][1].abs()) + roundoff;
        difference += delta * power;
        power *= q;
    }
    // g_other/g_selected = P_selected/P_other. Reserving epsilon/(1+epsilon)
    // in the denominator bounds the gain ratio itself by epsilon.
    if difference <= denominator * MAX_FOCUS_RELATIVE_ERROR / (1.0 + MAX_FOCUS_RELATIVE_ERROR) { return true; }
    if depth == 16 { return false; }
    let middle = (low + high) * 0.5;
    focus_interval_inside(points, aperture, low, middle, terms, q, denominator, depth + 1, budget)
        && focus_interval_inside(points, aperture, middle, high, terms, q, denominator, depth + 1, budget)
}

/// Exact-focal PA subset of native 0.3.4's inverse-distance weighting, p=3.5.
/// Aperture extrapolation is not admitted. Distance extrapolation is allowed by
/// the native metric, but the caller separately verifies the on-file interval.
pub fn interpolate_vignetting(points: &[VignetteCalibration], focal: f64,
    aperture: f64, distance: f64) -> Option<[f32; 3]> {
    if points.is_empty() || points.len() > 64 || !focal.is_finite() || !(0.1..=2000.0).contains(&focal)
        || !aperture.is_finite()
        || !distance.is_sign_positive() || distance.is_nan() || distance == 0.0 {
        return None;
    }
    let focal = focal as f32;
    let aperture = aperture as f32;
    let distance = distance as f32;
    let mut minimum = f32::INFINITY;
    let mut maximum = f32::NEG_INFINITY;
    for point in points {
        if !point.focal_mm.is_finite() || point.focal_mm as f32 != focal
            || !point.aperture.is_finite() || !(0.7..=128.0).contains(&point.aperture)
            || !point.distance_m.is_finite() || !(0.01..=1000000.0).contains(&point.distance_m)
            || point.terms.iter().any(|v| !v.is_finite() || !(-16.0..=16.0).contains(v)) {
            return None;
        }
        minimum = minimum.min(point.aperture as f32);
        maximum = maximum.max(point.aperture as f32);
    }
    if aperture < minimum || aperture > maximum { return None; }
    let a1 = (4.0_f64 / aperture as f64) as f32;
    let d1 = (0.1_f64 / distance as f64) as f32;
    let mut terms = [0.0_f32; 3];
    let mut total = 0.0_f32;
    let mut closest = f32::MAX;
    for point in points {
        let a2 = (4.0_f64 / point.aperture as f32 as f64) as f32;
        let d2 = (0.1_f64 / point.distance_m as f32 as f64) as f32;
        let da = a2 - a1;
        let dd = d2 - d1;
        let distance = ((da * da + dd * dd) as f64).sqrt() as f32;
        if distance < 0.0001 { return Some(point.terms.map(|v| v as f32)); }
        closest = closest.min(distance);
        let weight = (1.0 / (distance as f64).powf(3.5)).abs() as f32;
        for i in 0..3 { terms[i] += weight * point.terms[i] as f32; }
        total += weight;
    }
    if closest > 1.0 || !total.is_finite() || total <= 0.0 { return None; }
    for term in &mut terms { *term /= total; }
    terms.iter().all(|v| v.is_finite()).then_some(terms)
}
