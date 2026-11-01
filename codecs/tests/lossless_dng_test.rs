use film_codecs::{
    develop::{Budget, decode},
    dng::Dng,
};
use std::{fs, path::PathBuf, sync::atomic::AtomicBool};

fn fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}
fn temp(name: &str) -> PathBuf {
    std::env::temp_dir().join(format!("lossless-check-{}-{name}", std::process::id()))
}

#[test]
fn compressed_sensor_and_developed_output_equal_uncompressed() {
    for name in [
        "lossless-tiles",
        "lossless-edge",
        "lossless-crop",
        "lossless-be",
        "lossless-strips",
        "lossless-category16",
        "lossless-metadata",
        "lossless-baseline",
    ] {
        let plain = fixture(&format!("{name}-plain.dng"));
        let packed = fixture(&format!("{name}.dng"));
        let mut a = fs::File::open(&plain).unwrap();
        let mut b = fs::File::open(&packed).unwrap();
        let da = Dng::read(&mut a).unwrap();
        let db = Dng::read(&mut b).unwrap();
        assert!(db.probe().variant.contains("LOSSLESS_JPEG"));
        assert_eq!(da.oriented_dim(), db.oriented_dim());
        for y in (da.active.y..da.active.y + da.active.h).step_by(37) {
            let h = (da.active.y + da.active.h - y).min(37);
            assert_eq!(
                da.read_tile(&mut a, y, h).unwrap(),
                db.read_tile(&mut b, y, h).unwrap(),
                "sensor {name} y={y}"
            );
        }
        let oa = temp(&format!("{name}-plain.f32"));
        let ob = temp(&format!("{name}.f32"));
        for p in [&oa, &ob] {
            let _ = fs::remove_file(p);
        }
        let cancel = AtomicBool::new(false);
        decode(&plain, &oa, &Budget::default(), &cancel).unwrap();
        decode(&packed, &ob, &Budget::default(), &cancel).unwrap();
        assert_eq!(
            fs::read(&oa).unwrap(),
            fs::read(&ob).unwrap(),
            "developed {name}"
        );
        for p in [&oa, &ob] {
            fs::remove_file(p).unwrap();
        }
    }
}

#[test]
fn malformed_compressed_payload_never_publishes_output() {
    let original = fs::read(fixture("lossless-tiles.dng")).unwrap();
    // Damage SOF precision, SOF dimensions and a marker; keep TIFF ranges valid.
    let sof = original.windows(2).position(|b| b == [0xff, 0xc3]).unwrap();
    for (case, pos, byte) in [
        ("precision", sof + 4, 8),
        ("geometry", sof + 5, 0x7f),
        ("marker", sof + 1, 0xc0),
    ] {
        let mut bytes = original.clone();
        bytes[pos] = byte;
        let source = temp(&format!("bad-{case}.dng"));
        let output = temp(&format!("bad-{case}.f32"));
        let _ = fs::remove_file(&output);
        fs::write(&source, bytes).unwrap();
        assert!(
            decode(
                &source,
                &output,
                &Budget::default(),
                &AtomicBool::new(false)
            )
            .is_err(),
            "{case}"
        );
        assert!(!output.exists(), "partial output {case}");
        fs::remove_file(source).unwrap();
    }
}

#[test]
fn cancelled_compressed_input_creates_no_output() {
    let output = temp("cancel.f32");
    let _ = fs::remove_file(&output);
    let err = decode(
        &fixture("lossless-tiles.dng"),
        &output,
        &Budget::default(),
        &AtomicBool::new(true),
    )
    .unwrap_err();
    assert_eq!(err.code, "CANCELLED");
    assert!(!output.exists());
}

#[test]
fn premature_entropy_end_restart_and_invalid_huffman_are_rejected() {
    let original = fs::read(fixture("lossless-tiles.dng")).unwrap();
    let sos = original.windows(2).position(|b| b == [0xff, 0xda]).unwrap();
    let scan = sos + 2 + u16::from_be_bytes([original[sos + 2], original[sos + 3]]) as usize;
    let dht = original.windows(2).position(|b| b == [0xff, 0xc4]).unwrap();
    for case in ["early-eoi", "restart", "oversubscribed"] {
        let mut bytes = original.clone();
        match case {
            "early-eoi" => bytes[scan..scan + 2].copy_from_slice(&[255, 217]),
            "restart" => bytes[scan..scan + 2].copy_from_slice(&[255, 208]),
            _ => bytes[dht + 5] = 3, // three codes of length 1 cannot form a prefix tree
        }
        let source = temp(&format!("bad-{case}.dng"));
        let output = temp(&format!("bad-{case}.f32"));
        let _ = fs::remove_file(&output);
        fs::write(&source, bytes).unwrap();
        assert!(
            decode(
                &source,
                &output,
                &Budget::default(),
                &AtomicBool::new(false)
            )
            .is_err(),
            "{case}"
        );
        assert!(!output.exists());
        fs::remove_file(source).unwrap();
    }
}

#[test]
fn profile_metadata_warns_and_legacy_lossless_version_is_rejected() {
    let source = fixture("lossless-metadata.dng");
    let d = Dng::read(&mut fs::File::open(&source).unwrap()).unwrap();
    assert!(
        d.warnings
            .iter()
            .any(|w| w.code == "DNG_CAMERA_TONE_CURVE_NOT_APPLIED")
    );
    let original = fs::read(source).unwrap();
    let n = u16::from_le_bytes([original[8], original[9]]) as usize;
    for kind in ["legacy", "nan-curve"] {
        let mut bytes = original.clone();
        for i in 0..n {
            let p = 10 + i * 12;
            let tag = u16::from_le_bytes([bytes[p], bytes[p + 1]]);
            if kind == "legacy" && tag == 50706 {
                bytes[p + 8..p + 12].copy_from_slice(&[1, 0, 0, 0]);
            }
            if kind == "nan-curve" && tag == 50940 {
                let offset = u32::from_le_bytes(bytes[p + 8..p + 12].try_into().unwrap()) as usize;
                bytes[offset..offset + 4].copy_from_slice(&f32::NAN.to_le_bytes());
            }
        }
        let file = temp(&format!("{kind}.dng"));
        fs::write(&file, bytes).unwrap();
        assert!(Dng::read(&mut fs::File::open(&file).unwrap()).is_err());
        fs::remove_file(file).unwrap();
    }
}

#[test]
fn baseline_exposure_is_applied_once_in_linear_rgb() {
    let plain = fixture("lossless-tiles-plain.dng");
    let baseline = fixture("lossless-baseline.dng");
    let p = temp("base-zero.f32");
    let q = temp("base-two.f32");
    for f in [&p, &q] {
        let _ = fs::remove_file(f);
    }
    decode(&plain, &p, &Budget::default(), &AtomicBool::new(false)).unwrap();
    let (meta, _) = decode(&baseline, &q, &Budget::default(), &AtomicBool::new(false)).unwrap();
    assert_eq!(meta.baseline_exposure_applied_ev, 2.);
    let a = fs::read(&p).unwrap();
    let b = fs::read(&q).unwrap();
    for (x, y) in a.chunks_exact(4).zip(b.chunks_exact(4)) {
        let x = f32::from_le_bytes(x.try_into().unwrap());
        let y = f32::from_le_bytes(y.try_into().unwrap());
        assert_eq!(y, x * 4.);
    }
    fs::remove_file(p).unwrap();
    fs::remove_file(q).unwrap();
}
