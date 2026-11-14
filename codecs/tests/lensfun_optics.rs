use film_codecs::{lensfun::optics::{TcaCalibration, TcaCorrection, VignetteCalibration,
    VignettingCorrection, interpolate_tca, interpolate_vignetting,
    interpolate_vignetting_general}, lensfun_geometry::{Calibration,
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

fn full_data() -> Value {
    serde_json::from_str(include_str!("fixtures/lensfun-0.3.4-full-optics.json")).unwrap()
}

fn distortion_rows(profile: &Value) -> Vec<CalibrationPoint> {
    profile["distortion"].as_array().unwrap().iter().map(|p| {
        let t = |i| p["terms"][i].as_f64().unwrap();
        CalibrationPoint { focal_mm: p["focal_mm"].as_f64().unwrap(), model: match p["model"].as_str().unwrap() {
            "ptlens" => DistortionModel::PtLens { a: t(0), b: t(1), c: t(2) },
            "poly3" => DistortionModel::Poly3 { k1: t(0) },
            "poly5" => DistortionModel::Poly5 { k1: t(0), k2: t(1) },
            _ => panic!("unrecognized fixture model"),
        }}
    }).collect()
}

#[test]
fn native_full_library_interpolation_projection_and_independent_optics_oracle() {
    let data = full_data();
    let mut max_coord = 0.0_f64;
    let mut max_gain = 0.0_f64;
    let mut max_tca = 0.0_f64;
    let mut max_vignette = 0.0_f64;
    let mut points = 0;
    let mut rejected_unsafe_gain = 0;
    for case in data["cases"].as_array().unwrap() {
        let name = case["profile"].as_str().unwrap();
        let profile = data["profiles"].as_array().unwrap().iter().find(|p| p["id"] == name).unwrap();
        let rows = distortion_rows(profile);
        let tca: Vec<_> = profile["tca"].as_array().unwrap().iter().map(|p| TcaCalibration {
            focal_mm: p["focal_mm"].as_f64().unwrap(),
            terms: std::array::from_fn(|i| p["terms"][i].as_f64().unwrap()),
        }).collect();
        let vignette: Vec<_> = profile["vignetting"].as_array().unwrap().iter().map(|p| VignetteCalibration {
            focal_mm: p["focal_mm"].as_f64().unwrap(), aperture: p["aperture"].as_f64().unwrap(),
            distance_m: p["distance_m"].as_f64().unwrap(),
            terms: std::array::from_fn(|i| p["terms"][i].as_f64().unwrap()),
        }).collect();
        let focal = case["focal_mm"].as_f64().unwrap();
        let aperture = case["aperture"].as_f64().unwrap();
        let distance = case["distance_m"].as_f64().unwrap();
        let width = case["width"].as_u64().unwrap() as usize;
        let height = case["height"].as_u64().unwrap() as usize;
        let crop = case["camera_crop"].as_f64().unwrap();
        let aspect = profile["aspect_ratio"].as_f64().unwrap();
        let calibration = Calibration { crop_factor: profile["crop_factor"].as_f64().unwrap(),
            aspect_ratio: aspect, center: std::array::from_fn(|i| profile["center"][i].as_f64().unwrap()),
            points: &rows };
        let identity = Geometry::identity(&calibration, crop, width, height).unwrap();
        let g = if rows.is_empty() { identity.clone() } else {
            Geometry::new_general(&calibration, crop, focal, width, height)
                .unwrap_or_else(|| panic!("safe native distortion: {name} at {focal}"))
        };
        assert!((g.autofill_zoom() - case["distortion_auto_scale"].as_f64().unwrap()).abs() < 0.000002,
            "{name} native distortion autoscale");
        let t = if case["tca_coefficients"].is_null() {
            assert!(interpolate_tca(&tca, focal).is_none()); None
        } else {
            let coefficients = interpolate_tca(&tca, focal).unwrap();
            for (i, c) in coefficients.into_iter().enumerate() {
                let error = (c as f64 - case["tca_coefficients"][i].as_f64().unwrap()).abs();
                assert!(error < 0.0000003, "{name} TCA coefficient {i}: {error}");
                max_tca = max_tca.max(error);
            }
            Some(TcaCorrection::new_samples(&g, &tca, focal).unwrap())
        };
        if let Some(t) = &t {
            assert!((t.autofill_zoom() - case["combined_auto_scale"].as_f64().unwrap()).abs() < 0.000002,
                "{name} native combined autoscale");
        }
        let focal_range = std::array::from_fn(|i| profile["focal_range"][i].as_f64().unwrap());
        let unsafe_native_gain = case["points"].as_array().unwrap().iter().any(|p| {
            p["source_gain"].as_f64().is_some_and(|g| !(0.25..=64.0).contains(&g))
        });
        let v = if case["vignetting_coefficients"].is_null() {
            assert!(interpolate_vignetting_general(&vignette, focal_range, focal, aperture, distance).is_none()); None
        } else {
            let coefficients = interpolate_vignetting_general(&vignette, focal_range, focal, aperture, distance).unwrap();
            for (i, c) in coefficients.into_iter().enumerate() {
                let error = (c as f64 - case["vignetting_coefficients"][i].as_f64().unwrap()).abs();
                assert!(error < 0.000003, "{name} vignetting coefficient {i}: {error}");
                max_vignette = max_vignette.max(error);
            }
            let v = VignettingCorrection::new_general_for_bounds(&identity, &vignette, aspect,
                focal_range, focal, aperture, [distance; 2], [0.0, 0.0, (width - 1) as f64, (height - 1) as f64]);
            if unsafe_native_gain {
                // The circular fisheye's fitted PA polynomial is not valid
                // outside its image circle. Native output zero is not a gain.
                assert!(v.is_none(), "{name}: unsafe full-rectangle gain must be rejected");
                rejected_unsafe_gain += 1;
            } else { assert!(v.is_some(), "{name}: safe independent source gain"); }
            v
        };
        for point in case["points"].as_array().unwrap() {
            let x = point["output"][0].as_u64().unwrap() as usize;
            let y = point["output"][1].as_u64().unwrap() as usize;
            let actual = g.source_position(x, y).unwrap();
            for axis in 0..2 {
                let error = (actual[axis] - point["distortion"][axis].as_f64().unwrap()).abs();
                assert!(error < 0.003, "{name} distortion at {x},{y} axis {axis}: {error}");
                max_coord = max_coord.max(error);
            }
            if let Some(t) = &t { for channel in 0..3 {
                let post = t.post_source_position(x as f64, y as f64, channel).unwrap();
                for axis in 0..2 {
                    let error = (post[axis] - point["tca_post"][channel * 2 + axis].as_f64().unwrap()).abs();
                    assert!(error < 0.003, "{name} standalone TCA at {x},{y} channel {channel}: {error}");
                    max_coord = max_coord.max(error);
                }
                let combined = t.source_position(x, y, channel, 1.0).unwrap();
                for axis in 0..2 {
                    let error = (combined[axis] - point["combined"][channel * 2 + axis].as_f64().unwrap()).abs();
                    assert!(error < 0.003, "{name} combined at {x},{y} channel {channel}: {error}");
                    max_coord = max_coord.max(error);
                }
            }}
            if let Some(v) = &v {
                let relative = (v.source_gain(x as f64, y as f64).unwrap()
                    / point["source_gain"].as_f64().unwrap() - 1.0).abs();
                assert!(relative < 0.00003, "{name} source gain at {x},{y}: {relative}");
                max_gain = max_gain.max(relative);
            }
            points += 1;
        }
    }
    assert_eq!((points, rejected_unsafe_gain), (135, 1));
    eprintln!("Lensfun full optics: {points} points; max coordinate {max_coord:.9} px, gain relative {max_gain:.9}, TCA coefficient {max_tca:.9}, vignette coefficient {max_vignette:.9}; one invalid circular-domain PA rejected");
}

#[test]
fn standalone_tca_continuous_bounds_cover_offsets_and_reject_remote_folds() {
    let g = Geometry::identity(&Calibration { crop_factor: 1.0, aspect_ratio: 1.5,
        center: [0.03, -0.02], points: &[] }, 1.0, 6001, 4001).unwrap();
    let t = TcaCorrection::new_samples(&g, &[TcaCalibration { focal_mm: 35.0,
        terms: [1.002, 0.997, -0.0008, 0.001, 0.0006, -0.0002] }], 35.0).unwrap();
    let rectangle = [-140.0, -80.0, 6140.0, 4080.0];
    let (_, _, jacobian) = t.sampling_bounds_for(rectangle).unwrap();
    for channel in 0..3 {
        let b = t.post_source_bounds(rectangle, channel).unwrap();
        for y in 0..=20 { for x in 0..=30 {
            let p = [rectangle[0] + (rectangle[2] - rectangle[0]) * x as f64 / 30.0,
                     rectangle[1] + (rectangle[3] - rectangle[1]) * y as f64 / 20.0];
            let q = t.post_source_position(p[0], p[1], channel).unwrap();
            assert!((b[0]..=b[2]).contains(&q[0]) && (b[1]..=b[3]).contains(&q[1]));
            if channel == 1 { assert_eq!(p, q); }
            if x < 30 && y < 20 {
                let r = t.post_source_position(p[0] + 0.003, p[1] + 0.004, channel).unwrap();
                assert!((r[0] - q[0]).hypot(r[1] - q[1]) <= jacobian * 0.005 + 1e-9);
            }
        }}
    }
    // Valid on the reference image, but not on an arbitrarily enlarged source
    // halo: the post-map's radial derivative becomes negative farther away.
    let shrinking = TcaCorrection::new_samples(&g, &[TcaCalibration { focal_mm: 35.0,
        terms: [1.0, 1.0, 0.0, 0.0, -0.03, -0.03] }], 35.0).unwrap();
    assert!(shrinking.sampling_bounds_for([-20000.0, -20000.0, 20000.0, 20000.0]).is_none());
    assert!(shrinking.post_source_bounds([19000.0, 19000.0, 20000.0, 20000.0], 0).is_none());
    assert!(t.post_source_bounds([f64::NAN, 0.0, 1.0, 1.0], 0).is_none());
}

#[test]
fn general_gain_certifies_supplied_active_extent_and_multifocal_focus_interval() {
    let g = Geometry::identity(&Calibration { crop_factor: 1.0, aspect_ratio: 1.5,
        center: [0.0, 0.0], points: &[] }, 1.0, 6001, 4001).unwrap();
    let mut rows = Vec::new();
    for focal in [20.0, 35.0, 50.0, 70.0, 100.0] {
        for aperture in [2.8, 4.0, 5.6, 8.0, 11.0, 16.0] {
            for distance_m in [1.0, 10.0, 1000.0] {
                rows.push(VignetteCalibration { focal_mm: focal, aperture, distance_m, terms: [-0.2, 0.02, 0.0] });
            }
        }
    }
    assert_eq!(rows.len(), 90);
    let bounds = [-160.0, -160.0, 6160.0, 4160.0];
    let v = VignettingCorrection::new_general_for_bounds(&g, &rows, 1.5, [20.0, 100.0],
        42.0, 6.3, [1.0, f64::INFINITY], bounds).unwrap();
    assert_eq!(v.source_bounds(), bounds);
    for x in [bounds[0], 3000.0, bounds[2]] { for y in [bounds[1], 2000.0, bounds[3]] {
        assert!((0.25..=64.0).contains(&v.source_gain(x, y).unwrap()));
    }}
    assert!(v.source_gain(bounds[0] - 0.01, 2000.0).is_err());
    // A focal-dependent row at an interior focus must be included even when
    // both reported interval endpoints happen to be nearly identical.
    rows.push(VignetteCalibration { focal_mm: 42.0, aperture: 6.3, distance_m: 2.0, terms: [-0.7, 0.0, 0.0] });
    assert!(VignettingCorrection::new_general_for_bounds(&g, &rows, 1.5, [20.0, 100.0],
        42.0, 6.3, [1.0, 1000.0], bounds).is_none());
    let over_cap = vec![rows[0]; film_codecs::lensfun_geometry::MAX_CALIBRATION_POINTS + 1];
    assert!(interpolate_vignetting_general(&over_cap, [20.0, 100.0], 42.0, 6.3, 10.0).is_none());
}

#[test]
fn rf135_actual_focus_interval_retains_signed_polynomial_cancellation() {
    let data: Value = serde_json::from_str(include_str!("fixtures/lensfun-rf135-focus.json")).unwrap();
    let points: Vec<_> = data["rows"].as_array().unwrap().iter().map(|p| VignetteCalibration {
        focal_mm: p["focal_mm"].as_f64().unwrap(), aperture: p["aperture"].as_f64().unwrap(),
        distance_m: p["distance_m"].as_f64().unwrap(),
        terms: std::array::from_fn(|i| p["terms"][i].as_f64().unwrap()),
    }).collect();
    assert_eq!(points.len(), 20);
    let capture = &data["capture"];
    let active: [i64; 4] = std::array::from_fn(|i| capture["active"][i].as_i64().unwrap());
    let crop: [i64; 4] = std::array::from_fn(|i| capture["crop"][i].as_i64().unwrap());
    let bounds = [(active[0] - crop[0]) as f64, (active[1] - crop[1]) as f64,
        (active[0] + active[2] - crop[0] - 1) as f64,
        (active[1] + active[3] - crop[1] - 1) as f64];
    assert_eq!(bounds, [-12.0, -12.0, 8201.0, 5465.0]);
    let g = Geometry::identity(&Calibration { crop_factor: 1.0, aspect_ratio: 1.5,
        center: [0.0; 2], points: &[] }, 1.0, crop[2] as usize, crop[3] as usize).unwrap();
    let focus = std::array::from_fn(|i| capture["focus_range_m"][i].as_f64().unwrap());
    let full = VignettingCorrection::new_general_for_bounds(&g, &points, 1.5, [135.0; 2],
        135.0, 1.8, focus, bounds).expect("the actual RF135 interval must be continuously certified");
    assert_eq!(full.source_bounds(), bounds);
    assert_eq!(full.focus_range_m, [1.08, 1.12]);
    assert!((full.focus_estimate_m - 1.0996363636363637).abs() < 1e-14);
    // Proof refinement must not replace the native selected coefficients.
    assert_eq!(full.coefficients(), [-0.968080997467041_f32,
        0.5705993175506592_f32, -0.18272680044174194_f32]);
    for x in [bounds[0], 4095.5, bounds[2]] { for y in [bounds[1], 2731.5, bounds[3]] {
        assert!((1.0 - 1e-12..=2.397).contains(&full.source_gain(x, y).unwrap()));
    }}
    assert!(full.source_gain(bounds[0] - 0.001, 2731.5).is_err());
    assert!(full.source_gain(4095.5, bounds[3] + 0.001).is_err());
    assert!(VignettingCorrection::new_general_for_bounds(&g, &points, 1.5, [135.0; 2],
        135.0, 1.8, focus, [0.0, 0.0, 8191.0, 5463.0]).is_some());

    // A genuinely wider distance uncertainty is still rejected. At the
    // actual source corner its endpoint gains differ by much more than 2%.
    let near = VignettingCorrection::new_general_for_bounds(&g, &points, 1.5, [135.0; 2],
        135.0, 1.8, [0.69; 2], bounds).unwrap();
    let far = VignettingCorrection::new_general_for_bounds(&g, &points, 1.5, [135.0; 2],
        135.0, 1.8, [4.12; 2], bounds).unwrap();
    let endpoint_ratio = far.source_gain(bounds[0], bounds[1]).unwrap()
        / near.source_gain(bounds[0], bounds[1]).unwrap();
    assert!(endpoint_ratio > 1.1);
    assert!(VignettingCorrection::new_general_for_bounds(&g, &points, 1.5, [135.0; 2],
        135.0, 1.8, [0.69, 4.12], bounds).is_none());
    assert!(VignettingCorrection::new_general_for_bounds(&g, &points, 1.5, [135.0; 2],
        135.0, 1.8, focus, [-10000.0, -10000.0, 20000.0, 20000.0]).is_none());
}

#[test]
fn focus_difference_cancellation_at_the_corner_does_not_hide_a_radial_interior_extremum() {
    let g = Geometry::identity(&Calibration { crop_factor: 1.0, aspect_ratio: 1.5,
        center: [0.0; 2], points: &[] }, 1.0, 6001, 4001).unwrap();
    let points = [VignetteCalibration { focal_mm: 50.0, aperture: 4.0,
        distance_m: 1.0, terms: [0.24, -0.24, 0.0] },
        VignetteCalibration { focal_mm: 50.0, aperture: 4.0,
        distance_m: 2.0, terms: [0.0; 3] }];
    let bounds = [0.0, 0.0, 6000.0, 4000.0];
    let near = VignettingCorrection::new_general_for_bounds(&g, &points, 1.5, [50.0; 2],
        50.0, 4.0, [1.0; 2], bounds).unwrap();
    let far = VignettingCorrection::new_general_for_bounds(&g, &points, 1.5, [50.0; 2],
        50.0, 4.0, [2.0; 2], bounds).unwrap();
    // q is about 1 at the corner, where the terms cancel; q is about 1/2
    // halfway along the diagonal in radius, where the difference is 6%.
    assert!((near.source_gain(0.0, 0.0).unwrap() - far.source_gain(0.0, 0.0).unwrap()).abs() < 1e-6);
    let x = 3000.0 * (1.0 - 0.5_f64.sqrt());
    let y = 2000.0 * (1.0 - 0.5_f64.sqrt());
    assert!(far.source_gain(x, y).unwrap() / near.source_gain(x, y).unwrap() > 1.05);
    assert!(VignettingCorrection::new_general_for_bounds(&g, &points, 1.5, [50.0; 2],
        50.0, 4.0, [1.0, 2.0], bounds).is_none());
}
