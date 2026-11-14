//! End-to-end shared-sink tests. Expected images use independently constructed
//! camera RGB and simple analytic gains/constant channel scale, before the
//! already-tested color/crop/orientation path.
use super::*;
use crate::embedded_optics::{self, LensExtras, State};
use rawler::rawimage::{EmbeddedLensCorrections, EmbeddedOpticsState, EmbeddedTca, EmbeddedVignette};

fn tca() -> LensExtras {
    embedded_optics::from_metadata(&EmbeddedLensCorrections {
        tca: EmbeddedOpticsState::Ready(EmbeddedTca::Sony {
            red: vec![2048; 11], blue: vec![-2048; 11], distortion: Some(vec![0; 11]),
        }), vignette: EmbeddedOpticsState::Missing,
    })
}

fn vignette() -> LensExtras {
    let knots: Vec<f64> = (0..11).map(|i| i as f64 / 10.0).collect();
    embedded_optics::from_metadata(&EmbeddedLensCorrections {
        tca: EmbeddedOpticsState::Missing,
        vignette: EmbeddedOpticsState::Ready(EmbeddedVignette::Fuji {
            levels: knots.iter().map(|r| 100.0 / (1.0 + 0.2 * r)).collect(), knots, crop_factor: 1.0,
        }),
    })
}

fn render_camera(d: &Development, pixels: Vec<[f32; 3]>) -> Vec<u8> {
    assert_eq!(d.cpp, 3);
    assert_eq!(pixels.len(), d.active.w * d.active.h);
    let scratch = Scratch::new(); let (path, out) = scratch.output();
    out.set_len((d.crop.w * d.crop.h * 12) as u64).unwrap();
    let cancel = AtomicBool::new(false);
    let sink = CameraRgbSink::new(d, &path).unwrap();
    let region = sink.region(d);
    let p = sink.develop(d, d.active.y, d.active.h,
        pixels.into_iter().flatten().collect(), &cancel).unwrap();
    sink.emit_strip(d, &out, &p, d.active.y, region.y, region.y + region.h, &cancel).unwrap();
    sink.finish(d, &out, &cancel).unwrap();
    std::fs::read(path).unwrap()
}

fn original(d: &Development) -> Vec<[f32; 3]> {
    (0..d.active.h).flat_map(|y| (0..d.active.w).map(move |x| camera_pixel(x, y))).collect()
}

fn close(actual: &[u8], expected: &[u8], epsilon: f32) {
    assert_eq!(actual.len(), expected.len());
    let error = actual.chunks_exact(4).zip(expected.chunks_exact(4)).map(|(a,b)| {
        let a = f32::from_le_bytes(a.try_into().unwrap());
        let b = f32::from_le_bytes(b.try_into().unwrap());
        assert!(a.is_finite() && b.is_finite()); (a-b).abs()
    }).fold(0.0_f32, f32::max);
    assert!(error <= epsilon, "max pixel error {error}, allowed {epsilon}");
}

fn full_library_profile(d:&mut Development,lens_model:&str,focal:f64,aperture:Option<f64>,focus:Option<[f64;2]>) {
    // This is a sensor-to-RGB sink test, independently of source-file parsing:
    // select on a synthetic CFA descriptor, then feed analytic camera RGB.
    let cpp=d.cpp;d.cpp=1;
    let identity=crate::lensfun_metadata::CaptureIdentity {
        make:"Canon".into(),model:"Canon EOS R".into(),lens_make:Some("Canon".into()),
        lens_model:Some(lens_model.into()),lens_aliases:vec![],mount:Some("Canon RF".into()),
        focal_length_mm:focal,aperture,focus_range_m:focus,
        provenance:crate::lensfun_metadata::LensIdentityProvenance::OriginalExif,
    };
    crate::lensfun::configure_capture("CR3",&identity,d);d.cpp=cpp;
    assert!(d.lens_profile.is_some(),"profile {lens_model}");
}

#[test]
fn complete_database_selects_multiple_lenses_and_missing_capture_values_are_per_operation() {
    for (name,focal,aperture) in [("Canon RF 16mm F2.8 STM",16.0,2.8),
        ("Canon RF 24-105mm F4L IS USM",50.0,4.0)] {
        let mut d=development();d.lens=LensStatus::Missing;d.crop=d.active;
        full_library_profile(&mut d,name,focal,Some(aperture),Some([1000.0,1000.0]));
        let p=d.lens_profile.as_ref().unwrap();
        assert!(p.has_distortion(),"{name}");
        assert!(p.has_tca(),"{name}");
        assert!(p.has_vignetting(),"{name}");
        let all=render_strips(&d,31);
        let mut no_exposure=development();no_exposure.lens=LensStatus::Missing;no_exposure.crop=no_exposure.active;
        full_library_profile(&mut no_exposure,name,focal,None,None);
        let p=no_exposure.lens_profile.as_ref().unwrap();
        assert!(p.has_distortion() && p.has_tca() && !p.has_vignetting());
        assert!(p.vignetting_not_applied());
        assert_ne!(all,render_strips(&no_exposure,31));
    }
}

#[test]
fn olympus_source_does_not_double_apply_shading_even_with_complete_exposure_metadata() {
    let mut d=development();d.cpp=1;d.crop=d.active;d.lens=LensStatus::Missing;
    let identity=crate::lensfun_metadata::CaptureIdentity {
        make:"Olympus".into(),model:"E-M10".into(),lens_make:Some("Panasonic".into()),
        lens_model:Some("Lumix G 20mm F1.7 Asph.".into()),lens_aliases:vec![],
        mount:Some("Micro 4/3 System".into()),focal_length_mm:20.0,
        aperture:Some(1.7),focus_range_m:Some([10.0,10.0]),
        provenance:crate::lensfun_metadata::LensIdentityProvenance::OriginalExif,
    };
    crate::lensfun::configure_capture("ORF",&identity,&mut d);
    let p=d.lens_profile.as_ref().expect("the original lens still matches");
    assert!(p.has_distortion() && p.has_tca());
    assert!(!p.has_vignetting() && !p.vignetting_not_applied());
    assert!(matches!(d.lens_extras.vignette,State::Missing));
}

#[test]
fn full_library_preserves_embedded_identity_unsupported_and_missing_independently() {
    let mut d=development();d.crop=d.active;d.lens=lens(0);
    let embedded=d.lens.clone();
    full_library_profile(&mut d,"Canon RF 16mm F2.8 STM",16.0,Some(2.8),Some([1000.0,1000.0]));
    let p=d.lens_profile.as_ref().unwrap();
    assert_eq!(d.lens,embedded);
    assert!(!p.has_distortion() && p.has_tca() && p.has_vignetting());
    let corrected=render_strips(&d,31);
    let mut plain=d.clone();plain.lens_profile=None;
    assert_ne!(corrected,render_strips(&plain,31));

    let mut d=development();d.crop=d.active;d.lens=LensStatus::Unsupported;
    d.lens_extras.tca=State::Unsupported;
    full_library_profile(&mut d,"Canon RF 16mm F2.8 STM",16.0,Some(2.8),Some([1000.0,1000.0]));
    let p=d.lens_profile.as_ref().unwrap();
    assert!(!p.has_distortion() && !p.has_tca() && p.has_vignetting());
    assert!(!p.distortion_not_applied() && !p.tca_not_applied());
    assert!(matches!(d.lens,LensStatus::Unsupported));
    assert!(matches!(d.lens_extras.tca,State::Unsupported));
    assert!(CameraRgbSink::new(&d,&Scratch::new().0.join("unused")).unwrap().camera.is_none());
}

#[test]
fn mixed_source_references_keep_all_orientations_and_strip_boundaries() {
    for embedded in [false,true] {
        let mut full=development();full.crop=full.active;
        if !embedded {full.lens=LensStatus::Missing;}
        full_library_profile(&mut full,"Canon RF 16mm F2.8 STM",16.0,Some(2.8),Some([1000.0,1000.0]));
        assert!(full.lens_profile.as_ref().unwrap().has_tca());
        let reference=render_strips(&full,128);
        for orientation in 1..=8 {
            let mut cropped=full.clone();cropped.crop=development().crop;cropped.orientation=orientation;
            let expected=crop_and_orient(&reference,&cropped,full.active);
            for rows in [8,31,128] {assert_eq!(render_strips(&cropped,rows),expected);}
        }
    }
    // An off-center default crop makes embedded active and external reference
    // origins different. The model composition must still use source pixels.
    let mut mixed=development();
    full_library_profile(&mut mixed,"Canon RF 16mm F2.8 STM",16.0,None,None);
    assert!(mixed.lens_profile.as_ref().unwrap().has_tca());
    let expected=render_strips(&mixed,128);
    for rows in [8,17,31] {assert_eq!(render_strips(&mixed,rows),expected);}
}

#[test]
fn embedded_tca_composes_with_external_distortion_without_a_second_resample() {
    let mut d=development();d.lens=LensStatus::Missing;d.crop=d.active;d.lens_extras=tca();
    let original_tca=d.lens_extras.tca.clone();
    full_library_profile(&mut d,"Canon RF 16mm F2.8 STM",16.0,None,None);
    let p=d.lens_profile.as_ref().unwrap();
    assert!(p.has_distortion() && !p.has_tca());
    assert_eq!(d.lens_extras.tca,original_tca);
    let expected=render_strips(&d,128);
    for rows in [8,31] {assert_eq!(render_strips(&d,rows),expected);}
    let mut plain=d.clone();plain.lens_profile=None;
    assert_ne!(render_strips(&plain,128),expected);
}

#[test]
fn rf16_continuous_composition_keeps_native_field_of_view_at_full_resolution_and_shifted_reference() {
    for shifted in [false,true] {
        let mut d=development();
        d.width=6100;d.height=4100;
        d.active=Rect{x:27,y:31,w:6048,h:4048};
        d.crop=Rect{x:51,y:43,w:6000,h:4000};
        d.lens_reference=d.active;
        d.lens=LensStatus::Missing;
        if shifted {
            // Keep the manufacturer's active reference independently of the
            // external profile's off-center visible crop, without another gain.
            d.lens_extras=embedded_optics::from_metadata(&EmbeddedLensCorrections {
                tca:EmbeddedOpticsState::Missing,
                vignette:EmbeddedOpticsState::Ready(EmbeddedVignette::Sony{samples:vec![0;11]}),
            });
        }
        full_library_profile(&mut d,"Canon RF 16mm F2.8 STM",16.0,None,None);
        let p=d.lens_profile.as_ref().unwrap();
        assert!(p.has_distortion() && p.has_tca());
        assert!(p.extra_zoom<=1.002,
            "interval overestimation must not force a 10% crop: shifted={shifted}, zoom={}",p.extra_zoom);
        let old=crate::lensfun::rf16_with_optics_for_test(d.crop.w,d.crop.h,None,None);
        let dx=(d.crop.x-d.lens_reference.x) as f64;
        let dy=(d.crop.y-d.lens_reference.y) as f64;
        for y in [0,1,999,1999,3998,3999] {for x in [0,1,1499,2999,5998,5999] {for c in 0..3 {
            let expected=old.source_position_channel(d.crop.w,d.crop.h,x,y,c).unwrap();
            let actual=optics::source_position(&d,x+dx as usize,y+dy as usize,c).unwrap();
            assert!((actual[0]-dx-expected[0]).abs()<0.001
                && (actual[1]-dy-expected[1]).abs()<0.001,
                "native RF16 view changed at ({x},{y}), channel {c}, shifted={shifted}");
        }}}
    }
}

#[test]
fn vignette_is_applied_at_source_coordinates_before_distortion_and_color_once() {
    for distortion in [LensStatus::Missing, development().lens] {
        let mut d = development(); d.lens = distortion; d.lens_extras = vignette();
        let pixels = original(&d);
        let actual = render_camera(&d, pixels.clone());
        // Analytic gain 1 + 0.2*r, independent of the production model's spline.
        let center = [d.lens_reference.w as f64 * 0.5, d.lens_reference.h as f64 * 0.5];
        let corrected: Vec<_> = pixels.iter().enumerate().map(|(i, pixel)| {
            let x = (i % d.active.w + d.active.x) as f64 - d.lens_reference.x as f64;
            let y = (i / d.active.w + d.active.y) as f64 - d.lens_reference.y as f64;
            let radius = (x-center[0]).hypot(y-center[1]) / center[0].hypot(center[1]);
            pixel.map(|v| (f64::from(v) * (1.0 + 0.2 * radius.min(1.0))) as f32)
        }).collect();
        let mut expected_d = d.clone(); expected_d.lens_extras = Default::default();
        let expected = render_camera(&expected_d, corrected);
        close(&actual, &expected, 3e-7);
        assert_ne!(actual, render_camera(&expected_d, pixels));
    }
}

#[test]
fn tca_only_resamples_each_camera_channel_before_color_with_one_shared_zoom() {
    let mut d = development(); d.lens = LensStatus::Missing; d.lens_extras = tca();
    let pixels = original(&d);
    let actual = render_camera(&d, pixels.clone());
    let (w,h) = (d.active.w, d.active.h);
    let center = [w as f64 * 0.5, h as f64 * 0.5];
    let factors = [1.0 + 1.0/1024.0, 1.0, 1.0 - 1.0/1024.0];
    let zoom = factors[0];
    let kernel = crate::dng_warp::CubicWeights::new();
    let source = |x: usize, y: usize, c: usize| Ok(pixels[y*w+x][c]);
    let mut expected_pixels = Vec::with_capacity(w*h);
    for y in 0..h { for x in 0..w {
        let mut p = [0.0; 3];
        // Preserve the specified zoom-then-channel coordinate stages. Folding
        // them into one expression changes rounding at exact pixel boundaries
        // (e.g. x=64 by 7e-15), which can select the adjacent 1/32 kernel phase.
        let zoomed = [center[0] + (x as f64-center[0]) / zoom,
            center[1] + (y as f64-center[1]) / zoom];
        for c in 0..3 {
            let analytic = [center[0] + (x as f64-center[0]) / zoom * factors[c],
                center[1] + (y as f64-center[1]) / zoom * factors[c]];
            let position = if c == 1 { zoomed } else {
                [center[0] + (zoomed[0]-center[0]) * factors[c],
                 center[1] + (zoomed[1]-center[1]) * factors[c]]
            };
            let actual_position = optics::source_position(&d, x, y, c).unwrap();
            for axis in 0..2 {
                assert!((actual_position[axis]-analytic[axis]).abs() < 1e-11,
                    "independent analytic coordinate ({x},{y}) channel {c} axis {axis}");
            }
            p[c] = crate::dng_warp::sample_position(&kernel,w,h,position,c,false,&source).unwrap();
        }
        expected_pixels.push(p);
    }}
    let mut plain = d.clone(); plain.lens_extras = Default::default();
    close(&actual, &render_camera(&plain, expected_pixels), 2e-6);
    assert_ne!(actual, render_camera(&plain, pixels));
}

#[test]
fn combined_optics_keep_crop_orientation_and_bayer_xtrans_strip_halos_exact() {
    let mut full = development(); full.lens = lens(0);
    full.lens_extras = tca(); full.lens_extras.vignette = vignette().vignette;
    full.crop = full.lens_reference;
    let reference = render_strips(&full,128);
    for orientation in 1..=8 {
        let mut cropped = full.clone(); cropped.crop = development().crop; cropped.orientation = orientation;
        let expected = crop_and_orient(&reference,&cropped,full.lens_reference);
        for rows in [8,31,128] { assert_eq!(render_strips(&cropped,rows),expected); }
    }
    for cfa in ["RGGB", "GRGGBGBGBRGRGRGGBGGBGGRGRGRBGBGBGGRG"] {
        let mut d=full.clone(); d.cpp=1; d.cfa=cfa.into();
        let expected=render_strips(&d,128);
        for rows in [8,17,31] { assert_eq!(render_strips(&d,rows),expected,"CFA {cfa} rows {rows}"); }
    }
}

#[test]
fn lensfun_full_optics_and_independent_embedded_overrides_do_not_apply_twice() {
    let mut d=development();
    d.lens=LensStatus::Profile(crate::lensfun::rf16_with_optics_for_test(
        d.active.w,d.active.h,Some(2.8),Some([10.0,1000.0])));
    let actual=render_strips(&d,31);
    let mut old=d.clone();
    old.lens=LensStatus::Profile(crate::lensfun::rf16_for_test(d.active.w,d.active.h));
    let baseline=render_strips(&old,31);
    assert_ne!(actual,baseline);
    let identity=embedded_optics::from_metadata(&EmbeddedLensCorrections {
        tca:EmbeddedOpticsState::Ready(EmbeddedTca::Sony {red:vec![0;11],blue:vec![0;11],distortion:None}),
        vignette:EmbeddedOpticsState::Ready(EmbeddedVignette::Sony {samples:vec![0;11]}),
    });
    d.lens_extras=identity;
    assert_eq!(render_strips(&d,31),baseline,"accepted embedded identity blocks both external items");
    d.lens_extras=LensExtras {tca:State::Unsupported,vignette:State::Unsupported};
    assert_eq!(render_strips(&d,31),baseline,"present unsupported items are not silently replaced");
    d.lens_extras.vignette=State::Missing;
    let vignette_only=render_strips(&d,31);
    assert_ne!(vignette_only,baseline); assert_ne!(vignette_only,actual);
    d.lens_extras=LensExtras {tca:State::Missing,vignette:State::Unsupported};
    let tca_only=render_strips(&d,31);
    assert_ne!(tca_only,baseline); assert_ne!(tca_only,actual);
}

#[test]
fn vignette_only_keeps_strip_storage_and_tca_charges_the_existing_geometry_budget() {
    let mut d=development(); d.lens=LensStatus::Missing; d.lens_extras=vignette();
    let b=Budget::default(); let before=admission();
    assert_eq!(admit_geometry(&d,&b,before.clone()).unwrap().required_disk_bytes,before.required_disk_bytes);
    let scratch=Scratch::new(); let (path,out)=scratch.output();
    let sink=CameraRgbSink::new(&d,&path).unwrap(); assert!(sink.camera.is_none());
    assert_eq!(sink.develop(&d,d.active.y,d.active.h,samples(&d,d.active.y,d.active.y+d.active.h),
        &AtomicBool::new(true)).err().unwrap().code,"CANCELLED");
    d.lens_extras.tca=tca().tca;
    let needed=before.required_disk_bytes+(d.active.w*d.active.h*12) as u64;
    let mut exact=b.clone(); exact.disk_budget_bytes=needed;
    assert_eq!(admit_geometry(&d,&exact,before.clone()).unwrap().required_disk_bytes,needed);
    exact.disk_budget_bytes-=1;
    assert_eq!(admit_geometry(&d,&exact,before).unwrap_err().code,"DISK_BUDGET");
    let sink=CameraRgbSink::new(&d,&path).unwrap(); assert!(sink.camera.is_some());
    assert_eq!(std::fs::read_dir(&scratch.0).unwrap().count(),1);
    assert_eq!(sink.finish(&d,&out,&AtomicBool::new(true)).unwrap_err().code,"CANCELLED");
}

#[test]
fn an_unexecutable_optional_tca_is_rejected_before_probe_without_losing_vignette() {
    let mut d=development(); d.width=5184;d.height=3888;
    d.active=Rect{x:0,y:0,w:5184,h:3888};d.crop=d.active;d.lens_reference=d.active;
    d.lens=LensStatus::Missing;
    d.lens_extras=embedded_optics::from_metadata(&EmbeddedLensCorrections {
        tca:EmbeddedOpticsState::Ready(EmbeddedTca::Olympus {coefficients:[1.0,0.0,0.0,-0.5,0.0,0.0]}),
        vignette:EmbeddedOpticsState::Missing,
    });
    assert!(matches!(d.lens_extras.tca,State::Ready(_)),"algebraically invertible before spatial admission");
    d.lens_extras.vignette=vignette().vignette;
    let retained=d.lens_extras.vignette.clone();
    qualify_optics(&mut d);
    assert_eq!(d.lens_extras.tca,State::Unsupported);
    assert_eq!(d.lens_extras.vignette,retained);
    assert!(!enabled(&d));
    assert_eq!(admit_geometry(&d,&Budget::default(),admission()).unwrap().required_disk_bytes,admission().required_disk_bytes);
    // Ordinary small coefficients retain full execution at the same dimensions.
    d.lens_extras.tca=tca().tca;
    qualify_optics(&mut d);
    assert!(matches!(d.lens_extras.tca,State::Ready(_)));
}
