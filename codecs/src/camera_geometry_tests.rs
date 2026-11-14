//! Integration checks for the shared vendor sink, independent of coefficient
//! parser/model unit tests. Inputs deliberately have a shifted active rectangle
//! and a smaller off-center final crop.
use super::*;
use rawler::{pixarray::Color2D, rawimage::EmbeddedLensDistortion};
use std::{path::PathBuf, sync::atomic::AtomicU64};

#[path = "camera_optics_tests.rs"]
mod optics_tests;

struct Scratch(PathBuf);
impl Scratch {
    fn new() -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "film-camera-geometry-{}-{}",
            std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
    fn output(&self) -> (PathBuf, File) {
        let path = self.0.join("linear.rgb");
        let file = OpenOptions::new().read(true).write(true).create_new(true)
            .open(&path).unwrap();
        (path, file)
    }
}
impl Drop for Scratch {
    fn drop(&mut self) { let _ = std::fs::remove_dir_all(&self.0); }
}

fn lens(sample: i16) -> LensStatus {
    crate::embedded_lens::from_metadata(&EmbeddedLensDistortion::Sony {
        samples: vec![sample; 11],
    })
}

fn development() -> Development<'static> {
    Development {
        width: 160, height: 112, cpp: 3, orientation: 1,
        active: Rect { x: 6, y: 8, w: 146, h: 94 },
        crop: Rect { x: 17, y: 15, w: 115, h: 73 },
        lens_reference: Rect { x: 6, y: 8, w: 146, h: 94 },
        cfa: String::new(), wb: [1.35, 1.0, 1.15, 1.0],
        matrix: vec![0.64, 0.28, 0.08, 0.15, 0.78, 0.07, 0.03, 0.11, 0.86],
        baseline_exposure_ev: 0.5, gain_maps: &[],
        lens_extras: Default::default(),
        lens_profile: None,
        // A radius-varying model remains nonzero after automatic fill zoom.
        lens: crate::embedded_lens::from_metadata(&EmbeddedLensDistortion::Sony {
            samples: vec![0, -29, -109, -245, -424, -640, -881, -1135, -1390, -1631, -1829],
        }),
    }
}

fn camera_pixel(x: usize, y: usize) -> [f32; 3] {
    let edge = if (x / 5 + y / 7) % 2 == 0 { 0.04 } else { 0.0 };
    [0.04 + x as f32 * 0.001 + edge,
     0.05 + y as f32 * 0.002,
     0.03 + (x + y) as f32 * 0.0008 + edge]
}

fn samples(d: &Development, top: usize, bottom: usize) -> Vec<f32> {
    let pattern = if d.cpp == 1 { Some(CFA::new(&d.cfa)) } else { None };
    let mut values = Vec::with_capacity(d.active.w * (bottom - top) * d.cpp);
    for y in top..bottom { for x in 0..d.active.w {
        let pixel = camera_pixel(x, y - d.active.y);
        if let Some(pattern) = &pattern {
            values.push(pixel[pattern.color_at(y - d.active.y, x)]);
        } else {
            values.extend_from_slice(&pixel);
        }
    }}
    values
}

fn render_strips(d: &Development, rows: usize) -> Vec<u8> {
    let scratch = Scratch::new();
    let (path, out) = scratch.output();
    out.set_len((d.crop.w * d.crop.h * 12) as u64).unwrap();
    let cancel = AtomicBool::new(false);
    let sink = CameraRgbSink::new(d, &path).unwrap();
    let region = sink.region(d);
    let mut first = region.y;
    while first < region.y + region.h {
        let last = (first + rows).min(region.y + region.h);
        let top = first.saturating_sub(HALO).max(d.active.y);
        let bottom = (last + HALO).min(d.active.y + d.active.h);
        let pixels = sink.develop(d, top, bottom - top, samples(d, top, bottom), &cancel).unwrap();
        sink.emit_strip(d, &out, &pixels, top, first, last, &cancel).unwrap();
        first = last;
    }
    sink.finish(d, &out, &cancel).unwrap();
    let bytes = std::fs::read(path).unwrap();
    assert!(bytes.chunks_exact(4).all(|v| f32::from_le_bytes(v.try_into().unwrap()).is_finite()));
    bytes
}

// This oracle indexes destination -> source and does not call the production
// source -> destination `mapped` helper. It crops an already corrected image.
fn crop_and_orient(full: &[u8], d: &Development, reference: Rect) -> Vec<u8> {
    let (width, height) = d.oriented_dim();
    let mut expected = Vec::with_capacity(width * height * 12);
    for y in 0..height { for x in 0..width {
        let (cx, cy) = match d.orientation {
            1 => (x, y),
            2 => (d.crop.w - 1 - x, y),
            3 => (d.crop.w - 1 - x, d.crop.h - 1 - y),
            4 => (x, d.crop.h - 1 - y),
            5 => (y, x),
            6 => (y, d.crop.h - 1 - x),
            7 => (d.crop.w - 1 - y, d.crop.h - 1 - x),
            8 => (d.crop.w - 1 - y, x),
            _ => unreachable!(),
        };
        let index = ((cy + d.crop.y - reference.y) * reference.w
            + cx + d.crop.x - reference.x) * 12;
        expected.extend_from_slice(&full[index..index + 12]);
    }}
    expected
}

#[test]
fn nonzero_geometry_precedes_crop_and_all_eight_orientations_for_every_strip_size() {
    for domain in [development().active, Rect { x: 12, y: 12, w: 134, h: 82 }] {
        let mut full = development();
        full.lens_reference = domain; full.crop = domain;
        let reference = render_strips(&full, 128);
        let mut uncorrected = full.clone();
        uncorrected.lens = LensStatus::Missing;
        assert_ne!(reference, render_strips(&uncorrected, 128));

        for orientation in 1..=8 {
            let mut cropped = development();
            cropped.lens_reference = domain; cropped.orientation = orientation;
            let expected = crop_and_orient(&reference, &cropped, domain);
            for rows in [8, 31, 128] {
                assert_eq!(render_strips(&cropped, rows), expected,
                    "reference {domain:?}, orientation {orientation}, rows {rows}");
            }
        }
        let mut translated = full.clone();
        translated.lens_reference.x -= translated.active.x;
        translated.lens_reference.y -= translated.active.y;
        translated.active.x = 0; translated.active.y = 0;
        translated.crop = translated.lens_reference;
        assert_eq!(render_strips(&translated, 31), reference,
            "a sensor padding offset must not shift the optical center");
    }
}

#[test]
fn lensfun_uses_its_visible_reference_then_crops_and_orients_exactly_once() {
    let mut full = development();
    full.lens_reference = Rect { x: 12, y: 12, w: 134, h: 82 };
    full.crop = full.lens_reference;
    full.lens = LensStatus::Profile(crate::lensfun::rf16_for_test(full.crop.w, full.crop.h));
    let reference = render_strips(&full, 128);
    let mut plain = full.clone(); plain.lens = LensStatus::Missing;
    assert_ne!(reference, render_strips(&plain, 128));

    for orientation in 1..=8 {
        let mut cropped = full.clone();
        cropped.crop = development().crop;
        cropped.orientation = orientation;
        let expected = crop_and_orient(&reference, &cropped, full.lens_reference);
        for rows in [8,31,128] {
            assert_eq!(render_strips(&cropped, rows), expected,
                "Lensfun orientation {orientation}, rows {rows}");
        }
    }
    let mut translated = full.clone();
    translated.lens_reference.x -= translated.active.x;
    translated.lens_reference.y -= translated.active.y;
    translated.active.x = 0; translated.active.y = 0;
    translated.crop = translated.lens_reference;
    assert_eq!(render_strips(&translated,31),reference,
        "encoded sensor padding cannot change the Lensfun optical center");
}

#[test]
fn lensfun_reference_dimension_mismatch_is_rejected_before_spooling() {
    let mut d = development();
    d.lens_reference = d.crop;
    d.lens = LensStatus::Profile(crate::lensfun::rf16_for_test(d.crop.w + 1, d.crop.h));
    let scratch = Scratch::new();
    assert_eq!(CameraRgbSink::new(&d, &scratch.0.join("linear.rgb")).err().unwrap().code,
        "INVALID_INPUT");
    assert_eq!(std::fs::read_dir(&scratch.0).unwrap().count(),0);
}

#[test]
fn lensfun_admission_and_cancellation_share_the_bounded_camera_backing() {
    let mut d = development();
    d.lens_reference = d.crop;
    d.lens = LensStatus::Profile(crate::lensfun::rf16_for_test(d.crop.w,d.crop.h));
    let mut b = Budget::default();
    b.disk_budget_bytes = admission().required_disk_bytes + (d.active.w*d.active.h*12) as u64;
    let admitted = admit_geometry(&d,&b,admission()).unwrap();
    assert_eq!(admitted.required_disk_bytes,b.disk_budget_bytes);
    assert_eq!(admitted.estimated_peak_bytes,BASE + GEOMETRY_SCRATCH);
    b.disk_budget_bytes -= 1;
    assert_eq!(admit_geometry(&d,&b,admission()).unwrap_err().code,"DISK_BUDGET");
    let scratch = Scratch::new(); let (path,out) = scratch.output();
    let sink = CameraRgbSink::new(&d,&path).unwrap();
    assert!(sink.camera.is_some());
    assert_eq!(std::fs::read_dir(&scratch.0).unwrap().count(),1,
        "RGB backing must be anonymous while the output is the only directory entry");
    assert_eq!(sink.finish(&d,&out,&AtomicBool::new(true)).unwrap_err().code,"CANCELLED");
}

#[test]
fn bayer_and_xtrans_demosaic_halos_are_stable_before_geometry() {
    for cfa in ["RGGB", "GRGGBGBGBRGRGRGGBGGBGGRGRGRBGBGBGGRG"] {
        let mut d = development(); d.cpp = 1; d.cfa = cfa.into();
        let full = render_strips(&d, 128);
        for rows in [8, 17, 31] {
            assert_eq!(render_strips(&d, rows), full, "CFA {cfa}, rows {rows}");
        }
    }
}

#[test]
fn independently_decoded_rgb_tiles_match_strip_sink() {
    let d = development();
    let reference = render_strips(&d, 31);
    let scratch = Scratch::new(); let (path, out) = scratch.output();
    out.set_len((d.crop.w * d.crop.h * 12) as u64).unwrap();
    let cancel = AtomicBool::new(false);
    let sink = CameraRgbSink::new(&d, &path).unwrap();
    for y in (0..d.active.h).step_by(19) { for x in (0..d.active.w).step_by(43) {
        let w = 43.min(d.active.w - x); let h = 19.min(d.active.h - y);
        let values = (0..h).flat_map(|row| (0..w).map(move |col| camera_pixel(x + col, y + row))).collect();
        let pixels = Color2D::new_with(values, w, h);
        sink.emit_region(&d, &out, &pixels,
            Rect { x: x + d.active.x, y: y + d.active.y, w, h }, &cancel).unwrap();
    }}
    sink.finish(&d, &out, &cancel).unwrap();
    assert_eq!(std::fs::read(path).unwrap(), reference);
}

#[test]
fn identity_and_unavailable_metadata_keep_the_existing_pixels_and_skip_backing() {
    for orientation in [1, 6, 8] {
        let mut d = development(); d.orientation = orientation; d.lens = LensStatus::Missing;
        let reference = render_strips(&d, 31);
        for status in [LensStatus::Missing, LensStatus::Unsupported, lens(0)] {
            d.lens = status;
            let scratch = Scratch::new();
            let sink = CameraRgbSink::new(&d, &scratch.0.join("linear.rgb")).unwrap();
            assert!(sink.camera.is_none());
            let region = sink.region(&d);
            assert_eq!((region.x, region.y, region.w, region.h),
                (d.crop.x, d.crop.y, d.crop.w, d.crop.h));
            assert_eq!(std::fs::read_dir(&scratch.0).unwrap().count(), 0);
            assert_eq!(render_strips(&d, 31), reference);
        }
    }
}

#[test]
fn camera_values_reach_geometry_before_color_and_exposure_is_applied_once() {
    let d = development();
    let scratch = Scratch::new(); let (path, out) = scratch.output();
    out.set_len((d.crop.w * d.crop.h * 12) as u64).unwrap();
    let cancel = AtomicBool::new(false);
    let sink = CameraRgbSink::new(&d, &path).unwrap();
    // Non-neutral WB/exposure plus super-white and negative camera values make
    // an accidental early color transform or clipping observable.
    let color = [-0.2, 0.4, 1.4];
    let data: Vec<f32> = std::iter::repeat_n(color, d.active.w * d.active.h).flatten().collect();
    let pixels = sink.develop(&d, d.active.y, d.active.h, data.clone(), &cancel).unwrap();
    assert!(pixels.data.iter().all(|p| *p == color));
    let expected = develop_tile_cancel(&d, d.active.y, d.active.h, data, &cancel).unwrap().data[0];
    sink.emit_strip(&d, &out, &pixels, d.active.y, d.active.y, d.active.y + d.active.h, &cancel).unwrap();
    sink.finish(&d, &out, &cancel).unwrap();
    for pixel in std::fs::read(path).unwrap().chunks_exact(12) {
        for c in 0..3 {
            let value = f32::from_le_bytes(pixel[c * 4..c * 4 + 4].try_into().unwrap());
            assert!((value - expected[c]).abs() < 2e-6, "channel {c}: {value} vs {}", expected[c]);
        }
    }
}

fn admission() -> Admission {
    Admission { memory_budget_bytes: 128 * MIB, estimated_peak_bytes: BASE + 2 * MIB,
        required_disk_bytes: 700_000_000, strip_rows: 64 }
}

#[test]
fn geometry_admission_charges_backing_and_scratch_against_actual_budgets() {
    let mut d = development();
    d.width = 10_000; d.height = 5_000;
    d.active = Rect { x: 0, y: 0, w: d.width, h: d.height }; d.crop = d.active;
    d.lens_reference = d.active;
    let mut budget = Budget::default(); budget.disk_budget_bytes = 1_300_000_000;
    let admitted = admit_geometry(&d, &budget, admission()).unwrap();
    assert_eq!(admitted.required_disk_bytes, 1_300_000_000);
    assert!(admitted.required_disk_bytes > 1024 * MIB);
    assert_eq!(admitted.estimated_peak_bytes, BASE + GEOMETRY_SCRATCH);
    assert_eq!(admitted.strip_rows, 64);
    budget.disk_budget_bytes -= 1;
    assert_eq!(admit_geometry(&d, &budget, admission()).unwrap_err().code, "DISK_BUDGET");
    budget.disk_budget_bytes += 1;
    let mut memory = admission(); memory.memory_budget_bytes = BASE + GEOMETRY_SCRATCH - 1;
    assert_eq!(admit_geometry(&d, &budget, memory).unwrap_err().code, "MEMORY_BUDGET");
    let mut overflow = admission(); overflow.required_disk_bytes = u64::MAX;
    assert_eq!(admit_geometry(&d, &budget, overflow).unwrap_err().code, "DISK_BUDGET");
    for status in [LensStatus::Missing, LensStatus::Unsupported, lens(0)] {
        d.lens = status;
        let unchanged = admit_geometry(&d, &budget, admission()).unwrap();
        assert_eq!(unchanged.required_disk_bytes, admission().required_disk_bytes);
        assert_eq!(unchanged.estimated_peak_bytes, admission().estimated_peak_bytes);
    }
}

#[test]
fn cancellation_releases_anonymous_camera_backing_and_partial_output() {
    let d = development();
    let scratch = Scratch::new(); let (path, out) = scratch.output();
    let open_backings = || std::fs::read_dir("/proc/self/fd").unwrap()
        .filter_map(|entry| std::fs::read_link(entry.ok()?.path()).ok())
        .filter(|target| target.starts_with(&scratch.0)
            && target.file_name().is_some_and(|name| name.to_string_lossy().starts_with(".raw-camera-")))
        .count();
    let guard = OutputGuard { path: &path, keep: false };
    let cancel = AtomicBool::new(false);
    let sink = CameraRgbSink::new(&d, &path).unwrap();
    // The camera backing is unlinked immediately, including while alive.
    assert_eq!(std::fs::read_dir(&scratch.0).unwrap().count(), 1);
    assert_eq!(open_backings(), 1);
    let pixels = sink.develop(&d, d.active.y, d.active.h,
        samples(&d, d.active.y, d.active.y + d.active.h), &cancel).unwrap();
    sink.emit_strip(&d, &out, &pixels, d.active.y, d.active.y,
        d.active.y + d.active.h, &cancel).unwrap();
    cancel.store(true, Ordering::Release);
    assert_eq!(sink.finish(&d, &out, &cancel).unwrap_err().code, "CANCELLED");
    assert_eq!(open_backings(), 0);
    drop(out); drop(guard);
    assert!(!path.exists());
    assert_eq!(std::fs::read_dir(&scratch.0).unwrap().count(), 0);
}
