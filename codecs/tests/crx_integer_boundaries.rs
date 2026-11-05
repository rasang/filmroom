#[path = "../vendor/rawler-0.7.2/src/decompressors/crx/bounded_math.rs"]
mod math;
#[test]
fn quantization_extremes_fail_instead_of_profile_dependent_wrap() {
    assert_eq!(math::scaled(4095, 256), Some(1_048_320));
    assert_eq!(math::scaled(i32::MAX, 2), None);
    assert_eq!(math::scaled(i32::MIN, 2), None);
    assert_eq!(
        math::quantized(1, i32::MAX, u32::MAX, u32::MAX),
        Some(0x168000)
    );
    assert_eq!(math::quantized(i32::MAX, 0, u32::MAX, u32::MAX), None);
    assert_eq!(math::quantized(-1, i32::MIN, 0, 0), Some(-1));
}
#[test]
fn color_transform_uses_wide_math_at_hostile_and_nominal_limits() {
    for bits in [12, 14, 16] {
        let median = 1u16 << (bits - 1);
        let max = ((1u32 << bits) - 1) as u16;
        assert_eq!(math::planes(bits, 0, [0; 4]), Some([median; 4]));
        assert_eq!(math::planes(bits, 3, [0; 4]), Some([median; 4]));
        assert_eq!(
            math::planes(bits, 0, [i32::MAX, i32::MIN, 0, 1]),
            Some([max, 0, median, median + 1])
        );
        assert!(math::planes(bits, 3, [i32::MAX, i32::MIN, i32::MAX, i32::MIN]).is_some());
    }
    assert_eq!(math::planes(31, 3, [0; 4]), None);
    assert_eq!(math::planes(14, 255, [0; 4]), None);
}
