//! Distortion-only, output-to-source geometry compatible with Lensfun 0.3.4.
//!
//! References: Lensfun v0.3.4 `lens.cpp` (InterpolateDistortion), `auxfun.cpp`
//! (_lf_interpolate), `modifier.cpp` (normalization), and `mod-coord.cpp`
//! (polynomials and GetAutoScale), https://github.com/lensfun/lensfun/tree/v0.3.4.
//! Those implementations are copyright Andrew Zabolotny and Lensfun contributors,
//! LGPL-3.0; this adaptation is distributed under Filmroom's GPL-3.0-or-later.
//!
//! The caller supplies an already selected, rectilinear calibration and the
//! dimensions of its unrotated reference image. Pixel centers run from 0 to
//! width/height - 1. Calibration crop, camera crop, aspect ratio, and optical
//! center are distinct inputs. `real-focal` has no effect in Lensfun 0.3.4 when
//! projection conversion is disabled, so it is deliberately absent here.
//!
//! This module does not select profiles or authorize their use. It supports only
//! one distortion model per calibration, ordered focal samples, and in-range
//! focal lengths. Unlike Lensfun's nearest-endpoint fallback, it rejects focal
//! extrapolation. Automatic scale follows Lensfun's eight-direction estimate;
//! an additional bounded interval check rejects folds and any footprint that
//! cannot be certified inside the source image. No image-sized maps are stored.

use crate::error::{Result, err};

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum DistortionModel {
    PtLens { a: f64, b: f64, c: f64 },
    Poly3 { k1: f64 },
    Poly5 { k1: f64, k2: f64 },
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct CalibrationPoint {
    pub focal_mm: f64,
    pub model: DistortionModel,
}

/// One calibration sensor and optical center, with strictly increasing focal
/// samples. Aspect ratio is the longer side divided by the shorter side.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Calibration<'a> {
    pub crop_factor: f64,
    pub aspect_ratio: f64,
    pub center: [f64; 2],
    pub points: &'a [CalibrationPoint],
}

#[derive(Clone, Debug, PartialEq)]
pub struct Geometry {
    width: usize,
    height: usize,
    model: Polynomial,
    norm_scale: f64,
    norm_unscale: f64,
    center: [f64; 2],
    scale: f32,
    inverse_scale: f64,
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct Polynomial {
    kind: u8,
    terms: [f32; 3],
}

impl Geometry {
    /// Invalid or unsafe optional calibration returns None and must leave RAW
    /// development available. This performs constant-memory setup, independent
    /// of the number of pixels. Profile/camera authorization belongs to caller.
    pub fn new(
        calibration: &Calibration<'_>,
        camera_crop: f64,
        focal_mm: f64,
        width: usize,
        height: usize,
    ) -> Option<Self> {
        if !(5..=32768).contains(&width)
            || !(5..=32768).contains(&height)
            || width.checked_mul(height)? > 52_000_000
            || !finite_between(camera_crop, 0.1, 20.0)
            || !finite_between(calibration.crop_factor, 0.1, 20.0)
            || !finite_between(calibration.aspect_ratio, 1.0, 4.0)
            || calibration.center.iter().any(|&v| !finite_between(v, -0.5, 0.5))
        {
            return None;
        }
        let model = interpolate(calibration.points, focal_mm)?;
        // Retain Lensfun's float inputs and double normalization fields. The
        // normalized coordinates and coefficients refer to the calibration
        // sensor's half short side, after conversion through sensor diagonals.
        let w = (width - 1) as f32;
        let h = (height - 1) as f32;
        let size = w.min(h);
        let image_aspect = w.max(h) / size;
        let aspect = calibration.aspect_ratio as f32;
        let aspect_correction = (aspect * aspect + 1.0).sqrt() as f64;
        let correction = (1.0 / ((image_aspect * image_aspect + 1.0).sqrt() as f64)
            * calibration.crop_factor as f32 as f64 / camera_crop as f32 as f64
            * aspect_correction) as f32;
        let mut geometry = Self {
            width,
            height,
            model,
            norm_scale: 2.0 / size as f64 * correction as f64,
            norm_unscale: size as f64 * 0.5 / correction as f64,
            center: [
                ((w / size) as f64 + calibration.center[0] as f32 as f64) * correction as f64,
                ((h / size) as f64 + calibration.center[1] as f32 as f64) * correction as f64,
            ],
            scale: 1.0,
            inverse_scale: 1.0,
        };
        geometry.scale = geometry.auto_scale()?;
        if !finite_between(geometry.scale as f64, 0.125, 8.0) {
            return None;
        }
        // Lensfun stores the scale callback's reciprocal as a float.
        geometry.inverse_scale = (1.0_f64 / geometry.scale as f64) as f32 as f64;
        geometry.certify().then_some(geometry)
    }

    pub fn dimensions(&self) -> [usize; 2] {
        [self.width, self.height]
    }

    pub fn autofill_zoom(&self) -> f64 {
        self.scale as f64
    }

    /// Corrected output pixel -> uncorrected source pixel, before EXIF
    /// orientation. All three color planes use the same position. Evaluation
    /// uses f64 and direct coordinates, avoiding Lensfun's accumulated f32
    /// row/strip stepping error; no full image coordinate array is needed.
    pub fn source_position(&self, x: usize, y: usize) -> Result<[f64; 2]> {
        if x >= self.width || y >= self.height {
            return Err(err("INVALID_INPUT", "lensfun_geometry"));
        }
        let p = self.position([x as f64, y as f64]);
        if p.iter().any(|v| !v.is_finite())
            || p[0] < 0.0 || p[0] > (self.width - 1) as f64
            || p[1] < 0.0 || p[1] > (self.height - 1) as f64
        {
            return Err(err("INVALID_INPUT", "lensfun_geometry"));
        }
        Ok(p)
    }

    fn position(&self, p: [f64; 2]) -> [f64; 2] {
        let x = (p[0] * self.norm_scale - self.center[0]) * self.inverse_scale;
        let y = (p[1] * self.norm_scale - self.center[1]) * self.inverse_scale;
        let factor = self.model.factor(x.hypot(y));
        [
            (x * factor + self.center[0]) * self.norm_unscale,
            (y * factor + self.center[1]) * self.norm_unscale,
        ]
    }

    // Match Lensfun's bounded Newton iteration and 0.1% auto-scale margin.
    // Center-offset/aspect cases which this approximation cannot contain are
    // subsequently rejected by certify(), rather than clamped at image edges.
    fn auto_scale(&self) -> Option<f32> {
        let w = (self.width - 1) as f32;
        let h = (self.height - 1) as f32;
        let corner_angle = h.atan2(w);
        let pi = std::f64::consts::PI;
        let angles = [
            0.0, corner_angle, (pi / 2.0) as f32, (pi - corner_angle as f64) as f32,
            pi as f32, (pi + corner_angle as f64) as f32,
            (pi * 1.5) as f32, (2.0 * pi - corner_angle as f64) as f32,
        ];
        let dx = w as f64 * 0.5 * self.norm_scale;
        let dy = h as f64 * 0.5 * self.norm_scale;
        let corner = (((w as f64).powi(2) + (h as f64).powi(2)) as f32).sqrt() as f64
            * 0.5 * self.norm_scale;
        let distances = [dx, corner, dy, corner, dx, corner, dy, corner];
        let residual = |p: [f32; 2]| {
            (p[0] as f64 - dx).max(-dx - p[0] as f64)
                .max(p[1] as f64 - dy).max(-dy - p[1] as f64)
        };
        let mut scale = 0.01_f32;
        for (angle, distance) in angles.into_iter().zip(distances) {
            // C++'s sin/cos overload takes lfPoint::angle as float, despite
            // storing the result in double. Retain that float evaluation:
            // Newton's finite-difference step is sensitive to this rounding.
            let (sin, cos) = angle.sin_cos();
            let distance = distance as f32;
            let mut radius = distance;
            let mut delta = 0.0001_f32;
            let mut converged = false;
            for _ in 0..=50 {
                let rd = residual(self.model.map_float([
                    cos * radius, sin * radius,
                ]));
                if !rd.is_finite() || !radius.is_finite() || radius <= 0.0 {
                    return None;
                }
                if rd.abs() < 0.001 {
                    converged = true;
                    break;
                }
                let next = radius + delta;
                let rd1 = residual(self.model.map_float([
                    cos * next, sin * next,
                ]));
                if (rd1 - rd).abs() < 0.00001 {
                    delta *= 2.0;
                } else {
                    radius = (radius as f64 - rd / ((rd1 - rd) / delta as f64)) as f32;
                }
            }
            if !converged { return None; }
            scale = scale.max(distance / radius);
        }
        Some((scale as f64 * 1.001) as f32)
    }

    fn certify(&self) -> bool {
        let lower = [
            -self.center[0] * self.inverse_scale,
            -self.center[1] * self.inverse_scale,
        ];
        let upper = [
            ((self.width - 1) as f64 * self.norm_scale - self.center[0]) * self.inverse_scale,
            ((self.height - 1) as f64 * self.norm_scale - self.center[1]) * self.inverse_scale,
        ];
        let radius = lower[0].abs().max(upper[0].abs())
            .hypot(lower[1].abs().max(upper[1].abs()));
        let Some((factor_min, _)) = self.model.range(0.0, radius, false) else { return false };
        let Some((derivative_min, _)) = self.model.range(0.0, radius, true) else { return false };
        if factor_min <= 1e-8 || derivative_min <= 1e-8 { return false; }
        // Positive factor and radial derivative imply an injective radial map.
        // Its image of a rectangle is bounded by its four transformed edges.
        // Bound those complete edges by polynomial extrema and interval
        // products, not by a finite grid of pixel samples.
        let mut budget = 4096_u32;
        for axis in 0..2 {
            for fixed in [lower[1 - axis], upper[1 - axis]] {
                if !self.edge_inside(axis, fixed, lower[axis], upper[axis], 0, &mut budget) {
                    return false;
                }
            }
        }
        true
    }

    fn edge_inside(
        &self, axis: usize, fixed: f64, lower: f64, upper: f64,
        depth: u8, budget: &mut u32,
    ) -> bool {
        if *budget == 0 { return false; }
        *budget -= 1;
        let near = if lower <= 0.0 && upper >= 0.0 { 0.0 } else { lower.abs().min(upper.abs()) };
        let far = lower.abs().max(upper.abs());
        let Some(factor) = self.model.range(fixed.hypot(near), fixed.hypot(far), false) else {
            return false;
        };
        let variable = product_range((lower, upper), factor);
        let constant = product_range((fixed, fixed), factor);
        let ranges = if axis == 0 { [variable, constant] } else { [constant, variable] };
        let mut inside = true;
        for (i, (low, high)) in ranges.into_iter().enumerate() {
            let low = (low + self.center[i]) * self.norm_unscale;
            let high = (high + self.center[i]) * self.norm_unscale;
            let limit = if i == 0 { self.width - 1 } else { self.height - 1 } as f64;
            // A small strict interior margin covers f64 arithmetic rounding.
            inside &= low > 1e-7 && high < limit - 1e-7;
            if !low.is_finite() || !high.is_finite() || high < 0.0 || low > limit {
                return false;
            }
        }
        if inside { return true; }
        if depth == 16 { return false; }
        let middle = (lower + upper) * 0.5;
        self.edge_inside(axis, fixed, lower, middle, depth + 1, budget)
            && self.edge_inside(axis, fixed, middle, upper, depth + 1, budget)
    }
}

impl Polynomial {
    fn from_model(model: DistortionModel) -> Option<Self> {
        let (kind, terms) = match model {
            DistortionModel::PtLens { a, b, c } => (0, [a, b, c]),
            DistortionModel::Poly3 { k1 } => (1, [k1, 0.0, 0.0]),
            DistortionModel::Poly5 { k1, k2 } => (2, [k1, k2, 0.0]),
        };
        if terms.iter().any(|&v| !finite_between(v, -4.0, 4.0)) { return None; }
        Some(Self { kind, terms: terms.map(|v| v as f32) })
    }

    fn map_float(self, [x, y]: [f32; 2]) -> [f32; 2] {
        let [a, b, c] = self.terms;
        let r2 = x * x + y * y;
        let factor = match self.kind {
            0 => {
                let d = (1.0_f64 - a as f64 - b as f64 - c as f64) as f32;
                let r = r2.sqrt();
                a * r2 * r + b * r2 + c * r + d
            }
            1 => (1.0_f64 - a as f64) as f32 + a * r2,
            _ => (1.0_f64 + (a * r2) as f64 + (b * r2 * r2) as f64) as f32,
        };
        [x * factor, y * factor]
    }

    fn coefficients(self, derivative: bool) -> ([f64; 4], bool) {
        let [a, b, c] = self.terms.map(f64::from);
        match self.kind {
            0 => {
                // Match the float constant held by the native callback.
                let d = (1.0 - a - b - c) as f32 as f64;
                if derivative { ([d, 2.0 * c, 3.0 * b, 4.0 * a], false) }
                else { ([d, c, b, a], false) }
            }
            1 => ([(1.0 - a) as f32 as f64, 0.0, if derivative { 3.0 * a } else { a }, 0.0], false),
            _ => ([1.0, if derivative { 3.0 * a } else { a }, if derivative { 5.0 * b } else { b }, 0.0], true),
        }
    }

    fn factor(self, r: f64) -> f64 {
        let (c, squared) = self.coefficients(false);
        let x = if squared { r * r } else { r };
        c[0] + x * (c[1] + x * (c[2] + x * c[3]))
    }

    fn range(self, low: f64, high: f64, derivative: bool) -> Option<(f64, f64)> {
        let (c, squared) = self.coefficients(derivative);
        cubic_range(c, if squared { low * low } else { low }, if squared { high * high } else { high })
    }
}

fn interpolate(points: &[CalibrationPoint], focal: f64) -> Option<Polynomial> {
    if points.is_empty() || points.len() > 64 || !finite_between(focal, 0.1, 2000.0) {
        return None;
    }
    let focal = focal as f32;
    let first = Polynomial::from_model(points[0].model)?;
    let mut previous = 0.0_f32;
    for point in points {
        let f = point.focal_mm as f32;
        if !finite_between(point.focal_mm, 0.1, 2000.0) || f <= previous
            || Polynomial::from_model(point.model)?.kind != first.kind
        {
            return None;
        }
        previous = f;
    }
    if focal < points[0].focal_mm as f32 || focal > points.last()?.focal_mm as f32 {
        return None;
    }
    let upper = points.partition_point(|point| (point.focal_mm as f32) < focal);
    if points[upper].focal_mm as f32 == focal {
        return Polynomial::from_model(points[upper].model);
    }
    let lo = upper - 1;
    let hi = upper;
    // Native spline order is descending focal length (df = focal - sample).
    let indices = [
        if hi + 1 < points.len() { Some(hi + 1) } else { None },
        Some(hi), Some(lo), lo.checked_sub(1),
    ];
    let t = (focal - points[hi].focal_mm as f32)
        / (points[lo].focal_mm as f32 - points[hi].focal_mm as f32);
    let mut result = first;
    for coefficient in 0..3 {
        let values = indices.map(|i| i.map(|i| {
            Polynomial::from_model(points[i].model).unwrap().terms[coefficient]
                * points[i].focal_mm as f32
        }));
        let y2 = values[1]?;
        let y3 = values[2]?;
        let tangent2 = values[0].map_or(y3 - y2, |y1| (y3 - y1) * 0.5);
        let tangent3 = values[3].map_or(y3 - y2, |y4| (y4 - y2) * 0.5);
        let t2 = t * t;
        let t3 = t2 * t;
        result.terms[coefficient] = ((2.0 * t3 - 3.0 * t2 + 1.0) * y2
            + (t3 - 2.0 * t2 + t) * tangent2
            + (-2.0 * t3 + 3.0 * t2) * y3
            + (t3 - t2) * tangent3) / focal;
    }
    result.terms.iter().all(|v| v.is_finite()).then_some(result)
}

fn finite_between(value: f64, min: f64, max: f64) -> bool {
    value.is_finite() && (min..=max).contains(&value)
}

fn product_range(a: (f64, f64), b: (f64, f64)) -> (f64, f64) {
    let values = [a.0 * b.0, a.0 * b.1, a.1 * b.0, a.1 * b.1];
    (values.into_iter().fold(f64::INFINITY, f64::min), values.into_iter().fold(f64::NEG_INFINITY, f64::max))
}

/// Complete extrema of a cubic on a closed interval, including narrow minima.
fn cubic_range(c: [f64; 4], low: f64, high: f64) -> Option<(f64, f64)> {
    if c.iter().any(|v| !v.is_finite()) || !low.is_finite() || !high.is_finite() || low > high {
        return None;
    }
    let evaluate = |x| c[0] + x * (c[1] + x * (c[2] + x * c[3]));
    let mut minimum = evaluate(low).min(evaluate(high));
    let mut maximum = evaluate(low).max(evaluate(high));
    let (a, b, d) = (3.0 * c[3], 2.0 * c[2], c[1]);
    let roots = if a == 0.0 {
        if b == 0.0 { [f64::NAN; 2] } else { [-d / b, f64::NAN] }
    } else {
        let discriminant = b * b - 4.0 * a * d;
        if !discriminant.is_finite() { return None; }
        if discriminant < 0.0 { [f64::NAN; 2] }
        else {
            let q = -0.5 * (b + discriminant.sqrt().copysign(b));
            if q == 0.0 { [-b / (2.0 * a), f64::NAN] } else { [q / a, d / q] }
        }
    };
    for root in roots {
        if root > low && root < high {
            let value = evaluate(root);
            if !value.is_finite() { return None; }
            minimum = minimum.min(value);
            maximum = maximum.max(value);
        }
    }
    (minimum.is_finite() && maximum.is_finite()).then_some((minimum, maximum))
}
