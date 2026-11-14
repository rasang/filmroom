//! Full-library selection is data driven and independent for each operation.
//! The RAW adapter supplies an original-source identity; names are resolved by
//! the offline registry before any optical calibration is constructed.
use crate::{
    develop::Development,
    dng::Rect,
    embedded_lens::LensStatus,
    embedded_optics::State,
    lensfun_geometry::Geometry,
    lensfun_metadata::CaptureIdentity,
};
use super::{database::{self, MatchOutcome, MatchQuery}, optics::{TcaCorrection, VignettingCorrection}};

#[derive(Clone, Debug, PartialEq)]
pub struct ProfileSelection {
    pub(crate) profile_id: &'static str,
    reference: [usize; 4],
    pub(crate) distortion: Option<Geometry>,
    pub(crate) tca: Option<TcaCorrection>,
    pub(crate) vignette: Option<VignettingCorrection>,
    /// A calibration was present and eligible for fallback, but may have been
    /// rejected because its capture parameters or continuous bounds are unsafe.
    pub(crate) requested: [bool; 3],
    /// One uniform zoom, applied before the selected distortion and TCA maps.
    /// Composition and memory bounds are certified before publishing this plan.
    pub(crate) extra_zoom: f64,
}

impl ProfileSelection {
    pub fn profile_id(&self) -> &'static str { self.profile_id }
    pub fn reference(&self) -> Rect {
        let [x,y,w,h] = self.reference;
        Rect {x,y,w,h}
    }
    pub fn has_distortion(&self) -> bool { self.distortion.is_some() }
    pub fn has_tca(&self) -> bool { self.tca.is_some() }
    pub fn has_vignetting(&self) -> bool { self.vignette.is_some() }
    pub fn has_applied(&self) -> bool {
        self.has_distortion() || self.has_tca() || self.has_vignetting()
    }
    pub fn distortion_not_applied(&self) -> bool { self.requested[0] && !self.has_distortion() }
    pub fn tca_not_applied(&self) -> bool { self.requested[1] && !self.has_tca() }
    pub fn vignetting_not_applied(&self) -> bool { self.requested[2] && !self.has_vignetting() }
}

pub(crate) fn configure_capture(format: &str, identity: &CaptureIdentity, d: &mut Development<'_>) {
    // Camera-produced LinearRaw is not an uncorrected mosaic. The DNG adapter
    // separately proves native firmware origin and absence of optical opcodes.
    if !matches!(format, "CR3"|"CR2"|"NEF"|"ARW"|"RAF"|"ORF"|"PEF"|"RW2"|"DNG")
        || d.cpp != 1 || !d.gain_maps.is_empty() || d.crop.w < 5 || d.crop.h < 5
        || d.lens_profile.is_some() || matches!(d.lens, LensStatus::Profile(_))
    { return; }
    // Olympus shading switches are not gain coefficients, and shading may
    // already be baked into ORF pixels. Missing a supported embedded PA table
    // does not prove an uncorrected source. Until that state is established,
    // do not add a second shading correction or invent a detected-table warning.
    let external_vignette_allowed = format != "ORF";
    let wanted = [matches!(d.lens, LensStatus::Missing),
        matches!(d.lens_extras.tca, State::Missing),
        external_vignette_allowed && matches!(d.lens_extras.vignette, State::Missing)];
    if !wanted.iter().any(|v| *v) { return; }
    let aliases: Vec<&str> = identity.lens_aliases.iter().map(String::as_str).collect();
    let query = MatchQuery {
        camera_make: &identity.make,
        camera_model: &identity.model,
        lens_make: identity.lens_make.as_deref(),
        lens_model: identity.lens_model.as_deref(),
        lens_aliases: &aliases,
        mount: identity.mount.as_deref(),
        focal_mm: identity.focal_length_mm,
        camera_crop: None,
    };
    let MatchOutcome::Matched(found) = database::match_profile(&query) else { return; };
    let lens = found.lens;
    let reference = d.crop;
    // A database record may intentionally contain discrete physical setups
    // (fixed camera lens with/without a converter). Never interpolate between
    // those setups, including indirect contributions to PA's 3-D IDW metric.
    use std::borrow::Cow;
    let focal=identity.focal_length_mm;
    let points:Cow<'_,[_]>=if lens.discrete_focals {
        Cow::Owned(lens.calibration.points.iter().filter(|p|p.focal_mm as f32==focal as f32).copied().collect())
    } else {Cow::Borrowed(lens.calibration.points)};
    let tca_points:Cow<'_,[_]>=if lens.discrete_focals {
        Cow::Owned(lens.tca.iter().filter(|p|p.focal_mm as f32==focal as f32).copied().collect())
    } else {Cow::Borrowed(lens.tca)};
    let vignette_points:Cow<'_,[_]>=if lens.discrete_focals {
        Cow::Owned(lens.vignetting.iter().filter(|p|p.focal_mm as f32==focal as f32).copied().collect())
    } else {Cow::Borrowed(lens.vignetting)};
    let calibration=crate::lensfun_geometry::Calibration {points:&points,..lens.calibration};
    let focal_range=if lens.discrete_focals {[focal,focal]} else {lens.focal_range};
    let Some(normalization) = Geometry::identity(&calibration, found.camera_crop,
        reference.w, reference.h) else { return; };
    let requested = [wanted[0] && !points.is_empty(),
        wanted[1] && !tca_points.is_empty(), wanted[2] && !vignette_points.is_empty()];
    if !requested.iter().any(|value| *value) { return; }
    let distortion = requested[0].then(|| Geometry::new_general(&calibration,
        found.camera_crop, identity.focal_length_mm, reference.w, reference.h)).flatten();
    // Always construct the post-TCA map in its own calibration reference. This
    // also permits embedded distortion plus an external TCA in one resample.
    let tca = requested[1].then(|| TcaCorrection::new_samples(&normalization,
        &tca_points, identity.focal_length_mm)).flatten();
    let vignette = if requested[2] {
        identity.aperture.zip(identity.focus_range_m).and_then(|(aperture, focus)| {
            let active_bounds = [
                d.active.x as f64-reference.x as f64,
                d.active.y as f64-reference.y as f64,
                (d.active.x+d.active.w-1) as f64-reference.x as f64,
                (d.active.y+d.active.h-1) as f64-reference.y as f64,
            ];
            VignettingCorrection::new_general_for_bounds(&normalization, &vignette_points,
                calibration.aspect_ratio, focal_range,
                identity.focal_length_mm, aperture, focus, active_bounds)
        })
    } else { None };
    d.lens_profile = Some(ProfileSelection {
        profile_id: lens.id, reference: [reference.x,reference.y,reference.w,reference.h],
        distortion, tca, vignette, requested, extra_zoom: 1.0,
    });
    if !matches!(d.lens, LensStatus::Ready(_)) && !d.lens_extras.has_ready() {
        d.lens_reference = reference;
    }
    crate::develop::qualify_optics(d);
}
