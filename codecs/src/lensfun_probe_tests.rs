use super::*;

fn camera() -> CameraRaw {
    let active = Rect {x:0,y:0,w:150,h:100};
    CameraRaw {
        he: None,
        development: Development {
            width:150,height:100,cpp:1,orientation:1,active,crop:active,
            cfa:"RGGB".into(),wb:[1.0;4],matrix:vec![],baseline_exposure_ev:0.0,
            gain_maps:&[],lens:crate::embedded_lens::LensStatus::Missing,lens_reference:active,
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
    assert_eq!(profile.development_policy,"camera-matrix-linear-srgb-lensfun-v1");
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
