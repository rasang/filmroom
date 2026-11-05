use film_codecs::{
    cfa_gainmap as c,
    develop::{Budget, decode, develop_tile},
    dng::Dng,
};
use std::{fs, path::PathBuf, sync::atomic::AtomicBool};
fn list(w: u32, h: u32, flags: u32) -> Vec<u8> {
    let mut b = 4u32.to_be_bytes().to_vec();
    for phase in 0..4 {
        let mut p = Vec::new();
        for v in [phase / 2, phase % 2, h, w, 0, 1, 2, 2, 2, 2] {
            p.extend(v.to_be_bytes())
        }
        for v in [1f64, 1., 0., 0.] {
            p.extend(v.to_be_bytes())
        }
        p.extend(1u32.to_be_bytes());
        for v in [
            1. + phase as f32,
            2. + phase as f32,
            3. + phase as f32,
            4. + phase as f32,
        ] {
            p.extend(v.to_be_bytes())
        }
        for v in [9u32, 0x01030000, flags, p.len() as u32] {
            b.extend(v.to_be_bytes())
        }
        b.extend(p)
    }
    b
}
#[test]
fn cfa_phase_affine_oracle_pixel_centers_absolute_strip_coordinates() {
    let (w, h) = (20, 24);
    let m = c::parse_four_phase(&list(w, h, 1), w as usize, h as usize).unwrap();
    let mut full = vec![0.03; w as usize * h as usize];
    c::apply(
        &m,
        &mut full,
        w as usize,
        h as usize,
        0,
        &AtomicBool::new(false),
    )
    .unwrap();
    for y in 0..h {
        for x in 0..w {
            let expected = 0.03
                * (1.
                    + ((y % 2) * 2 + x % 2) as f64
                    + (x as f64 + 0.5) / w as f64
                    + 2. * (y as f64 + 0.5) / h as f64);
            assert!((full[(y * w + x) as usize] as f64 - expected).abs() < 1e-7)
        }
    }
    for size in [1, 3, 7, 11] {
        let mut other = vec![];
        for y in (0..h as usize).step_by(size) {
            let rows = size.min(h as usize - y);
            let mut p = vec![0.03; rows * w as usize];
            c::apply(
                &m,
                &mut p,
                w as usize,
                h as usize,
                y,
                &AtomicBool::new(false),
            )
            .unwrap();
            other.extend(p)
        }
        assert_eq!(full, other)
    }
}
#[test]
fn malformed_counts_planes_duplicates_nonfinite_and_cancel() {
    let b = list(20, 24, 1);
    for cutoff in [0, 3, 17, b.len() - 1] {
        assert!(c::parse_four_phase(&b[..cutoff], 20, 24).is_err())
    }
    let mut trailing = b.clone();
    trailing.push(0);
    assert!(c::parse_four_phase(&trailing, 20, 24).is_err());
    for (offset, value) in [
        (4 + 16 + 28, 1u32),
        (4 + 16 + 72, 3),
        (4 + 16 + 76, f32::NAN.to_bits()),
        (4 + 16 + 76, 0),
        (4 + 16 + 76, 65f32.to_bits()),
        (4 + 108 + 16 + 4, 0),
    ] {
        let mut bad = b.clone();
        bad[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
        assert!(
            c::parse_four_phase(&bad, 20, 24).is_err(),
            "offset {offset}"
        )
    }
    let m = c::parse_four_phase(&b, 20, 24).unwrap();
    assert_eq!(
        c::apply(&m, &mut vec![0.1; 480], 20, 24, 0, &AtomicBool::new(true))
            .unwrap_err()
            .code,
        "CANCELLED"
    );
    assert!(c::apply(&m, &mut vec![0.1; 21], 20, 24, 0, &AtomicBool::new(false)).is_err());
    let mut high = vec![0.8; 480];
    c::apply(&m, &mut high, 20, 24, 0, &AtomicBool::new(false)).unwrap();
    assert!(high.iter().all(|x| *x <= 1.));
    assert!(high.contains(&1.));
}
fn fixture() -> Vec<u8> {
    fs::read(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/RGGB.dng")).unwrap()
}
fn inject(mut b: Vec<u8>, make: &[u8], op: &[u8]) -> Vec<u8> {
    let off = u32::from_le_bytes(b[4..8].try_into().unwrap()) as usize;
    let n = u16::from_le_bytes(b[off..off + 2].try_into().unwrap()) as usize;
    let mut entries: Vec<Vec<u8>> = (0..n)
        .map(|i| b[off + 2 + i * 12..off + 14 + i * 12].to_vec())
        .filter(|e| !matches!(u16::from_le_bytes(e[..2].try_into().unwrap()), 271 | 51009))
        .collect();
    for (tag, typ, p) in [(271u16, 2u16, make), (51009, 7, op)] {
        let mut e = Vec::new();
        e.extend(tag.to_le_bytes());
        e.extend(typ.to_le_bytes());
        e.extend((p.len() as u32).to_le_bytes());
        e.extend((b.len() as u32).to_le_bytes());
        b.extend(p);
        entries.push(e)
    }
    entries.sort_by_key(|e| u16::from_le_bytes(e[..2].try_into().unwrap()));
    let o = b.len() as u32;
    b[4..8].copy_from_slice(&o.to_le_bytes());
    b.extend((entries.len() as u16).to_le_bytes());
    for e in entries {
        b.extend(e)
    }
    b.extend(0u32.to_le_bytes());
    b
}
fn path(n: &str) -> PathBuf {
    std::env::temp_dir().join(format!("cfa-gain-{}-{n}", std::process::id()))
}
#[test]
fn generic_capability_applies_after_sensor_normalization_before_demosaic_once() {
    let old = path("old.dng");
    fs::write(&old, fixture()).unwrap();
    let d = Dng::read(&mut fs::File::open(&old).unwrap()).unwrap();
    let input = d
        .read_tile(&mut fs::File::open(&old).unwrap(), 0, d.height)
        .unwrap();
    assert!(d.black.iter().any(|v| *v > 0.));
    let op = list(d.width as u32, d.height as u32, 1);
    let new = path("new.dng");
    fs::write(&new, inject(fixture(), b"Xiaomi\0", &op)).unwrap();
    let n = Dng::read(&mut fs::File::open(&new).unwrap()).unwrap();
    assert_eq!(n.cfa_gain_maps.len(), 4);
    let actual = n
        .read_tile(&mut fs::File::open(&new).unwrap(), 0, n.height)
        .unwrap();
    let mut expected = input.clone();
    for y in 0..d.height {
        for x in 0..d.width {
            let gain = 1.
                + ((y % 2) * 2 + x % 2) as f64
                + (x as f64 + 0.5) / d.width as f64
                + 2. * (y as f64 + 0.5) / d.height as f64;
            expected[y * d.width + x] =
                (input[y * d.width + x].clamp(0., 1.) * gain as f32).min(1.);
            assert!((actual[y * d.width + x] - expected[y * d.width + x]).abs() < 2e-7)
        }
    }
    let reference = develop_tile(&d, 0, d.height, actual.clone()).unwrap();
    let a = develop_tile(&n, 0, n.height, actual).unwrap();
    assert_eq!(a.data, reference.data);
    let out = path("new.f32");
    decode(&new, &out, &Budget::default(), &AtomicBool::new(false)).unwrap();
    assert!(out.exists());
    for p in [old, new, out] {
        fs::remove_file(p).unwrap()
    }
}
#[test]
fn all_brands_share_supported_capabilities_and_unknown_required_failclosed() {
    let tmp = path("cap.dng");
    fs::write(&tmp, fixture()).unwrap();
    let d = Dng::read(&mut fs::File::open(&tmp).unwrap()).unwrap();
    for make in [
        b"OPPO\0" as &[u8],
        b"Xiaomi 13 Ultra\0",
        b"Xiaomi\0",
        b"Unlisted camera\0",
    ] {
        for flags in [0, 1, 2] {
            let op = list(d.width as u32, d.height as u32, flags);
            fs::write(&tmp, inject(fixture(), make, &op)).unwrap();
            let n = Dng::read(&mut fs::File::open(&tmp).unwrap()).unwrap();
            assert_eq!(n.cfa_gain_maps.len(), 4);
            let mut unknown = op;
            unknown[4..8].copy_from_slice(&77u32.to_be_bytes());
            fs::write(&tmp, inject(fixture(), make, &unknown)).unwrap();
            let r = Dng::read(&mut fs::File::open(&tmp).unwrap());
            if flags & 1 == 0 {
                assert_eq!(r.unwrap_err().code, "UNSUPPORTED_DNG_OPCODE");
            } else {
                let n = r.unwrap();
                assert_eq!(n.cfa_gain_maps.len(), 3);
                assert!(
                    n.warnings
                        .iter()
                        .any(|w| w.code == "DNG_OPTIONAL_LENS_CORRECTIONS_NOT_APPLIED")
                );
            }
        }
    }
    fs::remove_file(tmp).unwrap();
}

#[test]
fn stage2_range_clips_before_subunity_gain() {
    let mut b = list(20, 24, 1);
    for record in 0..4 {
        for sample in 0..4 {
            let at = 4 + record * 108 + 16 + 76 + sample * 4;
            b[at..at + 4].copy_from_slice(&0.5f32.to_be_bytes());
        }
    }
    let m = c::parse_four_phase(&b, 20, 24).unwrap();
    let mut p = vec![1.5; 480];
    p[1] = -0.5;
    c::apply(&m, &mut p, 20, 24, 0, &AtomicBool::new(false)).unwrap();
    assert_eq!(p[0], 0.5);
    assert_eq!(p[1], 0.);
}
#[test]
fn different_make_same_maps_produce_identical_developed_bytes() {
    let p = path("other-brand.dng");
    let old = path("base-no-maps.dng");
    let a = path("other.f32");
    let b = path("base.f32");
    fs::write(&old, fixture()).unwrap();
    let d = Dng::read(&mut fs::File::open(&old).unwrap()).unwrap();
    fs::write(
        &old,
        inject(
            fixture(),
            b"New vendor\0",
            &list(d.width as u32, d.height as u32, 1),
        ),
    )
    .unwrap();
    fs::write(
        &p,
        inject(
            fixture(),
            b"OPPO\0",
            &list(d.width as u32, d.height as u32, 1),
        ),
    )
    .unwrap();
    for (i, o) in [(&p, &a), (&old, &b)] {
        decode(i, o, &Budget::default(), &AtomicBool::new(false)).unwrap();
    }
    assert_eq!(fs::read(&a).unwrap(), fs::read(&b).unwrap());
    for f in [p, old, a, b] {
        fs::remove_file(f).unwrap()
    }
}
#[test]
fn any_declared_phase_subset_runs_in_order_without_four_map_brand_requirement() {
    let p = path("subset.dng");
    fs::write(&p, fixture()).unwrap();
    let d = Dng::read(&mut fs::File::open(&p).unwrap()).unwrap();
    for flags in [0, 1] {
        let mut op = list(d.width as u32, d.height as u32, flags);
        op.truncate(4 + 3 * 108);
        op[..4].copy_from_slice(&3u32.to_be_bytes());
        fs::write(&p, inject(fixture(), b"Any vendor\0", &op)).unwrap();
        let n = Dng::read(&mut fs::File::open(&p).unwrap()).unwrap();
        assert_eq!(n.cfa_gain_maps.len(), 3);
    }
    fs::remove_file(p).unwrap();
}
