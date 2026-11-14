//! Deterministic lookup in the pinned, offline Lensfun database.
//!
//! Names are normalized for spelling conventions, never ranked by substring or
//! edit distance. A source identity resolving to two different lens models is
//! ambiguous. Alternate sensor calibrations of one model are selected only
//! after mount compatibility and the actual camera crop factor are known.

use crate::lensfun_geometry::Calibration;
use super::optics::{TcaCalibration, VignetteCalibration};
use super::profiles;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Projection { Rectilinear, Fisheye, Equisolid, Stereographic, Panoramic }

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct LensProfile {
    pub id: &'static str,
    pub maker: &'static str,
    pub model: &'static str,
    pub maker_aliases: &'static [&'static str],
    pub model_aliases: &'static [&'static str],
    pub maker_key: &'static str,
    pub model_key: &'static str,
    pub mounts: &'static [&'static str],
    pub projection: Projection,
    pub calibration: Calibration<'static>,
    pub tca: &'static [TcaCalibration],
    pub vignetting: &'static [VignetteCalibration],
    /// Native GuessParameters range, including explicit XML overrides.
    pub focal_range: [f64; 2],
    pub aperture_range: [f64; 2],
    /// Preserved even though correction without projection conversion does not
    /// use real-focal. Entries are [nominal focal, measured focal].
    pub real_focal: &'static [[f64; 2]],
    pub source_file: &'static str,
    /// A converter/adapter combination must have an explicit matching identity;
    /// a compact camera's private mount alone cannot establish this attachment.
    pub requires_attachment_identity: bool,
    /// A private fixed-lens family whose generic fixed-lens alias explicitly
    /// shares one record with attachments. Its focal values are discrete
    /// optical groups, not a zoom range to interpolate between.
    pub discrete_focals: bool,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct CameraProfile {
    pub id: &'static str,
    pub maker: &'static str,
    pub model: &'static str,
    pub maker_aliases: &'static [&'static str],
    pub model_aliases: &'static [&'static str],
    pub variant: &'static str,
    pub mount: &'static str,
    pub crop_factor: f64,
    pub fixed_lens: bool,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct MountProfile {
    pub name: &'static str,
    pub compatible: &'static [&'static str],
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct NameIndex {
    pub key: &'static str,
    pub record: u16,
}

#[derive(Clone, Copy, Debug, Default)]
pub(crate) struct MatchQuery<'a> {
    pub camera_make: &'a str,
    pub camera_model: &'a str,
    pub lens_make: Option<&'a str>,
    pub lens_model: Option<&'a str>,
    /// Additional independently established names, not approximate suggestions.
    pub lens_aliases: &'a [&'a str],
    /// An identified lens mount, if the RAW adapter establishes one.
    pub mount: Option<&'a str>,
    pub focal_mm: f64,
    /// The crop factor of the reference rectangle, when independently known.
    /// None uses an unambiguous camera database record; it does not invent a
    /// crop factor for an unknown camera or select a camera-variant arbitrarily.
    pub camera_crop: Option<f64>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct MatchedProfile {
    pub camera: &'static CameraProfile,
    pub lens: &'static LensProfile,
    pub camera_crop: f64,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum MatchOutcome {
    Matched(MatchedProfile),
    MissingCamera,
    AmbiguousCamera,
    MissingLens,
    AmbiguousLens,
    IncompatibleLens,
    InvalidMetadata,
}

pub(crate) fn lenses() -> &'static [LensProfile] { profiles::LENSES }
pub(crate) fn cameras() -> &'static [CameraProfile] { profiles::CAMERAS }

/// Shared with the build-time generator. Preserve digits, decimal points,
/// range hyphens, suffixes and converter markers. Normalize only whitespace,
/// case, Unicode typographic hyphens, and the equivalent f/2.8 versus f2.8.
fn name_key(name: &str) -> String {
    let mut result = String::with_capacity(name.len());
    for ch in name.chars().flat_map(char::to_lowercase) {
        if ch.is_whitespace() { continue; }
        let ch = match ch { '\u{2010}' | '\u{2011}' | '\u{2012}' | '\u{2013}' | '\u{2212}' => '-', _ => ch };
        if ch == '/' && result.ends_with('f') { continue; }
        result.push(ch);
    }
    result
}

fn maker_key(maker: &str) -> String {
    let key: String = maker.chars().flat_map(char::to_lowercase).filter(|c| c.is_alphanumeric()).collect();
    match key.as_str() {
        "nikoncorporation" => "nikon".into(),
        "canoninc" => "canon".into(),
        "sonycorporation" => "sony".into(),
        "fujifilmcorporation" | "fujiphotofilmcoltd" => "fujifilm".into(),
        "olympusimagingcorp" | "olympuscorporation" | "olympusopticalcoltd" => "olympus".into(),
        "omdigitalsolutions" | "omdigitalsolutionscorporation" => "omsystem".into(),
        "ricohimagingcompanyltd" | "ricohimagingcompany" | "ricohcoltd" => "ricoh".into(),
        "leicacameraag" => "leica".into(),
        "lumix" | "panasoniccorporation" | "panasonicentertainmentcommunicationcoltd" => "panasonic".into(),
        "carlzeiss" => "zeiss".into(),
        _ => key,
    }
}

fn bounded_name(name: &str) -> bool {
    !name.is_empty() && name.len() <= 512 && !name.chars().any(char::is_control)
}

fn candidates(index: &'static [NameIndex], key: &str) -> &'static [NameIndex] {
    let start = index.partition_point(|entry| entry.key < key);
    let end = index.partition_point(|entry| entry.key <= key);
    &index[start..end]
}

fn camera_candidates(make: &str, model: &str) -> Vec<&'static CameraProfile> {
    if !bounded_name(make) || !bounded_name(model) { return Vec::new(); }
    let maker = maker_key(make);
    let model = name_key(model);
    let key = format!("{maker}|{model}");
    let mut found = Vec::new();
    for entry in candidates(profiles::CAMERA_INDEX, &key) {
        let camera = &profiles::CAMERAS[entry.record as usize];
        if !found.iter().any(|c: &&CameraProfile| c.id == camera.id) { found.push(camera); }
    }
    found
}

/// Returns None for both missing names and camera variants requiring a crop
/// factor. Use match_profile to retain the distinction for diagnostics.
pub(crate) fn find_camera(make: &str, model: &str) -> Option<&'static CameraProfile> {
    let found = camera_candidates(make, model);
    (found.len() == 1).then(|| found[0])
}

fn canonical_mount(mount: &str) -> &str {
    match mount.trim() {
        // Both spellings occur in the pinned database for the same standard.
        "Micro 4/3" => "Micro 4/3 System",
        other => other,
    }
}

fn same_mount(a: &str, b: &str) -> bool { name_key(canonical_mount(a)) == name_key(canonical_mount(b)) }

/// Database-declared directed compatibility. An RF body may use an identified
/// EF lens through an adapter; compatibility alone never identifies that lens.
fn compatible_mount(camera_mount: &str, lens_mount: &str) -> bool {
    // Both inputs here are canonical XML identifiers, not source text. Avoid
    // allocating normalized strings while traversing this very small graph.
    let camera_mount = canonical_mount(camera_mount);
    let lens_mount = canonical_mount(lens_mount);
    if camera_mount == lens_mount { return true; }
    let mut pending = vec![camera_mount];
    let mut visited: Vec<&str> = Vec::new();
    while let Some(current) = pending.pop() {
        if visited.contains(&current) { continue; }
        visited.push(current);
        if visited.len() > profiles::MOUNTS.len() + 1 { return false; }
        if let Some(mount) = profiles::MOUNTS.iter().find(|m| canonical_mount(m.name) == current) {
            for compatible in mount.compatible {
                let compatible = canonical_mount(compatible);
                if compatible == lens_mount { return true; }
                pending.push(compatible);
            }
        }
    }
    false
}

fn same_model(a: &LensProfile, b: &LensProfile) -> bool {
    if a.maker_key != b.maker_key { return false; }
    if a.model_key == b.model_key { return true; }
    // The database sometimes gives APS-C and MFT calibrations different
    // primary names but explicitly supplies the same model alias (Sigma's
    // "E 30mm f/2.8" / "Sigma 30mm f/2.8 EX DN", for example). Only a shared
    // specific alias is identity evidence. Compact-camera aliases such as
    // "fixed lens" describe unrelated optics and never establish equivalence.
    if [a, b].iter().any(|p| p.source_file.starts_with("compact-") || p.source_file == "actioncams.xml") {
        return false;
    }
    a.model_aliases.iter().any(|left| {
        let left = name_key(left);
        left.chars().any(|c| c.is_ascii_digit()) && !left.starts_with("fixedlens")
            && !left.starts_with("festesobjektiv")
            && b.model_aliases.iter().any(|right| name_key(right) == left)
    })
}

fn compatible_lens(camera: &CameraProfile, lens: &LensProfile, query: &MatchQuery, crop: f64) -> bool {
    if query.lens_make.is_some_and(|m| maker_key(m) != lens.maker_key) { return false; }
    if !lens.mounts.iter().any(|m| compatible_mount(camera.mount, m)) { return false; }
    if query.mount.is_some_and(|m| !lens.mounts.iter().any(|p| same_mount(p, m))) { return false; }
    // Lensfun's crop-factor matching allows small manufacturer rounding
    // differences (e.g. nominal 1.5 versus measured 1.534). Never use an APS-C
    // calibration across an otherwise uncalibrated full-frame outer domain.
    if lens.calibration.crop_factor > crop / 0.96 { return false; }
    if lens.discrete_focals {
        let focal = query.focal_mm as f32;
        return camera.fixed_lens
            && (lens.calibration.points.iter().any(|p| p.focal_mm as f32 == focal)
                || lens.tca.iter().any(|p| p.focal_mm as f32 == focal)
                || lens.vignetting.iter().any(|p| p.focal_mm as f32 == focal));
    }
    let [minimum, maximum] = lens.focal_range;
    minimum > 0.0 && maximum >= minimum && query.focal_mm >= minimum * 0.9999
        && query.focal_mm <= maximum * 1.0001
}

pub(crate) fn match_profile(query: &MatchQuery) -> MatchOutcome {
    if !bounded_name(query.camera_make) || !bounded_name(query.camera_model)
        || !query.focal_mm.is_finite() || !(0.1..=10000.0).contains(&query.focal_mm)
        || query.camera_crop.is_some_and(|v| !v.is_finite() || !(0.1..=20.0).contains(&v))
        || query.lens_aliases.len() > 16
        || query.lens_model.is_some_and(|v| !bounded_name(v))
        || query.lens_make.is_some_and(|v| !bounded_name(v))
        || query.mount.is_some_and(|v| !bounded_name(v))
        || query.lens_aliases.iter().any(|v| !bounded_name(v)) {
        return MatchOutcome::InvalidMetadata;
    }
    let mut cameras = camera_candidates(query.camera_make, query.camera_model);
    if cameras.is_empty() { return MatchOutcome::MissingCamera; }
    if cameras.len() > 1 {
        let Some(crop) = query.camera_crop else { return MatchOutcome::AmbiguousCamera; };
        cameras.retain(|c| (c.crop_factor / crop - 1.0).abs() < 0.02);
        if cameras.is_empty() { return MatchOutcome::AmbiguousCamera; }
        // Some historical EXIF names represent several bodies with exactly
        // the same private lens mount and sensor crop. Their calibration
        // contract is identical after the actual crop has been established.
        if cameras.iter().skip(1).any(|c| c.mount != cameras[0].mount || c.crop_factor != cameras[0].crop_factor) {
            return MatchOutcome::AmbiguousCamera;
        }
    }
    let camera = cameras[0];
    let crop = query.camera_crop.unwrap_or(camera.crop_factor);
    let mut found: Vec<&'static LensProfile> = Vec::new();
    for name in query.lens_model.into_iter().chain(query.lens_aliases.iter().copied()) {
        for entry in candidates(profiles::LENS_INDEX, &name_key(name)) {
            let lens = &profiles::LENSES[entry.record as usize];
            if !found.iter().any(|p| p.id == lens.id) { found.push(lens); }
        }
    }
    if found.is_empty() && query.lens_model.is_none() && query.lens_aliases.is_empty() && camera.fixed_lens {
        found.extend(profiles::LENSES.iter().filter(|p| (!p.requires_attachment_identity || p.discrete_focals)
            && p.mounts.iter().any(|m| same_mount(camera.mount, m))));
    }
    if found.is_empty() { return MatchOutcome::MissingLens; }
    // Two explicit names identifying different physical models are a metadata
    // conflict. A convenient focal length or camera mount must not erase one.
    if query.lens_model.is_some() && !query.lens_aliases.is_empty()
        && found.iter().skip(1).any(|lens| !same_model(found[0], lens)) {
        return MatchOutcome::AmbiguousLens;
    }
    found.retain(|lens| compatible_lens(camera, lens, query, crop));
    if found.is_empty() { return MatchOutcome::IncompatibleLens; }
    let mut selected = found[0];
    for candidate in found.iter().skip(1) {
        if !same_model(selected, candidate) { return MatchOutcome::AmbiguousLens; }
        if candidate.calibration.crop_factor > selected.calibration.crop_factor { selected = candidate; }
    }
    // Equal-crop duplicate records with differing coefficients are not ordered
    // by file name or author. They need an additional source discriminator.
    if found.iter().any(|p| p.id != selected.id
        && p.calibration.crop_factor == selected.calibration.crop_factor
        && (p.calibration != selected.calibration || p.tca != selected.tca || p.vignetting != selected.vignetting)) {
        return MatchOutcome::AmbiguousLens;
    }
    MatchOutcome::Matched(MatchedProfile { camera, lens: selected, camera_crop: crop })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn query<'a>(camera_make: &'a str, camera_model: &'a str, lens_model: Option<&'a str>, focal_mm: f64) -> MatchQuery<'a> {
        MatchQuery { camera_make, camera_model, lens_model, focal_mm, ..Default::default() }
    }

    fn matched(query: &MatchQuery) -> MatchedProfile {
        match match_profile(query) { MatchOutcome::Matched(profile) => profile, other => panic!("{other:?}") }
    }

    #[test]
    fn complete_library_and_index_are_included() {
        assert_eq!(lenses().len(), 1580);
        assert_eq!(cameras().len(), 1059);
        assert_eq!(lenses().iter().filter(|p| !p.calibration.points.is_empty()).count(), 1538);
        assert_eq!(lenses().iter().filter(|p| !p.tca.is_empty()).count(), 1021);
        assert_eq!(lenses().iter().filter(|p| !p.vignetting.is_empty()).count(), 717);
        for index in [profiles::CAMERA_INDEX, profiles::LENS_INDEX] {
            assert!(index.windows(2).all(|p| (p[0].key, p[0].record) < (p[1].key, p[1].record)));
        }
        for (record, lens) in lenses().iter().enumerate() {
            assert!(candidates(profiles::LENS_INDEX, &name_key(lens.model)).iter().any(|p| p.record as usize == record), "{}", lens.id);
            assert!(lens.focal_range[0] > 0.0 && lens.focal_range[0] <= lens.focal_range[1], "{}", lens.id);
        }
        for camera in cameras() {
            for maker in camera.maker_aliases {
                for model in camera.model_aliases {
                    assert!(camera_candidates(maker, model).iter().any(|c| c.id == camera.id), "{} / {maker} / {model}", camera.id);
                }
            }
        }
    }

    #[test]
    fn every_compiled_parameter_range_matches_the_independent_native_library() {
        let oracle: serde_json::Value = serde_json::from_str(include_str!(
            "../tests/fixtures/lensfun-database-0.3.4-ranges.json")).unwrap();
        let rows = oracle["rows"].as_array().unwrap();
        assert_eq!(rows.len(), lenses().len());
        for (lens, expected) in lenses().iter().zip(rows) {
            assert_eq!(lens.id, expected["id"].as_str().unwrap());
            let actual = [lens.focal_range[0], lens.focal_range[1], lens.aperture_range[0], lens.aperture_range[1]];
            let values = expected["ranges"].as_array().unwrap();
            for (actual, expected) in actual.into_iter().zip(values) {
                // The public native fields are f32. Some serde_json versions
                // round a long exact-f32 decimal one f64 ULP differently; its
                // original native f32 bits are the contract under test.
                assert_eq!((actual as f32).to_bits(), (expected.as_f64().unwrap() as f32).to_bits(), "{}", lens.id);
            }
        }
    }

    #[test]
    fn canon_rf16_and_zoom_match_without_a_per_lens_whitelist() {
        let q = query("Canon", "Canon EOS R", Some("RF16mm F2.8 STM"), 16.0);
        assert_eq!(matched(&q).lens.model, "Canon RF 16mm F2.8 STM");
        let q = query("Canon", "EOS R", Some("RF24-105mm F4 L IS USM"), 50.0);
        assert_eq!(matched(&q).lens.model, "Canon RF 24-105mm F4L IS USM");
        let q = query("Canon", "EOS R", Some("RF 24mm F1.8 MACRO IS STM"), 24.0);
        assert_eq!(matched(&q).lens.calibration.crop_factor, 1.0);
        let q = query("Canon", "EOS R7", Some("RF 24mm F1.8 MACRO IS STM"), 24.0);
        assert_eq!(matched(&q).lens.calibration.crop_factor, 1.613);
    }

    #[test]
    fn manufacturer_camera_aliases_and_explicit_lens_aliases_are_resolved() {
        assert!(find_camera("NIKON CORPORATION", "NIKON D850").is_some());
        let q = query("NIKON CORPORATION", "NIKON D850", Some("Nikon AF-S Nikkor 50mm f/1.8G"), 50.0);
        let p = matched(&q);
        assert!(p.lens.model.contains("50mm"));
        let q = query("Canon", "Canon EOS R", Some("RF16mm F2.8 STM II"), 16.0);
        assert_eq!(match_profile(&q), MatchOutcome::MissingLens);
    }

    #[test]
    fn explicit_shared_generation_profiles_expand_only_the_declared_generations() {
        let mut ids = Vec::new();
        for model in ["E PZ 16-50mm F3.5-5.6 OSS", "E PZ 16-50mm F3.5-5.6 OSS I", "E PZ 16-50mm F3.5-5.6 OSS II"] {
            let q = query("Sony", "ILCE-6100", Some(model), 16.0);
            ids.push(matched(&q).lens.id);
        }
        assert!(ids.windows(2).all(|p| p[0] == p[1]));
        let q = query("Sony", "ILCE-6100", Some("E PZ 16-50mm F3.5-5.6 OSS III"), 16.0);
        assert_eq!(match_profile(&q), MatchOutcome::MissingLens);
    }

    #[test]
    fn contradictory_lens_identities_are_not_ranked_or_silently_ignored() {
        let aliases = ["Canon RF 24mm F1.8 MACRO IS STM"];
        let mut q = query("Canon", "Canon EOS R", Some("RF16mm F2.8 STM"), 16.0);
        q.lens_aliases = &aliases;
        // Contradictory complete names must not be resolved by focal length.
        assert_eq!(match_profile(&q), MatchOutcome::AmbiguousLens);
        q.focal_mm = f64::NAN;
        assert_eq!(match_profile(&q), MatchOutcome::InvalidMetadata);
        q.focal_mm = 16.0;
        q.lens_aliases = &[];
        q.lens_make = Some("Sigma");
        assert_eq!(match_profile(&q), MatchOutcome::IncompatibleLens);
    }

    #[test]
    fn mount_and_focal_constraints_prevent_cross_system_and_wrong_range_matches() {
        let q = query("Nikon", "Nikon D850", Some("Canon RF 16mm F2.8 STM"), 16.0);
        assert_eq!(match_profile(&q), MatchOutcome::IncompatibleLens);
        let q = query("Canon", "Canon EOS R", Some("RF16mm F2.8 STM"), 24.0);
        assert_eq!(match_profile(&q), MatchOutcome::IncompatibleLens);
        let q = query("Canon", "Canon EOS R", None, 16.0);
        assert_eq!(match_profile(&q), MatchOutcome::MissingLens);
        let q = query("Unlisted", "Unknown camera", Some("RF16mm F2.8 STM"), 16.0);
        assert_eq!(match_profile(&q), MatchOutcome::MissingCamera);
    }

    #[test]
    fn special_projection_and_extra_only_profiles_are_retained() {
        assert_eq!(lenses().iter().filter(|p| p.projection != Projection::Rectilinear).count(), 30);
        assert_eq!(lenses().iter().filter(|p| p.calibration.points.is_empty() && (!p.tca.is_empty() || !p.vignetting.is_empty())).count(), 39);
        assert_eq!(lenses().iter().map(|p| p.real_focal.len()).sum::<usize>(), 24);
    }

    #[test]
    fn camera_variants_and_converter_attachments_are_not_guessed() {
        let mut q = query("Panasonic", "DMC-LX3", None, 5.1);
        assert_eq!(match_profile(&q), MatchOutcome::AmbiguousCamera);
        q.camera_crop = Some(4.7);
        assert!(!matches!(match_profile(&q), MatchOutcome::AmbiguousCamera));
        // An identified EF lens on an RF camera is permitted by the directed
        // mount graph; the graph never supplies an identity for a missing lens.
        let q = query("Canon", "EOS R", Some("Canon EF 50mm f/1.8 STM"), 50.0);
        assert_eq!(matched(&q).lens.model, "Canon EF 50mm f/1.8 STM");
    }

    #[test]
    fn combined_fixed_lens_records_require_an_exact_discrete_optical_group() {
        for focal in [13.8, 18.3] {
            let q = query("Ricoh", "GR III", None, focal);
            let p = matched(&q);
            assert!(p.lens.discrete_focals);
            assert_eq!(p.lens.model, "Ricoh GR III & compatibles + GW-4");
        }
        for focal in [13.7, 15.0, 18.0, 18.30001, 19.0] {
            let q = query("Ricoh", "GR III", None, focal);
            assert_eq!(match_profile(&q), MatchOutcome::IncompatibleLens);
        }
        let q = query("Ricoh", "GR III", Some("Ricoh GR III + GW-3"), 18.3);
        assert_eq!(match_profile(&q), MatchOutcome::MissingLens);
        // The data's distinct converter-only records do not inherit this
        // exception merely because they share a camera mount and focal value.
        assert!(lenses().iter().filter(|p| p.discrete_focals).all(|p|
            p.model_aliases.iter().any(|name| name_key(name) == "fixedlens")));
    }

    #[test]
    fn full_library_exact_identity_lookup_reports_real_camera_coverage() {
        let mut matched_records = 0usize;
        let mut matched_models = std::collections::BTreeSet::new();
        let mut absent_camera_records = 0usize;
        let mut unresolved_records = Vec::new();
        for lens in lenses() {
            let mut has_camera = false;
            let mut exact_record = false;
            let mut model_match = false;
            for camera in cameras() {
                if lens.calibration.crop_factor > camera.crop_factor / 0.96
                    || !lens.mounts.iter().any(|m| compatible_mount(camera.mount, m)) { continue; }
                has_camera = true;
                let mut q = query(camera.maker, camera.model, Some(lens.model), lens.focal_range[0]);
                q.lens_make = Some(lens.maker);
                // This test names a concrete database camera variant. A RAW
                // adapter must independently establish that same crop factor.
                q.camera_crop = Some(camera.crop_factor);
                if let MatchOutcome::Matched(profile) = match_profile(&q) {
                    assert!(same_model(lens, profile.lens), "{} became {}", lens.id, profile.lens.id);
                    model_match = true;
                    matched_models.insert((lens.maker_key, lens.model_key));
                    if profile.lens.id == lens.id { exact_record = true; break; }
                }
            }
            matched_records += usize::from(exact_record);
            if !has_camera { absent_camera_records += 1; }
            else if !model_match { unresolved_records.push(lens.id); }
        }
        eprintln!("Lensfun canonical identity coverage: {matched_records}/1580 exact calibration records; {} unique model keys; {absent_camera_records} records have no compatible database camera; unresolved {:?}", matched_models.len(), unresolved_records);
        // The inventory contains manual/film-only mounts and generic entries.
        // This gate ensures broad real camera lookup, not an RF16 allowlist.
        assert_eq!(matched_records, 1580);
        assert_eq!(absent_camera_records, 0);
        assert!(matched_models.len() > 1400);
        assert!(unresolved_records.is_empty(), "{unresolved_records:?}");
    }
}
