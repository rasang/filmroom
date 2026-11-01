use film_codecs::{
    develop::Budget,
    export::{ExportParams, encode},
    output_color::OutputColorSpace,
};
use std::{fs, path::PathBuf, sync::atomic::AtomicBool};
fn temp(name: &str) -> PathBuf {
    std::env::temp_dir().join(format!("color-output-{}-{name}", std::process::id()))
}
fn input() -> PathBuf {
    static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let p = temp(&format!(
        "source-{}.f32",
        NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
    ));
    let mut bytes = Vec::new();
    for _ in 0..64 * 64 {
        for v in [0.18f32, 0.4, 0.7] {
            bytes.extend(v.to_le_bytes());
        }
    }
    fs::write(&p, bytes).unwrap();
    p
}
fn jpeg_icc(b: &[u8]) -> Vec<u8> {
    let mut p = 2;
    let mut parts = std::collections::BTreeMap::new();
    while p + 4 <= b.len() {
        assert_eq!(b[p], 255);
        let marker = b[p + 1];
        if marker == 0xda {
            break;
        }
        let n = u16::from_be_bytes([b[p + 2], b[p + 3]]) as usize;
        let chunk = &b[p + 4..p + 2 + n];
        if marker == 0xe2 && chunk.starts_with(b"ICC_PROFILE\0") {
            parts.insert(chunk[12], chunk[14..].to_vec());
        }
        p += 2 + n;
    }
    parts.into_values().flatten().collect()
}
#[test]
fn jpeg_and_tiff_embed_matching_profiles_and_transfer() {
    let input = input();
    for space in [
        OutputColorSpace::Srgb,
        OutputColorSpace::DisplayP3,
        OutputColorSpace::AdobeRgb,
    ] {
        let profile = space.icc().unwrap();
        let decoded = moxcms::ColorProfile::new_from_slice(&profile).unwrap();
        assert!(decoded.cicp.is_none());
        for format in ["JPEG", "TIFF16"] {
            let output = temp(&format!("{}-{format}", space.name()));
            let _ = fs::remove_file(&output);
            let p = ExportParams {
                width: 64,
                height: 64,
                format: format.into(),
                quality: Some(100),
                output_color_space: space.name().into(),
                budget: Budget::default(),
            };
            let report = encode(&input, &output, &p, &AtomicBool::new(false)).unwrap();
            assert_eq!(report.color_space, space.name());
            if format == "JPEG" {
                let bytes = fs::read(&output).unwrap();
                assert_eq!(jpeg_icc(&bytes), profile);
                let image = image::load_from_memory(&bytes).unwrap().to_rgb8();
                for px in image.pixels() {
                    for (a, b) in px.0.iter().zip([0.18, 0.4, 0.7]) {
                        assert!((*a as f32 / 255. - space.encode(b)).abs() < 3. / 255.);
                    }
                }
            } else {
                let mut d = tiff::decoder::Decoder::new(fs::File::open(&output).unwrap()).unwrap();
                assert_eq!(
                    d.get_tag_u8_vec(tiff::tags::Tag::IccProfile).unwrap(),
                    profile
                );
                let values = match d.read_image().unwrap() {
                    tiff::decoder::DecodingResult::U16(v) => v,
                    _ => panic!(),
                };
                for px in values.chunks_exact(3) {
                    for (a, b) in px.iter().zip([0.18, 0.4, 0.7]) {
                        assert!((*a as f32 / 65535. - space.encode(b)).abs() < 2. / 65535.);
                    }
                }
            }
            fs::remove_file(output).unwrap();
        }
    }
    fs::remove_file(input).unwrap();
}
#[test]
fn embedded_trcs_decode_the_same_values_we_encode() {
    for space in [
        OutputColorSpace::Srgb,
        OutputColorSpace::DisplayP3,
        OutputColorSpace::AdobeRgb,
    ] {
        let p = moxcms::ColorProfile::new_from_slice(&space.icc().unwrap()).unwrap();
        let mut linear = p.clone();
        let curve = moxcms::ToneReprCurve::Parametric(vec![1.]);
        linear.red_trc = Some(curve.clone());
        linear.green_trc = Some(curve.clone());
        linear.blue_trc = Some(curve);
        let transform = p
            .create_transform_f32(
                moxcms::Layout::Rgb,
                &linear,
                moxcms::Layout::Rgb,
                moxcms::TransformOptions::default(),
            )
            .unwrap();
        let input = [space.encode(0.18), space.encode(0.4), space.encode(0.7)];
        let mut output = [0.; 3];
        transform.transform(&input, &mut output).unwrap();
        for (a, b) in output.iter().zip([0.18, 0.4, 0.7]) {
            assert!((*a - b).abs() < 0.001, "{} {output:?}", space.name());
        }
    }
}
#[test]
fn unknown_color_space_fails_before_output_creation() {
    let input = input();
    let output = temp("unknown.tif");
    let _ = fs::remove_file(&output);
    let p = ExportParams {
        width: 64,
        height: 64,
        format: "TIFF16".into(),
        quality: None,
        output_color_space: "Bogus RGB".into(),
        budget: Budget::default(),
    };
    assert_eq!(
        encode(&input, &output, &p, &AtomicBool::new(false))
            .unwrap_err()
            .code,
        "UNSUPPORTED_COLOR_SPACE"
    );
    assert!(!output.exists());
    fs::remove_file(input).unwrap();
}
#[test]
fn bundled_png_profiles_match_native_profiles_exactly() {
    let root =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../app/src/main/assets/color-profiles");
    for (space, name) in [
        (OutputColorSpace::Srgb, "srgb.icc"),
        (OutputColorSpace::DisplayP3, "display-p3.icc"),
        (OutputColorSpace::AdobeRgb, "adobe-rgb-1998.icc"),
    ] {
        assert_eq!(fs::read(root.join(name)).unwrap(), space.icc().unwrap());
    }
}
#[test]
fn icc_primaries_match_engine_d65_matrices() {
    let spaces = [
        (
            OutputColorSpace::DisplayP3,
            [
                [0.48657095, 0.26566769, 0.19821729],
                [0.22897456, 0.69173852, 0.07928691],
                [0., 0.04511338, 1.04394437],
            ],
        ),
        (
            OutputColorSpace::AdobeRgb,
            [
                [0.57666904, 0.18555824, 0.18822865],
                [0.29734498, 0.62736357, 0.07529146],
                [0.02703136, 0.07068885, 0.99133754],
            ],
        ),
    ];
    let xyz_to_srgb = [
        [3.2406, -1.5372, -0.4986],
        [-0.9689, 1.8758, 0.0415],
        [0.0557, -0.204, 1.057],
    ];
    let mul = |m: [[f64; 3]; 3], v: [f64; 3]| -> [f64; 3] {
        std::array::from_fn(|i| (0..3).map(|j| m[i][j] * v[j]).sum())
    };
    let mut dst = moxcms::ColorProfile::new_srgb();
    dst.cicp = None;
    let trc = moxcms::ToneReprCurve::Parametric(vec![1.]);
    dst.red_trc = Some(trc.clone());
    dst.green_trc = Some(trc.clone());
    dst.blue_trc = Some(trc);
    for (space, matrix) in spaces {
        let src = moxcms::ColorProfile::new_from_slice(&space.icc().unwrap()).unwrap();
        let tx = src
            .create_transform_f32(
                moxcms::Layout::Rgb,
                &dst,
                moxcms::Layout::Rgb,
                moxcms::TransformOptions::default(),
            )
            .unwrap();
        // Interior colors avoid any gamut clipping by ICC library.
        for rgb in [[0.18, 0.18, 0.18], [0.4, 0.2, 0.1], [0.2, 0.4, 0.3]] {
            let expected = mul(xyz_to_srgb, mul(matrix, rgb));
            let encoded = rgb.map(|v| space.encode(v as f32));
            let mut actual = [0.; 3];
            tx.transform(&encoded, &mut actual).unwrap();
            for i in 0..3 {
                assert!(
                    (actual[i] as f64 - expected[i]).abs() < 0.001,
                    "{} {actual:?} {expected:?}",
                    space.name()
                );
            }
        }
    }
}
