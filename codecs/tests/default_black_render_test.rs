use film_codecs::{
    develop::{Budget, decode},
    dng::Dng,
};
use std::{fs, fs::File, path::PathBuf, sync::atomic::AtomicBool};
fn base() -> Vec<u8> {
    fs::read(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/RGGB.dng")).unwrap()
}
fn tagged(typ: u16, values: &[u32]) -> Vec<u8> {
    let mut b = base();
    assert_eq!(&b[..2], b"II");
    let off = u32::from_le_bytes(b[4..8].try_into().unwrap()) as usize;
    let n = u16::from_le_bytes(b[off..off + 2].try_into().unwrap()) as usize;
    let mut entries: Vec<Vec<u8>> = (0..n)
        .map(|i| b[off + 2 + i * 12..off + 14 + i * 12].to_vec())
        .collect();
    assert!(
        !entries
            .iter()
            .any(|e| u16::from_le_bytes(e[..2].try_into().unwrap()) == 51110)
    );
    let payload: Vec<u8> = values
        .iter()
        .flat_map(|v| {
            if typ == 3 {
                (*v as u16).to_le_bytes().to_vec()
            } else {
                v.to_le_bytes().to_vec()
            }
        })
        .collect();
    let mut entry = Vec::new();
    entry.extend(51110u16.to_le_bytes());
    entry.extend(typ.to_le_bytes());
    entry.extend((values.len() as u32).to_le_bytes());
    if payload.len() <= 4 {
        let mut p = [0; 4];
        p[..payload.len()].copy_from_slice(&payload);
        entry.extend(p)
    } else {
        entry.extend((b.len() as u32).to_le_bytes());
        b.extend(payload)
    }
    entries.push(entry);
    entries.sort_by_key(|e| u16::from_le_bytes(e[..2].try_into().unwrap()));
    let newoff = b.len() as u32;
    b[4..8].copy_from_slice(&newoff.to_le_bytes());
    b.extend((entries.len() as u16).to_le_bytes());
    for e in entries {
        b.extend(e)
    }
    b.extend(0u32.to_le_bytes());
    b
}
fn temp(name: &str) -> PathBuf {
    std::env::temp_dir().join(format!("film-none-{}-{name}", std::process::id()))
}
#[test]
fn none_retains_sensor_blacklevel_and_exact_linear_development() {
    let old = temp("old.dng");
    let new = temp("none.dng");
    let a = temp("old.f32");
    let b = temp("none.f32");
    fs::write(&old, base()).unwrap();
    fs::write(&new, tagged(4, &[1])).unwrap();
    let before = Dng::read(&mut File::open(&old).unwrap()).unwrap();
    let after = Dng::read(&mut File::open(&new).unwrap()).unwrap();
    assert_eq!(before.black, after.black);
    assert_eq!(before.white, after.white);
    assert!(after.black.iter().any(|v| *v > 0.));
    for (i, o) in [(&old, &a), (&new, &b)] {
        decode(i, o, &Budget::default(), &AtomicBool::new(false)).unwrap();
    }
    assert_eq!(fs::read(&a).unwrap(), fs::read(&b).unwrap());
    for p in [old, new, a, b] {
        fs::remove_file(p).unwrap()
    }
}
#[test]
fn unknown_wrong_type_and_count_remain_rejected() {
    for (index, (typ, values)) in [
        (4, vec![2]),
        (4, vec![u32::MAX]),
        (3, vec![1]),
        (4, vec![]),
        (4, vec![1, 1]),
    ]
    .into_iter()
    .enumerate()
    {
        let p = temp(&format!("invalid-{index}.dng"));
        fs::write(&p, tagged(typ, &values)).unwrap();
        let e = Dng::read(&mut File::open(&p).unwrap()).unwrap_err();
        assert!(
            matches!(e.code, "UNSUPPORTED_DNG_VARIANT" | "INVALID_INPUT"),
            "{index}: {e:?}"
        );
        fs::remove_file(p).unwrap();
    }
}

#[test]
fn explicit_auto_matches_missing_default_in_actual_development() {
    let original=temp("default.dng"); let explicit=temp("auto.dng");
    let a=temp("default.f32"); let b=temp("auto.f32");
    fs::write(&original,base()).unwrap(); fs::write(&explicit,tagged(4,&[0])).unwrap();
    for (i,o) in [(&original,&a),(&explicit,&b)] {
        decode(i,o,&Budget::default(),&AtomicBool::new(false)).unwrap();
    }
    assert_eq!(fs::read(&a).unwrap(),fs::read(&b).unwrap());
    for p in [original,explicit,a,b] { fs::remove_file(p).unwrap(); }
}
