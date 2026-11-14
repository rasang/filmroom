use film_codecs::{lensfun::optics::{TcaCalibration, TcaCorrection, VignetteCalibration,
    VignettingCorrection, interpolate_vignetting}, lensfun_geometry::{Calibration,
    CalibrationPoint, DistortionModel, Geometry}};
use serde_json::Value;

fn data() -> Value {
    serde_json::from_str(include_str!("fixtures/lensfun-0.3.4-optics.json")).unwrap()
}

fn geometry(width: usize, height: usize) -> Geometry {
    let points = [CalibrationPoint { focal_mm: 16.0,
        model: DistortionModel::PtLens { a: 0.0240971, b: -0.0938243, c: -0.0165449 } }];
    Geometry::new(&Calibration { crop_factor: 1.0, aspect_ratio: 1.5,
        center: [0.0, 0.0], points: &points }, 1.0, 16.0, width, height).unwrap()
}

fn calibrations(data: &Value) -> (TcaCalibration, Vec<VignetteCalibration>) {
    let tca = TcaCalibration { focal_mm: 16.0, terms: std::array::from_fn(|i| data["tca_terms"][i].as_f64().unwrap()) };
    let vignette = data["vignette_points"].as_array().unwrap().iter().map(|p| VignetteCalibration {
        focal_mm: p["focal"].as_f64().unwrap(), aperture: p["aperture"].as_f64().unwrap(),
        distance_m: p["distance"].as_f64().unwrap(),
        terms: ["k1", "k2", "k3"].map(|key| p[key].as_f64().unwrap()),
    }).collect();
    (tca, vignette)
}

#[test]
fn native_lensfun_combined_geometry_and_source_gain_oracle() {
    let data = data();
    let (tca, vignette) = calibrations(&data);
    let mut max_coordinate_error = 0.0_f64;
    let mut max_gain_relative_error = 0.0_f64;
    let mut count = 0;
    for case in data["cases"].as_array().unwrap() {
        let g = geometry(case["width"].as_u64().unwrap() as usize, case["height"].as_u64().unwrap() as usize);
        let t = TcaCorrection::new(&g, tca, 16.0).expect("full composite must pass continuous bounds");
        let distance = case["distance_m"].as_f64().unwrap();
        let aperture = case["aperture"].as_f64().unwrap();
        let v = VignettingCorrection::new(&g, &vignette, 1.5, 16.0, aperture, [distance, distance]).unwrap();
        assert_eq!(t.autofill_zoom(), case["auto_scale"].as_f64().unwrap());
        for (i, coefficient) in v.coefficients().into_iter().enumerate() {
            assert!((coefficient as f64 - case["vignetting_coefficients"][i].as_f64().unwrap()).abs() < 0.000001,
                "{} coefficient {i}", case["name"]);
        }
        for point in case["points"].as_array().unwrap() {
            let x = point["output"][0].as_u64().unwrap() as usize;
            let y = point["output"][1].as_u64().unwrap() as usize;
            for channel in 0..3 {
                let p = t.source_position(x, y, channel, 1.0).unwrap();
                for i in 0..2 {
                    let error = (p[i] - point["source_rgb"][channel][i].as_f64().unwrap()).abs();
                    assert!(error < 0.002, "{} ({x},{y}) channel={channel}, axis={i}, error={error}", case["name"]);
                    max_coordinate_error = max_coordinate_error.max(error);
                }
            }
            let gain = v.source_gain(x as f64, y as f64).unwrap();
            let error = (gain / point["source_gain"].as_f64().unwrap() - 1.0).abs();
            assert!(error < 0.0001, "{} ({x},{y}) gain relative error={error}", case["name"]);
            max_gain_relative_error = max_gain_relative_error.max(error);
            count += 1;
        }
    }
    assert_eq!(count, 432);
    eprintln!("Lensfun native optics: {count} points, max coordinate {max_coordinate_error:.9} px, max gain relative {max_gain_relative_error:.9}");
}

#[test]
fn source_vignetting_uses_original_coordinates_and_covers_the_real_active_halo() {
    let (_, points) = calibrations(&data());
    let g = geometry(6720, 4480);
    let v = VignettingCorrection::new(&g, &points, 1.5, 16.0, 2.8, [10.0, 10.0]).unwrap();
    assert!((v.source_gain(3359.5, 2239.5).unwrap() - 1.0).abs() < 1e-10);
    assert!(v.source_gain(0.0, 0.0).unwrap() > 24.0);
    let p = g.source_position(0, 0).unwrap();
    assert!(v.source_gain(p[0], p[1]).unwrap() < v.source_gain(0.0, 0.0).unwrap());
    // Actual EOS R active extent relative to its qualified default crop.
    for x in [-12.0, 6729.0] { for y in [-12.0, 4485.0] {
        assert!((0.25..=64.0).contains(&v.source_gain(x, y).unwrap()));
    }}
    for (x, y) in [(-23.0, 0.0), (0.0, 4502.0), (f64::NAN, 0.0)] {
        assert!(v.source_gain(x, y).is_err());
    }
}

#[test]
fn actual_focus_ranges_are_validated_and_unknown_or_unstable_focus_is_not_guessed() {
    let (_, points) = calibrations(&data());
    let g = geometry(6720, 4480);
    for range in [[5.98, f64::INFINITY], [1.5, 1.99], [f64::INFINITY; 2]] {
        let v = VignettingCorrection::new(&g, &points, 1.5, 16.0, 2.8, range).unwrap();
        assert_eq!(v.focus_range_m, range);
        assert!(v.source_gain(0.0, 0.0).is_ok());
    }
    for range in [[0.0, 1.0], [2.0, 1.0], [f64::NAN, 1.0], [-1.0, f64::INFINITY]] {
        assert!(VignettingCorrection::new(&g, &points, 1.5, 16.0, 2.8, range).is_none());
    }
    let mut uncertain = vec![points[0], points[1]];
    uncertain[0].terms = [-0.1, 0.0, 0.0];
    uncertain[1].terms = [-0.7, 0.0, 0.0];
    assert!(VignettingCorrection::new(&g, &uncertain, 1.5, 16.0, 2.8, [10.0, 1000.0]).is_none());
    for aperture in [0.0, 2.0, 23.0, f64::NAN] {
        assert!(VignettingCorrection::new(&g, &points, 1.5, 16.0, aperture, [10.0, 10.0]).is_none());
    }
    assert!(interpolate_vignetting(&points, 17.0, 2.8, 10.0).is_none());
}

#[test]
fn full_polynomial_bounds_reject_hidden_singularities_and_unbounded_gain() {
    let (_, points) = calibrations(&data());
    let g = geometry(6720, 4480);
    for terms in [[-6.0, 6.0, 0.0], [-0.999, 0.0, 0.0], [f64::NAN, 0.0, 0.0]] {
        let mut bad = points[0]; bad.terms = terms;
        assert!(VignettingCorrection::new(&g, &[bad], 1.5, 16.0, 2.8, [10.0, 10.0]).is_none());
    }
}

#[test]
fn agreeing_focus_endpoints_do_not_hide_an_unsafe_interior_calibration() {
    let g = geometry(6720, 4480);
    let points = [(10.0, 0.0), (12.0, -0.5), (20.0, 0.0), (1000.0, 0.0)]
        .map(|(distance_m, k1)| VignetteCalibration {
            focal_mm: 16.0, aperture: 2.8, distance_m, terms: [k1, 0.0, 0.0],
        });
    assert_eq!(interpolate_vignetting(&points, 16.0, 2.8, 10.0), Some([0.0; 3]));
    assert_eq!(interpolate_vignetting(&points, 16.0, 2.8, 1000.0), Some([0.0; 3]));
    let mid = 2.0 / (1.0 / 10.0 + 1.0 / 1000.0);
    assert!(interpolate_vignetting(&points, 16.0, 2.8, mid).unwrap()[0].abs() < 0.001);
    assert!(VignettingCorrection::new(&g, &points, 1.5, 16.0, 2.8, [10.0, 1000.0]).is_none());
}

#[test]
fn subpixel_mapping_rejects_folds_out_of_bounds_and_double_zoom_misuse() {
    let (tca, _) = calibrations(&data());
    let g = geometry(6720, 4480);
    for terms in [[2.0, 2.0, 0.0, 0.0, 0.0, 0.0], [1.0, 1.0, 0.0, 0.0, -0.5, -0.5],
        [1.0, f64::NAN, 0.0, 0.0, 0.0, 0.0]] {
        assert!(TcaCorrection::new(&g, TcaCalibration { focal_mm: 16.0, terms }, 16.0).is_none());
    }
    assert!(TcaCorrection::new(&g, tca, 17.0).is_none());
    let t = TcaCorrection::new(&g, tca, 16.0).unwrap();
    assert!(t.source_position(0, 0, 3, 1.0).is_err());
    for zoom in [0.9, f64::NAN, 9.0] { assert!(t.source_position(0, 0, 0, zoom).is_err()); }
    for zoom in [1.0, 1.02, 2.0, 8.0] {
        for channel in 0..3 { for (x, y) in [(0, 0), (6719, 0), (3359, 2239), (0, 4479), (6719, 4479)] {
            let p = t.source_position(x, y, channel, zoom).unwrap();
            assert!((0.0..=6719.0).contains(&p[0]) && (0.0..=4479.0).contains(&p[1]));
        }}
    }
}
