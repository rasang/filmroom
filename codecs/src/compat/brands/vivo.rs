use crate::tiff::Ifd;

/// Narrow admission for a nonstandard SDR profile curve produced by the validated vivo and Xiaomi samples.
/// The observed vivo file ends at (1, 0.9971559047698975), not the DNG-required (1, 1).
/// 0.997 is an explicit maximum 0.3% white-end deficit policy, NOT an assertion
/// that this is floating-point roundoff or that the file meets the SDR spec.
/// Explicit ProfileDynamicRange declarations are outside this exception.
/// Caller must retain all shape/type/range/monotonicity and (0,0)/final-x checks.
/// The Xiaomi sample ends at 0.997156023979187. This only permits the
/// already-omitted camera curve to be ignored with the
/// existing warning. It never rewrites the file, normalizes or applies a curve.
/// Brand admission is deliberately independent of Model and CFA layout.
pub(crate) fn allows_subunity_tone_endpoint(
    root: &Ifd,
    raw: &Ifd,
    endpoint: f32,
    previous: f32,
) -> bool {
    // Do not extend this exception to raw-SubIFD overrides/conflicting metadata.
    !root.contains_key(&52551)
        && !raw.contains_key(&52551)
        && root.contains_key(&50940)
        && (std::ptr::eq(root, raw) || (!raw.contains_key(&50940) && !raw.contains_key(&271)))
        && endpoint > previous
        && endpoint.is_finite()
        && (0.997..1.0).contains(&endpoint)
        && root.get(&271).is_some_and(|v| {
            v.typ == 2
                && v.count > 0
                && v.count <= 128
                && v.bytes.len() == v.count as usize
                && v.bytes.last() == Some(&0)
                && [b"vivo".as_slice(), b"xiaomi".as_slice()].iter().any(|make|
                    v.bytes[..v.bytes.len() - 1].eq_ignore_ascii_case(make))
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tiff::{Endian, Value};
    #[test]
    fn exception_is_ifd0_only_and_rejects_raw_overrides() {
        let value = Value {
            typ: 2,
            count: 5,
            bytes: b"vivo\0".to_vec(),
            endian: Endian::Le,
        };
        let curve = Value {
            typ: 11,
            count: 4,
            bytes: vec![0; 16],
            endian: Endian::Le,
        };
        let mut root = Ifd::new();
        root.insert(271, value.clone());
        root.insert(50940, curve.clone());
        let mut raw = Ifd::new();
        assert!(allows_subunity_tone_endpoint(&root, &raw, 0.9972, 0.5));
        raw.insert(50940, curve);
        assert!(!allows_subunity_tone_endpoint(&root, &raw, 0.9972, 0.5));
        raw.clear();
        raw.insert(271, value);
        assert!(!allows_subunity_tone_endpoint(&root, &raw, 0.9972, 0.5));
        assert!(!allows_subunity_tone_endpoint(&root, &root, 0.9972, 0.9972));
    }
    #[test]
    fn xiaomi_sample_and_boundaries() {
        let mut root=Ifd::new();
        root.insert(271,Value{typ:2,count:7,bytes:b"Xiaomi\0".to_vec(),endian:Endian::Le});
        root.insert(50940,Value{typ:11,count:64,bytes:vec![0;256],endian:Endian::Le});
        assert!(allows_subunity_tone_endpoint(&root,&root,0.997156024,0.97112298));
        for endpoint in [0.9969, 1., f32::NAN, f32::INFINITY] {
            assert!(!allows_subunity_tone_endpoint(&root,&root,endpoint,0.97));
        }
        root.insert(52551,Value{typ:4,count:1,bytes:0u32.to_le_bytes().to_vec(),endian:Endian::Le});
        assert!(!allows_subunity_tone_endpoint(&root,&root,0.997156024,0.97));
    }
    #[test]
    fn make_descriptor_must_be_exact_bounded_ascii() {
        for (typ, count, bytes) in [
            (2, 5, b"vivo\0".to_vec()),
            (2, 4, b"vivo\0".to_vec()),
            (7, 5, b"vivo\0".to_vec()),
            (2, 0, vec![]),
            (2, 129, vec![b'v'; 129]),
            (2, 5, b"vivoX".to_vec()),
            (2, 6, b"vivo\0\0".to_vec()),
        ] {
            let mut root = Ifd::new();
            root.insert(
                271,
                Value {
                    typ,
                    count,
                    bytes: bytes.clone(),
                    endian: Endian::Le,
                },
            );
            root.insert(
                50940,
                Value {
                    typ: 11,
                    count: 4,
                    bytes: vec![0; 16],
                    endian: Endian::Le,
                },
            );
            assert_eq!(
                allows_subunity_tone_endpoint(&root, &root, 0.9971559, 0.5),
                typ == 2 && count == 5 && bytes == b"vivo\0"
            );
        }
        assert!(!allows_subunity_tone_endpoint(
            &Ifd::new(),
            &Ifd::new(),
            0.9971559,
            0.5
        ));
    }
}
