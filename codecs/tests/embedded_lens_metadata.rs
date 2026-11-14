//! Optional real-file regression tests. Originals are CC0 raw.pixls.us samples
//! and deliberately not committed. Run with FILM_LENS_FIXTURES=/path/to/fixtures
//! cargo test --test embedded_lens_metadata -- --ignored --nocapture.
//!
//! Expected values were independently read from the TIFF/RAF/MakerNote bytes,
//! not captured from this decoder's output.

use film_codecs::embedded_lens::{LensStatus, from_metadata};
use rawler::{RawImage, rawimage::EmbeddedLensDistortion, rawsource::RawSource};
use std::path::PathBuf;

// Cargo does not execute a dependency's unit tests. Compile this small, actual
// vendored parsing module into the integration-test crate so its malformed-tag
// tests run in ordinary Filmroom CI too, without exposing a new decoder API.
mod formats {
    pub mod tiff {
        pub use rawler::formats::tiff::{Rational, SRational, Value};
    }
}
#[path = "../vendor/rawler-0.7.2/src/embedded_lens_metadata.rs"]
mod metadata_parser;

fn metadata(name: &str) -> RawImage {
    let path = PathBuf::from(std::env::var_os("FILM_LENS_FIXTURES")
        .expect("set FILM_LENS_FIXTURES to the downloaded CC0 RAW directory"))
        .join(name);
    let source = RawSource::new(&path).unwrap();
    let raw = rawler::decode_dummy(&source).unwrap();
    eprintln!("{name}: encoded {}x{}, active {:?}, crop {:?}, distortion {:?}",
        raw.width, raw.height, raw.active_area, raw.crop_area, raw.lens_distortion);
    raw
}

fn ready_nonidentity(metadata: &EmbeddedLensDistortion) {
    let LensStatus::Ready(model) = from_metadata(metadata) else {
        panic!("real nonzero metadata must be admitted: {metadata:?}");
    };
    assert!(!model.is_identity());
    let position = model.source_position(1200, 800, 1000, 400).unwrap();
    assert!((position[0] - 1000.0).abs() > 0.01);
    assert_eq!(position[1], 400.0);
}

#[test]
#[ignore = "requires raw.pixls.us CC0 sample 6736"]
fn sony_a6700_padded_distortion_table_is_retained() {
    // SHA256 dc3e2ed22f46fcef778553767e0703bc37cfb043f107afe2d7e6e4dbca34200a
    let raw = metadata("6736_ILCE-6700.ARW");
    assert_eq!(raw.lens_distortion, EmbeddedLensDistortion::Sony {
        samples: vec![0, -29, -109, -245, -424, -640, -881, -1135, -1390, -1631, -1829],
    });
    ready_nonidentity(&raw.lens_distortion);
}

#[test]
#[ignore = "requires raw.pixls.us CC0 sample 1178"]
fn fuji_x_t20_signed_rational_source_spline_is_retained() {
    // SHA256 a23045101b2912e64f154100d646c228c0d16585d21e95e0d1a006e537f7784c
    let raw = metadata("1178_X-T20.RAF");
    assert_eq!(raw.lens_distortion, EmbeddedLensDistortion::Fuji {
        knots: (0..11).map(|i| i as f64 / 10.0).collect(),
        distortion: vec![0.0, 0.114, 0.249, 0.427, 0.667, 0.976, 1.352, 1.818, 2.379, 3.042, 3.837],
        crop_factor: 1.0,
    });
    ready_nonidentity(&raw.lens_distortion);
}

#[test]
#[ignore = "requires raw.pixls.us CC0 sample 1993"]
fn olympus_e_m1ii_relative_makernote_ifd_is_retained_even_with_camera_setting_off() {
    // SHA256 0b433019e5fa61548d7eb0eb7bb90894251542c056da72355202382696d0ceb8
    let raw = metadata("1993_E-M1MarkII.ORF");
    assert_eq!(raw.lens_distortion, EmbeddedLensDistortion::Olympus {
        coefficients: [-0.007392614148557186, 0.008197912015020847,
            -0.0026901846285909414, 0.9997931718826294],
    });
    ready_nonidentity(&raw.lens_distortion);
}
