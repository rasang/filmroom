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
