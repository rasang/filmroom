use crate::tiff::Ifd;

/// Brand-independent tolerance for an otherwise valid, omitted SDR tone curve.
/// Vivo/Xiaomi samples motivated the policy, but Make/Model do not select it.
/// 0.997 is an explicit maximum 0.3% white-end deficit policy, NOT an assertion
/// that this is floating-point roundoff or that the file meets the SDR spec.
/// Explicit ProfileDynamicRange declarations are outside this exception.
/// Caller retains shape/type/range/x-ordering and (0,0)/final-x checks.
/// This never normalizes, applies or rewrites the curve or source file.
pub(crate) fn allows_subunity_tone_endpoint(
    root: &Ifd,
    raw: &Ifd,
    endpoint: f32,
    previous: f32,
) -> bool {
    // Keep the validated IFD0-only profile scope; do not resolve competing curves.
    !root.contains_key(&52551)
        && !raw.contains_key(&52551)
        && root.contains_key(&50940)
        && (std::ptr::eq(root, raw) || !raw.contains_key(&50940))
        && endpoint > previous
        && endpoint.is_finite()
        && (0.997..1.0).contains(&endpoint)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tiff::{Endian, Value};
    fn root() -> Ifd {
        let mut root = Ifd::new();
        root.insert(50940, Value { typ: 11, count: 4, bytes: vec![0; 16], endian: Endian::Le });
        root
    }
    #[test]
    fn compatibility_is_independent_of_make_or_missing_make() {
        let mut r = root();
        assert!(allows_subunity_tone_endpoint(&r, &r, 0.997156024, 0.97));
        for make in ["vivo", "Xiaomi", "OPPO", "Samsung", "Unknown"] {
            let bytes = format!("{make}\0").into_bytes();
            r.insert(271, Value { typ: 2, count: bytes.len() as u32, bytes, endian: Endian::Le });
            assert!(allows_subunity_tone_endpoint(&r, &r, 0.997156024, 0.97));
        }
    }
    #[test]
    fn exception_remains_bounded_and_ifd0_only() {
        let mut r = root();
        for endpoint in [0.9969, 1., f32::NAN, f32::INFINITY] {
            assert!(!allows_subunity_tone_endpoint(&r, &r, endpoint, 0.97));
        }
        assert!(!allows_subunity_tone_endpoint(&r, &r, 0.9972, 0.9972));
        assert!(allows_subunity_tone_endpoint(&r, &Ifd::new(), 0.9972, 0.5));
        assert!(!allows_subunity_tone_endpoint(&r, &root(), 0.9972, 0.5));
        r.insert(52551, Value { typ: 4, count: 1, bytes: 0u32.to_le_bytes().to_vec(), endian: Endian::Le });
        assert!(!allows_subunity_tone_endpoint(&r, &r, 0.9972, 0.5));
        assert!(!allows_subunity_tone_endpoint(&Ifd::new(), &Ifd::new(), 0.9972, 0.5));
    }
}
