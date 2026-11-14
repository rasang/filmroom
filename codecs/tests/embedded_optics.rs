//! Actual public parameter records, independent numeric oracle and optional
//! original-file decoder handoff. No original RAW or executable is committed.
use film_codecs::embedded_lens::{self, LensStatus};
use film_codecs::embedded_optics::{self, State};
use rawler::rawimage::{EmbeddedLensCorrections, EmbeddedLensDistortion, EmbeddedOpticsState, EmbeddedTca, EmbeddedVignette};
use serde_json::Value;

// Dependency unit tests are not run by Cargo. Include the actual small vendor
// parser so its malformed-input tests also run in Filmroom CI.
mod formats {
    pub mod tiff {
        pub use rawler::formats::tiff::{Rational, SRational, Value};
    }
}
mod rawimage {
    pub use rawler::rawimage::EmbeddedLensDistortion;
}
#[path = "../vendor/rawler-0.7.2/src/embedded_optics_metadata.rs"]
mod metadata_parser;

fn fixtures() -> Value {
    serde_json::from_str(include_str!("public-fixtures/embedded-optics-numerical.json")).unwrap()
}

fn numbers(value: &Value) -> Vec<f64> {
    value.as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect()
}

fn shorts(value: &Value) -> Vec<i16> {
    value.as_array().unwrap().iter().map(|v| i16::try_from(v.as_i64().unwrap()).unwrap()).collect()
}

fn distortion(row: &Value) -> EmbeddedLensDistortion {
    let values = numbers(&row["tags"]["distortion"]["values"]);
    match row["brand"].as_str().unwrap() {
        "sony" => EmbeddedLensDistortion::Sony { samples: values[1..=values[0] as usize].iter().map(|v| *v as i16).collect() },
        "fuji" => {
            let count = (values.len() - 1) / 2;
            EmbeddedLensDistortion::Fuji { knots: values[1..=count].to_vec(), distortion: values[count + 1..].to_vec(),
                crop_factor: row["model_parameters"]["tca"]["crop_factor"].as_f64().unwrap() }
        }
        "olympus" => EmbeddedLensDistortion::Olympus { coefficients: values.try_into().unwrap() },
        _ => panic!("unknown fixture brand"),
    }
}

fn metadata(row: &Value) -> EmbeddedLensCorrections {
    let p = &row["model_parameters"]["tca"];
    let v = &row["model_parameters"]["vignette"];
    let (tca, vignette) = match row["brand"].as_str().unwrap() {
        "sony" => (EmbeddedTca::Sony { red: shorts(&p["red"]), blue: shorts(&p["blue"]), distortion: Some(shorts(&p["distortion"])) },
            EmbeddedOpticsState::Ready(EmbeddedVignette::Sony { samples: shorts(&v["samples"]) })),
        "fuji" => (EmbeddedTca::Fuji { knots: numbers(&p["knots"]), red: numbers(&p["red"]), blue: numbers(&p["blue"]), crop_factor: p["crop_factor"].as_f64().unwrap() },
            EmbeddedOpticsState::Ready(EmbeddedVignette::Fuji { knots: numbers(&v["knots"]), levels: numbers(&v["levels"]), crop_factor: v["crop_factor"].as_f64().unwrap() })),
        "olympus" => (EmbeddedTca::Olympus { coefficients: numbers(&p["coefficients"]).try_into().unwrap() }, EmbeddedOpticsState::Missing),
        _ => panic!("unknown fixture brand"),
    };
    EmbeddedLensCorrections { tca: EmbeddedOpticsState::Ready(tca), vignette }
}

fn tiff_value(record: &Value) -> rawler::formats::tiff::Value {
    use rawler::formats::tiff::{SRational, Value as TiffValue};
    match record["type"].as_u64().unwrap() {
        8 => TiffValue::SShort(shorts(&record["raw_numeric"])),
        10 => TiffValue::SRational(record["raw_numeric"].as_array().unwrap().iter().map(|v| {
            SRational::new(v[0].as_i64().unwrap() as i32, v[1].as_i64().unwrap() as i32)
        }).collect()),
        11 => TiffValue::Float(numbers(&record["raw_numeric"]).iter().map(|v| *v as f32).collect()),
        _ => panic!("unexpected public fixture TIFF encoding"),
    }
}

#[test]
fn real_counted_float_and_rational_tag_records_reach_independent_states() {
    for row in fixtures()["samples"].as_array().unwrap() {
        let tags = &row["tags"];
        let ca = tiff_value(&tags["tca"]);
        let vignette = (!tags["vignette"].is_null()).then(|| tiff_value(&tags["vignette"]));
        let crop = row["crop_mode"].as_u64().map(|v| rawler::formats::tiff::Value::Short(vec![v as u16]));
        let parsed = match row["brand"].as_str().unwrap() {
            "sony" => metadata_parser::EmbeddedLensCorrections::sony(Some(&ca), vignette.as_ref(), &distortion(row)),
            "fuji" => metadata_parser::EmbeddedLensCorrections::fuji(Some(&ca), vignette.as_ref(), crop.as_ref(), &distortion(row)),
            "olympus" => metadata_parser::EmbeddedLensCorrections::olympus(Some(&ca)),
            _ => unreachable!(),
        };
        // Same source module, deliberately compiled as an independent test
        // type. Debug compares all variant names, counts and numeric values.
        assert_eq!(format!("{parsed:?}"), format!("{:?}", metadata(row)), "{}", row["filename"]);
    }
}

#[test]
fn real_models_match_independent_direct_and_bisected_oracle() {
    let mut coordinates = 0;
    let mut gains = 0;
    for row in fixtures()["samples"].as_array().unwrap() {
        let extras = embedded_optics::from_metadata(&metadata(row));
        let State::Ready(tca) = &extras.tca else { panic!("{} TCA rejected", row["filename"]); };
        let width = row["reference_dimensions"][0].as_u64().unwrap() as usize;
        let height = row["reference_dimensions"][1].as_u64().unwrap() as usize;
        for case in row["tca_cases"].as_array().unwrap() {
            let source = numbers(&case["source_after_distortion"]);
            for channel in 0..3 {
                let actual = tca.source_position_after_distortion(width, height, source[0], source[1], channel, true).unwrap();
                let expected = numbers(&case["channel_sources"][channel]);
                for axis in 0..2 {
                    assert!((actual[axis] - expected[axis]).abs() < 2e-9,
                        "{} channel {channel} axis {axis}: {} != {}", row["filename"], actual[axis], expected[axis]);
                    coordinates += 1;
                }
            }
        }
        if row["brand"] == "olympus" {
            assert_eq!(extras.vignette, State::Missing);
        } else {
            let State::Ready(vignette) = &extras.vignette else { panic!("{} vignette rejected", row["filename"]); };
            for case in row["vignette_cases"].as_array().unwrap() {
                let position = numbers(&case["position"]);
                let actual = vignette.source_gain(width, height, position[0], position[1]).unwrap();
                let expected = case["gain"].as_f64().unwrap();
                assert!((actual - expected).abs() < 2e-12, "{} gain {actual} != {expected}", row["filename"]);
                gains += 1;
            }
        }
    }
    assert_eq!((coordinates, gains), (150, 16));
}

#[test]
fn real_combined_autofill_keeps_all_rgb_channels_inside_without_tca_clamp() {
    for row in fixtures()["samples"].as_array().unwrap() {
        let extras = embedded_optics::from_metadata(&metadata(row));
        let State::Ready(tca) = extras.tca else { panic!("TCA rejected"); };
        let LensStatus::Ready(distortion) = embedded_lens::from_metadata(&distortion(row)) else { panic!("distortion rejected"); };
        let (width, height) = (480, 320);
        let zoom = distortion.autofill_zoom() * tca.autofill_zoom();
        for y in (0..height).step_by(7).chain(std::iter::once(height - 1)) {
            for x in (0..width).step_by(11).chain(std::iter::once(width - 1)) {
                let source = distortion.source_position_with_zoom(width, height, x, y, zoom).unwrap();
                for channel in 0..3 {
                    let p = tca.source_position_after_distortion(width, height, source[0], source[1], channel, true).unwrap();
                    assert!(p[0] >= -1e-9 && p[0] <= (width - 1) as f64 + 1e-9
                        && p[1] >= -1e-9 && p[1] <= (height - 1) as f64 + 1e-9,
                        "{} channel {channel}: out of bounds {p:?}", row["filename"]);
                }
            }
        }
    }
}

#[test]
#[ignore = "requires original CC0 files; set FILM_OPTICS_FIXTURES to a directory containing the five filenames"]
fn real_raw_decoder_handoff_matches_independently_read_parameters() {
    let directory = std::path::PathBuf::from(std::env::var_os("FILM_OPTICS_FIXTURES").expect("set FILM_OPTICS_FIXTURES"));
    for row in fixtures()["samples"].as_array().unwrap() {
        let filename = row["filename"].as_str().unwrap();
        let path = directory.join(filename);
        let source = rawler::rawsource::RawSource::new(&path).unwrap();
        let raw = rawler::decode_dummy(&source).unwrap();
        assert_eq!(raw.lens_corrections, metadata(row), "{filename}");
        let extras = embedded_optics::from_metadata(&raw.lens_corrections);
        assert!(matches!(extras.tca, State::Ready(_)), "{filename}");
        eprintln!("{filename}: {}x{}, active {:?}, crop {:?}, optics {:?}", raw.width, raw.height, raw.active_area, raw.crop_area, raw.lens_corrections);
    }
}
