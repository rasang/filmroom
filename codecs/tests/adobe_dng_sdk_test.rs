use film_codecs::{
    develop::Budget,
    dng_sdk::DEVELOPMENT_POLICY,
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
