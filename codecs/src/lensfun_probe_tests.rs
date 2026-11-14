use super::*;

fn camera() -> CameraRaw {
    let active = Rect {x:0,y:0,w:150,h:100};
    CameraRaw {
        he: None,
        development: Development {
            width:150,height:100,cpp:1,orientation:1,active,crop:active,
            cfa:"RGGB".into(),wb:[1.0;4],matrix:vec![],baseline_exposure_ev:0.0,
            gain_maps:&[],lens:crate::embedded_lens::LensStatus::Missing,lens_extras:Default::default(),lens_profile:None,lens_reference:active,
        },
        format:"CR3",input_bytes:100,jpeg_blocks:vec![],decoder_extra_bytes:0,
        source_ifd_offset:0,arw1_offset:None,arw6:None,
    }
}

#[test]
fn profile_has_a_distinct_warning_and_invalidates_older_linear_cache_policy() {
    let mut c = camera();
    let plain = c.probe();
    assert_eq!(plain.development_policy,"camera-matrix-linear-srgb-v1");
    c.development.lens = crate::embedded_lens::LensStatus::Profile(crate::lensfun::rf16_for_test(150,100));
    let profile = c.probe();
    assert_eq!(profile.development_policy,"camera-matrix-linear-srgb-lensfun-v3");
    assert_eq!(profile.width,plain.width);
    assert_eq!(profile.height,plain.height);
    assert!(profile.warnings.iter().any(|w|w.code=="LENSFUN_DISTORTION_APPLIED"));
    assert!(!profile.warnings.iter().any(|w|w.code=="EMBEDDED_LENS_DISTORTION_APPLIED"));
    c.development.lens = crate::embedded_lens::from_metadata(&rawler::rawimage::EmbeddedLensDistortion::Sony {samples:vec![0;11]});
    let embedded = c.probe();
    assert_eq!(embedded.development_policy,plain.development_policy);
    assert!(embedded.warnings.iter().any(|w|w.code=="EMBEDDED_LENS_DISTORTION_APPLIED"));
    assert!(!embedded.warnings.iter().any(|w|w.code=="LENSFUN_DISTORTION_APPLIED"));
}

#[test]
fn optics_warnings_and_policy_describe_each_selected_operation_without_double_claims() {
    use crate::embedded_optics::{self, State};
    use rawler::rawimage::{EmbeddedLensCorrections, EmbeddedOpticsState, EmbeddedTca, EmbeddedVignette};
    let mut c=camera();
    c.development.lens=crate::embedded_lens::LensStatus::Profile(
        crate::lensfun::rf16_with_optics_for_test(150,100,Some(2.8),Some([10.0,1000.0])));
    let p=c.probe();
    let codes:Vec<_>=p.warnings.iter().map(|w|w.code).collect();
    assert!(codes.contains(&"LENSFUN_DISTORTION_APPLIED"));
    assert!(codes.contains(&"LENSFUN_TCA_APPLIED"));
    assert!(codes.contains(&"LENSFUN_VIGNETTING_APPLIED"));
    assert_eq!(p.development_policy,"camera-matrix-linear-srgb-lensfun-v3");
    c.development.lens_extras=embedded_optics::from_metadata(&EmbeddedLensCorrections {
        tca:EmbeddedOpticsState::Ready(EmbeddedTca::Sony{red:vec![0;11],blue:vec![0;11],distortion:None}),
        vignette:EmbeddedOpticsState::Ready(EmbeddedVignette::Sony{samples:vec![0;11]}),
    });
    c.development.lens_extras.vignette=State::Unsupported;
    let p=c.probe();let codes:Vec<_>=p.warnings.iter().map(|w|w.code).collect();
    assert!(codes.contains(&"EMBEDDED_LENS_TCA_APPLIED"));
    assert!(codes.contains(&"EMBEDDED_LENS_VIGNETTING_NOT_APPLIED"));
    assert!(!codes.iter().any(|s|s.starts_with("LENSFUN_TCA")||s.starts_with("LENSFUN_VIGNETTING")));
    c.development.lens=crate::embedded_lens::LensStatus::Missing;
    assert_eq!(c.probe().development_policy,"camera-matrix-linear-srgb-embedded-v2");
    c.development.lens_extras.tca=State::Unsupported;
    let p=c.probe();let codes:Vec<_>=p.warnings.iter().map(|w|w.code).collect();
    assert_eq!(p.development_policy,"camera-matrix-linear-srgb-v1");
    assert!(codes.contains(&"EMBEDDED_LENS_TCA_NOT_APPLIED"));
    assert!(!codes.iter().any(|s|s.ends_with("_APPLIED")&&!s.ends_with("_NOT_APPLIED")));
}

#[test]
fn missing_profile_exposure_metadata_skips_only_vignette_and_reports_that_fact() {
    let mut c=camera();
    for (aperture,distance) in [(None,Some([10.0,1000.0])),(Some(2.8),None)] {
        c.development.lens=crate::embedded_lens::LensStatus::Profile(
            crate::lensfun::rf16_with_optics_for_test(150,100,aperture,distance));
        let p=c.probe();let codes:Vec<_>=p.warnings.iter().map(|w|w.code).collect();
        assert!(codes.contains(&"LENSFUN_DISTORTION_APPLIED"));
        assert!(codes.contains(&"LENSFUN_TCA_APPLIED"));
        assert!(codes.contains(&"LENSFUN_VIGNETTING_NOT_APPLIED"));
        assert!(!codes.contains(&"LENSFUN_VIGNETTING_APPLIED"));
    }
}
