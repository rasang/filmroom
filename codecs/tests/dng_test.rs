#[path = "support/gainmap_oracle.rs"]
mod gainmap_oracle;
use film_codecs::{
    develop::{Budget, admit, decode, mapped},
    dng::Dng,
};
use rawler::imgop::develop::{Intermediate, ProcessingStep, RawDevelop};
use std::{fs, path::PathBuf, sync::atomic::AtomicBool};
fn fixture(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}
fn temp(name: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("film-codecs-{}-{name}", std::process::id()));
    let _ = fs::remove_file(&p);
    p
}
#[test]
fn reference_all_patterns_linear_endian_crop_orientations() {
    for file in [
        "RGGB.dng",
        "BGGR.dng",
        "GRBG.dng",
        "GBRG.dng",
        "linear.dng",
        "be.dng",
        "crop.dng",
    ] {
        let input = fixture(file);
        let raw = rawler::decode_file(&input).unwrap();
        let mut dev = RawDevelop::default();
        dev.steps.retain(|s| !matches!(s, ProcessingStep::SRgb));
        let reference = match dev.develop_intermediate(&raw).unwrap() {
            Intermediate::ThreeColor(p) => p,
            _ => panic!(),
        };
        let mut source = fs::File::open(&input).unwrap();
        let d = Dng::read(&mut source).unwrap();
        let out = temp(file);
        decode(&input, &out, &Budget::default(), &AtomicBool::new(false)).unwrap();
        let actual: Vec<f32> = fs::read(&out)
            .unwrap()
            .chunks_exact(4)
            .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
            .collect();
        let (ow, oh) = d.oriented_dim();
        assert_eq!(actual.len(), ow * oh * 3);
        assert_eq!((reference.width, reference.height), (d.crop.w, d.crop.h));
        let mut max_diff = 0f32;
        for y in 0..reference.height {
            for x in 0..reference.width {
                let (ox, oy) = mapped(x, y, reference.width, reference.height, d.orientation);
                for c in 0..3 {
                    max_diff = max_diff.max(
                        (reference.data[y * reference.width + x][c]
                            - actual[(oy * ow + ox) * 3 + c])
                            .abs(),
                    );
                }
            }
        }
        assert!(max_diff < 2e-6, "{file}: maxdiff={max_diff}");
        fs::remove_file(out).unwrap();
    }
}
#[test]
fn reject_bad_header_truncated_and_budget_before_allocation() {
    let b = fs::read(fixture("RGGB.dng")).unwrap();
    for (name, bytes) in [
        ("header", vec![0; 32]),
        ("truncated", b[..b.len() - 10].to_vec()),
    ] {
        let f = temp(name);
        fs::write(&f, bytes).unwrap();
        assert!(Dng::read(&mut fs::File::open(&f).unwrap()).is_err());
        fs::remove_file(f).unwrap();
    }
    let d = Dng::read(&mut fs::File::open(fixture("RGGB.dng")).unwrap()).unwrap();
    assert_eq!(
        admit(
            &d,
            &Budget {
                raw_batch: film_codecs::raw_batch::RawBatch::Standard,
                memory_budget_mi_b: 1,
                disk_budget_bytes: u64::MAX
            }
        )
        .unwrap_err()
        .code,
        "MEMORY_BUDGET"
    );
    assert_eq!(
        admit(
            &d,
            &Budget {
                raw_batch: film_codecs::raw_batch::RawBatch::Standard,
                memory_budget_mi_b: 128,
                disk_budget_bytes: 1
            }
        )
        .unwrap_err()
        .code,
        "DISK_BUDGET"
    );
}
#[test]
fn cancel_before_create_and_preserve_existing() {
    let input = fixture("RGGB.dng");
    let out = temp("cancel");
    assert_eq!(
        decode(&input, &out, &Budget::default(), &AtomicBool::new(true))
            .unwrap_err()
            .code,
        "CANCELLED"
    );
    assert!(!out.exists());
    fs::write(&out, b"KEEP").unwrap();
    assert_eq!(
        decode(&input, &out, &Budget::default(), &AtomicBool::new(false))
            .unwrap_err()
            .code,
        "OUTPUT_EXISTS"
    );
    assert_eq!(fs::read(&out).unwrap(), b"KEEP");
    fs::remove_file(out).unwrap();
}
#[test]
fn io_enospc_maps_to_fixed_safe_code() {
    let e = film_codecs::error::io_error(std::io::Error::from_raw_os_error(libc::ENOSPC), "spool");
    assert_eq!(e.code, "NO_SPACE");
    assert!(!serde_json::to_string(&e).unwrap().contains('/'));
}
#[test]
fn orientation_mapping_all_eight_is_bijection() {
    for o in 1..=8 {
        let (w, h) = (13, 11);
        let (ow, oh) = if o >= 5 { (h, w) } else { (w, h) };
        let mut seen = vec![false; w * h];
        for y in 0..h {
            for x in 0..w {
                let (a, b) = mapped(x, y, w, h, o);
                assert!(a < ow && b < oh);
                assert!(!seen[b * ow + a]);
                seen[b * ow + a] = true;
            }
        }
        assert!(seen.iter().all(|x| *x));
    }
}

#[test]
fn independent_linear_sensor_color_values() {
    let input = fixture("linear.dng");
    let out = temp("numeric");
    decode(&input, &out, &Budget::default(), &AtomicBool::new(false)).unwrap();
    let bytes = fs::read(&out).unwrap();
    let (ox, oy) = mapped(0, 0, 64, 384, 6);
    let vals: Vec<f32> = bytes[(oy * 384 + ox) * 12..(oy * 384 + ox) * 12 + 12]
        .chunks_exact(4)
        .map(|b| f32::from_le_bytes(b.try_into().unwrap()))
        .collect();
    for (a, b) in vals.iter().zip([0.24, 0.22, 0.48]) {
        assert!((a - b).abs() < 0.001, "{vals:?}");
    }
    fs::remove_file(out).unwrap();
}
#[test]
fn hostile_dimensions_and_compression_rejected() {
    let source = fs::read(fixture("RGGB.dng")).unwrap();
    for (tag, value, code) in [
        (256u16, 4_000_000_000u32, "EDGE_LIMIT"),
        (259, 8, "UNSUPPORTED_COMPRESSION"),
    ] {
        let mut b = source.clone();
        let n = u16::from_le_bytes([b[8], b[9]]) as usize;
        for i in 0..n {
            let p = 10 + i * 12;
            if u16::from_le_bytes([b[p], b[p + 1]]) == tag {
                b[p + 8..p + 12].copy_from_slice(&value.to_le_bytes());
            }
        }
        let f = temp(code);
        fs::write(&f, b).unwrap();
        assert_eq!(
            Dng::read(&mut fs::File::open(&f).unwrap())
                .unwrap_err()
                .code,
            code
        );
        fs::remove_file(f).unwrap();
    }
}
#[test]
fn aosp_shaped_optional_metadata_and_rejection() {
    // Known optional and required maps execute the same supported operation.
    for name in ["phone-optional.dng", "phone-mandatory.dng"] {
        let input = fixture(name);
        let d = Dng::read(&mut fs::File::open(&input).unwrap()).unwrap();
        assert!(d.has_forward_matrix);
        assert!(!d.cfa_gain_maps.is_empty());
        assert!(
            !d.warnings
                .iter()
                .any(|w| w.code == "DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED")
        );
        let raw = gainmap_oracle::prepare(&input);
        assert_gainmap_sensor(&input, &raw);
        let mut dev = RawDevelop::default();
        dev.steps
            .retain(|s| !matches!(s, ProcessingStep::SRgb | ProcessingStep::Rescale));
        let reference = match dev.develop_intermediate(&raw).unwrap() {
            Intermediate::ThreeColor(p) => p,
            _ => panic!(),
        };
        let out = temp(name);
        decode(&input, &out, &Budget::default(), &AtomicBool::new(false)).unwrap();
        let actual = fs::read(&out).unwrap();
        let max = actual
            .chunks_exact(4)
            .zip(reference.data.iter().flatten())
            .map(|(a, b)| (f32::from_le_bytes(a.try_into().unwrap()) - b).abs())
            .fold(0f32, f32::max);
        assert!(
            max < 2e-6,
            "independent supported opcode oracle: {name} {max}"
        );
        fs::remove_file(out).unwrap();
    }
    // Unknown required operations still reject; unknown optional operations
    // retain the explicit warning and established uncorrected output policy.
    for (name, optional) in [("phone-optional.dng", true), ("phone-mandatory.dng", false)] {
        let mut bytes = fs::read(fixture(name)).unwrap();
        let n = u16::from_le_bytes([bytes[8], bytes[9]]) as usize;
        let entry = (0..n)
            .map(|i| 10 + i * 12)
            .find(|&p| u16::from_le_bytes([bytes[p], bytes[p + 1]]) == 51009)
            .unwrap();
        let offset = u32::from_le_bytes(bytes[entry + 8..entry + 12].try_into().unwrap()) as usize;
        bytes[offset + 4..offset + 8].copy_from_slice(&99u32.to_be_bytes());
        let path = temp(if optional {
            "unknown-optional.dng"
        } else {
            "unknown-required.dng"
        });
        fs::write(&path, bytes).unwrap();
        let d = Dng::read(&mut fs::File::open(&path).unwrap());
        if optional {
            let d = d.unwrap();
            assert!(
                d.warnings
                    .iter()
                    .any(|w| w.code == "DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED")
            );
            let out = temp("unknown-optional.f32");
            let plain = temp("unknown-plain.f32");
            decode(&path, &out, &Budget::default(), &AtomicBool::new(false)).unwrap();
            decode(
                &fixture("RGGB.dng"),
                &plain,
                &Budget::default(),
                &AtomicBool::new(false),
            )
            .unwrap();
            assert_eq!(fs::read(&out).unwrap(), fs::read(&plain).unwrap());
            fs::remove_file(out).unwrap();
            fs::remove_file(plain).unwrap();
        } else {
            assert_eq!(d.unwrap_err().code, "UNSUPPORTED_DNG_OPCODE");
        }
        fs::remove_file(path).unwrap();
    }
    for (name, code) in [
        ("phone-malformed.dng", "INVALID_INPUT"),
        ("phone-calibration.dng", "UNSUPPORTED_DNG_CALIBRATION"),
    ] {
        assert_eq!(
            Dng::read(&mut fs::File::open(fixture(name)).unwrap())
                .unwrap_err()
                .code,
            code
        );
    }
}

#[test]
fn positive_diagonal_calibration_cancels_under_rawdevelop_row_normalization() {
    let a = fixture("RGGB.dng");
    let b = temp("diagonal-only.dng");
    let mut bytes = fs::read(fixture("phone-diagonal.dng")).unwrap();
    let n = u16::from_le_bytes([bytes[8], bytes[9]]) as usize;
    for i in 0..n {
        let p = 10 + i * 12;
        if u16::from_le_bytes([bytes[p], bytes[p + 1]]) == 51009 {
            bytes[p..p + 2].copy_from_slice(&65000u16.to_le_bytes());
        }
    }
    fs::write(&b, bytes).unwrap();
    let original = Dng::read(&mut fs::File::open(&a).unwrap()).unwrap();
    let calibrated = Dng::read(&mut fs::File::open(&b).unwrap()).unwrap();
    // Prove calibration was actually included, rather than blanket ignored.
    assert!((calibrated.matrix[0] / original.matrix[0] - 63. / 64.).abs() < 1e-6);
    assert!((calibrated.matrix[8] / original.matrix[8] - 129. / 128.).abs() < 1e-6);
    let oa = temp("diag-a");
    let ob = temp("diag-b");
    decode(&a, &oa, &Budget::default(), &AtomicBool::new(false)).unwrap();
    decode(&b, &ob, &Budget::default(), &AtomicBool::new(false)).unwrap();
    let aa = fs::read(&oa).unwrap();
    let bb = fs::read(&ob).unwrap();
    assert_eq!(aa.len(), bb.len());
    let mut max = 0.0f32;
    for (a, b) in aa.chunks_exact(4).zip(bb.chunks_exact(4)) {
        max = max.max(
            (f32::from_le_bytes(a.try_into().unwrap()) - f32::from_le_bytes(b.try_into().unwrap()))
                .abs(),
        );
    }
    assert!(max < 2e-6, "diagonal equivalence difference {max}");
    fs::remove_file(oa).unwrap();
    fs::remove_file(ob).unwrap();
    fs::remove_file(b).unwrap();
}

#[test]
fn malformed_or_future_backward_version_rejected() {
    for bytes in [[240, 255, 255, 255], [1, 5, 0, 0], [0, 0, 0, 0]] {
        let mut data = fs::read(fixture("RGGB.dng")).unwrap();
        let count = u16::from_le_bytes([data[8], data[9]]) as usize;
        for i in 0..count {
            let p = 10 + 12 * i;
            if u16::from_le_bytes([data[p], data[p + 1]]) == 50707 {
                data[p + 8..p + 12].copy_from_slice(&bytes);
            }
        }
        let file = temp("bad-backward");
        fs::write(&file, data).unwrap();
        assert_eq!(
            Dng::read(&mut fs::File::open(&file).unwrap())
                .unwrap_err()
                .code,
            "UNSUPPORTED_DNG_VARIANT"
        );
        fs::remove_file(file).unwrap();
    }
}

#[test]
fn real_cc0_phone_dngs_match_pinned_full_rawdevelop() {
    use std::io::Read;
    for name in ["pixel2xl-cc0.dng", "oneplus3t-cc0.dng"] {
        let input = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("tests/public-fixtures")
            .join(name);
        let raw = gainmap_oracle::prepare(&input);
        assert_gainmap_sensor(&input, &raw);
        let mut dev = RawDevelop::default();
        dev.steps
            .retain(|s| !matches!(s, ProcessingStep::SRgb | ProcessingStep::Rescale));
        let reference = match dev.develop_intermediate(&raw).unwrap() {
            Intermediate::ThreeColor(p) => p,
            _ => panic!(),
        };
        drop(raw);
        let output = temp("real-reference");
        let (p, _) = decode(&input, &output, &Budget::default(), &AtomicBool::new(false)).unwrap();
        assert_eq!(
            (p.width as usize, p.height as usize),
            (reference.width, reference.height)
        );
        let mut f = fs::File::open(&output).unwrap();
        let mut bytes = vec![0u8; reference.width * 12];
        let mut max = 0f32;
        for row in reference.data.chunks_exact(reference.width) {
            f.read_exact(&mut bytes).unwrap();
            for (a, b) in bytes.chunks_exact(4).zip(row.iter().flatten()) {
                max = max.max((f32::from_le_bytes(a.try_into().unwrap()) - b).abs());
            }
        }
        eprintln!("independent gainmap RGB {name}: max={max}");
        assert!(max < 3e-6, "{name} whole-frame difference {max}");
        fs::remove_file(output).unwrap();
    }
}

/// Rebuild only a synthetic fixture's root IFD at EOF; existing pixel offsets
/// remain unchanged. No private camera sample enters this fixture or repository.
fn synthetic_hsv_profile(
    dims: [u32; 3],
    data_count: u32,
    data_type: u16,
    bad_offset: bool,
) -> Vec<u8> {
    let mut b = fs::read(fixture("RGGB.dng")).unwrap();
    let n = u16::from_le_bytes([b[8], b[9]]) as usize;
    let original = b[10..10 + n * 12].to_vec();
    let dims_offset = b.len() as u32;
    for d in dims {
        b.extend(d.to_le_bytes());
    }
    let data_offset = b.len() as u32;
    b.resize(b.len() + data_count as usize * 4, 0);
    let ifd = b.len() as u32;
    b.extend(((n + 2) as u16).to_le_bytes());
    b.extend(original);
    for (tag, typ, count, offset) in [
        (50937u16, 4u16, 3u32, dims_offset),
        (
            50938,
            data_type,
            data_count,
            if bad_offset { u32::MAX } else { data_offset },
        ),
    ] {
        b.extend(tag.to_le_bytes());
        b.extend(typ.to_le_bytes());
        b.extend(count.to_le_bytes());
        b.extend(offset.to_le_bytes());
    }
    b.extend(0u32.to_le_bytes());
    b[4..8].copy_from_slice(&ifd.to_le_bytes());
    b
}
#[test]
fn large_huesat_correction_table_is_descriptor_only_in_basic_development() {
    let input = temp("large-profile.dng");
    fs::write(
        &input,
        synthetic_hsv_profile([90, 16, 10], 43200, 11, false),
    )
    .unwrap();
    let mut f = fs::File::open(&input).unwrap();
    let t = film_codecs::tiff::Tiff::read(&mut f).unwrap();
    let table = &t.ifds[0][&50938];
    assert_eq!(table.count, 43200);
    assert!(table.bytes.is_empty());
    assert!(table.nums().is_err()); // descriptor cannot panic or be interpreted as payload
    let d = Dng::read(&mut fs::File::open(&input).unwrap()).unwrap();
    assert!(
        d.warnings
            .iter()
            .any(|w| w.code == "DNG_CAMERA_HSV_LOOK_NOT_APPLIED")
    );
    let output = temp("hsv-output");
    let reference = temp("hsv-reference");
    decode(&input, &output, &Budget::default(), &AtomicBool::new(false)).unwrap();
    decode(
        &fixture("RGGB.dng"),
        &reference,
        &Budget::default(),
        &AtomicBool::new(false),
    )
    .unwrap();
    assert_eq!(fs::read(&output).unwrap(), fs::read(&reference).unwrap());
    for p in [input, output, reference] {
        fs::remove_file(p).unwrap();
    }
}
#[test]
fn hsv_descriptors_reject_malformed_dimensions_type_count_and_file_bounds() {
    for (index, (dims, count, typ, bad_offset)) in [
        ([90, 16, 10], 43199, 11, false),
        ([90, 1, 10], 2700, 11, false),
        ([u32::MAX, u32::MAX, u32::MAX], 3, 11, false),
        ([90, 16, 10], 43200, 4, false),
        ([90, 16, 10], 43200, 11, true),
    ]
    .into_iter()
    .enumerate()
    {
        let p = temp(&format!("bad-hsv-{index}"));
        fs::write(&p, synthetic_hsv_profile(dims, count, typ, bad_offset)).unwrap();
        assert!(Dng::read(&mut fs::File::open(&p).unwrap()).is_err());
        fs::remove_file(p).unwrap();
    }
}

#[test]
fn hsv_second_table_and_required_descriptors_are_validated_independently() {
    let base = synthetic_hsv_profile([90, 16, 10], 43200, 11, false);
    let off = u32::from_le_bytes(base[4..8].try_into().unwrap()) as usize;
    let n = u16::from_le_bytes(base[off..off + 2].try_into().unwrap()) as usize;
    let dims_at = off + 2 + (n - 2) * 12;
    let data_at = off + 2 + (n - 1) * 12;
    let data_offset = u32::from_le_bytes(base[data_at + 8..data_at + 12].try_into().unwrap());
    let mut cases = Vec::new();
    let mut missing_dims = base.clone();
    missing_dims[dims_at..dims_at + 2].copy_from_slice(&65000u16.to_le_bytes());
    cases.push((missing_dims, false));
    let mut missing_first = base.clone();
    missing_first[data_at..data_at + 2].copy_from_slice(&50939u16.to_le_bytes());
    cases.push((missing_first, false));
    for (count, offset, valid) in [
        (43200u32, data_offset, true),
        (43199, data_offset, false),
        (43200, u32::MAX, false),
    ] {
        let mut b = base.clone();
        let new_ifd = b.len() as u32;
        b.extend(((n + 1) as u16).to_le_bytes());
        b.extend(&base[off + 2..off + 2 + n * 12]);
        b.extend(50939u16.to_le_bytes());
        b.extend(11u16.to_le_bytes());
        b.extend(count.to_le_bytes());
        b.extend(offset.to_le_bytes());
        b.extend(0u32.to_le_bytes());
        b[4..8].copy_from_slice(&new_ifd.to_le_bytes());
        cases.push((b, valid));
    }
    for (index, (bytes, valid)) in cases.into_iter().enumerate() {
        let p = temp(&format!("hsv-table2-{index}"));
        fs::write(&p, bytes).unwrap();
        assert_eq!(Dng::read(&mut fs::File::open(&p).unwrap()).is_ok(), valid);
        fs::remove_file(p).unwrap();
    }
}

fn assert_gainmap_sensor(path: &std::path::Path, raw: &rawler::RawImage) {
    let mut file = fs::File::open(path).unwrap();
    let d = Dng::read(&mut file).unwrap();
    let rawler::RawImageData::Float(pixels) = &raw.data else {
        panic!()
    };
    let mut max = 0f32;
    let mut changed = 0usize;
    for y in (0..d.active.h).step_by(64) {
        let h = 64.min(d.active.h - y);
        let values = d.read_tile(&mut file, d.active.y + y, h).unwrap();
        for (r, row) in values.chunks_exact(d.active.w).enumerate() {
            for (x, a) in row.iter().enumerate() {
                let b = pixels[(d.active.y + y + r) * raw.width + d.active.x + x];
                max = max.max((a - b).abs());
                changed += usize::from(a.to_bits() != b.to_bits());
            }
        }
    }
    eprintln!("independent gainmap sensor {path:?}: max={max} changed={changed}");
    assert_eq!(
        changed, 0,
        "independent GainMap sensor precision mismatch {path:?}: {max}"
    );
}
