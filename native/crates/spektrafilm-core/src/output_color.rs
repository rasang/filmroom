//! Linear D65 output-space transforms. Transfers/ICC are owned by codecs.
//! P3 and Adobe matrices are derived from their published RGB chromaticities and D65 (0.3127,0.3290).
pub fn to_linear_srgb(rgb: [f64; 3], space: &str) -> [f64; 3] {
    let to_xyz = match space {
        "Display P3" => P3_TO_XYZ,
        "Adobe RGB (1998)" => ADOBE_TO_XYZ,
        _ => return rgb,
    };
    let xyz = mul(to_xyz, rgb);
    mul(spektrafilm_math::colorspace::XYZ_TO_SRGB_F64, xyz)
}
pub fn mul(m: [[f64; 3]; 3], v: [f64; 3]) -> [f64; 3] {
    std::array::from_fn(|i| m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2])
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn inverse_and_neutral() {
        for (a, b) in [(P3_TO_XYZ, XYZ_TO_P3), (ADOBE_TO_XYZ, XYZ_TO_ADOBE)] {
            for rgb in [
                [1., 0., 0.],
                [0., 1., 0.],
                [0., 0., 1.],
                [0.18, 0.18, 0.18],
                [0.7, 0.2, 0.04],
            ] {
                let q = mul(b, mul(a, rgb));
                for i in 0..3 {
                    assert!((q[i] - rgb[i]).abs() < 1e-12);
                }
            }
            let white = mul(a, [1.; 3]);
            assert!((white[1] - 1.).abs() < 1e-12);
        }
    }
    #[test]
    fn srgb_is_exact_noop() {
        let p = [0.23, 0.17, 0.91];
        assert_eq!(to_linear_srgb(p, "sRGB"), p);
    }
    #[test]
    fn wide_red_preview_is_not_raw_red() {
        for s in ["Display P3", "Adobe RGB (1998)"] {
            let p = to_linear_srgb([1., 0., 0.], s);
            assert!(p[0] > 1.);
            assert!(p[1].abs() > 0.00001 || p[2].abs() > 0.00001);
        }
    }
}

pub use spektrafilm_math::colorspace::{ADOBE_TO_XYZ, P3_TO_XYZ, XYZ_TO_ADOBE, XYZ_TO_P3};
