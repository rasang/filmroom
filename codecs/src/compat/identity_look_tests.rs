//! Identity profile-look metadata is admitted only after full, bounded validation.
use super::unsupported_processing;
use crate::tiff::{Endian, Ifd, Value};

fn words(typ: u16, values: &[u32], endian: Endian) -> Value {
    let bytes = values.iter().flat_map(|v| match endian {
        Endian::Le => v.to_le_bytes(),
        Endian::Be => v.to_be_bytes(),
    }).collect();
    Value { typ, count: values.len() as u32, bytes, endian }
}
fn table(dims: [u32; 3], endian: Endian, encoding: Option<u32>) -> Ifd {
    let mut root = Ifd::new();
    root.insert(50981, words(4, &dims, endian));
    let entries = dims.iter().map(|&n| n as usize).product::<usize>();
    let payload: Vec<u32> = (0..entries).flat_map(|_| [0f32.to_bits(), 1f32.to_bits(), 1f32.to_bits()]).collect();
    root.insert(50982, words(11, &payload, endian));
    if let Some(n) = encoding { root.insert(51108, words(4, &[n], endian)); }
    root
}
fn rejected(root: &Ifd) {
    assert!(unsupported_processing(root, root).is_err());
    assert!(unsupported_processing(root, &Ifd::new()).is_err());
}

#[test]
fn identity_look_accepts_le_be_and_default_linear_encoding() {
    for endian in [Endian::Le, Endian::Be] {
        for encoding in [None, Some(0)] {
            let root = table([3, 2, 1], endian, encoding);
            assert!(unsupported_processing(&root, &root).is_ok());
            assert!(unsupported_processing(&root, &Ifd::new()).is_ok());
        }
    }
    let empty = Ifd::new();
    assert!(unsupported_processing(&empty, &empty).is_ok());
}

#[test]
fn identity_look_rejects_bad_dimensions_and_checked_product_overflow() {
    for dims in [[0, 2, 1], [3, 0, 1], [3, 1, 1], [3, 2, 0],
                 [u32::MAX, u32::MAX, u32::MAX], [u32::MAX, 2, 1]] {
        let mut root = table([3, 2, 1], Endian::Le, Some(0));
        root.insert(50981, words(4, &dims, Endian::Le));
        rejected(&root);
    }
    for typ in [1, 3, 9, 11] {
        let mut root = table([3, 2, 1], Endian::Le, Some(0));
        root.get_mut(&50981).unwrap().typ = typ;
        rejected(&root);
    }
    for count in [0, 2, 4, u32::MAX] {
        let mut root = table([3, 2, 1], Endian::Le, Some(0));
        root.get_mut(&50981).unwrap().count = count;
        rejected(&root);
    }
}

#[test]
fn identity_look_rejects_missing_pairs_and_encoding_without_table() {
    for tag in [50981, 50982] {
        for encoding in [None, Some(0)] {
            let mut root = table([3, 2, 1], Endian::Le, encoding);
            root.remove(&tag);
            rejected(&root);
        }
    }
    let mut root = Ifd::new();
    root.insert(51108, words(4, &[0], Endian::Le));
    rejected(&root);
}

#[test]
fn identity_look_rejects_non_linear_unknown_and_malformed_encoding() {
    for n in [1, 2, u32::MAX] {
        rejected(&table([3, 2, 1], Endian::Le, Some(n)));
    }
    for typ in [1, 3, 9, 11] {
        let mut root = table([3, 2, 1], Endian::Le, Some(0));
        root.get_mut(&51108).unwrap().typ = typ;
        rejected(&root);
    }
    for count in [0, 2, u32::MAX] {
        let mut root = table([3, 2, 1], Endian::Le, Some(0));
        root.get_mut(&51108).unwrap().count = count;
        rejected(&root);
    }
}

#[test]
fn identity_look_rejects_nonidentity_and_nonfinite_at_every_component() {
    for endian in [Endian::Le, Endian::Be] {
        for component in 0..3 {
            for replacement in [0.25f32, f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
                let mut root = table([3, 2, 1], endian, Some(0));
                // Last entry makes validation of the entire payload observable.
                let pos = (15 + component) * 4;
                let bytes = match endian {
                    Endian::Le => replacement.to_le_bytes(),
                    Endian::Be => replacement.to_be_bytes(),
                };
                root.get_mut(&50982).unwrap().bytes[pos..pos + 4].copy_from_slice(&bytes);
                rejected(&root);
            }
        }
    }
}

#[test]
fn identity_look_requires_exact_float_type_count_and_payload_lengths() {
    for typ in [1, 4, 9, 12] {
        let mut root = table([3, 2, 1], Endian::Le, Some(0));
        root.get_mut(&50982).unwrap().typ = typ;
        rejected(&root);
    }
    for count in [0, 17, 19, u32::MAX] {
        let mut root = table([3, 2, 1], Endian::Le, Some(0));
        root.get_mut(&50982).unwrap().count = count;
        rejected(&root);
    }
    for tag in [50981, 50982, 51108] {
        for append in [false, true] {
            let mut root = table([3, 2, 1], Endian::Le, Some(0));
            let bytes = &mut root.get_mut(&tag).unwrap().bytes;
            if append { bytes.push(0); } else { bytes.pop(); }
            rejected(&root);
        }
    }
}

#[test]
fn identity_look_is_ifd0_only_and_never_accepts_raw_overrides() {
    let root = table([3, 2, 1], Endian::Le, Some(0));
    assert!(unsupported_processing(&root, &root).is_ok());
    // Distinct RAW IFD with identical values is still an override.
    assert!(unsupported_processing(&root, &root.clone()).is_err());
    assert!(unsupported_processing(&Ifd::new(), &root).is_err());
    for tag in [50981, 50982, 51108] {
        let mut raw = Ifd::new();
        raw.insert(tag, root[&tag].clone());
        assert!(unsupported_processing(&root, &raw).is_err());
        assert!(unsupported_processing(&Ifd::new(), &raw).is_err());
        let mut split = root.clone();
        split.remove(&tag);
        assert!(unsupported_processing(&split, &raw).is_err());
    }
}

#[test]
fn identity_look_enforces_64kib_payload_bound() {
    // 2,730 * 2 * 1 * 3 * 4 = 65,520 bytes, the largest shape of
    // this form below 64 KiB; the next shape is 65,544 bytes.
    let largest = table([2730, 2, 1], Endian::Le, Some(0));
    assert_eq!(largest[&50982].bytes.len(), 65_520);
    assert!(unsupported_processing(&largest, &largest).is_ok());
    let oversized = table([2731, 2, 1], Endian::Le, Some(0));
    assert_eq!(oversized[&50982].bytes.len(), 65_544);
    rejected(&oversized);
    let mut descriptor_only = table([3, 2, 1], Endian::Le, Some(0));
    descriptor_only.get_mut(&50982).unwrap().bytes.clear();
    rejected(&descriptor_only);
}
