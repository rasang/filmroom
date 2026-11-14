use film_codecs::{
    develop::{Budget, decode},
    dng::Dng,
};
use std::{fs, path::PathBuf, sync::atomic::AtomicBool};
fn base() -> Vec<u8> {
    fs::read(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/RGGB.dng")).unwrap()
}
fn fixture(make: &[u8], kind: u16, points: &[f32]) -> Vec<u8> {
    let mut b = base();
    let off = u32::from_le_bytes(b[4..8].try_into().unwrap()) as usize;
    let n = u16::from_le_bytes(b[off..off + 2].try_into().unwrap()) as usize;
    let mut entries: Vec<Vec<u8>> = (0..n)
        .map(|i| b[off + 2 + i * 12..off + 14 + i * 12].to_vec())
        .filter(|e| !matches!(u16::from_le_bytes(e[..2].try_into().unwrap()), 271 | 50940))
        .collect();
    let curve: Vec<u8> = points.iter().flat_map(|p| p.to_le_bytes()).collect();
    for (tag, typ, count, payload) in [
        (271u16, 2u16, make.len() as u32, make.to_vec()),
        (50940, kind, points.len() as u32, curve),
    ] {
        let mut e = Vec::new();
        e.extend(tag.to_le_bytes());
        e.extend(typ.to_le_bytes());
        e.extend(count.to_le_bytes());
        if payload.len() <= 4 {
            let mut p = [0; 4];
            p[..payload.len()].copy_from_slice(&payload);
            e.extend(p)
        } else {
            e.extend((b.len() as u32).to_le_bytes());
            b.extend(payload)
        }
        entries.push(e);
    }
    entries.sort_by_key(|e| u16::from_le_bytes(e[..2].try_into().unwrap()));
    let off = b.len() as u32;
    b[4..8].copy_from_slice(&off.to_le_bytes());
    b.extend((entries.len() as u16).to_le_bytes());
    for e in entries {
        b.extend(e)
    }
    b.extend(0u32.to_le_bytes());
    b
}
fn path(s: &str) -> PathBuf {
    std::env::temp_dir().join(format!("vivo-endpoint-{}-{s}", std::process::id()))
}
fn accepted(make: &[u8], kind: u16, points: &[f32], name: &str) -> bool {
    let p = path(name);
    fs::write(&p, fixture(make, kind, points)).unwrap();
    let result = Dng::read(&mut fs::File::open(&p).unwrap());
    fs::remove_file(p).unwrap();
    if let Ok(d) = &result {
        assert_eq!(
            d.warnings
                .iter()
                .filter(|w| w.code == "DNG_CAMERA_TONE_CURVE_NOT_APPLIED")
                .count(),
            1
        )
    } else {
        assert_eq!(result.as_ref().unwrap_err().code, "INVALID_COLOR_METADATA")
    }
    result.is_ok()
}
#[test]
fn verified_vivo_endpoint_and_policy_boundary() {
    for (i, v) in [0.9971559047698975f32, 0.997, 1.].into_iter().enumerate() {
        assert!(accepted(
            b"vivo\0",
            11,
            &[0., 0., 0.5, 0.4, 1., v],
            &format!("valid-{i}")
        ))
    }
    assert!(!accepted(
        b"vivo\0",
        11,
        &[0., 0., 0.5, 0.4, 1., f32::from_bits(0.997f32.to_bits() - 1)],
        "below-boundary"
    ));
}
#[test]
fn bounded_compatibility_is_independent_of_brand() {
    for (i, make) in [
        b"OPPO\0" as &[u8],
        b"Xiaomi\0",
        b"Vivo X300\0",
        b"vivox\0",
        b" vivo\0",
    ]
    .into_iter()
    .enumerate()
    {
        assert!(accepted(
            make,
            11,
            &[0., 0., 1., 0.9971559],
            &format!("brand-{i}")
        ))
    }
    assert!(accepted(
        b"ViVo\0",
        11,
        &[0., 0., 1., 0.9971559],
        "mixed-case"
    ));
}
#[test]
fn conforming_curve_keeps_cross_brand_behavior() {
    for (i, make) in [b"OPPO\0" as &[u8], b"Xiaomi\0", b"vivo\0"]
        .into_iter()
        .enumerate()
    {
        assert!(accepted(
            make,
            11,
            &[0., 0., 0.5, 0.4, 1., 1.],
            &format!("standard-{i}")
        ))
    }
}
#[test]
fn malformed_or_outside_scope_curves_are_not_laundered() {
    let cases = vec![
        vec![0., 0., 1., 0.99],
        vec![0., 0., 1., 1.001],
        vec![0., 0., 1., f32::NAN],
        vec![0., 0., 1., f32::INFINITY],
        vec![0., 0., 0.5, f32::NAN, 1., 0.9972],
        vec![0., 0., 1., -0.1],
        vec![0.1, 0., 1., 0.9972],
        vec![0., 0.1, 1., 0.9972],
        vec![0., 0., 0.99, 0.9972],
        vec![0., 0., 0.5, 0.8, 0.4, 0.9, 1., 0.9972],
        vec![0., 0., 0.5, 0.8, 0.5, 0.9, 1., 0.9972],
        vec![0., 0., 0.5, 1., 1., 0.9972],
        vec![0., 0., 0.5, 0.9972, 1., 0.9972],
        vec![0., 0.],
        vec![0., 0., 1., 0.9972, 0.5],
    ];
    for (i, p) in cases.iter().enumerate() {
        assert!(!accepted(b"vivo\0", 11, p, &format!("invalid-{i}")))
    }
    assert!(!accepted(b"vivo\0", 11, &vec![0.; 4098], "excess-count"));
    assert!(!accepted(b"vivo\0", 4, &[0., 0., 1., 0.9972], "wrong-type"));
}
#[test]
fn admitted_endpoint_never_changes_sensor_spool_or_source_bytes() {
    let input = path("decode.dng");
    let out = path("decode.f32");
    let mut reference = None;
    for (i, p) in [
        vec![0., 0., 0.5, 0.4, 1., 1.],
        vec![0., 0., 0.5, 0.4, 1., 0.9971559],
    ]
    .into_iter()
    .enumerate()
    {
        let source = fixture(b"vivo\0", 11, &p);
        fs::write(&input, &source).unwrap();
        let (report, _) =
            decode(&input, &out, &Budget::default(), &AtomicBool::new(false)).unwrap();
        assert!(
            report
                .warnings
                .iter()
                .any(|w| w.code == "DNG_CAMERA_TONE_CURVE_NOT_APPLIED")
        );
        let pixels = fs::read(&out).unwrap();
        if i == 0 {
            reference = Some(pixels)
        } else {
            assert_eq!(reference.as_ref().unwrap(), &pixels)
        }
        assert_eq!(fs::read(&input).unwrap(), source);
        fs::remove_file(&out).unwrap();
    }
    fs::remove_file(input).unwrap();
}
fn extra_entry(mut b: Vec<u8>, tag: u16, typ: u16, count: u32, offset: u32) -> Vec<u8> {
    let old = u32::from_le_bytes(b[4..8].try_into().unwrap()) as usize;
    let n = u16::from_le_bytes(b[old..old + 2].try_into().unwrap()) as usize;
    let entries = b[old + 2..old + 2 + 12 * n].to_vec();
    let new = b.len() as u32;
    b[4..8].copy_from_slice(&new.to_le_bytes());
    b.extend(((n + 1) as u16).to_le_bytes());
    b.extend(entries);
    b.extend(tag.to_le_bytes());
    b.extend(typ.to_le_bytes());
    b.extend(count.to_le_bytes());
    b.extend(offset.to_le_bytes());
    b.extend(0u32.to_le_bytes());
    b
}
#[test]
fn dynamic_range_declaration_is_presence_only_and_never_enables_exception() {
    for (i, (typ, count, value)) in [
        (7u16, 8u32, 0u32),
        (65535, u32::MAX, u32::MAX),
        (4, 1, 0),
        (4, 1, 1),
    ]
    .into_iter()
    .enumerate()
    {
        for endpoint in [0.9972f32, 1.] {
            let p = path(&format!("dynamic-{i}-{endpoint}"));
            fs::write(
                &p,
                extra_entry(
                    fixture(b"vivo\0", 11, &[0., 0., 1., endpoint]),
                    52551,
                    typ,
                    count,
                    value,
                ),
            )
            .unwrap();
            let result = Dng::read(&mut fs::File::open(&p).unwrap());
            assert_eq!(result.is_ok(), endpoint == 1.);
            if endpoint != 1. {
                assert_eq!(result.unwrap_err().code, "INVALID_COLOR_METADATA")
            };
            fs::remove_file(p).unwrap();
        }
    }
}
#[test]
fn duplicate_tone_tag_remains_invalid() {
    let p = path("duplicate.dng");
    fs::write(
        &p,
        extra_entry(
            fixture(b"vivo\0", 11, &[0., 0., 1., 0.9972]),
            50940,
            11,
            4,
            0,
        ),
    )
    .unwrap();
    let err = Dng::read(&mut fs::File::open(&p).unwrap()).unwrap_err();
    assert_eq!(err.code, "INVALID_INPUT");
    fs::remove_file(p).unwrap();
}
