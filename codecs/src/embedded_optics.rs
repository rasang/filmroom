//! Validated manufacturer TCA and source-image vignetting models.
//!
//! Coordinates share `embedded_lens`: unrotated visible reference rectangle,
//! centre (width / 2, height / 2), half diagonal = 1. Sony uses active area;
//! Fuji/Olympus use the on-file default crop. TCA consumes the distortion
//! mapping's source position and is composed before the single RGB resample.
//! Vignetting multiplies source pixels before that resample.
//!
//! Formula references:
//! RawTherapee rtengine/lensmetadata.cc at
//! 64c2d90a3248c7ad41842d9162368858b61aac6a (Sony/Fuji tables and gains);
//! darktable src/iop/lens.cc at
//! 78f66f802183c6d241656f82b03ce6c10c1724d8 (Olympus source-radius TCA).
//! These models evaluate the underlying functions directly: they do not claim
//! pixel parity with either application's sampled splines/crop databases.

use crate::error::{Result, err};
use rawler::rawimage::{EmbeddedLensCorrections, EmbeddedLensDistortion, EmbeddedOpticsState, EmbeddedTca, EmbeddedVignette};

const MIN_TCA_FACTOR: f64 = 0.5;
const MAX_TCA_FACTOR: f64 = 2.0;
const MIN_GAIN: f64 = 0.125;
const MAX_GAIN: f64 = 64.0;
const MIN_DERIVATIVE: f64 = 1e-8;
const MAX_KNOT_RADIUS: f64 = 4.0;

#[derive(Clone, Debug, PartialEq, Default)]
pub enum State<T> {
    #[default]
    Missing,
    Unsupported,
    Ready(T),
}

#[derive(Clone, Debug, PartialEq, Default)]
pub struct LensExtras {
    pub tca: State<TcaModel>,
    pub vignette: State<VignetteModel>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct TcaModel {
    model: TcaKind,
    zoom: f64,
}

#[derive(Clone, Debug, PartialEq)]
enum TcaKind {
    Sony { red: Spline, blue: Spline, distortion: Option<ForwardRadius> },
    Fuji { red: Spline, blue: Spline },
    Olympus { coefficients: [f64; 6] },
}

#[derive(Clone, Debug, PartialEq)]
pub struct VignetteModel {
    gains: Spline,
    crop_reference: bool,
}

#[derive(Clone, Debug, PartialEq)]
struct Spline {
    knots: Vec<f64>,
    values: Vec<f64>,
}

/// An invertible map s = r * D(r), retaining the actual Sony linear D spline.
#[derive(Clone, Debug, PartialEq)]
struct ForwardRadius {
    factor: Spline,
    source_knots: Vec<f64>,
}

pub fn from_metadata(metadata: &EmbeddedLensCorrections) -> LensExtras {
    LensExtras {
        tca: validate(&metadata.tca, make_tca),
        vignette: validate(&metadata.vignette, make_vignette),
    }
}

fn validate<A, B>(state: &EmbeddedOpticsState<A>, f: impl FnOnce(&A) -> Option<B>) -> State<B> {
    match state {
        EmbeddedOpticsState::Missing => State::Missing,
        EmbeddedOpticsState::Unsupported => State::Unsupported,
        EmbeddedOpticsState::Ready(value) => f(value).map(State::Ready).unwrap_or(State::Unsupported),
    }
}

impl LensExtras {
    pub fn has_ready(&self) -> bool {
        matches!(self.tca, State::Ready(_)) || matches!(self.vignette, State::Ready(_))
    }

    pub fn has_active(&self) -> bool {
        matches!(&self.tca, State::Ready(model) if !model.is_identity())
            || matches!(&self.vignette, State::Ready(model) if !model.is_identity())
    }

    pub fn reference_uses_crop(&self) -> bool {
        matches!(&self.tca, State::Ready(model) if model.reference_uses_crop())
            || matches!(&self.vignette, State::Ready(model) if model.reference_uses_crop())
    }
}

impl TcaModel {
    pub fn reference_uses_crop(&self) -> bool {
        !matches!(self.model, TcaKind::Sony { .. })
    }

    /// Continuous upper bound on all three channel factors, at least one.
    /// Multiply the distortion autofill zoom by this bound BEFORE either map.
    pub fn autofill_zoom(&self) -> f64 { self.zoom }

    /// Continuous bounds over source radius [0, 1], including green:
    /// (minimum channel factor, maximum channel factor, spatial Jacobian norm).
    ///
    /// For F(u) = f(|u|)u the two Jacobian eigenvalues are f and f+r*f'.
    /// Piecewise-linear endpoints and polynomial critical points bound every
    /// interval; no sampled grid is used. The caller combines this bound with
    /// its distortion Jacobian/zoom and actual image dimensions to admit the
    /// shared RGB source window BEFORE declaring optional TCA executable.
    pub fn sampling_bounds(&self, distortion_applied: bool) -> Option<(f64, f64, f64)> {
        if distortion_applied && matches!(&self.model, TcaKind::Sony { distortion: None, .. }) {
            return None;
        }
        if self.is_identity() { return Some((1.0, 1.0, 1.0)); }
        let mut bounds = (1.0_f64, 1.0_f64, 1.0_f64);
        let channels = match &self.model {
            TcaKind::Sony { red, blue, distortion } if distortion_applied => {
                // s = r*D(r), C_source(s) = C(r(s)). Hence the radial
                // derivative is C + s*C'/[D+r*D']. Since s <= 1, this
                // conservative bound covers the inverse map, including all
                // source-reachable knots and its constant endpoint tails.
                let minimum_derivative = distortion.as_ref()?.factor.minimum_radial_derivative()?;
                let bound = |spline: &Spline| {
                    let minimum = spline.values.iter().copied().fold(f64::INFINITY, f64::min);
                    let maximum = spline.maximum();
                    let slope = spline.maximum_absolute_slope()?;
                    let jacobian = maximum + slope / minimum_derivative;
                    outward_bounds(minimum, maximum, jacobian, maximum, jacobian)
                };
                [bound(red)?, bound(blue)?]
            }
            TcaKind::Sony { red, blue, .. } | TcaKind::Fuji { red, blue } => {
                [red.sampling_bounds()?, blue.sampling_bounds()?]
            }
            TcaKind::Olympus { coefficients } => {
                let bound = |i: usize| {
                    let f = [1.0 + coefficients[i], coefficients[i + 1], coefficients[i + 2]];
                    let radial = [f[0], 3.0 * f[1], 5.0 * f[2]];
                    let (minimum, maximum) = quadratic_range(f, 0.0, 1.0)?;
                    let (radial_minimum, radial_maximum) = quadratic_range(radial, 0.0, 1.0)?;
                    let jacobian = maximum.max(radial_minimum.abs()).max(radial_maximum.abs());
                    outward_bounds(minimum, maximum, jacobian,
                        f.iter().map(|v| v.abs()).sum(), radial.iter().map(|v| v.abs()).sum())
                };
                [bound(0)?, bound(3)?]
            }
        };
        for channel in channels {
            bounds.0 = bounds.0.min(channel.0);
            bounds.1 = bounds.1.max(channel.1);
            bounds.2 = bounds.2.max(channel.2);
        }
        Some(bounds)
    }

    pub fn is_identity(&self) -> bool {
        match &self.model {
            TcaKind::Sony { red, blue, .. } | TcaKind::Fuji { red, blue } => red.is_identity() && blue.is_identity(),
            TcaKind::Olympus { coefficients } => coefficients.iter().all(|v| *v == 0.0),
        }
    }

    /// TCA alone. For a position already transformed by embedded distortion,
    /// use `source_position_after_distortion` with its explicit flag instead.
    pub fn source_position(&self, width: usize, height: usize, x: f64, y: f64, channel: usize) -> Result<[f64; 2]> {
        self.source_position_after_distortion(width, height, x, y, channel, false)
    }

    pub fn source_position_after_distortion(
        &self, width: usize, height: usize, x: f64, y: f64,
        channel: usize, distortion_applied: bool,
    ) -> Result<[f64; 2]> {
        let (center, offset, radius) = radial_position(width, height, x, y)?;
        // The caller's autofill ensures this domain. Polynomial extrapolation
        // outside the validated image footprint would not have a safe bound.
        if channel > 2 || radius > 1.0 + 1e-9 {
            return Err(err("INVALID_INPUT", "embedded_tca_geometry"));
        }
        if channel == 1 || radius == 0.0 { return Ok([x, y]); }
        let radius = radius.min(1.0);
        let factor = match &self.model {
            TcaKind::Sony { red, blue, distortion } => {
                let evaluation_radius = if distortion_applied {
                    distortion.as_ref().and_then(|map| map.inverse(radius))
                        .ok_or_else(|| err("INVALID_INPUT", "embedded_tca_distortion_domain"))?
                } else { radius };
                if channel == 0 { red.at(evaluation_radius) } else { blue.at(evaluation_radius) }
            }
            TcaKind::Fuji { red, blue } => {
                // Fuji CA is defined in SOURCE radius. The existing exact
                // inverse distortion has already found that radius for us.
                if channel == 0 { red.at(radius) } else { blue.at(radius) }
            }
            TcaKind::Olympus { coefficients } => {
                let i = if channel == 0 { 0 } else { 3 };
                let t = radius * radius;
                1.0 + coefficients[i] + t * (coefficients[i + 1] + t * coefficients[i + 2])
            }
        };
        let result = [center[0] + offset[0] * factor, center[1] + offset[1] * factor];
        if !within(factor, MIN_TCA_FACTOR, MAX_TCA_FACTOR) || result.iter().any(|v| !v.is_finite()) {
            return Err(err("INVALID_INPUT", "embedded_tca_geometry"));
        }
        // No coordinate clamp here: it would hide a composition/autofill bug.
        Ok(result)
    }
}

impl VignetteModel {
    pub fn reference_uses_crop(&self) -> bool { self.crop_reference }
    pub fn is_identity(&self) -> bool { self.gains.is_identity() }

    /// Source-image gain, before TCA/distortion resampling. Finite source
    /// coordinates may lie outside the reference crop (active-area padding).
    /// Spline tails are constant, so no unbounded extrapolation is introduced.
    pub fn source_gain(&self, width: usize, height: usize, x: f64, y: f64) -> Result<f64> {
        let (_, _, radius) = radial_position(width, height, x, y)?;
        let gain = self.gains.at(radius);
        if within(gain, MIN_GAIN, MAX_GAIN) { Ok(gain) }
        else { Err(err("INVALID_INPUT", "embedded_vignette_geometry")) }
    }
}

fn radial_position(width: usize, height: usize, x: f64, y: f64) -> Result<([f64; 2], [f64; 2], f64)> {
    if width == 0 || height == 0 || !x.is_finite() || !y.is_finite() {
        return Err(err("INVALID_INPUT", "embedded_optics_geometry"));
    }
    let center = [width as f64 * 0.5, height as f64 * 0.5];
    let offset = [x - center[0], y - center[1]];
    let radius = offset[0].hypot(offset[1]) / center[0].hypot(center[1]);
    if !radius.is_finite() { return Err(err("INVALID_INPUT", "embedded_optics_geometry")); }
    Ok((center, offset, radius))
}

fn make_tca(metadata: &EmbeddedTca) -> Option<TcaModel> {
    let (model, maximum) = match metadata {
        EmbeddedTca::Sony { red, blue, distortion } => {
            if red.len() != blue.len() || !(2..=16).contains(&red.len()) { return None; }
            let knots = sony_knots(red.len());
            let red = Spline::new(knots.clone(), red.iter().map(|v| 1.0 + *v as f64 / 2097152.0).collect(), MIN_TCA_FACTOR, MAX_TCA_FACTOR)?;
            let blue = Spline::new(knots, blue.iter().map(|v| 1.0 + *v as f64 / 2097152.0).collect(), MIN_TCA_FACTOR, MAX_TCA_FACTOR)?;
            if !red.radial_monotone() || !blue.radial_monotone() { return None; }
            let distortion = match distortion {
                None => None,
                Some(samples) => {
                    if !(2..=16).contains(&samples.len()) { return None; }
                    // If the independently validated distortion is unsupported,
                    // the pipeline will not apply it. Preserve valid TCA alone
                    // instead of letting bad distortion erase a separate item.
                    let distortion_status = crate::embedded_lens::from_metadata(
                        &EmbeddedLensDistortion::Sony { samples: samples.clone() });
                    if matches!(distortion_status, crate::embedded_lens::LensStatus::Ready(_)) {
                        let factor = Spline::new(sony_knots(samples.len()), samples.iter().map(|v| 1.0 + *v as f64 / 16384.0).collect(), 0.125, 8.0)?;
                        let map = ForwardRadius::new(factor)?;
                        if !product_radial_monotone(&map.factor, &red) || !product_radial_monotone(&map.factor, &blue) { return None; }
                        Some(map)
                    } else { None }
                }
            };
            let maximum = red.maximum().max(blue.maximum()).max(1.0);
            (TcaKind::Sony { red, blue, distortion }, maximum)
        }
        EmbeddedTca::Fuji { knots, red, blue, crop_factor } => {
            if red.len() != knots.len() || blue.len() != knots.len() { return None; }
            let (source_knots, red) = fuji_nodes(knots, red, *crop_factor, |v| 1.0 + v)?;
            let (_, blue) = fuji_nodes(knots, blue, *crop_factor, |v| 1.0 + v)?;
            let red = Spline::new(source_knots.clone(), red, MIN_TCA_FACTOR, MAX_TCA_FACTOR)?;
            let blue = Spline::new(source_knots, blue, MIN_TCA_FACTOR, MAX_TCA_FACTOR)?;
            if !red.radial_monotone() || !blue.radial_monotone() { return None; }
            let maximum = red.maximum().max(blue.maximum()).max(1.0);
            (TcaKind::Fuji { red, blue }, maximum)
        }
        EmbeddedTca::Olympus { coefficients } => {
            if coefficients.iter().any(|v| !v.is_finite()) { return None; }
            let mut maximum: f64 = 1.0;
            for i in [0, 3] {
                let p = [1.0 + coefficients[i], coefficients[i + 1], coefficients[i + 2]];
                let (min, max) = quadratic_range(p, 0.0, 1.0)?;
                let (min_derivative, _) = quadratic_range([p[0], 3.0 * p[1], 5.0 * p[2]], 0.0, 1.0)?;
                if !within(min, MIN_TCA_FACTOR, MAX_TCA_FACTOR) || !within(max, MIN_TCA_FACTOR, MAX_TCA_FACTOR)
                    || min_derivative <= MIN_DERIVATIVE { return None; }
                maximum = maximum.max(max);
            }
            (TcaKind::Olympus { coefficients: *coefficients }, maximum)
        }
    };
    Some(TcaModel { model, zoom: maximum })
}

fn make_vignette(metadata: &EmbeddedVignette) -> Option<VignetteModel> {
    let (knots, gains, crop_reference) = match metadata {
        EmbeddedVignette::Sony { samples } => {
            if !(2..=16).contains(&samples.len()) { return None; }
            let gains = samples.iter().map(|v| (2.0_f64).powf((2.0_f64).powf(*v as f64 / 8192.0 - 1.0) - 0.5)).collect();
            (sony_knots(samples.len()), gains, false)
        }
        EmbeddedVignette::Fuji { knots, levels, crop_factor } => {
            let (knots, gains) = fuji_nodes(knots, levels, *crop_factor, |v| 100.0 / v)?;
            (knots, gains, true)
        }
    };
    Some(VignetteModel { gains: Spline::new(knots, gains, MIN_GAIN, MAX_GAIN)?, crop_reference })
}

fn sony_knots(count: usize) -> Vec<f64> {
    (0..count).map(|i| (i as f64 + 0.5) / (count - 1) as f64).collect()
}

fn fuji_nodes(knots: &[f64], values: &[f64], crop_factor: f64, convert: impl Fn(f64) -> f64) -> Option<(Vec<f64>, Vec<f64>)> {
    if ![9, 11].contains(&knots.len()) || values.len() != knots.len() || ![1.0, 1.25].contains(&crop_factor) { return None; }
    let mut out_knots = Vec::with_capacity(knots.len() + 1);
    let mut out_values = Vec::with_capacity(knots.len() + 1);
    if knots[0] > 0.0 { out_knots.push(0.0); out_values.push(1.0); }
    out_knots.extend(knots.iter().map(|v| v * crop_factor));
    out_values.extend(values.iter().map(|v| convert(*v)));
    Some((out_knots, out_values))
}

fn within(value: f64, minimum: f64, maximum: f64) -> bool {
    value.is_finite() && (minimum..=maximum).contains(&value)
}

/// Small outward allowance for the endpoint/critical-point arithmetic. The
/// derivative scale includes absolute terms, not just a cancelled result.
fn outward_bounds(minimum: f64, maximum: f64, jacobian: f64,
    factor_scale: f64, derivative_scale: f64) -> Option<(f64, f64, f64)> {
    if [minimum, maximum, jacobian, factor_scale, derivative_scale].iter().any(|v| !v.is_finite()) {
        return None;
    }
    let factor_slack = 64.0 * f64::EPSILON * (1.0 + factor_scale.abs());
    let derivative_slack = 64.0 * f64::EPSILON * (1.0 + derivative_scale.abs());
    let bounds = (minimum - factor_slack, maximum + factor_slack, jacobian + derivative_slack);
    (bounds.0 > 0.0 && bounds.1 >= bounds.0 && bounds.2 > 0.0
        && bounds.2.is_finite()).then_some(bounds)
}

impl Spline {
    fn new(knots: Vec<f64>, values: Vec<f64>, minimum: f64, maximum: f64) -> Option<Self> {
        if knots.len() != values.len() || !(2..=17).contains(&knots.len())
            || knots.iter().any(|v| !within(*v, 0.0, MAX_KNOT_RADIUS))
            || knots.windows(2).any(|pair| pair[0] >= pair[1])
            || values.iter().any(|v| !within(*v, minimum, maximum)) { return None; }
        Some(Self { knots, values })
    }

    fn is_identity(&self) -> bool { self.values.iter().all(|v| *v == 1.0) }
    fn maximum(&self) -> f64 { self.values.iter().copied().fold(f64::NEG_INFINITY, f64::max) }

    fn at(&self, radius: f64) -> f64 {
        let upper = self.knots.partition_point(|v| *v < radius);
        if upper == 0 { self.values[0] }
        else if upper == self.knots.len() { self.values[upper - 1] }
        else {
            let t = (radius - self.knots[upper - 1]) / (self.knots[upper] - self.knots[upper - 1]);
            self.values[upper - 1] + t * (self.values[upper] - self.values[upper - 1])
        }
    }

    fn line_at(&self, radius: f64) -> (f64, f64) {
        let upper = self.knots.partition_point(|v| *v < radius);
        if upper == 0 { (0.0, self.values[0]) }
        else if upper == self.knots.len() { (0.0, self.values[upper - 1]) }
        else {
            let a = (self.values[upper] - self.values[upper - 1]) / (self.knots[upper] - self.knots[upper - 1]);
            (a, self.values[upper - 1] - a * self.knots[upper - 1])
        }
    }

    fn radial_monotone(&self) -> bool {
        (0..self.knots.len() - 1).all(|i| {
            // Use explicit segment indices: the midpoint of two adjacent
            // f64 knots can round to an endpoint and select the wrong segment.
            let slope = (self.values[i + 1] - self.values[i]) / (self.knots[i + 1] - self.knots[i]);
            [self.values[i] + self.knots[i] * slope, self.values[i + 1] + self.knots[i + 1] * slope]
                .iter().all(|v| v.is_finite() && *v > MIN_DERIVATIVE)
        })
    }

    fn sampling_bounds(&self) -> Option<(f64, f64, f64)> {
        let endpoints = [self.at(0.0), self.at(1.0)];
        let mut minimum = endpoints[0].min(endpoints[1]);
        let mut maximum = endpoints[0].max(endpoints[1]);
        let mut jacobian = maximum;
        let mut derivative_scale = maximum;
        for i in 0..self.knots.len() - 1 {
            let left = self.knots[i].max(0.0);
            let right = self.knots[i + 1].min(1.0);
            if left > right { continue; }
            let slope = (self.values[i + 1] - self.values[i]) / (self.knots[i + 1] - self.knots[i]);
            for radius in [left, right] {
                let factor = self.at(radius);
                let radial = factor + radius * slope;
                if !slope.is_finite() || !factor.is_finite() || !radial.is_finite() { return None; }
                minimum = minimum.min(factor);
                maximum = maximum.max(factor);
                jacobian = jacobian.max(factor.abs()).max(radial.abs());
                derivative_scale = derivative_scale.max(factor.abs() + (radius * slope).abs());
            }
        }
        outward_bounds(minimum, maximum, jacobian, maximum, derivative_scale)
    }

    /// Global minimum of d[r*D(r)]/dr, including both constant tails. Sony's
    /// source-to-output inverse may reach r > 1, so limiting this to [0,1]
    /// would not establish the derivative bound used by source-space TCA.
    fn minimum_radial_derivative(&self) -> Option<f64> {
        let mut minimum = self.values[0].min(*self.values.last()?);
        let mut arithmetic_scale = minimum.abs();
        for i in 0..self.knots.len() - 1 {
            let slope = (self.values[i + 1] - self.values[i]) / (self.knots[i + 1] - self.knots[i]);
            for j in [i, i + 1] {
                let product = self.knots[j] * slope;
                let value = self.values[j] + product;
                if !value.is_finite() { return None; }
                minimum = minimum.min(value);
                arithmetic_scale = arithmetic_scale.max(self.values[j].abs() + product.abs());
            }
        }
        // Lower the denominator before division, especially when cancellation
        // leaves a derivative close to the supported monotonicity threshold.
        let lower = minimum - 64.0 * f64::EPSILON * (1.0 + arithmetic_scale);
        (lower.is_finite() && lower > 0.0).then_some(lower)
    }

    fn maximum_absolute_slope(&self) -> Option<f64> {
        let mut maximum: f64 = 0.0;
        for i in 0..self.knots.len() - 1 {
            let slope = ((self.values[i + 1] - self.values[i]) / (self.knots[i + 1] - self.knots[i])).abs();
            if !slope.is_finite() { return None; }
            maximum = maximum.max(slope);
        }
        if maximum == 0.0 { Some(0.0) }
        else { Some(maximum + 64.0 * f64::EPSILON * (1.0 + maximum)) }
    }
}

impl ForwardRadius {
    fn new(factor: Spline) -> Option<Self> {
        if !factor.radial_monotone() { return None; }
        let source_knots: Vec<_> = factor.knots.iter().zip(&factor.values).map(|(r, f)| r * f).collect();
        if source_knots.iter().any(|v| !v.is_finite()) || source_knots.windows(2).any(|v| v[0] >= v[1]) { return None; }
        Some(Self { factor, source_knots })
    }

    fn inverse(&self, source: f64) -> Option<f64> {
        let upper = self.source_knots.partition_point(|v| *v < source);
        if upper == 0 { return Some(source / self.factor.values[0]); }
        if upper == self.source_knots.len() { return Some(source / self.factor.values[upper - 1]); }
        let left = self.factor.knots[upper - 1];
        let right = self.factor.knots[upper];
        let (a, b) = self.factor.line_at((left + right) * 0.5);
        let radius = if a == 0.0 { source / b } else {
            // Solve a*r*r + b*r = source, choosing the increasing branch.
            // Select the algebraic form that avoids cancellation.
            let discriminant = b * b + 4.0 * a * source;
            if !discriminant.is_finite() || discriminant < 0.0 { return None; }
            let root = discriminant.sqrt();
            if b >= 0.0 { 2.0 * source / (b + root) } else { (root - b) / (2.0 * a) }
        };
        (radius.is_finite() && radius >= left - 1e-10 && radius <= right + 1e-10)
            .then(|| radius.clamp(left, right))
    }
}

/// The product's radial derivative is a quadratic on each shared interval.
/// Checking its true extrema also excludes folds between the metadata knots.
fn product_radial_monotone(first: &Spline, second: &Spline) -> bool {
    let mut knots = vec![0.0];
    knots.extend(&first.knots);
    knots.extend(&second.knots);
    knots.sort_by(f64::total_cmp);
    knots.dedup();
    knots.windows(2).all(|pair| {
        let mid = (pair[0] + pair[1]) * 0.5;
        let (a, b) = first.line_at(mid);
        let (c, d) = second.line_at(mid);
        quadratic_range([b * d, 2.0 * (a * d + b * c), 3.0 * a * c], pair[0], pair[1])
            .is_some_and(|(minimum, _)| minimum > MIN_DERIVATIVE)
    })
}

fn quadratic_range(c: [f64; 3], left: f64, right: f64) -> Option<(f64, f64)> {
    if c.iter().any(|v| !v.is_finite()) { return None; }
    let evaluate = |x: f64| c[0] + x * (c[1] + x * c[2]);
    let a = evaluate(left);
    let b = evaluate(right);
    if !a.is_finite() || !b.is_finite() { return None; }
    let mut minimum = a.min(b);
    let mut maximum = a.max(b);
    if c[2] != 0.0 {
        let vertex = -c[1] / (2.0 * c[2]);
        if vertex > left && vertex < right {
            let value = evaluate(vertex);
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

    fn tca(metadata: EmbeddedTca) -> TcaModel { make_tca(&metadata).expect("valid TCA fixture") }
    fn vignette(metadata: EmbeddedVignette) -> VignetteModel { make_vignette(&metadata).expect("valid vignette fixture") }

    #[test]
    fn identity_and_availability_are_separate() {
        let result = from_metadata(&EmbeddedLensCorrections {
            tca: EmbeddedOpticsState::Ready(EmbeddedTca::Olympus { coefficients: [0.0; 6] }),
            vignette: EmbeddedOpticsState::Missing,
        });
        assert!(result.has_ready());
        assert!(!result.has_active());
        assert!(result.reference_uses_crop());
        assert_eq!(LensExtras::default(), LensExtras { tca: State::Missing, vignette: State::Missing });
        let metadata = EmbeddedLensCorrections {
            tca: EmbeddedOpticsState::Unsupported,
            vignette: EmbeddedOpticsState::Ready(EmbeddedVignette::Sony { samples: vec![0; 11] }),
        };
        let result = from_metadata(&metadata);
        assert!(result.has_ready());
        assert!(!result.has_active());
        assert_eq!(result.tca, State::Unsupported);
    }

    #[test]
    fn sony_recovers_output_radius_only_when_distortion_was_applied() {
        let model = tca(EmbeddedTca::Sony { red: vec![0, 2000, -2000], blue: vec![1000; 3], distortion: Some(vec![2000, 1000, 0]) });
        let center = [600.0_f64, 400.0_f64];
        let x = 1000.0;
        let r = (x - center[0]) / center[0].hypot(center[1]);
        let d = 1.0 + 2000.0 / 16384.0 + ((r - 0.25) / 0.5) * (-1000.0 / 16384.0);
        let expected_ca = 1.0 + ((r - 0.25) / 0.5) * (2000.0 / 2097152.0);
        let source = center[0] + (x - center[0]) * d;
        let composed = model.source_position_after_distortion(1200, 800, source, 400.0, 0, true).unwrap();
        assert!((composed[0] - (center[0] + (source - center[0]) * expected_ca)).abs() < 1e-9);
        let uncomposed = model.source_position(1200, 800, source, 400.0, 0).unwrap();
        assert!((uncomposed[0] - composed[0]).abs() > 0.01);
        assert_eq!(model.source_position_after_distortion(1200, 800, source, 400.0, 1, true).unwrap(), [source, 400.0]);
    }

    #[test]
    fn fuji_tca_consumes_source_radius_and_preserves_green() {
        let knots: Vec<_> = (0..11).map(|i| i as f64 / 10.0).collect();
        let model = tca(EmbeddedTca::Fuji { knots: knots.clone(), red: knots.iter().map(|v| v * 0.001).collect(), blue: vec![-0.001; 11], crop_factor: 1.0 });
        let source = [900.0, 600.0];
        let r = (300.0_f64).hypot(200.0) / (600.0_f64).hypot(400.0);
        let expected = [600.0 + 300.0 * (1.0 + r * 0.001), 400.0 + 200.0 * (1.0 + r * 0.001)];
        let actual = model.source_position_after_distortion(1200, 800, source[0], source[1], 0, true).unwrap();
        assert!((actual[0] - expected[0]).abs() < 1e-10);
        assert!((actual[1] - expected[1]).abs() < 1e-10);
        assert_eq!(model.source_position(1200, 800, source[0], source[1], 1).unwrap(), source);
    }

    #[test]
    fn gains_use_source_coordinates_constant_tails_and_no_silent_clipping() {
        let model = vignette(EmbeddedVignette::Fuji { knots: (0..11).map(|i| i as f64 / 10.0).collect(), levels: vec![50.0; 11], crop_factor: 1.0 });
        for point in [[600.0, 400.0], [0.0, 0.0], [-24.0, -16.0], [1600.0, 1200.0]] {
            assert_eq!(model.source_gain(1200, 800, point[0], point[1]).unwrap(), 2.0);
        }
        assert!(!model.is_identity());
        let sony = vignette(EmbeddedVignette::Sony { samples: vec![8192; 16] });
        assert!((sony.source_gain(1200, 800, 0.0, 0.0).unwrap() - 2.0_f64.sqrt()).abs() < 1e-12);
        assert!(sony.source_gain(0, 800, 0.0, 0.0).is_err());
        assert!(sony.source_gain(1200, 800, f64::INFINITY, 0.0).is_err());
    }

    #[test]
    fn olympus_continuous_bounds_include_internal_extrema_and_folds() {
        // Maximum of 1 + .1*t - .1*t^2 is internal at t=.5.
        let model = tca(EmbeddedTca::Olympus { coefficients: [0.0, 0.1, -0.1, 0.0, 0.0, 0.0] });
        assert!((model.autofill_zoom() - 1.025).abs() < 1e-12);
        // End factors are positive, but the radial derivative folds inside.
        assert!(make_tca(&EmbeddedTca::Olympus { coefficients: [0.5, -1.5, 0.63, 0.0, 0.0, 0.0] }).is_none());
    }

    #[test]
    fn bad_shapes_domains_and_nonfinite_values_are_unsupported() {
        for metadata in [
            EmbeddedTca::Sony { red: vec![0; 17], blue: vec![0; 17], distortion: None },
            EmbeddedTca::Sony { red: vec![0; 3], blue: vec![0; 2], distortion: None },
            EmbeddedTca::Fuji { knots: vec![0.0; 9], red: vec![0.0; 9], blue: vec![0.0; 9], crop_factor: 1.0 },
            EmbeddedTca::Olympus { coefficients: [f64::NAN; 6] },
        ] { assert!(make_tca(&metadata).is_none()); }
        for levels in [vec![0.0; 11], vec![f64::NAN; 11], vec![-50.0; 11], vec![0.001; 11]] {
            assert!(make_vignette(&EmbeddedVignette::Fuji { knots: (0..11).map(|i| i as f64 / 10.0).collect(), levels, crop_factor: 1.0 }).is_none());
        }
        assert!(make_vignette(&EmbeddedVignette::Sony { samples: vec![i16::MAX; 16] }).is_none());
        let model = tca(EmbeddedTca::Olympus { coefficients: [0.0; 6] });
        assert!(model.source_position(1200, 800, 600.0, 400.0, 3).is_err());
        assert!(model.source_position(1200, 800, -10000.0, 0.0, 0).is_err());
    }

    #[test]
    fn numerically_unsupported_distortion_does_not_erase_valid_sony_tca() {
        let model = tca(EmbeddedTca::Sony { red: vec![100; 3], blue: vec![-100; 3], distortion: Some(vec![0, -14000]) });
        let standalone = model.source_position(1200, 800, 1000.0, 400.0, 0).unwrap();
        assert!(standalone[0] > 1000.0);
        assert!(model.source_position_after_distortion(1200, 800, 1000.0, 400.0, 0, true).is_err());
        assert!(model.sampling_bounds(true).is_none());
        assert!(model.sampling_bounds(false).is_some());
    }

    #[test]
    fn sampling_bounds_of_fuji_linear_fields_are_analytic_and_crop_aware() {
        let knots: Vec<_> = (0..11).map(|i| i as f64 / 10.0).collect();
        for (crop_factor, expected) in [(1.0, (0.95, 1.1, 1.2)), (1.25, (0.96, 1.08, 1.16))] {
            let model = tca(EmbeddedTca::Fuji {
                knots: knots.clone(), red: knots.iter().map(|r| 0.1 * r).collect(),
                blue: knots.iter().map(|r| -0.05 * r).collect(), crop_factor,
            });
            let bounds = model.sampling_bounds(true).unwrap();
            assert!((bounds.0 - expected.0).abs() < 1e-12);
            assert!((bounds.1 - expected.1).abs() < 1e-12);
            assert!((bounds.2 - expected.2).abs() < 1e-12);
            assert_eq!(bounds, model.sampling_bounds(false).unwrap());
        }
    }

    #[test]
    fn sony_inverse_sampling_bound_covers_the_inverse_domain_and_constant_tails() {
        let model = tca(EmbeddedTca::Sony { red: vec![0, 1000, 2000], blue: vec![-1000; 3], distortion: Some(vec![-4096; 3]) });
        let a = 1000.0 / 2097152.0;
        let direct = model.sampling_bounds(false).unwrap();
        assert!((direct.0 - (1.0 - a)).abs() < 1e-12);
        assert!((direct.1 - (1.0 + 1.5 * a)).abs() < 1e-12);
        assert!((direct.2 - (1.0 + 3.5 * a)).abs() < 1e-12);
        let inverse = model.sampling_bounds(true).unwrap();
        assert!((inverse.0 - (1.0 - a)).abs() < 1e-12);
        assert!((inverse.1 - (1.0 + 2.0 * a)).abs() < 1e-12);
        // C'(r)=2a and d[rD]/dr=.75. s<=1 gives the stated safe upper bound.
        assert!((inverse.2 - (1.0 + 2.0 * a + 2.0 * a / 0.75)).abs() < 1e-12);
        let factor = Spline::new(vec![0.25, 0.75, 1.25], vec![1.1, 1.0, 0.9], 0.125, 8.0).unwrap();
        // The minimum occurs beyond output radius 1. Restricting the inverse
        // derivative calculation to [0,1] would incorrectly give .75, not .65.
        let lower = factor.minimum_radial_derivative().unwrap();
        assert!(lower <= 0.65 && (lower - 0.65).abs() < 1e-12);
    }

    #[test]
    fn olympus_sampling_jacobian_includes_internal_polynomial_extrema() {
        let model = tca(EmbeddedTca::Olympus { coefficients: [0.0, 0.1, -0.1, 0.0, 0.0, 0.0] });
        let (minimum, maximum, jacobian) = model.sampling_bounds(true).unwrap();
        // f(t)=1+.1t-.1t² peaks at t=.5. The radial derivative
        // 1+.3t-.5t² peaks at t=.3; neither maximum is an endpoint.
        assert!((minimum - 1.0).abs() < 1e-12);
        assert!((maximum - 1.025).abs() < 1e-12);
        assert!((jacobian - 1.045).abs() < 1e-12);
        let identity = tca(EmbeddedTca::Olympus { coefficients: [0.0; 6] });
        assert_eq!(identity.sampling_bounds(true), Some((1.0, 1.0, 1.0)));
    }

    #[test]
    fn sampling_bound_exposes_extreme_rgb_window_before_execution() {
        let extreme = tca(EmbeddedTca::Olympus { coefficients: [1.0, 0.0, 0.0, -0.5, 0.0, 0.0] });
        let real = tca(EmbeddedTca::Olympus { coefficients: [
            2.63929323409684e-05, 3.147125244140625e-05, 8.184909529518336e-05,
            0.00028548241243697703, -0.00017845630645751953, -0.0001784801424946636,
        ] });
        let footprint = |model: &TcaModel| {
            let (minimum, maximum, jacobian) = model.sampling_bounds(false).unwrap();
            // Identity distortion for this independent execution-budget case.
            let span = jacobian / model.autofill_zoom() * 127.0_f64.hypot(31.0);
            let width = (span + 2592.0 * (maximum - minimum)).ceil() as u64 + 5;
            let height = (span + 1944.0 * (maximum - minimum)).ceil() as u64 + 5;
            width.checked_mul(height).unwrap().checked_mul(12).unwrap()
        };
        assert!(footprint(&extreme) > 8 * 1024 * 1024);
        assert!(footprint(&real) < 8 * 1024 * 1024);
    }

    #[test]
    fn continuous_bounds_do_not_skip_adjacent_floating_point_knots() {
        let knot = 0.5_f64;
        let next = knot.next_up();
        assert_eq!((knot + next) * 0.5, knot);
        let steep = Spline::new(vec![0.0, knot, next, 1.0], vec![1.0, 1.0, 1.01, 1.01], 0.5, 2.0).unwrap();
        assert!(steep.radial_monotone());
        assert!(steep.sampling_bounds().unwrap().2 > 1e12);
        let folding = Spline::new(vec![0.0, knot, next, 1.0], vec![1.0, 1.01, 1.0, 1.0], 0.5, 2.0).unwrap();
        assert!(!folding.radial_monotone());
    }
}
