//! Digital bridges used when a physical recording medium is absent.
//!
//! These are not substitute film/paper profiles. The negative bridge reads
//! normalized developed channel density into a positive digital signal. Its
//! fixed per-profile gray anchor avoids image-dependent normalization (and thus
//! avoids zoom/ROI brightness changes). An actual paper still uses its own
//! sensitometry and spectral scanner after the RGB-to-paper exposure bridge.

pub const REFERENCE_GRAY: f64 = 0.18;
pub const LOG_FLOOR: f64 = 1e-10;

#[derive(Debug, Clone, Copy)]
pub struct PositiveDensityBridge {
    pub minimum: [f64; 3],
    pub maximum: [f64; 3],
    pub gamma: [f64; 3],
}
impl PositiveDensityBridge {
    /// Fixed profile references, never a min/max measured from the user's image.
    pub fn calibrated(minimum: [f64; 3], maximum: [f64; 3], midgray: [f64; 3]) -> Result<Self, &'static str> {
        let mut gamma = [1.; 3];
        for c in 0..3 {
            if !minimum[c].is_finite() || !maximum[c].is_finite() || !midgray[c].is_finite()
                || maximum[c] <= minimum[c] || midgray[c] <= minimum[c] || midgray[c] >= maximum[c] {
                return Err("Invalid fixed negative density references");
            }
            let normalized = (midgray[c] - minimum[c]) / (maximum[c] - minimum[c]);
            gamma[c] = REFERENCE_GRAY.ln() / normalized.ln();
        }
        Ok(Self { minimum, maximum, gamma })
    }
    pub fn positive(self, density: [f64; 3]) -> [f64; 3] {
        std::array::from_fn(|c| {
            ((density[c] - self.minimum[c]) / (self.maximum[c] - self.minimum[c]))
                .clamp(0., 1.).powf(self.gamma[c])
        })
    }
}

/// A negative-working photographic paper becomes darker with more exposure.
/// Reciprocal exposure therefore presents a positive RGB source without making
/// it pass through an arbitrary film stock. The actual paper's fixed gray
/// exposure is calibrated separately; paper optics act on this linear light.
pub fn rgb_to_paper_log_exposure(rgb: [f64; 3], paper_gray_log_exposure: f64, exposure_ev: f64) -> [f64; 3] {
    std::array::from_fn(|c| {
        paper_gray_log_exposure + (REFERENCE_GRAY / rgb[c].max(LOG_FLOOR)).log10()
            + exposure_ev * std::f64::consts::LOG10_2
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn negative_bridge_is_positive_and_fixed_gray_anchored() {
        let b = PositiveDensityBridge::calibrated([0.1, 0.2, 0.3], [3.1, 3.2, 3.3], [1.1, 1.2, 1.3]).unwrap();
        assert_eq!(b.positive(b.minimum), [0.; 3]);
        assert_eq!(b.positive(b.maximum), [1.; 3]);
        for v in b.positive([1.1, 1.2, 1.3]) { assert!((v - REFERENCE_GRAY).abs() < 1e-14); }
        let mut previous = [0.; 3];
        for i in 0..1001 {
            let density = std::array::from_fn(|c| b.minimum[c] + (b.maximum[c] - b.minimum[c]) * i as f64 / 1000.);
            let out = b.positive(density);
            for c in 0..3 { assert!(out[c].is_finite() && out[c] >= previous[c]); }
            previous = out;
        }
    }
    #[test]
    fn invalid_calibration_cannot_turn_into_silent_default() {
        assert!(PositiveDensityBridge::calibrated([0.;3], [0.;3], [0.;3]).is_err());
        assert!(PositiveDensityBridge::calibrated([0.;3], [1.;3], [f64::NAN;3]).is_err());
        assert!(PositiveDensityBridge::calibrated([0.;3], [1.;3], [1.;3]).is_err());
    }
    #[test]
    fn paper_bridge_gray_and_polarity() {
        assert_eq!(rgb_to_paper_log_exposure([REFERENCE_GRAY;3], -1., 0.), [-1.;3]);
        let dark = rgb_to_paper_log_exposure([0.01;3], -1., 0.);
        let light = rgb_to_paper_log_exposure([0.9;3], -1., 0.);
        assert!(dark[0] > light[0]);
        assert!(rgb_to_paper_log_exposure([0.;3], -1., 0.).iter().all(|v|v.is_finite()));
    }
    #[test]
    fn paper_exposure_is_independent_of_scene_crop() {
        let sample = [0.11, 0.23, 0.57];
        let a = rgb_to_paper_log_exposure(sample, -0.7, 1.);
        let b = rgb_to_paper_log_exposure(sample, -0.7, 0.);
        for c in 0..3 { assert!((a[c] - b[c] - std::f64::consts::LOG10_2).abs() < 1e-14); }
    }
}
