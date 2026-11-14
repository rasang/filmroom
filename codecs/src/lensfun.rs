//! Offline, full-library lens calibration for original camera RAW development.
//! Matching and per-operation source selection are in the data-driven registry
//! and selection modules. The RF16 constants below are regression fixtures.
use crate::{
    develop::Development,
    embedded_lens::LensStatus,
    error::{Result, err},
    lensfun_geometry::Geometry,
};

#[path = "lensfun_profiles.rs"]
mod profiles;

#[path = "lensfun_database.rs"]
pub(crate) mod database;

#[path = "lensfun_selection.rs"]
mod selection;
pub use selection::ProfileSelection;
pub(crate) use selection::configure_capture;

#[path = "lensfun_optics.rs"]
pub mod optics;
use optics::{TcaCorrection, VignettingCorrection};

/// Bump this policy whenever the selected coefficients, reference domain,
/// interpolation or resampling policy changes. Android persists it with the
/// linear spool and rebuilds caches from an older development policy.
pub(crate) const DEVELOPMENT_POLICY: &str = "camera-matrix-linear-srgb-lensfun-v3";

#[derive(Clone, Debug, PartialEq)]
pub struct LensfunCorrection {
    profile_id: &'static str,
    geometry: Geometry,
    tca: Option<TcaCorrection>,
    vignetting: Option<VignettingCorrection>,
}

impl LensfunCorrection {
    pub fn profile_id(&self) -> &'static str { self.profile_id }

    pub fn dimensions(&self) -> [usize; 2] { self.geometry.dimensions() }

    pub fn source_position(&self, width: usize, height: usize, x: usize, y: usize) -> Result<[f64; 2]> {
        if self.dimensions() != [width, height] {
            return Err(err("INVALID_INPUT", "lensfun_geometry"));
        }
        self.geometry.source_position(x, y)
    }

    pub fn autofill_zoom(&self) -> f64 { self.geometry.autofill_zoom() }

    pub fn has_tca(&self) -> bool { self.tca.is_some() }
    pub fn has_vignetting(&self) -> bool { self.vignetting.is_some() }
    // The qualified RF16 record contains both calibrations. Missing/unsafe
    // capture parameters or a failed model admission is a per-operation skip.
    pub fn tca_not_applied(&self) -> bool { self.tca.is_none() }
    pub fn vignetting_not_applied(&self) -> bool { self.vignetting.is_none() }

    pub fn source_position_channel(&self, width: usize, height: usize, x: usize, y: usize,
        channel: usize) -> Result<[f64; 2]> {
        self.source_position_for_correction(width, height, x, y, channel, self.has_tca(), 1.0)
    }

    pub fn source_position_for_correction(&self, width: usize, height: usize, x: usize, y: usize,
        channel: usize, apply_profile_tca: bool, extra_zoom: f64) -> Result<[f64; 2]> {
        if self.dimensions() != [width, height] || channel >= 3 {
            return Err(err("INVALID_INPUT", "lensfun_geometry"));
        }
        if apply_profile_tca {
            self.tca.as_ref().ok_or_else(|| err("INVALID_INPUT", "lensfun_tca"))?
                .source_position(x, y, channel, extra_zoom)
        } else {
            self.geometry.source_position_with_zoom(x, y, extra_zoom)
        }
    }

    pub fn source_gain(&self, width: usize, height: usize, x: f64, y: f64) -> Result<f64> {
        if self.dimensions() != [width, height] { return Err(err("INVALID_INPUT", "lensfun_vignetting")); }
        self.vignetting.as_ref().ok_or_else(|| err("INVALID_INPUT", "lensfun_vignetting"))?.source_gain(x, y)
    }

    pub fn corrected_autofill_zoom(&self) -> f64 {
        self.tca.as_ref().map_or_else(|| self.geometry.autofill_zoom(), TcaCorrection::autofill_zoom)
    }
}

#[cfg(test)]
pub(crate) fn rf16_for_test(width: usize, height: usize) -> LensfunCorrection {
    LensfunCorrection { profile_id: "canon-eos-r-rf16-v1",
        geometry: Geometry::new(&profiles::RF16_CALIBRATION,
            profiles::RF16_CAMERA_CROP, 16.0, width, height).unwrap(), tca: None, vignetting: None }
}

#[cfg(test)]
pub(crate) fn rf16_with_optics_for_test(width: usize, height: usize,
    aperture: Option<f64>, focus_range_m: Option<[f64; 2]>) -> LensfunCorrection {
    let geometry = Geometry::new(&profiles::RF16_CALIBRATION,
        profiles::RF16_CAMERA_CROP, 16.0, width, height).unwrap();
    let tca = TcaCorrection::new(&geometry, profiles::RF16_TCA, 16.0);
    let vignetting = aperture.zip(focus_range_m).and_then(|(aperture, range)| {
        VignettingCorrection::new(&geometry, &profiles::RF16_VIGNETTING,
            profiles::RF16_CALIBRATION.aspect_ratio, 16.0, aperture, range)
    });
    LensfunCorrection { profile_id: "canon-eos-r-rf16-v2", geometry, tca, vignetting }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reference_size_is_bound_to_the_selected_geometry() {
        let correction = rf16_for_test(6720,4480);
        assert_eq!(correction.profile_id(),"canon-eos-r-rf16-v1");
        assert!(correction.source_position(4480,6720,0,0).is_err());
        assert!(correction.source_position(6742,4498,0,0).is_err());
        assert!(correction.source_position(6720,4480,0,0).is_ok());
    }

    #[test]
    fn profile_operations_are_independent_and_legacy_geometry_is_unchanged() {
        let old = rf16_for_test(6720, 4480);
        let full = rf16_with_optics_for_test(6720, 4480, Some(2.8), Some([1.5, 1.99]));
        assert!(full.has_tca() && full.has_vignetting());
        assert!(!full.tca_not_applied() && !full.vignetting_not_applied());
        for (x, y) in [(0, 0), (3359, 2239), (6719, 4479)] {
            assert_eq!(full.source_position(6720, 4480, x, y).unwrap(), old.source_position(6720, 4480, x, y).unwrap());
            for channel in 0..3 {
                assert_eq!(full.source_position_for_correction(6720, 4480, x, y, channel, false, 1.0).unwrap(),
                    full.geometry.source_position_with_zoom(x, y, 1.0).unwrap());
                assert!(full.source_position_channel(6720, 4480, x, y, channel).is_ok());
            }
        }
        for (aperture, focus) in [(None, Some([1.5, 1.99])), (Some(2.8), None),
            (Some(1.0), Some([1.5, 1.99])), (Some(2.8), Some([2.0, 1.0]))] {
            let partial = rf16_with_optics_for_test(6720, 4480, aperture, focus);
            assert!(partial.has_tca() && !partial.has_vignetting());
            assert!(!partial.tca_not_applied() && partial.vignetting_not_applied());
            assert_eq!(partial.source_position_channel(6720, 4480, 0, 0, 0).unwrap(),
                full.source_position_channel(6720, 4480, 0, 0, 0).unwrap());
            assert!(partial.source_gain(6720, 4480, 0.0, 0.0).is_err());
        }
        assert!(full.source_gain(4480, 6720, 0.0, 0.0).is_err());
        assert!(full.source_position_channel(4480, 6720, 0, 0, 0).is_err());
    }
}
