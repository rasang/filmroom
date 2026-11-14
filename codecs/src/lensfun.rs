//! Optional database distortion for explicitly qualified original RAW layouts.
//!
//! The shared geometry code is independent of the opt-in registry. The registry
//! is intentionally narrower than the upstream database: a matching marketing
//! name alone does not establish sensor geometry, an uncorrected source, or a
//! validated lens identity. Missing/invalid metadata preserves ordinary decode.
use crate::{
    develop::Development,
    embedded_lens::LensStatus,
    error::{Result, err},
    lensfun_geometry::Geometry,
    lensfun_metadata::{CanonRf16Identity, canon_rf16_identity},
};
use std::fs::File;

#[path = "lensfun_profiles.rs"]
mod profiles;

#[path = "lensfun_optics.rs"]
pub mod optics;
use optics::{TcaCorrection, VignettingCorrection};

/// Bump this policy whenever the selected coefficients, reference domain,
/// interpolation or resampling policy changes. Android persists it with the
/// linear spool and rebuilds caches from an older development policy.
pub(crate) const DEVELOPMENT_POLICY: &str = "camera-matrix-linear-srgb-lensfun-v2";

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

/// Called ONLY by the bounded, original-CR3 Bayer adapter. In particular, the
/// DNG adapter's intentionally Missing vendor status is never a fallback cue.
pub(crate) fn configure_cr3(
    format: &str,
    make: &str,
    model: &str,
    d: &mut Development<'_>,
    file: &mut File,
    input_bytes: u64,
) {
    if !eligible_descriptor(format, make, model, d) { return; }
    let Some(identity) = canon_rf16_identity(file, input_bytes) else { return; };
    if identity.orientation != u16::from(d.orientation)
        || identity.make != make || identity.model != model
        || identity.exif_size != [d.crop.w as u32, d.crop.h as u32]
    { return; }
    let Some(correction) = select(&identity, d.crop.w, d.crop.h) else { return; };
    // The official-reference experiment uses the visible DefaultCrop domain,
    // not encoded sensor padding, the larger active rectangle or EXIF rotation.
    d.lens_reference = d.crop;
    d.lens = LensStatus::Profile(correction);
}

fn eligible_descriptor(format: &str, make: &str, model: &str, d: &Development<'_>) -> bool {
    format == "CR3"
        && make == "Canon" && model == "Canon EOS R"
        && d.cpp == 1 && d.cfa == "RGGB"
        && matches!(d.lens, LensStatus::Missing)
        && d.gain_maps.is_empty()
        && (1..=8).contains(&d.orientation)
        && [d.width, d.height] == [6888, 4546]
        && [d.active.x, d.active.y, d.active.w, d.active.h] == [144, 46, 6742, 4498]
        && [d.crop.x, d.crop.y, d.crop.w, d.crop.h] == [156, 58, 6720, 4480]
}

fn select(identity: &CanonRf16Identity, width: usize, height: usize) -> Option<LensfunCorrection> {
    // Identity is constructed only by the bounded metadata reader after
    // cross-checking camera, EXIF and independent Canon RF lens identifiers.
    let geometry = Geometry::new(&profiles::RF16_CALIBRATION,
        profiles::RF16_CAMERA_CROP, identity.focal_length_mm, width, height)?;
    let tca = TcaCorrection::new(&geometry, profiles::RF16_TCA, identity.focal_length_mm);
    let vignetting = identity.aperture.zip(identity.focus_range_m).and_then(|(aperture, range)| {
        VignettingCorrection::new(&geometry, &profiles::RF16_VIGNETTING,
            profiles::RF16_CALIBRATION.aspect_ratio, identity.focal_length_mm, aperture, range)
    });
    Some(LensfunCorrection { profile_id: "canon-eos-r-rf16-v2", geometry, tca, vignetting })
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
    use crate::dng::Rect;
    use rawler::rawimage::EmbeddedLensDistortion;

    fn descriptor() -> Development<'static> {
        Development {
            width: 6888, height: 4546, cpp: 1, orientation: 1,
            active: Rect { x: 144, y: 46, w: 6742, h: 4498 },
            crop: Rect { x: 156, y: 58, w: 6720, h: 4480 },
            lens_reference: Rect { x: 144, y: 46, w: 6742, h: 4498 },
            cfa: "RGGB".into(), wb: [1.0;4], matrix: vec![], baseline_exposure_ev: 0.0,
            gain_maps: &[], lens: LensStatus::Missing,
            lens_extras: Default::default(),
        }
    }

    #[test]
    fn embedded_ready_identity_unsupported_and_existing_profile_all_block_fallback() {
        let mut d = descriptor();
        assert!(eligible_descriptor("CR3", "Canon", "Canon EOS R", &d));
        for status in [
            LensStatus::Unsupported,
            crate::embedded_lens::from_metadata(&EmbeddedLensDistortion::Sony { samples: vec![0;11] }),
            crate::embedded_lens::from_metadata(&EmbeddedLensDistortion::Sony { samples: vec![100;11] }),
            LensStatus::Profile(rf16_for_test(6720,4480)),
        ] {
            d.lens = status;
            assert!(!eligible_descriptor("CR3", "Canon", "Canon EOS R", &d));
        }
    }

    #[test]
    fn converted_linear_raw_dng_and_unqualified_sensor_domains_are_not_candidates() {
        let d = descriptor();
        for format in ["DNG", "ORF", "ARW", "RAF", "CR2", "PEF", "NEF", "RW2"] {
            assert!(!eligible_descriptor(format,"Canon","Canon EOS R",&d));
        }
        for (make,model) in [("Canon","Canon EOS RP"),("Canon","Canon EOS R5"),("RICOH","GR III"),("Nikon","Canon EOS R")] {
            assert!(!eligible_descriptor("CR3",make,model,&d));
        }
        let mut changed = d.clone(); changed.cpp = 3;
        assert!(!eligible_descriptor("CR3","Canon","Canon EOS R",&changed));
        let mut changed = d.clone(); changed.width += 2;
        assert!(!eligible_descriptor("CR3","Canon","Canon EOS R",&changed));
        let mut changed = d.clone(); changed.active.x += 1;
        assert!(!eligible_descriptor("CR3","Canon","Canon EOS R",&changed));
        let mut changed = d.clone(); changed.crop.w -= 2;
        assert!(!eligible_descriptor("CR3","Canon","Canon EOS R",&changed));
        let mut changed = d.clone(); changed.crop.x += 1;
        assert!(!eligible_descriptor("CR3","Canon","Canon EOS R",&changed));
        let mut changed = d.clone(); changed.cfa = "BGGR".into();
        assert!(!eligible_descriptor("CR3","Canon","Canon EOS R",&changed));
        for orientation in 1..=8 {
            let mut changed = d.clone(); changed.orientation = orientation;
            assert!(eligible_descriptor("CR3","Canon","Canon EOS R",&changed));
        }
    }

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
