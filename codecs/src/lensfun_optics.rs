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

use crate::{error::{Result, err}, lensfun_geometry::{Geometry, cubic_range,
    MAX_CALIBRATION_POINTS, product_range, rectangle_radius}};

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
        let result = Self::from_terms(geometry, terms)?;
        for channel in [0, 2] {
            if !result.geometry.certify_subpixel(|lo, hi| result.factor_range(channel, lo, hi)) {
                return None;
            }
        }
        Some(result)
    }

    /// Full-table native focal interpolation. Linear TCA is exactly poly3 with
    /// zero c/b terms. The caller certifies the complete composed source map:
    /// native 1.001 alone is not a universal containment proof for arbitrary CA.
    pub fn new_samples(geometry: &Geometry, calibration: &[TcaCalibration], focal: f64) -> Option<Self> {
        Self::from_terms(geometry, interpolate_tca(calibration, focal)?)
    }

    fn from_terms(geometry: &Geometry, terms: [f32; 6]) -> Option<Self> {
        if terms[..2].iter().any(|v| !(0.5..=2.0).contains(v))
            || terms[2..].iter().any(|v| !(-0.5..=0.5).contains(v)) {
            return None;
        }
        let geometry = geometry.with_subpixel_scale()?;
        let result = Self { geometry, terms };
        result.sampling_bounds()?;
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

    pub fn is_identity(&self) -> bool { self.terms == [1.0, 1.0, 0.0, 0.0, 0.0, 0.0] }

    pub fn center_pixels(&self) -> [f64; 2] { self.geometry.center_pixels() }

    pub fn coefficients(&self) -> [f32; 6] { self.terms }

    /// Factor envelope and Euclidean Jacobian norm for the post-distortion
    /// callback only. No geometry or auto/extra zoom is included in this bound.
    pub fn sampling_bounds(&self) -> Option<(f64, f64, f64)> {
        let [w, h] = self.geometry.dimensions();
        self.sampling_bounds_for([0.0, 0.0, (w - 1) as f64, (h - 1) as f64])
    }

    pub fn sampling_bounds_for(&self, rectangle: [f64; 4]) -> Option<(f64, f64, f64)> {
        let xy = self.normalized_box(rectangle)?;
        let (_, radius) = rectangle_radius(xy);
        let (mut minimum, mut maximum, mut jacobian) = (1.0_f64, 1.0_f64, 1.0_f64);
        for channel in [0, 2] {
            let (lo, hi) = self.factor_range(channel, 0.0, radius)?;
            let index = if channel == 0 { 0 } else { 1 };
            let [v, c, b] = [self.terms[index], self.terms[index + 2], self.terms[index + 4]].map(f64::from);
            let derivative = cubic_range([v, 2.0 * c, 3.0 * b, 0.0], 0.0, radius)?;
            minimum = minimum.min(lo);
            maximum = maximum.max(hi);
            jacobian = jacobian.max(hi).max(derivative.1);
        }
        Some((minimum, maximum, jacobian * (1.0 + 1e-12)))
    }

    fn normalized_box(&self, rectangle: [f64; 4]) -> Option<[(f64, f64); 2]> {
        if rectangle.iter().any(|v| !v.is_finite() || v.abs() > 131072.0)
            || rectangle[0] > rectangle[2] || rectangle[1] > rectangle[3] { return None; }
        let (scale, _, center) = self.geometry.normalization();
        Some([0, 1].map(|i| (rectangle[i] * scale - center[i], rectangle[i + 2] * scale - center[i])))
    }

    /// Continuous interval enclosure of the standalone post callback, including
    /// an active-area halo if the caller's composed source box reaches it.
    pub fn post_source_bounds(&self, rectangle: [f64; 4], channel: usize) -> Option<[f64; 4]> {
        if channel >= 3 { return None; }
        let xy = self.normalized_box(rectangle)?;
        if channel == 1 || self.is_identity() { return Some(rectangle); }
        let (near, far) = rectangle_radius(xy);
        let factor = self.factor_range(channel, near, far)?;
        let (_, unscale, center) = self.geometry.normalization();
        let output = [0, 1].map(|i| {
            let (lo, hi) = product_range(xy[i], factor);
            ((lo + center[i]) * unscale - 1e-8, (hi + center[i]) * unscale + 1e-8)
        });
        Some([output[0].0, output[1].0, output[0].1, output[1].1])
    }

    /// Source-pixel coordinates -> per-channel source coordinates, without
    /// applying geometry or scaling again. Final active-area containment is
    /// checked by the composed pipeline, using post_source_bounds at preflight.
    pub fn post_source_position(&self, x: f64, y: f64, channel: usize) -> Result<[f64; 2]> {
        if channel >= 3 || !x.is_finite() || !y.is_finite() || x.abs() > 131072.0 || y.abs() > 131072.0 {
            return Err(err("INVALID_INPUT", "lensfun_tca"));
        }
        if channel == 1 || self.is_identity() { return Ok([x, y]); }
        let (scale, unscale, center) = self.geometry.normalization();
        let point = [x * scale - center[0], y * scale - center[1]];
        let index = if channel == 0 { 0 } else { 1 };
        let r2 = point[0] * point[0] + point[1] * point[1];
        let factor = self.terms[index] as f64 + self.terms[index + 2] as f64 * r2.sqrt()
            + self.terms[index + 4] as f64 * r2;
        let result = [(point[0] * factor + center[0]) * unscale,
            (point[1] * factor + center[1]) * unscale];
        if !factor.is_finite() || factor <= 0.0 || result.iter().any(|v| !v.is_finite()) {
            return Err(err("INVALID_INPUT", "lensfun_tca"));
        }
        Ok(result)
    }

    pub fn source_position(&self, x: usize, y: usize, channel: usize, extra_zoom: f64) -> Result<[f64; 2]> {
        if channel >= 3 { return Err(err("INVALID_INPUT", "lensfun_tca")); }
        let p = self.geometry.source_position_with_zoom(x, y, extra_zoom)?;
        if channel == 1 { return Ok(p); }
        let result = self.post_source_position(p[0], p[1], channel)?;
        let [width, height] = self.geometry.dimensions();
        if result.iter().any(|v| !v.is_finite()) || result[0] < 0.0 || result[1] < 0.0
            || result[0] > (width - 1) as f64 || result[1] > (height - 1) as f64 {
            return Err(err("INVALID_INPUT", "lensfun_tca"));
        }
        Ok(result)
    }
}

/// Native 0.3.4 Hermite interpolation. First two TCA terms are dimensionless;
/// later terms are interpolated after multiplication by sample focal length.
pub fn interpolate_tca(points: &[TcaCalibration], focal: f64) -> Option<[f32; 6]> {
    if points.is_empty() || points.len() > MAX_CALIBRATION_POINTS || !focal.is_finite()
        || !(0.1..=2000.0).contains(&focal) { return None; }
    let mut rows: Vec<TcaCalibration> = Vec::with_capacity(points.len());
    for &row in points {
        if !row.focal_mm.is_finite() || !(0.1..=2000.0).contains(&row.focal_mm)
            || row.terms.iter().any(|v| !v.is_finite()) { return None; }
        if let Some(old) = rows.iter_mut().find(|old| old.focal_mm as f32 == row.focal_mm as f32) {
            *old = row;
        } else { rows.push(row); }
    }
    rows.sort_by(|a, b| (a.focal_mm as f32).total_cmp(&(b.focal_mm as f32)));
    let focal = (focal as f32).clamp(rows.first()?.focal_mm as f32, rows.last()?.focal_mm as f32);
    let hi = rows.partition_point(|p| (p.focal_mm as f32) < focal);
    if rows[hi].focal_mm as f32 == focal { return Some(rows[hi].terms.map(|v| v as f32)); }
    let lo = hi - 1;
    let indices = [if hi + 1 < rows.len() { Some(hi + 1) } else { None }, Some(hi), Some(lo), lo.checked_sub(1)];
    let t = (focal - rows[hi].focal_mm as f32) / (rows[lo].focal_mm as f32 - rows[hi].focal_mm as f32);
    let (t2, t3) = (t * t, t * t * t);
    let mut result = [0.0; 6];
    for c in 0..6 {
        let values = indices.map(|i| i.map(|i| rows[i].terms[c] as f32
            * if c < 2 { 1.0 } else { rows[i].focal_mm as f32 }));
        let (y2, y3) = (values[1]?, values[2]?);
        let tangent2 = values[0].map_or(y3 - y2, |y1| (y3 - y1) * 0.5);
        let tangent3 = values[3].map_or(y3 - y2, |y4| (y4 - y2) * 0.5);
        result[c] = ((2.0 * t3 - 3.0 * t2 + 1.0) * y2 + (t3 - 2.0 * t2 + t) * tangent2
            + (-2.0 * t3 + 3.0 * t2) * y3 + (t3 - t2) * tangent3)
            / if c < 2 { 1.0 } else { focal };
    }
    result.iter().all(|v| v.is_finite()).then_some(result)
}

#[derive(Clone, Debug, PartialEq)]
pub struct VignettingCorrection {
    dimensions: [usize; 2],
    terms: [f32; 3],
    norm_scale: f64,
    center: [f64; 2],
    diagonal: f64,
    source_bounds: [f64; 4],
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
        let source_bounds = [-guard, -guard, (dimensions[0] - 1) as f64 + guard, (dimensions[1] - 1) as f64 + guard];
        let result = Self { dimensions, terms, norm_scale, center, diagonal, source_bounds,
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

    pub fn new_general(geometry: &Geometry, calibration: &[VignetteCalibration], aspect: f64,
        focal_range: [f64; 2], focal: f64, aperture: f64, focus_range_m: [f64; 2]) -> Option<Self> {
        let [w, h] = geometry.dimensions();
        let halo = (w - 1).min(h - 1) as f64 * 0.005;
        Self::new_general_for_bounds(geometry, calibration, aspect, focal_range, focal, aperture,
            focus_range_m, [-halo, -halo, (w - 1) as f64 + halo, (h - 1) as f64 + halo])
    }

    /// Full PA focal/aperture/distance interpolation with the actual source
    /// domain. The active rectangle may exceed DefaultCrop by more than 0.5%;
    /// every gain and the focus uncertainty are certified over that whole box.
    pub fn new_general_for_bounds(geometry: &Geometry, calibration: &[VignetteCalibration], aspect: f64,
        focal_range: [f64; 2], focal: f64, aperture: f64, focus_range_m: [f64; 2],
        source_bounds: [f64; 4]) -> Option<Self> {
        let [near, far] = focus_range_m;
        if !aperture.is_finite() || !(0.7..=128.0).contains(&aperture)
            || near.is_nan() || far.is_nan() || near <= 0.0 || far < near
            || (!near.is_finite() && near != f64::INFINITY)
            || (!far.is_finite() && far != f64::INFINITY)
            || !aspect.is_finite() || !(1.0..=4.0).contains(&aspect)
            || source_bounds.iter().any(|v| !v.is_finite() || v.abs() > 131072.0)
            || source_bounds[0] > source_bounds[2] || source_bounds[1] > source_bounds[3] {
            return None;
        }
        let points = prepare_vignetting(calibration)?;
        let reciprocal_midpoint = (1.0 / near + 1.0 / far) * 0.5;
        let estimate = if reciprocal_midpoint == 0.0 { f64::INFINITY } else { 1.0 / reciprocal_midpoint };
        let terms = interpolate_vignetting_prepared(&points, focal_range, focal, aperture, estimate)?;
        let (norm_scale, _, center) = geometry.normalization();
        let aspect = aspect as f32;
        let diagonal = (1.0 / (aspect * aspect + 1.0).sqrt() as f64) as f32 as f64;
        let result = Self { dimensions: geometry.dimensions(), terms, norm_scale, center, diagonal,
            source_bounds, aperture, focus_range_m, focus_estimate_m: estimate };
        let radius = result.max_radius_squared();
        let bounds = denominator_range(terms, radius)?;
        if bounds.0 < 1.0 / MAX_GAIN || bounds.1 > 4.0 { return None; }
        for distance in [near, far] {
            let endpoint = interpolate_vignetting_prepared(&points, focal_range, focal, aperture, distance)?;
            let range = denominator_range(endpoint, radius)?;
            if range.0 < 1.0 / MAX_GAIN || range.1 > 4.0 { return None; }
        }
        if !certify_focus_interval_with_focal(&points, aperture, focus_range_m, terms, radius,
            bounds.0, Some((focal, focal_range))) { return None; }
        Some(result)
    }

    pub fn source_bounds(&self) -> [f64; 4] { self.source_bounds }

    fn max_radius_squared(&self) -> f64 {
        let extents = [0, 1].map(|i| {
            let low = self.source_bounds[i] * self.norm_scale - self.center[i];
            let high = self.source_bounds[i + 2] * self.norm_scale - self.center[i];
            low.abs().max(high.abs()) * self.diagonal
        });
        extents[0] * extents[0] + extents[1] * extents[1]
    }

    /// Absolute original source position relative to the unrotated reference
    /// crop. The narrow extra domain includes active-area demosaic/resample taps.
    pub fn source_gain(&self, x: f64, y: f64) -> Result<f64> {
        if !x.is_finite() || !y.is_finite() || x < self.source_bounds[0] || y < self.source_bounds[1]
            || x > self.source_bounds[2] || y > self.source_bounds[3] {
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
    certify_focus_interval_with_focal(points, aperture, range, terms, radius_squared, minimum_denominator, None)
}

fn certify_focus_interval_with_focal(points: &[VignetteCalibration], aperture: f64, range: [f64; 2],
    terms: [f32; 3], radius_squared: f64, minimum_denominator: f64,
    focal: Option<(f64, [f64; 2])>) -> bool {
    if range[0] == range[1] { return true; }
    let aperture_coordinate = (4.0 / aperture as f32 as f64) as f32 as f64;
    let low = (0.1 / range[1] as f32 as f64) as f32 as f64;
    let high = (0.1 / range[0] as f32 as f64) as f32 as f64;
    if !low.is_finite() || !high.is_finite() || low > high { return false; }
    let focal_offsets: Vec<f64> = if let Some((focal, focal_range)) = focal {
        let Some(query) = focal_coordinate(focal, focal_range) else { return false; };
        let Some(offsets) = points.iter().map(|p| focal_coordinate(p.focal_mm, focal_range)
            .map(|f| (f - query) as f64)).collect::<Option<Vec<_>>>() else { return false; };
        offsets
    } else { vec![0.0; points.len()] };
    let mut budget = 2048_u32;
    focus_interval_inside(points, &focal_offsets, aperture_coordinate, low, high, terms,
        radius_squared, minimum_denominator, 0, &mut budget)
}

fn focus_interval_inside(points: &[VignetteCalibration], focal_offsets: &[f64], aperture: f64, low: f64, high: f64,
    terms: [f32; 3], q: f64, denominator: f64, depth: u8, budget: &mut u32) -> bool {
    if *budget == 0 { return false; }
    *budget -= 1;
    let mut weights = Vec::with_capacity(points.len());
    let mut exact = Vec::new();
    let mut native_domain_covered = false;
    for (point, &focal_offset) in points.iter().zip(focal_offsets) {
        let a = (4.0 / point.aperture as f32 as f64) as f32 as f64 - aperture;
        let d = (0.1 / point.distance_m as f32 as f64) as f32 as f64;
        let nearest = if (low..=high).contains(&d) { 0.0 } else { (d - low).abs().min((d - high).abs()) };
        let farthest = (d - low).abs().max((d - high).abs());
        let base = focal_offset.hypot(a);
        let r_min = base.hypot(nearest);
        let r_max = base.hypot(farthest);
        native_domain_covered |= r_max <= 1.0 - 1e-6;
        if r_min < 0.000101 { exact.push(point.terms.map(|v| v as f32 as f64)); }
        // Padding encloses float rounding of the native distance and pow call.
        let w_min = r_max.powf(-3.5) * (1.0 - 0.00001);
        let w_max = if r_min == 0.0 { f64::INFINITY } else { r_min.powf(-3.5) * (1.0 + 0.00001) };
        weights.push((w_min, w_max));
    }
    // Prefix/suffix sums keep exclusion bounds O(n), rather than O(n^2) per
    // interval, and avoid subtracting nearly equal large positive weights.
    let mut prefix = vec![(0.0, 0.0); weights.len() + 1];
    let mut suffix = prefix.clone();
    for i in 0..weights.len() { prefix[i + 1] = (prefix[i].0 + weights[i].0, prefix[i].1 + weights[i].1); }
    for i in (0..weights.len()).rev() { suffix[i] = (suffix[i + 1].0 + weights[i].0, suffix[i + 1].1 + weights[i].1); }
    let mut bounds = [[0.0_f64; 2]; 3];
    for (i, point) in points.iter().enumerate() {
        let (min, max) = weights[i];
        let other_min = (prefix[i].0 + suffix[i + 1].0) * (1.0 - 1e-12);
        let other_max = (prefix[i].1 + suffix[i + 1].1) * (1.0 + 1e-12);
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
    let mut center = [0.0; 4];
    let mut uncertainty = 0.0;
    let mut absolute_center = 0.0;
    let mut power = q;
    for j in 0..3 {
        // Also cover the native f32 accumulation and final normalization.
        let roundoff = points.len() as f64 * 16.0 * f32::EPSILON as f64
            * points.iter().map(|p| p.terms[j].abs()).fold(1.0, f64::max);
        // Retain cancellation between the signed coefficient differences.
        // The intervals may be asymmetric: use their actual center, and the
        // larger distance to either endpoint to cover midpoint rounding.
        center[j + 1] = bounds[j][0] * 0.5 + bounds[j][1] * 0.5;
        let radius = (center[j + 1] - bounds[j][0]).abs()
            .max((bounds[j][1] - center[j + 1]).abs());
        uncertainty += (radius + roundoff) * power;
        absolute_center += center[j + 1].abs() * power;
        power *= q;
    }
    // Every possible difference polynomial is its fixed signed center plus
    // an interval remainder. Cubic extrema cover the entire source radius,
    // while sum(radius_j*q^j) bounds that remainder at every radius in [0,q].
    // This tightens the proof without changing the model or the 2% limit.
    let Some((low_difference, high_difference)) = cubic_range(center, 0.0, q) else { return false; };
    let arithmetic_slack = 64.0 * f64::EPSILON * (1.0 + absolute_center + uncertainty);
    let difference = low_difference.abs().max(high_difference.abs()) + uncertainty + arithmetic_slack;
    // g_other/g_selected = P_selected/P_other. Reserving epsilon/(1+epsilon)
    // in the denominator bounds the gain ratio itself by epsilon.
    if native_domain_covered && difference <= denominator * MAX_FOCUS_RELATIVE_ERROR / (1.0 + MAX_FOCUS_RELATIVE_ERROR) { return true; }
    if depth == 16 { return false; }
    let middle = (low + high) * 0.5;
    focus_interval_inside(points, focal_offsets, aperture, low, middle, terms, q, denominator, depth + 1, budget)
        && focus_interval_inside(points, focal_offsets, aperture, middle, high, terms, q, denominator, depth + 1, budget)
}

fn prepare_vignetting(points: &[VignetteCalibration]) -> Option<Vec<VignetteCalibration>> {
    if points.is_empty() || points.len() > MAX_CALIBRATION_POINTS { return None; }
    let mut rows: Vec<VignetteCalibration> = Vec::with_capacity(points.len());
    for &p in points {
        if !p.focal_mm.is_finite() || !(0.1..=2000.0).contains(&p.focal_mm)
            || !p.aperture.is_finite() || !(0.7..=128.0).contains(&p.aperture)
            || !p.distance_m.is_finite() || !(0.01..=1000000.0).contains(&p.distance_m)
            || p.terms.iter().any(|v| !v.is_finite() || !(-64.0..=64.0).contains(v)) { return None; }
        if let Some(old) = rows.iter_mut().find(|old| old.focal_mm as f32 == p.focal_mm as f32
            && old.aperture as f32 == p.aperture as f32 && old.distance_m as f32 == p.distance_m as f32) {
            *old = p;
        } else { rows.push(p); }
    }
    Some(rows)
}

fn focal_coordinate(focal: f64, range: [f64; 2]) -> Option<f32> {
    if !focal.is_finite() || !(0.1..=2000.0).contains(&focal)
        || range.iter().any(|v| !v.is_finite() || !(0.1..=2000.0).contains(v)) || range[0] > range[1] { return None; }
    let mut result = focal as f32 - range[0] as f32;
    let difference = range[1] as f32 - range[0] as f32;
    if difference != 0.0 { result /= difference; }
    result.is_finite().then_some(result)
}

/// Full native three-dimensional PA interpolation. `focal_range` is the lens's
/// complete MinFocal/MaxFocal after official GuessParameters, not the PA subset.
pub fn interpolate_vignetting_general(points: &[VignetteCalibration], focal_range: [f64; 2],
    focal: f64, aperture: f64, distance: f64) -> Option<[f32; 3]> {
    interpolate_vignetting_prepared(&prepare_vignetting(points)?, focal_range, focal, aperture, distance)
}

fn interpolate_vignetting_prepared(points: &[VignetteCalibration], focal_range: [f64; 2],
    focal: f64, aperture: f64, distance: f64) -> Option<[f32; 3]> {
    let f1 = focal_coordinate(focal, focal_range)?;
    if !aperture.is_finite() || !(0.7..=128.0).contains(&aperture)
        || !distance.is_sign_positive() || distance.is_nan() || distance == 0.0 { return None; }
    let a1 = (4.0_f64 / aperture as f32 as f64) as f32;
    let d1 = (0.1_f64 / distance as f32 as f64) as f32;
    let mut terms = [0.0_f32; 3];
    let mut total = 0.0_f32;
    let mut closest = f32::MAX;
    for p in points {
        let df = focal_coordinate(p.focal_mm, focal_range)? - f1;
        let da = (4.0_f64 / p.aperture as f32 as f64) as f32 - a1;
        let dd = (0.1_f64 / p.distance_m as f32 as f64) as f32 - d1;
        let distance = ((df * df + da * da + dd * dd) as f64).sqrt() as f32;
        if distance < 0.0001 { return Some(p.terms.map(|v| v as f32)); }
        closest = closest.min(distance);
        let weight = (1.0 / (distance as f64).powf(3.5)).abs() as f32;
        for i in 0..3 { terms[i] += weight * p.terms[i] as f32; }
        total += weight;
    }
    if closest > 1.0 || !total.is_finite() || total <= 0.0 { return None; }
    for term in &mut terms { *term /= total; }
    terms.iter().all(|v| v.is_finite()).then_some(terms)
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
