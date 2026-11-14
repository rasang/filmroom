use film_codecs::lensfun_geometry::{Calibration, CalibrationPoint, DistortionModel, Geometry};
use serde_json::Value;

fn fixture() -> Value {
    serde_json::from_str(include_str!("fixtures/lensfun-0.3.4-points.json")).unwrap()
}

fn points(value: &Value) -> Vec<CalibrationPoint> {
    value.as_array().unwrap().iter().map(|point| {
        let terms: Vec<f64> = point["terms"].as_array().unwrap().iter().map(|x| x.as_f64().unwrap()).collect();
        let model = match point["model"].as_str().unwrap() {
            "ptlens" => DistortionModel::PtLens { a: terms[0], b: terms[1], c: terms[2] },
            "poly3" => DistortionModel::Poly3 { k1: terms[0] },
            "poly5" => DistortionModel::Poly5 { k1: terms[0], k2: terms[1] },
            other => panic!("unexpected model {other}"),
        };
        CalibrationPoint { focal_mm: point["focal_mm"].as_f64().unwrap(), model }
    }).collect()
}

#[test]
fn points_and_scale_match_independent_native_lensfun_0_3_4() {
    let data = fixture();
    let mut worst_error = 0.0_f64;
    let mut checked = 0;
    for case in data["cases"].as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        let p = points(&case["calibration"]["points"]);
        let calibration = Calibration {
            crop_factor: case["calibration"]["crop_factor"].as_f64().unwrap(),
            aspect_ratio: case["calibration"]["aspect_ratio"].as_f64().unwrap(),
            center: [case["calibration"]["center"][0].as_f64().unwrap(), case["calibration"]["center"][1].as_f64().unwrap()],
            points: &p,
        };
        let width = case["width"].as_u64().unwrap() as usize;
        let height = case["height"].as_u64().unwrap() as usize;
        let geometry = Geometry::new(&calibration, case["camera_crop"].as_f64().unwrap(), case["focal_mm"].as_f64().unwrap(), width, height)
            .unwrap_or_else(|| panic!("rejected reference case {name}"));
        let scale_error = (geometry.autofill_zoom() - case["auto_scale"].as_f64().unwrap()).abs();
        assert!(scale_error <= 0.000002, "{name}: auto-scale error {scale_error}");
        for point in case["points"].as_array().unwrap() {
            let x = point["output"][0].as_u64().unwrap() as usize;
            let y = point["output"][1].as_u64().unwrap() as usize;
            let actual = geometry.source_position(x, y).unwrap();
            for (axis, value) in actual.into_iter().enumerate() {
                let error = (value - point["source"][axis].as_f64().unwrap()).abs();
                worst_error = worst_error.max(error);
                assert!(error <= 0.001, "{name} ({x},{y}) axis {axis}: {value}, reference {}, error {error}", point["source"][axis]);
            }
            checked += 1;
        }
        assert!(geometry.source_position(width, 0).is_err());
        assert!(geometry.source_position(0, height).is_err());
        assert_eq!(geometry.dimensions(), [width, height]);
    }
    eprintln!("Lensfun oracle: {checked} points; max coordinate error {worst_error:.9} px");
    assert!(checked >= 800);
}

#[test]
fn real_focal_is_irrelevant_without_projection_conversion() {
    let data = fixture();
    let cases = data["cases"].as_array().unwrap();
    let without = cases.iter().find(|v| v["name"] == "without-real-focal").unwrap();
    let with = cases.iter().find(|v| v["name"] == "with-real-focal").unwrap();
    assert_eq!(without["points"], with["points"]);
    assert_eq!(without["auto_scale"], with["auto_scale"]);
}

#[test]
fn malformed_folding_ambiguous_and_out_of_range_calibrations_fail_closed() {
    let valid = CalibrationPoint { focal_mm: 16.0, model: DistortionModel::PtLens { a: 0.0240971, b: -0.0938243, c: -0.0165449 } };
    let p = [valid];
    let calibration = Calibration { crop_factor: 1.0, aspect_ratio: 1.5, center: [0.0; 2], points: &p };
    assert!(Geometry::new(&calibration, 1.0, 16.0, 6720, 4480).is_some());
    for crop in [f64::NAN, f64::INFINITY, 0.0, -1.0] {
        assert!(Geometry::new(&calibration, crop, 16.0, 6720, 4480).is_none());
    }
    for focal in [f64::NAN, 0.0, 15.0, 17.0] {
        assert!(Geometry::new(&calibration, 1.0, focal, 6720, 4480).is_none());
    }
    for (width, height) in [(0, 5), (4, 5), (32769, 5), (10000, 10000)] {
        assert!(Geometry::new(&calibration, 1.0, 16.0, width, height).is_none());
    }
    // The native eight-ray auto-scale ignores optical-center offsets. The
    // complete boundary check must reject this case instead of extending the
    // bottom edge, even though the model itself is smooth and non-folding.
    assert!(Geometry::new(&Calibration { center: [0.0, 0.2], ..calibration }, 1.0, 16.0, 6720, 4480).is_none());
    for invalid in [
        vec![],
        vec![valid, valid],
        vec![CalibrationPoint { model: DistortionModel::Poly3 { k1: f64::NAN }, ..valid }],
        vec![CalibrationPoint { model: DistortionModel::Poly3 { k1: -0.5 }, ..valid }],
        // This remains positive as a factor, but its radial derivative dips
        // below zero between ordinary radial grid samples.
        vec![CalibrationPoint { model: DistortionModel::PtLens { a: 0.0, b: 3.0, c: -2.9 }, ..valid }],
        vec![valid, CalibrationPoint { focal_mm: 20.0, model: DistortionModel::Poly3 { k1: 0.01 } }],
        vec![CalibrationPoint { focal_mm: 20.0, ..valid }, valid],
    ] {
        assert!(Geometry::new(&Calibration { points: &invalid, ..calibration }, 1.0, 16.0, 6720, 4480).is_none(), "accepted {invalid:?}");
    }
}

#[test]
fn continuous_geometry_boxes_and_jacobian_cover_native_models() {
    for case in fixture()["cases"].as_array().unwrap() {
        let rows = points(&case["calibration"]["points"]);
        let calibration = Calibration {
            crop_factor: case["calibration"]["crop_factor"].as_f64().unwrap(),
            aspect_ratio: case["calibration"]["aspect_ratio"].as_f64().unwrap(),
            center: std::array::from_fn(|i| case["calibration"]["center"][i].as_f64().unwrap()),
            points: &rows,
        };
        let w = case["width"].as_u64().unwrap() as usize;
        let h = case["height"].as_u64().unwrap() as usize;
        let g = Geometry::new_general(&calibration, case["camera_crop"].as_f64().unwrap(),
            case["focal_mm"].as_f64().unwrap(), w, h).unwrap();
        let jacobian = g.sampling_jacobian_bound().unwrap();
        for point in case["points"].as_array().unwrap() {
            let x = point["output"][0].as_u64().unwrap() as usize;
            let y = point["output"][1].as_u64().unwrap() as usize;
            let end = [(x + 1).min(w - 1), (y + 1).min(h - 1)];
            let b = g.source_bounds([x as f64, y as f64, end[0] as f64, end[1] as f64], 1.0).unwrap();
            let a = g.source_position(x, y).unwrap();
            let z = g.source_position(end[0], end[1]).unwrap();
            for p in [a, z] {
                assert!((b[0]..=b[2]).contains(&p[0]) && (b[1]..=b[3]).contains(&p[1]));
            }
            let input_length = ((end[0] - x) as f64).hypot((end[1] - y) as f64);
            assert!((z[0] - a[0]).hypot(z[1] - a[1]) <= jacobian * input_length + 1e-9);
        }
        let rectangle = [0.0, 0.0, (w - 1) as f64, (h - 1) as f64];
        // Extra zoom acts before geometry, around the very same optical
        // center. Its interval agrees with explicitly shrinking input bounds.
        for zoom in [1.01, 1.4, 4.0, 8.0] {
            let center = g.center_pixels();
            let shrunk = std::array::from_fn(|i| center[i % 2] + (rectangle[i] - center[i % 2]) / zoom);
            let direct = g.source_bounds(rectangle, zoom).unwrap();
            let separate = g.source_bounds(shrunk, 1.0).unwrap();
            for i in 0..4 { assert!((direct[i] - separate[i]).abs() < 1e-8); }
        }
    }
}

#[test]
fn identity_has_no_autocrop_and_general_rows_are_bounded() {
    let calibration = Calibration { crop_factor: 2.0, aspect_ratio: 4.0 / 3.0,
        center: [0.08, -0.04], points: &[] };
    let g = Geometry::identity(&calibration, 2.0, 800, 600).unwrap();
    assert!(g.is_identity());
    assert_eq!(g.autofill_zoom(), 1.0);
    assert_eq!(g.sampling_jacobian_bound(), Some(1.0));
    assert_eq!(g.source_bounds([0.0, 0.0, 799.0, 599.0], 1.0), Some([0.0, 0.0, 799.0, 599.0]));
    for (x, y) in [(0, 0), (799, 599), (400, 300)] {
        assert_eq!(g.source_position(x, y).unwrap(), [x as f64, y as f64]);
    }
    assert!(g.source_bounds([-1.0, 0.0, 799.0, 599.0], 1.0).is_none());
    assert!(g.source_bounds([0.0, 0.0, 799.0, 599.0], 0.99).is_none());
    let row = CalibrationPoint { focal_mm: 35.0, model: DistortionModel::Poly3 { k1: 0.01 } };
    let oversized = vec![row; film_codecs::lensfun_geometry::MAX_CALIBRATION_POINTS + 1];
    assert!(Geometry::new_general(&Calibration { points: &oversized, ..calibration }, 2.0, 35.0, 800, 600).is_none());
    // Missing distortion calibration does not prevent a coordinate frame for
    // independent TCA/vignetting, but cannot masquerade as corrected geometry.
    assert!(Geometry::new_general(&calibration, 2.0, 35.0, 800, 600).is_none());
}
