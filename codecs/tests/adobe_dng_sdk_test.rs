use film_codecs::{
    develop::Budget,
    dng::Rect,
    dng_sdk::{DEVELOPMENT_POLICY, DEVELOPMENT_POLICY_LENSFUN},
    raw::{decode, probe},
};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::atomic::AtomicBool,
};

fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures").join(name)
}

fn temp(name: &str) -> PathBuf {
    std::env::temp_dir().join(format!("adobe-dng-sdk-test-{}-{name}", std::process::id()))
}

#[test]
fn valid_dng_fixtures_decode_via_adobe_sdk() {
    let valid = [
        "RGGB.dng",
        "BGGR.dng",
        "GBRG.dng",
        "GRBG.dng",
        "be.dng",
        "crop.dng",
        "linear.dng",
        "lossless-baseline.dng",
        "lossless-be.dng",
        "lossless-category16.dng",
        "lossless-crop.dng",
        "lossless-edge.dng",
        "lossless-metadata.dng",
        "lossless-strips.dng",
        "lossless-tiles.dng",
        "phone-calibration.dng",
        "phone-diagonal.dng",
        "phone-mandatory.dng",
        "phone-optional.dng",
    ];
    for name in valid {
        let input = fixture(name);
        let out = temp(name);
        let _ = fs::remove_file(&out);
        let (p, a) = probe(&input, &Budget::default(), false).expect(name);
        assert_eq!(p.format, "DNG", "{name}");
        assert_eq!(p.development_policy, DEVELOPMENT_POLICY, "{name}");
        assert_eq!(p.output_orientation, 1, "{name}");
        assert!(p.variant.starts_with("DNG_SDK_"), "{name}: {}", p.variant);
        assert!(a.is_some(), "{name}");

        let (q, _) = decode(&input, &out, &Budget::default(), &AtomicBool::new(false)).expect(name);
        assert_eq!(p.width, q.width, "{name}");
        assert_eq!(p.height, q.height, "{name}");
        let bytes = fs::read(&out).expect(name);
        assert_eq!(bytes.len() as u64, q.output_bytes, "{name}");
        assert!(
            bytes
                .chunks_exact(4)
                .map(|c| f32::from_le_bytes(c.try_into().unwrap()))
                .all(|v| v.is_finite() && (0.0..=1.0).contains(&v)),
            "{name} produced out-of-range float"
        );
        fs::remove_file(&out).unwrap();
    }

    let bad = fixture("phone-malformed.dng");
    let out = temp("malformed.f32");
    let _ = fs::remove_file(&out);
    assert!(decode(&bad, &out, &Budget::default(), &AtomicBool::new(false)).is_err());
    assert!(!out.exists());
}

#[test]
fn lossless_jpeg_matches_uncompressed_under_adobe_sdk() {
    for stem in [
        "lossless-baseline",
        "lossless-be",
        "lossless-category16",
        "lossless-crop",
        "lossless-edge",
        "lossless-metadata",
        "lossless-strips",
        "lossless-tiles",
    ] {
        let a_out = temp(&format!("{stem}-ljpeg.f32"));
        let b_out = temp(&format!("{stem}-plain.f32"));
        let _ = fs::remove_file(&a_out);
        let _ = fs::remove_file(&b_out);
        decode(
            &fixture(&format!("{stem}.dng")),
            &a_out,
            &Budget::default(),
            &AtomicBool::new(false),
        )
        .expect(stem);
        decode(
            &fixture(&format!("{stem}-plain.dng")),
            &b_out,
            &Budget::default(),
            &AtomicBool::new(false),
        )
        .expect(stem);
        assert_eq!(fs::read(&a_out).unwrap(), fs::read(&b_out).unwrap(), "{stem}");
        fs::remove_file(&a_out).unwrap();
        fs::remove_file(&b_out).unwrap();
    }
}

#[test]
fn admission_preserve_existing_and_spill_behaviour() {
    let input = fixture("crop.dng");
    let out = temp("admission.f32");
    let _ = fs::remove_file(&out);

    // Sub-floor memory budget refused before creating output.
    let mut low_mem = Budget::default();
    low_mem.memory_budget_mi_b = 16;
    assert_eq!(
        decode(&input, &out, &low_mem, &AtomicBool::new(false)).unwrap_err().code,
        "MEMORY_BUDGET"
    );
    assert!(!out.exists());

    // Insufficient disk budget refused before creating output.
    let mut low_disk = Budget::default();
    low_disk.disk_budget_bytes = 1024;
    assert_eq!(
        decode(&input, &out, &low_disk, &AtomicBool::new(false)).unwrap_err().code,
        "DISK_BUDGET"
    );
    assert!(!out.exists());

    // Existing file is never overwritten or deleted.
    fs::write(&out, b"DO_NOT_TOUCH").unwrap();
    assert_eq!(
        decode(&input, &out, &Budget::default(), &AtomicBool::new(false)).unwrap_err().code,
        "OUTPUT_EXISTS"
    );
    assert_eq!(fs::read(&out).unwrap(), b"DO_NOT_TOUCH");
    fs::remove_file(&out).unwrap();

    // Tight 32 MiB budget (0 heap share for >=4 MiB buffers) still succeeds cleanly.
    let mut min_mem = Budget::default();
    min_mem.memory_budget_mi_b = 32;
    let (p, _) = decode(&input, &out, &min_mem, &AtomicBool::new(false)).unwrap();
    assert_eq!((p.width, p.height), (374, 56));
    assert_eq!(fs::metadata(&out).unwrap().len(), p.output_bytes);
    fs::remove_file(&out).unwrap();
}

#[allow(dead_code)]
#[path = "common/scale.rs"]
mod scale;

#[test]
fn all_eight_orientations_and_default_scale_match_canonical() {
    let raw = fs::read(fixture("RGGB.dng")).unwrap();
    let cropped = scale::replace(raw, 50720, 5, 2, scale::rational(&[(33, 4), (37, 4)]));
    let scaled = scale::replace(cropped.clone(), 50718, 5, 2, scale::rational(&[(2, 1), (5, 4)]));

    for (label, base) in [("unscaled", cropped), ("scaled", scaled)] {
        let mut canonical: Vec<[u8; 12]> = Vec::new();
        let mut base_w = 0usize;
        let mut base_h = 0usize;
        for orientation in 1..=8u16 {
            let dng = temp(&format!("{label}-orient-{orientation}.dng"));
            let out = temp(&format!("{label}-orient-{orientation}.f32"));
            let _ = fs::remove_file(&dng);
            let _ = fs::remove_file(&out);
            let bytes = scale::replace(base.clone(), 274, 3, 1, orientation.to_le_bytes().to_vec());
            fs::write(&dng, bytes).unwrap();

            let (p, _) = decode(&dng, &out, &Budget::default(), &AtomicBool::new(false)).unwrap();
            assert_eq!(p.source_orientation, orientation as u8);
            assert_eq!(p.output_orientation, 1);
            let px: Vec<[u8; 12]> = fs::read(&out)
                .unwrap()
                .chunks_exact(12)
                .map(|c| c.try_into().unwrap())
                .collect();
            if orientation == 1 {
                base_w = p.width as usize;
                base_h = p.height as usize;
                assert_ne!(base_w, base_h);
                canonical = px;
            } else {
                let (ew, eh) = if orientation >= 5 { (base_h, base_w) } else { (base_w, base_h) };
                assert_eq!((p.width as usize, p.height as usize), (ew, eh), "{label} o={orientation}");
                for y in 0..base_h {
                    for x in 0..base_w {
                        let (ox, oy) =
                            film_codecs::develop::mapped(x, y, base_w, base_h, orientation as u8);
                        assert_eq!(
                            px[oy * ew + ox],
                            canonical[y * base_w + x],
                            "{label} orientation={orientation} ({x},{y})->({ox},{oy})"
                        );
                    }
                }
            }
            fs::remove_file(&dng).unwrap();
            fs::remove_file(&out).unwrap();
        }
    }
}

fn rect4(r: Rect) -> [usize; 4] {
    [r.x, r.y, r.w, r.h]
}

#[test]
fn default_crop_reports_sensor_coordinates_with_active_area_offset() {
    let (p, _) = probe(&fixture("crop.dng"), &Budget::default(), false).unwrap();
    assert_eq!(rect4(p.active_area), [2, 2, 60, 380]);
    assert_eq!(rect4(p.default_crop), [3, 5, 56, 374]);
}

fn opcode_record(id: u32, version: u32, flags: u32, payload: &[u8]) -> Vec<u8> {
    [id, version, flags, payload.len() as u32]
        .into_iter()
        .flat_map(u32::to_be_bytes)
        .chain(payload.iter().copied())
        .collect()
}

fn opcode_list(records: &[Vec<u8>]) -> Vec<u8> {
    (records.len() as u32)
        .to_be_bytes()
        .into_iter()
        .chain(records.iter().flatten().copied())
        .collect()
}

fn warp_rectilinear(k0_per_plane: &[f64], flags: u32) -> Vec<u8> {
    let mut payload = (k0_per_plane.len() as u32).to_be_bytes().to_vec();
    for &k0 in k0_per_plane {
        payload.extend([k0, 0.0, 0.0, 0.0, 0.0, 0.0].into_iter().flat_map(f64::to_be_bytes));
    }
    payload.extend([0.5f64, 0.5].into_iter().flat_map(f64::to_be_bytes));
    opcode_record(1, 0x01030000, flags, &payload)
}

fn fix_vignette_radial(k0: f64, flags: u32) -> Vec<u8> {
    let payload: Vec<u8> = [k0, 0.0, 0.0, 0.0, 0.0, 0.5, 0.5]
        .into_iter()
        .flat_map(f64::to_be_bytes)
        .collect();
    opcode_record(3, 0x01030000, flags, &payload)
}

#[test]
fn embedded_opcodes_and_profile_tone_curve_warnings_match_between_probe_and_decode() {
    let base = fs::read(fixture("RGGB.dng")).unwrap();

    // 1) Non-linear ProfileToneCurve (tag 50940) is stripped completely while emitting
    //    DNG_CAMERA_TONE_CURVE_NOT_APPLIED identically in probe and decode.
    let base_dng = temp("curve-base.dng");
    let base_out = temp("curve-base.f32");
    fs::write(&base_dng, &base).unwrap();
    let (base_p, _) = decode(&base_dng, &base_out, &Budget::default(), &AtomicBool::new(false)).unwrap();
    assert!(base_p.warnings.is_empty(), "{:?}", base_p.warnings);
    let base_pixels = fs::read(&base_out).unwrap();

    let curve_floats: Vec<u8> = [0.0f32, 0.0, 0.35, 0.85, 1.0, 1.0]
        .into_iter()
        .flat_map(f32::to_le_bytes)
        .collect();
    let list3 = opcode_list(&[
        warp_rectilinear(&[0.98], 0),
        warp_rectilinear(&[1.002, 1.0, 0.998], 0),
        fix_vignette_radial(0.15, 0),
        opcode_record(9999, 0x01030000, 1, &[1, 2, 3, 4]),
    ]);
    let rich_bytes = scale::replace(
        scale::replace(base.clone(), 50940, 11, 6, curve_floats.clone()),
        51022,
        7,
        list3.len() as u32,
        list3,
    );
    let rich_dng = temp("embedded-warnings.dng");
    let rich_out = temp("embedded-warnings.f32");
    let _ = fs::remove_file(&rich_out);
    fs::write(&rich_dng, &rich_bytes).unwrap();

    let (probed, _) = probe(&rich_dng, &Budget::default(), true).unwrap();
    let (decoded, _) = decode(&rich_dng, &rich_out, &Budget::default(), &AtomicBool::new(false)).unwrap();
    let codes: Vec<&str> = probed.warnings.iter().map(|w| w.code).collect();
    let decoded_codes: Vec<&str> = decoded.warnings.iter().map(|w| w.code).collect();
    assert_eq!(codes, decoded_codes);
    assert_eq!(probed.development_policy, DEVELOPMENT_POLICY);
    assert_eq!(decoded.development_policy, DEVELOPMENT_POLICY);
    for expected in [
        "EMBEDDED_LENS_DISTORTION_APPLIED",
        "EMBEDDED_LENS_TCA_APPLIED",
        "EMBEDDED_LENS_VIGNETTING_APPLIED",
        "DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED",
        "DNG_CAMERA_TONE_CURVE_NOT_APPLIED",
    ] {
        assert!(codes.contains(&expected), "missing {expected} in {codes:?}");
    }

    // Verify tone-curve stripping alone leaves linear RGB float output unchanged.
    let curve_only_dng = temp("curve-only.dng");
    let curve_only_out = temp("curve-only.f32");
    let _ = fs::remove_file(&curve_only_out);
    fs::write(&curve_only_dng, scale::replace(base.clone(), 50940, 11, 6, curve_floats)).unwrap();
    let (curve_p, _) = decode(&curve_only_dng, &curve_only_out, &Budget::default(), &AtomicBool::new(false)).unwrap();
    assert_eq!(
        curve_p.warnings.iter().map(|w| w.code).collect::<Vec<_>>(),
        vec!["DNG_CAMERA_TONE_CURVE_NOT_APPLIED"]
    );
    assert_eq!(fs::read(&curve_only_out).unwrap(), base_pixels);

    // 2) Required unknown opcode fails closed during metadata-only probe too.
    let req_unknown = opcode_list(&[opcode_record(9999, 0x01030000, 0, &[0; 4])]);
    let bad_dng = temp("req-unknown.dng");
    fs::write(&bad_dng, scale::replace(base, 51022, 7, req_unknown.len() as u32, req_unknown)).unwrap();
    assert_eq!(probe(&bad_dng, &Budget::default(), true).unwrap_err().code, "UNSUPPORTED_DNG_OPCODE");

    for f in [base_dng, base_out, rich_dng, rich_out, curve_only_dng, curve_only_out, bad_dng] {
        let _ = fs::remove_file(f);
    }
}

fn build_ricoh_cfa_dng(
    model: &str,
    software: &str,
    focal_num_den: (u32, u32),
    extra_tags: &[(u16, u16, u32, Vec<u8>)],
) -> Vec<u8> {
    let encoded_w = 100u32;
    let encoded_h = 68u32;
    let mut tags: Vec<(u16, u16, u32, Vec<u8>)> = Vec::new();
    let ascii = |s: &str| -> (u16, u32, Vec<u8>) {
        let mut v = s.as_bytes().to_vec();
        v.push(0);
        (2, v.len() as u32, v)
    };
    for (tag, val) in [
        (254u16, 0u32),
        (256, encoded_w),
        (257, encoded_h),
        (258, 16),
        (259, 1),
        (262, 32803),
        (273, 0),
        (274, 1),
        (277, 1),
        (278, encoded_h),
        (279, 0),
        (50717, 16383),
    ] {
        tags.push((tag, 4, 1, val.to_le_bytes().to_vec()));
    }
    tags.push((50706, 1, 4, vec![1, 4, 0, 0]));
    tags.push((33421, 3, 2, [2u16, 2].into_iter().flat_map(u16::to_le_bytes).collect()));
    tags.push((33422, 1, 4, vec![0, 1, 1, 2])); // RGGB
    for (tag, text) in [(271u16, "RICOH IMAGING COMPANY, LTD."), (272, model), (305, software)] {
        let (ty, n, bytes) = ascii(text);
        tags.push((tag, ty, n, bytes));
    }
    // Nonzero black level to exercise Stage3BlackLevel un-offset/re-offset in LensfunVignetteTask.
    tags.push((50713, 3, 2, [2u16, 2].into_iter().flat_map(u16::to_le_bytes).collect()));
    tags.push((50714, 4, 4, [256u32; 4].into_iter().flat_map(u32::to_le_bytes).collect()));
    // ActiveArea [top=2, left=4, bottom=66, right=98] -> active (x=4, y=2, w=94, h=64)
    // DefaultCropOrigin [2, 2], DefaultCropSize [90, 60] -> sensor crop (x=6, y=4, w=90, h=60) (3:2)
    tags.push((50829, 4, 4, [2u32, 4, 66, 98].into_iter().flat_map(u32::to_le_bytes).collect()));
    tags.push((50719, 4, 2, [2u32, 2].into_iter().flat_map(u32::to_le_bytes).collect()));
    tags.push((50720, 4, 2, [90u32, 60].into_iter().flat_map(u32::to_le_bytes).collect()));
    // Dual-illuminant camera profile + AsShotNeutral.
    for (tag, vals) in [
        (50721u16, [12587i32, -5232, -1496, -3610, 10841, 277, -911, 1674, 2070]),
        (50722u16, [8389i32, -3198, -1019, -3834, 10222, 661, -1122, 1900, 3415]),
    ] {
        let bytes: Vec<u8> = vals
            .into_iter()
            .flat_map(|v| [v.to_le_bytes(), 10000i32.to_le_bytes()].concat())
            .collect();
        tags.push((tag, 10, 9, bytes));
    }
    tags.push((50778, 3, 1, 17u16.to_le_bytes().to_vec()));
    tags.push((50779, 3, 1, 21u16.to_le_bytes().to_vec()));
    tags.push((
        50728,
        5,
        3,
        scale::rational(&[(1024, 2090), (1024, 1024), (1024, 1524)]),
    ));
    // FNumber = f/2.8, SubjectDistance = 10 m, FocalLength = focal_num_den
    tags.push((0x829d, 5, 1, scale::rational(&[(28, 10)])));
    tags.push((0x9206, 5, 1, scale::rational(&[(10, 1)])));
    tags.push((0x920a, 5, 1, scale::rational(&[focal_num_den])));

    for &(tag, ty, count, ref data) in extra_tags {
        tags.retain(|t| t.0 != tag);
        tags.push((tag, ty, count, data.clone()));
    }
    tags.sort_by_key(|t| t.0);

    let base = 8 + 2 + tags.len() * 12 + 4;
    let mut entries: Vec<u8> = Vec::new();
    let mut payload: Vec<u8> = Vec::new();
    for (tag, ty, count, data) in &tags {
        entries.extend(tag.to_le_bytes());
        entries.extend(ty.to_le_bytes());
        entries.extend(count.to_le_bytes());
        if data.len() <= 4 {
            let mut inline = [0u8; 4];
            inline[..data.len()].copy_from_slice(data);
            entries.extend(inline);
        } else {
            entries.extend(((base + payload.len()) as u32).to_le_bytes());
            payload.extend_from_slice(data);
        }
    }
    // Patterned radial gradient CFA data (within [256..12000]) so both vignetting gain
    // and geometric resampling produce measurable pixel differences.
    let mut pixels = Vec::with_capacity((encoded_w * encoded_h * 2) as usize);
    for y in 0..encoded_h {
        for x in 0..encoded_w {
            let dx = x as i32 - 50;
            let dy = y as i32 - 34;
            let v = (4096 + ((dx * dx + dy * dy) % 2048) as u16 + ((x * 13 + y * 7) % 256) as u16)
                .min(14000);
            pixels.extend(v.to_le_bytes());
        }
    }
    let pixel_offset = (base + payload.len()) as u32;
    for (i, (tag, _, _, _)) in tags.iter().enumerate() {
        if *tag == 273 {
            entries[i * 12 + 8..i * 12 + 12].copy_from_slice(&pixel_offset.to_le_bytes());
        }
        if *tag == 279 {
            entries[i * 12 + 8..i * 12 + 12].copy_from_slice(&(pixels.len() as u32).to_le_bytes());
        }
    }
    let mut out = vec![b'I', b'I', 42, 0, 8, 0, 0, 0];
    out.extend((tags.len() as u16).to_le_bytes());
    out.extend(entries);
    out.extend(0u32.to_le_bytes());
    out.extend(payload);
    out.extend(pixels);
    out
}

#[test]
fn native_firmware_ricoh_gr_iii_and_iiix_apply_stage3_lensfun_and_converter_gates_exclude() {
    // 1) Ricoh GR III (18.3 mm, f/2.8, 10 m, native firmware): applies Distortion + TCA + Vignetting.
    let griii_dng = temp("griii-native.dng");
    let griii_out = temp("griii-native.f32");
    let _ = fs::remove_file(&griii_out);
    fs::write(
        &griii_dng,
        build_ricoh_cfa_dng("RICOH GR III", "RICOH GR III Ver. 1.00", (183, 10), &[]),
    )
    .unwrap();
    let (p_probe, _) = probe(&griii_dng, &Budget::default(), false).unwrap();
    let (p_decode, _) = decode(&griii_dng, &griii_out, &Budget::default(), &AtomicBool::new(false)).unwrap();
    assert_eq!(p_probe.development_policy, DEVELOPMENT_POLICY_LENSFUN);
    assert_eq!(p_decode.development_policy, DEVELOPMENT_POLICY_LENSFUN);
    assert_eq!(rect4(p_probe.default_crop), [6, 4, 90, 60]);
    let griii_codes: Vec<&str> = p_probe.warnings.iter().map(|w| w.code).collect();
    assert_eq!(
        griii_codes,
        p_decode.warnings.iter().map(|w| w.code).collect::<Vec<_>>()
    );
    assert_eq!(
        griii_codes,
        vec![
            "LENSFUN_DISTORTION_APPLIED",
            "LENSFUN_TCA_APPLIED",
            "LENSFUN_VIGNETTING_APPLIED",
        ]
    );
    let griii_pixels: Vec<f32> = fs::read(&griii_out)
        .unwrap()
        .chunks_exact(4)
        .map(|c| f32::from_le_bytes(c.try_into().unwrap()))
        .collect();
    assert_eq!(griii_pixels.len(), 90 * 60 * 3);
    assert!(griii_pixels.iter().all(|v| v.is_finite() && (0.0..=1.0).contains(v)));

    // 2) Same Ricoh GR III sensor data with Adobe DNG Converter Software string:
    //    must NOT apply Lensfun, must use standard DEVELOPMENT_POLICY, and must
    //    produce measurably different corner pixels (no vignette lift / warp).
    let conv_dng = temp("griii-converter.dng");
    let conv_out = temp("griii-converter.f32");
    let _ = fs::remove_file(&conv_out);
    fs::write(
        &conv_dng,
        build_ricoh_cfa_dng("RICOH GR III", "Adobe DNG Converter 17.1", (183, 10), &[]),
    )
    .unwrap();
    let (conv_probe, _) = probe(&conv_dng, &Budget::default(), false).unwrap();
    let (conv_decode, _) = decode(&conv_dng, &conv_out, &Budget::default(), &AtomicBool::new(false)).unwrap();
    assert_eq!(conv_probe.development_policy, DEVELOPMENT_POLICY);
    assert_eq!(conv_decode.development_policy, DEVELOPMENT_POLICY);
    assert!(conv_probe.warnings.is_empty(), "{:?}", conv_probe.warnings);
    let conv_pixels: Vec<f32> = fs::read(&conv_out)
        .unwrap()
        .chunks_exact(4)
        .map(|c| f32::from_le_bytes(c.try_into().unwrap()))
        .collect();
    // Corner green sample must be lifted significantly by GR III f/2.8 vignetting correction.
    assert!(
        griii_pixels[1] > conv_pixels[1] * 1.15,
        "expected corner vignette lift: lensfun={} plain={}",
        griii_pixels[1],
        conv_pixels[1]
    );
    // Center sample remains closely aligned (gain ~ 1.0 near optical center).
    let center_idx = (30 * 90 + 45) * 3 + 1;
    assert!(
        (griii_pixels[center_idx] - conv_pixels[center_idx]).abs() < 0.02,
        "center drifted unexpectedly: lensfun={} plain={}",
        griii_pixels[center_idx],
        conv_pixels[center_idx]
    );

    // 2b) Disabling profile_corrections on the native GR III DNG turns off Stage 3 Lensfun,
    //     clears Lensfun warnings, switches policy to DEVELOPMENT_POLICY_UNCORRECTED, and
    //     matches the uncorrected sensor pixels of the converter-tagged DNG!
    let uncorr_budget = Budget { profile_corrections: false, ..Budget::default() };
    let griii_uncorr_out = temp("griii-uncorr.f32");
    let _ = fs::remove_file(&griii_uncorr_out);
    let (u_probe, _) = probe(&griii_dng, &uncorr_budget, false).unwrap();
    let (u_decode, _) = decode(&griii_dng, &griii_uncorr_out, &uncorr_budget, &AtomicBool::new(false)).unwrap();
    assert_eq!(u_probe.development_policy, film_codecs::dng_sdk::DEVELOPMENT_POLICY_UNCORRECTED);
    assert_eq!(u_decode.development_policy, film_codecs::dng_sdk::DEVELOPMENT_POLICY_UNCORRECTED);
    assert!(u_probe.warnings.is_empty(), "{:?}", u_probe.warnings);
    assert!(u_decode.warnings.is_empty(), "{:?}", u_decode.warnings);
    let griii_uncorr_pixels: Vec<f32> = fs::read(&griii_uncorr_out)
        .unwrap()
        .chunks_exact(4)
        .map(|c| f32::from_le_bytes(c.try_into().unwrap()))
        .collect();
    assert_eq!(griii_uncorr_pixels, conv_pixels);

    // 3) Additional exclusion gates: OriginalRawFileName (50827) and empty OpcodeList3 (51022).
    for (label, extra) in [
        ("orig-name", (50827u16, 2u16, 9u32, b"R000001\0\0".to_vec())),
        ("empty-op3", (51022u16, 7u16, 4u32, 0u32.to_be_bytes().to_vec())),
    ] {
        let ex_dng = temp(&format!("griii-{label}.dng"));
        fs::write(
            &ex_dng,
            build_ricoh_cfa_dng("RICOH GR III", "RICOH GR III Ver. 1.00", (183, 10), &[extra]),
        )
        .unwrap();
        let (p, _) = probe(&ex_dng, &Budget::default(), false).unwrap();
        assert_eq!(p.development_policy, DEVELOPMENT_POLICY, "{label}");
        assert!(p.warnings.is_empty(), "{label}: {:?}", p.warnings);
        fs::remove_file(&ex_dng).unwrap();
    }

    // 4) Ricoh GR IIIx (26.0 mm, f/2.8, 10 m, native firmware): applies Distortion + Vignetting (no TCA in profile).
    let griiix_dng = temp("griiix-native.dng");
    let griiix_out = temp("griiix-native.f32");
    let _ = fs::remove_file(&griiix_out);
    fs::write(
        &griiix_dng,
        build_ricoh_cfa_dng("RICOH GR IIIx", "RICOH GR IIIx Ver. 1.02", (26, 1), &[]),
    )
    .unwrap();
    let (px_probe, _) = probe(&griiix_dng, &Budget::default(), false).unwrap();
    let (px_decode, _) = decode(&griiix_dng, &griiix_out, &Budget::default(), &AtomicBool::new(false)).unwrap();
    assert_eq!(px_probe.development_policy, DEVELOPMENT_POLICY_LENSFUN);
    assert_eq!(px_decode.development_policy, DEVELOPMENT_POLICY_LENSFUN);
    assert_eq!(
        px_probe.warnings.iter().map(|w| w.code).collect::<Vec<_>>(),
        vec!["LENSFUN_DISTORTION_APPLIED", "LENSFUN_VIGNETTING_APPLIED"]
    );

    for f in [griii_dng, griii_out, griii_uncorr_out, conv_dng, conv_out, griiix_dng, griiix_out] {
        let _ = fs::remove_file(f);
    }
}

#[test]
fn profile_corrections_false_disables_embedded_dng_lens_opcodes_but_keeps_tone_curve_strip() {
    let mut base = fs::read(fixture("RGGB.dng")).unwrap();
    base = scale::replace(base, 50829, 4, 4, [0u32, 0, 16, 20].into_iter().flat_map(u32::to_le_bytes).collect());
    base = scale::replace(base, 50719, 4, 2, [0u32, 0].into_iter().flat_map(u32::to_le_bytes).collect());
    base = scale::replace(base, 50720, 4, 2, [20u32, 16].into_iter().flat_map(u32::to_le_bytes).collect());

    let plain_dng = temp("plain-embed.dng");
    let plain_out = temp("plain-embed.f32");
    let _ = fs::remove_file(&plain_out);
    fs::write(&plain_dng, &base).unwrap();
    decode(&plain_dng, &plain_out, &Budget::default(), &AtomicBool::new(false)).unwrap();
    let plain_pixels = fs::read(&plain_out).unwrap();

    // Strong FixVignetteRadial (k0 = 0.8 -> +80% corner boost) + WarpRectilinear + ProfileToneCurve.
    let mut vig = Vec::new();
    for f in [0.8f64, 0.0, 0.0, 0.0, 0.0, 0.5, 0.5] {
        vig.extend(f.to_be_bytes());
    }
    let mut warp = Vec::new();
    warp.extend(3u32.to_be_bytes());
    for (k1, _) in [(0.04f64, 0.0f64), (0.04, 0.0), (0.06, 0.0)] {
        for f in [1.0f64, k1, 0.0, 0.0, 0.0, 0.0] {
            warp.extend(f.to_be_bytes());
        }
    }
    warp.extend(0.5f64.to_be_bytes());
    warp.extend(0.5f64.to_be_bytes());
    let op3 = opcode_list(&[
        opcode_record(3, 0x01030000, 0, &vig),
        opcode_record(1, 0x01030000, 0, &warp),
    ]);
    let curve_floats: Vec<u8> = [0.0f32, 0.0, 0.25, 0.75, 1.0, 1.0]
        .into_iter()
        .flat_map(f32::to_le_bytes)
        .collect();
    let mut embedded = scale::replace(base, 51022, 7, op3.len() as u32, op3);
    embedded = scale::replace(embedded, 50940, 11, 6, curve_floats);
    let emb_dng = temp("emb-toggle.dng");
    fs::write(&emb_dng, embedded).unwrap();

    let on_out = temp("emb-on.f32");
    let off_out = temp("emb-off.f32");
    let _ = fs::remove_file(&on_out);
    let _ = fs::remove_file(&off_out);
    let (p_on, _) = decode(&emb_dng, &on_out, &Budget::default(), &AtomicBool::new(false)).unwrap();
    let uncorr = Budget { profile_corrections: false, ..Budget::default() };
    let (p_off_probe, _) = probe(&emb_dng, &uncorr, true).unwrap();
    let (p_off, _) = decode(&emb_dng, &off_out, &uncorr, &AtomicBool::new(false)).unwrap();

    assert_ne!(fs::read(&on_out).unwrap(), plain_pixels);
    assert_eq!(fs::read(&off_out).unwrap(), plain_pixels);
    assert!(p_on.warnings.iter().any(|w| w.code == "EMBEDDED_LENS_DISTORTION_APPLIED"));
    assert!(p_on.warnings.iter().any(|w| w.code == "EMBEDDED_LENS_TCA_APPLIED"));
    assert!(p_on.warnings.iter().any(|w| w.code == "EMBEDDED_LENS_VIGNETTING_APPLIED"));
    assert_eq!(
        p_off_probe.warnings.iter().map(|w| w.code).collect::<Vec<_>>(),
        vec!["DNG_CAMERA_TONE_CURVE_NOT_APPLIED"]
    );
    assert_eq!(
        p_off.warnings.iter().map(|w| w.code).collect::<Vec<_>>(),
        vec!["DNG_CAMERA_TONE_CURVE_NOT_APPLIED"]
    );
    assert_eq!(p_off.development_policy, film_codecs::dng_sdk::DEVELOPMENT_POLICY_UNCORRECTED);

    for f in [plain_dng, plain_out, emb_dng, on_out, off_out] {
        let _ = fs::remove_file(f);
    }
}


